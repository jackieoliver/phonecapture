package com.hapticasensorics.phonecapturesourcestimulus;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class CommandReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        StimulusMode mode = StimulusMode.fromWireValue(intent.getStringExtra(MainActivity.EXTRA_MODE));
        Intent launchIntent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_MODE, mode.wireValue())
                .putExtra(MainActivity.EXTRA_SOURCE, "broadcast");
        context.startActivity(launchIntent);
    }
}

