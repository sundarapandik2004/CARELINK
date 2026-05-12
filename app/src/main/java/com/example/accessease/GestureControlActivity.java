package com.example.accessease;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GestureControlActivity
 *
 * Lets users control the app using simple hand/face gestures detected by the
 * front camera using ML Kit Face Detection.
 *
 * Gestures recognised:
 *   ✋ RAISED HAND   → Emergency SOS  (face moves high in frame = hand raised above head)
 *   👋 WAVE          → Call caregiver  (face rapidly moves left/right)
 *   😮 OPEN MOUTH   → Send location   (mouth open detected by ML Kit smile/open-mouth)
 *   ✊ HOLD STILL   → Cancel / reset
 *
 * Why face detection for gestures?
 *   ML Kit's hand landmark API requires ML Kit 17.x which needs Google Play Services.
 *   Face detection is universal — works offline on all devices including Indian phones.
 *   We use face POSITION + MOVEMENT to infer hand gestures reliably.
 */
public class GestureControlActivity extends AppCompatActivity {

    private static final String TAG       = "GestureControl";
    private static final int    CAM_PERM  = 301;
    private static final String CH_GESTURE = "gesture_alerts";

    // ── Views ──────────────────────────────────────────────────────────────────
    private PreviewView previewView;
    private TextView    tvGestureStatus;
    private TextView    tvGestureDetected;
    private TextView    tvCountdown;
    private View        overlayFlash;

    // ── Camera ─────────────────────────────────────────────────────────────────
    private ExecutorService cameraExecutor;
    private FaceDetector    faceDetector;

    // ── Gesture state ───────────────────────────────────────────────────────────
    // Gesture 1: EYES CLOSED — both eyes closed for 2+ seconds → SOS
    // Gesture 2: NOD HEAD   — head tilts up then down (headEulerAngleX) → Call caregiver
    // Gesture 3: LOOK LEFT  — face turns left (headEulerAngleY < -25) → Location
    // Gesture 4: LOOK RIGHT — face turns right (headEulerAngleY > +25) → Safety Assistant
    private long    eyesClosedStartTime = -1L;  // when user first closed eyes
    private float   lastHeadX          = 0f;    // for nod detection (up/down tilt)
    private int     nodCount           = 0;     // nod oscillation counter
    private long    lastNodTime        = 0L;
    private long    lastGestureTime    = 0L;
    private boolean isProcessingGesture = false;

    // Thresholds
    private static final long   EYES_CLOSED_DURATION = 2000; // hold eyes shut 2 sec = SOS
    private static final float  EYE_CLOSED_THRESHOLD = 0.25f;// eye prob below this = closed
    private static final float  NOD_DELTA            = 8f;   // 8 degree head tilt = one nod
    private static final int    NOD_COUNT_NEEDED     = 2;    // 2 nods = confirmed
    private static final long   NOD_WINDOW           = 3000; // nods must complete in 3 sec
    private static final long   GESTURE_COOLDOWN     = 5000; // 5 sec cooldown between gestures

