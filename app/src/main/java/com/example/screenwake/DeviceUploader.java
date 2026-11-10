package com.example.screenwake;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class DeviceUploader {

    private static final String TAG = "DeviceUploader";

    // 写死AES密钥16位，必须和 jm.py 一致
    public static final String DEFAULT_AES_KEY = "MyKey1234567890";

    private static final int MAX_ATTEMPTS = 3;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onResult(boolean ok, String msg);
    }

    private DeviceUploader() {}

    // ==================== 兼容旧签名：send(Context, Callback) ====================
    public static void send(Context context, Callback callback) {
        String hook = ConfigManager.getFeishuWebhook(context);
        if (hook == null || hook.trim().isEmpty()) {
            if (callback != null) {
                MAIN.post(() -> callback.onResult(false, "飞书Webhook未配置"));
            }
            return;
        }
        send(context, hook, DEFAULT_AES_KEY, DeviceInfo.collect(context), callback);
    }

    // ==================== 主入口：飞书Webhook + AES加密 + 硬件JSON ====================
    public static void send(Context context, String webhookUrl, String aesKey16,
                            JSONObject hardwareInfo, Callback callback) {

        final Context app = context.getApplicationContext();

        if (!RUNNING.compareAndSet(false, true)) {
            notifyCallback(callback, false, "已有上报任务正在执行");
            LogManager.log(app, LogManager.SUB_UPLOAD, "上报跳过：任务锁占用");
            return;
        }

        new Thread(() -> {
            boolean success = false;
            String finalMessage = "上报失败";
            try {
                if (webhookUrl == null || webhookUrl.trim().isEmpty()) {
                    throw new Exception("飞书Webhook地址未配置");
                }
                if (hardwareInfo == null) {
                    throw new Exception("硬件信息为空");
                }
                if (aesKey16 == null || aesKey16.length() != 16) {
                    throw new Exception("AES密钥必须是16字符");
                }

                // 1. 加密硬件JSON
                String rawJson = hardwareInfo.toString();
                String[] enc = encryptAesCbc(rawJson, aesKey16);
                String cipherB64 = enc[0];
                String ivB64 = enc[1];
                String deviceId = ConfigManager.getDeviceId(app);

                LogManager.log(app, LogManager.SUB_UPLOAD,
                        "加密完成；deviceId=" + deviceId + " iv=" + ivB64
                                + " 明文长度=" + rawJson.length());

                // 2. 组装飞书卡片
                JSONObject cardJson = new JSONObject();

                JSONObject content = new JSONObject();
                content.put("tag", "markdown");
                content.put("content",
                        "📱 设备硬件密文上报\n"
                                + "device_id：`" + deviceId + "`\n"
                                + "iv(base64)：`" + ivB64 + "`\n"
                                + "cipher(base64)：\n```\n" + cipherB64 + "\n```\n"
                                + "运行 python jm.py 解密，输出：已开启.txt");

                JSONObject elements = new JSONObject();
                elements.put("tag", "div");
                elements.put("text", content);

                JSONObject body = new JSONObject();
                body.put("elements", new JSONObject[]{elements});

                JSONObject card = new JSONObject();
                card.put("config", new JSONObject().put("wide_screen_mode", true));
                card.put("body", body);

                cardJson.put("msg_type", "interactive");
                cardJson.put("card", card);

                // 3. POST 到飞书
                for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                    LogManager.log(app, LogManager.SUB_UPLOAD,
                            "飞书上报，第 " + attempt + "/" + MAX_ATTEMPTS + " 次尝试");

                    HttpURLConnection connection = null;
                    try {
                        URL url = new URL(webhookUrl.trim());
                        connection = (HttpURLConnection) url.openConnection();
                        connection.setRequestMethod("POST");
                        connection.setConnectTimeout(10000);
                        connection.setReadTimeout(15000);
                        connection.setDoOutput(true);
                        connection.setUseCaches(false);
                        connection.setInstanceFollowRedirects(false);
                        connection.setRequestProperty("Content-Type",
                                "application/json; charset=utf-8");

                        try (OutputStream os = connection.getOutputStream()) {
                            os.write(cardJson.toString().getBytes(StandardCharsets.UTF_8));
                        }

                        int code = connection.getResponseCode();
                        InputStream is = (code >= 200 && code < 300)
                                ? connection.getInputStream() : connection.getErrorStream();
                        String resp = readStream(is);

                        LogManager.log(app, LogManager.SUB_UPLOAD,
                                "飞书返回 HTTP " + code + " 内容=" + resp);

                        if (code == 200) {
                            success = true;
                            finalMessage = "上报成功";
                            break;
                        } else {
                            finalMessage = "HTTP " + code + " : " + resp;
                        }
                    } catch (Exception e) {
                        finalMessage = "请求异常：" + e.getClass().getSimpleName()
                                + " | " + e.getMessage();
                        LogManager.log(app, LogManager.SUB_UPLOAD, finalMessage);
                        Log.e(TAG, "单次上报异常", e);
                    } finally {
                        if (connection != null) connection.disconnect();
                    }

                    if (!success && attempt < MAX_ATTEMPTS) {
                        try {
                            Thread.sleep(attempt * 2000L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }

                if (!success) {
                    finalMessage = "全部" + MAX_ATTEMPTS + "次失败：" + finalMessage;
                    LogManager.log(app, LogManager.SUB_UPLOAD, finalMessage);
                }

            } catch (Exception e) {
                finalMessage = "上报初始化异常：" + e.getMessage();
                LogManager.log(app, LogManager.SUB_UPLOAD, finalMessage);
                Log.e(TAG, "外层异常", e);
            }

            RUNNING.set(false);
            notifyCallback(callback, success, finalMessage);
        }, "ScreenWake-Uploader").start();
    }

    // ==================== AES-CBC 随机IV加密 ====================
    private static String[] encryptAesCbc(String plainText, String key16) throws Exception {
        byte[] keyBytes = key16.getBytes(StandardCharsets.UTF_8);
        byte[] ivBytes = new byte[16];
        new SecureRandom().nextBytes(ivBytes);

        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(ivBytes);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

        String cipherB64 = Base64.encodeToString(encrypted, Base64.NO_WRAP);
        String ivB64 = Base64.encodeToString(ivBytes, Base64.NO_WRAP);
        return new String[]{cipherB64, ivB64};
    }

    // ==================== 读响应 ====================
    private static String readStream(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line);
        }
        br.close();
        return sb.toString();
    }

    private static void notifyCallback(Callback cb, boolean ok, String msg) {
        if (cb == null) return;
        MAIN.post(() -> cb.onResult(ok, msg));
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }
}
