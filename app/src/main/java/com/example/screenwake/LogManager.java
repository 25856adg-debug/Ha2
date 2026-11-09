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

    private static final String FILE_NAME = "alarm.log";
    private static final int MAX_LINES = 500;

    private LogManager() {}

    private static Uri findFile(Context context) {

        Uri collection = MediaStore.Files.getContentUri("external");

        String selection =
                MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " +
                MediaStore.MediaColumns.RELATIVE_PATH + "=?";

        String[] args = {
                FILE_NAME,
                Environment.DIRECTORY_MUSIC + "/"
        };

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

        } catch (Exception ignored) {}

        return null;
    }

    private static Uri createFile(Context context) throws Exception {

        ContentValues values = new ContentValues();

        values.put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_MUSIC + "/"
        );

        return context.getContentResolver().insert(
                MediaStore.Files.getContentUri("external"),
                values
        );
    }

    private static List<String> readLines(Context context) {

        List<String> lines = new ArrayList<>();

        Uri uri = findFile(context);

        if (uri == null) return lines;

        try (InputStream in =
                     context.getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {

            String line;

            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }

        } catch (Exception ignored) {}

        return lines;
    }

    private static void writeAll(
            Context context,
            List<String> lines
    ) throws Exception {

        Uri uri = findFile(context);

        if (uri == null) {
            uri = createFile(context);
        }

        if (uri == null) {
            throw new Exception("无法创建日志文件");
        }

        try (OutputStream out =
                     context.getContentResolver().openOutputStream(uri, "wt");
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(out, StandardCharsets.UTF_8))) {

            for (String line : lines) {
                writer.write(line);
                writer.newLine();
            }
        }
    }

    public static synchronized void log(
            Context context,
            String message
    ) {

        if (context == null || message == null) return;

        try {

            List<String> lines = readLines(context);

            long now = System.currentTimeMillis();

            String time = new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss.SSS",
                    Locale.getDefault()
            ).format(new Date(now));

            lines.add(now + " | " + time + " | " +
                    message.replace("\n", " "));

            while (lines.size() > MAX_LINES) {
                lines.remove(0);
            }

            writeAll(context, lines);

        } catch (Exception e) {
            android.util.Log.e(
                    "ScreenWake",
                    "日志写入失败",
                    e
            );
        }
    }

    public static synchronized String read(Context context) {

        List<String> lines = readLines(context);

        StringBuilder result = new StringBuilder();

        for (String line : lines) {
            result.append(line).append('\n');
        }

        return result.toString();
    }

    public static synchronized int getLineCount(Context context) {
        return readLines(context).size();
    }
}
