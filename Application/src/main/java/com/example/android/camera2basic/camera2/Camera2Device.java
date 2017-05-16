package com.example.android.camera2basic.camera2;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import com.example.android.camera2basic.StateMachine;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Created by grigory on 16.05.17.
 */

public class Camera2Device implements AutoCloseable
{
    private static final String TAG = Camera2Device.class.getSimpleName();

    private final StateMachine controller = new StateMachine();

    private enum States
    {
        SELECT_CAMERA, STARTUP, READY,
        WAITING_LOCK, WAITING_PRECAPTURE, WAITING_NON_PRECAPTURE, PICTURE_TAKEN,
        SHUTDOWN
    }

    private final Context context;

    @Nullable
    private String cameraId;

    @Nullable
    private HandlerThread backgroundThread;

    @Nullable
    private Handler backgroundHandler;

    @Nullable
    private ImageReader imageReader;

    @Nullable
    private SurfaceTexture surfaceTexture;

    private final Semaphore cameraOpenCloseLock = new Semaphore(1);

    @Nullable
    private CameraDevice cameraDevice;

    @Nullable
    private CameraCaptureSession captureSession;

    @Nullable
    private CaptureRequest.Builder previewRequestBuilder;

    @Nullable
    private CaptureRequest previewRequest;

    private static final Size PREVIEW_SIZE = new Size(200, 200);

    // ------------------------- Internal state -------------------------

    private final ImageReader.OnImageAvailableListener onImageAvailableListener = new ImageReader.OnImageAvailableListener()
    {
        @Override
        public void onImageAvailable(ImageReader reader)
        {
            // todo save it
            //mBackgroundHandler.post(new Camera2BasicFragment.ImageSaver(reader.acquireNextImage(), mFile));
        }
    };

