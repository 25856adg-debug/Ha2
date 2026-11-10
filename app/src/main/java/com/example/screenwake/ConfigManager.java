package com.example.screenwake;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

public final class ConfigManager {

    private static final String PREF_NAME = "ScreenWakeConfig";

    private static final String KEY_SERVER_URL     = "server_url";
    private static final String KEY_TOKEN          = "token";
    private static final String KEY_DEVICE_ID      = "device_id";
    private static final String KEY_ENABLED        = "alarm_enabled";
    private static final String KEY_INTERVAL_MIN   = "interval_minutes";
    private static final String KEY_NEXT_TRIGGER   = "next_trigger";
    private static final String KEY_MONITOR_URL    = "monitor_url";
    private static final String KEY_FEISHU_HOOK    = "feishu_hook";
    private static final String KEY_SLEEP_MINUTES  = "sleep_minutes";

    private ConfigManager() {}

    private static SharedPreferences pref(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    // ================= 服务器地址（旧版自建服务器用，保留） =================
    public static String getServerUrl(Context ctx) {
        return pref(ctx).getString(KEY_SERVER_URL, "");
    }

    public static void setServerUrl(Context ctx, String url) {
        pref(ctx).edit().putString(KEY_SERVER_URL, url == null ? "" : url).apply();
    }

    // ================= 设备Token =================
    public static String getToken(Context ctx) {
        return pref(ctx).getString(KEY_TOKEN, "");
    }

    public static void setToken(Context ctx, String token) {
        pref(ctx).edit().putString(KEY_TOKEN, token == null ? "" : token).apply();
    }

    // 别名，给 DeviceUploader 旧调用兼容
    public static String getDeviceToken(Context ctx) { return getToken(ctx); }
    public static void setDeviceToken(Context ctx, String token) { setToken(ctx, token); }

    // ================= 设备唯一ID（首次自动生成并持久化） =================
    public static String getDeviceId(Context ctx) {
        SharedPreferences p = pref(ctx);
        String id = p.getString(KEY_DEVICE_ID, "");
        if (id == null || id.trim().isEmpty()) {
            id = "dev-" + UUID.randomUUID().toString().replace("-", "");
            if (id.length() > 24) id = id.substring(0, 24);
            p.edit().putString(KEY_DEVICE_ID, id).apply();
        }
        return id;
    }

    public static void setDeviceId(Context ctx, String id) {
        pref(ctx).edit().putString(KEY_DEVICE_ID, id == null ? "" : id).apply();
    }

    // ================= 定时器开关 / 周期 / 下次触发（AlarmScheduler 用） =================
    public static boolean isEnabled(Context ctx) {
        return pref(ctx).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        pref(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static void enable(Context ctx) { setEnabled(ctx, true); }
    public static void disable(Context ctx) { setEnabled(ctx, false); }

    public static long getIntervalMinutes(Context ctx) {
        return pref(ctx).getLong(KEY_INTERVAL_MIN, 60L);   // 默认60分钟
    }

    public static void setIntervalMinutes(Context ctx, long minutes) {
        pref(ctx).edit().putLong(KEY_INTERVAL_MIN, minutes).apply();
    }

    public static long getNextTrigger(Context ctx) {
        return pref(ctx).getLong(KEY_NEXT_TRIGGER, 0L);
    }

    public static void setNextTrigger(Context ctx, long ts) {
        pref(ctx).edit().putLong(KEY_NEXT_TRIGGER, ts).apply();
    }

    // ================= 监控网页地址 =================
    public static String getMonitorUrl(Context ctx) {
        return pref(ctx).getString(KEY_MONITOR_URL, "");
    }

    public static void setMonitorUrl(Context ctx, String url) {
        pref(ctx).edit().putString(KEY_MONITOR_URL, url == null ? "" : url).apply();
    }

    // ================= 飞书 Webhook =================
    public static String getFeishuWebhook(Context ctx) {
        return pref(ctx).getString(KEY_FEISHU_HOOK, "");
    }

    public static void setFeishuWebhook(Context ctx, String hook) {
        pref(ctx).edit().putString(KEY_FEISHU_HOOK, hook == null ? "" : hook).apply();
    }

    // ================= 休眠时长（分钟，1~1440） =================
    public static int getSleepMinutes(Context ctx) {
        return pref(ctx).getInt(KEY_SLEEP_MINUTES, 60);   // 默认60分钟
    }

    public static void setSleepMinutes(Context ctx, int minutes) {
        if (minutes < 1) minutes = 1;
        if (minutes > 1440) minutes = 1440;
        pref(ctx).edit().putInt(KEY_SLEEP_MINUTES, minutes).apply();
    }
}
