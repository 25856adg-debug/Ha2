package com.example.screenwake;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements CommandPoller.ServerBindNotifyCallback {

    // -------- 颜色配置 (浅色主题) --------
    private static final int COLOR_BG     = Color.rgb(245, 247, 250); // 浅灰背景
    private static final int COLOR_WHITE  = Color.rgb(255, 255, 255); // 纯白卡片
    private static final int COLOR_TEXT   = Color.rgb(51, 51, 51);    // 主标题深黑
    private static final int COLOR_SUB    = Color.rgb(153, 153, 153); // 副标题浅灰
    private static final int COLOR_BLUE   = Color.rgb(45, 134, 255);  // 顶部渐变蓝

    // 金刚区图标颜色池
    private static final int[] ICON_COLORS = {
            Color.rgb(116, 178, 255), // 浅蓝
            Color.rgb(86, 206, 172),  // 浅绿
            Color.rgb(255, 166, 77),  // 橙色
            Color.rgb(255, 128, 153), // 粉红
            Color.rgb(152, 144, 255), // 紫色
            Color.rgb(88, 193, 255),  // 天蓝
            Color.rgb(255, 192, 65),  // 黄色
            Color.rgb(93, 215, 235),  // 青色
            Color.rgb(180, 142, 255)  // 浅紫
    };

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
        
        getWindow().setStatusBarColor(COLOR_BLUE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // 1. 顶部蓝色 Header
        addHeader(root);

        // 2. 状态卡片 (稍微往上移一点，形成叠压效果)
        LinearLayout statusWrapper = new LinearLayout(this);
        statusWrapper.setPadding(dp(16), 0, dp(16), dp(16));
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wrapLp.topMargin = -dp(20); // 向上偏移，压住蓝色背景
        statusWrapper.setLayoutParams(wrapLp);
        addStatusCard(statusWrapper);
        root.addView(statusWrapper);

        // 3. 金刚区 (九宫格)
        addGridSection(root);

        // 4. 底部版本信息
        addFooter(root);

        scroll.addView(root);
        setContentView(scroll);

        ui.post(statusTicker);
    }

    // ================================================================
    // 界面构建
    // ================================================================

    private void addHeader(LinearLayout root) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        
        // 渐变蓝背景
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(50, 140, 255), Color.rgb(30, 90, 255)});
        bg.setCornerRadii(new float[]{0, 0, 0, 0, dp(24), dp(24), dp(24), dp(24)}); // 底部圆角
        header.setBackground(bg);
        
        header.setPadding(dp(24), dp(48), dp(24), dp(48));

        TextView title = new TextView(this);
        title.setText("ScreenWake");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title);

        TextView sub = new TextView(this);
        sub.setText("设备管理与自动化中枢");
        sub.setTextColor(Color.parseColor("#CCFFFFFF"));
        sub.setTextSize(14);
        sub.setPadding(0, dp(8), 0, 0);
        header.addView(sub);

        root.addView(header);
    }

    private void addStatusCard(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedBg(COLOR_WHITE, dp(12)));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        
        // 简单的阴影模拟 (elevation)
        card.setElevation(dp(4));

        TextView tv = new TextView(this);
        tv.setText("📊 系统运行状态");
        tv.setTextColor(COLOR_TEXT);
        tv.setTextSize(15);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(tv);

        globalStatusView = new TextView(this);
        globalStatusView.setTextColor(COLOR_SUB);
        globalStatusView.setTextSize(13);
        globalStatusView.setPadding(0, dp(8), 0, 0);
        globalStatusView.setLineSpacing(0, 1.3f);
        card.addView(globalStatusView);

        root.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void addGridSection(LinearLayout root) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setBackground(roundedBg(COLOR_WHITE, dp(12)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(16), 0, dp(16), dp(16));
        section.setLayoutParams(lp);
        section.setPadding(dp(8), dp(16), dp(8), dp(16));

        // 添加 9 个金刚区图标 (3x3)
        // 第 1 行
        addGridRow(section,
                new GridItem("文件接收", "💬", 0, MultiWsActivity.class, null),
                new GridItem("亮屏测试", "📱", 1, PatternActivity.class, null),
                new GridItem("浏览器", "🌐", 2, BrowserActivity.class, null)
        );
        // 第 2 行
        addGridRow(section,
                new GridItem("唤醒功能", "⚡", 3, ScreenWakeService.class, null),
                new GridItem("状态检测", "📊", 4, null, "等待开发"),
                new GridItem("定时任务", "⏰", 5, null, "等待开发")
        );
        // 第 3 行
        addGridRow(section,
                new GridItem("日志查看", "📝", 6, null, "等待开发"),
                new GridItem("数据上传", "☁️", 7, null, "等待开发"),
                new GridItem("设置权限", "⚙️", 8, SettingsPage.class, null)
        );

        root.addView(section);
    }

    private void addGridRow(LinearLayout parent, GridItem... items) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, dp(12));

        for (GridItem item : items) {
            LinearLayout itemView = new LinearLayout(this);
            itemView.setOrientation(LinearLayout.VERTICAL);
            itemView.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            itemView.setLayoutParams(itemLp);

            // 彩色圆形背景
            TextView icon = new TextView(this);
            icon.setText(item.emoji);
            icon.setTextSize(24);
            icon.setGravity(Gravity.CENTER);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(ICON_COLORS[item.colorIndex % ICON_COLORS.length]);
            icon.setBackground(circle);
            
            // 圆形大小
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(52), dp(52));
            icon.setLayoutParams(iconLp);
            itemView.addView(icon);

            // 文字
            TextView text = new TextView(this);
            text.setText(item.title);
            text.setTextColor(COLOR_TEXT);
            text.setTextSize(13);
            text.setPadding(0, dp(8), 0, 0);
            itemView.addView(text);

            // 点击事件
            itemView.setOnClickListener(v -> {
                if (item.toastMsg != null) {
                    Toast.makeText(this, item.toastMsg, Toast.LENGTH_SHORT).show();
                } else if (item.target == ScreenWakeService.class) {
                    startService(new Intent(this, ScreenWakeService.class));
                    Toast.makeText(this, "唤醒服务已启动", Toast.LENGTH_SHORT).show();
                } else if (item.target != null) {
                    try {
                        startActivity(new Intent(this, item.target));
                    } catch (Exception e) {
                        Toast.makeText(this, "页面未创建或未注册", Toast.LENGTH_SHORT).show();
                    }
                }
            });

            row.addView(itemView);
        }
        parent.addView(row);
    }

    private void addFooter(LinearLayout root) {
        TextView footer = new TextView(this);
        footer.setText("ScreenWake v1.0\n稳定运行中");
        footer.setTextColor(COLOR_SUB);
        footer.setTextSize(12);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(16), 0, dp(32));
        root.addView(footer);
    }

    // ================================================================
    // 数据与逻辑
    // ================================================================

    // 金刚区数据模型
    private static class GridItem {
        String title;
        String emoji;
        int colorIndex;
        Class<?> target;
        String toastMsg; // 如果为 null，表示可以跳转；如果不为 null，则弹 Toast

        GridItem(String title, String emoji, int colorIndex, Class<?> target, String toastMsg) {
            this.title = title;
            this.emoji = emoji;
            this.colorIndex = colorIndex;
            this.target = target;
            this.toastMsg = toastMsg;
        }
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

    private void refreshGlobalStatus() {
        if (globalStatusView == null) return;
        
        String webStatusStr = WebMonitor.isStarted() ? "运行中" : "已停止";
        boolean isTimerEnabled = ConfigManager.isEnabled(this);
        String timerStr = isTimerEnabled ? "已开启" : "已关闭";
        String trustStr = (LogManager.getTrustedServerBindId(this) != null) ? "已绑定" : "未绑定";
        
        String info = "检测状态：" + webStatusStr + "  |  定时任务：" + timerStr + "\n"
                    + "受信服务器：" + trustStr;
        
        globalStatusView.setText(info);
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
                        Toast.makeText(this, "已信任该服务器", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("拒绝", (dialog, which) -> {
                    Toast.makeText(this, "已拒绝该服务器", Toast.LENGTH_SHORT).show();
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
}package com.example.screenwake;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements CommandPoller.ServerBindNotifyCallback {

    // -------- 颜色配置 (浅色主题) --------
    private static final int COLOR_BG     = Color.rgb(245, 247, 250); // 浅灰背景
    private static final int COLOR_WHITE  = Color.rgb(255, 255, 255); // 纯白卡片
    private static final int COLOR_TEXT   = Color.rgb(51, 51, 51);    // 主标题深黑
    private static final int COLOR_SUB    = Color.rgb(153, 153, 153); // 副标题浅灰
    private static final int COLOR_BLUE   = Color.rgb(45, 134, 255);  // 顶部渐变蓝

    // 金刚区图标颜色池
    private static final int[] ICON_COLORS = {
            Color.rgb(116, 178, 255), // 浅蓝
            Color.rgb(86, 206, 172),  // 浅绿
            Color.rgb(255, 166, 77),  // 橙色
            Color.rgb(255, 128, 153), // 粉红
            Color.rgb(152, 144, 255), // 紫色
            Color.rgb(88, 193, 255),  // 天蓝
            Color.rgb(255, 192, 65),  // 黄色
            Color.rgb(93, 215, 235),  // 青色
            Color.rgb(180, 142, 255)  // 浅紫
    };

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
        
        getWindow().setStatusBarColor(COLOR_BLUE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // 1. 顶部蓝色 Header
        addHeader(root);

        // 2. 状态卡片 (稍微往上移一点，形成叠压效果)
        LinearLayout statusWrapper = new LinearLayout(this);
        statusWrapper.setPadding(dp(16), 0, dp(16), dp(16));
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wrapLp.topMargin = -dp(20); // 向上偏移，压住蓝色背景
        statusWrapper.setLayoutParams(wrapLp);
        addStatusCard(statusWrapper);
        root.addView(statusWrapper);

        // 3. 金刚区 (九宫格)
        addGridSection(root);

        // 4. 底部版本信息
        addFooter(root);

        scroll.addView(root);
        setContentView(scroll);

        ui.post(statusTicker);
    }

    // ================================================================
    // 界面构建
    // ================================================================

    private void addHeader(LinearLayout root) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        
        // 渐变蓝背景
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(50, 140, 255), Color.rgb(30, 90, 255)});
        bg.setCornerRadii(new float[]{0, 0, 0, 0, dp(24), dp(24), dp(24), dp(24)}); // 底部圆角
        header.setBackground(bg);
        
        header.setPadding(dp(24), dp(48), dp(24), dp(48));

        TextView title = new TextView(this);
        title.setText("ScreenWake");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title);

        TextView sub = new TextView(this);
        sub.setText("设备管理与自动化中枢");
        sub.setTextColor(Color.parseColor("#CCFFFFFF"));
        sub.setTextSize(14);
        sub.setPadding(0, dp(8), 0, 0);
        header.addView(sub);

        root.addView(header);
    }

    private void addStatusCard(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedBg(COLOR_WHITE, dp(12)));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        
        // 简单的阴影模拟 (elevation)
        card.setElevation(dp(4));

        TextView tv = new TextView(this);
        tv.setText("📊 系统运行状态");
        tv.setTextColor(COLOR_TEXT);
        tv.setTextSize(15);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(tv);

        globalStatusView = new TextView(this);
        globalStatusView.setTextColor(COLOR_SUB);
        globalStatusView.setTextSize(13);
        globalStatusView.setPadding(0, dp(8), 0, 0);
        globalStatusView.setLineSpacing(0, 1.3f);
        card.addView(globalStatusView);

        root.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void addGridSection(LinearLayout root) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setBackground(roundedBg(COLOR_WHITE, dp(12)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(16), 0, dp(16), dp(16));
        section.setLayoutParams(lp);
        section.setPadding(dp(8), dp(16), dp(8), dp(16));

        // 添加 9 个金刚区图标 (3x3)
        // 第 1 行
        addGridRow(section,
                new GridItem("文件接收", "💬", 0, MultiWsActivity.class, null),
                new GridItem("亮屏测试", "📱", 1, PatternActivity.class, null),
                new GridItem("浏览器", "🌐", 2, BrowserActivity.class, null)
        );
        // 第 2 行
        addGridRow(section,
                new GridItem("唤醒功能", "⚡", 3, ScreenWakeService.class, null),
                new GridItem("状态检测", "📊", 4, null, "等待开发"),
                new GridItem("定时任务", "⏰", 5, null, "等待开发")
        );
        // 第 3 行
        addGridRow(section,
                new GridItem("日志查看", "📝", 6, null, "等待开发"),
                new GridItem("数据上传", "☁️", 7, null, "等待开发"),
                new GridItem("设置权限", "⚙️", 8, SettingsPage.class, null)
        );

        root.addView(section);
    }

    private void addGridRow(LinearLayout parent, GridItem... items) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, dp(12));

        for (GridItem item : items) {
            LinearLayout itemView = new LinearLayout(this);
            itemView.setOrientation(LinearLayout.VERTICAL);
            itemView.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            itemView.setLayoutParams(itemLp);

            // 彩色圆形背景
            TextView icon = new TextView(this);
            icon.setText(item.emoji);
            icon.setTextSize(24);
            icon.setGravity(Gravity.CENTER);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(ICON_COLORS[item.colorIndex % ICON_COLORS.length]);
            icon.setBackground(circle);
            
            // 圆形大小
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(52), dp(52));
            icon.setLayoutParams(iconLp);
            itemView.addView(icon);

            // 文字
            TextView text = new TextView(this);
            text.setText(item.title);
            text.setTextColor(COLOR_TEXT);
            text.setTextSize(13);
            text.setPadding(0, dp(8), 0, 0);
            itemView.addView(text);

            // 点击事件
            itemView.setOnClickListener(v -> {
                if (item.toastMsg != null) {
                    Toast.makeText(this, item.toastMsg, Toast.LENGTH_SHORT).show();
                } else if (item.target == ScreenWakeService.class) {
                    startService(new Intent(this, ScreenWakeService.class));
                    Toast.makeText(this, "唤醒服务已启动", Toast.LENGTH_SHORT).show();
                } else if (item.target != null) {
                    try {
                        startActivity(new Intent(this, item.target));
                    } catch (Exception e) {
                        Toast.makeText(this, "页面未创建或未注册", Toast.LENGTH_SHORT).show();
                    }
                }
            });

            row.addView(itemView);
        }
        parent.addView(row);
    }

    private void addFooter(LinearLayout root) {
        TextView footer = new TextView(this);
        footer.setText("ScreenWake v1.0\n稳定运行中");
        footer.setTextColor(COLOR_SUB);
        footer.setTextSize(12);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(16), 0, dp(32));
        root.addView(footer);
    }

    // ================================================================
    // 数据与逻辑
    // ================================================================

    // 金刚区数据模型
    private static class GridItem {
        String title;
        String emoji;
        int colorIndex;
        Class<?> target;
        String toastMsg; // 如果为 null，表示可以跳转；如果不为 null，则弹 Toast

        GridItem(String title, String emoji, int colorIndex, Class<?> target, String toastMsg) {
            this.title = title;
            this.emoji = emoji;
            this.colorIndex = colorIndex;
            this.target = target;
            this.toastMsg = toastMsg;
        }
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

    private void refreshGlobalStatus() {
        if (globalStatusView == null) return;
        
        String webStatusStr = WebMonitor.isStarted() ? "运行中" : "已停止";
        boolean isTimerEnabled = ConfigManager.isEnabled(this);
        String timerStr = isTimerEnabled ? "已开启" : "已关闭";
        String trustStr = (LogManager.getTrustedServerBindId(this) != null) ? "已绑定" : "未绑定";
        
        String info = "检测状态：" + webStatusStr + "  |  定时任务：" + timerStr + "\n"
                    + "受信服务器：" + trustStr;
        
        globalStatusView.setText(info);
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
                        Toast.makeText(this, "已信任该服务器", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("拒绝", (dialog, which) -> {
                    Toast.makeText(this, "已拒绝该服务器", Toast.LENGTH_SHORT).show();
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
}package com.example.screenwake;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements CommandPoller.ServerBindNotifyCallback {

    // -------- 颜色配置 (浅色主题) --------
    private static final int COLOR_BG     = Color.rgb(245, 247, 250); // 浅灰背景
    private static final int COLOR_WHITE  = Color.rgb(255, 255, 255); // 纯白卡片
    private static final int COLOR_TEXT   = Color.rgb(51, 51, 51);    // 主标题深黑
    private static final int COLOR_SUB    = Color.rgb(153, 153, 153); // 副标题浅灰
    private static final int COLOR_BLUE   = Color.rgb(45, 134, 255);  // 顶部渐变蓝

    // 金刚区图标颜色池
    private static final int[] ICON_COLORS = {
            Color.rgb(116, 178, 255), // 浅蓝
            Color.rgb(86, 206, 172),  // 浅绿
            Color.rgb(255, 166, 77),  // 橙色
            Color.rgb(255, 128, 153), // 粉红
            Color.rgb(152, 144, 255), // 紫色
            Color.rgb(88, 193, 255),  // 天蓝
            Color.rgb(255, 192, 65),  // 黄色
            Color.rgb(93, 215, 235),  // 青色
            Color.rgb(180, 142, 255)  // 浅紫
    };

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
        
        getWindow().setStatusBarColor(COLOR_BLUE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // 1. 顶部蓝色 Header
        addHeader(root);

        // 2. 状态卡片 (稍微往上移一点，形成叠压效果)
        LinearLayout statusWrapper = new LinearLayout(this);
        statusWrapper.setPadding(dp(16), 0, dp(16), dp(16));
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wrapLp.topMargin = -dp(20); // 向上偏移，压住蓝色背景
        statusWrapper.setLayoutParams(wrapLp);
        addStatusCard(statusWrapper);
        root.addView(statusWrapper);

        // 3. 金刚区 (九宫格)
        addGridSection(root);

        // 4. 底部版本信息
        addFooter(root);

        scroll.addView(root);
        setContentView(scroll);

        ui.post(statusTicker);
    }

    // ================================================================
    // 界面构建
    // ================================================================

    private void addHeader(LinearLayout root) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        
        // 渐变蓝背景
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(50, 140, 255), Color.rgb(30, 90, 255)});
        bg.setCornerRadii(new float[]{0, 0, 0, 0, dp(24), dp(24), dp(24), dp(24)}); // 底部圆角
        header.setBackground(bg);
        
        header.setPadding(dp(24), dp(48), dp(24), dp(48));

        TextView title = new TextView(this);
        title.setText("ScreenWake");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title);

        TextView sub = new TextView(this);
        sub.setText("设备管理与自动化中枢");
        sub.setTextColor(Color.parseColor("#CCFFFFFF"));
        sub.setTextSize(14);
        sub.setPadding(0, dp(8), 0, 0);
        header.addView(sub);

        root.addView(header);
    }

    private void addStatusCard(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedBg(COLOR_WHITE, dp(12)));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        
        // 简单的阴影模拟 (elevation)
        card.setElevation(dp(4));

        TextView tv = new TextView(this);
        tv.setText("📊 系统运行状态");
        tv.setTextColor(COLOR_TEXT);
        tv.setTextSize(15);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(tv);

        globalStatusView = new TextView(this);
        globalStatusView.setTextColor(COLOR_SUB);
        globalStatusView.setTextSize(13);
        globalStatusView.setPadding(0, dp(8), 0, 0);
        globalStatusView.setLineSpacing(0, 1.3f);
        card.addView(globalStatusView);

        root.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void addGridSection(LinearLayout root) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setBackground(roundedBg(COLOR_WHITE, dp(12)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(16), 0, dp(16), dp(16));
        section.setLayoutParams(lp);
        section.setPadding(dp(8), dp(16), dp(8), dp(16));

        // 添加 9 个金刚区图标 (3x3)
        // 第 1 行
        addGridRow(section,
                new GridItem("文件接收", "💬", 0, MultiWsActivity.class, null),
                new GridItem("亮屏测试", "📱", 1, PatternActivity.class, null),
                new GridItem("浏览器", "🌐", 2, BrowserActivity.class, null)
        );
        // 第 2 行
        addGridRow(section,
                new GridItem("唤醒功能", "⚡", 3, ScreenWakeService.class, null),
                new GridItem("状态检测", "📊", 4, null, "等待开发"),
                new GridItem("定时任务", "⏰", 5, null, "等待开发")
        );
        // 第 3 行
        addGridRow(section,
                new GridItem("日志查看", "📝", 6, null, "等待开发"),
                new GridItem("数据上传", "☁️", 7, null, "等待开发"),
                new GridItem("设置权限", "⚙️", 8, SettingsPage.class, null)
        );

        root.addView(section);
    }

    private void addGridRow(LinearLayout parent, GridItem... items) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, dp(12));

        for (GridItem item : items) {
            LinearLayout itemView = new LinearLayout(this);
            itemView.setOrientation(LinearLayout.VERTICAL);
            itemView.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            itemView.setLayoutParams(itemLp);

            // 彩色圆形背景
            TextView icon = new TextView(this);
            icon.setText(item.emoji);
            icon.setTextSize(24);
            icon.setGravity(Gravity.CENTER);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(ICON_COLORS[item.colorIndex % ICON_COLORS.length]);
            icon.setBackground(circle);
            
            // 圆形大小
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(52), dp(52));
            icon.setLayoutParams(iconLp);
            itemView.addView(icon);

            // 文字
            TextView text = new TextView(this);
            text.setText(item.title);
            text.setTextColor(COLOR_TEXT);
            text.setTextSize(13);
            text.setPadding(0, dp(8), 0, 0);
            itemView.addView(text);

            // 点击事件
            itemView.setOnClickListener(v -> {
                if (item.toastMsg != null) {
                    Toast.makeText(this, item.toastMsg, Toast.LENGTH_SHORT).show();
                } else if (item.target == ScreenWakeService.class) {
                    startService(new Intent(this, ScreenWakeService.class));
                    Toast.makeText(this, "唤醒服务已启动", Toast.LENGTH_SHORT).show();
                } else if (item.target != null) {
                    try {
                        startActivity(new Intent(this, item.target));
                    } catch (Exception e) {
                        Toast.makeText(this, "页面未创建或未注册", Toast.LENGTH_SHORT).show();
                    }
                }
            });

            row.addView(itemView);
        }
        parent.addView(row);
    }

    private void addFooter(LinearLayout root) {
        TextView footer = new TextView(this);
        footer.setText("ScreenWake v1.0\n稳定运行中");
        footer.setTextColor(COLOR_SUB);
        footer.setTextSize(12);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(16), 0, dp(32));
        root.addView(footer);
    }

    // ================================================================
    // 数据与逻辑
    // ================================================================

    // 金刚区数据模型
    private static class GridItem {
        String title;
        String emoji;
        int colorIndex;
        Class<?> target;
        String toastMsg; // 如果为 null，表示可以跳转；如果不为 null，则弹 Toast

        GridItem(String title, String emoji, int colorIndex, Class<?> target, String toastMsg) {
            this.title = title;
            this.emoji = emoji;
            this.colorIndex = colorIndex;
            this.target = target;
            this.toastMsg = toastMsg;
        }
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

    private void refreshGlobalStatus() {
        if (globalStatusView == null) return;
        
        String webStatusStr = WebMonitor.isStarted() ? "运行中" : "已停止";
        boolean isTimerEnabled = ConfigManager.isEnabled(this);
        String timerStr = isTimerEnabled ? "已开启" : "已关闭";
        String trustStr = (LogManager.getTrustedServerBindId(this) != null) ? "已绑定" : "未绑定";
        
        String info = "检测状态：" + webStatusStr + "  |  定时任务：" + timerStr + "\n"
                    + "受信服务器：" + trustStr;
        
        globalStatusView.setText(info);
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
                        Toast.makeText(this, "已信任该服务器", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("拒绝", (dialog, which) -> {
                    Toast.makeText(this, "已拒绝该服务器", Toast.LENGTH_SHORT).show();
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
}package com.example.screenwake;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements CommandPoller.ServerBindNotifyCallback {

    // -------- 颜色配置 (浅色主题) --------
    private static final int COLOR_BG     = Color.rgb(245, 247, 250); // 浅灰背景
    private static final int COLOR_WHITE  = Color.rgb(255, 255, 255); // 纯白卡片
    private static final int COLOR_TEXT   = Color.rgb(51, 51, 51);    // 主标题深黑
    private static final int COLOR_SUB    = Color.rgb(153, 153, 153); // 副标题浅灰
    private static final int COLOR_BLUE   = Color.rgb(45, 134, 255);  // 顶部渐变蓝

    // 金刚区图标颜色池
    private static final int[] ICON_COLORS = {
            Color.rgb(116, 178, 255), // 浅蓝
            Color.rgb(86, 206, 172),  // 浅绿
            Color.rgb(255, 166, 77),  // 橙色
            Color.rgb(255, 128, 153), // 粉红
            Color.rgb(152, 144, 255), // 紫色
            Color.rgb(88, 193, 255),  // 天蓝
            Color.rgb(255, 192, 65),  // 黄色
            Color.rgb(93, 215, 235),  // 青色
            Color.rgb(180, 142, 255)  // 浅紫
    };

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
        
        getWindow().setStatusBarColor(COLOR_BLUE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // 1. 顶部蓝色 Header
        addHeader(root);

        // 2. 状态卡片 (稍微往上移一点，形成叠压效果)
        LinearLayout statusWrapper = new LinearLayout(this);
        statusWrapper.setPadding(dp(16), 0, dp(16), dp(16));
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wrapLp.topMargin = -dp(20); // 向上偏移，压住蓝色背景
        statusWrapper.setLayoutParams(wrapLp);
        addStatusCard(statusWrapper);
        root.addView(statusWrapper);

        // 3. 金刚区 (九宫格)
        addGridSection(root);

        // 4. 底部版本信息
        addFooter(root);

        scroll.addView(root);
        setContentView(scroll);

        ui.post(statusTicker);
    }

    // ================================================================
    // 界面构建
    // ================================================================

    private void addHeader(LinearLayout root) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        
        // 渐变蓝背景
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(50, 140, 255), Color.rgb(30, 90, 255)});
        bg.setCornerRadii(new float[]{0, 0, 0, 0, dp(24), dp(24), dp(24), dp(24)}); // 底部圆角
        header.setBackground(bg);
        
        header.setPadding(dp(24), dp(48), dp(24), dp(48));

        TextView title = new TextView(this);
        title.setText("ScreenWake");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title);

        TextView sub = new TextView(this);
        sub.setText("设备管理与自动化中枢");
        sub.setTextColor(Color.parseColor("#CCFFFFFF"));
        sub.setTextSize(14);
        sub.setPadding(0, dp(8), 0, 0);
        header.addView(sub);

        root.addView(header);
    }

    private void addStatusCard(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedBg(COLOR_WHITE, dp(12)));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        
        // 简单的阴影模拟 (elevation)
        card.setElevation(dp(4));

        TextView tv = new TextView(this);
        tv.setText("📊 系统运行状态");
        tv.setTextColor(COLOR_TEXT);
        tv.setTextSize(15);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(tv);

        globalStatusView = new TextView(this);
        globalStatusView.setTextColor(COLOR_SUB);
        globalStatusView.setTextSize(13);
        globalStatusView.setPadding(0, dp(8), 0, 0);
        globalStatusView.setLineSpacing(0, 1.3f);
        card.addView(globalStatusView);

        root.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void addGridSection(LinearLayout root) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setBackground(roundedBg(COLOR_WHITE, dp(12)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(16), 0, dp(16), dp(16));
        section.setLayoutParams(lp);
        section.setPadding(dp(8), dp(16), dp(8), dp(16));

        // 添加 9 个金刚区图标 (3x3)
        // 第 1 行
        addGridRow(section,
                new GridItem("文件接收", "💬", 0, MultiWsActivity.class, null),
                new GridItem("亮屏测试", "📱", 1, PatternActivity.class, null),
                new GridItem("浏览器", "🌐", 2, BrowserActivity.class, null)
        );
        // 第 2 行
        addGridRow(section,
                new GridItem("唤醒功能", "⚡", 3, ScreenWakeService.class, null),
                new GridItem("状态检测", "📊", 4, null, "等待开发"),
                new GridItem("定时任务", "⏰", 5, null, "等待开发")
        );
        // 第 3 行
        addGridRow(section,
                new GridItem("日志查看", "📝", 6, null, "等待开发"),
                new GridItem("数据上传", "☁️", 7, null, "等待开发"),
                new GridItem("设置权限", "⚙️", 8, SettingsPage.class, null)
        );

        root.addView(section);
    }

    private void addGridRow(LinearLayout parent, GridItem... items) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, dp(12));

        for (GridItem item : items) {
            LinearLayout itemView = new LinearLayout(this);
            itemView.setOrientation(LinearLayout.VERTICAL);
            itemView.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            itemView.setLayoutParams(itemLp);

            // 彩色圆形背景
            TextView icon = new TextView(this);
            icon.setText(item.emoji);
            icon.setTextSize(24);
            icon.setGravity(Gravity.CENTER);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(ICON_COLORS[item.colorIndex % ICON_COLORS.length]);
            icon.setBackground(circle);
            
            // 圆形大小
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(52), dp(52));
            icon.setLayoutParams(iconLp);
            itemView.addView(icon);

            // 文字
            TextView text = new TextView(this);
            text.setText(item.title);
            text.setTextColor(COLOR_TEXT);
            text.setTextSize(13);
            text.setPadding(0, dp(8), 0, 0);
            itemView.addView(text);

            // 点击事件
            itemView.setOnClickListener(v -> {
                if (item.toastMsg != null) {
                    Toast.makeText(this, item.toastMsg, Toast.LENGTH_SHORT).show();
                } else if (item.target == ScreenWakeService.class) {
                    startService(new Intent(this, ScreenWakeService.class));
                    Toast.makeText(this, "唤醒服务已启动", Toast.LENGTH_SHORT).show();
                } else if (item.target != null) {
                    try {
                        startActivity(new Intent(this, item.target));
                    } catch (Exception e) {
                        Toast.makeText(this, "页面未创建或未注册", Toast.LENGTH_SHORT).show();
                    }
                }
            });

            row.addView(itemView);
        }
        parent.addView(row);
    }

    private void addFooter(LinearLayout root) {
        TextView footer = new TextView(this);
        footer.setText("ScreenWake v1.0\n稳定运行中");
        footer.setTextColor(COLOR_SUB);
        footer.setTextSize(12);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(16), 0, dp(32));
        root.addView(footer);
    }

    // ================================================================
    // 数据与逻辑
    // ================================================================

    // 金刚区数据模型
    private static class GridItem {
        String title;
        String emoji;
        int colorIndex;
        Class<?> target;
        String toastMsg; // 如果为 null，表示可以跳转；如果不为 null，则弹 Toast

        GridItem(String title, String emoji, int colorIndex, Class<?> target, String toastMsg) {
            this.title = title;
            this.emoji = emoji;
            this.colorIndex = colorIndex;
            this.target = target;
            this.toastMsg = toastMsg;
        }
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

    private void refreshGlobalStatus() {
        if (globalStatusView == null) return;
        
        String webStatusStr = WebMonitor.isStarted() ? "运行中" : "已停止";
        boolean isTimerEnabled = ConfigManager.isEnabled(this);
        String timerStr = isTimerEnabled ? "已开启" : "已关闭";
        String trustStr = (LogManager.getTrustedServerBindId(this) != null) ? "已绑定" : "未绑定";
        
        String info = "检测状态：" + webStatusStr + "  |  定时任务：" + timerStr + "\n"
                    + "受信服务器：" + trustStr;
        
        globalStatusView.setText(info);
    }

    @Override
    public void needShowBindDialog(String bindId, boolean isChanged) {
        String title = isChanged ? "服务器标识码已变更" : "收到新服务器绑定请求";
        String msg = isChanged 
                ? "服务器重启，标识码更新。是否信任该新服务器？\nID: " + bindId
                : "是否信任此服务器？\nID: " + bindId;package com.example.screenwake;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements CommandPoller.ServerBindNotifyCallback {

    // -------- 颜色配置 (浅色主题) --------
    private static final int COLOR_BG     = Color.rgb(245, 247, 250); // 浅灰背景
    private static final int COLOR_WHITE  = Color.rgb(255, 255, 255); // 纯白卡片
    private static final int COLOR_TEXT   = Color.rgb(51, 51, 51);    // 主标题深黑
    private static final int COLOR_SUB    = Color.rgb(153, 153, 153); // 副标题浅灰
    private static final int COLOR_BLUE   = Color.rgb(45, 134, 255);  // 顶部渐变蓝

    // 金刚区图标颜色池
    private static final int[] ICON_COLORS = {
            Color.rgb(116, 178, 255), // 浅蓝
            Color.rgb(86, 206, 172),  // 浅绿
            Color.rgb(255, 166, 77),  // 橙色
            Color.rgb(255, 128, 153), // 粉红
            Color.rgb(152, 144, 255), // 紫色
            Color.rgb(88, 193, 255),  // 天蓝
            Color.rgb(255, 192, 65),  // 黄色
            Color.rgb(93, 215, 235),  // 青色
            Color.rgb(180, 142, 255)  // 浅紫
    };

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
        
        getWindow().setStatusBarColor(COLOR_BLUE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // 1. 顶部蓝色 Header
        addHeader(root);

        // 2. 状态卡片 (稍微往上移一点，形成叠压效果)
        LinearLayout statusWrapper = new LinearLayout(this);
        statusWrapper.setPadding(dp(16), 0, dp(16), dp(16));
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wrapLp.topMargin = -dp(20); // 向上偏移，压住蓝色背景
        statusWrapper.setLayoutParams(wrapLp);
        addStatusCard(statusWrapper);
        root.addView(statusWrapper);

        // 3. 金刚区 (九宫格)
        addGridSection(root);

        // 4. 底部版本信息
        addFooter(root);

        scroll.addView(root);
        setContentView(scroll);

        ui.post(statusTicker);
    }

    // ================================================================
    // 界面构建
    // ================================================================

    private void addHeader(LinearLayout root) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        
        // 渐变蓝背景
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(50, 140, 255), Color.rgb(30, 90, 255)});
        bg.setCornerRadii(new float[]{0, 0, 0, 0, dp(24), dp(24), dp(24), dp(24)}); // 底部圆角
        header.setBackground(bg);
        
        header.setPadding(dp(24), dp(48), dp(24), dp(48));

        TextView title = new TextView(this);
        title.setText("ScreenWake");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title);

        TextView sub = new TextView(this);
        sub.setText("设备管理与自动化中枢");
        sub.setTextColor(Color.parseColor("#CCFFFFFF"));
        sub.setTextSize(14);
        sub.setPadding(0, dp(8), 0, 0);
        header.addView(sub);

        root.addView(header);
    }

    private void addStatusCard(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedBg(COLOR_WHITE, dp(12)));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        
        // 简单的阴影模拟 (elevation)
        card.setElevation(dp(4));

        TextView tv = new TextView(this);
        tv.setText("📊 系统运行状态");
        tv.setTextColor(COLOR_TEXT);
        tv.setTextSize(15);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(tv);

        globalStatusView = new TextView(this);
        globalStatusView.setTextColor(COLOR_SUB);
        globalStatusView.setTextSize(13);
        globalStatusView.setPadding(0, dp(8), 0, 0);
        globalStatusView.setLineSpacing(0, 1.3f);
        card.addView(globalStatusView);

        root.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void addGridSection(LinearLayout root) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setBackground(roundedBg(COLOR_WHITE, dp(12)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(16), 0, dp(16), dp(16));
        section.setLayoutParams(lp);
        section.setPadding(dp(8), dp(16), dp(8), dp(16));

        // 添加 9 个金刚区图标 (3x3)
        // 第 1 行
        addGridRow(section,
                new GridItem("文件接收", "💬", 0, MultiWsActivity.class, null),
                new GridItem("亮屏测试", "📱", 1, PatternActivity.class, null),
                new GridItem("浏览器", "🌐", 2, BrowserActivity.class, null)
        );
        // 第 2 行
        addGridRow(section,
                new GridItem("唤醒功能", "⚡", 3, ScreenWakeService.class, null),
                new GridItem("状态检测", "📊", 4, null, "等待开发"),
                new GridItem("定时任务", "⏰", 5, null, "等待开发")
        );
        // 第 3 行
        addGridRow(section,
                new GridItem("日志查看", "📝", 6, null, "等待开发"),
                new GridItem("数据上传", "☁️", 7, null, "等待开发"),
                new GridItem("设置权限", "⚙️", 8, SettingsPage.class, null)
        );

        root.addView(section);
    }

    private void addGridRow(LinearLayout parent, GridItem... items) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, dp(12));

        for (GridItem item : items) {
            LinearLayout itemView = new LinearLayout(this);
            itemView.setOrientation(LinearLayout.VERTICAL);
            itemView.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            itemView.setLayoutParams(itemLp);

            // 彩色圆形背景
            TextView icon = new TextView(this);
            icon.setText(item.emoji);
            icon.setTextSize(24);
            icon.setGravity(Gravity.CENTER);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(ICON_COLORS[item.colorIndex % ICON_COLORS.length]);
            icon.setBackground(circle);
            
            // 圆形大小
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(52), dp(52));
            icon.setLayoutParams(iconLp);
            itemView.addView(icon);

            // 文字
            TextView text = new TextView(this);
            text.setText(item.title);
            text.setTextColor(COLOR_TEXT);
            text.setTextSize(13);
            text.setPadding(0, dp(8), 0, 0);
            itemView.addView(text);

            // 点击事件
            itemView.setOnClickListener(v -> {
                if (item.toastMsg != null) {
                    Toast.makeText(this, item.toastMsg, Toast.LENGTH_SHORT).show();
                } else if (item.target == ScreenWakeService.class) {
                    startService(new Intent(this, ScreenWakeService.class));
                    Toast.makeText(this, "唤醒服务已启动", Toast.LENGTH_SHORT).show();
                } else if (item.target != null) {
                    try {
                        startActivity(new Intent(this, item.target));
                    } catch (Exception e) {
                        Toast.makeText(this, "页面未创建或未注册", Toast.LENGTH_SHORT).show();
                    }
                }
            });

            row.addView(itemView);
        }
        parent.addView(row);
    }

    private void addFooter(LinearLayout root) {
        TextView footer = new TextView(this);
        footer.setText("ScreenWake v1.0\n稳定运行中");
        footer.setTextColor(COLOR_SUB);
        footer.setTextSize(12);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(16), 0, dp(32));
        root.addView(footer);
    }

    // ================================================================
    // 数据与逻辑
    // ================================================================

    // 金刚区数据模型
    private static class GridItem {
        String title;
        String emoji;
        int colorIndex;
        Class<?> target;
        String toastMsg; // 如果为 null，表示可以跳转；如果不为 null，则弹 Toast

        GridItem(String title, String emoji, int colorIndex, Class<?> target, String toastMsg) {
            this.title = title;
            this.emoji = emoji;
            this.colorIndex = colorIndex;
            this.target = target;
            this.toastMsg = toastMsg;
        }
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

    private void refreshGlobalStatus() {
        if (globalStatusView == null) return;
        
        String webStatusStr = WebMonitor.isStarted() ? "运行中" : "已停止";
        boolean isTimerEnabled = ConfigManager.isEnabled(this);
        String timerStr = isTimerEnabled ? "已开启" : "已关闭";
        String trustStr = (LogManager.getTrustedServerBindId(this) != null) ? "已绑定" : "未绑定";
        
        String info = "检测状态：" + webStatusStr + "  |  定时任务：" + timerStr + "\n"
                    + "受信服务器：" + trustStr;
        
        globalStatusView.setText(info);
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
                        Toast.makeText(this, "已信任该服务器", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("拒绝", (dialog, which) -> {
                    Toast.makeText(this, "已拒绝该服务器", Toast.LENGTH_SHORT).show();
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
}package com.example.screenwake;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements CommandPoller.ServerBindNotifyCallback {

    // -------- 颜色配置 (浅色主题) --------
    private static final int COLOR_BG     = Color.rgb(245, 247, 250); // 浅灰背景
    private static final int COLOR_WHITE  = Color.rgb(255, 255, 255); // 纯白卡片
    private static final int COLOR_TEXT   = Color.rgb(51, 51, 51);    // 主标题深黑
    private static final int COLOR_SUB    = Color.rgb(153, 153, 153); // 副标题浅灰
    private static final int COLOR_BLUE   = Color.rgb(45, 134, 255);  // 顶部渐变蓝

    // 金刚区图标颜色池
    private static final int[] ICON_COLORS = {
            Color.rgb(116, 178, 255), // 浅蓝
            Color.rgb(86, 206, 172),  // 浅绿
            Color.rgb(255, 166, 77),  // 橙色
            Color.rgb(255, 128, 153), // 粉红
            Color.rgb(152, 144, 255), // 紫色
            Color.rgb(88, 193, 255),  // 天蓝
            Color.rgb(255, 192, 65),  // 黄色
            Color.rgb(93, 215, 235),  // 青色
            Color.rgb(180, 142, 255)  // 浅紫
    };

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
        
        getWindow().setStatusBarColor(COLOR_BLUE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // 1. 顶部蓝色 Header
        addHeader(root);

        // 2. 状态卡片 (稍微往上移一点，形成叠压效果)
        LinearLayout statusWrapper = new LinearLayout(this);
        statusWrapper.setPadding(dp(16), 0, dp(16), dp(16));
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wrapLp.topMargin = -dp(20); // 向上偏移，压住蓝色背景
        statusWrapper.setLayoutParams(wrapLp);
        addStatusCard(statusWrapper);
        root.addView(statusWrapper);

        // 3. 金刚区 (九宫格)
        addGridSection(root);

        // 4. 底部版本信息
        addFooter(root);

        scroll.addView(root);
        setContentView(scroll);

        ui.post(statusTicker);
    }

    // ================================================================
    // 界面构建
    // ================================================================

    private void addHeader(LinearLayout root) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        
        // 渐变蓝背景
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(50, 140, 255), Color.rgb(30, 90, 255)});
        bg.setCornerRadii(new float[]{0, 0, 0, 0, dp(24), dp(24), dp(24), dp(24)}); // 底部圆角
        header.setBackground(bg);
        
        header.setPadding(dp(24), dp(48), dp(24), dp(48));

        TextView title = new TextView(this);
        title.setText("ScreenWake");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title);

        TextView sub = new TextView(this);
        sub.setText("设备管理与自动化中枢");
        sub.setTextColor(Color.parseColor("#CCFFFFFF"));
        sub.setTextSize(14);
        sub.setPadding(0, dp(8), 0, 0);
        header.addView(sub);

        root.addView(header);
    }

    private void addStatusCard(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedBg(COLOR_WHITE, dp(12)));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        
        // 简单的阴影模拟 (elevation)
        card.setElevation(dp(4));

        TextView tv = new TextView(this);
        tv.setText("📊 系统运行状态");
        tv.setTextColor(COLOR_TEXT);
        tv.setTextSize(15);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(tv);

        globalStatusView = new TextView(this);
        globalStatusView.setTextColor(COLOR_SUB);
        globalStatusView.setTextSize(13);
        globalStatusView.setPadding(0, dp(8), 0, 0);
        globalStatusView.setLineSpacing(0, 1.3f);
        card.addView(globalStatusView);

        root.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void addGridSection(LinearLayout root) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setBackground(roundedBg(COLOR_WHITE, dp(12)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(16), 0, dp(16), dp(16));
        section.setLayoutParams(lp);
        section.setPadding(dp(8), dp(16), dp(8), dp(16));

        // 添加 9 个金刚区图标 (3x3)
        // 第 1 行
        addGridRow(section,
                new GridItem("文件接收", "💬", 0, MultiWsActivity.class, null),
                new GridItem("亮屏测试", "📱", 1, PatternActivity.class, null),
                new GridItem("浏览器", "🌐", 2, BrowserActivity.class, null)
        );
        // 第 2 行
        addGridRow(section,
                new GridItem("唤醒功能", "⚡", 3, ScreenWakeService.class, null),
                new GridItem("状态检测", "📊", 4, null, "等待开发"),
                new GridItem("定时任务", "⏰", 5, null, "等待开发")
        );
        // 第 3 行
        addGridRow(section,
                new GridItem("日志查看", "📝", 6, null, "等待开发"),
                new GridItem("数据上传", "☁️", 7, null, "等待开发"),
                new GridItem("设置权限", "⚙️", 8, SettingsPage.class, null)
        );

        root.addView(section);
    }

    private void addGridRow(LinearLayout parent, GridItem... items) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, dp(12));

        for (GridItem item : items) {
            LinearLayout itemView = new LinearLayout(this);
            itemView.setOrientation(LinearLayout.VERTICAL);
            itemView.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            itemView.setLayoutParams(itemLp);

            // 彩色圆形背景
            TextView icon = new TextView(this);
            icon.setText(item.emoji);
            icon.setTextSize(24);
            icon.setGravity(Gravity.CENTER);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(ICON_COLORS[item.colorIndex % ICON_COLORS.length]);
            icon.setBackground(circle);
            
            // 圆形大小
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(52), dp(52));
            icon.setLayoutParams(iconLp);
            itemView.addView(icon);

            // 文字
            TextView text = new TextView(this);
            text.setText(item.title);
            text.setTextColor(COLOR_TEXT);
            text.setTextSize(13);
            text.setPadding(0, dp(8), 0, 0);
            itemView.addView(text);

            // 点击事件
            itemView.setOnClickListener(v -> {
                if (item.toastMsg != null) {
                    Toast.makeText(this, item.toastMsg, Toast.LENGTH_SHORT).show();
                } else if (item.target == ScreenWakeService.class) {
                    startService(new Intent(this, ScreenWakeService.class));
                    Toast.makeText(this, "唤醒服务已启动", Toast.LENGTH_SHORT).show();
                } else if (item.target != null) {
                    try {
                        startActivity(new Intent(this, item.target));
                    } catch (Exception e) {
                        Toast.makeText(this, "页面未创建或未注册", Toast.LENGTH_SHORT).show();
                    }
                }
            });

            row.addView(itemView);
        }
        parent.addView(row);
    }

    private void addFooter(LinearLayout root) {
        TextView footer = new TextView(this);
        footer.setText("ScreenWake v1.0\n稳定运行中");
        footer.setTextColor(COLOR_SUB);
        footer.setTextSize(12);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(16), 0, dp(32));
        root.addView(footer);
    }

    // ================================================================
    // 数据与逻辑
    // ================================================================

    // 金刚区数据模型
    private static class GridItem {
        String title;
        String emoji;
        int colorIndex;
        Class<?> target;
        String toastMsg; // 如果为 null，表示可以跳转；如果不为 null，则弹 Toast

        GridItem(String title, String emoji, int colorIndex, Class<?> target, String toastMsg) {
            this.title = title;
            this.emoji = emoji;
            this.colorIndex = colorIndex;
            this.target = target;
            this.toastMsg = toastMsg;
        }
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

    private void refreshGlobalStatus() {
        if (globalStatusView == null) return;
        
        String webStatusStr = WebMonitor.isStarted() ? "运行中" : "已停止";
        boolean isTimerEnabled = ConfigManager.isEnabled(this);
        String timerStr = isTimerEnabled ? "已开启" : "已关闭";
        String trustStr = (LogManager.getTrustedServerBindId(this) != null) ? "已绑定" : "未绑定";
        
        String info = "检测状态：" + webStatusStr + "  |  定时任务：" + timerStr + "\n"
                    + "受信服务器：" + trustStr;
        
        globalStatusView.setText(info);
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
                        Toast.makeText(this, "已信任该服务器", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("拒绝", (dialog, which) -> {
                    Toast.makeText(this, "已拒绝该服务器", Toast.LENGTH_SHORT).show();
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
                             }

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("接收", (dialog, which) -> {
                    boolean ok = LogManager.setTrustedServerBindId(this, bindId);
                    if (ok) {
                        CommandPoller.start(this);
                        Toast.makeText(this, "已信任该服务器", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("拒绝", (dialog, which) -> {
                    Toast.makeText(this, "已拒绝该服务器", Toast.LENGTH_SHORT).show();
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
}
