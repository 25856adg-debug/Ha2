package com.example.screenwake;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public final class AlarmScheduler {

    // ================= 主定时器（用户设定的周期） =================
    public static final String ACTION_TIMER =
            "com.example.screenwake.ACTION_TIMER";

    private static final int REQUEST_CODE = 7101;

    // ================= 一小时保活基线（不受用户周期影响） =================
    public static final String ACTION_BASELINE =
            "com.example.screenwake.ACTION_BASELINE";

    private static final int REQUEST_CODE_BASELINE = 7102;

    /** 保活基线固定为一小时 */
    public static final long BASELINE_MINUTES = 60L;

    private AlarmScheduler() {}

    // ================= 主定时器：PendingIntent =================
    private static PendingIntent getPendingIntent(Context context) {
        Intent intent = new Intent(context, TaskReceiver.class);
        intent.setAction(ACTION_TIMER);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                flags
        );
    }

    // ================= 主定时器：安排下一次 =================
    public static synchronized void scheduleNext(Context context) {
        Context c = context.getApplicationContext();

        if (!ConfigManager.isEnabled(c)
                || ConfigManager.isPaused(c)) {
            cancelAlarm(c);
            ConfigManager.setNextTrigger(c, 0L);
            return;
        }

        cancelAlarm(c);

        long interval = ConfigManager.getIntervalMinutes(c);
        long triggerTime = System.currentTimeMillis()
                + interval * 60_000L;

        AlarmManager alarmManager =
                (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);

        if (alarmManager == null) {
            LogManager.log(c, "定时器安排失败：AlarmManager不可用");
            return;
        }

        try {
            PendingIntent pendingIntent = getPendingIntent(c);

            if (Build.VERSION.SDK_INT >= 23) {
                alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                );
            } else {
                alarmManager.set(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                );
            }

            ConfigManager.setNextTrigger(c, triggerTime);

            LogManager.log(c,
                    "已安排下一次定时任务，计划时间："
                            + triggerTime
                            + "，间隔分钟："
                            + interval);

        } catch (Exception e) {
            ConfigManager.setNextTrigger(c, 0L);
            LogManager.log(c, "安排定时任务失败：" + e.getMessage());
        }
    }

    // ================= 主定时器：取消 =================
    public static synchronized void cancelAlarm(Context context) {
        Context c = context.getApplicationContext();

        AlarmManager alarmManager =
                (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);

        if (alarmManager != null) {
            try {
                alarmManager.cancel(getPendingIntent(c));
            } catch (Exception e) {
                LogManager.log(c, "取消系统闹钟失败：" + e.getMessage());
            }
        }
    }

    // ================= 一小时基线：PendingIntent =================
    // requestCode 和 action 都不同，与主定时器互不覆盖
    private static PendingIntent getBaselinePendingIntent(Context context) {
        Intent intent = new Intent(context, TaskReceiver.class);
        intent.setAction(ACTION_BASELINE);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_BASELINE,
                intent,
                flags
        );
    }

    // ================= 一小时基线：安排下一次 =================
    // 注意：不检查 isEnabled / isPaused，基线永远跑
    public static synchronized void scheduleBaseline(Context context) {

        Context c = context.getApplicationContext();

        AlarmManager alarmManager =
                (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);

        if (alarmManager == null) {
            LogManager.log(c, "保活基线安排失败：AlarmManager不可用");
            return;
        }

        long triggerTime = System.currentTimeMillis()
                + BASELINE_MINUTES * 60_000L;

        try {
            PendingIntent pendingIntent = getBaselinePendingIntent(c);

            if (Build.VERSION.SDK_INT >= 23) {
                alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                );
            } else {
                alarmManager.set(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                );
            }

            LogManager.log(c,
                    "已安排一小时保活基线，计划时间："
                            + triggerTime
                            + "（" + BASELINE_MINUTES + " 分钟后）");

        } catch (Exception e) {
            LogManager.log(c, "安排保活基线失败：" + e.getMessage());
        }
    }

    // ================= 一小时基线：取消 =================
    public static synchronized void cancelBaseline(Context context) {
        Context c = context.getApplicationContext();

        AlarmManager alarmManager =
                (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);

        if (alarmManager != null) {
            try {
                alarmManager.cancel(getBaselinePendingIntent(c));
            } catch (Exception e) {
                LogManager.log(c, "取消保活基线失败：" + e.getMessage());
            }
        }
    }

    // ================= 启用定时器 =================
    public static synchronized void enable(Context context) {
        Context c = context.getApplicationContext();
        ConfigManager.setEnabled(c, true);
        ConfigManager.setPaused(c, false);
        LogManager.log(c, "定时器已启用");
        scheduleNext(c);
        // 启用时顺便确保基线在跑
        scheduleBaseline(c);
    }

    // ================= 暂停定时器 =================
    // 只停主定时器，保活基线继续跑
    public static synchronized void pause(Context context) {
        Context c = context.getApplicationContext();
        ConfigManager.setPaused(c, true);
        cancelAlarm(c);
        ConfigManager.setNextTrigger(c, 0L);
        LogManager.log(c, "定时器已暂停（保活基线仍在运行）");
    }

    // ================= 恢复定时器 =================
    public static synchronized void resume(Context context) {
        Context c = context.getApplicationContext();

        if (!ConfigManager.isEnabled(c)) {
            LogManager.log(c, "恢复失败：定时器尚未启用");
            return;
        }

        ConfigManager.setPaused(c, false);
        LogManager.log(c, "定时器已恢复");
        scheduleNext(c);
    }

    // ================= 取消定时器 =================
    // 只停主定时器，保活基线继续跑
    public static synchronized void cancel(Context context) {
        Context c = context.getApplicationContext();
        cancelAlarm(c);
        ConfigManager.setEnabled(c, false);
        ConfigManager.setPaused(c, false);
        ConfigManager.setNextTrigger(c, 0L);
        LogManager.log(c, "定时器已取消（保活基线仍在运行）");
    }

    // ================= 立即触发一次 =================
    public static synchronized void triggerNow(Context context) {

        Context c = context.getApplicationContext();

        if (!ConfigManager.isEnabled(c)) {
            LogManager.log(c, "立即触发失败：定时器未启用");
            return;
        }

        if (ConfigManager.isPaused(c)) {
            LogManager.log(c, "立即触发失败：定时器处于暂停状态");
            return;
        }

        cancelAlarm(c);
        ConfigManager.setNextTrigger(c, 0L);

        LogManager.log(c, "用户手动请求立即触发");

        Intent intent = new Intent(c, TaskReceiver.class);
        intent.setAction(ACTION_TIMER);

        try {
            c.sendBroadcast(intent);
        } catch (Exception e) {
            LogManager.log(c, "立即触发广播失败：" + e.getMessage());
        }
    }
}
