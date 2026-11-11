package com.example.screenwake;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 设置与权限子页面
 *
 * 入口：MainActivity -> startActivity(new Intent(this, SettingsPage.class));
 *
 * 内容：
 *  - 系统设置与权限申请（文件、存储、相机）
 *  - 常用系统设置跳转（Wi-Fi、蓝牙、网络、流量、本应用权限）
 *  - 屏幕亮度调节（本页窗口亮度 + 系统亮度）
 *  - 退出本页
 */
public class SettingsPage extends Activity {

    private static final int COLOR_BG     = Color.rgb(20, 27, 36);
    private static final int COLOR_CARD   = Color.rgb(48, 59, 79);
    private static final int COLOR_TEXT   = Color.rgb(220, 227, 234);
    private static final int COLOR_SUB    = Color.rgb(150, 165, 180);
    private static final int COLOR_ACCENT = Color.rgb(100, 180, 255);
    private static final int COLOR_OK     = Color.rgb(120, 210, 140);
    private static final int COLOR_WARN   = Color.rgb(240, 190, 90);

    private static final int REQ_STORAGE = 2001;
    private static final int REQ_CAMERA  = 2002;

    /** 亮度最大值（系统 SCREEN_BRIGHTNESS 范围 0~255） */
    private static final int MAX_BRIGHTNESS = 255;

    private TextView brightnessValueView;
    private TextView brightnessStateView;
    private SeekBar  brightnessBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(COLOR_BG);
        getWindow().setNavigationBarColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        addHeader(root);

        // ------------------------------------------------------------
        // 一、系统设置与权限申请
        // ------------------------------------------------------------
        addSection(root, "系统设置与权限申请");
        addButton(root, "申请全部文件访问权限", this::requestAllFilePermission);
        addButton(root, "申请普通存储权限", this::requestNormalStoragePermission);
        addButton(root, "申请相机权限", this::requestCameraPermission);

        // ------------------------------------------------------------
        // 二、网络与连接
        // ------------------------------------------------------------
        addSection(root, "网络与连接");
        addButton(root, "Wi-Fi 设置",
                () -> openSystemSettings(Settings.ACTION_WIFI_SETTINGS));
        addButton(root, "蓝牙设置",
                () -> openSystemSettings(Settings.ACTION_BLUETOOTH_SETTINGS));
        addButton(root, "网络设置",
                () -> openSystemSettings(Settings.ACTION_WIRELESS_SETTINGS));
        addButton(root, "流量使用情况",
                () -> openSystemSettings(Settings.ACTION_DATA_USAGE_SETTINGS));

        // ------------------------------------------------------------
        // 三、屏幕亮度
        // ------------------------------------------------------------
        addSection(root, "屏幕亮度");
        addBrightnessCard(root);

