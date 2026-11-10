package com.example.screenwake;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class CommandPoller {
    /** 轮询间隔：10 秒 */
    private static final long POLL_SECONDS = 10L;
    /** 指令去重用的 SharedPreferences 名（与旧版保持一致，历史标记继续有效） */
    private static final String PREFS_NAME = "screenwake_commands";
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

    // ================= 获取连接真实远端IP =================
    private static String getConnectedIp(HttpURLConnection conn) {
        try {
            Socket socket = conn.getSocket();
            if (socket != null && socket.getInetAddress() != null) {
                return socket.getInetAddress().getHostAddress();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ================= 发送绑定确认UI广播 =================
    private static void fireBindConfirmUi(Context c, String bindId, boolean isChanged) {
        Intent intent = new Intent("com.example.screenwake.BIND_CONFIRM");
        intent.putExtra("server_bind_id", bindId);
        intent.putExtra("is_changed", isChanged);
        LocalBroadcastManager.getInstance(c).sendBroadcast(intent);
    }

    // ================= 兼容旧调用：上报成功后调用 =================
    // 不再开「一小时窗口」，改为确保轮询常驻
    public static synchronized void startWindow(Context context) {
        start(context);
    }

    // ================= 兼容旧调用：服务重建时调用 =================
    public static synchronized void restoreWindow(Context context) {
        start(context);
    }

    // ================= 常驻启动轮询 =================
    public static synchronized void start(Context context) {
        Context app = context.getApplicationContext();
        if (RUNNING && executor != null && !executor.isShutdown()) {
            // 已经在跑，直接返回，不重置计时
            return;
        }
        String server = ConfigManager.getServerUrl(app);
        String token = ConfigManager.getDeviceToken(app);
        if (!isServerValid(server) || token == null || token.trim().isEmpty()) {
            LogManager.log(app, LogManager.SUB_COMMAND, "指令轮询未启动：服务器地址或Token无效");
            return;
        }
        stopInternal();
        RUNNING = true;
        executor = Executors.newSingleThreadScheduledExecutor();
        future = executor.scheduleWithFixedDelay(
                () -> pollOnce(app),
                0,
                POLL_SECONDS,
                TimeUnit.SECONDS
        );
        LogManager.log(app, LogManager.SUB_COMMAND,
                "指令轮询已启动，每 " + POLL_SECONDS + " 秒一次，常驻运行");
    }

    // ================= 兜底：没在跑就拉起来 =================
    public static synchronized void ensureRunning(Context context) {
        if (!RUNNING) {
            start(context);
        }
    }

    // ================= 停轮询（不动上报定时器） =================
    public static synchronized void stopPolling(Context context) {
        Context app = context.getApplicationContext();
        stopInternal();
        LogManager.log(app, LogManager.SUB_COMMAND, "指令轮询已停止");
    }

    // ================= 兼容旧调用名 =================
    public static synchronized void stopWindow(Context context) {
        stopPolling(context);
    }

    public static boolean isRunning() {
        return RUNNING;
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
            LogManager.log(context, LogManager.SUB_COMMAND, "轮询跳过：服务器配置无效");
            return;
        }
        HttpURLConnection conn = null;
        try {
            String base = server.trim().replaceAll("/+$", "");
            URL url = new URL(base + "/api/device/commands/poll");
            JSONObject body = new JSONObject();
            body.put("device_id", deviceId);
            body.put("timestamp", System.currentTimeMillis());
            String[] aes = AesUtil.encrypt(body.toString());
            String encBody = aes[0];
            String ivB64 = aes[1];
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
            InputStream input = (code >= 200 && code < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream();
            String response = readResponse(input);
            if (code >= 200 && code < 300) {
                handleCommands(context, response, conn, url);
            } else {
                LogManager.log(context, LogManager.SUB_COMMAND,
                        "指令轮询HTTP失败：" + code + " " + response);
            }
        } catch (Exception e) {
            LogManager.log(context, LogManager.SUB_COMMAND, "指令轮询异常：" + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ================= 处理指令，增加服务器身份校验（IP OR bindId） =================
    private static void handleCommands(Context context, String response, HttpURLConnection conn, URL url) {
        try {
            JSONObject root = new JSONObject(response);
            // 取出服务器标识码
            String remoteBindId = root.optString("server_bind_id", "");
            if (remoteBindId.isEmpty()) {
                LogManager.log(context, LogManager.SUB_COMMAND, "响应缺少server_bind_id，拒绝全部指令");
                return;
            }

            // 读取本地配置
            Set<String> allowServerSet = LogManager.readServerAllowList(context);
            String trustedBindId = LogManager.getTrustedServerBindId(context);
            String realIp = getConnectedIp(conn);
            String host = url.getHost();

            // IP校验
            boolean okIp = false;
            if (realIp != null && allowServerSet.contains(realIp)) {
                okIp = true;
            } else if (allowServerSet.contains(host)) {
                okIp = true;
            }

            // bindId校验
            boolean okBind = false;
            if (trustedBindId != null && !trustedBindId.isEmpty()) {
                okBind = trustedBindId.equals(remoteBindId);
            }

            // 或逻辑：任意一个通过放行
            if (okIp || okBind) {
                LogManager.log(context, LogManager.SUB_COMMAND,
                        String.format("服务端身份校验通过 okIp=%b okBind=%b", okIp, okBind));
            } else {
                LogManager.log(context, LogManager.SUB_COMMAND,
                        String.format("服务端身份校验失败 okIp=%b okBind=%b remoteBindId=%s realIp=%s",
                                okIp, okBind, remoteBindId, realIp));
                boolean isChanged = trustedBindId != null;
                fireBindConfirmUi(context, remoteBindId, isChanged);
                return;
            }

            JSONArray commands = root.optJSONArray("commands");
            if (commands == null || commands.length() == 0) {
                return;
            }
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
                // 只有「已执行成功」才会被标记，所以失败/中断的指令下次还能重试
                if (isDone(context, commandId)) {
                    continue;
                }
                LogManager.log(context, LogManager.SUB_COMMAND,
                        "收到服务器指令：" + commandId
                                + "，动作：" + command.optString("action", ""));
                CommandExecutor.execute(
                        context,
                        command,
                        (id, success, message) -> {
                            if (success) {
                                markDone(context, id);
                            }
                            sendCommandResult(context, id, success, message);
                        }
                );
            }
        } catch (Exception e) {
            LogManager.log(context, LogManager.SUB_COMMAND, "解析服务器指令失败：" + e.getMessage());
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static boolean isDone(Context context, String commandId) {
        return prefs(context).getBoolean("cmd_" + commandId, false);
    }

    private static void markDone(Context context, String commandId) {
        prefs(context).edit().putBoolean("cmd_" + commandId, true).apply();
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
                LogManager.log(context, LogManager.SUB_COMMAND, "结果回传跳过：服务器配置无效");
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
            String[] aes = AesUtil.encrypt(body.toString());
            String encBody = aes[0];
            String ivB64 = aes[1];
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
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
            LogManager.log(context, LogManager.SUB_COMMAND,
                    "指令结果回传：" + commandId + "，HTTP " + code);
        } catch (Exception e) {
            LogManager.log(context, LogManager.SUB_COMMAND, "指令结果回传失败：" + e.getMessage());
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


