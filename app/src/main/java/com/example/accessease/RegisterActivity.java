package com.example.accessease;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

public class RegisterActivity extends AppCompatActivity {

    private static final String TAG = "RegisterActivity";

    private EditText    etEmail, etPassword, etName, etCaregiverCode;
    private RadioGroup  rgRole;
    private RadioButton rbUser, rbCaregiver;
    private TextView    tvCaregiverCodeLabel, tvCaregiverHint, tvRoleDescription;
    private Button      btnRegister, btnGoLogin;
    private ProgressBar progressBar;

    private FirebaseAuth      mAuth;
    private FirebaseFirestore db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        mAuth = FirebaseAuth.getInstance();
        db    = FirebaseFirestore.getInstance();

        etName               = findViewById(R.id.etName);
        etEmail              = findViewById(R.id.etEmail);
        etPassword           = findViewById(R.id.etPassword);
        rgRole               = findViewById(R.id.rgRole);
        rbUser               = findViewById(R.id.rbUser);
        rbCaregiver          = findViewById(R.id.rbCaregiver);
        etCaregiverCode      = findViewById(R.id.etCaregiverCode);
        tvCaregiverCodeLabel = findViewById(R.id.tvCaregiverCodeLabel);
        tvCaregiverHint      = findViewById(R.id.tvCaregiverHint);
        tvRoleDescription    = findViewById(R.id.tvRoleDescription);
        btnRegister          = findViewById(R.id.btnRegister);
        btnGoLogin           = findViewById(R.id.btnGoLogin);
        progressBar          = findViewById(R.id.progressBar);

        // Set initial state — User is selected by default
        updateRoleUI(false);

        // ★ FIX: RadioButtons are now direct children of RadioGroup
        // so this listener works correctly — switching one deselects the other
        rgRole.setOnCheckedChangeListener((group, checkedId) ->
                updateRoleUI(checkedId == R.id.rbCaregiver));

        btnRegister.setOnClickListener(v -> doRegister());

