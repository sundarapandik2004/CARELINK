package com.example.accessease;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class ReminderService extends Service {

    private static final String TAG = "ReminderService";
    private static final String PREFS_NAME = "ReminderPrefs";
    private static final String KEY_REMINDERS = "reminders";
    private static final int NOTIFICATION_ID = 999;
    public static final String CHANNEL_ID = "reminder_service_channel";

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "ReminderService created");
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "ReminderService started");

        // Start as foreground service
        startForeground(NOTIFICATION_ID, createServiceNotification());

        // Schedule all reminders
        scheduleAllReminders();

        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Reminder Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps reminder service running");
            channel.setShowBadge(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
                Log.d(TAG, "Service notification channel created");
            }
        }
    }

    private Notification createServiceNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AccessEase")
                .setContentText("Reminder service is active")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setSilent(true)
                .build();
    }

    public void scheduleAllReminders() {
        List<Reminder> reminders = loadReminders();
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);

        if (alarmManager == null) {
            Log.e(TAG, "AlarmManager is null");
            return;
        }

        Log.d(TAG, "Scheduling " + reminders.size() + " reminders");

        for (int i = 0; i < reminders.size(); i++) {
            Reminder reminder = reminders.get(i);
            if (!reminder.isCompleted()) {
                scheduleReminder(alarmManager, reminder, i);
            }
        }
    }

    private void scheduleReminder(AlarmManager alarmManager, Reminder reminder, int requestCode) {
        long reminderTime = reminder.getTime();
        long currentTime = System.currentTimeMillis();

        Log.d(TAG, "Scheduling: " + reminder.getTitle());
        Log.d(TAG, "Reminder time: " + new java.util.Date(reminderTime));
        Log.d(TAG, "Current time: " + new java.util.Date(currentTime));
        Log.d(TAG, "Time until reminder: " + (reminderTime - currentTime) + "ms");

        if (reminderTime > currentTime) {
            Intent intent = new Intent(this, ReminderReceiver.class);
            intent.putExtra("title", reminder.getTitle());
            intent.putExtra("description", reminder.getDescription());
            intent.putExtra("id", requestCode);

            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    this,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            reminderTime,
                            pendingIntent
                    );
                    Log.d(TAG, "Used setExactAndAllowWhileIdle");
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    alarmManager.setExact(
                            AlarmManager.RTC_WAKEUP,
                            reminderTime,
                            pendingIntent
                    );
                    Log.d(TAG, "Used setExact");
                } else {
                    alarmManager.set(
                            AlarmManager.RTC_WAKEUP,
                            reminderTime,
                            pendingIntent
                    );
                    Log.d(TAG, "Used set");
                }

                Log.d(TAG, "Successfully scheduled: " + reminder.getTitle());

            } catch (Exception e) {
                Log.e(TAG, "Error scheduling reminder: " + e.getMessage(), e);
            }
        } else {
            Log.d(TAG, "Skipping past reminder: " + reminder.getTitle());
        }
    }

    private List<Reminder> loadReminders() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String json = prefs.getString(KEY_REMINDERS, "[]");
        Type type = new TypeToken<ArrayList<Reminder>>() {}.getType();
        List<Reminder> list = new Gson().fromJson(json, type);
        return list != null ? list : new ArrayList<>();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "ReminderService destroyed");
    }
}
