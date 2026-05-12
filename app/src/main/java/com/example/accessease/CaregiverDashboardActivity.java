package com.example.accessease;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.*;

import java.text.SimpleDateFormat;
import java.util.*;

public class CaregiverDashboardActivity extends AppCompatActivity {

    private static final String TAG         = "CaregiverDashboard";
    private static final String CH_SOS      = "sos_alerts";
    private static final String CH_REMINDER   = "reminder_alerts";
    private static final String CH_INACTIVITY = "inactivity_alerts";

    // ── Views ─────────────────────────────────────────────────────────────────
    private TextView  tvLinkedUser, tvMyCode, tvMyCodeValue;
    private Button    btnCopyCode;
    private TextView  tvSosStatus, tvSosTime, tvSosLocation;
    private Button    btnCallUser, btnOpenMap, btnResolveSOS;
    private TextView  tvStatCompleted, tvStatMissed, tvStatPending, tvStatSOS, tvLastActive;
    private RecyclerView rvReminders;
    private TextView  tvEmptyReminders;
    private FloatingActionButton fabAddReminder;
    private TextView  tvLastComm;

    // ── Firebase ──────────────────────────────────────────────────────────────
    private FirebaseFirestore db;
    private String caregiverUid;
    private String linkedUserId    = null;
    private String linkedUserName  = null;
    private String linkedUserPhone = null;

    // ── Firestore listeners ───────────────────────────────────────────────────
    private ListenerRegistration sosListener;
    private ListenerRegistration reminderListener;
    private ListenerRegistration activityListener;
    private ListenerRegistration commListener;
    private ListenerRegistration evidenceListener;

    private String activeSosDocId    = null;
    private String lastNotifiedLevel = "LOW"; // avoid repeat notifications
    private double sosLatitude    = 0;
    private double sosLongitude   = 0;

