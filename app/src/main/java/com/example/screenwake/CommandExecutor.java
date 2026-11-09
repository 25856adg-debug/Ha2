package com.example.screenwake;

import android.content.Context;

import org.json.JSONObject;

public final class CommandExecutor {

    private CommandExecutor() {}

    public interface Callback {
        void onResult(
                String commandId,
                boolean success,
                String message
        );
    }

    // 只执行白名单中的指令
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

        try {

            switch (action) {

                case "get_status":
                    getStatus(c, commandId, callback);
                    break;

                case "get_logs":
                    getLogs(c, commandId, callback);
                    break;

                case "set_interval":
                    setInterval(c, command, commandId, callback);
                    break;

                case "enable_timer":
                    AlarmScheduler.enable(c);
                    finish(callback, commandId, true,
                            "定时器已启用");
                    break;

                case "pause_timer":
                    AlarmScheduler.pause(c);
                    finish(callback, commandId, true,
                            "定时器已暂停");
                    break;

                case "resume_timer":
                    AlarmScheduler.resume(c);
                    finish(callback, commandId, true,
                            "定时器已恢复");
                    break;

                case "cancel_timer":
                    AlarmScheduler.cancel(c);
                    finish(callback, commandId, true,
                            "定时器已取消");
                    break;

                case "trigger_now":
                    if (!ConfigManager.isEnabled(c)
                            || ConfigManager.isPaused(c)) {

                        finish(callback, commandId, false,
                                "定时器未启用或处于暂停状态");
                    } else {
                        AlarmScheduler.triggerNow(c);
                        finish(callback, commandId, true,
                                "已请求立即触发");
                    }
                    break;

                default:
                    finish(callback, commandId, false,
                            "不支持的指令：" + action);
                    break;
            }

        } catch (Exception e) {

            LogManager.log(c,
                    "指令执行异常，ID：" + commandId
                            + "，原因：" + e.getMessage());

            finish(callback, commandId, false,
                    "执行异常：" + e.getMessage());
        }
    }

    // 获取设备和定时器状态
    private static void getStatus(
            Context c,
            String commandId,
            Callback callback) throws Exception {

        JSONObject status = new JSONObject();

        status.put("device_id",
                ConfigManager.getDeviceId(c));

        status.put("timer_enabled",
                ConfigManager.isEnabled(c));

        status.put("timer_paused",
                ConfigManager.isPaused(c));

        status.put("interval_minutes",
                ConfigManager.getIntervalMinutes(c));

        status.put("next_trigger",
                ConfigManager.getNextTrigger(c));

        status.put("last_trigger",
                ConfigManager.getLastTrigger(c));

        status.put("command_window_running",
                CommandPoller.isRunning());

        finish(callback, commandId, true,
                status.toString());
    }

    // 获取运行日志，限制回传长度
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

        finish(callback, commandId, true, logs);
    }
    

    // 修改定时周期
    private static void setInterval(
            Context c,
            JSONObject command,
            String commandId,
            Callback callback) throws Exception {

        JSONObject params = command.optJSONObject("params");

        if (params == null) {
            finish(callback, commandId, false,
                    "缺少params参数");
            return;
        }

        int hours = params.optInt("hours", -1);
        int minutes = params.optInt("minutes", -1);

        if (hours < 0 || hours > 720
                || minutes < 0 || minutes > 59) {

            finish(callback, commandId, false,
                    "时间参数无效");
            return;
        }

        long totalMinutes = (long) hours * 60L + minutes;

        if (totalMinutes < 1L || totalMinutes > 43200L) {
            finish(callback, commandId, false,
                    "定时周期必须在1分钟到30天之间");
            return;
        }

        ConfigManager.setIntervalMinutes(c, totalMinutes);

        // 如果定时器正在运行，重新安排下一次触发
        if (ConfigManager.isEnabled(c)
                && !ConfigManager.isPaused(c)) {

            AlarmScheduler.scheduleNext(c);
        }

        LogManager.log(c,
                "服务器修改定时周期为："
                        + hours + "小时"
                        + minutes + "分钟");

        finish(callback, commandId, true,
                "定时周期已修改为"
                        + hours + "小时"
                        + minutes + "分钟");
    }

    // 统一返回执行结果
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
