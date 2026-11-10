package com.example.screenwake;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 多路 WebSocket 接收端（默认 5 路，可扩展）
 *
 * ================= 协议约定（全程密文，无明文字节） =================
 * 1) 数据消息（用"当前密钥"加密）
 *      {"iv":"xxxx","cipher":"xxxx"}  或  ivBase64:cipherBase64
 *    解密后明文为：
 *      {"t":"data","d":{ ...任意JSON... }}     或  任意文本
 *
 * 2) 换钥指令（也用密文发送，用"引导密钥"加密，密钥不裸奔）
 *      {"iv":"xxxx","cipher":"xxxx"}
 *    解密后明文为：
 *      {"t":"key","k":"MyKey12345678901"}
 *    兼容明文 kk 写法：kkMyKey12345678901 / kk:MyKey12345678901
 *
 * ================= 三个文件 =================
 *   /sdcard/ScreenWake/wsdata/（优先，公共目录，文件管理器能直接打开）
 *   其次 Android/data/<包名>/files/wsdata/
 *
 *     key.txt        密钥文件：第一行=当前密钥，第二行=上一个密钥（最多两个）
 *     pending.txt    待解文件：暂时解不开的密文（时间|通道|密文）
 *     decrypted/     已解文件：每解出一条存一个 txt，如 ch1_20261110_191203.txt
 *
 * ================= 调用示例 =================
 *   String[] urls = new String[]{ "ws://1.2.3.4:8081/ws", "ws://5.6.7.8:8082/ws", "", "", "" };
 *   MultiWsReceiver.setUrls(this, urls);
 *   MultiWsReceiver.start(this);
 *   MultiWsReceiver.getStatus(0);
 *   MultiWsReceiver.stop();
 */
public final class MultiWsReceiver {

    private static final String TAG = "MultiWsReceiver";

    /** 通道数量，默认 5 路；想开更多把这里调大即可 */
    public static final int SLOTS = 5;

    private static final String PREF_NAME = "ScreenWakeWs";
    private static final String KEY_URL = "url_";
    private static final String KEY_AES = "aes_";
    private static final String KEY_MODE = "mode";

    /** 多连接模式：5 个地址各自建一条连接 */
    public static final String MODE_MULTI = "multi";
    /** 单连接模式：只连 url_1，消息前缀带通道号，如 1|iv:cipher、kk|2|密钥 */
    public static final String MODE_SINGLE = "single";

    /** 轮询模式：不开长连接，每 2 秒用 HTTP 拉一次新密文（最稳，推荐） */
    public static final String MODE_POLL = "poll";

    private static final String SUB = "ws/";

    private static final String DIR_NAME = "wsdata";

    /** 公共目录：/sdcard/ScreenWake/wsdata */
    private static final String PUB_DIR = "ScreenWake/wsdata";

    private static final WsClient[] CLIENTS = new WsClient[SLOTS];

    private static volatile PollClient POLL_CLIENT;
    private static final String[] STATUS = new String[SLOTS];

    private static volatile boolean running = false;
    private static File baseDir = null;
    private static Context appCtx = null;

    private MultiWsReceiver() {}

    static {
        for (int i = 0; i < SLOTS; i++) {
            STATUS[i] = "未启动";
        }
    }

    // ==================== 对外接口 ====================

    /** 保存 5 路地址（空字符串表示该路不启用） */
    public static void setUrls(Context ctx, String[] urls) {
        SharedPreferences p = pref(ctx);
        SharedPreferences.Editor ed = p.edit();
        for (int i = 0; i < SLOTS; i++) {
            String u = (urls != null && i < urls.length && urls[i] != null)
                    ? urls[i].trim() : "";
            ed.putString(KEY_URL + (i + 1), u);
        }
        ed.apply();
    }

    /** 读取 5 路地址 */
    public static String[] getUrls(Context ctx) {
        SharedPreferences p = pref(ctx);
        String[] out = new String[SLOTS];
        for (int i = 0; i < SLOTS; i++) {
            out[i] = p.getString(KEY_URL + (i + 1), "");
        }
        return out;
    }

    /** 设置模式：MODE_MULTI（5条连接）/ MODE_SINGLE（1条连接+通道前缀） */
    public static void setMode(Context ctx, String mode) {
        String m = MODE_POLL.equals(mode) ? MODE_POLL
                : (MODE_SINGLE.equals(mode) ? MODE_SINGLE : MODE_MULTI);
        pref(ctx).edit().putString(KEY_MODE, m).apply();
    }

    public static String getMode(Context ctx) {
        String m = pref(ctx).getString(KEY_MODE, MODE_MULTI);
        if (MODE_POLL.equals(m)) return MODE_POLL;
        return MODE_SINGLE.equals(m) ? MODE_SINGLE : MODE_MULTI;
    }

