package com.example.screenwake;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

public final class ConfigManager {

    private static final String PREF = "screenwake_config";

    private static SharedPreferences prefs;

    private ConfigManager() {}

    private static synchronized SharedPreferences get(Context context) {

        if (prefs == null) {
            prefs = context.getApplicationContext()
                    .getSharedPreferences(PREF, Context.MODE_PRIVATE);
        }

        return prefs;
    }

    public static String getServerUrl(Context c) {
        return get(c).getString("server_url", "");
    }

    public static void setServerUrl(Context c, String url) {
        get(c).edit()
                .putString("server_url", url == null ? "" : url.trim())
                .apply();
    }

    public static String getToken(Context c) {
        return get(c).getString("device_token", "");
    }

    public static void setToken(Context c, String token) {
        get(c).edit()
                .putString("device_token", token == null ? "" : token.trim())
                .apply();
    }

    public static synchronized String getDeviceId(Context c) {

        SharedPreferences p = get(c);

        String id = p.getString("device_id", null);

        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
            p.edit().putString("device_id", id).apply();
        }

        return id;
    }

    public static long getIntervalMinutes(Context c) {
        return get(c).getLong("interval_minutes", 540L);
    }

    public static void setIntervalMinutes(Context c, long minutes) {

        minutes = Math.max(1L, Math.min(minutes, 43200L));

        get(c).edit()
                .putLong("interval_minutes", minutes)
                .apply();
    }

    public static void setInterval(Context c, int hours, int minutes) {
        setIntervalMinutes(c, (long) hours * 60L + minutes);
    }

    public static boolean isEnabled(Context c) {
        return get(c).getBoolean("timer_enabled", false);
    }

    public static void setEnabled(Context c, boolean enabled) {
        get(c).edit().putBoolean("timer_enabled", enabled).apply();
    }

    public static boolean isPaused(Context c) {
        return get(c).getBoolean("timer_paused", false);
    }

    public static void setPaused(Context c, boolean paused) {
        get(c).edit().putBoolean("timer_paused", paused).apply();
    }

    public static long getNextTrigger(Context c) {
        return get(c).getLong("next_trigger", 0L);
    }

    public static void setNextTrigger(Context c, long timeMillis) {
        get(c).edit().putLong("next_trigger", timeMillis).apply();
    }

    public static long getLastTrigger(Context c) {
        return get(c).getLong("last_trigger", 0L);
    }

    public static void setLastTrigger(Context c, long timeMillis) {
        get(c).edit().putLong("last_trigger", timeMillis).apply();
    }

    // ================= 指令窗口持久化 =================

    public static long getCommandWindowEnd(Context c) {
        return get(c).getLong("command_window_end", 0L);
    }

    public static void setCommandWindowEnd(Context c, long timeMillis) {
        get(c).edit()
                .putLong("command_window_end", timeMillis)
                .apply();
    }

    public static boolean isCommandWindowActive(Context c) {
        return getCommandWindowEnd(c) > System.currentTimeMillis();
    }

    public static void clearCommandWindow(Context c) {
        get(c).edit()
                .putLong("command_window_end", 0L)
                .apply();
    }
}
