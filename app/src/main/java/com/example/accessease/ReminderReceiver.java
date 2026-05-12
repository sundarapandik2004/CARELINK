package com.example.accessease;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

public class ReminderReceiver extends BroadcastReceiver {

    private static final String TAG = "ReminderReceiver";
    private static final String CHANNEL_ID = "reminder_channel";

    @Override
    public void onReceive(Context context, Intent intent) {
        String title       = intent.getStringExtra("title");
        String description = intent.getStringExtra("description");
        // FIX: use "id" to match what RemindersActivity puts in the intent
        int notificationId = intent.getIntExtra("id", (int) System.currentTimeMillis());

        Log.d(TAG, "Reminder fired: " + title + " id=" + notificationId);

        createNotificationChannel(context);
        showNotification(context, title, description, notificationId);
        speakReminder(context, title, description);
    }

    private void speakReminder(Context context, String title, String description) {
        // Build a natural-sounding announcement
        String message = "Reminder! It's time for " + title;
        if (description != null && !description.trim().isEmpty()) {
            message += ". " + description;
        }

        Intent serviceIntent = new Intent(context, TTSService.class);
        serviceIntent.putExtra("message", message);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, serviceIntent);
        } else {
            context.startService(serviceIntent);
        }

        Log.d(TAG, "Started TTSService with: " + message);
    }

    private void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager mgr =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (mgr == null || mgr.getNotificationChannel(CHANNEL_ID) != null) return;

            Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build();

            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Reminder notifications with voice");
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 500, 200, 500});
            ch.setSound(sound, attrs);
            ch.enableLights(true);
            ch.setLightColor(0xFF7C6FFF);
            ch.setShowBadge(true);
            mgr.createNotificationChannel(ch);
        }
    }

    private void showNotification(Context context, String title, String description, int id) {
        NotificationManager mgr =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        Intent tap = new Intent(context, MainActivity.class);
        tap.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pi = PendingIntent.getActivity(context, id, tap,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);

        String body = "It's time for " + title;
        if (description != null && !description.trim().isEmpty()) body += "\n\n" + description;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("⏰ " + title)
                .setContentText("It's time! Tap to open.")
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setSound(sound)
                .setVibrate(new long[]{0, 500, 200, 500})
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(pi, true);

        if (mgr != null) mgr.notify(id, builder.build());
        Log.d(TAG, "Notification shown id=" + id);
    }
}