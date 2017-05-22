package com.example.android.camera2basic.camera2;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v13.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.WindowManager;

import com.example.android.camera2basic.Asserts;
import com.example.android.camera2basic.StateMachine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by grigory on 16.05.17.
 */

public class Camera2Device implements AutoCloseable
{
    private static final String TAG = Camera2Device.class.getSimpleName();

    private static final int MAX_BRACKETS = 10;

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    public static class CameraException extends Exception
    {
        CameraException(String what)
        {
            super(what);
        }
    }

    public static class Bracket
    {
        private final long exposure;
        private final int iso;

        public Bracket(long exposure, int iso)
        {
            this.exposure = exposure;
            this.iso = iso;
        }

        public long getExposure()
        {
            return exposure;
        }

        public int getIso()
        {
            return iso;
        }
    }

    public interface Listener
    {
        void onCameraCharacteristics(@NonNull Range<Long> exposureRange, @NonNull Range<Integer> sensitivityRange);
        void onReady();
        void onImageAvailable(@NonNull Image image);
    }

    private final StateMachine controller = new StateMachine();

    private enum States { PREPARE, READY, TAKING_PICTURE, SHUTDOWN }

    private final Context context;

    @Nullable
    private String cameraId;

    @Nullable
    private HandlerThread backgroundThread;

    @Nullable
    private Handler backgroundHandler;

    @Nullable
    private ImageReader imageReader;

    private final Semaphore cameraOpenCloseLock = new Semaphore(1); // todo: we really need dat?

    @Nullable
    private CameraDevice cameraDevice;

    @Nullable
    private CameraCaptureSession captureSession;

    private final List<CaptureRequest> captureRequests = new ArrayList<>();

    private int sensorOrientation;

    // ------------------------- +Controller -------------------------

    private static final int REQUEST_CAMERA_PERMISSION = 123;

    public Camera2Device(@NonNull final Context context)
    {
        Log.d(TAG, "Camera2Device() called with: context = [" + context + "]");
        this.context = context;
        startBackgroundThread();
    }

    public void prepare(@NonNull final Listener listener)
    {
        Log.d(TAG, "prepare() called with: listener = [" + listener + "]");
        controller.addState(States.PREPARE, new StateMachine.State()
        {
            @Override
            protected void onEnter(@NonNull StateMachine parent)
            {
                Log.i(TAG, "onEnter: PREPARE");
                selectCamera(listener);
                openCamera();
            }
        });
        controller.addState(States.READY, new StateMachine.State()
        {
            @Override
            protected void onEnter(@NonNull StateMachine parent)
            {
                Log.i(TAG, "onEnter: READY");
                listener.onReady();
            }
        });
        controller.addState(States.TAKING_PICTURE, new StateMachine.State()
        {
            @Override
            protected void onEnter(@NonNull StateMachine parent)
            {
                Log.i(TAG, "onEnter: TAKING_PICTURE");
            }

            private final ArrayList<Bracket> clazz = new ArrayList<>();

            @Override
            protected void onEvent(@NonNull StateMachine parent, @NonNull Object event)
            {
               if (event.getClass().equals(clazz.getClass())) {
                   ArrayList<Bracket> casted = (ArrayList<Bracket>) event;
                   captureBurst(casted);
               }
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
            }
        });
        controller.switchState(States.PREPARE);
    }

    @Override
    public void close() throws Exception
    {
        Log.d(TAG, "close() called");
        controller.switchState(States.SHUTDOWN);
    }

    // ------------------------- +Interface -------------------------

    public static void requestCameraPermissions(@NonNull Activity activity)
    {
        Log.d(TAG, "requestCameraPermissions() called with: activity = [" + activity + "]");
        ActivityCompat.requestPermissions(activity, new String[]{ Manifest.permission.CAMERA }, REQUEST_CAMERA_PERMISSION);
    }

