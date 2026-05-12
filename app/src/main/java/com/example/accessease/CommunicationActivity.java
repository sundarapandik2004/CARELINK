package com.example.accessease;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

// ★ NEW — Firebase imports (Feature 5: comm log, Feature 6: last active)
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class CommunicationActivity extends AppCompatActivity {

    private static final String TAG = "CommunicationActivity";
    private static final int PERMISSION_RECORD_AUDIO = 200;

    // ── Views ────────────────────────────────────────────────────────────────
    private EditText etInput;
    private TextView tvResult, tvListeningStatus, btnMic;
    private Button btnSpeak, btnListen, btnClear;
    private FrameLayout btnMicFrame;
    private View micPulseRing;           // animated ring around mic button

    // ── Speech ───────────────────────────────────────────────────────────────
    private SpeechRecognizer speechRecognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean isListening = false;
    private ObjectAnimator pulseAnimator;

    // ★ NEW — Firebase references
    private FirebaseFirestore db;
    private String currentUserId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_communication);

        // ★ NEW — init Firebase quietly (safe even if not configured)
        try {
            db = FirebaseFirestore.getInstance();
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            currentUserId = (user != null) ? user.getUid() : null;
        } catch (Exception e) {
            Log.w(TAG, "Firebase not available — logging disabled");
        }

        bindViews();
        setupTTS();
        setupSpeechRecognizer();
        setupListeners();
        checkAndRequestPermission();

        // ★ NEW — update caregiver's "last active" counter when user opens this screen
        updateLastActive();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  View binding  (unchanged)
    // ─────────────────────────────────────────────────────────────────────────
    private void bindViews() {
        etInput           = findViewById(R.id.etInputText);
        tvResult          = findViewById(R.id.tvSpeechResult);
        tvListeningStatus = findViewById(R.id.tvListeningStatus);
        btnSpeak          = findViewById(R.id.btnSpeak);
        btnListen         = findViewById(R.id.btnListen);
        btnClear          = findViewById(R.id.btnClear);
        btnMicFrame       = findViewById(R.id.btnMicFrame);
        btnMic            = findViewById(R.id.btnMic);
        micPulseRing      = findViewById(R.id.micPulseRing);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TTS setup  (unchanged)
    // ─────────────────────────────────────────────────────────────────────────
    private void setupTTS() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(Locale.US);
                if (result != TextToSpeech.LANG_MISSING_DATA
                        && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setSpeechRate(0.9f);
                    tts.setPitch(1.0f);
                    ttsReady = true;
                    Log.d(TAG, "TTS ready");
                } else {
                    Log.e(TAG, "TTS language not supported");
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  SpeechRecognizer setup
    //  Only change: onResults now also calls logSpeechToFirestore(text)  ★
    // ─────────────────────────────────────────────────────────────────────────
    private void setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Speech recognition not available on this device");
            return;
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {

            @Override
            public void onReadyForSpeech(Bundle params) {
                Log.d(TAG, "onReadyForSpeech");
                runOnUiThread(() -> {
                    setListeningState(true);
                    tvResult.setText("🎤 Listening... speak now");
                });
            }

            @Override
            public void onBeginningOfSpeech() {
                Log.d(TAG, "onBeginningOfSpeech");
                runOnUiThread(() -> tvResult.setText("🎙️ Hearing you..."));
            }

            @Override
            public void onRmsChanged(float rmsdB) {
                // Animate mic ring size based on volume
                if (micPulseRing != null && rmsdB > 0) {
                    float scale = 1f + (rmsdB / 20f);
                    scale = Math.min(scale, 1.8f);
                    micPulseRing.setScaleX(scale);
                    micPulseRing.setScaleY(scale);
                }
            }

            @Override
            public void onBufferReceived(byte[] buffer) { }

            @Override
            public void onEndOfSpeech() {
                Log.d(TAG, "onEndOfSpeech");
                runOnUiThread(() -> tvResult.setText("⏳ Processing..."));
            }

            @Override
            public void onError(int error) {
                Log.e(TAG, "onError: " + getSpeechErrorMessage(error));
                runOnUiThread(() -> {
                    setListeningState(false);
                    String msg = getSpeechErrorMessage(error);
                    tvResult.setText("❌ " + msg);
                    if (error == SpeechRecognizer.ERROR_NO_MATCH
                            || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        tvResult.setText("❌ " + msg + "\n\nTap 🎤 again and speak clearly.");
                    }
                });
            }

            @Override
            public void onResults(Bundle results) {
                Log.d(TAG, "onResults");
                runOnUiThread(() -> {
                    setListeningState(false);
                    ArrayList<String> matches = results.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        String text = matches.get(0);
                        etInput.setText(text);
                        tvResult.setText("✅ You said:\n" + text);
                        speak("You said: " + text);

                        // ★ NEW — send this speech to Firestore so caregiver can
                        //         see "Last Communication" in their dashboard
                        logSpeechToFirestore(text);

                    } else {
                        tvResult.setText("❌ Nothing recognised. Try again.");
                    }
                });
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                // Show live partial text as user speaks
                ArrayList<String> partial = partialResults.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                if (partial != null && !partial.isEmpty()) {
                    runOnUiThread(() ->
                            tvResult.setText("🎙️ " + partial.get(0) + "..."));
                }
            }

            @Override
            public void onEvent(int eventType, Bundle params) { }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Button listeners  (unchanged)
    // ─────────────────────────────────────────────────────────────────────────
    private void setupListeners() {

        // Speak text aloud
        btnSpeak.setOnClickListener(v -> {
            String text = etInput.getText().toString().trim();
            if (!text.isEmpty()) {
                speak(text);
                tvResult.setText("🔊 Speaking...");
            } else {
                Toast.makeText(this, "Type something first", Toast.LENGTH_SHORT).show();
            }
        });

        // Read back typed text
        btnListen.setOnClickListener(v -> {
            String text = etInput.getText().toString().trim();
            if (!text.isEmpty()) {
                speak(text);
                tvResult.setText("👂 Reading: " + text);
            } else {
                Toast.makeText(this, "Type something first", Toast.LENGTH_SHORT).show();
            }
        });

        // Mic button — toggle listening
        btnMicFrame.setOnClickListener(v -> {
            if (!hasAudioPermission()) {
                requestAudioPermission();
                return;
            }
            if (isListening) {
                stopListening();
            } else {
                startListening();
            }
        });

        // Clear
        btnClear.setOnClickListener(v -> {
            etInput.setText("");
            tvResult.setText("Your speech will appear here...");
            tvListeningStatus.setText("");
            if (isListening) stopListening();
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Start / stop listening  (unchanged)
    // ─────────────────────────────────────────────────────────────────────────
    private void startListening() {
        if (speechRecognizer == null) {
            setupSpeechRecognizer();
            if (speechRecognizer == null) {
                Toast.makeText(this,
                        "Speech recognition not available on this device",
                        Toast.LENGTH_LONG).show();
                return;
            }
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L);

        try {
            speechRecognizer.startListening(intent);
        } catch (Exception e) {
            Log.e(TAG, "startListening error: " + e.getMessage());
            tvResult.setText("❌ Could not start microphone: " + e.getMessage());
        }
    }

    private void stopListening() {
        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
        }
        setListeningState(false);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  UI state when listening / not listening  (unchanged)
    // ─────────────────────────────────────────────────────────────────────────
    private void setListeningState(boolean listening) {
        isListening = listening;

        if (btnMic != null) {
            btnMic.setText(listening ? "⏹️" : "🎤");
        }

        if (tvListeningStatus != null) {
            tvListeningStatus.setText(listening ? "● RECORDING" : "");
            tvListeningStatus.setTextColor(listening ? 0xFFEF4444 : 0xFF6B7280);
        }

        // Pulse ring animation
        if (micPulseRing != null) {
            if (listening) {
                micPulseRing.setVisibility(View.VISIBLE);
                pulseAnimator = ObjectAnimator.ofFloat(micPulseRing, "alpha", 0.6f, 0f);
                pulseAnimator.setDuration(800);
                pulseAnimator.setRepeatMode(ValueAnimator.RESTART);
                pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
                pulseAnimator.start();
            } else {
                if (pulseAnimator != null) pulseAnimator.cancel();
                micPulseRing.setVisibility(View.INVISIBLE);
                micPulseRing.setScaleX(1f);
                micPulseRing.setScaleY(1f);
            }
        }

        // Change mic button background colour
        if (btnMicFrame != null) {
            btnMicFrame.setBackgroundColor(
                    listening ? 0xFFEF4444 : 0xFF7C6FFF);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TTS  (unchanged)
    // ─────────────────────────────────────────────────────────────────────────
    private void speak(String text) {
        if (tts != null && ttsReady) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null,
                    "utterance_" + System.currentTimeMillis());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Permissions  (unchanged)
    // ─────────────────────────────────────────────────────────────────────────
    private boolean hasAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void checkAndRequestPermission() {
        if (!hasAudioPermission()) requestAudioPermission();
    }

    private void requestAudioPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_RECORD_AUDIO);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_RECORD_AUDIO) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "✅ Microphone ready! Tap 🎤 to speak.",
                        Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this,
                        "Microphone denied. Go to Settings → Apps → AccessEase → Permissions",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Error messages  (unchanged)
    // ─────────────────────────────────────────────────────────────────────────
    private String getSpeechErrorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "Microphone error. Check permissions.";
            case SpeechRecognizer.ERROR_CLIENT:
                return "App error. Reopen the screen.";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "Microphone permission denied.";
            case SpeechRecognizer.ERROR_NETWORK:
                return "No internet. Try again.";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "Network timeout. Check your connection.";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "Didn't catch that. Speak louder.";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "Microphone busy. Wait a moment.";
            case SpeechRecognizer.ERROR_SERVER:
                return "Server error. Try again.";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "No speech detected. Try again.";
            default:
                return "Unknown error (" + error + ")";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ★ NEW — Feature 5: Log recognised speech to Firestore
    //
    //  What this does:
    //    Every time the user speaks and it is recognised successfully,
    //    this method saves a document to Firestore under "communications".
    //    The caregiver dashboard listens to this collection in real time
    //    and shows the last message under "LAST COMMUNICATION".
    //
    //  Firestore path:  communications/{autoId}
    //  Fields saved:
    //    userId    — who spoke
    //    message   — what they said
    //    timestamp — when they said it (milliseconds)
    // ─────────────────────────────────────────────────────────────────────────
    private void logSpeechToFirestore(String spokenText) {
        if (db == null || currentUserId == null) return;

        Map<String, Object> entry = new HashMap<>();
        entry.put("userId",    currentUserId);
        entry.put("message",   spokenText);
        entry.put("timestamp", System.currentTimeMillis());

        db.collection("communications")
                .add(entry)
                .addOnSuccessListener(ref ->
                        Log.d(TAG, "Speech logged to Firestore: " + ref.getId()))
                .addOnFailureListener(e ->
                        Log.w(TAG, "Speech log failed: " + e.getMessage()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ★ NEW — Feature 6: Update lastActive on user's Firestore document
    //
    //  What this does:
    //    Writes the current timestamp to users/{userId}/lastActive.
    //    The caregiver dashboard reads this and shows "Last active: 5 min ago".
    //    Called once when this screen opens.
    // ─────────────────────────────────────────────────────────────────────────
    private void updateLastActive() {
        if (db == null || currentUserId == null) return;

        db.collection("users")
                .document(currentUserId)
                .update("lastActive", System.currentTimeMillis())
                .addOnFailureListener(e ->
                        Log.w(TAG, "lastActive update failed: " + e.getMessage()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Lifecycle  (unchanged)
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    protected void onPause() {
        super.onPause();
        if (isListening) stopListening();
    }

    @Override
    protected void onDestroy() {
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (pulseAnimator != null) pulseAnimator.cancel();
        super.onDestroy();
    }
}