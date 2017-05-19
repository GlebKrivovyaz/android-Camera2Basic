package com.example.android.camera2basic.camera2;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.widget.Toast;

/**
 * Created by grigory on 27.03.17.
 */

public class PermissionsRequestActivity extends Activity
{
    private final static int REQUEST_PERMISSIONS = 111;
    private final static int REQUEST_ENABLE_BT = 222;

    private final BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        requestPermissions(
                new String[]{
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.CAMERA},
                REQUEST_PERMISSIONS
        );
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults)
    {
        switch (requestCode) {
            case REQUEST_PERMISSIONS: {
                if (grantResults.length == 0) {
                    Toast.makeText(this, "Need permissions to continue, now switching off..", Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                for (int perm : grantResults) {
                    if (perm != PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(this, "Need permissions to continue, now switching off..", Toast.LENGTH_LONG).show();
                        finish();
                        return;
                    }
                }
                finish();
                startActivity(new Intent(this, Camera2Activity.class));
            }
            break;
        }
    }
}
