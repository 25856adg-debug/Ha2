package com.example.screenwake;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
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
        Intent intent = new Intent(context, CycleService.class);
        intent.setAction(ACTION_START);

        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            LogManager.log(context,
                    "启动任务前台服务失败：" + e.getMessage());
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, CycleService.class);
        intent.setAction(ACTION_STOP);

        try {
            context.startService(intent);
        } catch (Exception e) {
            LogManager.log(context,
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

        String action = intent == null ? ACTION_START : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!started) {
            try {
                startForeground(
                        NOTIFICATION_ID,
                        buildNotification()
                );
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
                402,
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
                .setContentText("设备任务与指令接收服务运行中")
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
