package com.example.screenwake;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity implements CommandPoller.ServerBindNotifyCallback {

    private int dp(float n) {
        return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView timerStatus;
    private TextView webStatus;

    private static final int REQUEST_MANAGE_STORAGE = 10086;
    private static final int REQ_PERM_STORAGE = 1001;
    private static final int REQ_PERM_CAMERA = 1002;
    private static final int REQ_PICK_FILE = 3001;

    // ========= tail日志弹窗 =========
    private final Handler tailHandler = new Handler(Looper.getMainLooper());
    private AlertDialog tailLogDialog;
    private TextView tailLogText;
    private String tailCurrentSub = LogManager.SUB_APP;
    private final Runnable tailRunnable = new Runnable() {
        @Override
        public void run() {
            if (tailLogDialog != null && tailLogText != null) {
                String txt = LogManager.readTailDebug(MainActivity.this, tailCurrentSub, 20);
                tailLogText.setText(txt);
            }
            if (tailLogDialog != null && tailLogDialog.isShowing()) {
                tailHandler.postDelayed(this, 1200);
            }
        }
    };

    // ========= 状态刷新（每5秒） =========
    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusRunnable = new Runnable() {
        @Override
        public void run() {
            updateTimerStatus();
            updateWebStatus();
            statusHandler.postDelayed(this, 5000);
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(18, 23, 33));
        getWindow().setNavigationBarColor(Color.BLACK);
        buildLauncher();
        AlarmScheduler.scheduleBaseline(this);

        String hook = ConfigManager.getFeishuWebhook(this);
        String mode = ConfigManager.getDetectMode(this);
        boolean okSource = ConfigManager.MODE_FILE.equals(mode)
                ? !ConfigManager.getLocalFileUri(this).isEmpty()
                : !ConfigManager.getMonitorUrl(this).isEmpty();
        if (!hook.isEmpty() && okSource) {
            WebMonitor.start(this, ConfigManager.getMonitorUrl(this), hook);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateTimerStatus();
        updateWebStatus();
        statusHandler.removeCallbacks(statusRunnable);
        statusHandler.postDelayed(statusRunnable, 0);
        CommandPoller.setServerBindNotifyCallback(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        statusHandler.removeCallbacks(statusRunnable);
        CommandPoller.setServerBindNotifyCallback(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        statusHandler.removeCallbacks(statusRunnable);
        tailHandler.removeCallbacks(tailRunnable);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_MANAGE_STORAGE) {
            toast(hasAllFilePermission() ? "全部文件权限已获取" : "未授予全部文件访问权限");
            return;
        }

        // ===== SAF 选本地文本文件 =====
        if (requestCode == REQ_PICK_FILE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                ConfigManager.setLocalFileUri(this, uri.toString());
                ConfigManager.setDetectMode(this, ConfigManager.MODE_FILE);

                LogManager.log(this, LogManager.SUB_UPLOAD,
                        "已选择本地检测文件：" + uri.toString());

                WebMonitor.start(this,
                        ConfigManager.getMonitorUrl(this),
                        ConfigManager.getFeishuWebhook(this));

                updateWebStatus();
                confirmDialog("已选择本地文件",
                        "路径：" + uri.toString()
                                + "\n\n检测方式已切换为【本地文件】\n规则：0=休眠，1=发一次，2~500=按秒循环发送，读不到=不动作");
            } else {
                toast("未选择文件");
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults == null || grantResults.length == 0) return;
        switch (requestCode) {
            case REQ_PERM_STORAGE:
                toast(grantResults[0] == PackageManager.PERMISSION_GRANTED
                        ? "存储权限已授予" : "存储权限被拒绝");
                break;
            case REQ_PERM_CAMERA:
                toast(grantResults[0] == PackageManager.PERMISSION_GRANTED
                        ? "相机权限已授予" : "相机权限被拒绝");
                break;
        }
    }

    // ================= 权限 =================
    private boolean hasAllFilePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return android.os.Environment.isExternalStorageManager();
        }
        return true;
    }

    private void requestAllFilePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_MANAGE_STORAGE);
        } else {
            toast("Android10以下不需要该权限");
        }
    }

    private void requestNormalStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            toast("Android13+不再需要普通存储权限");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM_STORAGE);
        }
    }

    private void requestCameraPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_PERM_CAMERA);
        }
    }

    // ================= SAF 打开文件选择器 =================
    private void pickLocalFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_PICK_FILE);
        } catch (Exception e) {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.addCategory(Intent.CATEGORY_OPENABLE);
            fallback.setType("*/*");
            try {
                startActivityForResult(fallback, REQ_PICK_FILE);
            } catch (Exception e2) {
                toast("打开文件选择器失败：" + e2.getMessage());
            }
        }
    }

    // ================= 私有目录测试 =================
    private void testPrivateWrite() {
        File baseDir = getExternalFilesDir(null);
        StringBuilder sb = new StringBuilder();
        sb.append("=== 目录检测 ===\n");
        sb.append("getExternalFilesDir = ")
                .append(baseDir == null ? "【NULL，系统返回空！】" : baseDir.getAbsolutePath())
                .append("\n");

        if (baseDir == null) {
            sb.append("❌ 致命：系统返回空目录！ROM限制\n");
        } else {
            File testFile = new File(baseDir, "write_test.txt");
            sb.append("测试文件路径：").append(testFile.getAbsolutePath()).append("\n");
            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(testFile))) {
                w.write("test " + System.currentTimeMillis());
                w.flush();
                sb.append("✅ 文件写入成功\n");
                if (testFile.exists()) sb.append("✅ 文件exists=true\n");
            } catch (Exception e) {
                sb.append("❌ 写入异常：").append(e.getClass().getSimpleName())
                        .append(" | ").append(e.getMessage()).append("\n");
            }
        }
        confirmDialog("私有目录读写测试", sb.toString());
    }

    // ================= 服务器绑定回调 =================
    @Override
    public void needShowBindDialog(String bindId, boolean isChanged) {
        String title = isChanged ? "服务器标识码已变更" : "收到新服务器绑定请求";
        String msg = isChanged
                ? "服务器重启，标识码更新。是否信任该新服务器？\nID:" + bindId
                : "是否信任此服务器？\nID:" + bindId;

        new AlertDialog.Builder(MainActivity.this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("接收", (dialog, which) -> {
                    boolean ok = LogManager.setTrustedServerBindId(MainActivity.this, bindId);
                    if (ok) {
                        CommandPoller.start(MainActivity.this);
                        toast("已信任该服务器");
                    } else {
                        toast("保存信任ID失败");
                    }
                })
                .setNegativeButton("拒绝", (dialog, which) -> {
                    LogManager.log(MainActivity.this, LogManager.SUB_COMMAND,
                            "用户拒绝服务器bindId=" + bindId);
                    toast("已拒绝该服务器");
                })
                .setCancelable(false)
                .show();
    }

    // ================= tail 实时日志 =================
    private void showTailLogDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));

        LinearLayout barLayout = new LinearLayout(this);
        barLayout.setOrientation(LinearLayout.HORIZONTAL);
        barLayout.setGravity(Gravity.CENTER);
        barLayout.setPadding(0, 0, 0, dp(12));

        TextView btnApp = new TextView(this);
        btnApp.setText("App日志");
        btnApp.setPadding(dp(8), dp(6), dp(8), dp(6));
        btnApp.setTextSize(13);
        btnApp.setOnClickListener(v -> tailCurrentSub = LogManager.SUB_APP);

        TextView btnUpload = new TextView(this);
        btnUpload.setText("上报Upload日志");
        btnUpload.setPadding(dp(8), dp(6), dp(8), dp(6));
        btnUpload.setTextSize(13);
        btnUpload.setOnClickListener(v -> tailCurrentSub = LogManager.SUB_UPLOAD);

        TextView btnCmd = new TextView(this);
        btnCmd.setText("指令Command日志");
        btnCmd.setPadding(dp(8), dp(6), dp(8), dp(6));
        btnCmd.setTextSize(13);
        btnCmd.setOnClickListener(v -> tailCurrentSub = LogManager.SUB_COMMAND);

        barLayout.addView(btnApp);
        barLayout.addView(btnUpload);
        barLayout.addView(btnCmd);
        layout.addView(barLayout);

        tailLogText = new TextView(this);
        tailLogText.setTextSize(11);
        tailLogText.setMaxHeight(dp(400));
        tailLogText.setMovementMethod(ScrollingMovementMethod.getInstance());

        ScrollView sv = new ScrollView(this);
        sv.addView(tailLogText);
        layout.addView(sv);

        builder.setTitle("实时日志 tail-20行｜点击切换日志分类");
        builder.setView(layout);
        builder.setNegativeButton("关闭", (dialog, which) -> {
            tailHandler.removeCallbacks(tailRunnable);
            tailLogDialog = null;
            tailLogText = null;
        });

        tailLogDialog = builder.create();
        tailLogDialog.show();
        tailHandler.post(tailRunnable);
    }

    // ================= 主界面 =================
    private void buildLauncher() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(18));
        root.setBackgroundColor(Color.rgb(18, 23, 33));

        TextView title = new TextView(this);
        title.setText("本地启动控制中心");
        title.setTextColor(Color.WHITE);
        title.setTextSize(25);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(20), 0, dp(8));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("所有操作由你手动点击");
        subtitle.setTextColor(Color.LTGRAY);
        subtitle.setTextSize(13);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 0, 0, dp(24));
        root.addView(subtitle);

        addButton(root, "浏览器", () -> startActivity(new Intent(this, BrowserActivity.class)));
        addButton(root, "打开 Termux", this::openTermux);
        addButton(root, "立即亮屏测试（5分钟）", () -> startActivity(new Intent(this, PatternActivity.class)));
        addButton(root, "亮屏权限设置", () -> startActivity(new Intent(this, SettingsActivity.class)));

        // ================= 检测中心 =================
        addSection(root, "状态检测（网页 / 本地文件）");

        webStatus = new TextView(this);
        webStatus.setTextColor(Color.rgb(255, 210, 130));
        webStatus.setTextSize(14);
        webStatus.setGravity(Gravity.CENTER);
        webStatus.setPadding(dp(8), dp(12), dp(8), dp(12));
        root.addView(webStatus);
        updateWebStatus();

        addButton(root, "选择检测方式（网页 / 本地文件）", this::showDetectModeDialog);
        addButton(root, "选择本地文本文件（SAF）", this::pickLocalFile);
        addButton(root, "配置网页地址与飞书Webhook", this::showMonitorConfigDialog);
        addButton(root, "设置休眠时长（1分钟~1天）", this::showSleepDialog);
        addButton(root, "启动检测（10秒一次）", () -> {
            String hook = ConfigManager.getFeishuWebhook(this);
            if (hook.isEmpty()) {
                toast("请先配置飞书Webhook");
                return;
            }
            String mode = ConfigManager.getDetectMode(this);
            if (ConfigManager.MODE_FILE.equals(mode)) {
                if (ConfigManager.getLocalFileUri(this).isEmpty()) {
                    toast("请先选择本地文本文件");
                    return;
                }
            } else {
                if (ConfigManager.getMonitorUrl(this).isEmpty()) {
                    toast("请先配置监控网页地址");
                    return;
                }
            }
            WebMonitor.start(this, ConfigManager.getMonitorUrl(this), hook);
            updateWebStatus();
            toast("已启动，每10秒检测一次");
        });
        addButton(root, "停止检测", () -> {
            WebMonitor.stop();
            updateWebStatus();
            toast("已停止检测与发送");
        });
        addButton(root, "清除休眠并立即恢复", () -> {
            WebMonitor.clearSleepAndResume();
            updateWebStatus();
            toast("已清除休眠");
        });
        addButton(root, "立即手动上报一次", () -> {
            String hook = ConfigManager.getFeishuWebhook(this);
            if (hook.isEmpty()) {
                toast("请先配置飞书Webhook");
                return;
            }
            DeviceUploader.send(this, hook, "MyKey1234567890", DeviceInfo.collect(this),
                    (ok, msg) -> toast((ok ? "上报成功：" : "上报失败：") + msg));
        });
        addButton(root, "实时查看日志(tail-20行)", this::showTailLogDialog);
        addButton(root, "测试私有目录读写", this::testPrivateWrite);
        addButton(root, "查看日志（打开文件管理器）", this::openLogFolder);

        // ================= 定时任务 =================
        addSection(root, "设备定时任务");
        timerStatus = new TextView(this);
        timerStatus.setTextColor(Color.rgb(130, 220, 170));
        timerStatus.setTextSize(14);
        timerStatus.setGravity(Gravity.CENTER);
        timerStatus.setPadding(dp(8), dp(12), dp(8), dp(12));
        root.addView(timerStatus);
        updateTimerStatus();

        addButton(root, "配置服务器地址和Token（旧版保留）", this::showServerDialog);
        addButton(root, "设置定时周期（小时/分钟）", this::showIntervalDialog);
        addButton(root, "启用定时器", () -> {
            AlarmScheduler.enable(this);
            updateTimerStatus();
            toast("定时器已启用");
        });
        addButton(root, "关闭定时器", () -> {
            AlarmScheduler.cancel(this);
            updateTimerStatus();
            toast("定时器已关闭");
        });
        addButton(root, "立即触发一轮任务", () -> {
            if (!ConfigManager.isEnabled(this)) {
                toast("请先启用定时器");
                return;
            }
            AlarmScheduler.triggerNow(this);
            updateTimerStatus();
            toast("已发送立即触发请求");
        });

        // ================= 服务器信任 =================
        addSection(root, "服务器信任管理");
        addButton(root, "查看当前信任服务器ID", () -> {
            String id = LogManager.getTrustedServerBindId(this);
            if (id == null || id.isEmpty()) {
                confirmDialog("受信任服务器", "当前未信任任何服务器");
            } else {
                confirmDialog("受信任服务器bindId", id);
            }
        });
        addButton(root, "清除服务器信任标识码", () -> new AlertDialog.Builder(this)
                .setTitle("清除信任")
                .setMessage("确定清除本机信任的服务器标识码？")
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> {
                    boolean ok = LogManager.setTrustedServerBindId(this, null);
                    toast(ok ? "已清除服务器信任标识码" : "清除失败，请检查存储权限");
                }).show());

        // ================= 系统设置与权限 =================
        addSection(root, "系统设置与权限申请");
        addButton(root, "申请全部文件访问权限", this::requestAllFilePermission);
        addButton(root, "申请普通存储权限", this::requestNormalStoragePermission);
        addButton(root, "申请相机权限", this::requestCameraPermission);
        addButton(root, "Wi-Fi 设置", () -> openSystemSettings(Settings.ACTION_WIFI_SETTINGS));
        addButton(root, "网络设置", () -> openSystemSettings(Settings.ACTION_WIRELESS_SETTINGS));
        addButton(root, "流量使用情况", () -> openSystemSettings(Settings.ACTION_DATA_USAGE_SETTINGS));
        addButton(root, "本应用权限与后台设置", () -> openSystemSettings(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
        addButton(root, "退出启动器", this::finish);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    // ================= 状态显示 =================
    private void updateWebStatus() {
        if (webStatus == null) return;

        String mode = ConfigManager.getDetectMode(this);
        String src = ConfigManager.MODE_FILE.equals(mode)
                ? ("本地文件：" + shortUri(ConfigManager.getLocalFileUri(this)))
                : ("网页：" + ConfigManager.getMonitorUrl(this));

        String state;
        if (!WebMonitor.isStarted()) {
            state = "未启动";
        } else if (WebMonitor.isSleeping()) {
            long sec = WebMonitor.getSleepRemainMs() / 1000;
            state = "休眠中，剩余 " + (sec / 60) + " 分 " + (sec % 60) + " 秒";
        } else if (WebMonitor.getSendIntervalSec() > 0) {
            state = "检测中(10秒) + 每 " + WebMonitor.getSendIntervalSec() + " 秒发送";
        } else {
            state = "检测中（10秒一次）";
        }

        String info = "检测源：" + src
                + "\n状态：" + state
                + "\n休眠时长：" + ConfigManager.getSleepMinutes(this) + " 分钟";
        webStatus.setText(info);
    }

    private String shortUri(String uri) {
        if (uri == null || uri.isEmpty()) return "未选择";
        if (uri.length() <= 42) return uri;
        return uri.substring(0, 18) + "..." + uri.substring(uri.length() - 18);
    }

    private void updateTimerStatus() {
        if (timerStatus == null) return;
        boolean enabled = ConfigManager.isEnabled(this);
        long interval = ConfigManager.getIntervalMinutes(this);
        long next = ConfigManager.getNextTrigger(this);
        String nextText = next > 0
                ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(next))
                : "暂无计划";
        timerStatus.setText("状态：" + (enabled ? "运行中" : "未启用")
                + "\n周期：" + (interval / 60) + "小时" + (interval % 60) + "分钟"
                + "\n下次执行：" + nextText);
    }

    // ================= 检测方式选择弹窗 =================
    private void showDetectModeDialog() {
        String mode = ConfigManager.getDetectMode(this);
        String[] items = new String[]{
                (ConfigManager.MODE_WEB.equals(mode) ? "【当前】" : "") + "检测网页",
                (ConfigManager.MODE_FILE.equals(mode) ? "【当前】" : "") + "检测本地文本文件"
        };

        new AlertDialog.Builder(this)
                .setTitle("选择检测方式")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        ConfigManager.setDetectMode(this, ConfigManager.MODE_WEB);
                    } else {
                        ConfigManager.setDetectMode(this, ConfigManager.MODE_FILE);
                    }
                    LogManager.log(this, LogManager.SUB_UPLOAD,
                            "检测方式切换为：" + ConfigManager.getDetectMode(this));
                    WebMonitor.start(this,
                            ConfigManager.getMonitorUrl(this),
                            ConfigManager.getFeishuWebhook(this));
                    updateWebStatus();
                    toast(which == 0 ? "已切换为检测网页" : "已切换为检测本地文件");
                })
                .show();
    }

    // ================= 网页+Webhook配置弹窗 =================
    private void showMonitorConfigDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(8), dp(20), dp(8));

        EditText urlInput = new EditText(this);
        urlInput.setHint("监控网页地址，如 https://xxx.com/status.txt");
        urlInput.setSingleLine(true);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setText(ConfigManager.getMonitorUrl(this));

        EditText hookInput = new EditText(this);
        hookInput.setHint("飞书Webhook地址，https://open.feishu.cn/...");
        hookInput.setSingleLine(true);
        hookInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        hookInput.setText(ConfigManager.getFeishuWebhook(this));

        layout.addView(urlInput);
        layout.addView(hookInput);

        new AlertDialog.Builder(this)
                .setTitle("网页地址与飞书Webhook")
                .setMessage("规则：1=发一次；5=每5秒发；50=每50秒发；0=休眠；打不开=不动作")
                .setView(layout)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    String url = urlInput.getText().toString().trim();
                    String hook = hookInput.getText().toString().trim();
                    if (hook.isEmpty()) {
                        toast("飞书Webhook必须填写");
                        return;
                    }
                    if (!hook.startsWith("http://") && !hook.startsWith("https://")) {
                        toast("飞书Webhook必须以 http:// 或 https:// 开头");
                        return;
                    }
                    if (!url.isEmpty()
                            && !url.startsWith("http://")
                            && !url.startsWith("https://")) {
                        toast("网页地址必须以 http:// 或 https:// 开头");
                        return;
                    }
                    ConfigManager.setFeishuWebhook(this, hook);
                    ConfigManager.setMonitorUrl(this, url);
                    WebMonitor.start(this, url, hook);
                    updateWebStatus();
                    toast("已保存并启动检测");
                })
                .show();
    }

    // ================= 休眠时长弹窗 =================
    private void showSleepDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setPadding(dp(20), dp(12), dp(20), dp(8));

        EditText input = new EditText(this);
        input.setHint("分钟");
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setText(String.valueOf(ConfigManager.getSleepMinutes(this)));

        TextView tip = new TextView(this);
        tip.setText("单位：分钟");
        tip.setTextSize(13);
        tip.setTextColor(Color.LTGRAY);
        tip.setPadding(dp(10), 0, dp(10), 0);
        tip.setGravity(Gravity.CENTER);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        layout.addView(input, lp);
        layout.addView(tip);

        new AlertDialog.Builder(this)
                .setTitle("设置休眠时长")
                .setMessage("范围：1 ~ 1440 分钟（1天）\n检测读到 0 时按此时长休眠，期间不检测不发送")
                .setView(layout)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    try {
                        int m = Integer.parseInt(input.getText().toString().trim());
                        if (m < 1 || m > 1440) {
                            toast("必须在 1 ~ 1440 分钟之间");
                            return;
                        }
                        ConfigManager.setSleepMinutes(this, m);
                        updateWebStatus();
                        toast("已保存：" + m + " 分钟");
                    } catch (Exception e) {
                        toast("请输入有效数字");
                    }
                })
                .show();
    }

    // ================= 定时周期弹窗 =================
    private void showIntervalDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setPadding(dp(20), dp(12), dp(20), dp(8));

        EditText hours = new EditText(this);
        hours.setHint("小时");
        hours.setInputType(InputType.TYPE_CLASS_NUMBER);
        hours.setSingleLine(true);
        hours.setText(String.valueOf(ConfigManager.getIntervalMinutes(this) / 60));

        EditText minutes = new EditText(this);
        minutes.setHint("分钟");
        minutes.setInputType(InputType.TYPE_CLASS_NUMBER);
        minutes.setSingleLine(true);
        minutes.setText(String.valueOf(ConfigManager.getIntervalMinutes(this) % 60));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        layout.addView(hours, lp);
        layout.addView(minutes, lp);

        new AlertDialog.Builder(this)
                .setTitle("设置定时周期")
                .setMessage("范围：1分钟至30天")
                .setView(layout)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    try {
                        int h = Integer.parseInt(hours.getText().toString().trim());
                        int m = Integer.parseInt(minutes.getText().toString().trim());
                        if (h < 0 || h > 720 || m < 0 || m > 59) {
                            toast("时间格式不正确");
                            return;
                        }
                        long total = (long) h * 60L + m;
                        if (total < 1 || total > 43200) {
                            toast("周期必须在1分钟至30天");
                            return;
                        }
                        ConfigManager.setIntervalMinutes(this, total);
                        if (ConfigManager.isEnabled(this)) AlarmScheduler.scheduleNext(this);
                        updateTimerStatus();
                        toast("定时周期已保存");
                    } catch (Exception e) {
                        toast("请输入有效的小时和分钟");
                    }
                })
                .show();
    }

    // ================= 服务器配置（旧版保留） =================
    private void showServerDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(8), dp(20), dp(8));

        EditText server = new EditText(this);
        server.setHint("服务器地址，例如 http://1.2.3.4:3357");
        server.setSingleLine(true);
        server.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(ConfigManager.getServerUrl(this));

        EditText token = new EditText(this);
        token.setHint("设备Token");
        token.setSingleLine(true);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setText(ConfigManager.getToken(this));

        layout.addView(server);
        layout.addView(token);

        new AlertDialog.Builder(this)
                .setTitle("服务器配置")
                .setMessage("旧版自建服务器用，现在主用飞书Webhook，可留空")
                .setView(layout)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    String url = server.getText().toString().trim();
                    String key = token.getText().toString().trim();
                    if (url.isEmpty()
                            || key.isEmpty()
                            || (!url.startsWith("http://") && !url.startsWith("https://"))) {
                        toast("请填写有效的服务器地址和Token");
                        return;
                    }
                    ConfigManager.setServerUrl(this, url);
                    ConfigManager.setToken(this, key);
                    toast("服务器配置已保存");
                })
                .show();
    }

    // ================= 打开日志文件夹 =================
    private void openLogFolder() {
        File baseDir = getExternalFilesDir(null);
        File logDir = new File(baseDir, "ScreenWakeLogs");
        if (!logDir.exists()) {
            toast("日志目录还未生成，请先触发一次上报");
            return;
        }
        if (!hasAllFilePermission()) {
            new AlertDialog.Builder(this)
                    .setTitle("需要全部文件访问权限")
                    .setMessage("打开日志文件夹需要授予【所有文件访问权限】，前往授权？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("去授权", (d, w) -> requestAllFilePermission())
                    .show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.fromFile(logDir), "resource/folder");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivity(intent);
        } else {
            Intent chooser = new Intent(Intent.ACTION_GET_CONTENT);
            chooser.setType("*/*");
            chooser.addCategory(Intent.CATEGORY_OPENABLE);
            startActivity(Intent.createChooser(chooser, "打开日志文件夹"));
        }
    }

    // ================= 布局辅助 =================
    private void addSection(LinearLayout root, String label) {
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextColor(Color.rgb(100, 180, 255));
        text.setTextSize(18);
        text.setPadding(0, dp(22), 0, dp(8));
        root.addView(text);
    }

    private void addButton(LinearLayout root, String label, Runnable action) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(16);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(10), dp(15), dp(10), dp(15));
        button.setBackgroundColor(Color.rgb(48, 59, 79));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(12);
        root.addView(button, lp);
        button.setOnClickListener(v -> action.run());
    }

    // ================= 其它 =================
    private void confirmDialog(String title, String msg) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("确定", null)
                .show();
    }

    private void openTermux() {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage("com.termux");
            if (intent == null) {
                toast("没有找到 Termux");
                return;
            }
            startActivity(intent);
        } catch (Exception e) {
            showError(e);
        }
    }

    private void openSystemSettings(String action) {
        openSystemSettings(action, null);
    }

    private void openSystemSettings(String action, Uri uri) {
        try {
            Intent intent = new Intent(action);
            if (uri != null) intent.setData(uri);
            startActivity(intent);
        } catch (Exception e) {
            showError(e);
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void showError(Exception e) {
        Toast.makeText(this, "操作失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
    }
}



