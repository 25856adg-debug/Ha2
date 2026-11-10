package com.example.screenwake;

import android.content.Context;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class LogManager {
    public static final String SUB_APP = "app/";
    public static final String SUB_UPLOAD = "upload/";
    public static final String SUB_COMMAND = "command/";
    private static final String WHITELIST_SUB = "whitelist/";

    private static final String WHITELIST_FILENAME = "action_whitelist.txt";
    private static final String SERVER_ALLOW_FILENAME = "server_allow.txt";
    private static final String SERVER_TRUST_FILENAME = "server_trust.txt";

    private static final int MAX_SINGLE_FILE_LINES = 800;

    private LogManager() {}

    // 获取根目录： /Android/data/com.example.screenwake/files/ScreenWakeLogs/
    private static File getRootDir(Context context) {
        File base = context.getExternalFilesDir(null);
        File root = new File(base, "ScreenWakeLogs");
        if (!root.exists()) root.mkdirs();
        return root;
    }

    private static File getSubDir(Context context, String subDir) {
        File sub = new File(getRootDir(context), subDir);
        if (!sub.exists()) sub.mkdirs();
        return sub;
    }

    private static String getTodayFileName() {
        return new SimpleDateFormat("yyyy‑MM‑dd", Locale.getDefault()).format(new Date()) + ".log";
    }

    private static File getLogFile(Context context, String subDir) {
        return new File(getSubDir(context, subDir), getTodayFileName());
    }

    private static File getWhitelistFile(Context context, String fileName) {
        return new File(getSubDir(context, WHITELIST_SUB), fileName);
    }

    private static List<String> readLines(Context context, String subDir) {
        List<String> lines = new ArrayList<>();
        File file = getLogFile(context, subDir);
        if (!file.exists()) return lines;
        try (InputStream input = new FileInputStream(file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (Exception e) {
            android.util.Log.e("ScreenWake", "读取日志失败 sub:" + subDir, e);
        }
        return lines;
    }

    private static void writeAll(Context context, String subDir, List<String> lines) throws Exception {
        File file = getLogFile(context, subDir);
        try (OutputStream out = new FileOutputStream(file, false);
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
            for (String line : lines) {
                writer.write(line);
                writer.newLine();
            }
        }
    }

    public static synchronized void log(Context context, String message) {
        log(context, SUB_APP, message);
    }

    public static synchronized void log(Context context, String subDir, String message) {
        if (context == null || message == null) return;
        try {
            List<String> lines = readLines(context, subDir);
            long now = System.currentTimeMillis();
            String time = new SimpleDateFormat(
                    "yyyy‑MM‑dd HH:mm:ss.SSS",
                    Locale.getDefault()
            ).format(new Date(now));
            String logLine = now + " | " + time + " | " + message.replace("\n", " ");
            lines.add(logLine);

            while (lines.size() > MAX_SINGLE_FILE_LINES) {
                lines.remove(0);
            }
            writeAll(context, subDir, lines);
        } catch (Exception e) {
            android.util.Log.e("ScreenWake", "写日志失败", e);
        }
    }

    public static synchronized String read(Context context, String subDir) {
        List<String> lines = readLines(context, subDir);
        StringBuilder sb = new StringBuilder();
        for (String s : lines) {
            sb.append(s).append('\n');
        }
        return sb.toString();
    }

    public static synchronized String read(Context context) {
        return read(context, SUB_APP);
    }

    public static synchronized int getLineCount(Context context, String subDir) {
        return readLines(context, subDir).size();
    }

    public static synchronized int getLineCount(Context context) {
        return getLineCount(context, SUB_APP);
    }

    // ========= action_whitelist.txt 读取指令白名单 =========
    public static Set<String> readActionWhitelist(Context context) {
        Set<String> set = new HashSet<>();
        if (context == null) return set;
        File f = getWhitelistFile(context, WHITELIST_FILENAME);
        if (!f.exists()) {
            log(context, SUB_COMMAND, "action_whitelist.txt不存在，拒绝全部远程指令");
            return set;
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String trim = line.trim();
                if (trim.isEmpty()) continue;
                if (trim.startsWith("#")) continue;
                set.add(trim);
            }
        } catch (Exception e) {
            android.util.Log.e("ScreenWake", "读取action白名单异常", e);
            log(context, SUB_COMMAND, "读取action_whitelist异常:" + e.getMessage());
        }
        return set;
    }

    // ========= server_allow.txt 读取IP/域名白名单 =========
    public static Set<String> readServerAllowList(Context context) {
        Set<String> set = new HashSet<>();
        if (context == null) return set;
        File f = getWhitelistFile(context, SERVER_ALLOW_FILENAME);
        if (!f.exists()) {
            log(context, SUB_COMMAND, "server_allow.txt不存在，IP校验直接失败");
            return set;
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String trim = line.trim();
                if (trim.isEmpty()) continue;
                if (trim.startsWith("#")) continue;
                set.add(trim);
            }
        } catch (Exception e) {
            android.util.Log.e("ScreenWake", "读取server_allow异常", e);
            log(context, SUB_COMMAND, "读取server_allow异常:" + e.getMessage());
        }
        return set;
    }

    // ========= server_trust.txt 读取受信任bindId =========
    public static String getTrustedServerBindId(Context context) {
        if (context == null) return null;
        File f = getWhitelistFile(context, SERVER_TRUST_FILENAME);
        if (!f.exists()) return null;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line = br.readLine();
            if (line != null) {
                String val = line.trim();
                if (!val.isEmpty()) return val;
            }
        } catch (Exception e) {
            android.util.Log.e("ScreenWake", "读取server_trust失败", e);
            log(context, SUB_COMMAND, "读取server_trust异常:" + e.getMessage());
        }
        return null;
    }

    // ========= server_trust.txt 设置信任bindId；传null清空 =========
    public static boolean setTrustedServerBindId(Context context, String bindId) {
        if (context == null) return false;
        File f = getWhitelistFile(context, SERVER_TRUST_FILENAME);
        try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f, false), StandardCharsets.UTF_8))) {
            if (bindId != null && !bindId.trim().isEmpty()) {
                bw.write(bindId.trim());
            } else {
                bw.write("");
            }
            log(context, SUB_COMMAND, "更新受信任server_bind_id=" + (bindId == null ? "[清空]" : bindId));
            return true;
        } catch (Exception e) {
            android.util.Log.e("ScreenWake", "写入server_trust失败", e);
            log(context, SUB_COMMAND, "写入server_trust异常:" + e.getMessage());
            return false;
        }
    }
}
        <?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- 网络 -->
    <uses-permission android:name="android.permission.INTERNET"/>
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>

    <!-- Wi‑Fi -->
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>

    <!-- 蓝牙 -->
    <uses-permission android:name="android.permission.BLUETOOTH"/>
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>

    <!-- 开机启动 -->
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>

    <!-- 亮屏 -->
    <uses-permission android:name="android.permission.WAKE_LOCK"/>

    <!-- 前台服务 -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>

    <!-- 通知 -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>

    <!-- 其他权限 -->
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"/>

    <!-- 存储相关 -->
    <uses‑permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
    <uses‑permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32"/>
    <uses‑permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="32"/>

    <!-- 相机权限 -->
    <uses‑permission android:name="android.permission.CAMERA"/>

    <application
        android:allowBackup="false"
        android:label="接收器"
        android:theme="@android:style/Theme.Material.Light.NoActionBar"
        android:usesCleartextTraffic="true">

        <!-- 主界面 -->
        <activity
            android:name=".MainActivity"
            android:exported="true">

            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>

        </activity>

        <!-- 浏览器 -->
        <activity
            android:name=".BrowserActivity"
            android:exported="false"
            android:configChanges="keyboard|keyboardHidden|orientation|screenSize"/>

        <!-- 设置 -->
        <activity
            android:name=".SettingsActivity"
            android:exported="false"/>

        <!-- 亮屏图案 -->
        <activity
            android:name=".PatternActivity"
            android:exported="false"/>

        <!-- 开机接收器 -->
        <receiver
            android:name=".BootReceiver"
            android:enabled="true"
            android:exported="true">

            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED"/>
            </intent-filter>

        </receiver>

        <!-- 定时任务接收器 -->
        <receiver
            android:name=".TaskReceiver"
            android:enabled="true"
            android:exported="false">

            <intent-filter>
                <action android:name="com.example.screenwake.ACTION_TIMER"/>
            </intent-filter>

        </receiver>

        <!-- 原有亮屏服务 -->
        <service
            android:name=".ScreenWakeService"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="specialUse">

            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Brief post‑boot screen wake attempt and visible five‑minute display"/>

        </service>

        <!-- 新增设备任务服务 -->
        <service
            android:name=".CycleService"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="dataSync"/>

    </application>
</manifest>
            


