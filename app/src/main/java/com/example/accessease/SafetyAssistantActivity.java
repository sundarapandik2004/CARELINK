package com.example.accessease;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.Button;
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
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SafetyAssistantActivity extends AppCompatActivity {

    private static final String TAG         = "SafetyAssistant";
    private static final int    CAMERA_PERM = 301;
    private static final long   SPEAK_GAP   = 6000L; // speak at most once every 6 seconds

    // ── Views ─────────────────────────────────────────────────────────────────
    private PreviewView previewView;
    private TextView    tvDetectedObjects, tvCameraStatus, tvObjectCount;
    private Button      btnSpeakNow, btnPauseResume;
    private View        alertBanner;
    private TextView    tvAlertText;

    // ── Camera & ML Kit ───────────────────────────────────────────────────────
    private ProcessCameraProvider cameraProvider;
    private ImageLabeler          imageLabeler;   // ★ replaced ObjectDetector
    private ExecutorService       cameraExecutor;

    // ── TTS & State ───────────────────────────────────────────────────────────
    private TextToSpeech tts;
    private boolean      ttsReady      = false;
    private boolean      isPaused      = false;
    private long         lastSpeakTime = 0;
    private String       lastSpoken    = "";

    // Latest spoken labels — updated from camera thread
    private volatile List<String> latestSpoken = new ArrayList<>();

    // ── Comprehensive label → voice phrase map (80+ objects) ─────────────────
    // ML Kit default model returns category names. We map every known label
    // to a clear, natural voice phrase for the user.
    private static final Map<String, String> LABEL_MAP = new HashMap<String, String>() {{
        // ── People ────────────────────────────────────────────────────────────
        put("Person",           "Person ahead");
        put("Man",              "Person ahead");
        put("Woman",            "Person ahead");
        put("Human face",       "Person nearby");
        put("Human body",       "Person nearby");
        put("Human hand",       "Hand detected");
        put("Human eye",        "Face detected");
        put("Human head",       "Person nearby");
        put("Boy",              "Child ahead");
        put("Girl",             "Child ahead");
        put("Human",            "Person ahead");
        put("Crowd",            "Crowd of people ahead");

        // ── Vehicles ─────────────────────────────────────────────────────────
        put("Vehicle",          "Vehicle nearby. Be careful");
        put("Car",              "Car nearby. Be careful");
        put("Truck",            "Truck nearby. Be careful");
        put("Bus",              "Bus nearby. Be careful");
        put("Motorcycle",       "Motorcycle nearby. Be careful");
        put("Bicycle",          "Bicycle nearby");
        put("Scooter",          "Scooter nearby. Be careful");
        put("Auto rickshaw",    "Auto rickshaw nearby. Be careful");
        put("Van",              "Van nearby. Be careful");
        put("Ambulance",        "Ambulance nearby");
        put("Train",            "Train nearby. Be careful");
        put("Airplane",         "Airplane detected");
        put("Boat",             "Boat detected");
        put("Wheel",            "Vehicle wheel detected");
        put("Tire",             "Tire detected");

        // ── Animals ───────────────────────────────────────────────────────────
        put("Animal",           "Animal nearby");
        put("Dog",              "Dog nearby");
        put("Cat",              "Cat nearby");
        put("Bird",             "Bird nearby");
        put("Snake",            "Snake detected. Be careful");
        put("Cow",              "Cow nearby");
        put("Horse",            "Horse nearby");
        put("Elephant",         "Elephant nearby");
        put("Insect",           "Insect nearby");
        put("Spider",           "Spider nearby");

        // ── Furniture and indoor objects ──────────────────────────────────────
        put("Chair",            "Chair ahead");
        put("Table",            "Table ahead");
        put("Desk",             "Desk ahead");
        put("Couch",            "Sofa ahead");
        put("Sofa",             "Sofa ahead");
        put("Bed",              "Bed ahead");
        put("Door",             "Door ahead");
        put("Window",           "Window ahead");
        put("Stairs",           "Stairs ahead. Step carefully");
        put("Staircase",        "Stairs ahead. Step carefully");
        put("Shelf",            "Shelf ahead");
        put("Cabinet",          "Cabinet ahead");
        put("Wardrobe",         "Wardrobe ahead");
        put("Mirror",           "Mirror ahead");
        put("Lamp",             "Lamp nearby");
        put("Light",            "Light source ahead");
        put("Fan",              "Fan detected");
        put("Television",       "Television ahead");
        put("Computer monitor", "Screen ahead");
        put("Laptop",           "Laptop detected");
        put("Keyboard",         "Keyboard detected");
        put("Telephone",        "Phone detected");
        put("Clock",            "Clock detected");
        put("Pillow",           "Pillow detected");
        put("Blanket",          "Blanket detected");
        put("Curtain",          "Curtain ahead");
        put("Rug",              "Rug on floor");
        put("Home good",        "Household object ahead");

        // ── Kitchen items ─────────────────────────────────────────────────────
        put("Bottle",           "Bottle detected");
        put("Cup",              "Cup detected");
        put("Glass",            "Glass detected");
        put("Mug",              "Mug detected");
        put("Bowl",             "Bowl detected");
        put("Plate",            "Plate detected");
        put("Spoon",            "Spoon detected");
        put("Fork",             "Fork detected");
        put("Knife",            "Knife detected. Be careful");
        put("Scissors",         "Scissors detected. Be careful");
        put("Kettle",           "Kettle detected");
        put("Microwave oven",   "Microwave detected");
        put("Refrigerator",     "Refrigerator ahead");
        put("Sink",             "Sink ahead");
        put("Tap",              "Tap detected");
        put("Cooking pan",      "Pan detected");

        // ── Food ─────────────────────────────────────────────────────────────
        put("Food",             "Food item detected");
        put("Fruit",            "Fruit detected");
        put("Vegetable",        "Vegetable detected");
        put("Bread",            "Bread detected");
        put("Pizza",            "Pizza detected");
        put("Cake",             "Cake detected");

        // ── Danger and hazards ────────────────────────────────────────────────
        put("Fire",             "Fire detected! Be careful");
        put("Flame",            "Flame detected! Be careful");
        put("Smoke",            "Smoke detected! Be careful");
        put("Weapon",           "Danger ahead");
        put("Knife",            "Sharp object. Be careful");
        put("Electrical outlet","Electrical outlet nearby");

        // ── Outdoor / environment ─────────────────────────────────────────────
        put("Road",             "Road ahead");
        put("Footpath",         "Footpath ahead");
        put("Sidewalk",         "Sidewalk ahead");
        put("Wall",             "Wall ahead");
        put("Floor",            "Floor detected");
        put("Ceiling",          "Ceiling above");
        put("Tree",             "Tree ahead");
        put("Plant",            "Plant nearby");
        put("Grass",            "Grass area");
        put("Rock",             "Rock ahead");
        put("Water",            "Water ahead. Be careful");
        put("Puddle",           "Water on floor. Be careful");
        put("Pole",             "Pole ahead");
        put("Sign",             "Sign ahead");
        put("Building",         "Building ahead");
        put("House",            "Building ahead");
        put("Place",            "Structure ahead");

        // ── Clothing and accessories ──────────────────────────────────────────
        put("Fashion good",     "Clothing or accessory detected");
        put("Bag",              "Bag detected");
        put("Backpack",         "Backpack detected");
        put("Umbrella",         "Umbrella detected");
        put("Hat",              "Hat detected");
        put("Shoe",             "Shoe detected");
        put("Glasses",          "Glasses detected");

        // ── Medical ───────────────────────────────────────────────────────────
        put("Medicine",         "Medicine detected");
        put("Pill",             "Pill detected");
        put("Syringe",          "Medical object detected");
        put("Wheelchair",       "Wheelchair detected");
        put("Crutch",           "Walking aid detected");

        // ── Fallback ──────────────────────────────────────────────────────────
        put("Unknown",          "Unidentified object ahead");
    }};

    // Labels that trigger the red danger banner AND get spoken immediately
    // regardless of the rate-limit timer
    private static final List<String> DANGER = Arrays.asList(
            "Vehicle", "Car", "Truck", "Bus", "Motorcycle", "Bicycle",
            "Scooter", "Auto rickshaw", "Van", "Train",
            "Person", "Man", "Woman", "Human", "Crowd",
            "Animal", "Dog", "Snake",
            "Fire", "Flame", "Smoke",
            "Stairs", "Staircase", "Water", "Puddle",
            "Knife", "Weapon", "Scissors"
    );

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_safety_assistant);

        bindViews();
        setupTTS();
        setupDetector();
        cameraExecutor = Executors.newSingleThreadExecutor();
        setupButtons();

        if (hasCamPerm()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, CAMERA_PERM);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  View binding
    // ─────────────────────────────────────────────────────────────────────────
    private void bindViews() {
        previewView      = findViewById(R.id.cameraPreview);
        tvDetectedObjects= findViewById(R.id.tvDetectedObjects);
        tvCameraStatus   = findViewById(R.id.tvCameraStatus);
        tvObjectCount    = findViewById(R.id.tvObjectCount);
        btnSpeakNow      = findViewById(R.id.btnSpeakNow);
        btnPauseResume   = findViewById(R.id.btnPauseResume);
        alertBanner      = findViewById(R.id.alertBanner);
        tvAlertText      = findViewById(R.id.tvAlertText);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TTS
    // ─────────────────────────────────────────────────────────────────────────
    private void setupTTS() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(Locale.US);
                if (r != TextToSpeech.LANG_MISSING_DATA
                        && r != TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setSpeechRate(0.9f);
                    ttsReady = true;
                    speak("Safety Assistant ready. Point camera at your surroundings.");
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ML Kit object detector
    //  STREAM_MODE = optimised for live camera frames
    //  enableMultipleObjects = detect up to 5 objects per frame
    //  enableClassification  = get category labels
    // ─────────────────────────────────────────────────────────────────────────
    private void setupDetector() {
        // ImageLabeler returns specific labels like "Person", "Car", "Dog",
        // "Chair", "Bottle" — far more accurate than ObjectDetector's 5 categories.
        // Confidence threshold 0.65 = only show labels the model is confident about.
        imageLabeler = ImageLabeling.getClient(
                new ImageLabelerOptions.Builder()
                        .setConfidenceThreshold(0.65f)
                        .build());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  CameraX
    // ─────────────────────────────────────────────────────────────────────────
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                cameraProvider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this,
                        CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);

                runOnUiThread(() ->
                        tvCameraStatus.setText("Camera active — scanning surroundings"));

            } catch (Exception e) {
                Log.e(TAG, "Camera error: " + e.getMessage());
                runOnUiThread(() ->
                        tvCameraStatus.setText("Camera error: " + e.getMessage()));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Frame analysis — runs on cameraExecutor thread
    // ─────────────────────────────────────────────────────────────────────────
    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeFrame(ImageProxy proxy) {
        if (isPaused || proxy.getImage() == null) { proxy.close(); return; }

        InputImage image = InputImage.fromMediaImage(
                proxy.getImage(), proxy.getImageInfo().getRotationDegrees());

        imageLabeler.process(image)
                .addOnSuccessListener(labels -> { processLabels(labels); proxy.close(); })
                .addOnFailureListener(e -> { Log.e(TAG, "ML error: " + e.getMessage()); proxy.close(); });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Process ImageLabeler results
    //
    //  ImageLabeler returns a flat list of labels with confidence scores.
    //  Example: ["Person 0.98", "Human body 0.91", "Standing 0.85", ...]
    //
    //  We deduplicate using a priority system:
    //  - Take the top label that matches our LABEL_MAP first
    //  - If no match, use the highest-confidence label
    //  - Skip generic/unhelpful labels
    // ─────────────────────────────────────────────────────────────────────────
    private void processLabels(List<ImageLabel> labels) {
        List<String> displayLines = new ArrayList<>();
        List<String> spokenLines  = new ArrayList<>();
        boolean hasDanger   = false;
        String  dangerLabel = "";

        // ── Labels to skip entirely (describe photo, not objects) ─────────────
        List<String> SKIP = Arrays.asList(
                "no label", "snapshot", "screenshot", "image", "photo",
                "font", "text", "pattern", "line", "rectangle", "circle",
                "darkness", "black", "white", "grey", "gray", "color",
                "blurriness", "photography", "flash photography",
                "lighting", "sky", "outdoor", "indoor", "room", "interior",
                "stock photography", "close-up", "macro photography"
        );

        // ── Human labels — always win over animals if present ─────────────────
        // ML Kit small model confuses people with dogs/cats at distance.
        // Rule: if ANY human label is found at >= 0.55 confidence,
        //       use it — never say "Dog" when a human is more likely present.
        List<String> HUMAN_LABELS = Arrays.asList(
                "Person", "Human body", "Man", "Woman", "Human face",
                "Human head", "Human hand", "Human eye", "Boy", "Girl",
                "Standing", "Sitting", "Walking", "Crowd", "People"
        );

        // ── Animal labels need HIGH confidence to avoid false positives ────────
        List<String> ANIMAL_LABELS = Arrays.asList(
                "Dog", "Cat", "Bird", "Animal", "Horse", "Cow",
                "Elephant", "Snake", "Bear", "Rabbit", "Deer"
        );

        // ── Electronics/computer labels — prioritised over musical instruments ─
        // ML Kit confuses laptop keyboards with piano keys → "Musical instrument"
        // Rule: if ANY electronics label found at ≥ 0.55 confidence, use it.
        // Musical instrument labels need 0.85 confidence when electronics are present.
        List<String> ELECTRONICS_LABELS = Arrays.asList(
                "Laptop", "Computer", "Tablet computer", "Smartphone", "Mobile phone",
                "Computer monitor", "Keyboard", "Mouse", "Laptop keyboard",
                "Personal computer", "Netbook", "Screen", "Display device",
                "Electronic device", "Technology", "Gadget", "Computer hardware"
        );

        List<String> MUSICAL_LABELS = Arrays.asList(
                "Musical instrument", "String instrument", "Keyboard instrument",
                "Piano", "Guitar", "Violin", "Drum", "Flute", "Saxophone",
                "Wind instrument", "Percussion instrument", "Plucked string instrument"
        );

        // Step 1 — scan for any human label first (priority override)
        String humanRaw  = null;
        float  humanConf = 0f;
        for (ImageLabel label : labels) {
            if (HUMAN_LABELS.contains(label.getText())
                    && label.getConfidence() >= 0.55f
                    && label.getConfidence() > humanConf) {
                humanRaw  = label.getText();
                humanConf = label.getConfidence();
            }
        }

        // Step 1b — scan for electronics label (overrides musical instrument confusion)
        String electronicsRaw  = null;
        float  electronicsConf = 0f;
        for (ImageLabel label : labels) {
            if (ELECTRONICS_LABELS.contains(label.getText())
                    && label.getConfidence() >= 0.55f
                    && label.getConfidence() > electronicsConf) {
                electronicsRaw  = label.getText();
                electronicsConf = label.getConfidence();
            }
        }

        // Step 2 — pick best label
        String bestRaw      = null;
        String bestFriendly = null;
        float  bestConf     = 0f;

        if (humanRaw != null) {
            // ★ Human found → always use it, never let animal override
            bestRaw      = humanRaw;
            bestConf     = humanConf;
            bestFriendly = "There is a person ahead";

        } else {
            // No human detected — find best non-human label
            for (ImageLabel label : labels) {
                String raw  = label.getText();
                float  conf = label.getConfidence();

                if (SKIP.contains(raw.toLowerCase())) continue;
                if (HUMAN_LABELS.contains(raw)) continue; // already checked

                // ★ Musical instrument blocked if electronics detected nearby
                // (prevents "piano/guitar" misfire for laptops)
                if (MUSICAL_LABELS.contains(raw) && electronicsRaw != null) continue;

                // Animals and musical instruments need higher confidence
                float threshold = ANIMAL_LABELS.contains(raw)  ? 0.82f
                        : MUSICAL_LABELS.contains(raw)  ? 0.82f
                        : 0.60f;
                if (conf < threshold) continue;

                // ★ Prefer electronics labels over generic ones
                if (ELECTRONICS_LABELS.contains(raw) && conf > bestConf) {
                    bestConf     = conf;
                    bestRaw      = raw;
                    bestFriendly = LABEL_MAP.getOrDefault(raw,
                            "There is a " + raw.toLowerCase() + " ahead");
                } else if (LABEL_MAP.containsKey(raw) && conf > bestConf
                        && !ELECTRONICS_LABELS.contains(bestRaw == null ? "" : bestRaw)) {
                    // Don't replace an already-found electronics label with non-electronics
                    bestConf     = conf;
                    bestRaw      = raw;
                    bestFriendly = LABEL_MAP.get(raw);
                } else if (bestRaw == null && conf > bestConf) {
                    bestConf     = conf;
                    bestRaw      = raw;
                    bestFriendly = "There is a " + raw.toLowerCase() + " ahead";
                }
            }
        }

        if (bestRaw != null) {
            int pct = Math.round(bestConf * 100);
            displayLines.add("• " + bestRaw + " (" + pct + "%)");
            spokenLines.add(bestFriendly);

            if (DANGER.contains(bestRaw) || HUMAN_LABELS.contains(bestRaw)) {
                hasDanger   = true;
                dangerLabel = bestFriendly;
            }
        }

        latestSpoken = spokenLines;

        final List<String> dl  = displayLines;
        final boolean      hd  = hasDanger;
        final String       dt  = dangerLabel;
        final List<String> sl  = spokenLines;

        runOnUiThread(() -> {
            if (dl.isEmpty()) {
                tvDetectedObjects.setText("No objects detected");
                tvObjectCount.setText("0 objects");
                if (alertBanner != null) alertBanner.setVisibility(View.GONE);
                return;
            }

            StringBuilder sb = new StringBuilder();
            for (String line : dl) sb.append(line).append("\n");
            tvDetectedObjects.setText(sb.toString().trim());
            tvObjectCount.setText(dl.size() + " object" + (dl.size() > 1 ? "s" : "") + " detected");

            // Danger banner
            if (alertBanner != null) {
                alertBanner.setVisibility(hd ? View.VISIBLE : View.GONE);
                if (tvAlertText != null && hd) tvAlertText.setText("⚠  " + dt);
            }

            // Speak one clear phrase:
            // - Danger (person, car, fire) → speak immediately, bypass timer
            // - Other objects → speak only if label CHANGED and 6 sec passed
            //   (prevents constant repetition for same scene)
            String toSpeak = sl.isEmpty() ? "" : sl.get(0);
            long now = System.currentTimeMillis();
            boolean labelChanged = !toSpeak.equals(lastSpoken);
            boolean timerReady   = now - lastSpeakTime > SPEAK_GAP;

            if (!toSpeak.isEmpty() && labelChanged && (hd || timerReady)) {
                lastSpeakTime = now;
                lastSpoken    = toSpeak;
                speak(toSpeak);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Buttons
    // ─────────────────────────────────────────────────────────────────────────
    private void setupButtons() {
        if (btnSpeakNow != null) {
            btnSpeakNow.setOnClickListener(v -> {
                List<String> l = latestSpoken;
                speak(l.isEmpty() ? "No objects detected. Keep scanning." : String.join(". ", l));
            });
        }

        if (btnPauseResume != null) {
            btnPauseResume.setOnClickListener(v -> {
                isPaused = !isPaused;
                btnPauseResume.setText(isPaused ? "▶  Resume" : "⏸  Pause");
                tvCameraStatus.setText(isPaused ? "Detection paused" : "Camera active — scanning surroundings");
                if (isPaused) {
                    if (tts != null) tts.stop();
                    if (alertBanner != null) alertBanner.setVisibility(View.GONE);
                } else {
                    speak("Detection resumed.");
                }
            });
        }
    }

    private void speak(String text) {
        if (tts != null && ttsReady && !isPaused)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null,
                    "sa_" + System.currentTimeMillis());
    }

    private boolean hasCamPerm() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int req,
                                           @NonNull String[] perms,
                                           @NonNull int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == CAMERA_PERM) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "Camera permission required for Safety Assistant",
                        Toast.LENGTH_LONG).show();
                if (tvCameraStatus != null)
                    tvCameraStatus.setText("Camera permission denied");
            }
        }
    }

    @Override
    protected void onPause()   { super.onPause();  isPaused = true; if (tts != null) tts.stop(); }
    @Override
    protected void onResume()  { super.onResume(); isPaused = false; }

    @Override
    protected void onDestroy() {
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (imageLabeler != null) imageLabeler.close();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (cameraProvider != null) cameraProvider.unbindAll();
        super.onDestroy();
    }
}