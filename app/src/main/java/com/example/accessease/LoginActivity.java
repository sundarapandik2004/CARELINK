package com.example.accessease;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

public class LoginActivity extends AppCompatActivity {

    private static final String TAG          = "LoginActivity";
    private static final String PREFS        = "LoginPrefs";
    private static final String KEY_REMEMBER = "rememberMe";

    private EditText     etEmail, etPassword;
    private Button       btnLogin, btnGoRegister;
    private CheckBox     cbRememberMe;
    private ProgressBar  progressBar;
    private FirebaseAuth mAuth;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        try {
            mAuth = FirebaseAuth.getInstance();
            FirebaseUser current = mAuth.getCurrentUser();
            boolean rememberMe = prefs.getBoolean(KEY_REMEMBER, false);

            if (current != null && rememberMe) {
                routeByRole(current.getUid());
                return;
            } else if (current != null) {
                mAuth.signOut();
            }
        } catch (Exception e) {
            Log.e(TAG, "Firebase init error: " + e.getMessage());
        }

        setContentView(R.layout.activity_login);
        bindViews();
    }

    private void bindViews() {
        etEmail       = findViewById(R.id.etEmail);
        etPassword    = findViewById(R.id.etPassword);
        btnLogin      = findViewById(R.id.btnLogin);
        btnGoRegister = findViewById(R.id.btnGoRegister);
        cbRememberMe  = findViewById(R.id.cbRememberMe);
        progressBar   = findViewById(R.id.progressBar);

        if (cbRememberMe != null)
            cbRememberMe.setChecked(prefs.getBoolean(KEY_REMEMBER, false));

        if (btnLogin != null)
            btnLogin.setOnClickListener(v -> doLogin());

        if (btnGoRegister != null)
            btnGoRegister.setOnClickListener(v ->
                    startActivity(new Intent(this, RegisterActivity.class)));
    }

    private void doLogin() {
        String email    = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        if (email.isEmpty())    { etEmail.setError("Enter email");       etEmail.requestFocus();    return; }
        if (password.isEmpty()) { etPassword.setError("Enter password"); etPassword.requestFocus(); return; }

        setLoading(true);

        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    setLoading(false);
                    if (task.isSuccessful()) {
                        prefs.edit()
                                .putBoolean(KEY_REMEMBER,
                                        cbRememberMe != null && cbRememberMe.isChecked())
                                .apply();
                        FirebaseUser user = mAuth.getCurrentUser();
                        if (user != null) routeByRole(user.getUid());
                    } else {
                        String msg = task.getException() != null
                                ? getFriendlyError(task.getException().getMessage())
                                : "Login failed";
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                    }
                });
    }

    /**
     * ★ SELF-HEALING routeByRole
     *
     * The problem: SettingsActivity previously had a bug that set role="caregiver"
     * on the user's own Firestore document. This caused them to be sent to
     * CaregiverDashboardActivity instead of MainActivity on every login.
     *
     * The self-healing fix:
     *   If role == "caregiver", do a second check:
     *     → Query if ANY user document has linkedCaregiverId == this UID
     *     → If YES → real caregiver → go to CaregiverDashboardActivity
     *     → If NO  → corrupted role → auto-fix it to "user" → go to MainActivity
     *
     * This means the app repairs itself automatically without the user
     * needing to go to Firebase Console.
     */
    private void routeByRole(String uid) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();

        db.collection("users").document(uid)
                .get()
                .addOnSuccessListener(doc -> {
                    String role = doc.exists() ? doc.getString("role") : null;
                    Log.d(TAG, "uid=" + uid + " role=" + role);

                    if ("caregiver".equals(role)) {
                        // ★ SELF-HEALING: verify this UID is actually linked to by a user
                        // A real caregiver will have at least one user pointing to them
                        verifyRealCaregiver(uid, db);
                    } else {
                        // role is "user", null, or anything else → go to user home
                        goToMain();
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "routeByRole failed: " + e.getMessage());
                    // Firestore unreachable → default to user home, never caregiver
                    goToMain();
                });
    }

    /**
     * Checks if any user document has linkedCaregiverId == this UID.
     * If none found → role was corrupted → auto-fix it and go to MainActivity.
     * If found → real caregiver → go to CaregiverDashboardActivity.
     */
    private void verifyRealCaregiver(String uid, FirebaseFirestore db) {
        db.collection("users")
                .whereEqualTo("linkedCaregiverId", uid)
                .get()
                .addOnSuccessListener(query -> {
                    if (!query.isEmpty()) {
                        // At least one user is linked to this UID → real caregiver
                        Log.d(TAG, "Verified real caregiver — " + query.size() + " linked user(s)");
                        goToCaregiver();
                    } else {
                        // No users linked → role was corrupted → auto-fix
                        Log.w(TAG, "role=caregiver but no linked users found. Auto-fixing role to user.");
                        db.collection("users").document(uid)
                                .update("role", "user")
                                .addOnSuccessListener(v -> {
                                    Log.d(TAG, "Role auto-fixed to 'user'");
                                    Toast.makeText(this,
                                            "Account role corrected. Welcome!",
                                            Toast.LENGTH_SHORT).show();
                                    goToMain();
                                })
                                .addOnFailureListener(e -> {
                                    // Couldn't fix the role but still send to user home
                                    Log.e(TAG, "Role fix failed: " + e.getMessage());
                                    goToMain();
                                });
                    }
                })
                .addOnFailureListener(e -> {
                    // Query failed → safe default is user home
                    Log.e(TAG, "verifyRealCaregiver query failed: " + e.getMessage());
                    goToMain();
                });
    }

    private void goToMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void goToCaregiver() {
        Intent intent = new Intent(this, CaregiverDashboardActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void setLoading(boolean loading) {
        if (progressBar != null)
            progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (btnLogin != null)
            btnLogin.setEnabled(!loading);
    }

    private String getFriendlyError(String raw) {
        if (raw == null) return "Login failed";
        if (raw.contains("no user record") || raw.contains("USER_NOT_FOUND"))
            return "No account found. Please register first.";
        if (raw.contains("password is invalid") || raw.contains("INVALID_PASSWORD")
                || raw.contains("INVALID_LOGIN_CREDENTIALS"))
            return "Incorrect email or password.";
        if (raw.contains("badly formatted"))
            return "Please enter a valid email address.";
        if (raw.contains("TOO_MANY_REQUESTS") || raw.contains("blocked"))
            return "Too many attempts. Wait a few minutes.";
        if (raw.contains("CONFIGURATION_NOT_FOUND") || raw.contains("configuration"))
            return "Firebase not configured. Add google-services.json.";
        if (raw.contains("network") || raw.contains("NETWORK"))
            return "No internet connection.";
        return raw;
    }
}