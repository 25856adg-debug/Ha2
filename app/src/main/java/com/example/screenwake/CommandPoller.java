package com.example.screenwake;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class CommandPoller {

    private static final String TAG = "CommandPoller";
    private static final long POLL_SECONDS = 10L;

    private static volatile boolean RUNNING = false;
    private static ScheduledExecutorService executor;
    private static ScheduledFuture<?> future;

    private CommandPoller() {}

    // ================= 服务器地址校验（http/https 都允许） =================
    private static boolean isServerValid(String server) {
        if (server == null) return false;
        String s = server.trim().toLowerCase();
        return s.startsWith("http://") || s.startsWith("https://");
    }

    // 常驻轮询的占位期限（仅为兼容旧配置键，不再有过期判断）
    private static long persistDeadline() {
        return System.currentTimeMillis() + 3650L * 24L * 60L * 60L * 1000L;
    }

    // ================= 上报成功后调用：确保轮询在跑 =================
    public static synchronized void startWindow(Context context) {
        start(context);
    }

    // ================= 正式入口：常驻轮询 =================
    public static synchronized void start(Context context) {
        Context app = context.getApplicationContext();

        if (RUNNING) {
            return;
        }

        String server = ConfigManager.getServerUrl(app);
        String token = ConfigManager.getDeviceToken(app);

        if (!isServerValid(server) || token == null || token.trim().isEmpty()) {
            LogManager.log(app, "指令轮询未启动：服务器地址或Token无效");
            return;
        }

        ConfigManager.setCommandWindowEnd(app, persistDeadline());

        RUNNING = true;
        executor = Executors.newSingleThreadScheduledExecutor();
        future = executor.scheduleWithFixedDelay(
                () -> pollOnce(app),
                0,
                POLL_SECONDS,
                TimeUnit.SECONDS
        );

        LogManager.log(app, "指令轮询已启动，常驻运行，每 " + POLL_SECONDS + " 秒一次");
    }

    // ================= 进程或前台服务重建时恢复 =================
    public static synchronized void restoreWindow(Context context) {
        start(context);
    }

    // 前后台切换时调用，已在跑就什么都不做
    public static synchronized void ensureRunning(Context context) {
        start(context);
    }

    public static boolean isRunning() {
        return RUNNING;
    }

    // 只停线程，不改配置
    public static synchronized void stopPolling() {
        stopInternal();
    }

    // ================= 主动停止 =================
    public static synchronized void stopWindow(Context context) {
        Context app = context.getApplicationContext();
        stopInternal();
        ConfigManager.clearCommandWindow(app);
        LogManager.log(app, "指令轮询已手动停止");
    }

    private static synchronized void stopInternal() {
        RUNNING = false;
        if (future != null) {
            future.cancel(true);
            future = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    // ================= 单次轮询 =================
    private static void pollOnce(Context context) {
        if (!RUNNING) {
            return;
        }

        String server = ConfigManager.getServerUrl(context);
        String token = ConfigManager.getDeviceToken(context);
        String deviceId = ConfigManager.getDeviceId(context);

        if (!isServerValid(server) || token == null || token.trim().isEmpty()) {
            LogManager.log(context, "轮询停止：服务器配置无效");
            stopInternal();
            return;
        }

        HttpURLConnection conn = null;

        try {
            String base = server.trim().replaceAll("/+$", "");
            URL url = new URL(base + "/api/device/commands/poll");

            JSONObject body = new JSONObject();
            body.put("device_id", deviceId);
            body.put("timestamp", System.currentTimeMillis());

            // ---------- AES 加密 ----------
            String plain = body.toString();
            String[] aes = AesUtil.encrypt(plain);
            String encBody = aes[0];
            String ivB64 = aes[1];
            // -----------------------------

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("X-Device-Token", token);
            conn.setRequestProperty("X-Device-Id", deviceId);
            conn.setRequestProperty("X-Aes-Iv", ivB64);

            byte[] data = encBody.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(data.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(data);
                os.flush();
            }

            int code = conn.getResponseCode();
            InputStream input = code >= 200 && code < 300
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            String response = readResponse(input);

            if (code >= 200 && code < 300) {
                handleCommands(context, response);
            } else {
                LogManager.log(context, "指令轮询HTTP失败：" + code + " " + response);
            }

        } catch (Exception e) {
            LogManager.log(context, "指令轮询异常：" + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ================= 处理指令 =================
    private static void handleCommands(Context context, String response) {
        try {
            JSONObject root = new JSONObject(response);
            JSONArray commands = root.optJSONArray("commands");

            if (commands == null || commands.length() == 0) {
                return;
            }

            SharedPreferences prefs = context.getSharedPreferences(
                    "screenwake_commands", Context.MODE_PRIVATE);

            for (int i = 0; i < commands.length(); i++) {
                if (!RUNNING) {
                    break;
                }

                JSONObject command = commands.optJSONObject(i);
                if (command == null) {
                    continue;
                }

                String commandId = command.optString("id", "").trim();
                if (commandId.isEmpty()) {
                    continue;
                }

                String key = "cmd_" + commandId;
                if (prefs.getBoolean(key, false)) {
                    continue;
                }

                // 先标记，防止重复执行同一条指令
                prefs.edit().putBoolean(key, true).apply();

                LogManager.log(context, "收到服务器指令：" + commandId
                        + "，动作：" + command.optString("action", ""));

                CommandExecutor.execute(
                        context,
                        command,
                        (id, success, message) -> sendCommandResult(
                                context, id, success, message)
                );
            }

        } catch (Exception e) {
            LogManager.log(context, "解析服务器指令失败：" + e.getMessage());
        }
    }

    // ================= 回传结果 =================
    private static void sendCommandResult(
            Context context,
            String commandId,
            boolean success,
            String message) {

        HttpURLConnection conn = null;

        try {
            String server = ConfigManager.getServerUrl(context);
            String token = ConfigManager.getDeviceToken(context);
            String deviceId = ConfigManager.getDeviceId(context);

            if (!isServerValid(server) || token == null || token.trim().isEmpty()) {
                return;
            }

            String base = server.trim().replaceAll("/+$", "");
            URL url = new URL(base + "/api/device/commands/result");

            JSONObject body = new JSONObject();
            body.put("device_id", deviceId);
            body.put("command_id", commandId);
            body.put("success", success);
            body.put("message", message == null ? "" : message);
            body.put("timestamp", System.currentTimeMillis());

            // ---------- AES 加密 ----------
            String plain = body.toString();
            String[] aes = AesUtil.encrypt(plain);
            String encBody = aes[0];
            String ivB64 = aes[1];
            // -----------------------------

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("X-Device-Token", token);
            conn.setRequestProperty("X-Device-Id", deviceId);
            conn.setRequestProperty("X-Aes-Iv", ivB64);

            byte[] data = encBody.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(data.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(data);
                os.flush();
            }

            int code = conn.getResponseCode();
            LogManager.log(context, "指令结果回传：" + commandId + "，HTTP " + code);

        } catch (Exception e) {
            LogManager.log(context, "指令结果回传失败：" + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readResponse(InputStream input) throws Exception {
        if (input == null) {
            return "";
        }

        StringBuilder result = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        }

        return result.toString();
    }
}



