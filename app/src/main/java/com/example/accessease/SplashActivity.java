package com.example.accessease;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import androidx.appcompat.app.AppCompatActivity;

/**
 * SplashActivity — shown for 2.5 seconds when app opens.
 * Then navigates to LoginActivity (or MainActivity if already logged in).
 *
 * Register in AndroidManifest.xml as the LAUNCHER activity.
 * Move the intent-filter from LoginActivity to this one.
 */
public class SplashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Full screen — no status bar during splash
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

        setContentView(R.layout.activity_splash);

        // Fade in the whole layout
        View root = findViewById(android.R.id.content);
        AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
        fadeIn.setDuration(600);
        root.startAnimation(fadeIn);

        // Animate the 3 dots — staggered pulse
        animateDot(R.id.dot1, 0);
        animateDot(R.id.dot2, 200);
        animateDot(R.id.dot3, 400);

        // Navigate after 2.5 seconds
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            // Fade out
            AlphaAnimation fadeOut = new AlphaAnimation(1f, 0f);
            fadeOut.setDuration(400);
            fadeOut.setAnimationListener(new Animation.AnimationListener() {
                @Override public void onAnimationStart(Animation a) {}
                @Override public void onAnimationRepeat(Animation a) {}
                @Override public void onAnimationEnd(Animation a) {
                    goToLogin();
                }
            });
            root.startAnimation(fadeOut);
        }, 2500);
    }

    private void animateDot(int dotId, long delay) {
        View dot = findViewById(dotId);
        if (dot == null) return;

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            AlphaAnimation pulse = new AlphaAnimation(0.2f, 1f);
            pulse.setDuration(500);
            pulse.setRepeatMode(Animation.REVERSE);
            pulse.setRepeatCount(Animation.INFINITE);
            dot.startAnimation(pulse);
        }, delay);
    }

    private void goToLogin() {
        // Always go to LoginActivity.
        // LoginActivity already handles rememberMe check internally —
        // if the user ticked "Remember Me" it routes them to MainActivity automatically.
        // If we skip LoginActivity here, users who logged out can never see the login page.
        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}