        // ------------------------------------------------------------
        // 四、本应用 / 退出
        // ------------------------------------------------------------
        addSection(root, "本应用");
        addButton(root, "本应用权限与后台设置", () -> openSystemSettings(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
        addButton(root, "退出本页面", this::finish);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(COLOR_BG);
        scroll.addView(root);
        setContentView(scroll);
    }

    // ================================================================
    // 页面头部
    // ================================================================

    private void addHeader(LinearLayout root) {
        TextView title = new TextView(this);
        title.setText("设置与权限");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(0, dp(8), 0, dp(4));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("统一管理权限申请、系统设置入口与屏幕亮度");
        sub.setTextColor(COLOR_SUB);
        sub.setTextSize(13);
        sub.setPadding(0, 0, 0, dp(4));
        root.addView(sub);
    }

    // ================================================================
    // 亮度卡片
    // ================================================================

    private void addBrightnessCard(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedBg(COLOR_CARD, dp(12)));
        int cp = dp(14);
        card.setPadding(cp, cp, cp, cp);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(10);
        card.setLayoutParams(cardLp);

        // 当前亮度文字
        brightnessValueView = new TextView(this);
        brightnessValueView.setTextColor(COLOR_TEXT);
        brightnessValueView.setTextSize(15);
        card.addView(brightnessValueView);

        // 状态文字（是否可写系统亮度）
        brightnessStateView = new TextView(this);
        brightnessStateView.setTextColor(COLOR_SUB);
        brightnessStateView.setTextSize(12);
        brightnessStateView.setPadding(0, dp(4), 0, dp(10));
        card.addView(brightnessStateView);

        // 拖动条
        brightnessBar = new SeekBar(this);
        brightnessBar.setMax(MAX_BRIGHTNESS);
        brightnessBar.setPadding(dp(4), 0, dp(4), 0);
        card.addView(brightnessBar);

        brightnessBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                updateBrightnessLabel(progress);
                applyWindowBrightness(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int value = Math.max(seekBar.getProgress(), 1);
                if (writeSystemBrightness(value)) {
                    toast("系统亮度已设为 " + value);
                } else {
                    toast("仅调整本页面亮度（未获得修改系统设置权限）");
                }
                refreshBrightnessState();
            }
        });

        // 三个快捷按钮
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, 0);

        Button follow = smallButton("跟随系统");
        follow.setOnClickListener(v -> {
            applyWindowBrightness(-1);
            int cur = readSystemBrightness();
            brightnessBar.setProgress(cur);
            updateBrightnessLabel(cur);
            toast("本页面亮度已改为跟随系统");
        });

        Button sysPage = smallButton("系统亮度设置");
        sysPage.setOnClickListener(v ->
                openSystemSettings(Settings.ACTION_DISPLAY_SETTINGS));

        Button writePerm = smallButton("授权改亮度");
        writePerm.setOnClickListener(v -> requestWriteSettingsPermission());

        row.addView(follow, weightParams());
        row.addView(sysPage, weightParams());
        row.addView(writePerm, weightParams());

        card.addView(row);
        root.addView(card);

        // 初始化显示
        int cur = readSystemBrightness();
        brightnessBar.setProgress(cur);
        updateBrightnessLabel(cur);
        refreshBrightnessState();
    }

    private LinearLayout.LayoutParams weightParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        return lp;
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTextColor(COLOR_TEXT);
        b.setBackground(roundedBg(COLOR_BG, dp(8)));
        b.setPadding(dp(4), dp(6), dp(4), dp(6));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        return b;
    }

    private void updateBrightnessLabel(int value) {
        if (brightnessValueView == null) return;
        int percent = Math.round(value * 100f / MAX_BRIGHTNESS);
        brightnessValueView.setText("当前亮度：" + value + " / " + MAX_BRIGHTNESS
                + "（约 " + percent + "%）");
    }

    private void refreshBrightnessState() {
        if (brightnessStateView == null) return;
        boolean canWrite = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.System.canWrite(this);
        if (canWrite) {
            brightnessStateView.setTextColor(COLOR_OK);
            brightnessStateView.setText("已获得修改系统亮度权限，拖动后会写入系统设置");
        } else {
            brightnessStateView.setTextColor(COLOR_WARN);
            brightnessStateView.setText("未获得修改系统亮度权限，拖动仅影响本页面显示");
        }
    }

    // ================================================================
    // 亮度读写
    // ================================================================

    /** 读取系统亮度，失败时返回 128 */
    private int readSystemBrightness() {
        try {
            return Settings.System.getInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS);
        } catch (Exception e) {
            return 128;
        }
    }

    /** 设置本页面窗口亮度；value < 0 表示跟随系统 */
    private void applyWindowBrightness(int value) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (value < 0) {
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        } else {
            float f = value / (float) MAX_BRIGHTNESS;
            if (f < 0.01f) f = 0.01f;   // 避免全黑看不见
            lp.screenBrightness = f;
        }
        getWindow().setAttributes(lp);
    }

    /** 写入系统亮度，成功返回 true */
    private boolean writeSystemBrightness(int value) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.System.canWrite(this)) {
            return false;
        }
        try {
            Settings.System.putInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, value);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 申请“修改系统设置”权限 */
    private void requestWriteSettingsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.System.canWrite(this)) {
                toast("已拥有修改系统设置权限");
                refreshBrightnessState();
                return;
            }
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                toast("请允许“修改系统设置”后返回");
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        } else {
            toast("当前系统无需额外授权");
        }
    }

    // ================================================================
    // 权限申请
    // ================================================================

    /** 申请全部文件访问权限 */
    private void requestAllFilePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                toast("已拥有全部文件访问权限");
                return;
            }
            try {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                toast("请在列表中找到本应用并打开开关");
            } catch (Exception e) {
                try {
                    startActivity(new Intent(
                            Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception e2) {
                    toast("当前系统不支持该设置页");
                }
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, REQ_STORAGE);
            } else {
                toast("当前系统默认拥有存储权限");
            }
        }
    }

    /** 申请普通存储权限 */
    private void requestNormalStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQ_STORAGE);
        } else {
            toast("当前系统无需动态申请存储权限");
        }
    }

    /** 申请相机权限 */
    private void requestCameraPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        } else {
            toast("当前系统无需动态申请相机权限");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        switch (requestCode) {
            case REQ_STORAGE:
                toast(granted ? "存储权限已授予" : "存储权限被拒绝");
                break;
            case REQ_CAMERA:
                toast(granted ? "相机权限已授予" : "相机权限被拒绝");
                break;
            default:
                break;
        }
    }

    // ================================================================
    // 系统设置跳转
    // ================================================================

    private void openSystemSettings(String action) {
        openSystemSettings(action, null);
    }

    private void openSystemSettings(String action, Uri data) {
        try {
            Intent intent = new Intent(action);
            if (data != null) {
                intent.setData(data);
            }
            startActivity(intent);
        } catch (Exception e) {
            toast("当前系统不支持该设置页面");
        }
    }

    // ================================================================
    // 通用 UI 工具
    // ================================================================

    private void addSection(LinearLayout root, String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(COLOR_ACCENT);
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(0, dp(18), 0, dp(8));
        root.addView(tv);
    }

    private void addButton(LinearLayout root, String text, Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(COLOR_TEXT);
        b.setTextSize(15);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setBackground(roundedBg(COLOR_CARD, dp(10)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        lp.bottomMargin = dp(8);
        b.setLayoutParams(lp);

        b.setOnClickListener(v -> {
            if (action != null) action.run();
        });
        root.addView(b);
    }

    private GradientDrawable roundedBg(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ================================================================
    // 生命周期
    // ================================================================

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统设置页返回后刷新状态
        if (brightnessStateView != null) {
            refreshBrightnessState();
            int cur = readSystemBrightness();
            if (brightnessBar != null) brightnessBar.setProgress(cur);
            updateBrightnessLabel(cur);
        }
    }
}
