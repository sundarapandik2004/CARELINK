package com.example.accessease;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ChatbotActivity — Powerful AI-powered Care Assistant
 *
 * Architecture:
 *  1. Rule-based layer  — instant responses for known intents (call, SOS, reminder...)
 *  2. Claude AI layer   — free-form conversation for everything else
 *
 * Contacts: reads contact1/contact2/contact3 + name1/name2/name3 from SOSPrefs
 *           (same SharedPreferences as EmergencySOSActivity — no duplication)
 *
 * Does NOT modify any existing file.
 */
public class ChatbotActivity extends AppCompatActivity {

    private static final String TAG       = "ChatbotActivity";
    private static final int    VOICE_REQ = 201;
    private static final String PREFS_SOS = "SOSPrefs";
    private static final String PREFS_REM = "ReminderPrefs";

    // ── Claude API ────────────────────────────────────────────────────────────
    // This uses the Anthropic Claude API for free-form AI responses.
    // The API key is handled server-side via the claude.ai environment.
    // For your own deployment, add your key to BuildConfig or a secure config file.
    private static final String CLAUDE_API_URL   = "https://api.anthropic.com/v1/messages";
    private static final String CLAUDE_MODEL     = "claude-haiku-4-5-20251001";
    private static final String CLAUDE_API_KEY   = "YOUR_ANTHROPIC_API_KEY"; // Get free key from console.anthropic.com

    // ── Views ─────────────────────────────────────────────────────────────────
    private RecyclerView         rvMessages;
    private EditText             etInput;
    private ImageButton          btnSend;
    private FloatingActionButton fabVoice;
    private TextView             tvTyping;
    private ChipGroup            chipGroup;    // quick-reply chips
    private TextView             tvContactBadge; // shows saved contact name in header

    // ── Data ──────────────────────────────────────────────────────────────────
    private final List<ChatMessage> messages = new ArrayList<>();
    private ChatAdapter adapter;

    // ── Services ──────────────────────────────────────────────────────────────
    private TextToSpeech      tts;
    private boolean           ttsReady = false;
    private FirebaseFirestore db;
    private String            userId;
    private String            userName;
    private ExecutorService   aiExecutor = Executors.newSingleThreadExecutor();

    // ── Contacts (loaded from SOSPrefs) ───────────────────────────────────────
    private String contact1, contact2, contact3;
    private String name1,    name2,    name3;

    // ── Conversation state ────────────────────────────────────────────────────
    private enum PendingAction {
        NONE, CALL_CONFIRM, CALL_WHICH, SOS_CONFIRM, REMINDER_TITLE, REMINDER_TIME,
        BREATHING_ACTIVE
    }
    private PendingAction pendingAction      = PendingAction.NONE;
    private String        pendingReminderTitle = null;
    private String        pendingCallNumber    = null;
    private String        pendingCallName      = null;

