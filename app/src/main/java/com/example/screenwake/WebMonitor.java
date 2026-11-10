package com.example.screenwake;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WebMonitor {

    private static final String TAG = "WebMonitor";

    // 写死AES密钥16位，必须和 jm.py 一致
    private static final String FIX_AES_KEY = "MyKey1234567890";

    private static final long CHECK_INTERVAL_MS = 10_000L;
    private static final int  MAX_SEND_INTERVAL_SEC = 500;
    private static final int  MIN_SLEEP_MINUTES = 1;
    private static final int  MAX_SLEEP_MINUTES = 1440;

    // 网页返回了页面源码、但里面没有一个能用的数字
private static final String NO_VALUE_MARK = "@@NOVALUE@@";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static Context appCtx = null;
    private static String monitorUrl = "";
    private static String feishuHook = "";

    private static Runnable checkRunnable = null;
    private static Runnable sendRunnable = null;
    private static Runnable wakeRunnable = null;

    private static int sendIntervalSec = 0;

    // 给界面直接显示的状态文字
    private static volatile String lastStatus = "未启动";
    private static volatile long lastStatusTs = 0L;
    // 上次网页返回内容的开头（排查用）
    private static volatile String lastSniff = "";

    private static final AtomicBoolean checking = new AtomicBoolean(false);
    private static volatile boolean sleeping = false;
    private static volatile long sleepEndTs = 0L;
    private static volatile boolean started = false;

    private WebMonitor() {}

    // ==================== 启动 ====================
    public static synchronized void start(Context ctx, String url, String hook) {
appCtx = ctx.getApplicationContext();
        monitorUrl = url == null ? "" : url.trim();
        feishuHook = hook == null ? "" : hook.trim();

        cancelAll("重启检测任务");
        started = true;
        sleeping = false;
        sleepEndTs = 0L;

        String mode = ConfigManager.getDetectMode(appCtx);
        String srcDesc = ConfigManager.MODE_FILE.equals(mode)
                ? ("本地文件：" + ConfigManager.getLocalFileUri(appCtx))
                : ("网页：" + monitorUrl);

        LogManager.log(appCtx, LogManager.SUB_UPLOAD,
                "启动自动检测：每10秒一次，检测源=" + srcDesc);
        setStatus("检测中（10秒一次）");
        scheduleNextCheck(0L);
    }

    // ==================== 停止 ====================
    public static synchronized void stop() {
        cancelAll("手动停止");
        started = false;
        sleeping = false;
        sleepEndTs = 0L;
        if (appCtx != null) {
            LogManager.log(appCtx, LogManager.SUB_UPLOAD, "已停止检测");
        }
        setStatus("已停止");
    }
// ==================== 清除休眠立即恢复 ====================
    public static synchronized void clearSleepAndResume() {
        if (appCtx == null) return;
        if (wakeRunnable != null) {
            MAIN.removeCallbacks(wakeRunnable);
            wakeRunnable = null;
        }
        sleeping = false;
        sleepEndTs = 0L;
        stopSendLoop("手动清除休眠");
        LogManager.log(appCtx, LogManager.SUB_UPLOAD, "手动清除休眠，立即恢复10秒检测");
        if (started) scheduleNextCheck(0L);
    }

    // ==================== 10秒检测循环 ====================
    private static void scheduleNextCheck(long delayMs) {
        if (appCtx == null || !started) return;
        if (checkRunnable != null) MAIN.removeCallbacks(checkRunnable);

        checkRunnable = new Runnable() {
            @Override
            public void run() {
                if (sleeping || !started) return;
                doCheckOnce();
            }
        };
        MAIN.postDelayed(checkRunnable, delayMs);
    }

    private static void doCheckOnce() {
if (!checking.compareAndSet(false, true)) {
            scheduleNextCheck(CHECK_INTERVAL_MS);
            return;
        }

        final Context ctx = appCtx;
        final boolean fileMode =
                ConfigManager.MODE_FILE.equals(ConfigManager.getDetectMode(ctx));

        new Thread(() -> {
            int httpCode = -1;
            String body = null;

            if (fileMode) {
                // ===== 本地文件检测 =====
                body = readLocalFile(ctx);
                if (body != null) {
                    httpCode = 200;
                } else {
                    LogManager.log(ctx, LogManager.SUB_UPLOAD,
                            "本地文件读取失败 / 文件不存在 / URI失效，本轮不动作");
                }
            } else {
                // ===== 网页检测 =====
                String urlStr = monitorUrl;
                try {
                    if (urlStr != null && urlStr.toLowerCase(Locale.getDefault()).endsWith("/stream")) {
                        // 地址本身就是 SSE 流，直接读首条 data
body = fetchStreamValue(urlStr);
                        httpCode = body != null ? 200 : -1;
                    } else {
                        URL urlObj = new URL(urlStr);
                        HttpURLConnection conn = (HttpURLConnection) urlObj.openConnection();
                        conn.setConnectTimeout(8000);
                        conn.setReadTimeout(8000);
                        conn.setRequestMethod("GET");
                        conn.setRequestProperty("Cache-Control", "no-cache");
                        conn.setUseCaches(false);
                        httpCode = conn.getResponseCode();
                        InputStream is = (httpCode >= 200 && httpCode < 300)
                                ? conn.getInputStream() : conn.getErrorStream();
                        if (is != null) {
                            body = readStreamToString(is);
                        }
                        conn.disconnect();

                        // 拿到的是网页源码 / JSON / SSE 时，统一抠出数字
String value = normalizeValue(body);
                        if (value == null && looksLikeHtml(body)) {
                            // 页面是 JS 动态渲染：先顺着它自己写的 EventSource 路径抓，再退回猜 /stream
                            String esPath = extractDataPath(body);
                            String sv = null;
                            if (esPath != null) {
                                String esUrl = buildAbsolute(urlStr, esPath);
                                LogManager.log(ctx, LogManager.SUB_UPLOAD,
                                        "页面是动态渲染，按 EventSource 路径读：" + esUrl);
                                sv = fetchStreamValue(esUrl);
                            }
                            if (sv == null) {
                                String fallback = deriveStreamUrl(urlStr);
                                LogManager.log(ctx, LogManager.SUB_UPLOAD,
                                        "再试默认路径：" + fallback);
                                sv = fetchStreamValue(fallback);
}
                            if (sv != null) {
                                value = sv;
                            }
                        }
                        if (value != null) {
                            body = value;
                        } else if (body != null) {
                            // 记住返回内容开头，方便界面直接看出服务器到底给了啥
                            lastSniff = cutForLog(body, 70);
                            body = NO_VALUE_MARK;
                        }
                    }
                } catch (Exception e) {
                    LogManager.log(ctx, LogManager.SUB_UPLOAD,
                            "网页检测异常：" + e.getClass().getSimpleName() + " " + e.getMessage());
                }
            }

            final int fCode = httpCode;
            final String fBody = body;
            final boolean fFileMode = fileMode;

            MAIN.post(() -> {
                checking.set(false);
                handleResult(ctx, fCode, fBody, fFileMode);
if (!sleeping && started) scheduleNextCheck(CHECK_INTERVAL_MS);
            });
        }).start();
    }

    // ==================== 读本地文件 ====================
    private static String readLocalFile(Context ctx) {
        String uriStr = ConfigManager.getLocalFileUri(ctx);
        if (uriStr == null || uriStr.trim().isEmpty()) {
            return null;
        }
        InputStream is = null;
        try {
            is = ctx.getContentResolver().openInputStream(Uri.parse(uriStr.trim()));
            if (is == null) return null;
            String content = readStreamToString(is);
            if (content == null) return null;
            // 取第一个非空行
            String[] lines = content.split("\n");
            for (String line : lines) {
                String t = line.trim();
                if (!t.isEmpty()) return t;
            }
            return "";
        } catch (Exception e) {
            LogManager.log(ctx, LogManager.SUB_UPLOAD,
                    "读本地文件异常：" + e.getClass().getSimpleName() + " " + e.getMessage());
return null;
        } finally {
            try {
                if (is != null) is.close();
            } catch (Exception ignored) {}
        }
    }

    // ==================== 从响应里抠出真正的数值 ====================
    private static boolean looksLikeHtml(String s) {
        if (s == null) return false;
        String t = s.trim().toLowerCase(Locale.getDefault());
        return t.startsWith("<!doctype") || t.startsWith("<html")
                || (t.startsWith("<") && t.contains("<body"));
    }

    /**
     * 支持四种返回格式：
     * 1) 纯数字 4
     * 2) JSON {"value":4} / {"n":"4"}
     * 3) SSE  data: 4
     * 4) HTML 里的 <div id="n">4</div>
     * 抠不出来返回 null
     */
    private static String normalizeValue(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;

        // 1. 纯数字
        if (isPureNumber(t)) return t;

        // 2. JSON
        if (t.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(t);
String[] keys = {"value", "n", "num", "count", "status", "v"};
                for (String k : keys) {
                    if (o.has(k)) {
                        String v = String.valueOf(o.opt(k)).trim();
                        if (isPureNumber(v)) return v;
                    }
                }
            } catch (Exception ignored) {}
        }

        // 3. SSE：data: 4
        int di = t.indexOf("data:");
        if (di >= 0) {
            String rest = t.substring(di + 5);
            int nl = rest.indexOf('\n');
            if (nl >= 0) rest = rest.substring(0, nl);
            rest = rest.trim();
            if (isPureNumber(rest)) return rest;
            // 形如 data: {"value":4}
            if (rest.startsWith("{")) return normalizeValue(rest);
        }

        // 4. HTML：<div id="n">4</div>
        Matcher m = Pattern.compile("(?s)id\\s*=\\s*\"n\"[^>]*>\\s*([^<]{1,20})<").matcher(t);
        if (m.find()) {
            String v = m.group(1).trim();
            if (isPureNumber(v)) return v;
}

        // 5. 万能兜底：把 HTML 标签全剥掉，如果剩下的可见文本整体就是个数字，就用它
        if (looksLikeHtml(t)) {
            String text = t
                    .replaceAll("(?s)<script[^>]*>.*?</script>", " ")
                    .replaceAll("(?s)<style[^>]*>.*?</style>", " ")
                    .replaceAll("(?s)<!--.*?-->", " ")
                    .replaceAll("<[^>]+>", " ")
                    .trim();
            if (isPureNumber(text)) return text;
        }

        return null;
    }

    /** 从页面源码里找真正的数据源路径：EventSource('/x') / fetch('/x') / open('GET','/x') */
    private static String extractDataPath(String html) {
        if (html == null) return null;

        Matcher m = Pattern.compile("EventSource\\s*\\(\\s*[\"']([^\"']+)[\"']").matcher(html);
        if (m.find()) return m.group(1);

        m = Pattern.compile("fetch\\s*\\(\\s*[\"']([^\"']+)[\"']").matcher(html);
        if (m.find()) return m.group(1);

        m = Pattern.compile(".open\\s*\\(\\s*[\"']GET[\"']\\s*,\\s*[\"']([^\"']+)[\"']").matcher(html);
if (m.find()) return m.group(1);

        return null;
    }

    /** 相对路径拼成绝对地址 */
    private static String buildAbsolute(String baseUrl, String path) {
        if (path == null) return null;
        String p = path.trim();
        if (p.startsWith("http://") || p.startsWith("https://")) return p;
        try {
            URL u = new URL(baseUrl);
            String portPart = u.getPort() > 0 ? (":" + u.getPort()) : "";
            String scheme = u.getProtocol();
            if (p.startsWith("/")) {
                return scheme + "://" + u.getHost() + portPart + p;
            }
            String basePath = u.getPath();
            if (basePath == null || "/".equals(basePath)) basePath = "";
            int idx = basePath.lastIndexOf('/');
            String dir = idx >= 0 ? basePath.substring(0, idx + 1) : "/";
            return scheme + "://" + u.getHost() + portPart + dir + p;
        } catch (Exception e) {
            return null;
        }
    }

    /** 把 http://host:port/xxx 推导成 http://host:port/stream */
