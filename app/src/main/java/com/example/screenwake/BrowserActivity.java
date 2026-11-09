package com.example.screenwake;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class BrowserActivity extends Activity {
    private LinearLayout root;
    private TextView status;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildPage();
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
        refreshStatus();
    }

    @Override protected void onResume() { super.onResume(); if (status != null) refreshStatus(); }

    private void buildPage() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 24, 28, 24);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("开机亮屏 5 分钟");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView intro = new TextView(this);
        intro.setText("请逐项设置权限。完成后先手动测试，再重启手机验证。\\n注意：不会绕过锁屏密码。");
        intro.setTextSize(15);
        intro.setPadding(0, 16, 0, 12);
        root.addView(intro);

        status = new TextView(this);
        status.setTextSize(14);
        status.setPadding(0, 8, 0, 12);
        root.addView(status);

        button("1. 开启悬浮窗权限", this::openOverlay);
        button("2. 开启通知权限", this::openNotifications);
        button("3. 允许后台运行 / 忽略电池优化", this::openBattery);
        button("4. 打开应用详情（自启动等设置）", this::openAppDetails);
        button("5. 立即测试：显示图案 5 分钟", this::testNow);

        TextView note = new TextView(this);
        note.setText("OPPO/ColorOS：另外在系统设置或手机管家中搜索并开启“自启动”“允许后台活动”“后台弹出界面”。这些是厂商设置，菜单名称因版本而异，应用无法替你自动开启。");
        note.setTextSize(13);
        note.setPadding(0, 18, 0, 0);
        root.addView(note);
        setContentView(root);
    }

    private void button(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(v -> action.run());
        root.addView(b, new LinearLayout.LayoutParams(-1, -2));
    }

    private void refreshStatus() {
        boolean overlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        boolean battery = false;
        if (Build.VERSION.SDK_INT >= 23) {
            PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
            battery = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        }
        String notice = "系统版本无需运行时通知授权";
        if (Build.VERSION.SDK_INT >= 33) {
            notice = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ? "已允许" : "未允许";
        }
        status.setText("权限状态：\\n悬浮窗：" + (overlay ? "已允许" : "未允许") +
            "\\n通知：" + notice +
            "\\n电池优化：" + (battery ? "已忽略" : "仍受限制/未确认") +
            "\\n自启动、后台弹出：请检查 ColorOS 设置");
    }

    private void openOverlay() {
        if (Build.VERSION.SDK_INT >= 23) {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); }
            catch (Exception e) { openAppDetails(); }
        }
    }
    private void openNotifications() {
        if (Build.VERSION.SDK_INT >= 26) {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(i);
        } else openAppDetails();
    }
    private void openBattery() {
        if (Build.VERSION.SDK_INT >= 23) {
            try { startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))); }
            catch (Exception e) { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
        }
    }
    private void openAppDetails() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    }
    private void testNow() {
        try {
            Intent i = new Intent(this, ScreenWakeService.class);
            i.setAction(ScreenWakeService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Exception e) {
            Toast.makeText(this, "启动失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
