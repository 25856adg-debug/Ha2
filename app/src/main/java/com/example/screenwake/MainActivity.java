package com.example.screenwake;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private int dp(float n) {
        return (int)(n *
            getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.rgb(18,23,33));
        getWindow().setNavigationBarColor(Color.BLACK);

        buildLauncher();
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

        addButton(root,"Wi-Fi 设置",
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

    private void showError(Exception e) {
        Toast.makeText(
            this,
            "操作失败：" + e.getMessage(),
            Toast.LENGTH_LONG
        ).show();
    }
}