private static String deriveStreamUrl(String urlStr) {
        if (urlStr == null) return null;
        try {
            URL u = new URL(urlStr);
            String host = u.getHost();
            int port = u.getPort();
            String path = u.getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) {
                return "http://" + host + ":" + port + "/stream";
            }
            if (path.toLowerCase(Locale.getDefault()).endsWith("/stream")) {
                return urlStr;
            }
            int idx = path.lastIndexOf('/');
            String base = idx >= 0 ? path.substring(0, idx) : path;
            return "http://" + host + ":" + port + base + "/stream";
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 读取 SSE 流里的第一条 data，读到就立刻断开（不会一直挂着）
     */
    private static String fetchStreamValue(String streamUrl) {
        if (streamUrl == null) return null;
        HttpURLConnection conn = null;
        BufferedReader br = null;
try {
            URL u = new URL(streamUrl);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setUseCaches(false);

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;

            br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            String line;
            int lines = 0;
            while ((line = br.readLine()) != null && lines++ < 12) {
                String v = normalizeValue(line);
                if (v != null) return v;
            }
            return null;
        } catch (Exception e) {
            LogManager.log(appCtx, LogManager.SUB_UPLOAD,
                    "读 /stream 失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
return null;
        } finally {
            try {
                if (br != null) br.close();
            } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    private static String readStreamToString(InputStream is) throws Exception {
        if (is == null) return null;
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line).append("\n");
        }
        br.close();
        return sb.toString();
    }

    // ==================== 结果处理（规则统一） ====================
    private static void handleResult(Context ctx, int httpCode, String body, boolean fileMode) {
        String src = fileMode ? "本地文件" : "网页";
        LogManager.log(ctx, LogManager.SUB_UPLOAD,
                src + "检测结果 code=" + httpCode + " 内容=[" + cutForLog(body) + "]");

        if (NO_VALUE_MARK.equals(body)) {
setStatus("检测失败：返回的不是数字 [HTTP " + httpCode + "] " + lastSniff);
            LogManager.log(ctx, LogManager.SUB_UPLOAD,
                    "返回的是HTML页面，没抠到数字，本轮不动作");
            return;
        }

        if (!fileMode && !(httpCode >= 200 && httpCode < 300)) {
            LogManager.log(ctx, LogManager.SUB_UPLOAD, "网页打不开，本轮不动作");
            setStatus("检测失败：网页打不开（HTTP " + httpCode + "）");
            return;
        }
        if (body == null) {
            LogManager.log(ctx, LogManager.SUB_UPLOAD, src + "读取失败，本轮不动作");
            setStatus("检测失败：读不到" + src + "内容");
            return;
        }

        String flag = body.trim();

        if ("0".equals(flag)) {
            // ===== 0：休眠 =====
            stopSendLoop("返回0，进入休眠");
            if (checkRunnable != null) {
                MAIN.removeCallbacks(checkRunnable);
                checkRunnable = null;
            }

            int minutes = ConfigManager.getSleepMinutes(ctx);
            if (minutes < MIN_SLEEP_MINUTES) minutes = MIN_SLEEP_MINUTES;
if (minutes > MAX_SLEEP_MINUTES) minutes = MAX_SLEEP_MINUTES;
            long sleepMs = minutes * 60_000L;

            sleeping = true;
            sleepEndTs = System.currentTimeMillis() + sleepMs;

            LogManager.log(ctx, LogManager.SUB_UPLOAD,
                    src + "返回0：停止检测与发送，休眠 " + minutes
                            + " 分钟，预计 " + formatTime(sleepEndTs) + " 自动恢复");
            setStatus("已检测到 0，进入休眠 " + minutes + " 分钟");

            wakeRunnable = new Runnable() {
                @Override
                public void run() {
                    if (!sleeping || appCtx == null) return;
                    sleeping = false;
                    sleepEndTs = 0L;
                    LogManager.log(appCtx, LogManager.SUB_UPLOAD, "休眠结束，自动恢复10秒检测");
                    scheduleNextCheck(0L);
                }
            };
            MAIN.postDelayed(wakeRunnable, sleepMs);

        } else if ("1".equals(flag)) {
            // ===== 1：立即发一次 =====
            stopSendLoop("返回1，切换单次发送");
LogManager.log(ctx, LogManager.SUB_UPLOAD, src + "返回1，立即发送一次硬件信息");
            setStatus("已检测到 1，正在发送…");
            sendOnce(ctx);

        } else if (isPureNumber(flag)) {
            int sec = Integer.parseInt(flag);
            if (sec > MAX_SEND_INTERVAL_SEC) {
                LogManager.log(ctx, LogManager.SUB_UPLOAD,
                        src + "返回" + sec + "秒，超上限，按" + MAX_SEND_INTERVAL_SEC + "秒执行");
                sec = MAX_SEND_INTERVAL_SEC;
            }
            if (sec <= 0) {
                LogManager.log(ctx, LogManager.SUB_UPLOAD, "非法间隔" + flag + "，忽略");
                setStatus("检测到非法间隔[" + flag + "]，不执行");
            } else if (sec == 1) {
                stopSendLoop("返回1秒，按单次发送");
                LogManager.log(ctx, LogManager.SUB_UPLOAD, src + "返回1秒，按单次发送处理");
                setStatus("已检测到 1，正在发送…");
                sendOnce(ctx);
            } else {
                setStatus("已检测到 " + sec + "，每" + sec + "秒发送一次");
                startSendLoop(ctx, sec);
}

        } else {
            LogManager.log(ctx, LogManager.SUB_UPLOAD,
                    src + "返回[" + flag + "]不是0/1/数字，本轮不动作");
            setStatus("检测到 [" + shortMsg(flag) + "]，不是0/1/数字，不执行");
        }
    }

    // ==================== 单次发送 ====================
    private static void sendOnce(Context ctx) {
        try {
            JSONObject hardware = DeviceInfo.collect(ctx);
            DeviceUploader.send(ctx, feishuHook, FIX_AES_KEY, hardware, (ok, msg) -> {
                LogManager.log(ctx, LogManager.SUB_UPLOAD,
                        "单次上报结果 ok=" + ok + " msg=" + msg);
                setStatus(classify(ok, msg));
            });
        } catch (Exception e) {
            LogManager.log(ctx, LogManager.SUB_UPLOAD, "单次采集异常：" + e.getMessage());
            setStatus("发送失败：采集异常 " + e.getMessage());
        }
    }

    // ==================== n秒发送循环 ====================
    private static void startSendLoop(Context ctx, int intervalSec) {
        stopSendLoop("切换发送间隔");
sendIntervalSec = intervalSec;

        LogManager.log(ctx, LogManager.SUB_UPLOAD,
                "启动秒级发送循环，间隔 " + intervalSec + " 秒（检测仍每10秒一次）");

        sendRunnable = new Runnable() {
            @Override
            public void run() {
                if (sleeping || sendRunnable != this || appCtx == null) return;
                try {
                    JSONObject hardware = DeviceInfo.collect(appCtx);
                    DeviceUploader.send(appCtx, feishuHook, FIX_AES_KEY, hardware, (ok, msg) -> {
                        LogManager.log(appCtx, LogManager.SUB_UPLOAD,
                                "循环上报(" + sendIntervalSec + "s) ok=" + ok + " msg=" + msg);
                        setStatus(classify(ok, msg) + "（每" + sendIntervalSec + "秒）");
                    });
                } catch (Exception e) {
                    LogManager.log(appCtx, LogManager.SUB_UPLOAD,
                            "循环采集异常：" + e.getMessage());
                }
                if (sendRunnable == this) {
MAIN.postDelayed(this, sendIntervalSec * 1000L);
                }
            }
        };
        MAIN.post(sendRunnable);
    }

    private static void stopSendLoop(String reason) {
        if (sendRunnable != null) {
            MAIN.removeCallbacks(sendRunnable);
            sendRunnable = null;
            if (appCtx != null) {
                LogManager.log(appCtx, LogManager.SUB_UPLOAD,
                        "停止发送循环（原间隔" + sendIntervalSec + "秒）：" + reason);
            }
        }
        sendIntervalSec = 0;
    }

    private static void cancelAll(String reason) {
        if (checkRunnable != null) {
            MAIN.removeCallbacks(checkRunnable);
            checkRunnable = null;
        }
        if (wakeRunnable != null) {
            MAIN.removeCallbacks(wakeRunnable);
            wakeRunnable = null;
        }
        stopSendLoop(reason);
    }

    // ==================== 工具 ====================
    private static boolean isPureNumber(String s) {
if (s == null || s.isEmpty() || s.length() > 6) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static String formatTime(long ts) {
        return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(ts));
    }

    // ==================== 状态文字 ====================
    private static void setStatus(String s) {
        lastStatus = s;
        lastStatusTs = System.currentTimeMillis();
    }

    // 把上传结果翻译成界面能直接看懂的话
    private static String classify(boolean ok, String msg) {
        if (ok) return "发送成功";

        String m = msg == null ? "" : msg;

        // 飞书地址/配置问题
        if (m.contains("未配置") || m.contains("地址错误") || m.contains("密钥")) {
            return "飞书地址错误：" + m;
        }
        // HTTP 400/403/404 基本都是 webhook 填错或被禁用
        if (m.contains("HTTP 400") || m.contains("HTTP 401")
                || m.contains("HTTP 403") || m.contains("HTTP 404")) {
return "飞书地址错误（" + briefHttp(m) + "）";
        }
        // 网络层问题
        if (m.contains("ConnectException") || m.contains("UnknownHost")
                || m.contains("SocketTimeout") || m.contains("SSL")
                || m.contains("Unable to resolve") || m.contains("Network is unreachable")) {
            return "发送失败：网络错误";
        }
        if (m.contains("HTTP 5")) {
            return "发送失败：飞书服务异常（" + briefHttp(m) + "）";
        }
        return "发送失败：" + shortMsg(m);
    }

    private static String briefHttp(String m) {
        int i = m.indexOf("HTTP ");
        if (i < 0) return "HTTP ?";
        int end = Math.min(m.length(), i + 9);
        return m.substring(i, end).trim();
    }

    private static String shortMsg(String m) {
        if (m.length() <= 40) return m;
        return m.substring(0, 40) + "…";
    }

    private static String cutForLog(String s) {
        return cutForLog(s, 200);
    }

    private static String cutForLog(String s, int max) {
        if (s == null) return "null";
        String one = s.replace("\n", " ").replace("\r", " ").trim();
        one = one.replaceAll("\\s+", " ");
        if (one.length() <= max) return one;
        return one.substring(0, max) + "…(共" + s.length() + "字)";
    }

    /** 给界面用：最近一次状态文字 */
    public static String getLastStatus() {
        return lastStatus;
    }

    /** 给界面用：最近一次状态变化时间戳 */
    public static long getLastStatusTs() {
        return lastStatusTs;
    }

    // ==================== 状态查询 ====================
    public static boolean isSleeping() { return sleeping; }
    public static boolean isStarted() { return started; }
    public static int getSendIntervalSec() { return sendIntervalSec; }

    public static long getSleepRemainMs() {
        if (!sleeping) return 0;
        return Math.max(0, sleepEndTs - System.currentTimeMillis());
    }
}


