package com.example.screenwake;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

public final class ConfigManager {

    private static final String PREF =
            "screenwake_config";

    private static SharedPreferences prefs;

    private ConfigManager() {}

    private static synchronized SharedPreferences get(Context context) {
        if (prefs == null) {
            prefs = context.getApplicationContext()
                    .getSharedPreferences(PREF, Context.MODE_PRIVATE);
        }
        return prefs;
    }

    // ================= 服务器配置 =================

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
                .putString("device_token",
                        token == null ? "" : token.trim())
                .apply();
    }

    // ================= 设备编号 =================

    public static synchronized String getDeviceId(Context c) {
        SharedPreferences p = get(c);

        String id = p.getString("device_id", null);

        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();

            p.edit()
                    .putString("device_id", id)
                    .apply();
        }

        return id;
    }

    // ================= 定时周期 =================

    // 默认 9 小时
    public static long getIntervalMinutes(Context c) {
        return get(c).getLong("interval_minutes", 540L);
    }

    public static void setIntervalMinutes(Context c, long minutes) {
        // 最小 1 分钟，最大 30 天
        minutes = Math.max(1L, Math.min(minutes, 43200L));

        get(c).edit()
                .putLong("interval_minutes", minutes)
                .apply();
    }

    public static void setInterval(Context c, int hours, int minutes) {
        long total = (long) hours * 60L + minutes;
        setIntervalMinutes(c, total);
    }

    // ================= 定时器状态 =================

    public static boolean isEnabled(Context c) {
        return get(c).getBoolean("timer_enabled", false);
    }

    public static void setEnabled(Context c, boolean enabled) {
        get(c).edit()
                .putBoolean("timer_enabled", enabled)
                .apply();
    }

    public static boolean isPaused(Context c) {
        return get(c).getBoolean("timer_paused", false);
    }

    public static void setPaused(Context c, boolean paused) {
        get(c).edit()
                .putBoolean("timer_paused", paused)
                .apply();
    }

    // ================= 下次触发时间 =================

    public static long getNextTrigger(Context c) {
        return get(c).getLong("next_trigger", 0L);
    }

    public static void setNextTrigger(Context c, long timeMillis) {
        get(c).edit()
                .putLong("next_trigger", timeMillis)
                .apply();
    }

    // ================= 最后一次执行时间 =================

    public static long getLastTrigger(Context c) {
        return get(c).getLong("last_trigger", 0L);
    }

    public static void setLastTrigger(Context c, long timeMillis) {
        get(c).edit()
                .putLong("last_trigger", timeMillis)
                .apply();
    }
}