        if (btnGoLogin != null) {
            btnGoLogin.setOnClickListener(v -> {
                startActivity(new Intent(this, LoginActivity.class));
                finish();
            });
        }
    }

    /**
     * ★ FIXED: Updates description text and shows/hides caregiver code field
     * based on selected role.
     *
     * This now also updates the role description card below the radio buttons
     * so the user clearly understands what each role means.
     */
    private void updateRoleUI(boolean isCaregiver) {
        if (isCaregiver) {
            // Caregiver selected
            if (tvRoleDescription != null)
                tvRoleDescription.setText(
                        "Family member or nurse monitoring the user remotely via the dashboard");
            if (tvCaregiverCodeLabel != null) tvCaregiverCodeLabel.setVisibility(View.GONE);
            if (etCaregiverCode      != null) { etCaregiverCode.setVisibility(View.GONE); etCaregiverCode.setText(""); }
            if (tvCaregiverHint      != null) tvCaregiverHint.setVisibility(View.GONE);
        } else {
            // User selected
            if (tvRoleDescription != null)
                tvRoleDescription.setText(
                        "Person using the app for reminders, communication and SOS");
            if (tvCaregiverCodeLabel != null) tvCaregiverCodeLabel.setVisibility(View.VISIBLE);
            if (etCaregiverCode      != null) etCaregiverCode.setVisibility(View.VISIBLE);
            if (tvCaregiverHint      != null) tvCaregiverHint.setVisibility(View.VISIBLE);
        }
    }

    private void doRegister() {
        String name         = etName.getText().toString().trim();
        String email        = etEmail.getText().toString().trim();
        String password     = etPassword.getText().toString().trim();
        boolean isCaregiver = rbCaregiver.isChecked();
        String role         = isCaregiver ? "caregiver" : "user";
        String linkedId     = (etCaregiverCode != null)
                ? etCaregiverCode.getText().toString().trim() : "";

        if (name.isEmpty())    { etName.setError("Enter your name");    etName.requestFocus();    return; }
        if (email.isEmpty())   { etEmail.setError("Enter your email");  etEmail.requestFocus();   return; }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            etEmail.setError("Enter a valid email address"); etEmail.requestFocus(); return;
        }
        if (password.length() < 6) {
            etPassword.setError("Minimum 6 characters"); etPassword.requestFocus(); return;
        }

        setLoading(true);
        Log.d(TAG, "Registering: " + email + " as " + role);

        mAuth.createUserWithEmailAndPassword(email, password)
                .addOnSuccessListener(authResult -> {
                    FirebaseUser firebaseUser = authResult.getUser();
                    if (firebaseUser == null) {
                        setLoading(false);
                        showError("Unexpected error. Please try again.");
                        return;
                    }
                    saveProfileToFirestore(
                            firebaseUser, name, email, role, linkedId, isCaregiver);
                })
                .addOnFailureListener(e -> {
                    setLoading(false);
                    Log.e(TAG, "Auth failed: " + e.getMessage());
                    showError(getFriendlyAuthError(e.getMessage()));
                });
    }

    private void saveProfileToFirestore(FirebaseUser firebaseUser,
                                        String name, String email,
                                        String role, String linkedId,
                                        boolean isCaregiver) {
        Map<String, Object> userData = new HashMap<>();
        userData.put("uid",       firebaseUser.getUid());
        userData.put("name",      name);
        userData.put("email",     email);
        userData.put("role",      role);
        userData.put("createdAt", System.currentTimeMillis());

        if (!isCaregiver && !linkedId.isEmpty()) {
            userData.put("linkedCaregiverId", linkedId);
            Log.d(TAG, "Linking to caregiver: " + linkedId);
        }

        db.collection("users")
                .document(firebaseUser.getUid())
                .set(userData)
                .addOnSuccessListener(unused -> {
                    setLoading(false);
                    Log.d(TAG, "Firestore saved successfully");
                    Toast.makeText(this,
                            "Account created! Welcome, " + name,
                            Toast.LENGTH_SHORT).show();
                    navigateToHome(isCaregiver);
                })
                .addOnFailureListener(e -> {
                    setLoading(false);
                    Log.e(TAG, "Firestore FAILED: " + e.getMessage());
                    Toast.makeText(this,
                            "Profile save failed. Fix: Firebase → Firestore → Rules → " +
                                    "allow read, write: if request.auth != null;",
                            Toast.LENGTH_LONG).show();
                    navigateToHome(isCaregiver);
                });
    }

    private void navigateToHome(boolean isCaregiver) {
        Intent dest = isCaregiver
                ? new Intent(this, CaregiverDashboardActivity.class)
                : new Intent(this, MainActivity.class);
        dest.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(dest);
        finish();
    }

    private void setLoading(boolean loading) {
        if (progressBar != null) progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (btnRegister != null) btnRegister.setEnabled(!loading);
        if (etName      != null) etName.setEnabled(!loading);
        if (etEmail     != null) etEmail.setEnabled(!loading);
        if (etPassword  != null) etPassword.setEnabled(!loading);
    }

    private void showError(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private String getFriendlyAuthError(String raw) {
        if (raw == null) return "Registration failed. Try again.";
        if (raw.contains("email address is already in use"))
            return "This email is already registered. Please log in instead.";
        if (raw.contains("badly formatted") || raw.contains("INVALID_EMAIL"))
            return "Please enter a valid email address.";
        if (raw.contains("weak-password") || raw.contains("WEAK_PASSWORD"))
            return "Password is too weak. Use at least 6 characters.";
        if (raw.contains("CONFIGURATION_NOT_FOUND"))
            return "Firebase not configured. Add google-services.json to the app folder.";
        if (raw.contains("network") || raw.contains("NETWORK"))
            return "No internet connection. Check your network and try again.";
        return raw;
    }
}