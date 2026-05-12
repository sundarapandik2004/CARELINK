package com.example.accessease;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "SettingsActivity";

    // ── Settings views ────────────────────────────────────────────────────────
    private SeekBar  sbFontSize, sbSpeechRate;
    private Switch   swVoiceFeedback, swHighContrast;
    private TextView tvFontValue, tvSpeechValue;
    private SharedPreferences prefs;

    // ── Caregiver linking views ───────────────────────────────────────────────
    private TextView    tvCurrentCaregiverStatus;
    private EditText    etNewCaregiverCode;
    private Button      btnLinkCaregiver, btnRemoveCaregiver;
    private ProgressBar pbLinking;

    // ── Theme picker ──────────────────────────────────────────────────────────
    private LinearLayout themeContainer;

    // ── Firebase ──────────────────────────────────────────────────────────────
    private FirebaseFirestore db;
    private String currentUserId;
    private String currentUserRole; // ★ track the logged-in user's own role

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences("AccessEase", MODE_PRIVATE);

        try {
            db = FirebaseFirestore.getInstance();
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            currentUserId = user != null ? user.getUid() : null;
        } catch (Exception e) {
            Log.w(TAG, "Firebase not available");
        }

        initializeViews();
        loadSettings();
        setupListeners();
        buildThemePicker();
        loadCurrentUserRoleThenCaregiverStatus(); // ★ load role first
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  View binding
    // ─────────────────────────────────────────────────────────────────────────
    private void initializeViews() {
        sbFontSize      = findViewById(R.id.sbFontSize);
        sbSpeechRate    = findViewById(R.id.sbSpeechRate);
        swVoiceFeedback = findViewById(R.id.swVoiceFeedback);
        swHighContrast  = findViewById(R.id.swHighContrast);
        tvFontValue     = findViewById(R.id.tvFontSizeValue);
        tvSpeechValue   = findViewById(R.id.tvSpeechRateValue);

        tvCurrentCaregiverStatus = findViewById(R.id.tvCurrentCaregiverStatus);
        etNewCaregiverCode       = findViewById(R.id.etNewCaregiverCode);
        btnLinkCaregiver         = findViewById(R.id.btnLinkCaregiver);
        btnRemoveCaregiver       = findViewById(R.id.btnRemoveCaregiver);
        pbLinking                = findViewById(R.id.pbLinking);

        themeContainer = findViewById(R.id.themeContainer);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ★ Load the logged-in user's OWN role first, then show caregiver status.
    //    This prevents the caregiver link section from showing to caregivers,
    //    and makes sure we never corrupt the user's own role field.
    // ─────────────────────────────────────────────────────────────────────────
    private void loadCurrentUserRoleThenCaregiverStatus() {
        if (db == null || currentUserId == null) return;

        db.collection("users").document(currentUserId)
                .get()
                .addOnSuccessListener(doc -> {
                    currentUserRole = doc.getString("role");
                    Log.d(TAG, "Current user role: " + currentUserRole);

                    if ("caregiver".equals(currentUserRole)) {
                        // Caregiver has no need for a caregiver link section
                        View caregiverSection = findViewById(R.id.caregiverLinkSection);
                        if (caregiverSection != null)
                            caregiverSection.setVisibility(View.GONE);
                    } else {
                        // User — show caregiver link status
                        loadCurrentCaregiverStatus(doc.getString("linkedCaregiverId"));
                    }
                })
                .addOnFailureListener(e ->
                        Log.e(TAG, "Failed to load user role: " + e.getMessage()));
    }

    private void loadCurrentCaregiverStatus(String linkedId) {
        if (tvCurrentCaregiverStatus == null) return;

        if (linkedId == null || linkedId.isEmpty()) {
            tvCurrentCaregiverStatus.setText("Not linked to any caregiver");
            tvCurrentCaregiverStatus.setTextColor(0xFFEF4444);
            if (btnRemoveCaregiver != null) btnRemoveCaregiver.setVisibility(View.GONE);
        } else {
            db.collection("users").document(linkedId)
                    .get()
                    .addOnSuccessListener(cgDoc -> {
                        String cgName = cgDoc.getString("name");
                        tvCurrentCaregiverStatus.setText(
                                "Linked to: " + (cgName != null ? cgName : linkedId));
                        tvCurrentCaregiverStatus.setTextColor(0xFF10B981);
                        if (btnRemoveCaregiver != null)
                            btnRemoveCaregiver.setVisibility(View.VISIBLE);
                    })
                    .addOnFailureListener(e -> {
                        tvCurrentCaregiverStatus.setText(
                                "Linked (code: " + linkedId.substring(0, Math.min(8, linkedId.length())) + "...)");
                        tvCurrentCaregiverStatus.setTextColor(0xFF10B981);
                    });
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Link caregiver
    //
    //  ★ KEY FIX: Removed the line that auto-updated the caregiver's role.
    //    That line was:
    //      db.collection("users").document(code).update("role", "caregiver");
    //    It was meant to fix wrong roles, but if a user accidentally pasted
    //    their OWN uid as the code, it would overwrite their own role to "caregiver"
    //    causing them to be routed to the caregiver dashboard forever.
    //
    //    Role fixing is now done ONLY in RegisterActivity at registration time.
    // ─────────────────────────────────────────────────────────────────────────
    private void linkCaregiver() {
        if (db == null || currentUserId == null) {
            Toast.makeText(this, "Please log in first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (etNewCaregiverCode == null) return;

        String code = etNewCaregiverCode.getText().toString().trim();
        if (code.isEmpty()) {
            etNewCaregiverCode.setError("Enter the caregiver's code");
            etNewCaregiverCode.requestFocus();
            return;
        }

        // ★ Safety check — user must not enter their own UID as the caregiver code
        if (code.equals(currentUserId)) {
            etNewCaregiverCode.setError("This is your own code — enter your CAREGIVER's code");
            Toast.makeText(this,
                    "You entered your own code. Ask your caregiver to share theirs.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        setLinkingProgress(true);

        db.collection("users").document(code)
                .get()
                .addOnSuccessListener(doc -> {
                    if (!doc.exists()) {
                        setLinkingProgress(false);
                        etNewCaregiverCode.setError("Code not found. Check with your caregiver.");
                        Toast.makeText(this,
                                "No account found with this code.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }

                    String caregiverName = doc.getString("name");

                    // ★ Save linkedCaregiverId on the USER's document ONLY
                    // We do NOT touch the role field of any document here
                    db.collection("users").document(currentUserId)
                            .update("linkedCaregiverId", code)
                            .addOnSuccessListener(v -> {
                                setLinkingProgress(false);
                                String name = caregiverName != null ? caregiverName : "caregiver";
                                Toast.makeText(this,
                                        "Successfully linked to " + name + "!",
                                        Toast.LENGTH_LONG).show();
                                etNewCaregiverCode.setText("");
                                // Refresh status display
                                loadCurrentCaregiverStatus(code);
                            })
                            .addOnFailureListener(e -> {
                                setLinkingProgress(false);
                                Toast.makeText(this,
                                        "Failed to save: " + e.getMessage(),
                                        Toast.LENGTH_LONG).show();
                            });
                })
                .addOnFailureListener(e -> {
                    setLinkingProgress(false);
                    Toast.makeText(this, "Network error. Try again.", Toast.LENGTH_SHORT).show();
                });
    }

    private void removeCaregiver() {
        if (db == null || currentUserId == null) return;
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Remove Caregiver")
                .setMessage("Your caregiver will no longer see your activity.")
                .setPositiveButton("Remove", (d, w) ->
                        db.collection("users").document(currentUserId)
                                .update("linkedCaregiverId", "")
                                .addOnSuccessListener(v -> {
                                    Toast.makeText(this, "Caregiver removed",
                                            Toast.LENGTH_SHORT).show();
                                    loadCurrentCaregiverStatus("");
                                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void setLinkingProgress(boolean loading) {
        if (pbLinking != null)
            pbLinking.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (btnLinkCaregiver != null)
            btnLinkCaregiver.setEnabled(!loading);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Theme picker
    // ─────────────────────────────────────────────────────────────────────────
    private void buildThemePicker() {
        if (themeContainer == null) return;
        themeContainer.removeAllViews();

        String[] names   = ThemeManager.getThemeNames();
        String[] keys    = ThemeManager.getThemeKeys();
        String   current = ThemeManager.getSavedTheme(this);

        int[] accents  = {0xFF7C6FFF, 0xFF0EA5E9, 0xFF22C55E, 0xFFF97316, 0xFFEC4899};
        int[] bgColors = {0xFF1A1633, 0xFF0C2545, 0xFF0A2010, 0xFF251508, 0xFF250820};

        for (int i = 0; i < names.length; i++) {
            final String key      = keys[i];
            boolean      selected = key.equals(current);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(android.view.Gravity.CENTER_VERTICAL);
            card.setPadding(dp(16), dp(14), dp(16), dp(14));
            card.setBackgroundColor(selected ? bgColors[i] : 0xFF0A0E1A);

            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            cp.setMargins(0, 0, 0, dp(8));
            card.setLayoutParams(cp);

            View dot = new View(this);
            LinearLayout.LayoutParams dp24 = new LinearLayout.LayoutParams(dp(24), dp(24));
            dp24.setMargins(0, 0, dp(14), 0);
            dot.setLayoutParams(dp24);
            dot.setBackgroundColor(accents[i]);

            TextView nameView = new TextView(this);
            nameView.setText(names[i]);
            nameView.setTextSize(15);
            nameView.setTextColor(0xFFFFFFFF);
            nameView.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView check = new TextView(this);
            check.setText(selected ? "✓" : "");
            check.setTextSize(18);
            check.setTextColor(accents[i]);

            card.addView(dot);
            card.addView(nameView);
            card.addView(check);

            card.setOnClickListener(v -> {
                if (!key.equals(ThemeManager.getSavedTheme(this))) {
                    ThemeManager.saveTheme(this, key);
                    Toast.makeText(this, "Theme applied!", Toast.LENGTH_SHORT).show();
                    Intent intent = new Intent(this, MainActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                }
            });

            themeContainer.addView(card);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Standard settings
    // ─────────────────────────────────────────────────────────────────────────
    private void loadSettings() {
        int   fontSize   = prefs.getInt("font_size", 16);
        float speechRate = prefs.getFloat("speech_rate", 0.8f);
        boolean voiceFb  = prefs.getBoolean("voice_feedback", true);
        boolean hiCont   = prefs.getBoolean("high_contrast",  false);

        if (sbFontSize      != null) sbFontSize.setProgress(fontSize);
        if (sbSpeechRate    != null) sbSpeechRate.setProgress((int)(speechRate * 10));
        if (swVoiceFeedback != null) swVoiceFeedback.setChecked(voiceFb);
        if (swHighContrast  != null) swHighContrast.setChecked(hiCont);
        updateDisplayValues();
    }

    private void setupListeners() {
        if (sbFontSize != null) {
            sbFontSize.setOnSeekBarChangeListener(simple(() -> updateDisplayValues(), this::saveSettings));
        }
        if (sbSpeechRate != null) {
            sbSpeechRate.setOnSeekBarChangeListener(simple(() -> updateDisplayValues(), this::saveSettings));
        }
        if (swVoiceFeedback != null) swVoiceFeedback.setOnCheckedChangeListener((b, c) -> saveSettings());
        if (swHighContrast  != null) swHighContrast.setOnCheckedChangeListener((b, c) -> saveSettings());

        if (btnLinkCaregiver   != null) btnLinkCaregiver.setOnClickListener(v -> linkCaregiver());
        if (btnRemoveCaregiver != null) btnRemoveCaregiver.setOnClickListener(v -> removeCaregiver());
    }

    private SeekBar.OnSeekBarChangeListener simple(Runnable onChange, Runnable onStop) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { onChange.run(); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { onStop.run(); }
        };
    }

    private void updateDisplayValues() {
        if (tvFontValue  != null && sbFontSize  != null)
            tvFontValue.setText(sbFontSize.getProgress() + "sp");
        if (tvSpeechValue != null && sbSpeechRate != null)
            tvSpeechValue.setText(String.format("%.1fx", sbSpeechRate.getProgress() / 10.0f));
    }

    private void saveSettings() {
        if (sbFontSize == null || sbSpeechRate == null) return;
        prefs.edit()
                .putInt("font_size",      sbFontSize.getProgress())
                .putFloat("speech_rate",  sbSpeechRate.getProgress() / 10.0f)
                .putBoolean("voice_feedback",
                        swVoiceFeedback != null && swVoiceFeedback.isChecked())
                .putBoolean("high_contrast",
                        swHighContrast  != null && swHighContrast.isChecked())
                .apply();
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
    }
}