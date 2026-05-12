package com.example.accessease;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

/**
 * RiskAlarmReceiver
 *
 * ★ WHY BroadcastReceiver INSTEAD OF Service:
 *   Android 8+ kills background services after a few minutes.
 *   A BroadcastReceiver wakes up for ~10 seconds, does the work,
 *   and goes back to sleep — the OS cannot kill it mid-task.
 *   This is why the risk check was NOT running after the app was closed.
 *
 * How it works:
 *   1. scheduleRepeating() sets an exact alarm every 30 minutes
 *   2. When the alarm fires, onReceive() runs — even if app is closed/killed
 *   3. It updates lastActive, runs RiskAnalyzer, shows notification if HIGH
 *   4. It schedules the NEXT alarm before finishing
 *
 * Call scheduleRepeating(context) from:
 *   - MainActivity.onCreate()    — when user opens app
 *   - BootReceiver.onReceive()   — when phone restarts
 */
public class RiskAlarmReceiver extends BroadcastReceiver {

    private static final String TAG      = "RiskAlarmReceiver";
    public  static final String CH_RISK  = "risk_alerts";
    private static final int    REQ_CODE = 9001;
    private static final long   INTERVAL = 30 * 60 * 1000L; // 30 minutes

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Risk alarm fired — running check");

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.d(TAG, "No logged-in user — skipping");
            scheduleNext(context); // still reschedule for when they log in later
            return;
        }

        String userId = user.getUid();

        // Update lastActive so caregiver sees accurate "last active" time
        FirebaseFirestore.getInstance()
                .collection("users").document(userId)
                .update("lastActive", System.currentTimeMillis());

        // Run risk analysis — writes to user_status/{userId}
        // Caregiver dashboard's snapshot listener fires automatically
        RiskAnalyzer.analyze(userId, (riskLevel, reasons) -> {
            Log.d(TAG, "Risk result: " + riskLevel + " — " + reasons);

            // Show local notification to the USER if HIGH or MEDIUM
            if ("HIGH".equals(riskLevel)) {
                showNotification(context,
                        "⚠ Health Alert",
                        "Please check your medications and reminders.\n" + reasons,
                        true);
            } else if ("MEDIUM".equals(riskLevel)) {
                showNotification(context,
                        "Reminder Check",
                        reasons,
                        false);
            }
        });

        // Schedule the next check
        scheduleNext(context);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Schedule the first/next alarm
    // ─────────────────────────────────────────────────────────────────────────
    private void scheduleNext(Context context) {
        schedule(context, System.currentTimeMillis() + INTERVAL);
    }

    /**
     * Call this from MainActivity and BootReceiver.
     * Sets an exact repeating alarm that fires every 30 minutes.
     * Safe to call multiple times — uses FLAG_UPDATE_CURRENT so it
     * replaces any existing alarm instead of creating duplicates.
     */
    public static void scheduleRepeating(Context context) {
        schedule(context, System.currentTimeMillis() + INTERVAL);
        Log.d(TAG, "Risk check alarm scheduled — first run in 30 min");
    }

    /**
     * Run a check RIGHT NOW (used on app open to get immediate result).
     */
    public static void runNow(Context context) {
        schedule(context, System.currentTimeMillis() + 5000L); // 5 seconds from now
        Log.d(TAG, "Risk check scheduled to run in 5 seconds");
    }

    private static void schedule(Context context, long triggerAt) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent intent = new Intent(context, RiskAlarmReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(
                context, REQ_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // setExactAndAllowWhileIdle fires even in Doze mode (phone asleep)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        } else {
            am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Local notification to the user's phone
    // ─────────────────────────────────────────────────────────────────────────
    private static void showNotification(Context context, String title,
                                         String body, boolean urgent) {
        NotificationManager mgr =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (mgr == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CH_RISK, "Health Risk Alerts",
                    NotificationManager.IMPORTANCE_HIGH);
            mgr.createNotificationChannel(ch);
        }

        mgr.notify(889,
                new NotificationCompat.Builder(context, CH_RISK)
                        .setSmallIcon(android.R.drawable.ic_dialog_alert)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                        .setPriority(urgent
                                ? NotificationCompat.PRIORITY_MAX
                                : NotificationCompat.PRIORITY_DEFAULT)
                        .setAutoCancel(true)
                        .build());
    }
}