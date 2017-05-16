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
import android.hardware.camera2.CameraMetadata;
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
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.WindowManager;

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

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

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

    private int mSensorOrientation;

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

    private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback()
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
                    } else if (
                        CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                        CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState
                    ) {
                        Integer aeState = casted.get(CaptureResult.CONTROL_AE_STATE);
                        if (
                            aeState == null ||
                            aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED
                        ) {
                            controller.switchState(States.PICTURE_TAKEN);
                            captureStillPicture();
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
                    Integer aeState = casted.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        controller.switchState(States.PICTURE_TAKEN);
                        captureStillPicture();
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
                Integer boxedOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                if (boxedOrientation == null) throw new RuntimeException("Assertation failed: boxedOrientation == null");
                mSensorOrientation = boxedOrientation;
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
            surfaceTexture.setDefaultBufferSize(PREVIEW_SIZE.getWidth(), PREVIEW_SIZE.getHeight());
            Surface surface = new Surface(surfaceTexture);
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);
            cameraDevice.createCaptureSession(
                    Arrays.asList(surface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback()
                    {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession)
                        {
                            if (cameraDevice == null) {
                                return;
                            }
                            captureSession = cameraCaptureSession;
                            try {
                                previewRequestBuilder.set(
                                        CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                );
                                previewRequest = previewRequestBuilder.build();
                                captureSession.setRepeatingRequest(previewRequest, captureCallback, backgroundHandler);
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

    private void takePicture()
    {
        lockFocus();
    }

    private void lockFocus()
    {
        if (previewRequestBuilder == null) throw new RuntimeException("Assertation failed: previewRequestBuilder == null");
        if (captureSession == null) throw new RuntimeException("Assertation failed: captureSession == null");
        try {
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
            controller.switchState(States.WAITING_LOCK);
            captureSession.capture(previewRequestBuilder.build(), captureCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private void runPrecaptureSequence()
    {
        if (previewRequestBuilder == null) throw new RuntimeException("Assertation failed: previewRequestBuilder == null");
        if (captureSession == null) throw new RuntimeException("Assertation failed: captureSession == null");
        try {
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            controller.switchState(States.WAITING_PRECAPTURE);
            captureSession.capture(previewRequestBuilder.build(), captureCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private void captureStillPicture()
    {
        if (cameraDevice == null) throw new RuntimeException("Assertation failed: cameraDevice == null");
        if (imageReader == null) throw new RuntimeException("Assertation failed: imageReader == null");
        if (captureSession == null) throw new RuntimeException("Assertation failed: captureSession == null");
        try {
            final CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            final int rotation = windowManager.getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(rotation));
            CameraCaptureSession.CaptureCallback CaptureCallback = new CameraCaptureSession.CaptureCallback()
            {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result)
                {
                    unlockFocus();
                }
            };
            captureSession.stopRepeating();
            captureSession.capture(captureBuilder.build(), CaptureCallback, null);
        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private int getOrientation(int rotation)
    {
        // Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
        // We have to take that into account and rotate JPEG properly.
        // For devices with orientation of 90, we simply return our mapping from ORIENTATIONS.
        // For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
        return (ORIENTATIONS.get(rotation) + mSensorOrientation + 270) % 360;
    }

    private void unlockFocus()
    {
        if (previewRequest == null) throw new RuntimeException("Assertation failed: previewRequest == null");
        if (captureSession == null) throw new RuntimeException("Assertation failed: captureSession == null");
        if (previewRequestBuilder == null) throw new RuntimeException("Assertation failed: previewRequestBuilder == null");
        try {
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            captureSession.capture(previewRequestBuilder.build(), captureCallback, backgroundHandler);
            controller.switchState(States.READY);
            captureSession.setRepeatingRequest(previewRequest, captureCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
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
