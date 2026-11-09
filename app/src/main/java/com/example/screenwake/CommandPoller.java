package com.example.screenwake;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

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
    private static final long WINDOW_MS = 60L * 60L * 1000L;
    private static final long POLL_SECONDS = 10L;

    private static volatile boolean RUNNING = false;
    private static volatile long endTime = 0L;

    private static ScheduledExecutorService executor;
    private static ScheduledFuture<?> future;

    private CommandPoller() {}

    // 上传完成后，开启一小时指令接收窗口
    public static synchronized void startWindow(Context context) {
        Context app = context.getApplicationContext();

        stopWindowInternal();

        String server = ConfigManager.getServerUrl(app);
        String token = ConfigManager.getDeviceToken(app);

        if (server == null || !server.startsWith("https://")
                || token == null || token.trim().isEmpty()) {
            ConfigManager.clearCommandWindow(app);
            LogManager.log(app, "指令轮询未启动：服务器地址或Token无效");
            CycleService.stop(app);
            return;
        }

        long deadline = System.currentTimeMillis() + WINDOW_MS;

        ConfigManager.setCommandWindowEnd(app, deadline);

        startPollingUntil(app, deadline);

        LogManager.log(app, "指令接收窗口已开启，持续一小时，每10秒轮询");
    }

    // 进程或前台服务重新创建时，恢复原来的剩余时间
    public static synchronized void restoreWindow(Context context) {
        Context app = context.getApplicationContext();

        if (RUNNING) {
            return;
        }

        long deadline = ConfigManager.getCommandWindowEnd(app);

        if (deadline <= System.currentTimeMillis()) {
            ConfigManager.clearCommandWindow(app);
            LogManager.log(app, "指令接收窗口已过期");
            CycleService.stop(app);
            return;
        }

        String server = ConfigManager.getServerUrl(app);
        String token = ConfigManager.getDeviceToken(app);

        if (server == null || !server.startsWith("https://")
                || token == null || token.trim().isEmpty()) {
            ConfigManager.clearCommandWindow(app);
            LogManager.log(app, "恢复指令窗口失败：服务器地址或Token无效");
            CycleService.stop(app);
            return;
        }

        startPollingUntil(app, deadline);

        long remaining = deadline - System.currentTimeMillis();
        LogManager.log(app, "指令轮询已恢复，剩余 "
                + (remaining / 1000) + " 秒");
    }

    private static synchronized void startPollingUntil(
            Context context, long deadline) {

        stopWindowInternal();

        endTime = deadline;
        RUNNING = true;

        executor = Executors.newSingleThreadScheduledExecutor();

        future = executor.scheduleWithFixedDelay(
                () -> pollOnce(context.getApplicationContext()),
                0,
                POLL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    public static boolean isRunning() {
        return RUNNING;
    }

    // 主动停止窗口
    public static synchronized void stopWindow(Context context) {
        Context app = context.getApplicationContext();

        stopWindowInternal();
        ConfigManager.clearCommandWindow(app);

        LogManager.log(app, "指令接收窗口已手动停止");
        CycleService.stop(app);
    }

    private static synchronized void stopWindowInternal() {
        RUNNING = false;
        endTime = 0L;

        if (future != null) {
            future.cancel(true);
            future = null;
        }

        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private static void pollOnce(Context context) {

        if (!RUNNING) {
            return;
        }

        if (System.currentTimeMillis() >= endTime) {
            finishWindow(context);
            return;
        }

        String server = ConfigManager.getServerUrl(context);
        String token = ConfigManager.getDeviceToken(context);
        String deviceId = ConfigManager.getDeviceId(context);

        if (server == null || !server.startsWith("https://")
                || token == null || token.trim().isEmpty()) {
            LogManager.log(context, "轮询停止：服务器配置无效");
            finishWindow(context);
            return;
        }

        HttpURLConnection conn = null;

        try {
            String base = server.replaceAll("/+$", "");
            URL url = new URL(base + "/api/device/commands/poll");

            JSONObject body = new JSONObject();
            body.put("device_id", deviceId);
            body.put("timestamp", System.currentTimeMillis());

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);
            conn.setInstanceFollowRedirects(false);

            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("X-Device-Token", token);
            conn.setRequestProperty("X-Device-Id", deviceId);

            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);

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
                LogManager.log(context, "指令轮询HTTP失败：" + code);
            }

        } catch (Exception e) {
            LogManager.log(context, "指令轮询异常：" + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        if (RUNNING && System.currentTimeMillis() >= endTime) {
            finishWindow(context);
        }
    }

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

                if (!RUNNING || System.currentTimeMillis() >= endTime) {
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

                LogManager.log(context, "收到服务器指令：" + commandId);

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

            if (server == null || !server.startsWith("https://")
                    || token == null || token.trim().isEmpty()) {
                return;
            }

            String base = server.replaceAll("/+$", "");
            URL url = new URL(base + "/api/device/commands/result");

            JSONObject body = new JSONObject();
            body.put("device_id", deviceId);
            body.put("command_id", commandId);
            body.put("success", success);
            body.put("message", message == null ? "" : message);
            body.put("timestamp", System.currentTimeMillis());

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);
            conn.setInstanceFollowRedirects(false);

            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("X-Device-Token", token);
            conn.setRequestProperty("X-Device-Id", deviceId);

            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(data);
                os.flush();
            }

            int code = conn.getResponseCode();

            LogManager.log(context, "指令结果回传：" + commandId
                    + "，HTTP " + code);

        } catch (Exception e) {
            LogManager.log(context, "指令结果回传失败："
                    + e.getMessage());
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

    private static synchronized void finishWindow(Context context) {

        if (!RUNNING) {
            return;
        }

        stopWindowInternal();

        ConfigManager.clearCommandWindow(context);

        LogManager.log(context, "一小时指令接收时间结束，轮询已关闭");

        CycleService.stop(context);
    }
}


