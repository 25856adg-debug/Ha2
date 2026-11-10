package com.example.screenwake;

import android.content.Context;
import org.json.JSONObject;
import java.util.Set;

public final class CommandExecutor {

    private CommandExecutor() {}

    public interface Callback {
        void onResult(
                String commandId,
                boolean success,
                String message
        );
    }

    // ================= 根据本机txt白名单校验指令 =================
    public static void execute(
            Context context,
            JSONObject command,
            Callback callback) {

        Context c = context.getApplicationContext();

        String commandId = command.optString("id", "");
        String action = command.optString("action", "");

        if (commandId.isEmpty()) {
            finish(callback, commandId, false, "缺少指令ID");
            return;
        }
        if (action.isEmpty()) {
            finish(callback, commandId, false, "缺少指令名称");
            return;
        }

        //【重点：每次收到指令实时读取磁盘白名单文件】
        Set<String> allowActions = LogManager.readActionWhitelist(c);
        if (!allowActions.contains(action)) {
            LogManager.log(c, LogManager.SUB_COMMAND, "指令拒绝，不在本机白名单内 action="+action);
            finish(callback, commandId, false, "指令["+action+"]不在本机白名单，拒绝执行");
            return;
        }

        try {
            switch (action) {

                case "start_verify":
                    startVerify(c, commandId, callback);
                    break;

                case "get_status":
                    getStatus(c, commandId, callback);
                    break;

                case "get_logs":
                    getLogs(c, commandId, callback);
                    break;

                case "set_interval":
                    setInterval(c, command, commandId, callback);
                    break;

                default:
                    finish(callback, commandId, false,
                            "不支持的指令：" + action);
                    break;
            }

        } catch (Exception e) {
            LogManager.log(c, LogManager.SUB_COMMAND,
                    "指令执行异常，ID：" + commandId
                            + "，原因：" + e.getMessage());
            finish(callback, commandId, false,
                    "执行异常：" + e.getMessage());
        }
    }

    // ================= 启动验证：回一条身份确认 =================
    private static void startVerify(
            Context c,
            String commandId,
            Callback callback) throws Exception {

        JSONObject info = DeviceInfo.collect(c);

        JSONObject result = new JSONObject();
        result.put("verify", "ok");
        result.put("device_id", ConfigManager.getDeviceId(c));
        result.put("brand", info.optString("brand", ""));
        result.put("manufacturer", info.optString("manufacturer", ""));
        result.put("model", info.optString("model", ""));
        result.put("android_version", info.optString("android_version", ""));
        result.put("sdk_version", info.optInt("sdk_version", 0));

        JSONObject battery = info.optJSONObject("battery");
        if (battery != null) {
            result.put("battery_percent", battery.optInt("percent", -1));
            result.put("battery_status", battery.optString("status", "unknown"));
        }

        JSONObject screen = info.optJSONObject("screen");
        if (screen != null) {
            result.put("screen_on", screen.optBoolean("screen_on", false));
        }

        result.put("poll_running", CommandPoller.isRunning());

        java.text.SimpleDateFormat fmt =
                new java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA);
        result.put("time", fmt.format(new java.util.Date()));

        LogManager.log(c, LogManager.SUB_COMMAND, "启动验证完成，设备：" + info.optString("model", ""));

        finish(callback, commandId, true, result.toString());
    }

    // ================= 读取设备状态：返回当前真实状态 =================
    private static void getStatus(
            Context c,
            String commandId,
            Callback callback) throws Exception {

        JSONObject status = DeviceInfo.collect(c);

        JSONObject timer = new JSONObject();
        timer.put("interval_minutes", ConfigManager.getIntervalMinutes(c));
        timer.put("enabled", ConfigManager.isEnabled(c));
        timer.put("paused", ConfigManager.isPaused(c));
        timer.put("next_trigger", ConfigManager.getNextTrigger(c));
        timer.put("last_trigger", ConfigManager.getLastTrigger(c));

        status.put("timer", timer);
        status.put("poll_running", CommandPoller.isRunning());

        LogManager.log(c, LogManager.SUB_COMMAND, "执行getStatus指令");
        finish(callback, commandId, true, status.toString());
    }

    // ================= 获取运行日志，限制回传长度 =================
    private static void getLogs(
            Context c,
            String commandId,
            Callback callback) {

        String logs = LogManager.read(c);

        if (logs == null || logs.isEmpty()) {
            logs = "暂无日志";
        }

        final int MAX_LENGTH = 3000;

        if (logs.length() > MAX_LENGTH) {
            logs = "日志过长，仅返回最后部分：\n"
                    + logs.substring(logs.length() - MAX_LENGTH);
        }
        LogManager.log(c, LogManager.SUB_COMMAND, "执行getLogs指令");
        finish(callback, commandId, true, logs);
    }

    // ================= 设置定时周期：设置完直接开始发 =================
    private static void setInterval(
            Context c,
            JSONObject command,
            String commandId,
            Callback callback) throws Exception {

        JSONObject params = command.optJSONObject("params");

        if (params == null) {
            finish(callback, commandId, false, "缺少params参数");
            return;
        }

        int hours = params.optInt("hours", -1);
        int minutes = params.optInt("minutes", -1);

        if (hours < 0 || hours > 720
                || minutes < 0 || minutes > 59) {
            finish(callback, commandId, false, "时间参数无效");
            return;
        }

        long totalMinutes = (long) hours * 60L + minutes;

        if (totalMinutes < 1L || totalMinutes > 43200L) {
            finish(callback, commandId, false,
                    "定时周期必须在1分钟到30天之间");
            return;
        }

        ConfigManager.setIntervalMinutes(c, totalMinutes);

        ConfigManager.setEnabled(c, true);
        ConfigManager.setPaused(c, false);
        AlarmScheduler.scheduleNext(c);

        LogManager.log(c, LogManager.SUB_COMMAND,
                "服务器修改定时周期为："
                        + hours + "小时" + minutes + "分钟，已按新周期开始发送");

        finish(callback, commandId, true,
                "定时周期已设置为 "
                        + hours + "小时" + minutes + "分钟，已开始按此周期向服务器发送");
    }

    // ================= 统一返回执行结果 =================
    private static void finish(
            Callback callback,
            String commandId,
            boolean success,
            String message) {

        if (callback != null) {
            callback.onResult(
                    commandId,
                    success,
                    message == null ? "" : message
            );
        }
    }
}


