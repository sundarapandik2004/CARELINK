package com.example.accessease;

import android.util.Log;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.HashMap;
import java.util.Map;

/**
 * RiskAnalyzer
 *
 * Reads existing Firestore data and computes a risk level.
 * Called by RiskAlarmReceiver every 30 minutes.
 *
 * ★ FIX: inactiveHours is now computed from lastActive in Firestore,
 *   which is the timestamp the user last opened the app OR the last
 *   time the alarm receiver updated it. This means it works correctly
 *   even when the user's phone has the app closed.
 *
 * Risk levels:
 *   LOW    — everything normal
 *   MEDIUM — 1–2 missed reminders OR inactive 2–4 hours
 *   HIGH   — 3+ missed OR inactive 5+ hours OR active SOS
 *
 * Writes to: user_status/{userId}
 */
public class RiskAnalyzer {

    private static final String TAG = "RiskAnalyzer";

    // ── Thresholds ────────────────────────────────────────────────────────────
    private static final int   MISSED_HIGH       = 3;
    private static final int   MISSED_MEDIUM     = 1;
    private static final float INACTIVE_HIGH_HRS = 5.0f;
    private static final float INACTIVE_MED_HRS  = 2.0f;

    public interface OnRiskAnalyzed {
        void onResult(String riskLevel, String reasons);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Main entry point
    // ─────────────────────────────────────────────────────────────────────────
    public static void analyze(String userId, OnRiskAnalyzed callback) {
        if (userId == null || userId.isEmpty()) return;

        FirebaseFirestore db = FirebaseFirestore.getInstance();

        // Step 1: Read lastActive from Firestore users/{userId}
        // This field is updated by RiskAlarmReceiver.onReceive()
        // So it reflects the last time the user was active regardless of
        // whether the app is currently open
        db.collection("users").document(userId)
                .get()
                .addOnSuccessListener(userDoc -> {
                    Long lastActive = userDoc.getLong("lastActive");

                    // ★ FIX: Calculate hours since last activity
                    // If lastActive is null (never set), treat as 0 hours
                    // so we don't falsely trigger HIGH risk on new accounts
                    float inactiveHours = 0f;
                    if (lastActive != null) {
                        long diffMs = System.currentTimeMillis() - lastActive;
                        inactiveHours = diffMs / (1000f * 60f * 60f);
                        // Cap at 24 hours to avoid extreme values on first launch
                        inactiveHours = Math.min(inactiveHours, 24f);
                    }
                    final float finalInactive = inactiveHours;

                    // Step 2: Count missed reminders
                    db.collection("reminders")
                            .whereEqualTo("userId", userId)
                            .get()
                            .addOnSuccessListener(remSnap -> {
                                int missed = 0, completed = 0;
                                long now = System.currentTimeMillis();

                                for (QueryDocumentSnapshot doc : remSnap) {
                                    String status = doc.getString("status");
                                    Long   time   = doc.getLong("time");

                                    if ("Missed".equals(status)) {
                                        missed++;
                                    } else if ("Pending".equals(status)
                                            && time != null && time < now) {
                                        // Pending but time already passed → treat as missed
                                        missed++;
                                        // Auto-update the status in Firestore
                                        doc.getReference().update("status", "Missed");
                                    } else if ("Completed".equals(status)) {
                                        completed++;
                                    }
                                }
                                final int finalMissed    = missed;
                                final int finalCompleted = completed;

                                // Step 3: Check active SOS
                                db.collection("sos")
                                        .whereEqualTo("userId", userId)
                                        .get()
                                        .addOnSuccessListener(sosSnap -> {
                                            int sosCount = 0;
                                            for (QueryDocumentSnapshot s : sosSnap) {
                                                if ("Active".equals(s.getString("status")))
                                                    sosCount++;
                                            }
                                            final int finalSos = sosCount;

                                            // Step 4: Compute and write risk
                                            String level   = computeLevel(
                                                    finalMissed, finalInactive, finalSos);
                                            String reasons = buildReasons(
                                                    finalMissed, finalInactive,
                                                    finalSos, finalCompleted);

                                            Log.d(TAG, "userId=" + userId
                                                    + " missed=" + finalMissed
                                                    + " inactive=" + String.format("%.1f", finalInactive) + "h"
                                                    + " sos=" + finalSos
                                                    + " → " + level);

                                            writeStatus(db, userId, level,
                                                    finalMissed, finalInactive,
                                                    reasons, finalSos);

                                            if (callback != null)
                                                callback.onResult(level, reasons);
                                        })
                                        .addOnFailureListener(e -> {
                                            // SOS query failed — run without it
                                            String level   = computeLevel(finalMissed, finalInactive, 0);
                                            String reasons = buildReasons(finalMissed, finalInactive, 0, finalCompleted);
                                            writeStatus(db, userId, level, finalMissed, finalInactive, reasons, 0);
                                            if (callback != null) callback.onResult(level, reasons);
                                        });
                            })
                            .addOnFailureListener(e ->
                                    Log.e(TAG, "Reminders query failed: " + e.getMessage()));
                })
                .addOnFailureListener(e ->
                        Log.e(TAG, "User doc failed: " + e.getMessage()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Risk level logic
    // ─────────────────────────────────────────────────────────────────────────
    private static String computeLevel(int missed, float inactive, int sos) {
        if (sos > 0)                       return "HIGH";
        if (missed >= MISSED_HIGH)          return "HIGH";
        if (inactive >= INACTIVE_HIGH_HRS)  return "HIGH";
        if (missed >= MISSED_MEDIUM)        return "MEDIUM";
        if (inactive >= INACTIVE_MED_HRS)   return "MEDIUM";
        return "LOW";
    }

    private static String buildReasons(int missed, float inactive,
                                       int sos, int completed) {
        StringBuilder sb = new StringBuilder();

        if (sos > 0)
            sb.append("Active SOS alert triggered. ");

        if (missed >= MISSED_HIGH)
            sb.append("Missed ").append(missed).append(" medication reminders. ");
        else if (missed >= MISSED_MEDIUM)
            sb.append("Missed ").append(missed).append(" reminder(s). ");

        if (inactive >= INACTIVE_HIGH_HRS)
            sb.append(String.format("No activity for %.0f hours. ", inactive));
        else if (inactive >= INACTIVE_MED_HRS)
            sb.append(String.format("Inactive for %.0f hours. ", inactive));

        if (sb.length() == 0) {
            if (completed > 0)
                sb.append("All reminders completed. User is active.");
            else
                sb.append("User appears healthy and active.");
        }

        return sb.toString().trim();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Write result to user_status/{userId}
    //  Caregiver dashboard's snapshot listener fires the moment this writes
    // ─────────────────────────────────────────────────────────────────────────
    private static void writeStatus(FirebaseFirestore db, String userId,
                                    String level, int missed,
                                    float inactive, String reasons, int sos) {
        Map<String, Object> data = new HashMap<>();
        data.put("riskLevel",     level);
        data.put("missedCount",   missed);
        data.put("inactiveHours", Math.round(inactive * 10) / 10.0);
        data.put("reasons",       reasons);
        data.put("checkedAt",     System.currentTimeMillis());
        data.put("sosCount",      sos);

        db.collection("user_status").document(userId)
                .set(data)
                .addOnSuccessListener(v -> Log.d(TAG, "Risk written: " + level))
                .addOnFailureListener(e -> Log.e(TAG, "Risk write failed: " + e.getMessage()));
    }
}