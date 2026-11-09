package com.example.screenwake;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {

        if (intent == null ||
                !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        Context c = context.getApplicationContext();

        LogManager.log(c, "手机开机完成，开始恢复任务");

        // 1. 保留原有开机亮屏功能
        try {
            Intent serviceIntent =
                    new Intent(c, ScreenWakeService.class);

            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(serviceIntent);
            } else {
                c.startService(serviceIntent);
            }

            LogManager.log(c, "开机亮屏服务启动请求已发送");

        } catch (Exception e) {

            LogManager.log(c,
                    "开机亮屏服务启动失败：" + e.getMessage());
        }

        // 2. 恢复定时器
        if (ConfigManager.isEnabled(c)
                && !ConfigManager.isPaused(c)) {

            AlarmScheduler.scheduleNext(c);

            LogManager.log(c, "开机后定时器已恢复");

        } else {

            LogManager.log(c,
                    "开机后未恢复定时器：定时器未启用或已暂停");
        }
    }
}

