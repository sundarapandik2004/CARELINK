package com.example.accessease;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        Log.d("BootReceiver", "Boot completed — starting services");

        // Start ReminderService (existing)
        Intent reminderIntent = new Intent(context, ReminderService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(reminderIntent);
        } else {
            context.startService(reminderIntent);
        }

        // ★ NEW — reschedule RiskAlarmReceiver after reboot
        // Without this, the alarm is lost when the phone restarts
        // and risk checking stops working until the user opens the app again
        RiskAlarmReceiver.scheduleRepeating(context);
        Log.d("BootReceiver", "Risk alarm rescheduled after boot");
    }
}