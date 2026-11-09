package com.example.screenwake;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

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
import java.util.concurrent.atomic.AtomicBoolean;

public final class CommandPoller {

    private static final long WINDOW_MS = 60L * 60L * 1000L;
    private static final long INTERVAL_SECONDS = 10L;

    private static final AtomicBoolean RUNNING =
            new AtomicBoolean(false);

    private static ScheduledExecutorService executor;
    private static ScheduledFuture<?> pollingTask;
    private static long endTime;

    private static final String PREF = "screenwake_commands";

    private CommandPoller() {}

    public interface CommandResultCallback {
        void onResult(
                String commandId,
                boolean success,
                String message
        );
    }

    // 开启一小时指令接收窗口
    public static synchronized void startWindow(Context context) {

        Context c = context.getApplicationContext();

        stopWindow();

        String server = ConfigManager.getServerUrl(c);
        String token = ConfigManager.getToken(c);

        if (server == null || !server.startsWith("https://")
                || token == null || token.isEmpty()) {

            LogManager.log(c,
                    "指令窗口未启动：服务器地址或Token未配置");
            return;
        }

        endTime = System.currentTimeMillis() + WINDOW_MS;
        RUNNING.set(true);

        executor = Executors.newSingleThreadScheduledExecutor();

        LogManager.log(c,
                "一小时指令接收窗口已开启，结束时间：" + endTime);

        // 立即检查一次，之后每10秒检查
        pollingTask = executor.scheduleAtFixedRate(
                () -> pollOnce(c),
                0,
                INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    // 停止指令窗口
    public static synchronized void stopWindow() {

        RUNNING.set(false);

        if (pollingTask != null) {
            pollingTask.cancel(true);
            pollingTask = null;
        }

        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }

    // 单次查询
    private static void pollOnce(Context context) {

        Context c = context.getApplicationContext();

        if (!RUNNING.get()) {
            return;
        }

        if (System.currentTimeMillis() >= endTime) {
            finishWindow(c);
            return;
        }

        HttpURLConnection connection = null;

        try {
            String baseUrl = ConfigManager.getServerUrl(c);
            String token = ConfigManager.getToken(c);

            if (baseUrl == null || !baseUrl.startsWith("https://")
                    || token == null || token.isEmpty()) {
                LogManager.log(c, "指令查询失败：服务器配置无效");
                return;
            }

            String url = baseUrl.replaceAll("/+$", "")
                    + "/api/device/commands/poll";

            JSONObject body = new JSONObject();
            body.put("device_id", ConfigManager.getDeviceId(c));
            body.put("timestamp", System.currentTimeMillis());

            connection = (HttpURLConnection)
                    new URL(url).openConnection();

            connection.setRequestMethod("POST");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setDoOutput(true);
            connection.setDoInput(true);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);

            connection.setRequestProperty(
                    "Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("X-Device-Token", token);
            connection.setRequestProperty(
                    "X-Device-Id", ConfigManager.getDeviceId(c));

            byte[] data = body.toString().getBytes(
                    StandardCharsets.UTF_8);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(data);
                os.flush();
            }

            int code = connection.getResponseCode();

            if (code < 200 || code >= 300) {
                LogManager.log(c,
                        "指令查询失败，HTTP：" + code);
                return;
            }

            String response = readResponse(connection);

            if (response == null || response.trim().isEmpty()) {
                return;
            }

            JSONObject result = new JSONObject(response);
            JSONArray commands = result.optJSONArray("commands");

            if (commands == null || commands.length() == 0) {
                return;
            }

            LogManager.log(c,
                    "服务器返回指令数量：" + commands.length());

            for (int i = 0; i < commands.length(); i++) {

                if (!RUNNING.get()) {
                    break;
                }

                JSONObject command = commands.optJSONObject(i);

                if (command == null) {
                    continue;
                }

                String commandId = command.optString("id", "");

                if (commandId.isEmpty()) {
                    LogManager.log(c, "收到无ID指令，已忽略");
                    continue;
                }

                // 防止同一指令重复执行
                if (isCommandSeen(c, commandId)) {
                    LogManager.log(c,
                            "重复指令已忽略，ID：" + commandId);
                    continue;
                }

                // 收到后立即记录，避免重复执行
                markCommandSeen(c, commandId);

                LogManager.log(c,
                        "收到新指令，ID：" + commandId);

                CommandExecutor.execute(
                        c,
                        command,
                        (id, success, message) ->
                                sendCommandResult(
                                        c, id, success, message
                                )
                );
            }

        } catch (Exception e) {

            LogManager.log(c,
                    "指令查询异常：" + e.getMessage());

        } finally {

            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // 检查指令是否已经处理过
    private static boolean isCommandSeen(
            Context context, String commandId) {

        SharedPreferences p = context.getSharedPreferences(
                PREF, Context.MODE_PRIVATE);

        return p.getBoolean("cmd_" + commandId, false);
    }

    // 记录已接收的指令
    private static void markCommandSeen(
            Context context, String commandId) {

        SharedPreferences p = context.getSharedPreferences(
                PREF, Context.MODE_PRIVATE);

        p.edit().putBoolean("cmd_" + commandId, true).apply();
    }

    // 向服务器回传指令执行结果
    private static void sendCommandResult(
            Context context,
            String commandId,
            boolean success,
            String message) {

        Context c = context.getApplicationContext();

        HttpURLConnection connection = null;

        try {
            String baseUrl = ConfigManager.getServerUrl(c);
            String token = ConfigManager.getToken(c);

            if (baseUrl == null || !baseUrl.startsWith("https://")
                    || token == null || token.isEmpty()) {
                LogManager.log(c, "指令结果上传失败：配置无效");
                return;
            }

            String url = baseUrl.replaceAll("/+$", "")
                    + "/api/device/commands/result";

            JSONObject body = new JSONObject();
            body.put("device_id", ConfigManager.getDeviceId(c));
            body.put("command_id", commandId);
            body.put("success", success);
            body.put("message", message == null ? "" : message);
            body.put("timestamp", System.currentTimeMillis());

            connection = (HttpURLConnection)
                    new URL(url).openConnection();

            connection.setRequestMethod("POST");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setDoOutput(true);
            connection.setInstanceFollowRedirects(false);

            connection.setRequestProperty(
                    "Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("X-Device-Token", token);
            connection.setRequestProperty(
                    "X-Device-Id", ConfigManager.getDeviceId(c));

            try (OutputStream os = connection.getOutputStream()) {
                os.write(body.toString().getBytes(
                        StandardCharsets.UTF_8));
                os.flush();
            }

            int code = connection.getResponseCode();

            LogManager.log(c,
                    "指令结果回传，ID：" + commandId
                            + "，HTTP：" + code
                            + "，执行结果：" + success);

        } catch (Exception e) {

            LogManager.log(c,
                    "指令结果回传异常：" + e.getMessage());

        } finally {

            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // 读取服务器响应
    private static String readResponse(
            HttpURLConnection connection) throws Exception {

        InputStream input = connection.getInputStream();

        StringBuilder result = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        input, StandardCharsets.UTF_8))) {

            String line;

            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        }

        return result.toString();
    }

    // 一小时结束
    private static synchronized void finishWindow(Context context) {

        if (!RUNNING.getAndSet(false)) {
            return;
        }

        stopWindow();

        LogManager.log(context,
                "一小时指令接收窗口结束，停止轮询");
    }
}