    /** 启动全部已配置的通道 */
    public static synchronized void start(Context ctx) {
        stop();
        appCtx = ctx.getApplicationContext();

        // 目录优先级：
        //   1) 公共目录 /sdcard/ScreenWake/wsdata —— 任何文件管理器都能直接看
        //   2) Android/data 私有目录（旧位置，文件管理器一般进不去）
        //   3) 应用内部存储（连外部存储都没有时）
        File oldDir = null;
        File ext = appCtx.getExternalFilesDir(null);
        if (ext != null) oldDir = new File(ext, DIR_NAME);

        File pub = new File(Environment.getExternalStorageDirectory(), PUB_DIR);
        if (ensureWritableDir(pub)) {
            baseDir = pub;
            migrateKey(oldDir, baseDir);
        } else if (ext != null && ensureWritableDir(oldDir)) {
            baseDir = oldDir;
            LogManager.log(appCtx, SUB, "公共目录不可写（缺全部文件访问权限？），退回私有目录");
        } else {
            baseDir = new File(appCtx.getFilesDir(), DIR_NAME);
            if (!ensureWritableDir(baseDir)) {
                Log.e(TAG, "创建目录失败：" + baseDir.getAbsolutePath());
            }
            LogManager.log(appCtx, SUB, "外部目录不可用，改用内部存储");
        }
        LogManager.log(appCtx, SUB, "数据目录=" + baseDir.getAbsolutePath());

        // 启动先补一次：用现有密钥/默认密钥把待解文件重解一遍
        new Thread(() -> {
            try {
                Thread.sleep(500);
                retryPending();
            } catch (Exception e) {
                Log.e(TAG, "启动重解待解文件失败", e);
            }
        }, "ws-retry-boot").start();

        String[] urls = getUrls(appCtx);
        running = true;

        boolean single = MODE_SINGLE.equals(getMode(appCtx));
        boolean poll = MODE_POLL.equals(getMode(appCtx));

        if (poll) {
            String u = normalizeWsUrl(urls[0]);
            if (u.isEmpty()) {
                STATUS[0] = "未配置地址";
                LogManager.log(appCtx, SUB, "轮询模式：第1路地址为空，未启动");
                return;
            }
            if (POLL_CLIENT != null) POLL_CLIENT.active = false;
            POLL_CLIENT = new PollClient(0, u);
            Thread pt = new Thread(POLL_CLIENT, "ScreenWake-POLL");
            pt.start();
            for (int i = 1; i < SLOTS; i++) {
                STATUS[i] = "轮询模式（由第1路统一接收）";
            }
            LogManager.log(appCtx, SUB, "已启动轮询模式：" + u);
            return;
        }

        int startedCount = 0;
        for (int i = 0; i < SLOTS; i++) {
            if (single && i > 0) {
                STATUS[i] = "单连接模式（由第1路统一接收）";
                continue;
            }
            String u = normalizeWsUrl(urls[i]);
            if (u.isEmpty()) {
                STATUS[i] = "未配置地址";
                continue;
            }
            WsClient c = new WsClient(i, u);
            CLIENTS[i] = c;
            startedCount++;
            Thread t = new Thread(c, "ScreenWake-WS-" + (i + 1));
            t.start();
        }

        LogManager.log(appCtx, SUB, "多路WS启动，共 " + startedCount + " 路，模式="
                + (single ? "单连接（通道前缀）" : "多连接")
                + "，目录=" + (baseDir == null ? "?" : baseDir.getAbsolutePath()));
    }

    /**
     * 地址容错：http 当 ws、https 当 wss，没写协议自动补 ws，
     * 路径为空或只有 "/" 时补成 /ws（服务端路由就是这么注册的）
     */
    private static String normalizeWsUrl(String raw) {
        String u = raw == null ? "" : raw.trim();
        if (u.isEmpty()) return "";

        String lower = u.toLowerCase(Locale.getDefault());
        if (lower.startsWith("http://")) {
            u = "ws://" + u.substring(7);
        } else if (lower.startsWith("https://")) {
            u = "wss://" + u.substring(8);
        } else if (!lower.startsWith("ws://") && !lower.startsWith("wss://")) {
            u = "ws://" + u;
        }

        int sep = u.indexOf("://");
        if (sep < 0) return u;
        String afterScheme = u.substring(sep + 3);
        int slash = afterScheme.indexOf('/');
        String base = slash >= 0 ? u.substring(0, sep + 3 + slash) : u;
        String path = slash >= 0 ? afterScheme.substring(slash) : "";

        if (path.isEmpty() || "/".equals(path)) {
            return base + "/ws";
        }
        return u;
    }

    /** 启动指定通道（urls 传进来的同时覆盖保存） */
    public static synchronized void start(Context ctx, String[] urls) {
        setUrls(ctx, urls);
        start(ctx);
    }

    /** 停止全部通道 */
    public static synchronized void stop() {
        running = false;
        PollClient pc = POLL_CLIENT;
        if (pc != null) {
            pc.active = false;
            POLL_CLIENT = null;
        }
        for (int i = 0; i < SLOTS; i++) {
            WsClient c = CLIENTS[i];
            if (c != null) {
                c.active = false;
                c.closeQuietly();
                CLIENTS[i] = null;
            }
            STATUS[i] = "已停止";
        }
        if (appCtx != null) {
            LogManager.log(appCtx, SUB, "多路WS已全部停止");
        }
    }

