package com.example.screenwake;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements CommandPoller.ServerBindNotifyCallback {
    private int dp(float n) {
        return (int)(n *
                getResources().getDisplayMetrics().density + 0.5f);
    }
    private TextView timerStatus;
    private static final int REQUEST_MANAGE_STORAGE = 10086;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(18,23,33));
        getWindow().setNavigationBarColor(Color.BLACK);
        buildLauncher();
        AlarmScheduler.scheduleBaseline(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (timerStatus != null) {
            updateTimerStatus();
        }
        CommandPoller.setServerBindNotifyCallback(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CommandPoller.setServerBindNotifyCallback(null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MANAGE_STORAGE) {
            if (hasAllFilePermission()) {
                toast("权限已获取，再次点击查看日志");
            } else {
                toast("未授予全部文件访问权限，文件管理器可能无法打开目录");
            }
        }
    }

    // ========= 权限判断：安卓11+ MANAGE_EXTERNAL_STORAGE =========
    private boolean hasAllFilePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return true;
    }

    private void requestAllFilePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            Uri uri = Uri.parse("package:" + getPackageName());
            intent.setData(uri);
            startActivityForResult(intent, REQUEST_MANAGE_STORAGE);
        }
    }

    // ========= 收到服务器绑定弹窗 =========
    @Override
    public void needShowBindDialog(String bindId, boolean isChanged) {
        String title,msg;
        if(isChanged){
            title = "服务器标识码已变更";
            msg = "服务器重启，标识码更新。是否信任该新服务器？\nID:"+bindId;
        }else{
            title = "收到新服务器绑定请求";
            msg = "是否信任此服务器？\nID:"+bindId;
        }

        new AlertDialog.Builder(MainActivity.this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("接收", (dialog, which) -> {
                    boolean ok = LogManager.setTrustedServerBindId(MainActivity.this,bindId);
                    if(ok){
                        CommandPoller.start(MainActivity.this);
                        toast("已信任该服务器");
                    }else{
                        toast("保存信任ID失败，请检查存储权限");
                    }
                })
                .setNegativeButton("拒绝", (dialog, which) -> {
                    LogManager.log(MainActivity.this,LogManager.SUB_COMMAND,"用户拒绝服务器bindId="+bindId);
                    toast("已拒绝该服务器");
                })
                .setCancelable(false)
                .show();
    }

    private void buildLauncher() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16),dp(18),dp(16),dp(18));
        root.setBackgroundColor(Color.rgb(18,23,33));
        TextView title = new TextView(this);
        title.setText("本地启动控制中心");
        title.setTextColor(Color.WHITE);
        title.setTextSize(25);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0,dp(20),0,dp(8));
        root.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText("所有操作由你手动点击");
        subtitle.setTextColor(Color.LTGRAY);
        subtitle.setTextSize(13);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0,0,0,dp(24));
        root.addView(subtitle);
        addButton(root,"浏览器",
                () -> startActivity(
                        new Intent(this,BrowserActivity.class)
                )
        );
        addButton(root,"打开 Termux",
                this::openTermux
        );
        addButton(root,"立即亮屏测试（5分钟）",
                () -> startActivity(
                        new Intent(this,PatternActivity.class)
                )
        );
        addButton(root,"亮屏权限设置",
                () -> startActivity(
                        new Intent(this,SettingsActivity.class)
                )
        );
        // ================= 定时任务管理 =================
        addSection(root, "设备定时任务");
        timerStatus = new TextView(this);
        timerStatus.setTextColor(Color.rgb(130,220,170));
        timerStatus.setTextSize(14);
        timerStatus.setGravity(Gravity.CENTER);
        timerStatus.setPadding(dp(8),dp(12),dp(8),dp(12));
        root.addView(timerStatus);
        updateTimerStatus();

        addButton(root,"配置服务器地址和Token",
                this::showServerDialog
        );
        addButton(root,"设置定时周期（小时/分钟）",
                this::showIntervalDialog
        );
        addButton(root,"启用定时器",
                () -> {
                    AlarmScheduler.enable(this);
                    updateTimerStatus();
                    toast("定时器已启用");
                }
        );
        addButton(root,"关闭定时器",
                () -> {
                    AlarmScheduler.cancel(this);
                    updateTimerStatus();
                    toast("定时器已关闭");
                }
        );
        addButton(root,"立即触发一轮任务",
                () -> {
                    if (!ConfigManager.isEnabled(this)) {
                        toast("请先启用定时器");
                        return;
                    }
                    AlarmScheduler.triggerNow(this);
                    updateTimerStatus();
                    toast("已发送立即触发请求");
                }
        );
        // 【查看日志】修复版，带权限判断
        addButton(root,"查看日志", this::openLogFolder);

        // ======================【服务器信任管理按钮】======================
        addSection(root,"服务器信任管理");
        addButton(root,"查看当前信任服务器ID",()->{
            String id = LogManager.getTrustedServerBindId(this);
            if(id == null || id.isEmpty()){
                new AlertDialog.Builder(this)
                        .setTitle("受信任服务器")
                        .setMessage("当前未信任任何服务器")
                        .setPositiveButton("确定",null).show();
            }else{
                new AlertDialog.Builder(this)
                        .setTitle("受信任服务器bindId")
                        .setMessage(id)
                        .setPositiveButton("确定",null).show();
            }
        });
        addButton(root,"清除服务器信任标识码",()->{
            new AlertDialog.Builder(this)
                    .setTitle("清除信任")
                    .setMessage("确定清除本机信任的服务器标识码？清除后需要重新确认服务器。")
                    .setNegativeButton("取消",null)
                    .setPositiveButton("确定",(d,w)->{
                        boolean ok = LogManager.setTrustedServerBindId(this,null);
                        if(ok){
                            toast("已清除服务器信任标识码");
                        }else{
                            toast("清除失败，请检查存储权限");
                        }
                    }).show();
        });

        // ================= 原有系统设置 =================
        addSection(root, "系统设置");
        addButton(root,"Wi‑Fi 设置",
                () -> openSystemSettings(Settings.ACTION_WIFI_SETTINGS)
        );
        addButton(root,"网络设置",
                () -> openSystemSettings(Settings.ACTION_WIRELESS_SETTINGS)
        );
        addButton(root,"流量使用情况",
                () -> openSystemSettings(Settings.ACTION_DATA_USAGE_SETTINGS)
        );
        addButton(root,"本应用权限与后台设置",
                () -> openSystemSettings(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())
                )
        );
        addButton(root,"退出启动器",
                this::finish
        );
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    // ========= 修复后的查看日志，带权限判断，兜底不闪退 =========
    private void openLogFolder() {
        File baseDir = getExternalFilesDir(null);
        File logDir = new File(baseDir, "ScreenWakeLogs");
        if (!logDir.exists()) {
            toast("日志目录还未生成，请先触发一次上报");
            return;
        }
        // 没有全部文件权限，先申请
        if (!hasAllFilePermission()) {
            new AlertDialog.Builder(this)
                    .setTitle("需要全部文件访问权限")
                    .setMessage("打开日志文件夹需要授予【所有文件访问权限】，前往授权？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("去授权", (d, w) -> requestAllFilePermission())
                    .show();
            return;
        }
        // 优先文件管理器打开文件夹
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.fromFile(logDir), "resource/folder");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivity(intent);
        } else {
            // 兜底：选择文件查看器
            Intent chooser = new Intent(Intent.ACTION_GET_CONTENT);
            chooser.setType("*/*");
            chooser.addCategory(Intent.CATEGORY_OPENABLE);
            startActivity(Intent.createChooser(chooser, "打开日志文件夹"));
        }
    }

    private void addSection(LinearLayout root, String label) {
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextColor(Color.rgb(100,180,255));
        text.setTextSize(18);
        text.setPadding(0,dp(22),0,dp(8));
        root.addView(text);
    }

    private void addButton(
            LinearLayout root,
            String label,
            Runnable action
    ) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(16);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(10),dp(15),dp(10),dp(15));
        button.setBackgroundColor(Color.rgb(48,59,79));
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(-1,-2);
        lp.bottomMargin = dp(12);
        root.addView(button,lp);
        button.setOnClickListener(v -> action.run());
    }

    // ================= 定时器状态 =================
    private void updateTimerStatus() {
        if (timerStatus == null) return;
        boolean enabled = ConfigManager.isEnabled(this);
        long interval = ConfigManager.getIntervalMinutes(this);
        long next = ConfigManager.getNextTrigger(this);
        String state;
        if (!enabled) {
            state = "未启用";
        } else {
            state = "运行中";
        }
        String nextText = next > 0
                ? new SimpleDateFormat(
                "yyyy‑MM‑dd HH:mm:ss",
                Locale.getDefault()
        ).format(new Date(next))
                : "暂无计划";
        String info =
                "状态：" + state
                        + "\n周期：" + (interval / 60) + "小时"
                        + (interval % 60) + "分钟"
                        + "\n下次执行：" + nextText;
        timerStatus.setText(info);
    }

    // ================= 设置定时周期 =================
    private void showIntervalDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setPadding(dp(20),dp(12),dp(20),dp(8));
        EditText hours = new EditText(this);
        hours.setHint("小时");
        hours.setInputType(InputType.TYPE_CLASS_NUMBER);
        hours.setSingleLine(true);
        hours.setText(String.valueOf(
                ConfigManager.getIntervalMinutes(this) / 60));
        EditText minutes = new EditText(this);
        minutes.setHint("分钟");
        minutes.setInputType(InputType.TYPE_CLASS_NUMBER);
        minutes.setText(String.valueOf(
                ConfigManager.getIntervalMinutes(this) % 60));
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0,-2,1);
        layout.addView(hours,lp);
        layout.addView(minutes,lp);
        new AlertDialog.Builder(this)
                .setTitle("设置定时周期")
                .setMessage("范围：1分钟至30天")
                .setView(layout)
                .setNegativeButton("取消",null)
                .setPositiveButton("保存",(dialog,which) -> {
                    try {
                        int h = Integer.parseInt(
                                hours.getText().toString().trim());
                        int m = Integer.parseInt(
                                minutes.getText().toString().trim());
                        if (h < 0 || h > 720 || m < 0 || m > 59) {
                            toast("时间格式不正确");
                            return;
                        }
                        long total = (long)h * 60L + m;
                        if (total < 1 || total > 43200) {
                            toast("周期必须在1分钟至30天之间");
                            return;
                        }
                        ConfigManager.setIntervalMinutes(this,total);
                        if (ConfigManager.isEnabled(this)) {
                            AlarmScheduler.scheduleNext(this);
                        }
                        updateTimerStatus();
                        toast("定时周期已保存");
                    } catch (Exception e) {
                        toast("请输入有效的小时和分钟");
                    }
                })
                .show();
    }

    // ================= 服务器配置 =================
    private void showServerDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20),dp(8),dp(20),dp(8));
        EditText server = new EditText(this);
        server.setHint("服务器地址，例如 http://1.2.3.4:3357");
        server.setSingleLine(true);
        server.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(ConfigManager.getServerUrl(this));
        EditText token = new EditText(this);
        token.setHint("设备Token");
        token.setSingleLine(true);
        token.setInputType(InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setText(ConfigManager.getToken(this));
        layout.addView(server);
        layout.addView(token);
        new AlertDialog.Builder(this)
                .setTitle("服务器配置")
                .setMessage("请填写服务器地址（http/https 均可）和设备Token")
                .setView(layout)
                .setNegativeButton("取消",null)
                .setPositiveButton("保存",(dialog,which) -> {
                    String url = server.getText().toString().trim();
                    String key = token.getText().toString().trim();
                    if (url.isEmpty()
                            || key.isEmpty()
                            || !(url.startsWith("http://")
                            || url.startsWith("https://"))) {
                        toast("请填写有效的服务器地址（http 或 https）和Token");
                        return;
                    }
                    ConfigManager.setServerUrl(this,url);
                    ConfigManager.setToken(this,key);
                    toast("服务器配置已保存");
                })
                .show();
    }

    private void openTermux() {
        try {
            Intent intent =
                    getPackageManager().getLaunchIntentForPackage(
                            "com.termux"
                    );
            if(intent == null) {
                Toast.makeText(
                        this,
                        "没有找到 Termux",
                        Toast.LENGTH_LONG
                ).show();
                return;
            }
            startActivity(intent);
        } catch(Exception e) {
            showError(e);
        }
    }

    private void openSystemSettings(String action) {
        openSystemSettings(action,null);
    }

    private void openSystemSettings(String action,Uri uri) {
        try {
            Intent intent = new Intent(action);
            if(uri != null) {
                intent.setData(uri);
            }
            startActivity(intent);
        } catch(Exception e) {
            showError(e);
        }
    }

    private void toast(String message) {
        Toast.makeText(
                this,
                message,
                Toast.LENGTH_LONG
        ).show();
    }

    private void showError(Exception e) {
        Toast.makeText(
                this,
                "操作失败：" + e.getMessage(),
                Toast.LENGTH_LONG
        ).show();
    }
}


