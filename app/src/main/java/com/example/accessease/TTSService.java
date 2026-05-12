package com.example.accessease;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.Locale;

public class TTSService extends Service {

    private static final String TAG = "TTSService";
    private static final String CHANNEL_ID = "tts_service_channel";
    private static final int NOTIFICATION_ID = 9999;

    private TextToSpeech tts;
    private boolean isTTSReady = false;
    private String pendingMessage = null;   // ← hold message until TTS is ready

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "TTSService created");
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification("Ready to speak reminders"));
        initializeTTS();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String message = intent.getStringExtra("message");
            if (message != null) {
                if (isTTSReady) {
                    speakMessage(message);
                } else {
                    // Store it; will be spoken once TTS init callback fires
                    pendingMessage = message;
                    Log.d(TAG, "TTS not ready yet, queued: " + message);
                }
            }
        }
        return START_NOT_STICKY;
    }

    private void initializeTTS() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(Locale.US);
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                        result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "TTS Language not supported");
                    stopSelf();
                    return;
                }

                tts.setSpeechRate(0.85f);
                tts.setPitch(1.0f);
                isTTSReady = true;
                Log.d(TAG, "TTS initialized successfully");

                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) {
                        Log.d(TAG, "TTS speaking started");
                    }
                    @Override public void onDone(String id) {
                        Log.d(TAG, "TTS speaking done");
                        stopSelf();
                    }
                    @Override public void onError(String id) {
                        Log.e(TAG, "TTS error for: " + id);
                        stopSelf();
                    }
                });

                // Speak any message that arrived before TTS was ready
                if (pendingMessage != null) {
                    speakMessage(pendingMessage);
                    pendingMessage = null;
                }
            } else {
                Log.e(TAG, "TTS initialization failed, status=" + status);
                stopSelf();
            }
        });
    }

    private void speakMessage(final String message) {
        if (tts == null || !isTTSReady) {
            Log.e(TAG, "speakMessage called before TTS ready");
            return;
        }

        // Boost volume if too low
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (cur < max / 3) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max * 2 / 3, 0);
            }
        }

        // Use QUEUE_FLUSH so any previous speech is cleared
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "reminder_tts");
        } else {
            //noinspection deprecation
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null);
        }

        Log.d(TAG, "Speaking: " + message);
        updateNotification("🔊 " + message);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "TTS Service", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Voice announcement service");
            NotificationManager mgr = getSystemService(NotificationManager.class);
            if (mgr != null) mgr.createNotificationChannel(channel);
        }
    }

    private Notification createNotification(String msg) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("🔊 Reminder Voice")
                .setContentText(msg)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String msg) {
        NotificationManager mgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (mgr != null) mgr.notify(NOTIFICATION_ID, createNotification(msg));
    }

    @Override
    public void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
        Log.d(TAG, "TTSService destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}