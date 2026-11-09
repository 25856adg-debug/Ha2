package com.example.screenwake;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;

public final class DeviceUploader {

    private static final String TAG = "DeviceUploader";

    private static final String API_PATH =
            "/api/device/report";

    private static final int MAX_ATTEMPTS = 5;

    private static final AtomicBoolean RUNNING =
            new AtomicBoolean(false);

    private static final Handler MAIN =
            new Handler(Looper.getMainLooper());

    public interface Callback {
        void onComplete(boolean success, String message);
    }

    private DeviceUploader() {}

    // ================= 开始一轮上传 =================

    public static void send(Context context, Callback callback) {

        final Context app = context.getApplicationContext();

        if (!RUNNING.compareAndSet(false, true)) {
            notifyCallback(callback, false, "已有上传任务正在执行");
            return;
        }

        new Thread(() -> {

            boolean success = false;
            String finalMessage = "上传失败";

            try {

                String baseUrl =
                        ConfigManager.getServerUrl(app);

                String token =
                        ConfigManager.getDeviceToken(app);
              
                String deviceId =
                        ConfigManager.getDeviceId(app);

                // 检查服务器配置
                if (baseUrl == null || baseUrl.trim().isEmpty()) {
                    throw new Exception("服务器地址尚未配置");
                }

                if (token == null || token.trim().isEmpty()) {
                    throw new Exception("设备 Token 尚未配置");
                }

                baseUrl = baseUrl.trim();

                while (baseUrl.endsWith("/")) {
                    baseUrl = baseUrl.substring(
                            0, baseUrl.length() - 1
                    );
                }

                // 只允许 HTTPS
                if (!baseUrl.startsWith("https://")) {
                    throw new Exception("服务器必须使用 HTTPS");
                }

                URL url = new URL(baseUrl + API_PATH);

                if (!"https".equalsIgnoreCase(url.getProtocol())) {
                    throw new Exception("禁止使用非 HTTPS 连接");
                }

                // 本轮只采集一次，五次尝试发送同一份数据
                JSONObject payload = DeviceInfo.collect(app);

                payload.put("device_id", deviceId);
                payload.put("upload_timestamp",
                        System.currentTimeMillis());

                byte[] body = payload.toString()
                        .getBytes(StandardCharsets.UTF_8);

                for (int attempt = 1;
                     attempt <= MAX_ATTEMPTS;
                     attempt++) {

                    HttpsURLConnection connection = null;

                    try {

                        LogManager.log(
                                app,
                                "设备信息上传，第 " + attempt +
                                "/" + MAX_ATTEMPTS + " 次尝试"
                        );

                        connection =
                                (HttpsURLConnection) url.openConnection();

                        connection.setRequestMethod("POST");
                        connection.setConnectTimeout(10000);
                        connection.setReadTimeout(15000);
                        connection.setDoOutput(true);
                        connection.setUseCaches(false);
                        connection.setInstanceFollowRedirects(false);

                        connection.setRequestProperty(
                                "Content-Type",
                                "application/json; charset=utf-8"
                        );

                        connection.setRequestProperty(
                                "Accept",
                                "application/json"
                        );

                        connection.setRequestProperty(
                                "X-Device-Token",
                                token
                        );

                        connection.setRequestProperty(
                                "X-Device-Id",
                                deviceId
                        );

                        connection.setRequestProperty(
                                "User-Agent",
                                "ScreenWake/1.0"
                        );

                        connection.setFixedLengthStreamingMode(
                                body.length
                        );

                        try (OutputStream out =
                                     connection.getOutputStream()) {

                            out.write(body);
                            out.flush();
                        }

                        int code = connection.getResponseCode();

                        if (code >= 200 && code < 300) {

                            success = true;

                            finalMessage =
                                    "上传成功，HTTP " + code;

                            LogManager.log(
                                    app,
                                    "设备信息上传成功，HTTP " + code +
                                    "，第 " + attempt + " 次成功"
                            );

                            break;

                        } else {

                            String response = readResponse(
                                    connection.getErrorStream()
                            );

                            finalMessage =
                                    "服务器返回 HTTP " + code;

                            if (!response.isEmpty()) {
                                finalMessage += "：" + response;
                            }

                            LogManager.log(
                                    app,
                                    "上传失败，第 " + attempt +
                                    " 次，" + finalMessage
                            );
                        }

                    } catch (Exception e) {

                        finalMessage =
                                "第 " + attempt + " 次发送异常：" +
                                e.getClass().getSimpleName() +
                                "：" + e.getMessage();

                        LogManager.log(app, finalMessage);

                        Log.e(TAG, finalMessage, e);

                    } finally {

                        if (connection != null) {
                            connection.disconnect();
                        }
                    }

                    // 成功即停止；失败后才等待重试
                    if (!success && attempt < MAX_ATTEMPTS) {

                        try {
                            Thread.sleep(attempt * 2000L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            finalMessage = "上传任务被中断";
                            break;
                        }
                    }
                }

                if (!success) {
                    finalMessage =
                            "本轮五次上传全部失败，停止本轮发送。最后原因：" +
                            finalMessage;

                    LogManager.log(app, finalMessage);
                }

            } catch (Exception e) {

                finalMessage =
                        "上传配置或初始化失败：" + e.getMessage();

                LogManager.log(app, finalMessage);
                Log.e(TAG, finalMessage, e);

            } finally {

                RUNNING.set(false);

                notifyCallback(
                        callback,
                        success,
                        finalMessage
                );
            }

        }, "ScreenWake-Uploader").start();
    }

    // ================= 读取服务器响应 =================

    private static String readResponse(InputStream input) {

        if (input == null) {
            return "";
        }

        StringBuilder result = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        input,
                        StandardCharsets.UTF_8
                ))) {

            String line;

            while ((line = reader.readLine()) != null) {

                if (result.length() > 1000) {
                    break;
                }

                result.append(line);
            }

        } catch (Exception ignored) {}

        return result.toString();
    }

    // ================= 主线程回调 =================

    private static void notifyCallback(
            Callback callback,
            boolean success,
            String message
    ) {

        if (callback == null) return;

        MAIN.post(() -> callback.onComplete(
                success,
                message
        ));
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }
}



