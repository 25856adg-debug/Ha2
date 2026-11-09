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

    private boolean started = false;

    public static void start(Context context) {
        Context c = context.getApplicationContext();

        Intent intent = new Intent(c, CycleService.class);
        intent.setAction(ACTION_START);

        try {
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(intent);
            } else {
                c.startService(intent);
            }
        } catch (Exception e) {
            LogManager.log(c,
                    "启动任务前台服务失败：" + e.getMessage());
        }
    }

    public static void stop(Context context) {
        Context c = context.getApplicationContext();

        try {
            // 不再使用后台 startService() 发送停止命令
            c.stopService(new Intent(c, CycleService.class));
        } catch (Exception e) {
            LogManager.log(c,
                    "停止任务前台服务失败：" + e.getMessage());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent != null &&
                ACTION_STOP.equals(intent.getAction())) {

            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!started) {
            try {
                Notification notification = buildNotification();

                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(
                            NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    );
                } else {
                    startForeground(
                            NOTIFICATION_ID,
                            notification
                    );
                }

                started = true;

                LogManager.log(this, "任务前台服务已启动");

            } catch (Exception e) {

                LogManager.log(this,
                        "前台服务启动失败：" + e.getMessage());

                stopSelf();
                return START_NOT_STICKY;
            }
        }

        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= 26) {

            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "设备任务运行状态",
                            NotificationManager.IMPORTANCE_LOW
                    );

            channel.setDescription(
                    "显示设备信息上传和指令接收任务状态"
            );

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
                NOTIFICATION_ID,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("ScreenWake 正在运行")
                .setContentText("设备信息上传与指令接收运行中")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setShowWhen(true)
                .build();
    }

    @Override
    public void onDestroy() {
        started = false;

        LogManager.log(this, "任务前台服务已停止");

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}