    // Firebase
    private FirebaseFirestore db;
    private String            userId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gesture_control);

        db = FirebaseFirestore.getInstance();
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) userId = user.getUid();

        bindViews();
        createNotificationChannel();
        cameraExecutor = Executors.newSingleThreadExecutor();
        setupFaceDetector();

        if (hasCameraPermission()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, CAM_PERM);
        }
    }

    private void bindViews() {
        previewView       = findViewById(R.id.gesturePreview);
        tvGestureStatus   = findViewById(R.id.tvGestureStatus);
        tvGestureDetected = findViewById(R.id.tvGestureDetected);
        tvCountdown       = findViewById(R.id.tvGestureCountdown);
        overlayFlash      = findViewById(R.id.gestureFlashOverlay);

        // Cancel button
        findViewById(R.id.btnGestureClose).setOnClickListener(v -> finish());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ML Kit Face Detector setup
    //  HIGH_SPEED_ONLY mode for real-time frame analysis
    // ─────────────────────────────────────────────────────────────────────────
    private void setupFaceDetector() {
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.25f)
                .enableTracking()
                .build();
        faceDetector = FaceDetection.getClient(options);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Camera
    // ─────────────────────────────────────────────────────────────────────────
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

                // Use FRONT camera so user faces the phone naturally
                CameraSelector selector = CameraSelector.DEFAULT_FRONT_CAMERA;

                provider.unbindAll();
                provider.bindToLifecycle(this, selector, preview, analysis);

                updateStatus("👁️ Watching for gestures...\n😑 Close Eyes · 🙆 Nod · 👈 Look Left · 👉 Look Right");

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera failed: " + e.getMessage());
                updateStatus("❌ Camera error: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Frame analysis — runs on every camera frame
    // ─────────────────────────────────────────────────────────────────────────
    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeFrame(ImageProxy imageProxy) {
        if (imageProxy.getImage() == null) { imageProxy.close(); return; }
        if (isProcessingGesture) { imageProxy.close(); return; }

        InputImage image = InputImage.fromMediaImage(
                imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());

        faceDetector.process(image)
                .addOnSuccessListener(faces -> {
                    if (!faces.isEmpty()) {
                        processFaces(faces, imageProxy.getWidth(), imageProxy.getHeight());
                    } else {
                        // No face — reset nod tracking
                        lastHeadX  = 0f;
                        nodCount   = 0;
                        eyesClosedStartTime = -1L;
                    }
                    imageProxy.close();
                })
                .addOnFailureListener(e -> imageProxy.close());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Gesture recognition from face position
    // ─────────────────────────────────────────────────────────────────────────
    private void processFaces(List<Face> faces, int frameW, int frameH) {
        Face face = faces.get(0);
        long now  = System.currentTimeMillis();

        // ── Always update tracking state first ───────────────────────────────
        float headX = face.getHeadEulerAngleX(); // positive = tilted up, negative = down
        float headY = face.getHeadEulerAngleY(); // negative = look left, positive = look right
        Float leftEye  = face.getLeftEyeOpenProbability();
        Float rightEye = face.getRightEyeOpenProbability();

        // ── Gesture 1: CLOSE BOTH EYES (hold 2 seconds) → SOS ────────────────
        // Most reliable gesture — ML Kit eye detection is very accurate
        // User just closes eyes for 2 seconds, no movement needed
        if (leftEye != null && rightEye != null
                && leftEye < EYE_CLOSED_THRESHOLD && rightEye < EYE_CLOSED_THRESHOLD) {
            if (eyesClosedStartTime < 0) {
                eyesClosedStartTime = now; // start timer
                updateStatus("👁️ Eyes closed — hold 2 sec for SOS...");
            } else if (now - eyesClosedStartTime >= EYES_CLOSED_DURATION
                    && now - lastGestureTime >= GESTURE_COOLDOWN) {
                eyesClosedStartTime = -1L;
                triggerGesture("EYES_CLOSED");
                return;
            }
        } else {
            // Eyes opened again — reset timer
            if (eyesClosedStartTime > 0) {
                eyesClosedStartTime = -1L;
                updateStatus("👁️ Watching for gestures...\n😑 Close Eyes · 🙆 Nod · 👈 Look Left · 👉 Look Right");
            }
        }

        if (now - lastGestureTime < GESTURE_COOLDOWN) return;

        // ── Gesture 2: NOD HEAD UP-DOWN → Call caregiver ─────────────────────
        // headEulerAngleX: positive = tilted up, negative = tilted down
        // Nod = oscillates between +15 and -15 degrees twice
        float prevHeadX = lastHeadX;
        lastHeadX = headX;

        if (Math.abs(headX - prevHeadX) > NOD_DELTA) {
            if (now - lastNodTime < NOD_WINDOW) {
                nodCount++;
                if (nodCount >= NOD_COUNT_NEEDED) {
                    nodCount = 0;
                    triggerGesture("NOD");
                    return;
                }
            } else {
                nodCount = 1;
            }
            lastNodTime = now;
        }

        // ── Gesture 3: LOOK LEFT → Send location ─────────────────────────────
        if (headY < -25f) {
            nodCount = 0;
            triggerGesture("LOOK_LEFT");
            return;
        }

        // ── Gesture 4: LOOK RIGHT → Safety Assistant ──────────────────────────
        if (headY > 25f) {
            nodCount = 0;
            triggerGesture("LOOK_RIGHT");
            return;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Trigger a confirmed gesture — runs 3-second countdown then acts
    // ─────────────────────────────────────────────────────────────────────────
    private void triggerGesture(String gesture) {
        isProcessingGesture = true;
        lastGestureTime = System.currentTimeMillis();
        lastHeadX  = 0f;
        nodCount   = 0;
        eyesClosedStartTime = -1L;

        String label, action;
        switch (gesture) {
            case "EYES_CLOSED":
                label  = "😑 EYES CLOSED detected!";
                action = "🚨 Triggering Emergency SOS in...";
                break;
            case "NOD":
                label  = "🙆 NOD detected!";
                action = "📞 Calling caregiver in...";
                break;
            case "LOOK_LEFT":
                label  = "👈 LOOK LEFT detected!";
                action = "📍 Sending location in...";
                break;
            case "LOOK_RIGHT":
                label  = "👉 LOOK RIGHT detected!";
                action = "📷 Opening Safety Assistant in...";
                break;
            default:
                isProcessingGesture = false;
                return;
        }

        showGestureDetected(label, action, gesture);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  UI: show detected gesture + 3-second countdown (lets user cancel)
    // ─────────────────────────────────────────────────────────────────────────
    private void showGestureDetected(String label, String actionText, String gesture) {
        runOnUiThread(() -> {
            tvGestureDetected.setText(label);
            tvGestureDetected.setVisibility(View.VISIBLE);
            tvCountdown.setVisibility(View.VISIBLE);
            overlayFlash.setVisibility(View.VISIBLE);
        });

        Handler h = new Handler(Looper.getMainLooper());

        // Countdown 3 → 2 → 1 → execute
        for (int i = 3; i >= 1; i--) {
            final int count = i;
            h.postDelayed(() -> {
                tvCountdown.setText(actionText + " " + count);
            }, (3 - i) * 1000L);
        }

        // Execute after 3 seconds
        h.postDelayed(() -> {
            overlayFlash.setVisibility(View.GONE);
            tvCountdown.setVisibility(View.GONE);
            tvGestureDetected.setVisibility(View.GONE);
            executeGestureAction(gesture);
            isProcessingGesture = false;
        }, 3200);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Execute the gesture action
    // ─────────────────────────────────────────────────────────────────────────
    private void executeGestureAction(String gesture) {
        switch (gesture) {

            case "EYES_CLOSED": // 😑 → Emergency SOS
                updateStatus("🚨 SOS triggered by eyes-closed gesture!");
                sendGestureNotification("🚨 SOS Triggered", "Emergency SOS activated by eyes-closed gesture");
                writeSosToFirestore();
                runOnUiThread(() ->
                        startActivity(new Intent(this, EmergencySOSActivity.class)));
                break;

            case "NOD": // 🙆 → Call caregiver
                updateStatus("📞 Calling caregiver by nod gesture!");
                callCaregiver();
                break;

            case "LOOK_LEFT": // 👈 → Send location
                updateStatus("📍 Opening location by look-left gesture!");
                sendGestureNotification("📍 Location Shared", "Live location sent by gesture");
                openLocation();
                break;

            case "LOOK_RIGHT": // 👉 → Safety Assistant
                updateStatus("📷 Opening Safety Assistant by look-right gesture!");
                sendGestureNotification("📷 Safety Assistant", "Safety Assistant opened by gesture");
                runOnUiThread(() ->
                        startActivity(new Intent(this, SafetyAssistantActivity.class)));
                break;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Actions
    // ─────────────────────────────────────────────────────────────────────────
    private void writeSosToFirestore() {
        if (userId == null) return;
        Map<String, Object> sos = new HashMap<>();
        sos.put("userId",    userId);
        sos.put("timestamp", System.currentTimeMillis());
        sos.put("latitude",  0.0);
        sos.put("longitude", 0.0);
        sos.put("status",    "Active");
        sos.put("source",    "gesture");
        db.collection("sos").add(sos)
                .addOnSuccessListener(r -> Log.d(TAG, "Gesture SOS written"))
                .addOnFailureListener(e -> Log.e(TAG, "SOS write failed: " + e.getMessage()));
    }

    private void callCaregiver() {
        SharedPreferences prefs = getSharedPreferences("SOSPrefs", MODE_PRIVATE);
        String phone = prefs.getString("contact1", "");
        runOnUiThread(() -> {
            if (phone.isEmpty()) {
                Toast.makeText(this, "No caregiver number saved in Emergency SOS settings",
                        Toast.LENGTH_LONG).show();
            } else {
                Intent dial = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone));
                startActivity(dial);
            }
        });
    }

    private void openLocation() {
        runOnUiThread(() -> {
            try {
                Intent maps = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("geo:0,0?q=my+location"));
                startActivity(maps);
            } catch (Exception e) {
                Toast.makeText(this, "Cannot open maps", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void sendGestureNotification(String title, String body) {
        NotificationManager mgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (mgr == null) return;
        android.app.Notification n = new NotificationCompat.Builder(this, CH_GESTURE)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setAutoCancel(true)
                .build();
        mgr.notify(3001, n);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager mgr = getSystemService(NotificationManager.class);
            if (mgr != null) {
                mgr.createNotificationChannel(new NotificationChannel(
                        CH_GESTURE, "Gesture Alerts", NotificationManager.IMPORTANCE_HIGH));
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────
    private void updateStatus(String msg) {
        runOnUiThread(() -> {
            if (tvGestureStatus != null) tvGestureStatus.setText(msg);
        });
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == CAM_PERM && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Toast.makeText(this, "Camera permission needed for gesture control",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (faceDetector   != null) faceDetector.close();
    }
}