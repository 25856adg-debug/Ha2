package com.example.screenwake;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

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
    private static final String ROOT_RELATIVE_PATH = "ScreenWakeLogs/";
    public static final String SUB_APP = "app/";
    public static final String SUB_UPLOAD = "upload/";
    public static final String SUB_COMMAND = "command/";

    private static final String WHITELIST_SUB = "whitelist/";
    private static final String WHITELIST_FILENAME = "action_whitelist.txt";
    private static final String SERVER_ALLOW_FILENAME = "server_allow.txt";
    private static final String SERVER_TRUST_FILENAME = "server_trust.txt";

    private static final int MAX_SINGLE_FILE_LINES = 800;

    private LogManager() {}

    private static String getTodayFileName() {
        return new SimpleDateFormat("yyyy‑MM‑dd", Locale.getDefault()).format(new Date()) + ".log";
    }

    private static String getMediaRelativePath(String subDir) {
        return ROOT_RELATIVE_PATH + subDir;
    }

    private static Uri findMediaUri(Context context, String subDir, String fileName) {
        Uri collection = MediaStore.Files.getContentUri("external");
        String relPath = getMediaRelativePath(subDir);
        String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
        String[] args = {fileName, relPath};
        try (Cursor cursor = context.getContentResolver().query(
                collection,
                new String[]{MediaStore.MediaColumns._ID},
                selection,
                args,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(0);
                return Uri.withAppendedPath(collection, String.valueOf(id));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Uri createMediaFile(Context context, String subDir, String fileName) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, getMediaRelativePath(subDir));
        return context.getContentResolver().insert(
                MediaStore.Files.getContentUri("external"),
                values
        );
    }

    private static File getLegacyLogFile(Context context, String subDir, String fileName) throws Exception {
        File root = new File(Environment.getExternalStorageDirectory(), "ScreenWakeLogs");
        File targetSub = new File(root, subDir);
        if (!targetSub.exists() && !targetSub.mkdirs()) {
            throw new Exception("无法创建日志目录:" + targetSub.getAbsolutePath());
        }
        return new File(targetSub, fileName);
    }

    private static List<String> readLines(Context context, String subDir) {
        List<String> lines = new ArrayList<>();
        String fileName = getTodayFileName();
        try {
            InputStream input;
            if (Build.VERSION.SDK_INT >= 29) {
                Uri uri = findMediaUri(context, subDir, fileName);
                if (uri == null) return lines;
                input = context.getContentResolver().openInputStream(uri);
            } else {
                File file = getLegacyLogFile(context, subDir, fileName);
                if (!file.exists()) return lines;
                input = new FileInputStream(file);
            }
            if (input == null) return lines;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8)
            )) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
        } catch (Exception e) {
            android.util.Log.e("ScreenWake", "读取日志失败 sub:" + subDir, e);
        }
        return lines;
    }

    private static void writeAll(Context context, String subDir, List<String> lines) throws Exception {
        String fileName = getTodayFileName();
        OutputStream output;
        if (Build.VERSION.SDK_INT >= 29) {
            Uri uri = findMediaUri(context, subDir, fileName);
            if (uri == null) {
                uri = createMediaFile(context, subDir, fileName);
            }
            if (uri == null) {
                throw new Exception("无法创建媒体日志文件");
            }
            output = context.getContentResolver().openOutputStream(uri, "wt");
        } else {
            File file = getLegacyLogFile(context, subDir, fileName);
            output = new FileOutputStream(file, false);
        }
        if (output == null) {
            throw new Exception("日志输出流打开失败");
        }
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(output, StandardCharsets.UTF_8)
        )) {
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
        try {
            InputStream input;
            if (Build.VERSION.SDK_INT >= 29) {
                Uri uri = findMediaUri(context, WHITELIST_SUB, WHITELIST_FILENAME);
                if (uri == null) {
                    LogManager.log(context, SUB_COMMAND, "action_whitelist.txt不存在，拒绝全部远程指令");
                    return set;
                }
                input = context.getContentResolver().openInputStream(uri);
            } else {
                File root = new File(Environment.getExternalStorageDirectory(), "ScreenWakeLogs");
                File whitelistDir = new File(root, WHITELIST_SUB);
                File f = new File(whitelistDir, WHITELIST_FILENAME);
                if (!f.exists()) {
                    LogManager.log(context, SUB_COMMAND, "action_whitelist.txt不存在，拒绝全部远程指令");
                    return set;
                }
                input = new FileInputStream(f);
            }
            if (input == null) return set;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String trim = line.trim();
                    if (trim.isEmpty()) continue;
                    if (trim.startsWith("#")) continue;
                    set.add(trim);
                }
            }
        } catch (Exception e) {
            android.util.Log.e("ScreenWake", "读取action白名单异常", e);
            LogManager.log(context, SUB_COMMAND, "读取action_whitelist异常:" + e.getMessage());
        }
        return set;
    }

    // ========= server_allow.txt 读取IP/域名白名单 =========
    public static Set<String> readServerAllowList(Context context) {
        Set<String> set = new HashSet<>();
        if (context == null) return set;
        try {
            InputStream input;
            if (Build.VERSION.SDK_INT >= 29) {
                Uri uri = findMediaUri(context, WHITELIST_SUB, SERVER_ALLOW_FILENAME);
                if (uri == null) {
                    LogManager.log(context, SUB_COMMAND, "server_allow.txt不存在，IP校验直接失败");
                    return set;
                }
                input = context.getContentResolver().openInputStream(uri);
            } else {
                File root = new File(Environment.getExternalStorageDirectory(), "ScreenWakeLogs");
                File whitelistDir = new File(root, WHITELIST_SUB);
                File f = new File(whitelistDir, SERVER_ALLOW_FILENAME);
                if (!f.exists()) {
                    LogManager.log(context, SUB_COMMAND, "server_allow.txt不存在，IP校验直接失败");
                    return set;
                }
                input = new FileInputStream(f);
            }
            if (input == null) return set;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String trim = line.trim();
                    if (trim.isEmpty()) continue;
                    if (trim.startsWith("#")) continue;
                    set.add(trim);
                }
            }
        } catch (Exception e) {
            android.util.Log.e("ScreenWake", "读取server_allow异常", e);
            LogManager.log(context, SUB_COMMAND, "读取server_allow异常:" + e.getMessage());
        }
        return set;
    }

    // ========= server_trust.txt 读取受信任bindId =========
    public static String getTrustedServerBindId(Context context) {
        if (context == null) return null;
        try {
            InputStream input;
            if (Build.VERSION.SDK_INT >= 29) {
                Uri uri = findMediaUri(context, WHITELIST_SUB, SERVER_TRUST_FILENAME);
                if (uri == null) return null;
                input = context.getContentResolver().openInputStream(uri);
            } else {
                File root = new File(Environment.getExternalStorageDirectory(), "ScreenWakeLogs");
                File whitelistDir = new File(root, WHITELIST_SUB);
                File f = new File(whitelistDir, SERVER_TRUST_FILENAME);
                if (!f.exists()) return null;
                input = new FileInputStream(f);
            }
            if (input == null) return null;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line = br.readLine();
                if (line != null) {
                    String val = line.trim();
                    if (!val.isEmpty()) return val;
                }
            }
        } catch (Exception e) {
            android.util.Log.e("ScreenWake", "读取server_trust失败", e);
            LogManager.log(context, SUB_COMMAND, "读取server_trust异常:" + e.getMessage());
        }
        return null;
    }

    // ========= server_trust.txt 设置信任bindId；传null清空 =========
    public static boolean setTrustedServerBindId(Context context, String bindId) {
        if (context == null) return false;
        try {
            OutputStream output;
            if (Build.VERSION.SDK_INT >= 29) {
                Uri uri = findMediaUri(context, WHITELIST_SUB, SERVER_TRUST_FILENAME);
                if (uri == null) {
                    uri = createMediaFile(context, WHITELIST_SUB, SERVER_TRUST_FILENAME);
                }
                if (uri == null) return false;
                output = context.getContentResolver().openOutputStream(uri, "wt");
            } else {
                File root = new File(Environment.getExternalStorageDirectory(), "ScreenWakeLogs");
                File whitelistDir = new File(root, WHITELIST_SUB);
                if (!whitelistDir.exists()) whitelistDir.mkdirs();
                File f = new File(whitelistDir, SERVER_TRUST_FILENAME);
                output = new FileOutputStream(f, false);
            }
            if (output == null) return false;
            try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
                if (bindId != null && !bindId.trim().isEmpty()) {
                    bw.write(bindId.trim());
                } else {
                    bw.write("");
                }
            }
            LogManager.log(context, SUB_COMMAND, "更新受信任server_bind_id=" + (bindId == null ? "[清空]" : bindId));
            return true;
        } catch (Exception e) {
            android.util.Log.e("ScreenWake", "写入server_trust失败", e);
            LogManager.log(context, SUB_COMMAND, "写入server_trust异常:" + e.getMessage());
            return false;
        }
    }
}



