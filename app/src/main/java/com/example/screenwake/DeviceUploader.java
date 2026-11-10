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
        // 老代码 TaskReceiver 里重写的是 onComplete，必须保留这个名字
        void onComplete(boolean success, String message);

        // 兼容我这边新代码用 onResult 的写法
        default void onResult(boolean ok, String msg) {
            onComplete(ok, msg);
        }
    }

    private DeviceUploader() {}

    // ==================== 兼容旧签名：send(Context, Callback) ====================
    public static void send(Context context, Callback callback) {
        String hook = ConfigManager.getFeishuWebhook(context);
        if (hook == null || hook.trim().isEmpty()) {
            if (callback != null) {
                MAIN.post(() -> callback.onComplete(false, "飞书Webhook未配置"));
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

                // 2. 组装飞书卡片（必须用 v1 结构：elements 在 card 顶层，不能套 body）
                JSONObject card = new JSONObject();
                card.put("config", new JSONObject().put("wide_screen_mode", true));

                JSONObject header = new JSONObject();
                JSONObject title = new JSONObject();
                title.put("tag", "plain_text");
                title.put("content", "设备硬件密文上报");
                header.put("title", title);
                header.put("template", "blue");
                card.put("header", header);

                JSONObject textObj = new JSONObject();
                textObj.put("tag", "lark_md");
                textObj.put("content",
                        "**device_id**：" + deviceId + "\n"
+ "**iv（Base64）**：" + ivB64 + "\n"
                                + "**cipher（Base64）**：\n" + cipherB64 + "\n"
                                + "把 iv 和 cipher 粘进 jm.py 运行，生成 已开启.txt");

                JSONObject elem1 = new JSONObject();
                elem1.put("tag", "div");
                elem1.put("text", textObj);

                card.put("elements", new JSONObject[]{elem1});

                JSONObject cardJson = new JSONObject();
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

                        if (code == 200 && isFeishuOk(resp)) {
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

    // ==================== 判断飞书是否真的收下了 ====================
    // 飞书 HTTP 200 也可能带错误码，例如 {"code":11246,"msg":"parse card json err"}
    private static boolean isFeishuOk(String resp) {
        if (resp == null || resp.trim().isEmpty()) return false;
        try {
            JSONObject o = new JSONObject(resp);
            if (o.has("code")) {
                return o.optInt("code", -1) == 0;
            }
            if (o.has("StatusCode")) {
                return o.optInt("StatusCode", -1) == 0;
            }
        } catch (Exception ignored) {}
        return false;
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
        MAIN.post(() -> cb.onComplete(ok, msg));
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }
}

