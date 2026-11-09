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

            LogManager.log(c, "收到定时广播，但定时器未启用或已暂停");
            return;
        }

        if (DeviceUploader.isRunning()) {
            LogManager.log(c, "上一轮上传仍在运行，本次触发跳过");
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

        // 启动前台服务，托管本轮任务
        CycleService.start(c);

        // 开始上传设备信息
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

                        // 上传结束后开启一小时指令窗口
                        CommandPoller.startWindow(c);

                        // 安排下一轮定时任务
                        AlarmScheduler.scheduleNext(c);
                    }
                });
    }
}


