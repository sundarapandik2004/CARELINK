package com.example.accessease;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RemindersActivity extends AppCompatActivity {

    private static final int PERMISSION_POST_NOTIFICATIONS = 100;
    private RecyclerView recyclerView;
    private FloatingActionButton fabAdd;
    private View emptyState;
    private ReminderAdapter adapter;
    private List<Reminder> reminderList;

    private FirebaseFirestore db;
    private ListenerRegistration caregiverReminderListener;
    private String currentUserId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reminders);

        db = FirebaseFirestore.getInstance();
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        currentUserId = user != null ? user.getUid() : null;

        recyclerView = findViewById(R.id.recyclerView);
        fabAdd       = findViewById(R.id.fabAdd);
        emptyState   = findViewById(R.id.emptyState);

        reminderList = loadReminders();
        adapter = new ReminderAdapter(reminderList, this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        updateNoRemindersView();
        fabAdd.setOnClickListener(v -> checkPermissionsAndOpenDialog());
        checkAndRequestPermissions();
        startReminderService();
        listenForCaregiverReminders(); // ★ show reminders added by caregiver
    }

    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        PERMISSION_POST_NOTIFICATIONS);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null && !am.canScheduleExactAlarms()) showExactAlarmPermissionDialog();
        }
    }

    private void showExactAlarmPermissionDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Permission Required")
                .setMessage("This app needs permission to schedule exact alarms for reminders.")
                .setPositiveButton("Open Settings", (d, w) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void checkPermissionsAndOpenDialog() {
        boolean hasNotif = true;
        boolean hasAlarm = true;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            hasNotif = ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            hasAlarm = am != null && am.canScheduleExactAlarms();
        }

        if (!hasNotif) { checkAndRequestPermissions(); return; }
        if (!hasAlarm) { showExactAlarmPermissionDialog(); return; }

        openAddReminderDialog();
    }

    private void openAddReminderDialog() {
        AddReminderDialog dialog = new AddReminderDialog(this);
        dialog.setOnReminderAddedListener(reminder -> {
            if (reminder.getTime() <= System.currentTimeMillis()) {
                Toast.makeText(this, "Please select a future time", Toast.LENGTH_SHORT).show();
                return;
            }
            int index = reminderList.size();
            reminderList.add(reminder);
            saveReminders(reminderList);
            adapter.notifyItemInserted(index);
            scheduleReminder(reminder, index);
            saveReminderToFirestore(reminder);   // ← Cloud sync
            updateNoRemindersView();

            long mins = (reminder.getTime() - System.currentTimeMillis()) / 60000;
            Toast.makeText(this, "Reminder set for " + mins + " min from now", Toast.LENGTH_LONG).show();
        });
        dialog.show();
    }

    /** Save reminder to Firestore so caregiver can see it */
    private void saveReminderToFirestore(Reminder reminder) {
        if (currentUserId == null) return;

        Map<String, Object> data = new HashMap<>();
        data.put("userId",      currentUserId);
        data.put("title",       reminder.getTitle());
        data.put("description", reminder.getDescription());
        data.put("time",        reminder.getTime());
        data.put("medication",  reminder.isMedication());
        data.put("status",      "Pending");

        db.collection("reminders")
                .add(data)
                .addOnSuccessListener(ref -> Log.d("RemindersActivity", "Saved to Firestore: " + ref.getId()))
                .addOnFailureListener(e  -> Log.e("RemindersActivity", "Firestore save failed: " + e.getMessage()));
    }

    private void scheduleReminder(Reminder reminder, int requestCode) {
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;

            Intent intent = new Intent(this, ReminderReceiver.class);
            intent.putExtra("title",       reminder.getTitle());
            intent.putExtra("description", reminder.getDescription());
            intent.putExtra("id",          requestCode);

            PendingIntent pi = PendingIntent.getBroadcast(this, requestCode, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.getTime(), pi);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                am.setExact(AlarmManager.RTC_WAKEUP, reminder.getTime(), pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, reminder.getTime(), pi);
            }
        } catch (Exception e) {
            Log.e("RemindersActivity", "Error scheduling: " + e.getMessage());
        }
    }

    private void startReminderService() {
        Intent si = new Intent(this, ReminderService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(si);
        else startService(si);
    }

    private void updateNoRemindersView() {
        emptyState.setVisibility(reminderList.isEmpty() ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(reminderList.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private List<Reminder> loadReminders() {
        try {
            SharedPreferences prefs = getSharedPreferences("ReminderPrefs", MODE_PRIVATE);
            String json = prefs.getString("reminders", "[]");
            Type type = new TypeToken<ArrayList<Reminder>>(){}.getType();
            List<Reminder> list = new Gson().fromJson(json, type);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) { return new ArrayList<>(); }
    }

    private void saveReminders(List<Reminder> reminders) {
        SharedPreferences prefs = getSharedPreferences("ReminderPrefs", MODE_PRIVATE);
        prefs.edit().putString("reminders", new Gson().toJson(reminders)).apply();
        startReminderService();
    }

    /** Called by ReminderAdapter when a reminder is ticked/unticked */
    public void saveRemindersFromAdapter() {
        saveReminders(reminderList);
    }

    /** Called by ReminderAdapter to get the current user's Firestore UID */
    public String getCurrentUserId() {
        return currentUserId;
    }

    public void deleteReminder(int position) {
        if (position < 0 || position >= reminderList.size()) return;
        cancelReminder(position);
        reminderList.remove(position);
        saveReminders(reminderList);
        adapter.notifyItemRemoved(position);
        updateNoRemindersView();
    }

    private void cancelReminder(int code) {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(this, ReminderReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(this, code, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
        pi.cancel();
    }

    @Override
    public void onRequestPermissionsResult(int req,
                                           @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == PERMISSION_POST_NOTIFICATIONS) {
            String msg = (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
                    ? "Notifications enabled" : "Notifications disabled — reminders may not appear";
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        reminderList = loadReminders();
        adapter = new ReminderAdapter(reminderList, this);
        recyclerView.setAdapter(adapter);
        updateNoRemindersView();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (caregiverReminderListener != null) {
            caregiverReminderListener.remove();
            caregiverReminderListener = null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Listen for reminders the caregiver added to Firestore for this user.
    //
    //  Problem: loadReminders() reads SharedPreferences (local storage only).
    //  When a caregiver adds a reminder in their dashboard, it goes to Firestore
    //  but never reaches the user's phone — so the user never sees it.
    //
    //  Fix: Real-time Firestore listener that watches for reminders where
    //  addedBy == "caregiver" AND userId == currentUserId.
    //  When one arrives:
    //    1. Add it to the local SharedPreferences so it persists
    //    2. Schedule its alarm via AlarmManager
    //    3. Show a notification to the user
    //    4. Refresh the RecyclerView
    // ─────────────────────────────────────────────────────────────────────────
    private void listenForCaregiverReminders() {
        if (currentUserId == null) return;

        caregiverReminderListener = db.collection("reminders")
                .whereEqualTo("userId",   currentUserId)
                .whereEqualTo("addedBy",  "caregiver")
                .addSnapshotListener((snapshots, error) -> {
                    if (error != null) {
                        Log.e("RemindersActivity", "Caregiver listener error: " + error.getMessage());
                        return;
                    }
                    if (snapshots == null) return;

                    boolean anyNew = false;

                    for (QueryDocumentSnapshot doc : snapshots) {
                        String firestoreId = doc.getId();

                        // Skip if already in local list (avoid duplicates)
                        boolean alreadySaved = false;
                        for (Reminder r : reminderList) {
                            if (firestoreId.equals(r.getFirestoreId())) {
                                alreadySaved = true;
                                break;
                            }
                        }
                        if (alreadySaved) continue;

                        // Build the Reminder object from Firestore data
                        String  title       = doc.getString("title");
                        String  description = doc.getString("description");
                        Long    timeMs      = doc.getLong("time");
                        Boolean medication  = doc.getBoolean("medication");

                        if (title == null || timeMs == null) continue;
                        if (timeMs < System.currentTimeMillis())  continue; // already past

                        Reminder r = new Reminder();
                        r.setTitle(title);
                        r.setDescription(description != null ? description : "");
                        r.setTime(timeMs);
                        r.setMedication(medication != null && medication);
                        r.setFirestoreId(firestoreId); // track so we don't duplicate

                        // Add to local list + save to SharedPreferences
                        reminderList.add(r);
                        anyNew = true;

                        // Schedule alarm for this reminder
                        int requestCode = (int) (timeMs % Integer.MAX_VALUE);
                        scheduleReminder(r, requestCode);

                        // Notify user that caregiver added a reminder
                        notifyUserOfCaregiverReminder(title, timeMs);
                    }

                    if (anyNew) {
                        saveReminders(reminderList);
                        adapter = new ReminderAdapter(reminderList, this);
                        recyclerView.setAdapter(adapter);
                        updateNoRemindersView();
                    }
                });
    }

    // Shows a notification on the user's phone when caregiver adds a reminder
    private void notifyUserOfCaregiverReminder(String title, long timeMs) {
        android.app.NotificationManager mgr =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (mgr == null) return;

        // Create channel if needed (Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.NotificationChannel ch = new android.app.NotificationChannel(
                    "caregiver_reminders", "Caregiver Reminders",
                    android.app.NotificationManager.IMPORTANCE_HIGH);
            mgr.createNotificationChannel(ch);
        }

        String timeStr = new java.text.SimpleDateFormat("hh:mm a, MMM d",
                java.util.Locale.getDefault()).format(new java.util.Date(timeMs));

        android.app.Notification n =
                new androidx.core.app.NotificationCompat.Builder(this, "caregiver_reminders")
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("New Reminder from Caregiver")
                        .setContentText(title + " at " + timeStr)
                        .setStyle(new androidx.core.app.NotificationCompat.BigTextStyle()
                                .bigText("Your caregiver added a reminder:\n" + title + "\nat " + timeStr))
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .build();

        mgr.notify((int) System.currentTimeMillis(), n);
    }
}