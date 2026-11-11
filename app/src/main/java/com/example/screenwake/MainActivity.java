package com.example.screenwake;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements CommandPoller.ServerBindNotifyCallback {

    // -------- 颜色配置 --------
    private static final int COLOR_BG     = Color.rgb(20, 27, 36);
    private static final int COLOR_CARD   = Color.rgb(48, 59, 79);
    private static final int COLOR_TEXT   = Color.rgb(220, 227, 234);
    private static final int COLOR_SUB    = Color.rgb(150, 165, 180);
    private static final int COLOR_ACCENT = Color.rgb(100, 180, 255);
    private static final int COLOR_OK     = Color.rgb(120, 210, 140);

    private TextView globalStatusView;
    private final Handler ui = new Handler(Looper.getMainLooper());
    
    private final Runnable statusTicker = new Runnable() {
        @Override
        public void run() {
            refreshGlobalStatus();
            ui.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        getWindow().setStatusBarColor(COLOR_BG);
        getWindow().setNavigationBarColor(COLOR_BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(24));

        // 1. 头部标题
        addHeader(root);

        // 2. 全局运行状态卡片
        addGlobalStatusCard(root);

        // 3. 九大功能分类入口
        addSection(root, "📦 核心业务");
        // 假设你已经有了这些类，直接跳转
        addNavButton(root, "💬 文件接收", MultiWsActivity.class);
        addNavButton(root, "📱 亮屏测试", PatternActivity.class);
        addNavButton(root, "🌐 内置浏览器", BrowserActivity.class);

        addSection(root, "🛡️ 唤醒与监控");
        // 唤醒服务
        addNavButton(root, "⚡ 唤醒功能", ScreenWakeService.class);
        
        // 👇 下面这些还没建，暂时用提示代替，保证编译通过 👇
        addButton(root, "📊 状态检测 (待开发)", () -> toast("等待开发中..."));
        addButton(root, "⏰ 定时任务 (待开发)", () -> toast("等待开发中..."));

        addSection(root, "🗂️ 数据与设置");
        addButton(root, "📝 日志查看 (待开发)", () -> toast("等待开发中..."));
        addButton(root, "☁️ 数据上传 (待开发)", () -> toast("等待开发中..."));
        
        // SettingsPage 是你已经建好的
        addNavButton(root, "⚙️ 设置与权限", SettingsPage.class);

        // 4. 底部版本信息
        addFooter(root);

        scroll.addView(root);
        setContentView(scroll);

        ui.post(statusTicker);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshGlobalStatus();
        CommandPoller.setServerBindNotifyCallback(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CommandPoller.setServerBindNotifyCallback(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(statusTicker);
    }

    // ================================================================
    // 界面构建
    // ================================================================

    private void addHeader(LinearLayout root) {
        TextView title = new TextView(this);
        title.setText("ScreenWake 控制台");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(24);
        title.setPadding(0, 0, 0, dp(4));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("设备管理与自动化中枢");
        sub.setTextColor(COLOR_SUB);
        sub.setTextSize(14);
        sub.setPadding(0, 0, 0, dp(16));
        root.addView(sub);
    }

    private void addGlobalStatusCard(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedBg(COLOR_CARD, dp(12)));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        card.setLayoutParams(lp);

        TextView tv = new TextView(this);
        tv.setText("系统运行状态");
        tv.setTextColor(COLOR_ACCENT);
        tv.setTextSize(14);
        card.addView(tv);

        globalStatusView = new TextView(this);
        globalStatusView.setTextColor(COLOR_TEXT);
        globalStatusView.setTextSize(15);
        globalStatusView.setPadding(0, dp(8), 0, 0);
        globalStatusView.setLineSpacing(0, 1.2f);
        card.addView(globalStatusView);

        root.addView(card);
    }

    private void addSection(LinearLayout root, String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(COLOR_ACCENT);
        tv.setTextSize(15);
        tv.setPadding(dp(4), dp(16), 0, dp(8));
        root.addView(tv);
    }

    private void addNavButton(LinearLayout root, String text, Class<?> targetActivity) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(COLOR_TEXT);
        b.setTextSize(16);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setBackground(roundedBg(COLOR_CARD, dp(10)));
        b.setPadding(dp(16), 0, dp(16), 0);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        lp.bottomMargin = dp(10);
        b.setLayoutParams(lp);

        b.setOnClickListener(v -> {
            if (targetActivity == ScreenWakeService.class) {
                Intent intent = new Intent(this, ScreenWakeService.class);
                startService(intent);
                toast("唤醒服务已启动");
                return;
            }
            try {
                startActivity(new Intent(this, targetActivity));
            } catch (Exception e) {
                toast("该页面尚未创建或未在 Manifest 注册！");
            }
        });
        root.addView(b);
    }

    // 备用按钮（用于还没建的页面）
    private void addButton(LinearLayout root, String text, Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(COLOR_TEXT);
        b.setTextSize(16);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setBackground(roundedBg(COLOR_CARD, dp(10)));
        b.setPadding(dp(16), 0, dp(16), 0);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        lp.bottomMargin = dp(10);
        b.setLayoutParams(lp);

        b.setOnClickListener(v -> action.run());
        root.addView(b);
    }

    private void addFooter(LinearLayout root) {
        TextView footer = new TextView(this);
        footer.setText("ScreenWake v1.0\n稳定运行中");
        footer.setTextColor(COLOR_SUB);
        footer.setTextSize(12);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(32), 0, dp(16));
        root.addView(footer);
    }

    private void refreshGlobalStatus() {
        if (globalStatusView == null) return;
        
        String webStatusStr = WebMonitor.isStarted() ? "运行中" : "已停止";
        boolean isTimerEnabled = ConfigManager.isEnabled(this);
        String timerStr = isTimerEnabled ? "已开启" : "已关闭";
        String trustStr = (LogManager.getTrustedServerBindId(this) != null) ? "已绑定" : "未绑定";
        
        String info = "• 状态检测：" + webStatusStr + "\n"
                    + "• 定时任务：" + timerStr + "\n"
                    + "• 受信服务器：" + trustStr;
        
        globalStatusView.setText(info);
        globalStatusView.setTextColor(COLOR_OK);
    }

    @Override
    public void needShowBindDialog(String bindId, boolean isChanged) {
        String title = isChanged ? "服务器标识码已变更" : "收到新服务器绑定请求";
        String msg = isChanged 
                ? "服务器重启，标识码更新。是否信任该新服务器？\nID: " + bindId
                : "是否信任此服务器？\nID: " + bindId;

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("接收", (dialog, which) -> {
                    boolean ok = LogManager.setTrustedServerBindId(this, bindId);
                    if (ok) {
                        CommandPoller.start(this);
                        toast("已信任该服务器");
                    } else {
                        toast("保存信任ID失败");
                    }
                })
                .setNegativeButton("拒绝", (dialog, which) -> {
                    toast("已拒绝该服务器");
                })
                .setCancelable(false)
                .show();
    }

    private GradientDrawable roundedBg(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