    // Conversation history for Claude AI context
    private final List<Map<String, String>> aiHistory = new ArrayList<>();

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chatbot);

        // Firebase
        try {
            db = FirebaseFirestore.getInstance();
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user != null) {
                userId   = user.getUid();
                userName = user.getDisplayName();
                if (userName == null || userName.isEmpty()) loadNameFromFirestore();
            }
        } catch (Exception e) {
            Log.e(TAG, "Firebase init error: " + e.getMessage());
        }

        loadContacts();

        try {
            bindViews();
            setupRecyclerView();
            setupTTS();
            setupListeners();
        } catch (Exception e) {
            Log.e(TAG, "onCreate error: " + e.getMessage(), e);
            Toast.makeText(this, "Chat error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        // Welcome message
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            String caregiverInfo = buildCaregiverSummary();
            botSay("Hello! 😊 I'm your Care Assistant.\n\n" +
                    caregiverInfo +
                    "I can help you:\n" +
                    "📞 Call your caregiver by name\n" +
                    "🚨 Trigger emergency SOS\n" +
                    "⏰ Set medicine reminders\n" +
                    "🧘 Guide you through breathing exercises\n" +
                    "💬 Just talk — I'm always here!\n\n" +
                    "How are you feeling today?");
            showQuickReplies("I'm okay", "I need help", "Call caregiver", "I feel sick");
        }, 600);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Load contacts from SOSPrefs (same store as EmergencySOSActivity)
    // ─────────────────────────────────────────────────────────────────────────
    private void loadContacts() {
        SharedPreferences p = getSharedPreferences(PREFS_SOS, MODE_PRIVATE);
        contact1 = p.getString("contact1", "");
        contact2 = p.getString("contact2", "");
        contact3 = p.getString("contact3", "");
        name1    = p.getString("name1", "Caregiver 1");
        name2    = p.getString("name2", "Caregiver 2");
        name3    = p.getString("name3", "Caregiver 3");

        // Default display names if saved names are empty
        if (name1.isEmpty()) name1 = "Caregiver 1";
        if (name2.isEmpty()) name2 = "Caregiver 2";
        if (name3.isEmpty()) name3 = "Caregiver 3";
    }

    private String buildCaregiverSummary() {
        List<String> saved = new ArrayList<>();
        if (!contact1.isEmpty()) saved.add(name1);
        if (!contact2.isEmpty()) saved.add(name2);
        if (!contact3.isEmpty()) saved.add(name3);

        if (saved.isEmpty()) return "";
        return "📋 Saved contacts: " + String.join(", ", saved) + "\n\n";
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  View binding
    // ─────────────────────────────────────────────────────────────────────────
    private void bindViews() {
        rvMessages     = findViewById(R.id.rvChatMessages);
        etInput        = findViewById(R.id.etChatInput);
        btnSend        = findViewById(R.id.btnChatSend);
        fabVoice       = findViewById(R.id.fabChatVoice);
        tvTyping       = findViewById(R.id.tvChatTyping);
        chipGroup      = findViewById(R.id.chipGroupQuickReplies);
        tvContactBadge = findViewById(R.id.tvContactBadge);

        if (tvTyping != null)       tvTyping.setVisibility(View.GONE);
        if (chipGroup != null)      chipGroup.setVisibility(View.GONE);

        // Show contact badge in header
        updateContactBadge();
    }

    private void updateContactBadge() {
        if (tvContactBadge == null) return;
        if (!contact1.isEmpty()) {
            tvContactBadge.setText("📞 " + name1 + " saved");
            tvContactBadge.setVisibility(View.VISIBLE);
        } else {
            tvContactBadge.setText("⚠️ No contacts saved");
            tvContactBadge.setVisibility(View.VISIBLE);
        }
    }

    private void setupRecyclerView() {
        adapter = new ChatAdapter(messages);
        LinearLayoutManager llm = new LinearLayoutManager(this);
        llm.setStackFromEnd(true);
        rvMessages.setLayoutManager(llm);
        rvMessages.setAdapter(adapter);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Listeners
    // ─────────────────────────────────────────────────────────────────────────
    private void setupListeners() {
        btnSend.setOnClickListener(v -> {
            String text = etInput.getText().toString().trim();
            if (!text.isEmpty()) {
                etInput.setText("");
                hideQuickReplies();
                handleUserInput(text);
            }
        });

        etInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                btnSend.performClick();
                return true;
            }
            return false;
        });

        fabVoice.setOnClickListener(v -> openVoiceInput());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Quick reply chips
    // ─────────────────────────────────────────────────────────────────────────
    private void showQuickReplies(String... options) {
        if (chipGroup == null) return;
        chipGroup.removeAllViews();
        chipGroup.setVisibility(View.VISIBLE);

        for (String opt : options) {
            Chip chip = new Chip(this);
            chip.setText(opt);
            chip.setChipBackgroundColorResource(android.R.color.transparent);
            chip.setTextColor(0xFF7C6FFF);
            chip.setChipStrokeColor(android.content.res.ColorStateList.valueOf(0xFF7C6FFF));
            chip.setChipStrokeWidth(2f);
            chip.setOnClickListener(v -> {
                hideQuickReplies();
                handleUserInput(opt);
            });
            chipGroup.addView(chip);
        }
    }

    private void hideQuickReplies() {
        if (chipGroup != null) {
            chipGroup.setVisibility(View.GONE);
            chipGroup.removeAllViews();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TTS
    // ─────────────────────────────────────────────────────────────────────────
    private void setupTTS() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
                tts.setSpeechRate(0.9f);
                ttsReady = true;
            }
        });
    }

    private void speak(String text) {
        if (tts != null && ttsReady) {
            String clean = text.replaceAll("[^\\p{L}\\p{N}\\p{P}\\s]", "");
            tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "chat_" + System.currentTimeMillis());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Voice input
    // ─────────────────────────────────────────────────────────────────────────
    private void openVoiceInput() {
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to your Care Assistant...");
            startActivityForResult(intent, VOICE_REQ);
        } catch (Exception e) {
            Toast.makeText(this, "Voice not available", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VOICE_REQ && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results =
                    data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                hideQuickReplies();
                handleUserInput(results.get(0));
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Message display helpers
    // ─────────────────────────────────────────────────────────────────────────
    private void addUserMessage(String text) {
        messages.add(new ChatMessage(text, true));
        adapter.notifyItemInserted(messages.size() - 1);
        rvMessages.smoothScrollToPosition(messages.size() - 1);
    }

    private void botSay(String text) {
        messages.add(new ChatMessage(text, false));
        adapter.notifyItemInserted(messages.size() - 1);
        rvMessages.smoothScrollToPosition(messages.size() - 1);
        speak(text);
    }

    private void showTypingThenSay(String response) {
        showTypingThenSay(response, null);
    }

    private void showTypingThenSay(String response, String[] quickReplies) {
        if (tvTyping != null) tvTyping.setVisibility(View.VISIBLE);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (tvTyping != null) tvTyping.setVisibility(View.GONE);
            botSay(response);
            if (quickReplies != null) showQuickReplies(quickReplies);
        }, 800);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  CORE: handle everything the user types or speaks
    // ─────────────────────────────────────────────────────────────────────────
    private void handleUserInput(String raw) {
        addUserMessage(raw);
        String input = raw.toLowerCase(Locale.getDefault()).trim();

        // ── Pending confirmations ─────────────────────────────────────────────
        if (pendingAction == PendingAction.CALL_CONFIRM) {
            handleCallConfirmation(input);
            return;
        }
        if (pendingAction == PendingAction.CALL_WHICH) {
            handleCallWhich(raw);
            return;
        }
        if (pendingAction == PendingAction.SOS_CONFIRM) {
            handleSOSConfirmation(input);
            return;
        }
        if (pendingAction == PendingAction.REMINDER_TITLE) {
            handleReminderTitle(raw);
            return;
        }
        if (pendingAction == PendingAction.REMINDER_TIME) {
            handleReminderTime(raw);
            return;
        }

        // ── Rule-based intent classification ──────────────────────────────────
        String ruled = getRuledResponse(input, raw);
        if (ruled != null) {
            return; // response already handled
        }

        // ── AI fallback for everything else ───────────────────────────────────
        askClaude(raw);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Rule-based intent layer — returns null if no rule matched
    // ─────────────────────────────────────────────────────────────────────────
    private String getRuledResponse(String input, String raw) {

        // ── CALL CAREGIVER ────────────────────────────────────────────────────
        if (contains(input, "call", "phone", "ring", "dial", "contact")) {
            // Check if they named a specific contact
            String matched = matchContactByName(input);
            if (matched != null) {
                // Direct call to named contact
                pendingCallNumber = matched.split("\\|")[0];
                pendingCallName   = matched.split("\\|")[1];
                pendingAction     = PendingAction.CALL_CONFIRM;
                showTypingThenSay(
                        "📞 Call " + pendingCallName + " (" + pendingCallNumber + ") right now?\n\nReply YES or NO.",
                        new String[]{"YES", "NO"});
            } else if (howManyContactsSaved() > 1) {
                // Multiple contacts — ask which one
                pendingAction = PendingAction.CALL_WHICH;
                showTypingThenSay(buildContactListPrompt(),
                        buildContactChips());
            } else if (!contact1.isEmpty()) {
                // Only one contact saved — confirm directly
                pendingCallNumber = contact1;
                pendingCallName   = name1;
                pendingAction     = PendingAction.CALL_CONFIRM;
                showTypingThenSay(
                        "📞 Call " + name1 + " (" + contact1 + ") right now?\n\nReply YES or NO.",
                        new String[]{"YES", "NO"});
            } else {
                showTypingThenSay(
                        "📞 I don't have any caregiver numbers saved yet.\n\n" +
                                "Go to Emergency SOS → save your caregiver's phone number there first.\n" +
                                "I'll be able to call them instantly after that!");
            }
            return "handled";
        }

        // ── EMERGENCY / SOS ───────────────────────────────────────────────────
        if (contains(input, "emergency", "sos", "help me", "danger", "accident",
                "fell", "fall", "hurt", "bleeding", "can't breathe",
                "chest pain", "attack", "unconscious", "fainted")) {
            pendingAction = PendingAction.SOS_CONFIRM;
            showTypingThenSay(
                    "🚨 This sounds like an emergency!\n\n" +
                            "Should I activate SOS right now? This will:\n" +
                            "• Send SMS + location to ALL your contacts\n" +
                            "• Play loud alarm\n" +
                            "• Call " + (contact1.isEmpty() ? "your caregiver" : name1) + "\n\n" +
                            "Reply YES to activate or NO to cancel.",
                    new String[]{"YES — Activate SOS", "NO — Cancel"});
            return "handled";
        }

        // ── REMINDER ──────────────────────────────────────────────────────────
        if (contains(input, "remind", "reminder", "medicine", "medication",
                "tablet", "pill", "alarm", "schedule", "don't forget", "set alarm")) {
            pendingAction = PendingAction.REMINDER_TITLE;
            showTypingThenSay(
                    "⏰ Sure! I'll set a reminder for you.\n\n" +
                            "What should I remind you about?\n" +
                            "(e.g. Take blood pressure tablet)",
                    new String[]{"Take medicine", "Drink water", "Doctor appointment", "Exercise"});
            return "handled";
        }

        // ── LOCATION ─────────────────────────────────────────────────────────
        if (contains(input, "where am i", "location", "my location",
                "find me", "lost", "map", "maps")) {
            openMaps();
            showTypingThenSay("📍 Opening your location on Google Maps right now!");
            return "handled";
        }

        // ── SAFETY CAMERA ─────────────────────────────────────────────────────
        if (contains(input, "safety", "camera", "detect", "obstacle",
                "what is ahead", "scan", "object")) {
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> startActivity(new Intent(this, SafetyAssistantActivity.class)), 800);
            showTypingThenSay("📷 Opening Safety Assistant...\nPoint your camera and I'll describe what's ahead.");
            return "handled";
        }

        // ── COMMUNICATION AID ─────────────────────────────────────────────────
        if (contains(input, "speak for me", "communication", "i can't speak",
                "say for me", "text to speech", "voice")) {
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> startActivity(new Intent(this, CommunicationActivity.class)), 800);
            showTypingThenSay("🗣️ Opening Communication Aid...\nType what you want me to say aloud!");
            return "handled";
        }

        // ── BREATHING EXERCISE ────────────────────────────────────────────────
        if (contains(input, "breathe", "breathing", "calm", "relax",
                "anxious", "anxiety", "panic", "stress", "stressed")) {
            startBreathingExercise();
            return "handled";
        }

        // ── MEDICATION INFO ───────────────────────────────────────────────────
        if (contains(input, "what medicine", "which tablet", "what tablet",
                "my medicine", "my medication")) {
            showTypingThenSay(
                    "💊 To know your specific medications, please check with your doctor or caregiver.\n\n" +
                            "I can help you:\n" +
                            "• Set a reminder to take your medicine — just say \"remind me\"\n" +
                            "• Call your caregiver for guidance — say \"call caregiver\"\n\n" +
                            "Never change your medication without medical advice! 🏥");
            return "handled";
        }

        // ── EMOTIONAL SUPPORT ─────────────────────────────────────────────────
        if (contains(input, "scared", "afraid", "fear", "frightened", "i'm scared")) {
            pendingAction = PendingAction.CALL_CONFIRM;
            pendingCallNumber = contact1;
            pendingCallName   = name1;
            showTypingThenSay(
                    "💙 I hear you. It's completely okay to feel scared.\n\n" +
                            "You are NOT alone — I'm here with you.\n\n" +
                            "Would you like me to call " + (contact1.isEmpty() ? "your caregiver" : name1) + " right now?\n\nReply YES or NO.",
                    new String[]{"YES — Call now", "NO — Just talk"});
            return "handled";
        }
        if (contains(input, "sick", "unwell", "not feeling well",
                "nausea", "pain", "headache", "fever", "dizzy",
                "vomit", "stomach", "ill")) {
            pendingAction     = PendingAction.CALL_CONFIRM;
            pendingCallNumber = contact1;
            pendingCallName   = name1;
            showTypingThenSay(
                    "😟 I'm sorry you're not feeling well.\n\n" +
                            "If this is serious, I can call " + (contact1.isEmpty() ? "your caregiver" : name1) + " right away.\n" +
                            "Should I call them now?",
                    new String[]{"YES — Call now", "NO — I'm managing"});
            return "handled";
        }
        if (contains(input, "lonely", "alone", "no one", "nobody", "isolated")) {
            showTypingThenSay(
                    "💙 I understand. Feeling alone is difficult.\n\n" +
                            "I'm right here with you! You can:\n" +
                            "• Talk to me — I'm always listening 😊\n" +
                            "• Say \"call caregiver\" and I'll connect you instantly\n\n" +
                            "What would you like to talk about?",
                    new String[]{"Call caregiver", "Tell me a joke", "Let's just talk"});
            return "handled";
        }
        if (contains(input, "sad", "depressed", "unhappy", "crying",
                "upset", "miserable", "hopeless")) {
            showTypingThenSay(
                    "💙 I'm really sorry you're feeling this way. Your feelings matter.\n\n" +
                            "You don't have to face this alone.\n" +
                            "Talking always helps — tell me more about what's going on.\n\n" +
                            "Or if you'd like someone to talk to, I can call your caregiver right now.",
                    new String[]{"Call caregiver", "I want to talk", "Do breathing exercise"});
            return "handled";
        }
        if (contains(input, "tired", "exhausted", "can't sleep", "sleepy", "fatigued")) {
            showTypingThenSay(
                    "😴 Rest is so important for your wellbeing.\n\n" +
                            "Would you like me to:\n" +
                            "• Set a reminder to rest?\n" +
                            "• Guide you through a calming breathing exercise?\n\n" +
                            "Just say which one!",
                    new String[]{"Set rest reminder", "Breathing exercise"});
            return "handled";
        }
        if (contains(input, "happy", "good", "great", "wonderful",
                "feeling well", "fine", "excellent", "perfect")) {
            showTypingThenSay(
                    "😊 That's wonderful to hear! I'm so glad you're feeling good today!\n\n" +
                            "Is there anything I can help you with?",
                    new String[]{"Set a reminder", "Call caregiver", "Nothing, thanks"});
            return "handled";
        }

        // ── JOKE ─────────────────────────────────────────────────────────────
        if (contains(input, "joke", "funny", "laugh", "cheer me up")) {
            String[] jokes = {
                    "Why don't scientists trust atoms? Because they make up everything! 😄",
                    "What do you call a fish without eyes? A fsh! 😂",
                    "Why did the scarecrow win an award? Because he was outstanding in his field! 🌾😄",
                    "I told my doctor I broke my arm in two places. He told me to stop going to those places! 😂",
                    "Why can't you give Elsa a balloon? Because she'll let it go! ❄️😄"
            };
            showTypingThenSay(jokes[new Random().nextInt(jokes.length)] +
                            "\n\nWant to hear another one? 😊",
                    new String[]{"Tell me another", "That was funny!", "No thanks"});
            return "handled";
        }

        // ── GRATITUDE ─────────────────────────────────────────────────────────
        if (contains(input, "thank", "thanks", "thank you")) {
            showTypingThenSay(
                    "😊 You're very welcome! I'm always here for you.\n" +
                            "Stay safe and take care! 💙");
            return "handled";
        }

        // ── GREETING ──────────────────────────────────────────────────────────
        if (contains(input, "hello", "hi", "hey", "good morning",
                "good evening", "good afternoon", "good night")) {
            String name = (userName != null && !userName.isEmpty()) ? ", " + userName : "";
            showTypingThenSay(
                    "Hello" + name + "! 😊 Great to hear from you!\n\n" +
                            "How are you feeling right now?",
                    new String[]{"I'm okay", "I need help", "Not feeling well", "I'm happy"});
            return "handled";
        }

        // ── HELP MENU ─────────────────────────────────────────────────────────
        if (contains(input, "help", "what can you do", "features",
                "menu", "options", "commands", "list")) {
            showTypingThenSay(
                    "🤖 Here's everything I can do for you:\n\n" +
                            "📞 Call any of your saved caregivers by name\n" +
                            "🚨 Trigger emergency SOS instantly\n" +
                            "⏰ Set medicine & activity reminders\n" +
                            "📍 Show your location on the map\n" +
                            "📷 Open safety camera to detect obstacles\n" +
                            "🗣️ Speak for you using Communication Aid\n" +
                            "🧘 Guide breathing & relaxation exercises\n" +
                            "😄 Tell you a joke to cheer you up\n" +
                            "💬 Just talk — I understand your feelings!\n\n" +
                            "What would you like to do?",
                    new String[]{"Call caregiver", "Emergency SOS", "Set reminder", "Tell me a joke"});
            return "handled";
        }

        // ── HEALTH / TODAY'S ACTIVITIES ───────────────────────────────────────
        // Triggered by: "how am i doing", "show my health", "today activities",
        //               "my reminders", "health status", "dashboard", "status"
        if (contains(input,
                "how am i doing", "how am i", "my health", "show my health",
                "health status", "my status", "health report", "my report",
                "today activities", "today's activities", "today reminder",
                "my reminders", "my reminder", "show reminder",
                "dashboard", "activity", "activities",
                "what have i done", "what did i do",
                "how many", "summary", "my day",
                "status")) {
            showHealthSummary();
            return "handled";
        }

        return null; // no rule matched — fall through to AI
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Health summary — reads reminders + user_status from Firestore
    //  Called when user asks "how am I doing", "show my health", etc.
    // ─────────────────────────────────────────────────────────────────────────
    private void showHealthSummary() {
        // Show typing while we fetch from Firestore
        if (tvTyping != null) tvTyping.setVisibility(android.view.View.VISIBLE);

        if (db == null || userId == null) {
            if (tvTyping != null) tvTyping.setVisibility(android.view.View.GONE);
            showTypingThenSay(
                    "📊 I couldn't load your health data right now.\n\n" +
                            "Please make sure you're connected to the internet and logged in.\n\n" +
                            "Is there anything else I can help you with?",
                    new String[]{"Set a reminder", "Call caregiver", "I'm okay"});
            return;
        }

        // Step 1: read reminders for this user
        db.collection("reminders")
                .whereEqualTo("userId", userId)
                .get()
                .addOnSuccessListener(reminderSnap -> {

                    int completed = 0, missed = 0, pending = 0;
                    java.util.List<String> completedList = new java.util.ArrayList<>();
                    java.util.List<String> missedList    = new java.util.ArrayList<>();
                    java.util.List<String> pendingList   = new java.util.ArrayList<>();

                    for (com.google.firebase.firestore.DocumentSnapshot doc : reminderSnap.getDocuments()) {
                        String title  = doc.getString("title");
                        String status = doc.getString("status");
                        if (title == null) title = "Reminder";

                        if ("Completed".equalsIgnoreCase(status)) {
                            completed++;
                            if (completedList.size() < 3) completedList.add(title);
                        } else if ("Missed".equalsIgnoreCase(status)) {
                            missed++;
                            if (missedList.size() < 3) missedList.add(title);
                        } else {
                            pending++;
                            if (pendingList.size() < 3) pendingList.add(title);
                        }
                    }

                    final int fCompleted = completed;
                    final int fMissed    = missed;
                    final int fPending   = pending;
                    final java.util.List<String> fCompletedList = completedList;
                    final java.util.List<String> fMissedList    = missedList;
                    final java.util.List<String> fPendingList   = pendingList;

                    // Step 2: read risk level from user_status
                    db.collection("user_status")
                            .document(userId)
                            .get()
                            .addOnSuccessListener(statusDoc -> {

                                if (tvTyping != null)
                                    tvTyping.setVisibility(android.view.View.GONE);

                                String riskLevel = "LOW";
                                if (statusDoc != null && statusDoc.exists()) {
                                    String r = statusDoc.getString("riskLevel");
                                    if (r != null && !r.isEmpty()) riskLevel = r;
                                }

                                // Build the message
                                StringBuilder sb = new StringBuilder();
                                sb.append("📊 Here's your health summary for today:\n\n");

                                // Reminder stats
                                sb.append("✅ Completed: ").append(fCompleted).append("\n");
                                if (!fCompletedList.isEmpty()) {
                                    for (String t : fCompletedList)
                                        sb.append("   • ").append(t).append("\n");
                                }

                                sb.append("\n❌ Missed: ").append(fMissed).append("\n");
                                if (!fMissedList.isEmpty()) {
                                    for (String t : fMissedList)
                                        sb.append("   • ").append(t).append("\n");
                                }

                                sb.append("\n⏳ Pending: ").append(fPending).append("\n");
                                if (!fPendingList.isEmpty()) {
                                    for (String t : fPendingList)
                                        sb.append("   • ").append(t).append("\n");
                                }

                                // Risk level with emoji
                                sb.append("\n");
                                String riskEmoji = "🟢";
                                if ("HIGH".equalsIgnoreCase(riskLevel))   riskEmoji = "🔴";
                                else if ("MEDIUM".equalsIgnoreCase(riskLevel)) riskEmoji = "🟡";
                                sb.append("Risk Level: ").append(riskEmoji).append(" ").append(riskLevel);

                                // Personalised advice
                                sb.append("\n\n");
                                if (fMissed > 0 && "HIGH".equalsIgnoreCase(riskLevel)) {
                                    sb.append("⚠️ You've missed some reminders and your risk is HIGH.\nPlease contact your caregiver now.");
                                } else if (fMissed > 0) {
                                    sb.append("💡 You missed " + fMissed + " reminder(s) today.\nTry to stay on schedule — I can set a new reminder anytime!");
                                } else if (fCompleted > 0 && fMissed == 0) {
                                    sb.append("🌟 Excellent! You completed all your reminders today. Keep it up!");
                                } else if (fPending > 0) {
                                    sb.append("📋 You have " + fPending + " pending reminder(s) coming up.\nI'll notify you at the right time!");
                                } else {
                                    sb.append("💙 No reminders set for today. Say \"remind me\" to add one!");
                                }

                                final String message = sb.toString();
                                runOnUiThread(() -> {
                                    botSay(message);
                                    showQuickReplies(
                                            "Set a reminder ⏰",
                                            "Call caregiver 📞",
                                            "I'm okay 😊"
                                    );
                                });
                            })
                            .addOnFailureListener(e -> {
                                // user_status doc not found — still show reminders
                                if (tvTyping != null)
                                    tvTyping.setVisibility(android.view.View.GONE);

                                StringBuilder sb = new StringBuilder();
                                sb.append("📊 Here's your health summary for today:\n\n");
                                sb.append("✅ Completed: ").append(fCompleted).append("\n");
                                sb.append("❌ Missed: ").append(fMissed).append("\n");
                                sb.append("⏳ Pending: ").append(fPending).append("\n\n");
                                sb.append("Risk Level: 🟢 LOW");

                                if (fMissed > 0) {
                                    sb.append("\n\n💡 You missed " + fMissed + " reminder(s) today.");
                                } else if (fCompleted > 0) {
                                    sb.append("\n\n🌟 Great job completing your reminders!");
                                }

                                final String msg = sb.toString();
                                runOnUiThread(() -> {
                                    botSay(msg);
                                    showQuickReplies("Set a reminder ⏰", "Call caregiver 📞", "I'm okay 😊");
                                });
                            });
                })
                .addOnFailureListener(e -> {
                    if (tvTyping != null) tvTyping.setVisibility(android.view.View.GONE);
                    showTypingThenSay(
                            "📊 I couldn't load your reminders right now.\n" +
                                    "Please check your internet connection and try again.",
                            new String[]{"Try again", "Call caregiver", "I'm okay"});
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Contact matching helpers
    // ─────────────────────────────────────────────────────────────────────────
    private String matchContactByName(String input) {
        if (!contact1.isEmpty() && !name1.isEmpty()
                && input.contains(name1.toLowerCase())) return contact1 + "|" + name1;
        if (!contact2.isEmpty() && !name2.isEmpty()
                && input.contains(name2.toLowerCase())) return contact2 + "|" + name2;
        if (!contact3.isEmpty() && !name3.isEmpty()
                && input.contains(name3.toLowerCase())) return contact3 + "|" + name3;
        // Also match "contact 1/2/3"
        if (input.contains("contact 1") || input.contains("first")) {
            if (!contact1.isEmpty()) return contact1 + "|" + name1;
        }
        if (input.contains("contact 2") || input.contains("second")) {
            if (!contact2.isEmpty()) return contact2 + "|" + name2;
        }
        if (input.contains("contact 3") || input.contains("third")) {
            if (!contact3.isEmpty()) return contact3 + "|" + name3;
        }
        return null;
    }

    private int howManyContactsSaved() {
        int c = 0;
        if (!contact1.isEmpty()) c++;
        if (!contact2.isEmpty()) c++;
        if (!contact3.isEmpty()) c++;
        return c;
    }

    private String buildContactListPrompt() {
        StringBuilder sb = new StringBuilder("📞 Which caregiver should I call?\n\n");
        if (!contact1.isEmpty()) sb.append("1️⃣ ").append(name1).append(" (").append(contact1).append(")\n");
        if (!contact2.isEmpty()) sb.append("2️⃣ ").append(name2).append(" (").append(contact2).append(")\n");
        if (!contact3.isEmpty()) sb.append("3️⃣ ").append(name3).append(" (").append(contact3).append(")\n");
        sb.append("\nSay their name or number (1, 2, or 3).");
        return sb.toString();
    }

    private String[] buildContactChips() {
        List<String> chips = new ArrayList<>();
        if (!contact1.isEmpty()) chips.add(name1);
        if (!contact2.isEmpty()) chips.add(name2);
        if (!contact3.isEmpty()) chips.add(name3);
        chips.add("Cancel");
        return chips.toArray(new String[0]);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Confirmation handlers
    // ─────────────────────────────────────────────────────────────────────────
    private void handleCallConfirmation(String input) {
        pendingAction = PendingAction.NONE;
        if (isYes(input)) {
            callNumber(pendingCallNumber, pendingCallName);
        } else if (isNo(input)) {
            showTypingThenSay("Okay, no problem. I'm still here if you need anything! 😊");
        } else {
            showTypingThenSay("Please reply YES to call or NO to cancel.",
                    new String[]{"YES", "NO"});
            pendingAction = PendingAction.CALL_CONFIRM;
        }
    }

    private void handleCallWhich(String raw) {
        String input = raw.toLowerCase(Locale.getDefault()).trim();
        pendingAction = PendingAction.NONE;

        if (input.contains("cancel") || input.contains("no") || input.contains("never")) {
            showTypingThenSay("Okay, cancelled. I'm here if you need anything! 😊");
            return;
        }

        // Try to match by name
        String matched = matchContactByName(input);
        if (matched != null) {
            pendingCallNumber = matched.split("\\|")[0];
            pendingCallName   = matched.split("\\|")[1];
            pendingAction     = PendingAction.CALL_CONFIRM;
            showTypingThenSay("📞 Call " + pendingCallName + " now?\n\nReply YES or NO.",
                    new String[]{"YES", "NO"});
            return;
        }

        // Try by number
        if (input.contains("1") || input.contains("first") || input.contains("one")) {
            if (!contact1.isEmpty()) { doCall(contact1, name1); return; }
        }
        if (input.contains("2") || input.contains("second") || input.contains("two")) {
            if (!contact2.isEmpty()) { doCall(contact2, name2); return; }
        }
        if (input.contains("3") || input.contains("third") || input.contains("three")) {
            if (!contact3.isEmpty()) { doCall(contact3, name3); return; }
        }

        // Not understood — show list again
        pendingAction = PendingAction.CALL_WHICH;
        showTypingThenSay("I didn't catch that. " + buildContactListPrompt(),
                buildContactChips());
    }

    private void handleSOSConfirmation(String input) {
        pendingAction = PendingAction.NONE;
        if (isYes(input)) {
            triggerSOS();
        } else if (isNo(input)) {
            showTypingThenSay("SOS cancelled. I'm here if you need anything. Stay safe! 💙",
                    new String[]{"Call caregiver", "I'm okay now"});
        } else {
            showTypingThenSay("Please reply YES to activate SOS or NO to cancel.",
                    new String[]{"YES — Activate SOS", "NO — Cancel"});
            pendingAction = PendingAction.SOS_CONFIRM;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Reminder collection (title → time)
    // ─────────────────────────────────────────────────────────────────────────
    private void handleReminderTitle(String title) {
        pendingReminderTitle = title;
        pendingAction = PendingAction.REMINDER_TIME;
        showTypingThenSay(
                "Got it! ✅\n\nWhat time should I remind you?\n" +
                        "You can say things like:\n" +
                        "• \"In 30 minutes\"\n• \"At 3 PM\"\n• \"In 2 hours\"",
                new String[]{"In 30 minutes", "In 1 hour", "In 2 hours"});
    }

    private void handleReminderTime(String timeInput) {
        long reminderTimeMs = parseTime(timeInput);
        if (reminderTimeMs <= 0) {
            showTypingThenSay(
                    "I didn't understand that time. Please try again.\n" +
                            "Examples: \"In 30 minutes\", \"At 3 PM\", \"In 1 hour\"",
                    new String[]{"In 30 minutes", "In 1 hour", "At 8 PM"});
            pendingAction = PendingAction.REMINDER_TIME;
            return;
        }
        String savedTitle = pendingReminderTitle;
        pendingReminderTitle = null;
        pendingAction = PendingAction.NONE;

        scheduleReminderFromChat(savedTitle, reminderTimeMs);

        String timeStr = new SimpleDateFormat("hh:mm a", Locale.getDefault())
                .format(new Date(reminderTimeMs));
        showTypingThenSay(
                "✅ Reminder set!\n\n" +
                        "📋 " + savedTitle + "\n" +
                        "⏰ " + timeStr + "\n\n" +
                        "I'll notify you at that time. Is there anything else I can help with?",
                new String[]{"Set another reminder", "Call caregiver", "I'm done"});
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Breathing exercise
    // ─────────────────────────────────────────────────────────────────────────
    private void startBreathingExercise() {
        botSay("🧘 Let's do a calming breathing exercise together.\n\nFind a comfortable position and follow my guidance...");

        String[] steps = {
                "😮‍💨 Breathe IN slowly... count 1... 2... 3... 4...",
                "⏸️ HOLD your breath... 1... 2... 3... 4... 5... 6... 7...",
                "😤 Breathe OUT slowly... 1... 2... 3... 4... 5... 6... 7... 8...",
                "😮‍💨 Breathe IN again... 1... 2... 3... 4...",
                "⏸️ HOLD... 1... 2... 3... 4... 5... 6... 7...",
                "😤 Breathe OUT... 1... 2... 3... 4... 5... 6... 7... 8...",
                "✅ Well done! You completed a 4-7-8 breathing cycle.\n\nHow do you feel now? You should feel calmer. 💙"
        };

        long[] delays = {2000, 6000, 8000, 17000, 21000, 29000, 38000};

        Handler h = new Handler(Looper.getMainLooper());
        for (int i = 0; i < steps.length; i++) {
            final String step = steps[i];
            final boolean isLast = (i == steps.length - 1);
            h.postDelayed(() -> {
                botSay(step);
                if (isLast) {
                    showQuickReplies("I feel better", "Do it again", "Call caregiver");
                }
            }, delays[i]);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Claude AI — free-form conversation for anything not rule-matched
    // ─────────────────────────────────────────────────────────────────────────
    private void askClaude(String userMessage) {
        if (tvTyping != null) tvTyping.setVisibility(View.VISIBLE);

        // Add to AI history
        Map<String, String> userMsg = new HashMap<>();
        userMsg.put("role",    "user");
        userMsg.put("content", userMessage);
        aiHistory.add(userMsg);

        // Keep history to last 10 messages to avoid token overflow
        List<Map<String, String>> historyWindow = aiHistory.size() > 10
                ? aiHistory.subList(aiHistory.size() - 10, aiHistory.size())
                : new ArrayList<>(aiHistory);

        aiExecutor.execute(() -> {
            String response = callClaudeAPI(historyWindow);
            runOnUiThread(() -> {
                if (tvTyping != null) tvTyping.setVisibility(View.GONE);
                botSay(response);

                // Add AI response to history
                Map<String, String> botMsg = new HashMap<>();
                botMsg.put("role",    "assistant");
                botMsg.put("content", response);
                aiHistory.add(botMsg);
            });
        });
    }

    private String callClaudeAPI(List<Map<String, String>> history) {
        // If no API key is configured, return a helpful fallback
        if (CLAUDE_API_KEY == null || CLAUDE_API_KEY.isEmpty()) {
            return getFallbackResponse();
        }

        try {
            URL url = new URL(CLAUDE_API_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("x-api-key", CLAUDE_API_KEY);
            conn.setRequestProperty("anthropic-version", "2023-06-01");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);

            // Build system prompt with user context
            String caregiverContext = buildCaregiverSummary().replace("\n\n", ". ");
            String systemPrompt =
                    "You are CareLink AI, a powerful and caring assistant inside a health app " +
                            "called CareLink. You help elderly and differently-abled users.\n\n" +
                            "User context: " +
                            (userName != null ? "User's name is " + userName + ". " : "") +
                            (caregiverContext.isEmpty() ? "No caregiver contacts saved. " : caregiverContext) +
                            "\n\nGuidelines:\n" +
                            "- Be warm, empathetic, patient, like a caring friend\n" +
                            "- Keep replies SHORT and simple (2-3 sentences max)\n" +
                            "- NEVER give specific medical diagnoses or drug doses\n" +
                            "- For ANY health concern always suggest calling a caregiver\n" +
                            "- Remember context from this conversation\n" +
                            "- Use simple words — the user may be elderly or differently-abled\n" +
                            "- Use emojis to make responses friendly\n" +
                            "- If unsure, say 'I'm here for you' and suggest calling the caregiver";

            // Build messages array
            JSONArray messagesArr = new JSONArray();
            for (Map<String, String> msg : history) {
                JSONObject m = new JSONObject();
                m.put("role",    msg.get("role"));
                m.put("content", msg.get("content"));
                messagesArr.put(m);
            }

            JSONObject body = new JSONObject();
            body.put("model",      CLAUDE_MODEL);
            body.put("max_tokens", 500);
            body.put("system",     systemPrompt);
            body.put("messages",   messagesArr);

            byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }

            int code = conn.getResponseCode();
            if (code == 200) {
                java.io.InputStream is = conn.getInputStream();
                String responseStr = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                JSONObject json = new JSONObject(responseStr);
                return json.getJSONArray("content")
                        .getJSONObject(0)
                        .getString("text")
                        .trim();
            } else {
                Log.e(TAG, "Claude API error: " + code);
                return getFallbackResponse();
            }
        } catch (Exception e) {
            Log.e(TAG, "Claude API exception: " + e.getMessage());
            return getFallbackResponse();
        }
    }

    private String getFallbackResponse() {
        // Friendly fallback when AI isn't available
        String[] fallbacks = {
                "I'm here with you! 😊 If you need anything, just ask. Say \"help\" to see what I can do.",
                "I didn't quite understand that, but I'm here! 💙 Try saying \"call caregiver\" or \"I need help\".",
                "I'm always here for you. 😊 You can talk to me or say \"help\" to see all my features.",
                "I hear you! If you need urgent help, just say \"emergency\" and I'll activate SOS right away. 🚨",
                "I care about you! 💙 I can call someone, set a reminder, or just listen. What do you need?",
                "You're not alone. 😊 Tell me how you feel, or say 'help' to see everything I can do."
        };
        return fallbacks[new Random().nextInt(fallbacks.length)];
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Action: call a specific number
    // ─────────────────────────────────────────────────────────────────────────
    private void callCaregiver() {
        // Default: call contact1
        if (contact1.isEmpty()) {
            botSay("📞 I don't have any caregiver numbers saved yet.\n\n" +
                    "Go to Emergency SOS and save your caregiver's number first.");
            return;
        }
        doCall(contact1, name1);
    }

    private void callNumber(String number, String name) {
        if (number == null || number.isEmpty()) {
            botSay("❌ No number saved for " + name + ".\n" +
                    "Go to Emergency SOS to add it.");
            return;
        }
        doCall(number, name);
    }

    private void doCall(String number, String name) {
        botSay("📞 Calling " + name + " (" + number + ") now...");
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Intent call = new Intent(Intent.ACTION_CALL);
                call.setData(Uri.parse("tel:" + number));
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                        == PackageManager.PERMISSION_GRANTED) {
                    startActivity(call);
                } else {
                    // Fallback to dial screen
                    Intent dial = new Intent(Intent.ACTION_DIAL);
                    dial.setData(Uri.parse("tel:" + number));
                    startActivity(dial);
                }
            } catch (Exception e) {
                Log.e(TAG, "Call failed: " + e.getMessage());
                botSay("❌ Could not place the call automatically.\nPlease dial " + number + " manually.");
            }
        }, 1000);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Action: Trigger SOS
    // ─────────────────────────────────────────────────────────────────────────
    private void triggerSOS() {
        botSay("🚨 Activating SOS! Stay calm — help is on the way!");

        if (userId != null) {
            try {
                Map<String, Object> sos = new HashMap<>();
                sos.put("userId",    userId);
                sos.put("timestamp", System.currentTimeMillis());
                sos.put("latitude",  0.0);
                sos.put("longitude", 0.0);
                sos.put("status",    "Active");
                sos.put("source",    "chatbot");
                db.collection("sos").add(sos);
            } catch (Exception e) {
                Log.e(TAG, "SOS Firestore error: " + e.getMessage());
            }
        }

        new Handler(Looper.getMainLooper()).postDelayed(
                () -> startActivity(new Intent(this, EmergencySOSActivity.class)), 1200);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Action: Open maps
    // ─────────────────────────────────────────────────────────────────────────
    private void openMaps() {
        try {
            Intent maps = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("geo:0,0?q=my+location"));
            maps.setPackage("com.google.android.apps.maps");
            if (maps.resolveActivity(getPackageManager()) != null) {
                startActivity(maps);
            } else {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://maps.google.com/maps?q=my+location")));
            }
        } catch (Exception e) {
            botSay("❌ Could not open maps. Please open Google Maps manually.");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Action: Schedule reminder
    // ─────────────────────────────────────────────────────────────────────────
    private void scheduleReminderFromChat(String title, long timeMs) {
        try {
            Reminder r = new Reminder(title, "Set by Care Assistant", timeMs, false);

            SharedPreferences prefs = getSharedPreferences(PREFS_REM, MODE_PRIVATE);
            String json = prefs.getString("reminders", "[]");
            List<Reminder> list;
            try {
                com.google.gson.reflect.TypeToken<ArrayList<Reminder>> token =
                        new com.google.gson.reflect.TypeToken<ArrayList<Reminder>>(){};
                list = new com.google.gson.Gson().fromJson(json, token.getType());
                if (list == null) list = new ArrayList<>();
            } catch (Exception e) {
                list = new ArrayList<>();
            }
            list.add(r);
            prefs.edit().putString("reminders",
                    new com.google.gson.Gson().toJson(list)).apply();

            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;

            Intent intent = new Intent(this, ReminderReceiver.class);
            intent.putExtra("title",       title);
            intent.putExtra("description", "Set by Care Assistant");
            intent.putExtra("id",          (int)(timeMs % Integer.MAX_VALUE));

            PendingIntent pi = PendingIntent.getBroadcast(this,
                    (int)(timeMs % Integer.MAX_VALUE), intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timeMs, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, timeMs, pi);
            }

            if (userId != null && db != null) {
                Map<String, Object> data = new HashMap<>();
                data.put("userId",      userId);
                data.put("title",       title);
                data.put("description", "Set by Care Assistant");
                data.put("time",        timeMs);
                data.put("medication",  false);
                data.put("status",      "Pending");
                data.put("addedBy",     "user");
                db.collection("reminders").add(data);
            }
        } catch (Exception e) {
            Log.e(TAG, "scheduleReminderFromChat error: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Time parser
    // ─────────────────────────────────────────────────────────────────────────
    private long parseTime(String input) {
        String s = input.toLowerCase(Locale.getDefault()).trim();
        long now = System.currentTimeMillis();
        try {
            if (s.matches(".*in\\s+(\\d+)\\s+min.*")) {
                int mins = Integer.parseInt(s.replaceAll(".*in\\s+(\\d+)\\s+min.*", "$1"));
                return now + mins * 60_000L;
            }
            if (s.matches(".*in\\s+(\\d+)\\s+hour.*")) {
                int hrs = Integer.parseInt(s.replaceAll(".*in\\s+(\\d+)\\s+hour.*", "$1"));
                return now + hrs * 3_600_000L;
            }
            if (s.matches(".*in\\s+(\\d+)\\s+sec.*")) {
                int secs = Integer.parseInt(s.replaceAll(".*in\\s+(\\d+)\\s+sec.*", "$1"));
                return now + secs * 1_000L;
            }
            if (s.contains("at ")) {
                String timePart = s.replaceAll(".*at\\s+", "").trim();
                for (String fmt : new String[]{"h:mm a", "h a", "HH:mm", "H:mm"}) {
                    try {
                        SimpleDateFormat sdf = new SimpleDateFormat(fmt, Locale.US);
                        Date parsed = sdf.parse(timePart);
                        if (parsed != null) {
                            Calendar target    = Calendar.getInstance();
                            Calendar parsedCal = Calendar.getInstance();
                            parsedCal.setTime(parsed);
                            target.set(Calendar.HOUR_OF_DAY, parsedCal.get(Calendar.HOUR_OF_DAY));
                            target.set(Calendar.MINUTE,      parsedCal.get(Calendar.MINUTE));
                            target.set(Calendar.SECOND,      0);
                            if (target.getTimeInMillis() <= now)
                                target.add(Calendar.DAY_OF_YEAR, 1);
                            return target.getTimeInMillis();
                        }
                    } catch (Exception ignored) {}
                }
            }
            if (s.matches("\\d+")) {
                return now + Integer.parseInt(s) * 60_000L;
            }
        } catch (Exception e) {
            Log.e(TAG, "parseTime error: " + e.getMessage());
        }
        return -1;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────
    private boolean contains(String input, String... keywords) {
        for (String k : keywords) if (input.contains(k)) return true;
        return false;
    }

    private boolean isYes(String input) {
        return contains(input, "yes", "yeah", "yep", "ok", "okay", "sure",
                "do it", "please", "call", "activate", "go ahead", "confirm", "now");
    }

    private boolean isNo(String input) {
        return contains(input, "no", "nope", "don't", "cancel",
                "stop", "not now", "skip", "never");
    }

    private void loadNameFromFirestore() {
        if (userId == null || db == null) return;
        db.collection("users").document(userId).get()
                .addOnSuccessListener(doc -> {
                    if (doc != null) userName = doc.getString("name");
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Called by SOS button in header XML (android:onClick)
    public void onHeaderSOSClick(android.view.View v) {
        hideQuickReplies();
        pendingAction = PendingAction.SOS_CONFIRM;
        botSay("🚨 Activating SOS!\n\nThis will alert ALL your contacts and share your location.\n\nReply YES to confirm or NO to cancel.");
        showQuickReplies("YES — Activate SOS", "NO — Cancel");
    }

    @Override
    protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (aiExecutor != null) aiExecutor.shutdown();
        super.onDestroy();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ChatMessage model
    // ─────────────────────────────────────────────────────────────────────────
    public static class ChatMessage {
        public final String  text;
        public final boolean isUser;
        public final long    timestamp;

        public ChatMessage(String text, boolean isUser) {
            this.text      = text;
            this.isUser    = isUser;
            this.timestamp = System.currentTimeMillis();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ChatAdapter
    // ─────────────────────────────────────────────────────────────────────────
    public static class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.VH> {

        private static final int TYPE_USER = 1;
        private static final int TYPE_BOT  = 0;

        private final List<ChatMessage> items;
        public ChatAdapter(List<ChatMessage> items) { this.items = items; }

        @Override public int getItemViewType(int pos) {
            return items.get(pos).isUser ? TYPE_USER : TYPE_BOT;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int layout = viewType == TYPE_USER
                    ? R.layout.item_chat_user
                    : R.layout.item_chat_bot;
            View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            ChatMessage msg = items.get(pos);
            h.tvMessage.setText(msg.text);
            // Show timestamp
            if (h.tvTime != null) {
                h.tvTime.setText(new SimpleDateFormat("hh:mm a", Locale.getDefault())
                        .format(new Date(msg.timestamp)));
            }
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvMessage;
            TextView tvTime;
            VH(@NonNull View v) {
                super(v);
                tvMessage = v.findViewById(R.id.tvChatMessage);
                tvTime    = v.findViewById(R.id.tvChatTime); // optional — won't crash if missing
            }
        }
    }
}