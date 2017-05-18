package com.example.android.camera2basic.camera2;

import android.app.Activity;
import android.media.Image;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.widget.Toast;

/**
 * Created by grigory on 17.05.17.
 */

public class Camera2Activity extends Activity
{
    private static final String TAG = Camera2Device.class.getSimpleName();

    @Nullable
    private Camera2Device device;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        Camera2Device.requestCameraPermissions(this);
    }

    private long started;
    private int bracket = 0;

    private final Camera2Device.Listener listener = new Camera2Device.Listener()
    {
        @Override
        public void onReady()
        {
            if (device == null) throw new RuntimeException("Assertation failed: device == null");
            started = System.currentTimeMillis();
            device.performBracketing();
            Log.i(TAG, "onReady: starting");
        }

        @Override
        public void onImageAvailable(@NonNull Image image)
        {
            Log.i(TAG, "onImageAvailable: " + image);
            bracket++;
            if (bracket == 5) {
                Log.i(TAG, "onImageAvailable: took " + (System.currentTimeMillis() - started) / 1000.0);
            }
        }
    };

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults)
    {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        try {
            if (Camera2Device.onPermissionsResult(requestCode, permissions, grantResults)) {
                device = new Camera2Device(this);
                device.prepare(listener);
            }
        } catch (Camera2Device.CameraException e) {
            Toast.makeText(this, "Permissions :(", Toast.LENGTH_LONG).show();
            finish();
        }
    }
}