    public static boolean onPermissionsResult(int requestCode, String[] permissions, int[] grantResults) throws CameraException
    {
        Log.d(TAG, "onPermissionsResult() called with: requestCode = [" + requestCode + "], permissions = [" + Arrays.toString(permissions) + "], grantResults = [" + Arrays.toString(grantResults) + "]");
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    throw new CameraException("Permission not granted!");
                }
            }
            return true;
        } else {
            return false;
        }
    }

    public void performBracketing(@NonNull ArrayList<Bracket> brackets)
    {
        Log.d(TAG, "performBracketing() called");
        Asserts.assertTrue(brackets.size() < MAX_BRACKETS, "brackets.size() < MAX_BRACKETS");
        Asserts.assertTrue(controller.isInState(States.READY), "controller.isInState(States.READY)");
        Asserts.assertNotNull(imageReader, "imageReader != null");
        controller.switchState(States.TAKING_PICTURE);
        controller.sendEvent(brackets);
    }

    // ------------------------- +Business -------------------------

    private void startBackgroundThread()
    {
        Log.d(TAG, "startBackgroundThread() called");
        Asserts.assertNull(backgroundThread, "backgroundThread == null");
        Asserts.assertNull(backgroundHandler, "backgroundHandler == null");
        backgroundThread = new HandlerThread("Camera2Background");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread()
    {
        Log.d(TAG, "stopBackgroundThread() called");
        Asserts.assertNotNull(backgroundThread, "backgroundThread != null");
        backgroundThread.quitSafely();
        try {
            backgroundThread.join();
            backgroundHandler = null;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Nullable
    private Range<Long> exposureRange;

    @Nullable
    private Range<Integer> sensitivityRange;

    private void selectCamera(@NonNull final Listener listener)
    {
        Log.d(TAG, "selectCamera() called");
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            final String[] cameraIdList = manager.getCameraIdList();
            for (String cameraId : cameraIdList) {
                Asserts.assertNotNull(cameraId, "cameraId != null");
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                Asserts.assertNotNull(exposureRange, "exposureRange != null");
                sensitivityRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                Asserts.assertNotNull(sensitivityRange, "sensitivityRange != null");
                listener.onCameraCharacteristics(exposureRange, sensitivityRange);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }
                Integer boxedOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                Asserts.assertNotNull(boxedOrientation, "boxedOrientation != null");
                sensorOrientation = boxedOrientation;
                Size largest = Collections.max(
                        Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
                        new Camera2Device.CompareSizesByArea()
                );
                imageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(), ImageFormat.JPEG, MAX_BRACKETS);
                Asserts.assertNotNull(imageReader, "imageReader != null");
                imageReader.setOnImageAvailableListener(
                        new ImageReader.OnImageAvailableListener()
                        {
                            @Override
                            public void onImageAvailable(ImageReader reader)
                            {
                                listener.onImageAvailable(reader.acquireLatestImage());
                            }
                        },
                        backgroundHandler
                );
                Camera2Device.this.cameraId = cameraId;
                break;
            }
        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        }
        Asserts.assertNotNull(cameraId, "cameraId != null");
    }

    private final CameraDevice.StateCallback deviceStateCallback = new CameraDevice.StateCallback()
    {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice)
        {
            cameraOpenCloseLock.release();
            Camera2Device.this.cameraDevice = cameraDevice;
            createCaptureSession();
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

    private void openCamera()
    {
        Log.d(TAG, "openCamera() called");
        Asserts.assertNotNull(cameraId, "cameraId != null");
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
        Log.d(TAG, "closeCamera() called");
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
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing", e);
        } finally {
            cameraOpenCloseLock.release();
        }
    }

    private void createCaptureSession()
    {
        Log.d(TAG, "createCaptureSession() called");
        Asserts.assertNotNull(cameraDevice, "cameraDevice != null");
        Asserts.assertNotNull(imageReader, "imageReader != null");
        try {
            cameraDevice.createCaptureSession(
                Collections.singletonList(imageReader.getSurface()),
                new CameraCaptureSession.StateCallback()
                {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession)
                    {
                        Asserts.assertNull(captureSession, "captureSession == null");
                        captureSession = cameraCaptureSession;
                        controller.switchState(States.READY);
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession)
                    {
                        throw new RuntimeException("createCaptureSession failed!");
                    }
                },
                null
            );
        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private CaptureRequest.Builder createCaptureRequestBuilder()
    {
        Log.d(TAG, "createCaptureRequestBuilder() called");
        Asserts.assertNotNull(cameraDevice, "cameraDevice != null");
        Asserts.assertNotNull(imageReader, "imageReader != null");
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        final int rotation = windowManager.getDefaultDisplay().getRotation();
        CaptureRequest.Builder captureBuilder;
        try {
            captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        }
        captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(rotation));
        captureBuilder.addTarget(imageReader.getSurface());
        return captureBuilder;
    }

    private void updateCaptureRequests(@NonNull ArrayList<Bracket> brackets)
    {
        Log.d(TAG, "updateCaptureRequests() called with: brackets = [" + brackets + "]");
        Asserts.assertNotNull(exposureRange, "exposureRange != null");
        Asserts.assertNotNull(sensitivityRange, "sensitivityRange != null");
        captureRequests.clear();
        CaptureRequest.Builder captureRequestBuilder = createCaptureRequestBuilder();
        for (Bracket bracket : brackets) {
            Asserts.assertTrue(bracket.getExposure() <= exposureRange.getUpper() && bracket.getExposure() >= exposureRange.getLower(), "bracket.getExposure() < exposureRange.getUpper() && bracket.getExposure() > exposureRange.getLower()");
            Asserts.assertTrue(bracket.getIso() <= sensitivityRange.getUpper() && bracket.getIso() >= sensitivityRange.getLower(), "bracket.getIso() < sensitivityRange.getUpper() && bracket.getIso() > sensitivityRange.getLower()");
            captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF);
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            captureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, bracket.getExposure()); // https://developer.android.com/reference/android/hardware/camera2/CaptureRequest.html#SENSOR_EXPOSURE_TIME
            captureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, bracket.getIso()); // https://developer.android.com/reference/android/hardware/camera2/CaptureRequest.html#SENSOR_SENSITIVITY
            captureRequests.add(captureRequestBuilder.build());
        }
    }

    private void captureBurst(@NonNull final ArrayList<Bracket> brackets)
    {
        Log.d(TAG, "captureBurst() called");
        updateCaptureRequests(brackets);
        Asserts.assertNotNull(captureSession, "captureSession != null");
        Asserts.assertTrue(!captureRequests.isEmpty(), "!captureRequests.isEmpty()");
        final AtomicInteger frame = new AtomicInteger(0);
        try {
            captureSession.captureBurst(
                    captureRequests,
                    new CameraCaptureSession.CaptureCallback()
                    {
                        @Override
                        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                                       @NonNull CaptureRequest request,
                                                       @NonNull TotalCaptureResult result)
                        {
                            if (frame.incrementAndGet() == brackets.size()) {
                                controller.switchState(States.READY);
                            }
                        }
                    },
                    backgroundHandler
            );
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
        return (ORIENTATIONS.get(rotation) + sensorOrientation + 270) % 360;
    }

    // ------------------------- +Auxiliary -------------------------

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
