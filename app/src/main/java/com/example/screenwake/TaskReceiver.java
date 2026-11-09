package com.example.screenwake;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class TaskReceiver extends BroadcastReceiver {

    /** 距上次上报不足这个分钟数，保活上报就跳过，避免两条链撞车重复发 */
    private static final long BASELINE_MIN_GAP_MINUTES = 55L;

    @Override
    public void onReceive(Context context, Intent intent) {

        if (intent == null) {
            return;
        }

        String action = intent.getAction();

        if (AlarmScheduler.ACTION_BASELINE.equals(action)) {
            handleBaseline(context);
            return;
        }

        if (!AlarmScheduler.ACTION_TIMER.equals(action)) {
            return;
        }

        handleMainTimer(context);
    }

    // ================= 主定时器：用户设定的周期 =================
    private void handleMainTimer(Context context) {

        Context c = context.getApplicationContext();

        if (!ConfigManager.isEnabled(c)
                || ConfigManager.isPaused(c)) {
            LogManager.log(c,
                    "收到定时广播，但定时器未启用或已暂停");
            return;
        }

        // 防止上一轮上传尚未完成时重复触发
        if (DeviceUploader.isRunning()) {
            LogManager.log(c,
                    "上一轮上传仍在运行，本次触发已推迟");
            // ★ 修复：被拒也要重排，否则主定时器链会断
            AlarmScheduler.scheduleNext(c);
            return;
        }

        long now = System.currentTimeMillis();
        long planned = ConfigManager.getNextTrigger(c);
        long delayMinutes = 0L;

        if (planned > 0L && now > planned) {
            delayMinutes = (now - planned) / 60000L;
        }

        ConfigManager.setLastTrigger(c, now);
        ConfigManager.setNextTrigger(c, 0L);

        LogManager.log(c,
                "定时任务触发，实际时间：" + now
                        + "，计划时间：" + planned
                        + "，延迟分钟：" + delayMinutes);

        // 启动前台服务
        CycleService.start(c);

        // 上报设备信息，最多尝试五次
        DeviceUploader.send(c,
                new DeviceUploader.Callback() {
                    @Override
                    public void onComplete(
                            boolean success,
                            String message) {
                        LogManager.log(c,
                                "本轮上传结束，结果："
                                        + (success ? "成功" : "失败")
                                        + "，信息：" + message);

                        try {
                            // 上报结束确保指令轮询在跑
                            CommandPoller.ensureRunning(c);
                        } catch (Exception e) {
                            LogManager.log(c,
                                    "启动指令轮询异常：" + e.getMessage());
                        }

                        try {
                            // 安排下一轮主定时任务
                            AlarmScheduler.scheduleNext(c);
                        } catch (Exception e) {
                            LogManager.log(c,
                                    "安排下一轮任务异常：" + e.getMessage());
                        }

                        try {
                            // 顺便确保保活基线也在
                            AlarmScheduler.scheduleBaseline(c);
                        } catch (Exception e) {
                            LogManager.log(c,
                                    "安排保活基线异常：" + e.getMessage());
                        }
                    }
                });
    }

    // ================= 一小时保活基线：不管定时器设置 =================
    private void handleBaseline(Context context) {

        Context c = context.getApplicationContext();

        // ★ 第一件事：先把下一次基线排好，避免本轮失败导致链断
        AlarmScheduler.scheduleBaseline(c);

        LogManager.log(c, "一小时保活基线触发");

        // 进程可能刚被唤醒，前台服务也许已经死了，重新拉起来
        try {
            CycleService.start(c);
        } catch (Exception e) {
            LogManager.log(c, "保活基线拉起前台服务失败：" + e.getMessage());
        }

        // 确保指令轮询在跑
        try {
            CommandPoller.ensureRunning(c);
        } catch (Exception e) {
            LogManager.log(c, "保活基线恢复轮询失败：" + e.getMessage());
        }

        // 距上次上报太近就跳过上报，只保活
        long last = ConfigManager.getLastTrigger(c);
        long gapMinutes = last > 0L
                ? (System.currentTimeMillis() - last) / 60000L
                : Long.MAX_VALUE;

        if (gapMinutes < BASELINE_MIN_GAP_MINUTES) {
            LogManager.log(c,
                    "距上次上报仅 " + gapMinutes + " 分钟，本次保活只唤醒不上报");
            return;
        }

        if (DeviceUploader.isRunning()) {
            LogManager.log(c,
                    "上一轮上传仍在运行，本次保活上报跳过");
            return;
        }

        ConfigManager.setLastTrigger(c, System.currentTimeMillis());

        DeviceUploader.send(c,
                new DeviceUploader.Callback() {
                    @Override
                    public void onComplete(
                            boolean success,
                            String message) {
                        LogManager.log(c,
                                "保活上报结束，结果："
                                        + (success ? "成功" : "失败")
                                        + "，信息：" + message);

                        try {
                            CommandPoller.ensureRunning(c);
                        } catch (Exception e) {
                            LogManager.log(c,
                                    "保活上报后恢复轮询异常：" + e.getMessage());
                        }
                        // ★ 注意：这里不调 scheduleNext，绝不碰主定时器
                    }
                });
    }
}