    public static boolean isRunning() {
        return running;
    }

    public static String getStatus(int slot) {
        if (slot < 0 || slot >= SLOTS) return "";
        return STATUS[slot];
    }

    public static String getAllStatus() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SLOTS; i++) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("第").append(i + 1).append("路：").append(STATUS[i]);
        }
        return sb.toString();
    }

    /** 有没有"所有文件访问权限"（Android 11+ 用 MANAGE_EXTERNAL_STORAGE） */
    public static boolean hasAllFilesAccess() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                return android.os.Environment.isExternalStorageManager();
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 跳到系统的"所有文件访问权限"页面。
     * 注意：AndroidManifest.xml 里必须声明
     *   <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE"/>
     * 否则这个开关在系统页面里是灰的、点不动。
     */
    public static void requestAllFilesAccess(Context ctx) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                android.content.Intent it = new android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                it.setData(android.net.Uri.parse("package:" + ctx.getPackageName()));
                it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(it);
            } else {
                android.content.Intent it = new android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                it.setData(android.net.Uri.parse("package:" + ctx.getPackageName()));
                it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(it);
            }
        } catch (Throwable t) {
            try {
                android.content.Intent it = new android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(it);
            } catch (Throwable ignored) {}
        }
    }

    /** 目录，方便打开查看文件 */
    public static String getDataDir() {
        return baseDir == null ? "" : baseDir.getAbsolutePath();
    }

    /** 密钥文件路径（全文件只保存一个密钥） */
    public static String getKeyPath() {
        return baseDir == null ? "" : keyFile().getAbsolutePath();
    }

    /** 待解文件路径 */
    public static String getPendingPath() {
        return baseDir == null ? "" : pendingFile().getAbsolutePath();
    }

    /** 已解文件目录（每条一个文件，会越积越多） */
    public static String getDecryptedDir() {
        return baseDir == null ? "" : decryptedDir().getAbsolutePath();
    }

    /** 直接在 App 里看已解文件：最近的若干条，每条截断到 maxChars 字 */
    public static String previewDecrypted(int maxFiles, int maxChars) {
        try {
            File d = decryptedDir();
            if (d == null || !d.exists()) return "（还没有已解文件）";
            File[] fs = d.listFiles();
            if (fs == null || fs.length == 0) return "（还没有已解文件）";

            java.util.Arrays.sort(fs, (a, b) -> b.getName().compareTo(a.getName()));
            StringBuilder sb = new StringBuilder();
            int n = Math.min(maxFiles, fs.length);
            for (int i = 0; i < n; i++) {
                String body = readText(fs[i]);
                if (body.length() > maxChars) body = body.substring(0, maxChars) + "…";
                sb.append("【").append(fs[i].getName()).append("】\n").append(body).append("\n\n");
            }
            sb.append("共 ").append(fs.length).append(" 个已解文件，上面显示最近 ").append(n).append(" 个\n");
            sb.append("目录：").append(d.getAbsolutePath());
            return sb.toString();
        } catch (Exception e) {
            return "读取失败：" + e.getMessage();
        }
    }

    /** 当前密钥（内部调试用，界面上不要展示） */
    public static String getKey() {
        return readKey();
    }

    /** 兼容写法：五路共用同一个密钥（界面不展示） */
    public static String getKey(int slot) {
        return readKey();
    }

    /** 待解条数 */
    public static int getPendingCount() {
        if (baseDir == null) return 0;
        File f = pendingFile();
        if (!f.exists()) return 0;
        int n = 0;
        for (String line : readAllLines(f)) {
            if (!line.trim().isEmpty()) n++;
        }
        return n;
    }

    /** 清空待解文件（返回清掉的条数） */
    public static int clearPending() {
        if (baseDir == null) return 0;
        int n = getPendingCount();
        try {
            File f = pendingFile();
            if (f.exists()) writeText(f, "");
            LogManager.log(appCtx, SUB, "待解文件已清空，共 " + n + " 条");
        } catch (Exception e) {
            Log.e(TAG, "清空待解文件失败", e);
        }
        return n;
    }

    /** 已解文件个数 */
    public static int getDecryptedCount() {
        if (baseDir == null) return 0;
        File d = decryptedDir();
        if (!d.exists()) return 0;
        String[] names = d.list();
        return names == null ? 0 : names.length;
    }

    // ==================== 文件结构（三种文件） ====================

    /**
     * 引导密钥：写死在 APK 里，永不变。
     * 换钥指令用它加密，接收端永远解得开，不会因为会话密钥换了而失联。
     * 长度必须 16 位。发送端要用同一个值。
     */
    public static final String BOOTSTRAP_KEY = "SwBootstrapKey01";

    /**
     * 内置默认会话密钥，和发射端 DEFAULT_KEY 一致。
     * 作用：还没收到换钥指令时也能直接解密，避免"收到一堆密文全解不开"。
     */
    public static final String DEFAULT_KEY = "xhdndhxbjdhhwhdh";

    /** 1) 密钥文件：全盘只有一个 key.txt，换钥直接覆盖，旧密钥即被删除 */
    private static File keyFile() {
        return new File(baseDir, "key.txt");
    }

    /** 2) 待解文件：解不开的密文都堆在这里 */
    private static File pendingFile() {
        return new File(baseDir, "pending.txt");
    }

    /** 3) 已解文件：每解出一条就单独存一个 txt */
    private static File decryptedDir() {
        File d = new File(baseDir, "decrypted");
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    /** 读取全部可用密钥：第一行=当前，第二行=上一个（最多两个） */
    private static List<String> readKeys() {
        List<String> out = new ArrayList<>();
        if (baseDir == null) return out;
        File f = keyFile();
        if (!f.exists()) return out;
        for (String line : readAllLines(f)) {
            String k = line.trim();
            if (k.isEmpty()) continue;
            if (!out.contains(k)) out.add(k);
            if (out.size() >= 2) break;
        }
        return out;
    }

    /** 当前密钥（第一行） */
    private static String readKey() {
        List<String> keys = readKeys();
        return keys.isEmpty() ? "" : keys.get(0);
    }

    /**
     * 写入新密钥：当前密钥顶到第一行，原当前密钥降到第二行当备用。
     * 最多只留两个，更早的直接丢弃。
     */
    private static void writeKey(String key) {
        if (key == null || key.isEmpty()) return;
        List<String> old = readKeys();

        List<String> now = new ArrayList<>();
        now.add(key);
        for (String k : old) {
            if (!k.equals(key) && now.size() < 2) now.add(k);
        }

        StringBuilder sb = new StringBuilder();
        for (String k : now) sb.append(k).append("\n");

        try {
            writeText(keyFile(), sb.toString());
            LogManager.log(appCtx, SUB, "密钥文件已更新（当前 + 备用共 "
                    + now.size() + " 个）");   // 日志不打印密钥内容
        } catch (Exception e) {
            Log.e(TAG, "写密钥文件失败", e);
        }
    }

    private static void appendPending(int slot, String payload) {
        try {
            String line = now() + "|" + (slot + 1) + "|"
                    + payload.replace("\r", " ").replace("\n", " ");
            File f = pendingFile();
            if (!f.exists()) {
                writeText(f, line + "\n");
            } else {
                appendText(f, line + "\n");
            }
        } catch (Exception e) {
            Log.e(TAG, "写待解文件失败", e);
        }
    }

    /** 保存一条已解明文，返回文件路径 */
    private static String saveDecrypted(int slot, String plain) {
        try {
            File d = decryptedDir();
            String name = "ch" + (slot + 1) + "_" + fileStamp() + ".txt";
            File f = new File(d, name);
            int i = 1;
            while (f.exists()) {
                f = new File(d, "ch" + (slot + 1) + "_" + fileStamp() + "_" + i + ".txt");
                i++;
            }
            writeText(f, plain);
            return f.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "写已解文件失败", e);
            return null;
        }
    }

    private static String fileStamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
    }

    // ==================== 消息处理 ====================

    private static void onMessage(int connSlot, String msg) {
        if (msg == null) return;
        String m = msg.trim();
        if (m.isEmpty()) return;

        try {
            int targetSlot = connSlot;
            String body = m;

            // 单连接模式：从消息前缀里取通道号
            if (appCtx != null && MODE_SINGLE.equals(getMode(appCtx))) {
                if (m.regionMatches(true, 0, "kk", 0, 2)) {
                    String rest = m.substring(2);
                    if (rest.startsWith("|")) rest = rest.substring(1);
                    int bar = rest.indexOf('|');
                    if (bar > 0) {
                        int ch = parseSlot(rest.substring(0, bar).trim());
                        if (ch >= 0) {
                            targetSlot = ch;
                            body = "kk" + rest.substring(bar + 1);
                        }
                    }
                } else {
                    int bar = m.indexOf('|');
                    if (bar > 0) {
                        int ch = parseSlot(m.substring(0, bar).trim());
                        if (ch >= 0) {
                            targetSlot = ch;
                            body = m.substring(bar + 1).trim();
                        }
                    }
                }
            }

            dispatch(targetSlot, body);
        } catch (Exception e) {
            Log.e(TAG, "处理消息异常 slot=" + (connSlot + 1), e);
            setStatus(connSlot, "处理消息异常：" + e.getMessage());
        }
    }

    /** 把 "1" / "ch1" / "channel1" 解析成 0 基下标，非法返回 -1 */
    private static int parseSlot(String s) {
        if (s == null) return -1;
        String t = s.toLowerCase(Locale.getDefault()).trim();
        if (t.startsWith("channel")) {
            t = t.substring(7).trim();
        } else if (t.startsWith("ch")) {
            t = t.substring(2).trim();
        }
        try {
            int n = Integer.parseInt(t);
            if (n >= 1 && n <= SLOTS) return n - 1;
        } catch (Exception ignored) {}
        return -1;
    }

    /**
     * 全程密文：能解开就处理，解不开就进待解文件。
     * 明文只可能有两种：
     *   {"t":"key","k":"..."}   换钥指令（本身也加密）
     *   {"t":"data","d":{...}}  业务数据
     */
    private static void dispatch(int slot, String payload) {
        String plain = tryDecrypt(payload);

        if (plain != null) {
            String newKey = extractKeyFromPlain(plain);
            if (newKey != null) {
                writeKey(newKey);
                setStatus(slot, "已接收到换钥指令，正在重解待解文件…");
                int ok = retryPending();
                setStatus(slot, "已接收到：换钥完成，新解出 " + ok + " 条");
                return;
            }

            saveDecrypted(slot, plain);
            setStatus(slot, "已接收到（解密成功）");
            return;
        }
  
      // 明文 kk 兼容（不推荐，但别丢）
        String t = payload == null ? "" : payload.trim();
        if (t.regionMatches(true, 0, "kk", 0, 2)) {
            String k = extractKeyFromPlain(t);
            if (k != null) {
                writeKey(k);
                int ok = retryPending();
                setStatus(slot, "已接收到（未加密密钥，新解出 " + ok + " 条）");
                return;
            }
        }

        appendPending(slot, payload);
        setStatus(slot, "解不开（已存待解）：" + triedSummary() + " 都试过了");
    }

    /** 不泄露密钥内容，只报告试了哪几把、各多少位 */
    private static String triedSummary() {
        StringBuilder sb = new StringBuilder();
        List<String> ks = readKeys();
        sb.append("key.txt").append(ks.size()).append("把");
        if (!ks.isEmpty()) sb.append("/").append(ks.get(0).length()).append("位");
        sb.append("、默认").append(DEFAULT_KEY.length()).append("位");
        sb.append("、引导").append(BOOTSTRAP_KEY.length()).append("位");
        return sb.toString();
    }

    /** 用"密钥文件(当前/备用) → 内置默认密钥 → 引导密钥"依次尝试解密 */
    private static String tryDecrypt(String payload) {
        for (String k : readKeys()) {
            String plain = decryptPayload(payload, k);
            if (plain != null) return plain;
        }
        String plain = decryptPayload(payload, DEFAULT_KEY);
        if (plain != null) return plain;
        return decryptPayload(payload, BOOTSTRAP_KEY);
    }

    /** 从明文里识别密钥指令，返回 16 位密钥或 null */
    private static String extractKeyFromPlain(String plain) {
        if (plain == null) return null;
        String t = plain.trim();
        if (t.isEmpty()) return null;

        if (t.regionMatches(true, 0, "kk", 0, 2)) {
            String body = t.substring(2).trim();
            while (body.startsWith(":") || body.startsWith("=")) {
                body = body.substring(1).trim();
            }
            if (body.startsWith("{")) {
                try {
                    JSONObject o = new JSONObject(body);
                    String k = o.optString("key", o.optString("k", o.optString("aes", "")));
                    if (!k.isEmpty()) body = k.trim();
                } catch (Exception ignored) {}
            }
            return body.length() >= 16 ? body.substring(0, 16) : null;
        }

        if (t.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(t);
                String type = o.optString("t", o.optString("type", ""));
                String k = o.optString("k", o.optString("key", ""));
                if ("key".equalsIgnoreCase(type) && k.length() >= 16) {
                    return k.substring(0, 16);
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    // ==================== 待解文件重解 ====================

    /** 用当前密钥把待解文件里能解的都解出来，解出后从待解文件删除 */
    private static int retryPending() {
        File f = pendingFile();
        if (!f.exists()) return 0;

        List<String> all = readAllLines(f);
        List<String> remain = new ArrayList<>();
        int ok = 0;

        for (String line : all) {
            String s = line.trim();
            if (s.isEmpty()) continue;

            String[] parts = s.split("\\|", 3);
            if (parts.length < 3) {
                remain.add(s);
                continue;
            }
            int slot = 0;
            try {
                slot = Math.max(0, Math.min(SLOTS - 1, Integer.parseInt(parts[1]) - 1));
            } catch (Exception ignored) {}

            String plain = tryDecrypt(parts[2]);
            if (plain == null) {
                remain.add(s);
                continue;
            }
            if (extractKeyFromPlain(plain) != null) {
                ok++;   // 密钥指令，直接丢弃
                continue;
            }
            saveDecrypted(slot, plain);
            ok++;
        }

        try {
            rewritePending(remain);
        } catch (Exception e) {
            Log.e(TAG, "重写待解文件失败", e);
        }
        LogManager.log(appCtx, SUB, "待解文件重解完成：" + ok + " 条成功，剩余 "
                + remain.size() + " 条");
        return ok;
    }

    private static void rewritePending(List<String> lines) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append("\n");
        }
        if (sb.length() == 0) {
            writeText(pendingFile(), "");
        } else {
            writeText(pendingFile(), sb.toString());
        }
    }

    // ==================== 载荷解析与解密 ====================

    /**
     * 支持：
     *   {"iv":"...","cipher":"..."}
     *   ivBase64:cipherBase64
     * 返回 [iv, cipher]，解析失败返回 null
     */
    private static String[] parsePayload(String payload) {
        if (payload == null) return null;
        String p = payload.trim();

        // 去掉可能的 data: 前缀
        if (p.regionMatches(true, 0, "data:", 0, 5)) {
            p = p.substring(5).trim();
        }

        if (p.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(p);
                String iv = o.optString("iv", "");
                String cipher = o.optString("cipher", o.optString("data", o.optString("ct", "")));
                if (!iv.isEmpty() && !cipher.isEmpty()) {
                    return new String[]{iv, cipher};
                }
            } catch (Exception ignored) {}
            return null;
        }

        int sep = p.indexOf(':');
        if (sep > 0) {
            String iv = p.substring(0, sep).trim();
            String cipher = p.substring(sep + 1).trim();
            if (!iv.isEmpty() && !cipher.isEmpty()) {
                return new String[]{iv, cipher};
            }
        }
        return null;
    }

    /** 解密失败返回 null */
    private static String decryptPayload(String payload, String key16) {
        try {
            String[] parts = parsePayload(payload);
            if (parts == null) return null;

            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] data = Base64.decode(parts[1], Base64.NO_WRAP);

            SecretKeySpec keySpec = new SecretKeySpec(
                    key16.getBytes(StandardCharsets.UTF_8), "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            byte[] plain = cipher.doFinal(data);

            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "解密失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
            return null;
        }
    }

    // ==================== 基础读写 ====================

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date());
    }

    /** 目录能不能建、能不能写（写一个探针文件再删），不行就别用 */
    private static boolean ensureWritableDir(File d) {
        try {
            if (d == null) return false;
            if (!d.exists() && !d.mkdirs()) return false;
            File probe = new File(d, ".w_test");
            // 真写一个字节去验证，写不进去就是没权限
            try (FileOutputStream fos = new FileOutputStream(probe)) {
                fos.write('1');
            }
            probe.delete();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 换目录时把旧 key.txt 带过去，免得密钥丢了要重新换钥 */
    private static void migrateKey(File oldDir, File newDir) {
        try {
            if (oldDir == null || newDir == null) return;
            File src = new File(oldDir, "key.txt");
            File dst = new File(newDir, "key.txt");
            if (src.exists() && !dst.exists()) {
                String content = readText(src);
                if (content != null && !content.trim().isEmpty()) {
                    writeText(dst, content);
                    LogManager.log(appCtx, SUB, "已把旧 key.txt 迁到新的公共目录");
                }
            }
        } catch (Exception ignored) {}
    }

    /** 读整个小文件（UTF-8），不存在返回空串 */
    private static String readText(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static void writeText(File f, String content) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(f, false)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        }
    }

    private static void appendText(File f, String content) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(f, true)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        }
    }

    private static List<String> readAllLines(File f) {
        List<String> out = new ArrayList<>();
        if (f == null || !f.exists()) return out;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                out.add(line);
            }
        } catch (Exception e) {
            Log.e(TAG, "读文件失败：" + f.getName(), e);
        }
        return out;
    }

    // ==================== 状态 ====================

    private static void setStatus(int slot, String s) {
        if (slot >= 0 && slot < SLOTS) {
            STATUS[slot] = s;
        }
    }

    private static SharedPreferences pref(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    // ==================== WebSocket 客户端（零依赖实现） ====================

    /**
     * 轮询客户端：不依赖长连接，每 2 秒用 HTTP 拉一次新密文。
     * 服务端 /api/pull?ch=1&since=N 返回三部分：
     *   第1行 key:<加密换钥指令>
     *   第2行 last:<最新id>
     *   其余为一条条密文
     */
    private static final class PollClient implements Runnable {

        final int slot;
        final String baseUrl;
        volatile boolean active = true;
        private long since = 0L;

        PollClient(int slot, String wsUrl) {
            this.slot = slot;
            this.baseUrl = toHttpBase(wsUrl);
            this.since = readLastId();
        }

        /** last id 落盘，App 重启后接着上次的 id 拉，不会把老消息重解一遍 */
        private long readLastId() {
            try {
                File f = new File(baseDir, "last_id.txt");
                if (!f.exists()) return 0L;
                return Long.parseLong(readText(f).trim());
            } catch (Exception e) {
                return 0L;
            }
        }

        private void saveLastId(long id) {
            try {
                writeText(new File(baseDir, "last_id.txt"), String.valueOf(id));
            } catch (Exception ignored) {}
        }

        /** ws://h:p/ws → http://h:p/api/pull （长轮询，wait=15 秒） */
        private static String toHttpBase(String u) {
            String s = u;
            String lower = s.toLowerCase(Locale.getDefault());
            if (lower.startsWith("ws://")) {
                s = "http://" + s.substring(5);
            } else if (lower.startsWith("wss://")) {
                s = "https://" + s.substring(6);
            }
            int slash = s.indexOf('/', 8);
            if (slash > 0) s = s.substring(0, slash);
            return s + "/api/pull?ch=1&wait=15&since=";
        }

        @Override
        public void run() {
            setStatus(slot, "长轮询中（有消息立即下发）");
            while (running && active) {
                try {
                    pollOnce();
                } catch (Throwable t) {
                    if (!active || !running) break;
                    setStatus(slot, "轮询失败：" + t.getClass().getSimpleName()
                            + "，1秒后重试");
                    Log.w(TAG, "轮询失败：" + t.getMessage());
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            setStatus(slot, "已停止");
        }

        private void pollOnce() throws Exception {
            java.net.URL url = new java.net.URL(baseUrl + since);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("Cache-Control", "no-cache");
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);

            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            String keyLine = null;
            String lastLine = null;
            String line;
            int got = 0;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("key:")) {
                    keyLine = line.substring(4).trim();
                } else if (line.startsWith("last:")) {
                    lastLine = line.substring(5).trim();
                } else if (!line.trim().isEmpty()) {
                    String t = line.trim();
                    String body = t;
                    long id = -1L;
                    if (t.startsWith("d")) {
                        int c = t.indexOf(':');
                        if (c > 1) {
                            try {
                                id = Long.parseLong(t.substring(1, c));
                                body = t.substring(c + 1);
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                    if (id >= 0 && id <= since) continue;   // 去重：老消息不再解一遍
                    dispatch(slot, body);
                    got++;
                    if (id > since) {
                        since = id;
                        saveLastId(since);
                    }
                }
            }
            br.close();
            conn.disconnect();

            if (lastLine != null) {
                try {
                    long last = Long.parseLong(lastLine);
                    if (last > since) {
                        since = last;
                        saveLastId(since);
                    }
                } catch (NumberFormatException ignored) {}
            }
            if (keyLine != null && !keyLine.isEmpty()) {
                dispatch(slot, keyLine);
            }
            if (got > 0) {
                setStatus(slot, "已接收到 " + got + " 条（轮询）");
            }
        }
    }

    private static final class WsClient implements Runnable {

        private static final String KEY_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

        final int slot;
        final String url;
        volatile boolean active = true;

        private volatile Socket socket;
        private volatile OutputStream out;

        WsClient(int slot, String url) {
            this.slot = slot;
            this.url = url;
        }

        @Override
        public void run() {
            long backoff = 3000L;
            while (running && active) {
                try {
                    setStatus(slot, "连接中：" + url);
                    connectAndRead();
                    backoff = 3000L;
                } catch (Exception e) {
                    if (!active || !running) break;
                    setStatus(slot, "连接断开：" + e.getClass().getSimpleName());
                    Log.w(TAG, "第" + (slot + 1) + "路连接异常：" + e.getMessage());
                } finally {
                    closeQuietly();
                }

                if (!running || !active) break;

                setStatus(slot, "等待重连（" + (backoff / 1000) + "秒后）");
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (backoff < 30000L) backoff += 3000L;
            }
            setStatus(slot, "已停止");
        }

        private void connectAndRead() throws Exception {
            // 1. 解析地址
            String lower = url.toLowerCase(Locale.getDefault());
            String scheme;
            String rest;
            if (lower.startsWith("wss://")) {
                scheme = "wss";
                rest = url.substring(6);
            } else if (lower.startsWith("ws://")) {
                scheme = "ws";
                rest = url.substring(5);
            } else {
                scheme = "ws";
                rest = url;
            }

            String hostPort = rest;
            String path = "/";
            int slash = rest.indexOf('/');
            if (slash >= 0) {
                hostPort = rest.substring(0, slash);
                path = rest.substring(slash);
            }

            String host;
            int port;
            int colon = hostPort.lastIndexOf(':');
            if (colon > 0) {
                host = hostPort.substring(0, colon);
                port = Integer.parseInt(hostPort.substring(colon + 1));
            } else {
                host = hostPort;
                port = "wss".equals(scheme) ? 443 : 80;
            }

            // 2. 建连接
            Socket plain = new Socket();
            plain.connect(new InetSocketAddress(host, port), 8000);
            plain.setSoTimeout(0);
            plain.setKeepAlive(true);

            Socket sock;
            if ("wss".equals(scheme)) {
                SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
                SSLSocket ssl = (SSLSocket) f.createSocket(plain, host, port, true);
                ssl.startHandshake();
                sock = ssl;
            } else {
                sock = plain;
            }
            socket = sock;

            // 3. 握手
            String key = newKeyB64();
            String req = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + hostPort + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "\r\n";

            OutputStream os = sock.getOutputStream();
            out = os;
            os.write(req.getBytes(StandardCharsets.UTF_8));
            os.flush();

            DataInputStream in = new DataInputStream(sock.getInputStream());

            // 读响应头
            String line;
            String firstLine = null;
            StringBuilder head = new StringBuilder();
            while ((line = readLine(in)) != null) {
                if (firstLine == null) firstLine = line;
                if (line.isEmpty()) break;
                head.append(line).append("\n");
            }

            if (firstLine == null || !firstLine.contains("101")) {
                throw new Exception("握手失败：" + (firstLine == null ? "无响应" : firstLine));
            }

            setStatus(slot, "已连接，等待数据");
            LogManager.log(appCtx, SUB, "第" + (slot + 1) + "路 WebSocket 已连接：" + url);

            // 4. 收帧
            StringBuilder frag = new StringBuilder();

            while (running && active) {
                int b0 = in.read();
                if (b0 < 0) throw new Exception("连接被关闭");

                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;

                int b1 = in.read();
                if (b1 < 0) throw new Exception("连接被关闭");

                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;

                if (len == 126) {
                    len = readUShort(in);
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) {
                        len = (len << 8) | (in.read() & 0xFF);
                    }
                }

                byte[] mask = new byte[4];
                if (masked) {
                    in.readFully(mask);
                }

                if (len > 8_000_000L) throw new Exception("帧过大：" + len);

                byte[] payload = new byte[(int) len];
                in.readFully(payload);
                if (masked) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] = (byte) (payload[i] ^ mask[i % 4]);
                    }
                }

                if (opcode == 0x8) {           // close
                    throw new Exception("服务端主动关闭");
                } else if (opcode == 0x9) {    // ping -> pong
                    sendFrame(0xA, payload);
                } else if (opcode == 0xA) {    // pong
                    // ignore
                } else if (opcode == 0x1 || opcode == 0x2 || opcode == 0x0) {
                    frag.append(new String(payload, StandardCharsets.UTF_8));
                    if (fin) {
                        String msg = frag.toString();
                        frag.setLength(0);
                        if (!msg.trim().isEmpty()) {
                            // 单条消息处理异常绝不能把整条连接带下去
                            try {
                                onMessage(slot, msg);
                            } catch (Throwable t) {
                                Log.w(TAG, "处理消息失败（连接保持）：" + t.getClass().getSimpleName()
                                        + " " + t.getMessage());
                                setStatus(slot, "已接收到（处理异常，已跳过该条）");
                            }
                        }
                    }
                }
            }
        }

        private void sendFrame(int opcode, byte[] data) throws Exception {
            OutputStream os = out;
            if (os == null) return;
            int len = data.length;
            byte[] frame;
            byte[] mask = new byte[4];
            new SecureRandom().nextBytes(mask);

            if (len < 126) {
                frame = new byte[2 + 4 + len];
                frame[0] = (byte) (0x80 | opcode);
                frame[1] = (byte) (0x80 | len);
                System.arraycopy(mask, 0, frame, 2, 4);
                for (int i = 0; i < len; i++) {
                    frame[6 + i] = (byte) (data[i] ^ mask[i % 4]);
                }
            } else if (len < 65536) {
                frame = new byte[2 + 2 + 4 + len];
                frame[0] = (byte) (0x80 | opcode);
                frame[1] = (byte) (0x80 | 126);
                frame[2] = (byte) ((len >> 8) & 0xFF);
                frame[3] = (byte) (len & 0xFF);
                System.arraycopy(mask, 0, frame, 4, 4);
                for (int i = 0; i < len; i++) {
                    frame[8 + i] = (byte) (data[i] ^ mask[i % 4]);
                }
            } else {
                frame = new byte[2 + 8 + 4 + len];
                frame[0] = (byte) (0x80 | opcode);
                frame[1] = (byte) (0x80 | 127);
                long l = len;
                for (int i = 0; i < 8; i++) {
                    frame[2 + i] = (byte) ((l >> (8 * (7 - i))) & 0xFF);
                }
                System.arraycopy(mask, 0, frame, 10, 4);
                for (int i = 0; i < len; i++) {
                    frame[14 + i] = (byte) (data[i] ^ mask[i % 4]);
                }
            }
            synchronized (WsClient.class) {
                os.write(frame);
                os.flush();
            }
        }

        private static int readUShort(DataInputStream in) throws Exception {
            int a = in.read();
            int b = in.read();
            return ((a & 0xFF) << 8) | (b & 0xFF);
        }

        private static String readLine(DataInputStream in) throws Exception {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) >= 0) {
                if (c == '\n') break;
                if (c != '\r') sb.append((char) c);
                if (sb.length() > 4096) break;
            }
            if (c < 0 && sb.length() == 0) return null;
            return sb.toString();
        }

        private static String newKeyB64() {
            byte[] k = new byte[16];
            new SecureRandom().nextBytes(k);
            return Base64.encodeToString(k, Base64.NO_WRAP);
        }

        void closeQuietly() {
            try {
                if (socket != null) socket.close();
            } catch (Exception ignored) {}
            socket = null;
            out = null;
        }
    }
}
