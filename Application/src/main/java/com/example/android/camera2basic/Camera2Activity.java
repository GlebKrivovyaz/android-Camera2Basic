package com.example.android.camera2basic;

import android.app.Activity;
import android.media.Image;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.util.Range;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.example.android.camera2basic.camera2.Camera2Device;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Locale;

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

    @Nullable
    private TextView cameraCharacteristics;

    private static final ArrayList<Camera2Device.Bracket> BRACKETS = new ArrayList<>();
    static
    {
        BRACKETS.add(new Camera2Device.Bracket(100000000, 200));
        BRACKETS.add(new Camera2Device.Bracket(200000000, 400));
        BRACKETS.add(new Camera2Device.Bracket(300000000, 700));
        BRACKETS.add(new Camera2Device.Bracket(400000000, 1000));
        BRACKETS.add(new Camera2Device.Bracket(500000000, 1200));
        BRACKETS.add(new Camera2Device.Bracket(686000000, 1600));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_burst);
        buttonBurst = (Button) findViewById(R.id.btn_burst);
        Asserts.assertNotNull(buttonBurst, "buttonBurst != null");
        buttonBurst.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                Asserts.assertNotNull(device, "device != null");
                showButtons(false);
                started = System.currentTimeMillis();
                device.performBracketing(BRACKETS);
                Log.i(TAG, "onReady: starting");
            }
        });
        cameraCharacteristics = (TextView) findViewById(R.id.txt_cam_characteristics);
        Camera2Device.requestCameraPermissions(this);
    }

    private long started;
    private int bracket = 0;

    private final Camera2Device.Listener listener = new Camera2Device.Listener()
    {
        @Override
        public void onCameraCharacteristics(@NonNull Range<Long> exposureRange, @NonNull Range<Integer> sensitivityRange)
        {
            Asserts.assertNotNull(cameraCharacteristics, "cameraCharacteristics != null");
            cameraCharacteristics.setText(String.format(
                    Locale.US, "Exposure range: {%d - %d}, ISO range: {%d - %d}",
                    exposureRange.getLower(), exposureRange.getUpper(),
                    sensitivityRange.getLower(), sensitivityRange.getUpper()
            ));
        }

        @Override
        public void onReady()
        {
            runOnUiThread(new Runnable()
            {
                @Override
                public void run()
                {
                    showButtons(true);
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

    private void showButtons(boolean visible)
    {
        Asserts.assertNotNull(buttonBurst, "buttonBurst != null");
        buttonBurst.setVisibility(visible ? View.VISIBLE : View.GONE);
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
