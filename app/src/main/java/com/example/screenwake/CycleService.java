package com.example.screenwake;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

public class CycleService extends Service {

    public static final String ACTION_START =
            "com.example.screenwake.CYCLE_START";

    public static final String ACTION_STOP =
            "com.example.screenwake.CYCLE_STOP";

    private static final String CHANNEL_ID = "screenwake_cycle";
    private static final int NOTIFICATION_ID = 402;

    public static void start(Context context) {
        Intent intent = new Intent(context, CycleService.class);
        intent.setAction(ACTION_START);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            LogManager.log(context, "启动循环服务失败：" + e.getMessage());
        }
    }

    public static void stop(Context context) {
        try {
            Intent intent = new Intent(context, CycleService.class);
            context.stopService(intent);
        } catch (Exception e) {
            LogManager.log(context, "停止循环服务失败：" + e.getMessage());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            Notification notification = buildNotification();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                );
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }

        } catch (Exception e) {
            LogManager.log(this, "前台服务启动失败：" + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }

        // 检查是否存在尚未结束的一小时指令窗口
        long savedDeadline = ConfigManager.getCommandWindowEnd(this);

        if (savedDeadline > 0) {
            CommandPoller.restoreWindow(this);
        }

        LogManager.log(this, "循环服务运行中");

        // 允许系统在服务被回收后尝试重新创建
        return START_STICKY;
    }

    private void createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "设备指令接收服务",
                    NotificationManager.IMPORTANCE_LOW
            );

            channel.setDescription("保持设备指令接收窗口运行");

            NotificationManager manager =
                    getSystemService(NotificationManager.class);

            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {

        Intent openIntent = new Intent(this, MainActivity.class);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                402,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle("ScreenWake 设备服务")
                .setContentText("设备定时任务与指令接收服务运行中")
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build();
    }

    @Override
    public void onDestroy() {
        LogManager.log(this, "循环服务已结束");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
