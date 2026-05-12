package com.example.accessease;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * ThemeManager — central class for the app-wide color theme system.
 *
 * HOW IT WORKS:
 *   1. User picks a theme in Settings → stored in SharedPreferences.
 *   2. Every Activity calls ThemeManager.applyTheme(this) in onCreate()
 *      BEFORE setContentView() — this sets the correct style.
 *   3. When theme changes, all activities are recreated via recreate().
 *
 * AVAILABLE THEMES:
 *   DARK        — Deep dark (#0A0E1A bg, #7C6FFF purple accent)   ← default
 *   OCEAN       — Dark blue  (#071428 bg, #0EA5E9 cyan accent)
 *   FOREST      — Dark green (#0A1A0E bg, #22C55E green accent)
 *   SUNSET      — Dark warm  (#1A0A08 bg, #F97316 orange accent)
 *   ROSE        — Dark pink  (#1A0A12 bg, #EC4899 pink accent)
 */
public class ThemeManager {

    private static final String PREFS       = "ThemePrefs";
    private static final String KEY_THEME   = "selectedTheme";

    public static final String THEME_DARK   = "DARK";
    public static final String THEME_OCEAN  = "OCEAN";
    public static final String THEME_FOREST = "FOREST";
    public static final String THEME_SUNSET = "SUNSET";
    public static final String THEME_ROSE   = "ROSE";

    // ─────────────────────────────────────────────────────────────────────────
    //  Save & load
    // ─────────────────────────────────────────────────────────────────────────
    public static void saveTheme(Context ctx, String theme) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_THEME, theme).apply();
    }

    public static String getSavedTheme(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_THEME, THEME_DARK);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Apply theme — call this in every Activity.onCreate() BEFORE setContentView()
    // ─────────────────────────────────────────────────────────────────────────
    public static void applyTheme(Activity activity) {
        String theme = getSavedTheme(activity);
        switch (theme) {
            case THEME_OCEAN:  activity.setTheme(R.style.Theme_Ocean);  break;
            case THEME_FOREST: activity.setTheme(R.style.Theme_Forest); break;
            case THEME_SUNSET: activity.setTheme(R.style.Theme_Sunset); break;
            case THEME_ROSE:   activity.setTheme(R.style.Theme_Rose);   break;
            default:           activity.setTheme(R.style.Theme_Dark);   break;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Per-theme color values — used by activities to set dynamic backgrounds
    //  that can't be set in XML (e.g. programmatic views in adapters)
    // ─────────────────────────────────────────────────────────────────────────
    public static int getBgColor(Context ctx) {
        switch (getSavedTheme(ctx)) {
            case THEME_OCEAN:  return 0xFF071428;
            case THEME_FOREST: return 0xFF0A1A0E;
            case THEME_SUNSET: return 0xFF1A0A08;
            case THEME_ROSE:   return 0xFF1A0A12;
            default:           return 0xFF0A0E1A;
        }
    }

    public static int getCardColor(Context ctx) {
        switch (getSavedTheme(ctx)) {
            case THEME_OCEAN:  return 0xFF0D2040;
            case THEME_FOREST: return 0xFF0D2010;
            case THEME_SUNSET: return 0xFF201008;
            case THEME_ROSE:   return 0xFF20081A;
            default:           return 0xFF111827;
        }
    }

    public static int getAccentColor(Context ctx) {
        switch (getSavedTheme(ctx)) {
            case THEME_OCEAN:  return 0xFF0EA5E9;
            case THEME_FOREST: return 0xFF22C55E;
            case THEME_SUNSET: return 0xFFF97316;
            case THEME_ROSE:   return 0xFFEC4899;
            default:           return 0xFF7C6FFF;
        }
    }

    public static int getAccentDarkColor(Context ctx) {
        switch (getSavedTheme(ctx)) {
            case THEME_OCEAN:  return 0xFF0C2545;
            case THEME_FOREST: return 0xFF0A2010;
            case THEME_SUNSET: return 0xFF251508;
            case THEME_ROSE:   return 0xFF250820;
            default:           return 0xFF1A1633;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Theme display names — for the Settings picker UI
    // ─────────────────────────────────────────────────────────────────────────
    public static String[] getThemeNames() {
        return new String[]{"Dark Purple", "Ocean Blue", "Forest Green", "Sunset Orange", "Rose Pink"};
    }

    public static String[] getThemeKeys() {
        return new String[]{THEME_DARK, THEME_OCEAN, THEME_FOREST, THEME_SUNSET, THEME_ROSE};
    }

    public static int getThemeIndex(Context ctx) {
        String saved = getSavedTheme(ctx);
        String[] keys = getThemeKeys();
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(saved)) return i;
        }
        return 0;
    }
}