    private final List<Map<String, Object>> reminderItems = new ArrayList<>();
    private CaregiverReminderAdapter adapter;

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);   // ★ FIX — must be FIRST line
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_caregiver_dashboard);

        try {
            db = FirebaseFirestore.getInstance();
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) {
                startActivity(new Intent(this, LoginActivity.class));
                finish();
                return;
            }
            caregiverUid = user.getUid();

            bindViews();
            createNotificationChannels();
            setupStaticViews(); // ← shows UID immediately, BEFORE any Firestore call
            loadLinkedUser();

        } catch (Exception e) {
            Log.e(TAG, "onCreate crash: " + e.getMessage(), e);
            Toast.makeText(this, "Dashboard error: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  View binding
    // ─────────────────────────────────────────────────────────────────────────
    private void bindViews() {
        tvLinkedUser    = findViewById(R.id.tvLinkedUser);
        tvMyCode        = findViewById(R.id.tvMyCode);
        tvMyCodeValue   = findViewById(R.id.tvMyCodeValue);
        btnCopyCode     = findViewById(R.id.btnCopyCode);
        tvSosStatus     = findViewById(R.id.tvSosStatus);
        tvSosTime       = findViewById(R.id.tvSosTime);
        tvSosLocation   = findViewById(R.id.tvSosLocation);
        btnCallUser     = findViewById(R.id.btnCallUser);
        btnOpenMap      = findViewById(R.id.btnOpenMap);
        btnResolveSOS   = findViewById(R.id.btnResolveSOS);
        tvStatCompleted = findViewById(R.id.tvStatCompleted);
        tvStatMissed    = findViewById(R.id.tvStatMissed);
        tvStatPending   = findViewById(R.id.tvStatPending);
        tvStatSOS       = findViewById(R.id.tvStatSOS);
        tvLastActive    = findViewById(R.id.tvLastActive);
        rvReminders     = findViewById(R.id.rvReminders);
        tvEmptyReminders= findViewById(R.id.tvEmptyReminders);
        fabAddReminder  = findViewById(R.id.fabAddReminder);
        tvLastComm      = findViewById(R.id.tvLastComm);

        if (rvReminders != null) {
            adapter = new CaregiverReminderAdapter(
                    reminderItems, this::onReminderStatusChange);
            rvReminders.setLayoutManager(new LinearLayoutManager(this));
            rvReminders.setAdapter(adapter);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ★ KEY FIX — Show UID immediately when screen opens
    //
    //  Old bug: tvMyCode first showed "Tap to copy your code" as text.
    //  If caregiver tapped Copy before Firestore loaded, they copied that
    //  string instead of their real UID. User pasted wrong value → no link.
    //
    //  Fix: Show the real UID instantly in onCreate, BEFORE loadLinkedUser().
    //  Also show it in a separate clearly-labelled TextView so it's obvious.
    // ─────────────────────────────────────────────────────────────────────────
    private void setupStaticViews() {
        // ★ Show real UID immediately — never "Tap to copy" placeholder
        if (tvMyCodeValue != null) {
            tvMyCodeValue.setText(caregiverUid);
        }
        // Also put it in tvMyCode if that's what the layout uses
        if (tvMyCode != null) {
            tvMyCode.setText(caregiverUid);
        }

        // Copy button — always works from the moment the screen opens
        if (btnCopyCode != null) {
            btnCopyCode.setOnClickListener(v -> copyCode());
        }
        // Also make the code text itself tappable
        if (tvMyCodeValue != null) {
            tvMyCodeValue.setOnClickListener(v -> copyCode());
        }
        if (tvMyCode != null) {
            tvMyCode.setOnClickListener(v -> copyCode());
        }

        if (fabAddReminder != null) {
            fabAddReminder.setOnClickListener(v -> showAddReminderDialog());
        }

        // Default SOS card hidden
        safeSetVisibility(tvSosTime,     View.GONE);
        safeSetVisibility(tvSosLocation, View.GONE);
        safeSetVisibility(btnCallUser,   View.GONE);
        safeSetVisibility(btnOpenMap,    View.GONE);
        safeSetVisibility(btnResolveSOS, View.GONE);
        safeSetText(tvSosStatus, "No Active SOS");
        if (tvSosStatus != null) tvSosStatus.setTextColor(0xFF10B981);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Load linked user → start all listeners
    // ─────────────────────────────────────────────────────────────────────────
    private void loadLinkedUser() {
        db.collection("users")
                .whereEqualTo("linkedCaregiverId", caregiverUid)
                .get()
                .addOnSuccessListener(query -> {
                    if (query.isEmpty()) {
                        safeSetText(tvLinkedUser, "No linked user yet");
                        safeSetText(tvEmptyReminders,
                                "No linked user. Share your code above with a user.");
                        safeSetVisibility(tvEmptyReminders, View.VISIBLE);
                        return;
                    }
                    DocumentSnapshot doc = query.getDocuments().get(0);
                    linkedUserId    = doc.getId();
                    linkedUserName  = doc.getString("name");
                    linkedUserPhone = doc.getString("phone");

                    safeSetText(tvLinkedUser,
                            "Monitoring: " + (linkedUserName != null ? linkedUserName : "User"));

                    listenToReminders();
                    listenToSOS();
                    listenToActivityStatus();
                    listenToLastCommunication();
                    listenForEvidence();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "loadLinkedUser failed: " + e.getMessage());
                    Toast.makeText(this,
                            "Could not load user data: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Feature 1 — Real-time reminder monitoring (sorted in-memory, no index)
    // ─────────────────────────────────────────────────────────────────────────
    private void listenToReminders() {
        reminderListener = db.collection("reminders")
                .whereEqualTo("userId", linkedUserId)
                .addSnapshotListener((snapshots, error) -> {
                    if (error != null) {
                        Log.e(TAG, "Reminders error: " + error.getMessage()); return;
                    }
                    reminderItems.clear();
                    int completed = 0, missed = 0, pending = 0;
                    long now = System.currentTimeMillis();

                    if (snapshots != null) {
                        for (DocumentSnapshot d : snapshots.getDocuments()) {
                            Map<String, Object> item = new HashMap<>(
                                    d.getData() != null ? d.getData() : new HashMap<>());
                            item.put("docId", d.getId());

                            String status = (String) item.getOrDefault("status", "Pending");
                            Object rawTime = item.get("time");
                            if ("Pending".equals(status) && rawTime instanceof Long
                                    && (Long) rawTime < now) {
                                status = "Missed";
                                d.getReference().update("status", "Missed");
                                item.put("status", "Missed");
                                notifyReminderMissed((String) item.get("title"));
                            }
                            reminderItems.add(item);
                            switch (status) {
                                case "Completed": completed++; break;
                                case "Missed":    missed++;    break;
                                default:          pending++;   break;
                            }
                        }
                        Collections.sort(reminderItems, (a, b) -> {
                            Object tA = a.get("time"), tB = b.get("time");
                            if (tA instanceof Long && tB instanceof Long)
                                return Long.compare((Long) tA, (Long) tB);
                            return 0;
                        });
                    }
                    if (adapter != null) adapter.notifyDataSetChanged();
                    safeSetVisibility(tvEmptyReminders,
                            reminderItems.isEmpty() ? View.VISIBLE : View.GONE);
                    safeSetText(tvStatCompleted, String.valueOf(completed));
                    safeSetText(tvStatMissed,    String.valueOf(missed));
                    safeSetText(tvStatPending,   String.valueOf(pending));
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Feature 2 & 3 — SOS alert (filter in-memory, no composite index)
    // ─────────────────────────────────────────────────────────────────────────
    private void listenToSOS() {
        sosListener = db.collection("sos")
                .whereEqualTo("userId", linkedUserId)
                .addSnapshotListener((snapshots, error) -> {
                    if (error != null) { Log.e(TAG, "SOS error: " + error.getMessage()); return; }

                    DocumentSnapshot activeDoc = null;
                    if (snapshots != null) {
                        for (DocumentSnapshot doc : snapshots.getDocuments()) {
                            if ("Active".equals(doc.getString("status"))) {
                                activeDoc = doc; break;
                            }
                        }
                    }
                    if (activeDoc != null) {
                        activeSosDocId = activeDoc.getId();
                        Long ts    = activeDoc.getLong("timestamp");
                        Double lat = activeDoc.getDouble("latitude");
                        Double lng = activeDoc.getDouble("longitude");
                        sosLatitude  = lat != null ? lat : 0;
                        sosLongitude = lng != null ? lng : 0;
                        String time = ts != null
                                ? new SimpleDateFormat("hh:mm a, MMM d", Locale.getDefault())
                                .format(new Date(ts)) : "just now";
                        showSosActive(time,
                                sosLatitude != 0 ? sosLatitude + ", " + sosLongitude
                                        : "Location unavailable",
                                sosLatitude, sosLongitude);
                        showSOSNotification(time);
                        safeSetText(tvStatSOS, "1");
                        if (tvStatSOS != null) tvStatSOS.setTextColor(0xFFEF4444);
                    } else {
                        activeSosDocId = null;
                        hideSos();
                        safeSetText(tvStatSOS, "0");
                        if (tvStatSOS != null) tvStatSOS.setTextColor(0xFF10B981);
                    }
                });
    }

    private void showSosActive(String time, String location, double lat, double lng) {
        safeSetText(tvSosStatus, "SOS ACTIVE — needs help!");
        if (tvSosStatus != null) tvSosStatus.setTextColor(0xFFEF4444);
        safeSetText(tvSosTime,     "Time: " + time);
        safeSetText(tvSosLocation, "Location: " + location);
        safeSetVisibility(tvSosTime,     View.VISIBLE);
        safeSetVisibility(tvSosLocation, View.VISIBLE);
        safeSetVisibility(btnCallUser,   View.VISIBLE);
        safeSetVisibility(btnResolveSOS, View.VISIBLE);
        safeSetVisibility(btnOpenMap,    lat != 0 ? View.VISIBLE : View.GONE);
        if (btnCallUser   != null) btnCallUser.setOnClickListener(v -> callUser());
        if (btnOpenMap    != null) btnOpenMap.setOnClickListener(v -> openMaps(lat, lng));
        if (btnResolveSOS != null) btnResolveSOS.setOnClickListener(v -> resolveSOS());
    }

    private void hideSos() {
        safeSetText(tvSosStatus, "No Active SOS");
        if (tvSosStatus != null) tvSosStatus.setTextColor(0xFF10B981);
        safeSetVisibility(tvSosTime,     View.GONE);
        safeSetVisibility(tvSosLocation, View.GONE);
        safeSetVisibility(btnCallUser,   View.GONE);
        safeSetVisibility(btnOpenMap,    View.GONE);
        safeSetVisibility(btnResolveSOS, View.GONE);
    }

    private void callUser() {
        if (linkedUserPhone == null || linkedUserPhone.isEmpty()) {
            Toast.makeText(this, "User phone not in profile", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + linkedUserPhone)));
    }

    private void openMaps(double lat, double lng) {
        startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://maps.google.com/?q=" + lat + "," + lng)));
    }

    private void resolveSOS() {
        if (activeSosDocId == null) return;
        db.collection("sos").document(activeSosDocId).update("status", "Resolved")
                .addOnSuccessListener(v ->
                        Toast.makeText(this, "SOS resolved", Toast.LENGTH_SHORT).show());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Feature 4 — Caregiver adds reminder remotely
    // ─────────────────────────────────────────────────────────────────────────
    private void showAddReminderDialog() {
        if (linkedUserId == null) {
            Toast.makeText(this, "No linked user yet", Toast.LENGTH_SHORT).show();
            return;
        }
        View dialogView;
        try {
            dialogView = LayoutInflater.from(this)
                    .inflate(R.layout.dialog_caregiver_add_reminder, null);
        } catch (Exception e) {
            Toast.makeText(this, "Missing: dialog_caregiver_add_reminder.xml",
                    Toast.LENGTH_LONG).show();
            return;
        }

        EditText etTitle  = dialogView.findViewById(R.id.etCgReminderTitle);
        EditText etDesc   = dialogView.findViewById(R.id.etCgReminderDesc);
        TextView tvTime   = dialogView.findViewById(R.id.tvCgReminderTime);
        Button   btnTime  = dialogView.findViewById(R.id.btnCgSetTime);
        CheckBox cbRepeat = dialogView.findViewById(R.id.cbCgRepeat);

        final long[] sel = {System.currentTimeMillis() + 3_600_000};
        SimpleDateFormat sdf = new SimpleDateFormat("hh:mm a", Locale.getDefault());
        if (tvTime != null) tvTime.setText(sdf.format(new Date(sel[0])));

        if (btnTime != null) {
            btnTime.setOnClickListener(v -> {
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(sel[0]);
                new android.app.TimePickerDialog(this, (view, h, m) -> {
                    c.set(Calendar.HOUR_OF_DAY, h);
                    c.set(Calendar.MINUTE, m);
                    sel[0] = c.getTimeInMillis();
                    if (tvTime != null) tvTime.setText(sdf.format(new Date(sel[0])));
                }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show();
            });
        }

        new AlertDialog.Builder(this)
                .setTitle("Add Reminder for " + linkedUserName)
                .setView(dialogView)
                .setPositiveButton("Save", (d, w) -> {
                    if (etTitle == null) return;
                    String title = etTitle.getText().toString().trim();
                    String desc  = etDesc != null ? etDesc.getText().toString().trim() : "";
                    if (title.isEmpty()) {
                        Toast.makeText(this, "Enter a title", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Map<String, Object> r = new HashMap<>();
                    r.put("userId",      linkedUserId);
                    r.put("addedBy",     "caregiver");
                    r.put("caregiverId", caregiverUid);
                    r.put("title",       title);
                    r.put("description", desc);
                    r.put("time",        sel[0]);
                    r.put("repeat",      cbRepeat != null && cbRepeat.isChecked());
                    r.put("status",      "Pending");
                    r.put("medication",  title.toLowerCase().contains("medicine")
                            || title.toLowerCase().contains("tablet")
                            || title.toLowerCase().contains("medication"));
                    db.collection("reminders").add(r)
                            .addOnSuccessListener(ref -> Toast.makeText(this,
                                    "Reminder added for " + linkedUserName,
                                    Toast.LENGTH_SHORT).show())
                            .addOnFailureListener(e -> Toast.makeText(this,
                                    "Failed: " + e.getMessage(),
                                    Toast.LENGTH_SHORT).show());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Feature 5 — Last communication (latest found in-memory, no index)
    // ─────────────────────────────────────────────────────────────────────────
    private void listenToLastCommunication() {
        commListener = db.collection("communications")
                .whereEqualTo("userId", linkedUserId)
                .addSnapshotListener((snapshots, error) -> {
                    if (error != null) { Log.e(TAG, "Comm error: " + error.getMessage()); return; }
                    if (snapshots == null || snapshots.isEmpty()) {
                        safeSetText(tvLastComm, "No communication logged yet");
                        return;
                    }
                    DocumentSnapshot latest = null;
                    long latestTs = 0;
                    for (DocumentSnapshot doc : snapshots.getDocuments()) {
                        Long ts = doc.getLong("timestamp");
                        if (ts != null && ts > latestTs) { latestTs = ts; latest = doc; }
                    }
                    if (latest != null) {
                        String msg  = latest.getString("message");
                        String time = new SimpleDateFormat("hh:mm a", Locale.getDefault())
                                .format(new Date(latestTs));
                        safeSetText(tvLastComm, "\"" + msg + "\"  ·  " + time);
                    }
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Feature 6 — Last active time
    // ─────────────────────────────────────────────────────────────────────────
    private void listenToActivityStatus() {
        activityListener = db.collection("users").document(linkedUserId)
                .addSnapshotListener((doc, error) -> {
                    if (doc == null) return;
                    Long la = doc.getLong("lastActive");
                    if (la == null) {
                        safeSetText(tvLastActive, "Last active: unknown");
                        updateRiskBanner(0, 0, false);
                        return;
                    }
                    long diff = System.currentTimeMillis() - la;
                    String ago = diff < 60_000     ? "just now"
                            : diff < 3_600_000  ? (diff / 60_000)    + " min ago"
                            : diff < 86_400_000 ? (diff / 3_600_000) + " hr ago"
                            :                     (diff / 86_400_000) + " days ago";
                    safeSetText(tvLastActive, "Last active: " + ago);

                    // ★ Compute risk directly from inactivity + missed reminders + SOS
                    float inactiveHours = diff / (1000f * 60f * 60f);
                    int   missedCount   = getMissedCount();
                    boolean sosActive   = activeSosDocId != null;
                    updateRiskBanner(inactiveHours, missedCount, sosActive);
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Risk banner — computed directly from live data, no RiskAlarmReceiver needed
    //
    //  HIGH   : SOS active  OR  3+ missed reminders  OR  inactive 5+ hours
    //  MEDIUM : 1-2 missed  OR  inactive 2-4 hours
    //  LOW    : everything normal
    // ─────────────────────────────────────────────────────────────────────────
    private void updateRiskBanner(float inactiveHours, int missedCount, boolean sosActive) {
        android.widget.TextView tvRisk = findViewById(R.id.tvRiskLevel);
        if (tvRisk == null) return; // layout doesn't have risk card — skip silently

        String level;
        String reason;

        if (sosActive || missedCount >= 3 || inactiveHours >= 5f) {
            level  = "HIGH";
            reason = sosActive         ? "Active SOS alert"
                    : inactiveHours >= 5f ? String.format("Inactive for %.0f hours", inactiveHours)
                    :                       missedCount + " reminders missed";
        } else if (missedCount >= 1 || inactiveHours >= 2f) {
            level  = "MEDIUM";
            reason = inactiveHours >= 2f
                    ? String.format("Inactive for %.0f hours", inactiveHours)
                    : missedCount + " reminder(s) missed";
        } else {
            level  = "LOW";
            reason = "All good. User is active.";
        }

        tvRisk.setText(level);
        android.widget.TextView tvReason = findViewById(R.id.tvRiskReason);
        if (tvReason != null) tvReason.setText(reason);

        android.view.View card = findViewById(R.id.riskStatusCard);
        android.widget.Button btnCall = findViewById(R.id.btnCallFromRisk);

        switch (level) {
            case "HIGH":
                tvRisk.setTextColor(0xFFEF4444);
                if (card    != null) card.setBackgroundColor(0xFF2B0D0D);
                if (btnCall != null) btnCall.setVisibility(android.view.View.VISIBLE);
                break;
            case "MEDIUM":
                tvRisk.setTextColor(0xFFF59E0B);
                if (card    != null) card.setBackgroundColor(0xFF2B1E00);
                if (btnCall != null) btnCall.setVisibility(android.view.View.GONE);
                break;
            default:
                tvRisk.setTextColor(0xFF10B981);
                if (card    != null) card.setBackgroundColor(0xFF0D2B1E);
                if (btnCall != null) btnCall.setVisibility(android.view.View.GONE);
                break;
        }
        if (btnCall != null) btnCall.setOnClickListener(v -> callUser());

        // ★ Notify caregiver when level gets worse (not on every update)
        if (!level.equals(lastNotifiedLevel)) {
            if ("HIGH".equals(level) || "MEDIUM".equals(level)) {
                sendInactivityNotification(level, reason);
            }
            lastNotifiedLevel = level;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Inactivity / risk notification to caregiver
    //  Fires when risk level changes to MEDIUM or HIGH.
    //  4 hours inactive → MEDIUM → caregiver gets notified.
    //  5 hours inactive → HIGH   → caregiver gets urgent alert.
    // ─────────────────────────────────────────────────────────────────────────
    private void sendInactivityNotification(String level, String reason) {
        NotificationManager mgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (mgr == null) return;

        String userName = linkedUserName != null ? linkedUserName : "User";
        String title    = "HIGH".equals(level)
                ? "⚠️ HIGH RISK — " + userName
                : "⚠️ Check on " + userName;
        String body     = reason;

        int priority = "HIGH".equals(level)
                ? NotificationCompat.PRIORITY_MAX
                : NotificationCompat.PRIORITY_HIGH;

        android.app.Notification n = new NotificationCompat.Builder(this, CH_INACTIVITY)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(priority)
                .setAutoCancel(true)
                .build();

        mgr.notify(2001, n);
    }

    // Returns current missed count from the live reminderItems list
    private int getMissedCount() {
        int count = 0;
        for (java.util.Map<String, Object> item : reminderItems) {
            if ("Missed".equals(item.getOrDefault("status", ""))) count++;
        }
        return count;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Feature 7 — Notifications
    // ─────────────────────────────────────────────────────────────────────────
    private void notifyReminderMissed(String title) {
        NotificationManager mgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (mgr == null || title == null) return;
        mgr.notify((int) System.currentTimeMillis(),
                new NotificationCompat.Builder(this, CH_REMINDER)
                        .setSmallIcon(android.R.drawable.ic_dialog_alert)
                        .setContentTitle("Reminder Missed")
                        .setContentText((linkedUserName != null ? linkedUserName : "User")
                                + " did not complete: " + title)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true).build());
    }

    private void showSOSNotification(String time) {
        NotificationManager mgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (mgr == null) return;
        mgr.notify(1001, new NotificationCompat.Builder(this, CH_SOS)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("SOS ALERT!")
                .setContentText((linkedUserName != null ? linkedUserName : "User")
                        + " needs help! at " + time)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true).build());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Reminder status update
    // ─────────────────────────────────────────────────────────────────────────
    private void onReminderStatusChange(String docId, String newStatus) {
        db.collection("reminders").document(docId).update("status", newStatus)
                .addOnSuccessListener(v ->
                        Toast.makeText(this, "Updated to " + newStatus,
                                Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Failed: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Utilities
    // ─────────────────────────────────────────────────────────────────────────
    private void copyCode() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("uid", caregiverUid));
            Toast.makeText(this,
                    "Code copied!\n\nShare this with your user.\nThey paste it in the 'Caregiver Code' field during registration.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void safeSetText(TextView tv, String text) { if (tv != null) tv.setText(text); }
    private void safeSetVisibility(View v, int vis)    { if (v  != null) v.setVisibility(vis); }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager mgr = getSystemService(NotificationManager.class);
        if (mgr == null) return;
        mgr.createNotificationChannel(new NotificationChannel(
                CH_SOS, "SOS Alerts", NotificationManager.IMPORTANCE_HIGH));
        mgr.createNotificationChannel(new NotificationChannel(
                CH_REMINDER, "Missed Reminders", NotificationManager.IMPORTANCE_DEFAULT));
        mgr.createNotificationChannel(new NotificationChannel(
                CH_INACTIVITY, "Inactivity Alerts", NotificationManager.IMPORTANCE_HIGH));
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (sosListener      != null) sosListener.remove();
        if (reminderListener != null) reminderListener.remove();
        if (activityListener != null) activityListener.remove();
        if (commListener     != null) commListener.remove();
        if (evidenceListener != null) evidenceListener.remove();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // -------------------------------------------------------------------------
    //  Evidence Viewer -- reads from Firestore "evidence" collection
    //  Populated by EvidenceCaptureService when SOS is triggered.
    //  Shows: capture datetime, GPS location, photos (Glide), audio link.
    // -------------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    private void listenForEvidence() {
        if (linkedUserId == null) return;
        try {
            evidenceListener = db.collection("evidence")
                    .whereEqualTo("userId", linkedUserId)
                    .limit(10)
                    .addSnapshotListener((snap, err) -> {
                        if (err != null) {
                            android.util.Log.e(TAG, "Evidence error: " + err.getMessage());
                            runOnUiThread(() -> {
                                android.widget.LinearLayout card = findViewById(R.id.evidenceCard);
                                android.widget.TextView tvTime = findViewById(R.id.tvEvidenceTime);
                                if (card != null) card.setVisibility(android.view.View.VISIBLE);
                                if (tvTime != null) tvTime.setText("Error: " + err.getMessage());
                            });
                            return;
                        }
                        if (snap == null || snap.isEmpty()) return;

                        com.google.firebase.firestore.DocumentSnapshot latest = null;
                        long latestTs = 0;
                        for (com.google.firebase.firestore.DocumentSnapshot d
                                : snap.getDocuments()) {
                            Long ts = d.getLong("timestamp");
                            if (ts != null && ts > latestTs) {
                                latestTs = ts;
                                latest   = d;
                            }
                        }
                        if (latest == null) return;

                        String datetime = latest.getString("datetime");
                        String audioUrl = latest.getString("audioUrl");
                        Double lat      = latest.getDouble("latitude");
                        Double lng      = latest.getDouble("longitude");
                        java.util.List<java.util.Map<String, Object>> photos = new java.util.ArrayList<>();
                        try {
                            Object rawPhotos = latest.get("photoUrls");
                            if (rawPhotos instanceof java.util.List) {
                                for (Object item : (java.util.List<?>) rawPhotos) {
                                    if (item instanceof java.util.Map) {
                                        photos.add((java.util.Map<String, Object>) item);
                                    }
                                }
                            }
                        } catch (Exception castEx) {
                            android.util.Log.e(TAG, "photoUrls cast: " + castEx.getMessage());
                        }
                        final java.util.List<java.util.Map<String, Object>> finalPhotos = photos;
                        runOnUiThread(() ->
                                showEvidenceCard(datetime, audioUrl, lat, lng, finalPhotos));
                    });
        } catch (Exception e) {
            android.util.Log.e(TAG, "listenForEvidence failed: " + e.getMessage());
        }
    }

    private void showEvidenceCard(String datetime, String audioUrl,
                                  Double lat, Double lng,
                                  java.util.List<java.util.Map<String, Object>> photos) {
        android.widget.LinearLayout card    = findViewById(R.id.evidenceCard);
        android.widget.TextView tvTime      = findViewById(R.id.tvEvidenceTime);
        android.widget.TextView tvLoc       = findViewById(R.id.tvEvidenceLocation);
        android.widget.TextView tvAudio     = findViewById(R.id.tvEvidenceAudio);
        android.widget.LinearLayout photoRow = findViewById(R.id.evidencePhotoRow);

        if (card == null) return;
        if (datetime == null && (photos == null || photos.isEmpty())) return;
        card.setVisibility(android.view.View.VISIBLE);

        if (tvTime != null && datetime != null)
            tvTime.setText("Captured: " + datetime);

        if (tvLoc != null && lat != null && lng != null && (lat != 0.0 || lng != 0.0))
            tvLoc.setText(String.format(java.util.Locale.getDefault(),
                    "Location: %.5f, %.5f", lat, lng));

        if (tvAudio != null) {
            if (audioUrl != null && !audioUrl.isEmpty()) {
                tvAudio.setText("\u25b6 Play Audio Recording");
                tvAudio.setTextColor(0xFF10B981);
                tvAudio.setOnClickListener(v -> {
                    try {
                        startActivity(new android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(audioUrl)));
                    } catch (Exception e) {
                        Toast.makeText(this, "Cannot play audio",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                tvAudio.setText("Audio recording in progress... (available after ~60s)");
                tvAudio.setTextColor(0xFFF59E0B);
            }
        }

        if (photoRow != null && photos != null && !photos.isEmpty()) {
            photoRow.removeAllViews();

            // Screen width — each thumbnail gets 45% of screen width so 2 fit side by side
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int thumbSize = (int) (dm.widthPixels * 0.80f);

            for (java.util.Map<String, Object> p : photos) {
                Object urlObj = p.get("url");
                Object tsObj  = p.get("timestamp");
                if (urlObj == null) continue;
                String url = urlObj.toString().trim();
                if (url.isEmpty()) continue;
                String ts = tsObj != null ? tsObj.toString() : "";

                // Card wrapper
                android.widget.LinearLayout col = new android.widget.LinearLayout(this);
                col.setOrientation(android.widget.LinearLayout.VERTICAL);
                col.setBackgroundColor(0xFF1A1A2E);
                android.widget.LinearLayout.LayoutParams colLp =
                        new android.widget.LinearLayout.LayoutParams(thumbSize, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                colLp.setMargins(0, 0, 16, 8);
                col.setLayoutParams(colLp);
                col.setPadding(4, 4, 4, 6);

                // ImageView — large enough to actually see detail
                android.widget.ImageView iv = new android.widget.ImageView(this);
                android.widget.LinearLayout.LayoutParams ivLp =
                        new android.widget.LinearLayout.LayoutParams(thumbSize - 8, thumbSize - 8);
                iv.setLayoutParams(ivLp);
                iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                iv.setBackgroundColor(0xFF0D1117);

                // Loading spinner overlay
                android.widget.ProgressBar spinner = new android.widget.ProgressBar(this);
                android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
                frame.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                        thumbSize - 8, thumbSize - 8));
                android.widget.FrameLayout.LayoutParams spinLp =
                        new android.widget.FrameLayout.LayoutParams(80, 80);
                spinLp.gravity = android.view.Gravity.CENTER;
                spinner.setLayoutParams(spinLp);
                frame.addView(iv);
                frame.addView(spinner);

                // Build Cloudinary thumbnail URL — request exact size to avoid
                // loading a 4MB original into a small ImageView.
                // Pattern: insert /w_<px>,h_<px>,c_fill,q_auto,f_auto/ before upload/
                String thumbUrl = url; // use original URL for reliability

                final String finalThumbUrl = thumbUrl;
                final String finalFullUrl  = url;

                com.bumptech.glide.Glide.with(this)
                        .load(finalThumbUrl)
                        // Disk cache — avoids re-downloading on every dashboard open
                        .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                        // Decode at exact thumbnail size — no loading a 4MP image into RAM
                        .override(thumbSize - 8, thumbSize - 8)
                        .centerInside()
                        // Timeout: 15s — Cloudinary from India can be slow on first hit
                        .apply(new com.bumptech.glide.request.RequestOptions()
                                .timeout(30000))
                        .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                            @Override
                            public boolean onLoadFailed(com.bumptech.glide.load.engine.GlideException e,
                                                        Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                                        boolean isFirstResource) {
                                spinner.setVisibility(android.view.View.GONE);
                                // On failure, try loading original URL (no transformations)
                                runOnUiThread(() ->
                                        com.bumptech.glide.Glide.with(CaregiverDashboardActivity.this)
                                                .load(finalFullUrl)
                                                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                                                .placeholder(android.R.drawable.ic_menu_camera)
                                                .error(android.R.drawable.ic_dialog_alert)
                                                .into(iv));
                                return true;
                            }
                            @Override
                            public boolean onResourceReady(android.graphics.drawable.Drawable resource,
                                                           Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                                           com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                                spinner.setVisibility(android.view.View.GONE);
                                return false;
                            }
                        })
                        .into(iv);

                // Timestamp label
                android.widget.TextView tvTs = new android.widget.TextView(this);
                tvTs.setText(ts.isEmpty() ? "SOS Photo" : ts);
                tvTs.setTextColor(0xFF9CA3AF);
                tvTs.setTextSize(11f);
                tvTs.setPadding(4, 4, 4, 0);
                tvTs.setMaxLines(2);

                // Tap thumbnail → open full-size photo in browser/gallery
                iv.setOnClickListener(v -> {
                    try {
                        android.content.Intent intent = new android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(finalFullUrl));
                        intent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(CaregiverDashboardActivity.this,
                                "Opening in browser...", Toast.LENGTH_SHORT).show();
                        try {
                            startActivity(new android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(finalFullUrl)));
                        } catch (Exception ignored) {}
                    }
                });

                col.addView(frame);
                col.addView(tvTs);
                photoRow.addView(col);
            }
        }
    }


    //  Inner RecyclerView adapter
    // ─────────────────────────────────────────────────────────────────────────
    interface OnStatusChange { void onChange(String docId, String status); }

    static class CaregiverReminderAdapter
            extends RecyclerView.Adapter<CaregiverReminderAdapter.VH> {

        private final List<Map<String, Object>> items;
        private final OnStatusChange callback;

        CaregiverReminderAdapter(List<Map<String, Object>> items, OnStatusChange cb) {
            this.items = items; this.callback = cb;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_caregiver_reminder, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Map<String, Object> item = items.get(pos);
            String docId   = (String) item.get("docId");
            String title   = (String) item.getOrDefault("title",       "—");
            String desc    = (String) item.getOrDefault("description", "");
            String status  = (String) item.getOrDefault("status",      "Pending");
            boolean byCg   = "caregiver".equals(item.get("addedBy"));

            if (h.tvTitle != null) h.tvTitle.setText(title);
            if (h.tvDesc  != null) h.tvDesc.setText(
                    desc.isEmpty() && byCg ? "Added by caregiver" : desc);

            Object rawTime = item.get("time");
            if (h.tvTime != null && rawTime instanceof Long)
                h.tvTime.setText(new SimpleDateFormat("EEE, MMM d  hh:mm a", Locale.getDefault())
                        .format(new Date((Long) rawTime)));

            if (h.tvStatus != null) {
                switch (status) {
                    case "Completed":
                        h.tvStatus.setText("Completed");
                        h.tvStatus.setTextColor(0xFF10B981);
                        h.tvStatus.setBackgroundColor(0xFF0D2B1E); break;
                    case "Missed":
                        h.tvStatus.setText("Missed");
                        h.tvStatus.setTextColor(0xFFEF4444);
                        h.tvStatus.setBackgroundColor(0xFF2B0D0D); break;
                    default:
                        h.tvStatus.setText("Pending");
                        h.tvStatus.setTextColor(0xFFF59E0B);
                        h.tvStatus.setBackgroundColor(0xFF2B1E00); break;
                }
            }

            h.itemView.setOnLongClickListener(v -> {
                if (docId == null) return false;
                String[] labels   = {"Mark Completed", "Mark Missed", "Mark Pending"};
                String[] statuses = {"Completed", "Missed", "Pending"};
                new android.app.AlertDialog.Builder(v.getContext())
                        .setTitle("Update: " + title)
                        .setItems(labels, (d, which) -> callback.onChange(docId, statuses[which]))
                        .show();
                return true;
            });
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvTitle, tvDesc, tvTime, tvStatus;
            VH(@NonNull View v) {
                super(v);
                tvTitle  = v.findViewById(R.id.tvCgTitle);
                tvDesc   = v.findViewById(R.id.tvCgDesc);
                tvTime   = v.findViewById(R.id.tvCgTime);
                tvStatus = v.findViewById(R.id.tvCgStatus);
            }
        }
    }
}