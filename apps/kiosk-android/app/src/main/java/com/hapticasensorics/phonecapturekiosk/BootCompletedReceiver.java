package com.hapticasensorics.phonecapturekiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public final class BootCompletedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Intent serviceIntent = UvcCaptureService.buildServiceIntent(context, UvcCaptureService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }

        Intent homeIntent = new Intent(context, MainActivity.class);
        homeIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
        );
        try {
            context.startActivity(homeIntent);
        } catch (Exception ignored) {
            // Best-effort only. On some builds boot-time activity starts may be deferred.
        }
    }
}
