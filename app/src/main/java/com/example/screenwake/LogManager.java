package com.example.screenwake;

import android.content.Context;
import android.os.Environment;
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
    
    // 单文件最大行数，超过后自动清理旧日志（防止文件无限变大）
    private static final int MAX_SINGLE_FILE_LINES = 2000;

    private LogManager() {}

    /**
     * 获取日志根目录
     * 优先尝试保存在公共目录：/storage/emulated/0/ScreenWake/log/
     * 如果没有权限，则回退到应用私有目录
     */
    private static File getRootDir(Context context) {
        // 1. 尝试使用公共存储目录 (需要获取"所有文件访问权限")
        File publicRoot = new File(Environment.getExternalStorageDirectory(), "ScreenWake/log");
        if (publicRoot.exists() || publicRoot.mkdirs()) {
            return publicRoot;
        }

        // 2. 回退到应用私有目录 (Android/data/包名/files/ScreenWake/log)
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            android.util.Log.e("LogManager", "获取外部存储失败，ROM限制！");
            return null;
        }
        File privateRoot = new File(base, "ScreenWake/log");
        if (!privateRoot.exists()) {
            privateRoot.mkdirs();
        }
        return privateRoot;
    }

    private static File getSubDir(Context context, String subDir) {
        File root = getRootDir(context);
        if(root == null) return null;
        File sub = new File(root, subDir);
        if (!sub.exists()) {
            sub.mkdirs();
        }
        return sub;
    }

    private static String getTodayFileName() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date()) + ".log";
    }

    private static File getLogFile(Context context, String subDir) {
        File sub = getSubDir(context, subDir);
        if(sub == null) return null;
        return new File(sub, getTodayFileName());
    }

    private static File getWhitelistFile(Context context, String fileName) {
        File sub = getSubDir(context, WHITELIST_SUB);
        if(sub == null) return null;
        return new File(sub, fileName);
    }

    private static List<String> readLines(Context context, String subDir) throws Exception {
        List<String> lines = new ArrayList<>();
        File file = getLogFile(context, subDir);
        if (file == null) throw new Exception("获取日志文件失败，目录初始化异常");
        if (!file.exists()) return lines;
        
        try (InputStream input = new FileInputStream(file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static void writeAll(Context context, String subDir, List<String> lines) throws Exception {
        File file = getLogFile(context, subDir);
        if(file == null) throw new Exception("获取日志文件失败，目录初始化异常");
        
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

    /**
     * 核心日志写入方法：写详细的中文日志
     */
    public static synchronized void log(Context context, String subDir, String message) {
        if (context == null || message == null) return;
        try {
            File file = getLogFile(context, subDir);
            if (file == null) return;

            // 获取当前线程名，方便排查是哪个线程打印的
            String threadName = Thread.currentThread().getName();
            
            // 详细时间格式：2023-11-11 11:08:22.123
            long now = System.currentTimeMillis();
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date(now));

            // 拼接详细日志格式：[时间] [线程] | 内容
            String logLine = "[" + time + "] [" + threadName + "] | " + message.replace("\n", " ");

            // 用追加模式写入，性能更好
            try (OutputStream out = new FileOutputStream(file, true);
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
                writer.write(logLine);
                writer.newLine();
            }

            // 检查文件行数，如果超过上限则触发裁剪（为了性能，只在特定情况触发）
            // 这里简单判断文件大小，超过 500KB 开始裁剪
            if (file.length() > 500 * 1024) {
                List<String> lines = readLines(context, subDir);
                if (lines.size() > MAX_SINGLE_FILE_LINES) {
                    List<String> newLines = lines.subList(lines.size() - MAX_SINGLE_FILE_LINES, lines.size());
                    writeAll(context, subDir, newLines);
                }
            }
            
        } catch (Exception e) {
            android.util.Log.e("ScreenWake", "写日志失败，可能无存储权限", e);
        }
    }

    public static synchronized String read(Context context, String subDir) {
        try {
            List<String> all = readLines(context, subDir);
            StringBuilder sb = new StringBuilder();
            for(String s : all) sb.append(s).append('\n');
            return sb.toString();
        }catch (Exception e){
            return "读取异常："+e.getClass().getSimpleName()+" | "+e.getMessage();
        }
    }

    public static synchronized String read(Context context) {
        return read(context, SUB_APP);
    }

    public static synchronized int getLineCount(Context context, String subDir) {
        try {
            return readLines(context, subDir).size();
        }catch (Exception e){
            return -1;
        }
    }

    public static synchronized int getLineCount(Context context) {
        return getLineCount(context, SUB_APP);
    }

    /**
     * 【调试版tail】返回包含诊断信息：目录状态、异常、日志内容，直接给UI弹窗显示
     */
    public static synchronized String readTailDebug(Context context, String subDir, int maxLines) {
        StringBuilder out = new StringBuilder();
        out.append("========== 日志诊断报告 ==========\n");
        
        File root = getRootDir(context);
        out.append("根目录路径 = ").append(root == null ? "❌ 获取失败(系统限制)" : root.getAbsolutePath()).append("\n");
        if(root == null){
            out.append("【严重】系统拒绝提供外部存储目录，请检查权限！\n");
            return out.toString();
        }
        
        File sub = getSubDir(context, subDir);
        out.append("子目录路径 = ").append(sub == null ? "❌ 获取失败" : sub.getAbsolutePath()).append("\n");
        
        File logFile = new File(sub, getTodayFileName());
        out.append("今日日志文件 = ").append(logFile.exists() ? "✅ 存在" : "❌ 尚未生成（今天还没写过日志）").append("\n");
        out.append("\n========== 最新日志(最多" + maxLines + "行) ==========\n");
        
        try {
            List<String> all = readLines(context, subDir);
            int size = all.size();
            int start = Math.max(0, size - maxLines);
            if(size == 0){
                out.append("暂无日志数据\n");
            }else{
                for(int i=start;i<size;i++){
                    out.append(all.get(i)).append('\n');
                }
            }
        }catch (Exception e){
            out.append("\n【读取失败】异常类型：").append(e.getClass().getSimpleName()).append("\n");
            out.append("异常信息：").append(e.getMessage()).append("\n");
            if(e.getMessage() != null && e.getMessage().toLowerCase().contains("permission")){
                out.append("👉 判断：权限不足，请前往设置页申请“全部文件访问权限”\n");
            }
            if(e.getMessage() != null && e.getMessage().contains("ENOENT")){
                out.append("👉 判断：文件夹不存在，创建失败\n");
            }
        }
        return out.toString();
    }

    // ========= action_whitelist.txt 读取指令白名单 =========
    public static Set<String> readActionWhitelist(Context context) {
        Set<String> set = new HashSet<>();
        if (context == null) return set;
        File f = getWhitelistFile(context, WHITELIST_FILENAME);
        if(f == null || !f.exists()) {
            log(context, SUB_COMMAND, "指令白名单文件 action_whitelist.txt 不存在，系统将拒绝所有远程指令。");
            return set;
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String trim = line.trim();
                if (trim.isEmpty() || trim.startsWith("#")) continue;
                set.add(trim);
            }
        } catch (Exception e) {
            android.util.Log.e("ScreenWake", "读取action白名单异常", e);
            log(context, SUB_COMMAND, "读取 action_whitelist.txt 失败: " + e.getMessage());
        }
        return set;
    }

    // ========= server_allow.txt 读取IP/域名白名单 =========
    public static Set<String> readServerAllowList(Context context) {
        Set<String> set = new HashSet<>();
        if (context == null) return set;
        File f = getWhitelistFile(context, SERVER_ALLOW_FILENAME);
        if(f == null || !f.exists()) {
            log(context, SUB_COMMAND, "服务器白名单文件 server_allow.txt 不存在，IP校验将直接失败。");
            return set;
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String trim = line.trim();
                if (trim.isEmpty() || trim.startsWith("#")) continue;
                set.add(trim);
            }
        } catch (Exception e) {
            android.util.Log.e("ScreenWake", "读取server_allow异常", e);
            log(context, SUB_COMMAND, "读取 server_allow.txt 失败: " + e.getMessage());
        }
        return set;
    }

    // ========= server_trust.txt 读取受信任bindId =========
    public static String getTrustedServerBindId(Context context) {
        if (context == null) return null;
        File f = getWhitelistFile(context, SERVER_TRUST_FILENAME);
        if(f == null || !f.exists()) return null;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line = br.readLine();
            if (line != null) {
                String val = line.trim();
                if (!val.isEmpty()) return val;
            }
        } catch (Exception e) {
            android.util.Log.e("ScreenWake", "读取server_trust失败", e);
            log(context, SUB_COMMAND, "读取 server_trust.txt 失败: " + e.getMessage());
        }
        return null;
    }

    // ========= server_trust.txt 设置信任bindId；传null清空 =========
    public static boolean setTrustedServerBindId(Context context, String bindId) {
        if (context == null) return false;
        File f = getWhitelistFile(context, SERVER_TRUST_FILENAME);
        if(f == null) return false;
        try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f, false), StandardCharsets.UTF_8))) {
            if (bindId != null && !bindId.trim().isEmpty()) {
                bw.write(bindId.trim());
                log(context, SUB_COMMAND, "已将服务器 bind_id 加入受信任列表，ID: " + bindId);
            } else {
                bw.write("");
                log(context, SUB_COMMAND, "已清空受信任的服务器 bind_id。");
            }
            return true;
        } catch (Exception e) {
            android.util.Log.e("ScreenWake", "写入server_trust失败", e);
            log(context, SUB_COMMAND, "写入 server_trust.txt 失败: " + e.getMessage());
            return false;
        }
    }
}
