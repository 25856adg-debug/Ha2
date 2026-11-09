package com.example.screenwake;

import android.app.*;
import android.content.*;
import android.os.*;

public class ScreenWakeService extends Service {

    private Handler h;

    private PowerManager.WakeLock lock;

    private static final int ID = 301;

    @Override
    public void onCreate() {

        super.onCreate();

        h = new Handler(Looper.getMainLooper());

        if(Build.VERSION.SDK_INT >= 26) {

            NotificationChannel ch =
                new NotificationChannel(
                    "screenwake",
                    "开机亮屏",
                    NotificationManager.IMPORTANCE_LOW
                );

            ((NotificationManager)getSystemService(
                NOTIFICATION_SERVICE
            )).createNotificationChannel(ch);
        }

        Notification n;

        if(Build.VERSION.SDK_INT >= 26) {

            n = new Notification.Builder(this,"screenwake")
                .setContentTitle("ScreenWake")
                .setContentText("正在尝试执行开机亮屏")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build();

        } else {

            n = new Notification.Builder(this)
                .setContentTitle("ScreenWake")
                .setContentText("正在尝试执行开机亮屏")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build();
        }

        startForeground(ID,n);
    }

    @Override
    public int onStartCommand(
        Intent i,
        int flags,
        int id
    ) {

        try {

            PowerManager pm =
                (PowerManager)getSystemService(POWER_SERVICE);

            lock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK |
                PowerManager.ACQUIRE_CAUSES_WAKEUP |
                PowerManager.ON_AFTER_RELEASE,
                "ScreenWake:Boot"
            );

            lock.acquire(300000L);

        } catch(Exception ignored) {
        }

        // 开机后尝试显示亮屏界面

        h.postDelayed(()->{

            try {

                Intent a = new Intent(
                    this,
                    PatternActivity.class
                );

                a.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK |
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                );

                startActivity(a);

            } catch(Exception ignored) {
            }

        },1200);

        // 五分钟后结束

        h.postDelayed(()->{

            if(lock != null && lock.isHeld()) {
                lock.release();
            }

            stopForeground(true);

            stopSelf();

        },300000);

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {

        if(lock != null && lock.isHeld()) {
            lock.release();
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }
}



