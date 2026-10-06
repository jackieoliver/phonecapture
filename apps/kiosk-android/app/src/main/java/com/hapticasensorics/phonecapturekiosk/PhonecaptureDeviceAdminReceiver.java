package com.hapticasensorics.phonecapturekiosk;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

public final class PhonecaptureDeviceAdminReceiver extends DeviceAdminReceiver {
    @Override
    public void onEnabled(Context context, Intent intent) {
        Toast.makeText(context, "Device admin enabled", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        Toast.makeText(context, "Device admin disabled", Toast.LENGTH_SHORT).show();
    }
}
