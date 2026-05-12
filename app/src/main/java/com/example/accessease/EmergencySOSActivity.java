package com.example.accessease;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class EmergencySOSActivity extends AppCompatActivity {

    private static final String TAG = "EmergencySOSActivity";
    private static final int PERMISSION_REQUEST_CODE = 300;
    private static final String PREFS_NAME = "SOSPrefs";

    // ── UI ────────────────────────────────────────────────────────────────────
    private FrameLayout btnSOS;
    private TextView tvSOSLabel;      // "SOS"   — changes to "STOP" when active
    private TextView tvSOSSubLabel;   // "PRESS" — changes to "TAP TO STOP"
    private Button btnSaveContacts, btnStopAlarm;
    private EditText etContact1, etContact2, etContact3;
    private EditText etName1, etName2, etName3;
    private TextView tvStatus;
    private SwitchCompat switchLocation, switchAlarm, switchAutoCall, switchFlashlight;

    // ── Location ──────────────────────────────────────────────────────────────
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private double currentLatitude  = 0;
    private double currentLongitude = 0;
    private boolean locationAcquired = false;

    // ── Other ─────────────────────────────────────────────────────────────────
    private MediaPlayer alarmPlayer;
    private Vibrator vibrator;
    private CameraManager cameraManager;
    private String cameraId;
    private boolean isFlashlightOn = false;
    private boolean isSOSActive    = false;

    // ── Firebase ──────────────────────────────────────────────────────────────
    private FirebaseFirestore db;
    private String currentUserId;

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_emergency_sos);

        // Firebase
        db = FirebaseFirestore.getInstance();
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        currentUserId = (user != null) ? user.getUid() : null;

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        initViews();
        loadSavedContacts();
        setupListeners();
        setupCameraManager();
        setupLocationCallback();
        checkAndRequestAllPermissions();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  View binding
    // ─────────────────────────────────────────────────────────────────────────
    private void initViews() {
        btnSOS = findViewById(R.id.btnSOS);

        // ★ These IDs now exist in the XML (the fix)
        tvSOSLabel    = findViewById(R.id.tvSOSLabel);
        tvSOSSubLabel = findViewById(R.id.tvSOSSubLabel);

        btnSaveContacts = findViewById(R.id.btnSaveContacts);
        btnStopAlarm    = findViewById(R.id.btnStopAlarm);

        etContact1 = findViewById(R.id.etContact1);
        etContact2 = findViewById(R.id.etContact2);
        etContact3 = findViewById(R.id.etContact3);
        etName1    = findViewById(R.id.etName1);
        etName2    = findViewById(R.id.etName2);
        etName3    = findViewById(R.id.etName3);

        tvStatus         = findViewById(R.id.tvStatus);
        switchLocation   = findViewById(R.id.switchLocation);
        switchAlarm      = findViewById(R.id.switchAlarm);
        switchAutoCall   = findViewById(R.id.switchAutoCall);
        switchFlashlight = findViewById(R.id.switchFlashlight);
    }

    private void setupListeners() {
        btnSOS.setOnClickListener(v -> {
            if (!isSOSActive) showSOSConfirmation();
            else              stopAllAlerts();
        });
        btnSaveContacts.setOnClickListener(v -> saveContacts());
        btnStopAlarm.setOnClickListener(v -> stopAllAlerts());
    }

    private void setupCameraManager() {
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if (cameraManager != null && cameraManager.getCameraIdList().length > 0) {
                cameraId = cameraManager.getCameraIdList()[0];
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera error: " + e.getMessage());
        }
    }

    private void setupLocationCallback() {
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                if (result.getLastLocation() != null) {
                    currentLatitude  = result.getLastLocation().getLatitude();
                    currentLongitude = result.getLastLocation().getLongitude();
                    Log.d(TAG, "Location fix: " + currentLatitude + ", " + currentLongitude);
                    // If SOS is active and we haven't sent SMS yet, send now with real coords
                    if (isSOSActive && !locationAcquired) {
                        locationAcquired = true;
                        sendEmergencyMessages();
                    }
                }
            }
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Permissions
    // ─────────────────────────────────────────────────────────────────────────
    private void checkAndRequestAllPermissions() {
        ArrayList<String> needed = new ArrayList<>();
        String[] required = {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.SEND_SMS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.CAMERA,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.RECORD_AUDIO  // needed by EvidenceCaptureService
        };
        for (String p : required) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                needed.add(p);
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    needed.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    private boolean hasSMSPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  SOS flow
    // ─────────────────────────────────────────────────────────────────────────
    private void showSOSConfirmation() {
        new AlertDialog.Builder(this)
                .setTitle("⚠️ ACTIVATE EMERGENCY SOS?")
                .setMessage("This will immediately:\n\n"
                        + "• Send SMS + live location to all contacts\n"
                        + "• Play loud alarm (if enabled)\n"
                        + "• Auto-call Contact 1 (if enabled)\n"
                        + "• Flash torch in SOS pattern (if enabled)\n\n"
                        + "Only use in a real emergency!")
                .setPositiveButton("ACTIVATE SOS", (d, w) -> activateSOS())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void activateSOS() {
        Log.d(TAG, "SOS ACTIVATED");
        isSOSActive      = true;
        locationAcquired = false;

        // Update button appearance
        tvSOSLabel.setText("SOS");
        tvSOSSubLabel.setText("TAP TO STOP");
        tvSOSLabel.setTextColor(0xFFFFFFFF);
        tvSOSSubLabel.setTextColor(0xFFFF6B6B);

        tvStatus.setText("🚨 SOS ACTIVE — Alerting contacts...");
        tvStatus.setTextColor(0xFFEF4444);
        btnStopAlarm.setVisibility(View.VISIBLE);

        startVibration();

        if (switchLocation.isChecked() && hasLocationPermission()) {
            startLocationUpdates();
            // Safety fallback — send after 5s even without a GPS fix
            new android.os.Handler().postDelayed(() -> {
                if (!locationAcquired) {
                    locationAcquired = true;
                    Log.d(TAG, "GPS timeout — sending without location");
                    sendEmergencyMessages();
                }
            }, 5000);
        } else {
            sendEmergencyMessages();
        }

        if (switchAlarm.isChecked())      playAlarm();
        if (switchFlashlight.isChecked()) startFlashlight();
        if (switchAutoCall.isChecked()) {
            new android.os.Handler().postDelayed(this::makeEmergencyCall, 4000);
        }

        triggerFirestoreSOS();
        // Evidence capture wrapped in try-catch
        // so SOS always works even if evidence service has issues
        try {
            EvidenceCaptureService.start(this);
        } catch (Exception e) {
            android.util.Log.e("SOS", "Evidence capture failed: " + e.getMessage());
        }
        Toast.makeText(this, "🚨 EMERGENCY SOS ACTIVATED!", Toast.LENGTH_LONG).show();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Location — FusedLocationProviderClient
    // ─────────────────────────────────────────────────────────────────────────
    private void startLocationUpdates() {
        if (!hasLocationPermission()) return;

        // Try last known first — instant if available
        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null && !locationAcquired) {
                currentLatitude  = location.getLatitude();
                currentLongitude = location.getLongitude();
                locationAcquired = true;
                Log.d(TAG, "Last known location used: " + currentLatitude + ", " + currentLongitude);
                sendEmergencyMessages();
            }
        });

        // Also request fresh live updates
        LocationRequest req = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, 2000)
                .setMinUpdateIntervalMillis(1000)
                .setMaxUpdates(5)
                .build();
        try {
            fusedLocationClient.requestLocationUpdates(req, locationCallback, getMainLooper());
        } catch (SecurityException e) {
            Log.e(TAG, "Location security error: " + e.getMessage());
        }
    }

    private void stopLocationUpdates() {
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  SMS — fixed for all Android versions and dual-SIM phones
    // ─────────────────────────────────────────────────────────────────────────
    private void sendEmergencyMessages() {
        if (!hasSMSPermission()) {
            runOnUiThread(() -> {
                tvStatus.setText("❌ SMS permission denied. Go to Settings → Permissions.");
                Toast.makeText(this, "SMS permission not granted!", Toast.LENGTH_LONG).show();
            });
            return;
        }

        String c1 = etContact1.getText().toString().trim();
        String c2 = etContact2.getText().toString().trim();
        String c3 = etContact3.getText().toString().trim();

        if (c1.isEmpty() && c2.isEmpty() && c3.isEmpty()) {
            runOnUiThread(() -> {
                tvStatus.setText("⚠️ No contacts saved! Please add contacts first.");
                Toast.makeText(this, "No emergency contacts saved!", Toast.LENGTH_LONG).show();
            });
            return;
        }

        // Build message
        String locationText;
        if (currentLatitude != 0 && currentLongitude != 0) {
            locationText = "\n\n📍 My live location:\n"
                    + "https://maps.google.com/?q="
                    + currentLatitude + "," + currentLongitude;
        } else {
            locationText = "\n\n📍 Location unavailable (GPS off or not ready).";
        }

        String message = "🚨 EMERGENCY SOS!\n"
                + "I need immediate help!\n"
                + "This is an automatic alert from CareLink app."
                + locationText;

        int sent = 0;
        if (!c1.isEmpty() && sendSMS(c1, message)) sent++;
        if (!c2.isEmpty() && sendSMS(c2, message)) sent++;
        if (!c3.isEmpty() && sendSMS(c3, message)) sent++;

        final int finalSent = sent;
        final boolean hasLocation = currentLatitude != 0;
        runOnUiThread(() -> {
            if (finalSent > 0) {
                String loc = hasLocation ? " with live location ✅" : " (no location)";
                tvStatus.setText("✅ SMS sent to " + finalSent + " contact(s)" + loc);
                Toast.makeText(this,
                        "✅ Emergency SMS sent to " + finalSent + " contact(s)!",
                        Toast.LENGTH_LONG).show();
            } else {
                tvStatus.setText("❌ SMS failed — check number format (+91XXXXXXXXXX)");
                Toast.makeText(this,
                        "SMS failed. Check number format and network.",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * sendSMS — handles Android version differences and dual-SIM phones correctly.
     *
     * Root cause of old failure:
     *   SmsManager.getDefault() picks NO SIM on dual-SIM devices,
     *   causing silent failure with no exception thrown.
     *
     * Fix:
     *   Android 12+ → getSystemService(SmsManager.class)
     *   Android 5.1–11 → getSmsManagerForSubscriptionId(defaultSmsSubscriptionId)
     *   Older → getDefault() fallback
     */
    private boolean sendSMS(String number, String message) {
        try {
            Log.d(TAG, "Sending SMS to: " + number);
            SmsManager sms;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                sms = getSystemService(SmsManager.class);
                if (sms == null) sms = SmsManager.getDefault();
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                int subId = SubscriptionManager.getDefaultSmsSubscriptionId();
                sms = (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                        ? SmsManager.getSmsManagerForSubscriptionId(subId)
                        : SmsManager.getDefault();
            } else {
                sms = SmsManager.getDefault();
            }

            ArrayList<String> parts = sms.divideMessage(message);
            if (parts.size() == 1) {
                sms.sendTextMessage(number, null, message, null, null);
            } else {
                sms.sendMultipartTextMessage(number, null, parts, null, null);
            }

            Log.d(TAG, "SMS queued to: " + number);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "SMS failed to " + number + ": " + e.getMessage(), e);
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Call, Alarm, Vibration, Flashlight
    // ─────────────────────────────────────────────────────────────────────────
    private void makeEmergencyCall() {
        String number = etContact1.getText().toString().trim();
        if (number.isEmpty()) return;
        try {
            boolean canCall = ContextCompat.checkSelfPermission(this,
                    Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED;
            Intent i = new Intent(canCall ? Intent.ACTION_CALL : Intent.ACTION_DIAL);
            i.setData(Uri.parse("tel:" + number));
            startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "Call error: " + e.getMessage());
        }
    }

    private void playAlarm() {
        try {
            if (alarmPlayer != null) { alarmPlayer.release(); alarmPlayer = null; }
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null)
                am.setStreamVolume(AudioManager.STREAM_ALARM,
                        am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
            Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (sound == null) sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            alarmPlayer = new MediaPlayer();
            alarmPlayer.setDataSource(this, sound);
            alarmPlayer.setAudioStreamType(AudioManager.STREAM_ALARM);
            alarmPlayer.setLooping(true);
            alarmPlayer.prepare();
            alarmPlayer.start();
        } catch (Exception e) {
            Log.e(TAG, "Alarm error: " + e.getMessage());
        }
    }

    private void startVibration() {
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        long[] pattern = {0, 500, 200, 500, 200, 500};
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
        } else {
            vibrator.vibrate(pattern, 0);
        }
    }

    /** Flashes in SOS morse pattern: ··· --- ··· */
    private void startFlashlight() {
        if (cameraManager == null || cameraId == null
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        new Thread(() -> {
            int[] onDurations  = {200, 200, 200, 600, 600, 600, 200, 200, 200};
            int[] offDurations = {200, 200, 400, 200, 200, 400, 200, 200, 800};
            try {
                while (isSOSActive && switchFlashlight.isChecked()) {
                    for (int i = 0; i < onDurations.length; i++) {
                        if (!isSOSActive) break;
                        cameraManager.setTorchMode(cameraId, true);
                        isFlashlightOn = true;
                        Thread.sleep(onDurations[i]);
                        cameraManager.setTorchMode(cameraId, false);
                        isFlashlightOn = false;
                        Thread.sleep(offDurations[i]);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Flashlight error: " + e.getMessage());
            }
        }).start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Firebase SOS
    // ─────────────────────────────────────────────────────────────────────────
    private void triggerFirestoreSOS() {
        if (currentUserId == null) return;
        Map<String, Object> sos = new HashMap<>();
        sos.put("userId",    currentUserId);
        sos.put("timestamp", System.currentTimeMillis());
        sos.put("status",    "Active");
        sos.put("latitude",  currentLatitude);
        sos.put("longitude", currentLongitude);
        db.collection("sos").add(sos)
                .addOnSuccessListener(r -> Log.d(TAG, "Firestore SOS: " + r.getId()))
                .addOnFailureListener(e -> Log.e(TAG, "Firestore SOS failed: " + e.getMessage()));
    }

    private void resolveFirestoreSOS() {
        if (currentUserId == null) return;
        db.collection("sos")
                .whereEqualTo("userId", currentUserId)
                .whereEqualTo("status", "Active")
                .get()
                .addOnSuccessListener(snap -> {
                    for (var doc : snap.getDocuments())
                        doc.getReference().update("status", "Resolved");
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Stop all alerts
    // ─────────────────────────────────────────────────────────────────────────
    private void stopAllAlerts() {
        isSOSActive      = false;
        locationAcquired = false;

        // Reset button text
        tvSOSLabel.setText("SOS");
        tvSOSLabel.setTextColor(0xFFEF4444);
        tvSOSSubLabel.setText("PRESS");
        tvSOSSubLabel.setTextColor(0xFF9C4040);

        tvStatus.setText("Press SOS button in emergency");
        tvStatus.setTextColor(0xFF9CA3AF);
        btnStopAlarm.setVisibility(View.GONE);

        if (alarmPlayer != null) {
            if (alarmPlayer.isPlaying()) alarmPlayer.stop();
            alarmPlayer.release();
            alarmPlayer = null;
        }
        if (vibrator != null) vibrator.cancel();
        try {
            if (cameraManager != null && cameraId != null && isFlashlightOn
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cameraManager.setTorchMode(cameraId, false);
                isFlashlightOn = false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Flashlight stop error: " + e.getMessage());
        }

        stopLocationUpdates();
        resolveFirestoreSOS();
        Toast.makeText(this, "All alerts stopped", Toast.LENGTH_SHORT).show();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Contacts
    // ─────────────────────────────────────────────────────────────────────────
    private void saveContacts() {
        SharedPreferences.Editor ed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        ed.putString("contact1", etContact1.getText().toString().trim());
        ed.putString("contact2", etContact2.getText().toString().trim());
        ed.putString("contact3", etContact3.getText().toString().trim());
        ed.putString("name1",    etName1.getText().toString().trim());
        ed.putString("name2",    etName2.getText().toString().trim());
        ed.putString("name3",    etName3.getText().toString().trim());
        ed.apply();
        Toast.makeText(this,
                "✅ Contacts saved!\nRemember: use +91XXXXXXXXXX format",
                Toast.LENGTH_LONG).show();
    }

    private void loadSavedContacts() {
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        etContact1.setText(p.getString("contact1", ""));
        etContact2.setText(p.getString("contact2", ""));
        etContact3.setText(p.getString("contact3", ""));
        etName1.setText(p.getString("name1", ""));
        etName2.setText(p.getString("name2", ""));
        etName3.setText(p.getString("name3", ""));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Permission result
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST_CODE) return;

        StringBuilder denied = new StringBuilder();
        for (int i = 0; i < permissions.length; i++) {
            if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                String name = permissions[i].substring(permissions[i].lastIndexOf('.') + 1);
                denied.append("• ").append(name).append("\n");
            }
        }
        if (denied.length() > 0) {
            new AlertDialog.Builder(this)
                    .setTitle("Permissions Required")
                    .setMessage("These are denied — SOS may not fully work:\n\n"
                            + denied + "\nGo to Settings to grant them.")
                    .setPositiveButton("Open Settings", (d, w) -> {
                        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        i.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    })
                    .setNegativeButton("Skip", null)
                    .show();
        } else {
            Toast.makeText(this, "✅ All permissions granted! SOS is ready.", Toast.LENGTH_SHORT).show();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void onBackPressed() {
        if (isSOSActive) {
            new AlertDialog.Builder(this)
                    .setTitle("SOS Still Active")
                    .setMessage("Stop all alerts before leaving?")
                    .setPositiveButton("Stop & Exit", (d, w) -> {
                        stopAllAlerts();
                        super.onBackPressed();
                    })
                    .setNegativeButton("Keep Active", (d, w) -> super.onBackPressed())
                    .show();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        stopAllAlerts();
        super.onDestroy();
    }
}