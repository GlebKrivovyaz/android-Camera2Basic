package com.example.android.camera2basic.camera2;

import android.app.Activity;
import android.media.Image;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import com.example.android.camera2basic.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Created by grigory on 17.05.17.
 */

public class Camera2Activity extends Activity
{
    private static final String TAG = Camera2Device.class.getSimpleName();

    @Nullable
    private Camera2Device device;

    @Nullable
    private Button buttonBurst;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_burst);
        buttonBurst = (Button) findViewById(R.id.btn_burst);
        if (buttonBurst == null) throw new RuntimeException("Assertation failed: buttonBurst == null");
        buttonBurst.setVisibility(View.GONE);
        buttonBurst.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                if (device == null) throw new RuntimeException("Assertation failed: device == null");
                started = System.currentTimeMillis();
                device.performBracketing();
                Log.i(TAG, "onReady: starting");
            }
        });
        Camera2Device.requestCameraPermissions(this);
    }

    private long started;
    private int bracket = 0;

    private final Camera2Device.Listener listener = new Camera2Device.Listener()
    {
        @Override
        public void onReady()
        {
            if (buttonBurst == null) throw new RuntimeException("Assertation failed: buttonBurst == null");
            runOnUiThread(new Runnable()
            {
                @Override
                public void run()
                {
                    buttonBurst.setVisibility(View.VISIBLE);
                }
            });
        }

        @Override
        public void onImageAvailable(@NonNull Image image)
        {
            Log.i(TAG, "onImageAvailable: " + image);
            bracket++;
            if (bracket == 5) {
                bracket = 0;
                Log.i(TAG, "onImageAvailable: took " + (System.currentTimeMillis() - started) / 1000.0);
            }
            new ImageSaver(image).run();
            image.close();
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

    // ----------------------- Auxilary -------------------------------

    public static final File RESOURCES = new File(Environment.getExternalStorageDirectory().getAbsolutePath(), "GeoCV");

    private static class ImageSaver implements Runnable {

        /**
         * The JPEG image
         */
        private final Image mImage;
        /**
         * The file we save the image into.
         */
        private final File mFile;

        public ImageSaver(Image image) {
            RESOURCES.mkdirs();
            mImage = image;
            mFile = new File(RESOURCES, String.valueOf(System.currentTimeMillis()) + ".jpg");
        }

        @Override
        public void run() {
            ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            FileOutputStream output = null;
            try {
                output = new FileOutputStream(mFile);
                output.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                mImage.close();
                if (null != output) {
                    try {
                        output.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

    }
}
