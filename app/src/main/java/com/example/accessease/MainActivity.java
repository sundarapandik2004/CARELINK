package com.example.accessease;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.RecognitionListener;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private TextToSpeech tts;
    private SpeechRecognizer voiceRecognizer;
    private boolean          isListening = false;
    private TextView         tvVoiceStatus;
    private com.google.android.material.floatingactionbutton.FloatingActionButton fabVoiceNav;
    private CardView cardComm, cardReminders, cardEmergencySOS, cardSettings, cardCaregiver, cardSafety, cardChatbot, cardGesture;
    private Button btnHelp;
    private TextView tvWelcomeName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);   // ★ FIX — must be FIRST line
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        try {
            initializeViews();
            setupTTS();
            setupListeners();
            setupVoiceNavigation();
            loadUserName();
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void initializeViews() {
        cardComm         = findViewById(R.id.cardCommunication);
        cardReminders    = findViewById(R.id.cardReminders);
        cardEmergencySOS = findViewById(R.id.cardEmergencySOS);
        cardSettings     = findViewById(R.id.cardSettings);
        btnHelp          = findViewById(R.id.btnQuickHelp);
        tvWelcomeName    = findViewById(R.id.tvWelcomeName);
        cardCaregiver    = findViewById(R.id.cardCaregiver);
        cardSafety       = findViewById(R.id.cardSafety);
        cardChatbot      = findViewById(R.id.cardChatbot);
        cardGesture      = findViewById(R.id.cardGesture);
        fabVoiceNav      = findViewById(R.id.fabVoiceNav);
        tvVoiceStatus    = findViewById(R.id.tvVoiceStatus);
    }

    private void loadUserName() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection("users").document(user.getUid())
                .update("lastActive", System.currentTimeMillis());

        if (tvWelcomeName == null) return;
        db.collection("users").document(user.getUid())
                .get()
                .addOnSuccessListener(doc -> {
                    String name = doc.getString("name");
                    if (name != null && tvWelcomeName != null)
                        tvWelcomeName.setText("Hello, " + name + " 👋");
                });
    }

    private void setupTTS() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(Locale.US);
                if (r != TextToSpeech.LANG_MISSING_DATA
                        && r != TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setSpeechRate(0.8f);
                }
            }
        });
    }

    private void setupListeners() {
        if (cardComm != null) cardComm.setOnClickListener(v -> {
            speak("Opening Communication Aid");
            startActivity(new Intent(this, CommunicationActivity.class));
        });

        if (cardReminders != null) cardReminders.setOnClickListener(v -> {
            speak("Opening Reminders");
            startActivity(new Intent(this, RemindersActivity.class));
        });

        if (cardEmergencySOS != null) cardEmergencySOS.setOnClickListener(v -> {
            speak("Opening Emergency SOS");
            startActivity(new Intent(this, EmergencySOSActivity.class));
        });

        if (cardSettings != null) cardSettings.setOnClickListener(v -> {
            speak("Opening Settings");
            startActivity(new Intent(this, SettingsActivity.class));
        });

        if (cardCaregiver != null) {
            cardCaregiver.setOnClickListener(v -> {
                speak("Opening Caregiver Dashboard");
                startActivity(new Intent(this, CaregiverDashboardActivity.class));
            });
        }

        if (cardSafety != null) {
            cardSafety.setOnClickListener(v -> {
                speak("Opening Safety Assistant");
                startActivity(new Intent(this, SafetyAssistantActivity.class));
            });
        }

        // Care Chatbot card
        if (cardChatbot != null) {
            cardChatbot.setOnClickListener(v -> {
                speak("Opening Care Assistant");
                startActivity(new Intent(this, ChatbotActivity.class));
            });
        }

        // Gesture Control card
        if (cardGesture != null) {
            cardGesture.setOnClickListener(v -> {
                speak("Opening Gesture Control");
                startActivity(new Intent(this, GestureControlActivity.class));
            });
        }

        if (btnHelp != null) {
            btnHelp.setOnClickListener(v -> {
                String help = "CareLink helps with communication, reminders, and emergencies. " +
                        "Tap Communication for text and speech. " +
                        "Tap Reminders for daily tasks. " +
                        "Tap Emergency SOS for quick alerts. " +
                        "Tap Settings for preferences.";
                speak(help);
                Toast.makeText(this, help, Toast.LENGTH_LONG).show();
            });
        }

        Button btnLogout = findViewById(R.id.btnLogout);
        if (btnLogout != null) {
            btnLogout.setOnClickListener(v -> confirmLogout());
        }
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle("Sign Out")
                .setMessage("Are you sure you want to sign out?")
                .setPositiveButton("Sign Out", (dialog, which) -> doLogout())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doLogout() {
        getSharedPreferences("LoginPrefs", MODE_PRIVATE)
                .edit().putBoolean("rememberMe", false).apply();
        FirebaseAuth.getInstance().signOut();
        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
    private void setupVoiceNavigation() {
        if (fabVoiceNav == null) return;

        voiceRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        voiceRecognizer.setRecognitionListener(new RecognitionListener() {

            @Override
            public void onReadyForSpeech(Bundle params) {
                isListening = true;
                showVoiceStatus("🎤 Listening...");
                fabVoiceNav.setImageResource(android.R.drawable.ic_media_pause);
            }

            @Override
            public void onResults(Bundle results) {
                isListening = false;
                fabVoiceNav.setImageResource(android.R.drawable.ic_btn_speak_now);

                ArrayList<String> matches = results
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);

                if (matches != null && !matches.isEmpty()) {
                    String command = matches.get(0).toLowerCase().trim();
                    showVoiceStatus("Heard: \"" + matches.get(0) + "\"");
                    handleVoiceCommand(command);
                } else {
                    showVoiceStatus("Could not understand. Try again.");
                    speak("Sorry, I did not catch that. Please try again.");
                }
            }

            @Override
            public void onError(int error) {
                isListening = false;
                fabVoiceNav.setImageResource(android.R.drawable.ic_btn_speak_now);
                String msg = getVoiceErrorMessage(error);
                showVoiceStatus(msg);
                speak(msg);
            }

            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() { showVoiceStatus("Processing..."); }
            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });

        fabVoiceNav.setOnClickListener(v -> {
            // RecognizerIntent opens Google speech dialog — works on ALL devices
            // including Indian phones where SpeechRecognizer background service
            // is killed by battery optimisation and fails silently with ERROR_CLIENT
            try {
                Intent voiceIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                voiceIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                voiceIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN");
                voiceIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN");
                voiceIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
                voiceIntent.putExtra(RecognizerIntent.EXTRA_PROMPT,
                        "Say: Communication, Reminders, Emergency, Safety, Gesture or Chatbot");
                speak("Listening. Say a module name.");
                startActivityForResult(voiceIntent, 888);
            } catch (Exception e) {
                showVoiceStatus("Voice not supported on this device");
                Toast.makeText(MainActivity.this,
                        "Voice input not available", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // Receives the spoken text from the RecognizerIntent dialog
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 888 && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results =
                    data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                String command = results.get(0).toLowerCase(Locale.getDefault()).trim();
                showVoiceStatus("Heard: \"" + results.get(0) + "\"");
                handleVoiceCommand(command);
            } else {
                showVoiceStatus("Could not understand. Please try again.");
            }
        }
    }

    private void handleVoiceCommand(String command) {
        // Trim and clean — Google Speech sometimes adds trailing spaces or punctuation
        command = command.trim().replaceAll("[^a-z0-9 ]", "");

        // ── Care Assistant / Chatbot ──────────────────────────────────────────
        // Checked FIRST — "assistant" must not be caught by other blocks
        // Google Speech variants: "chatbot", "chat bot", "chat", "care", "care assistant"
        if (command.equals("chat") || command.equals("chatbot")
                || command.contains("chat bot") || command.contains("care assistant")
                || command.contains("care bot") || command.contains("care")
                || command.contains("chatbot")) {
            navigateTo("Care Assistant", ChatbotActivity.class);

            // ── Safety Assistant ──────────────────────────────────────────────────
            // Google Speech variants: "safety", "save t", "safety assistant", "camera"
        } else if (command.contains("safety") || command.contains("save t")
                || command.contains("savety") || command.contains("camera")
                || command.contains("detect") || command.contains("scan")
                || command.contains("obstacle") || command.contains("safe")) {
            navigateTo("Safety Assistant", SafetyAssistantActivity.class);

            // ── Communication Aid ─────────────────────────────────────────────────
        } else if (command.contains("communication") || command.contains("speech")
                || command.contains("speak") || command.contains("talk")
                || command.contains("type") || command.contains("text")) {
            navigateTo("Communication Aid", CommunicationActivity.class);

            // ── Smart Reminders ───────────────────────────────────────────────────
        } else if (command.contains("reminder") || command.contains("remind")
                || command.contains("medicine") || command.contains("medication")
                || command.contains("alarm") || command.contains("schedule")
                || command.contains("task") || command.contains("tablet")) {
            navigateTo("Smart Reminders", RemindersActivity.class);

            // ── Emergency SOS ─────────────────────────────────────────────────────
        } else if (command.contains("emergency") || command.contains("sos")
                || command.contains("alert") || command.contains("danger")
                || command.contains("help me") || command.contains("urgent")) {
            navigateTo("Emergency SOS", EmergencySOSActivity.class);

            // ── Settings ──────────────────────────────────────────────────────────
        } else if (command.contains("setting") || command.contains("settings")
                || command.contains("preference") || command.contains("theme")
                || command.contains("config")) {
            navigateTo("Settings", SettingsActivity.class);

            // ── Caregiver Dashboard ───────────────────────────────────────────────
        } else if (command.contains("caregiver") || command.contains("care giver")
                || command.contains("dashboard") || command.contains("monitor")
                || command.contains("guardian") || command.contains("doctor")) {
            navigateTo("Caregiver Dashboard", CaregiverDashboardActivity.class);

            // ── Gesture Control ───────────────────────────────────────────────────
        } else if (command.contains("gesture") || command.contains("hand")
                || command.contains("wave") || command.contains("motion")
                || command.contains("gesture control")) {
            navigateTo("Gesture Control", GestureControlActivity.class);

            // ── Help — list all commands ──────────────────────────────────────────
        } else if (command.contains("help") || command.contains("what")
                || command.contains("option") || command.contains("list")
                || command.contains("available") || command.contains("command")) {
            String help = "You can say: Chat, Safety, Communication, Reminders, "
                    + "Emergency, Settings, Caregiver, or Gesture.";
            showVoiceStatus(help);
            speak(help);

            // ── Not understood ────────────────────────────────────────────────────
        } else {
            showVoiceStatus("Not recognised: \"" + command + "\"");
            speak("I did not understand. Try saying Chat, Safety, Reminders or Emergency.");
        }
    }

    private void navigateTo(String moduleName, Class<?> destination) {
        speak("Opening " + moduleName);
        showVoiceStatus("Opening " + moduleName + "...");
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(() -> startActivity(new Intent(this, destination)), 600);
    }

    private void showVoiceStatus(String message) {
        if (tvVoiceStatus == null) return;
        if (message == null || message.isEmpty()) {
            tvVoiceStatus.setVisibility(android.view.View.GONE);
        } else {
            tvVoiceStatus.setText(message);
            tvVoiceStatus.setVisibility(android.view.View.VISIBLE);
            // Auto-hide after 3 seconds
            tvVoiceStatus.removeCallbacks(null);
            tvVoiceStatus.postDelayed(
                    () -> tvVoiceStatus.setVisibility(android.view.View.GONE), 3000);
        }
    }

    private String getVoiceErrorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "No match found. Please try again.";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "No speech detected. Tap mic and try again.";
            case SpeechRecognizer.ERROR_AUDIO:
                return "Audio error. Check microphone.";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "Network error. Voice needs internet.";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "Recognizer busy. Please wait.";
            default:
                return "Voice error. Please try again.";
        }
    }

    private void speak(String text) {
        if (tts != null) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
    }

    @Override
    protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (voiceRecognizer != null) voiceRecognizer.destroy();
        super.onDestroy();
    }
}