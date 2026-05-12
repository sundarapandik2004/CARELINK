package com.example.accessease;

import android.app.Application;
import com.cloudinary.android.MediaManager;
import java.util.HashMap;
import java.util.Map;

public class MyApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        initCloudinary();
    }

    private void initCloudinary() {
        try {
            // Guard: Cloudinary throws if init() is called more than once
            try { MediaManager.get(); return; } catch (IllegalStateException ignored) {}

            Map<String, String> config = new HashMap<>();
            config.put("cloud_name", "drzacgnfl");  // ← replace
            config.put("api_key",    "813749947439217");      // ← replace
            config.put("api_secret", "yREp_Zbsl-rQdHSzC5s2D8yvZYA");  // ← replace
            MediaManager.init(this, config);
        } catch (Exception e) {
            android.util.Log.e("MyApplication",
                    "Cloudinary init failed: " + e.getMessage());
        }
    }
}