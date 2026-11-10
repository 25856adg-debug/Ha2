package com.example.screenwake;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

public final class ConfigManager {

    public static final String MODE_WEB  = "web";
    public static final String MODE_FILE = "file";

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
    private static final String KEY_DETECT_MODE    = "detect_mode";
    private static final String KEY_LOCAL_FILE_URI = "local_file_uri";
    private static final String KEY_PAUSED         = "paused";
    private static final String KEY_LAST_TRIGGER   = "last_trigger";

    private ConfigManager() {}

    private static SharedPreferences pref(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    // ================= 旧版自建服务器 =================
    public static String getServerUrl(Context ctx) {
        return pref(ctx).getString(KEY_SERVER_URL, "");
    }

    public static void setServerUrl(Context ctx, String url) {
        pref(ctx).edit().putString(KEY_SERVER_URL, url == null ? "" : url).apply();
    }

    public static String getToken(Context ctx) {
        return pref(ctx).getString(KEY_TOKEN, "");
    }

    public static void setToken(Context ctx, String token) {
        pref(ctx).edit().putString(KEY_TOKEN, token == null ? "" : token).apply();
    }

    public static String getDeviceToken(Context ctx) { return getToken(ctx); }
    public static void setDeviceToken(Context ctx, String token) { setToken(ctx, token); }

    // ================= 设备ID =================
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

    // ================= 定时器（AlarmScheduler） =================
    public static boolean isEnabled(Context ctx) {
        return pref(ctx).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        pref(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static void enable(Context ctx) { setEnabled(ctx, true); }
    public static void disable(Context ctx) { setEnabled(ctx, false); }

    public static long getIntervalMinutes(Context ctx) {
        return pref(ctx).getLong(KEY_INTERVAL_MIN, 60L);
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

    // ================= 暂停开关（CommandExecutor / BootReceiver / TaskReceiver 使用） =================
    public static boolean isPaused(Context ctx) {
        return pref(ctx).getBoolean(KEY_PAUSED, false);
    }

    public static void setPaused(Context ctx, boolean paused) {
        pref(ctx).edit().putBoolean(KEY_PAUSED, paused).apply();
    }

    // ================= 上次触发时间（TaskReceiver 使用） =================
    public static long getLastTrigger(Context ctx) {
        return pref(ctx).getLong(KEY_LAST_TRIGGER, 0L);
    }

    public static void setLastTrigger(Context ctx, long ts) {
        pref(ctx).edit().putLong(KEY_LAST_TRIGGER, ts).apply();
    }

    // ================= 监控网页 =================
    public static String getMonitorUrl(Context ctx) {
        return pref(ctx).getString(KEY_MONITOR_URL, "");
    }

    public static void setMonitorUrl(Context ctx, String url) {
        pref(ctx).edit().putString(KEY_MONITOR_URL, url == null ? "" : url).apply();
    }

    // ================= 飞书Webhook =================
    public static String getFeishuWebhook(Context ctx) {
        return pref(ctx).getString(KEY_FEISHU_HOOK, "");
    }

    public static void setFeishuWebhook(Context ctx, String hook) {
        pref(ctx).edit().putString(KEY_FEISHU_HOOK, hook == null ? "" : hook).apply();
    }

    // ================= 休眠时长 =================
    public static int getSleepMinutes(Context ctx) {
        return pref(ctx).getInt(KEY_SLEEP_MINUTES, 60);
    }

    public static void setSleepMinutes(Context ctx, int minutes) {
        if (minutes < 1) minutes = 1;
        if (minutes > 1440) minutes = 1440;
        pref(ctx).edit().putInt(KEY_SLEEP_MINUTES, minutes).apply();
    }

    // ================= 检测模式：web / file =================
    public static String getDetectMode(Context ctx) {
        String m = pref(ctx).getString(KEY_DETECT_MODE, MODE_WEB);
        if (m == null || m.trim().isEmpty()) m = MODE_WEB;
        return m;
    }

    public static void setDetectMode(Context ctx, String mode) {
        if (!MODE_FILE.equals(mode)) mode = MODE_WEB;
        pref(ctx).edit().putString(KEY_DETECT_MODE, mode).apply();
    }

    // ================= 本地文本文件URI（SAF持久化） =================
    public static String getLocalFileUri(Context ctx) {
        return pref(ctx).getString(KEY_LOCAL_FILE_URI, "");
    }

    public static void setLocalFileUri(Context ctx, String uri) {
        pref(ctx).edit().putString(KEY_LOCAL_FILE_URI, uri == null ? "" : uri).apply();
    }
}