    private final CameraDevice.StateCallback deviceStateCallback = new CameraDevice.StateCallback()
    {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice)
        {
            // This method is called when the camera is opened.  We start camera preview here.
            cameraOpenCloseLock.release();
            Camera2Device.this.cameraDevice = cameraDevice;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice)
        {
            cameraOpenCloseLock.release();
            cameraDevice.close();
            Camera2Device.this.cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error)
        {
            cameraOpenCloseLock.release();
            cameraDevice.close();
            Camera2Device.this.cameraDevice = null;
            throw new RuntimeException("Camera error: " + error);
        }
    };

    private final CameraCaptureSession.CaptureCallback mCaptureCallback = new CameraCaptureSession.CaptureCallback()
    {
        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull CaptureResult partialResult)
        {
            controller.sendEvent(partialResult);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result)
        {
            controller.sendEvent(result);
        }
    };

    // ------------------------- Lifecycle -------------------------

    Camera2Device(@NonNull final Context context)
    {
        this.context = context;
        controller.addState(States.SELECT_CAMERA, new StateMachine.State()
        {
            @Override
            protected void onEnter(@NonNull StateMachine parent)
            {
                Log.i(TAG, "onEnter: SELECT_CAMERA");
                selectCamera();
                controller.switchState(States.STARTUP);
            }
        });
        controller.addState(States.STARTUP, new StateMachine.State()
        {
            @Override
            protected void onEnter(@NonNull StateMachine parent)
            {
                Log.i(TAG, "onEnter: STARTUP");
                startBackgroundThread();
                createSurface();
                openCamera();
            }
        });
        controller.addState(States.READY, new StateMachine.State()
        {
            @Override
            protected void onEnter(@NonNull StateMachine parent)
            {
                Log.i(TAG, "onEnter: READY");
            }
        });
        controller.addState(States.WAITING_LOCK, new StateMachine.State()
        {
            @Override
            protected void onEnter(@NonNull StateMachine parent)
            {
                Log.i(TAG, "onEnter: WAITING_LOCK");
            }

            @Override
            protected void onEvent(@NonNull StateMachine parent, @NonNull Object event)
            {
                if (event instanceof CaptureResult) {
                    CaptureResult casted = (CaptureResult) event;
                    Integer afState = casted.get(CaptureResult.CONTROL_AF_STATE);
                    if (afState == null) {
                        captureStillPicture();
                    } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                        // CONTROL_AE_STATE can be null on some devices
                        Integer aeState = casted.get(CaptureResult.CONTROL_AE_STATE);
                        if (
                            aeState == null ||
                            aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED
                        ) {
                            captureStillPicture();
                            controller.switchState(States.PICTURE_TAKEN);
                        } else {
                            runPrecaptureSequence();
                        }
                    }
                }
            }
        });
        controller.addState(States.WAITING_PRECAPTURE, new StateMachine.State()
        {
            @Override
            protected void onEnter(@NonNull StateMachine parent)
            {
                Log.i(TAG, "onEnter: WAITING_PRECAPTURE");
            }

            @Override
            protected void onEvent(@NonNull StateMachine parent, @NonNull Object event)
            {
                if (event instanceof CaptureResult) {
                    CaptureResult casted = (CaptureResult) event;
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = casted.get(CaptureResult.CONTROL_AE_STATE);
                    if (
                        aeState == null ||
                        aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                        aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED
                    ) {
                        controller.switchState(States.WAITING_NON_PRECAPTURE);
                    }
                }
            }
        });
        controller.addState(States.WAITING_NON_PRECAPTURE, new StateMachine.State()
        {
            @Override
            protected void onEnter(@NonNull StateMachine parent)
            {
                Log.i(TAG, "onEnter: WAITING_NON_PRECAPTURE");
            }

            @Override
            protected void onEvent(@NonNull StateMachine parent, @NonNull Object event)
            {
                if (event instanceof CaptureResult) {
                    CaptureResult casted = (CaptureResult) event;
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = casted.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        captureStillPicture();
                        controller.switchState(States.PICTURE_TAKEN);
                    }
                }
            }
        });
        controller.addState(States.PICTURE_TAKEN, new StateMachine.State()
        {
            @Override
            protected void onEnter(@NonNull StateMachine parent)
            {
                Log.i(TAG, "onEnter: PICTURE_TAKEN");
            }
        });
        controller.addState(States.SHUTDOWN, new StateMachine.State()
        {
            @Override
            protected void onEnter(@NonNull StateMachine parent)
            {
                Log.i(TAG, "onEnter: SHUTDOWN");
                closeCamera();
                stopBackgroundThread();
                removeSurface();
            }
        });
        controller.switchState(States.STARTUP);
    }

    @Override
    public void close() throws Exception
    {
        controller.switchState(States.SHUTDOWN);
    }

    // ------------------------- Business -------------------------

    private void startBackgroundThread()
    {
        if (backgroundThread != null) throw new RuntimeException("Assertation failed: backgroundThread != null");
        if (backgroundHandler != null) throw new RuntimeException("Assertation failed: backgroundHandler != null");
        backgroundThread = new HandlerThread("Camera2Background");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread()
    {
        if (backgroundThread == null) throw new RuntimeException("Assertation failed: backgroundThread == null");
        backgroundThread.quitSafely();
        try {
            backgroundThread.join();
            backgroundHandler = null;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void createSurface()
    {
        if (surfaceTexture != null) throw new RuntimeException("Assertation failed: surfaceTexture != null");
        surfaceTexture = new SurfaceTexture(10);
    }

    private void removeSurface()
    {
        if (surfaceTexture == null) throw new RuntimeException("Assertation failed: surfaceTexture == null");
        surfaceTexture.release();
        surfaceTexture = null;
    }

    private void selectCamera()
    {
        if (imageReader != null) throw new RuntimeException("Assertation failed: imageReader != null");
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            String[] cameraIdList = manager.getCameraIdList();
            for (String cameraId : cameraIdList) {
                if (cameraId == null) throw new RuntimeException("Assertation failed: cameraId == null");
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }
                // For still image captures, we use the largest available size.
                Size largest = Collections.max(
                        Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
                        new Camera2Device.CompareSizesByArea()
                );
                imageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(), ImageFormat.JPEG, /*maxImages*/2);
                imageReader.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler);
                Camera2Device.this.cameraId = cameraId;
                break;
            }
        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        }
        if (cameraId == null) throw new RuntimeException("Assertation failed: cameraId == null");
    }

    private void openCamera()
    {
        if (cameraId == null) throw new RuntimeException("Assertation failed: cameraId == null");
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            throw new RuntimeException("No camera2 permissions!");
        }
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            manager.openCamera(cameraId, deviceStateCallback, backgroundHandler);
        } catch (InterruptedException | CameraAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private void closeCamera()
    {
        try {
            cameraOpenCloseLock.acquire();
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing", e);
        } finally {
            cameraOpenCloseLock.release();
        }
    }

    private void createCameraPreviewSession()
    {
        if (cameraDevice == null) throw new RuntimeException("Assertation failed: cameraDevice == null");
        if (imageReader == null) throw new RuntimeException("Assertation failed: imageReader == null");
        if (surfaceTexture == null) throw new RuntimeException("Assertation failed: surfaceTexture == null");
        if (previewRequestBuilder != null) throw new RuntimeException("Assertation failed: previewRequestBuilder != null");
        if (previewRequest != null) throw new RuntimeException("Assertation failed: previewRequest != null");
        try {
            // We configure the size of default buffer to be the size of camera preview we want.
            surfaceTexture.setDefaultBufferSize(PREVIEW_SIZE.getWidth(), PREVIEW_SIZE.getHeight());

            // This is the output Surface we need to start preview.
            Surface surface = new Surface(surfaceTexture);

            // We set up a CaptureRequest.Builder with the output Surface.
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);

            // Here, we create a CameraCaptureSession for camera preview.
            cameraDevice.createCaptureSession(
                    Arrays.asList(surface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback()
                    {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession)
                        {
                            // The camera is already closed
                            if (cameraDevice == null) {
                                return;
                            }

                            // When the session is ready, we start displaying the preview.
                            captureSession = cameraCaptureSession;
                            try {
                                // Auto focus should be continuous for camera preview.
                                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);


                                // Finally, we start displaying the camera preview.
                                previewRequest = previewRequestBuilder.build();
                                captureSession.setRepeatingRequest(previewRequest, mCaptureCallback, backgroundHandler);
                            } catch (CameraAccessException e) {
                                throw new RuntimeException(e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession)
                        {
                            throw new RuntimeException("On configuration failed!");
                        }
                    },
                    null
            );
        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        }
    }

    // ------------------------- Auxiliary -------------------------

    private static class CompareSizesByArea implements Comparator<Size>
    {
        @Override
        public int compare(Size lhs, Size rhs)
        {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }
}
