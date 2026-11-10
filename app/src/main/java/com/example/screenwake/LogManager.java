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
import java.util.List;
import java.util.Locale;

public final class LogManager {
    // 和Download平级的公共主目录 ScreenWakeLogs
    private static final String ROOT_RELATIVE_PATH = "ScreenWakeLogs/";
    // 子目录
    public static final String SUB_APP = "app/";
    public static final String SUB_UPLOAD = "upload/";
    public static final String SUB_COMMAND = "command/";

    private static final int MAX_SINGLE_FILE_LINES = 800;

    private LogManager() {}

    /**
     * 获取当日日志文件名 yyyy-MM-dd.log
     */
    private static String getTodayFileName() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date()) + ".log";
    }

    /**
     * 根据子目录，构造 MediaStore RELATIVE_PATH
     */
    private static String getMediaRelativePath(String subDir) {
        return ROOT_RELATIVE_PATH + subDir;
    }

    // >= Android 10 使用MediaStore查询日志Uri
    private static Uri findMediaUri(Context context, String subDir, String fileName) {
        Uri collection = MediaStore.Files.getContentUri("external");
        String relPath = getMediaRelativePath(subDir);
        String selection =
                MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " +
                        MediaStore.MediaColumns.RELATIVE_PATH + "=?";
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

    // >= Android10 创建日志文件
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

    // Android 9及以下：直接文件路径，公开存储根目录 ScreenWakeLogs
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
            // mode wt 覆盖写
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

    /**
     * 主日志，默认写入 app子目录
     */
    public static synchronized void log(Context context, String message) {
        log(context, SUB_APP, message);
    }

    /**
     * 指定子目录输出日志
     * @param subDir LogManager.SUB_APP / SUB_UPLOAD / SUB_COMMAND
     */
    public static synchronized void log(Context context, String subDir, String message) {
        if (context == null || message == null) return;
        try {
            List<String> lines = readLines(context, subDir);
            long now = System.currentTimeMillis();
            String time = new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss.SSS",
                    Locale.getDefault()
            ).format(new Date(now));
            String logLine = now + " | " + time + " | " + message.replace("\n", " ");
            lines.add(logLine);

            // 超过最大行数，删掉最旧
            while (lines.size() > MAX_SINGLE_FILE_LINES) {
                lines.remove(0);
            }
            writeAll(context, subDir, lines);
        } catch (Exception e) {
            android.util.Log.e("ScreenWake", "写日志失败", e);
        }
    }

    /**
     * 读取当日某个分类全部日志
     */
    public static synchronized String read(Context context, String subDir) {
        List<String> lines = readLines(context, subDir);
        StringBuilder sb = new StringBuilder();
        for (String s : lines) {
            sb.append(s).append('\n');
        }
        return sb.toString();
    }

    /**
     * 兼容旧调用，读app分类日志
     */
    public static synchronized String read(Context context) {
        return read(context, SUB_APP);
    }

    public static synchronized int getLineCount(Context context, String subDir) {
        return readLines(context, subDir).size();
    }

    public static synchronized int getLineCount(Context context) {
        return getLineCount(context, SUB_APP);
    }
                    }


