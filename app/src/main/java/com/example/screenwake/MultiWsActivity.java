package com.example.screenwake;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * WebSocket 多路接收 —— 子页面
 *
 * 主界面只留一个入口按钮，点进来才看到五路配置与状态。
 * 页面内不显示任何密钥内容，只显示"已接收到 / 连接中 / 断开"。
 */
public class MultiWsActivity extends Activity {

    private static final int COLOR_BG = Color.rgb(20, 27, 36);
    private static final int COLOR_CARD = Color.rgb(48, 59, 79);
    private static final int COLOR_TEXT = Color.rgb(220, 227, 234);
    private static final int COLOR_SUB = Color.rgb(150, 165, 180);
    private static final int COLOR_ACCENT = Color.rgb(100, 180, 255);
    private static final int COLOR_OK = Color.rgb(120, 210, 140);
    private static final int COLOR_WARN = Color.rgb(240, 190, 90);

    private final EditText[] urlInputs = new EditText[MultiWsReceiver.SLOTS];
    private final TextView[] statusViews = new TextView[MultiWsReceiver.SLOTS];

    private TextView summaryView;
    private TextView modeView;
    private TextView pathView;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(30));
        root.setBackgroundColor(COLOR_BG);
        sv.addView(root);
        setContentView(sv);

        buildHeader(root);
        buildMode(root);
        buildUrls(root);
        buildActions(root);
        buildStatus(root);
        buildFooter(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        fillUrlsFromStore();
        ui.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
    }

    // ==================== 界面搭建 ====================

    private void buildHeader(LinearLayout root) {
        TextView title = new TextView(this);
        title.setText("WebSocket 五路接收");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        root.addView(title, lp(0, 10));

        TextView tip = new TextView(this);
        tip.setText("五路独立连接，全程只收密文；解出来的明文按通道分别存文件。");
        tip.setTextColor(COLOR_SUB);
        tip.setTextSize(13);
        tip.setGravity(Gravity.CENTER);
        root.addView(tip, lp(0, 16));
    }

    private void buildMode(LinearLayout root) {
        addSection(root, "连接模式");

        modeView = new TextView(this);
        modeView.setTextColor(COLOR_WARN);
        modeView.setTextSize(14);
        modeView.setGravity(Gravity.CENTER);
        root.addView(modeView, lp(0, 8));

        addButton(root, "多连接（五个地址各一条）", () -> {
            MultiWsReceiver.setMode(this, MultiWsReceiver.MODE_MULTI);
            refreshStatus();
            toast("已切换到多连接模式");
        });
        addButton(root, "单连接（一个地址 + 通道前缀）", () -> {
            MultiWsReceiver.setMode(this, MultiWsReceiver.MODE_SINGLE);
            refreshStatus();
            toast("已切换到单连接模式（只连第1个地址）");
        });
    }

    private void buildUrls(LinearLayout root) {
        addSection(root, "五路地址");

        for (int i = 0; i < MultiWsReceiver.SLOTS; i++) {
            TextView label = new TextView(this);
            label.setText("第" + (i + 1) + "路");
            label.setTextColor(COLOR_SUB);
            label.setTextSize(13);
            root.addView(label, lp(0, 6));

            EditText et = new EditText(this);
            et.setHint(i == 0 ? "ws://170.106.188.189:3359/ws1" : "留空表示不启用");
            et.setHintTextColor(Color.rgb(110, 120, 135));
            et.setTextColor(COLOR_TEXT);
            et.setTextSize(14);
            et.setSingleLine(true);
            et.setBackgroundColor(COLOR_CARD);
            et.setPadding(dp(12), dp(12), dp(12), dp(12));
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            urlInputs[i] = et;
            root.addView(et, lp(0, 6));
        }
    }

    private void buildActions(LinearLayout root) {
        addButton(root, "保存地址", () -> {
            String[] urls = collectUrls();
            MultiWsReceiver.setUrls(this, urls);
            toast("地址已保存");
        });
        addButton(root, "保存并启动", () -> {
            String[] urls = collectUrls();
            if (urls[0].isEmpty() && MultiWsReceiver.MODE_MULTI.equals(MultiWsReceiver.getMode(this))) {
                toast("第1路地址不能为空");
                return;
            }
            MultiWsReceiver.setMode(this, MultiWsReceiver.getMode(this));
            MultiWsReceiver.setUrls(this, urls);
            MultiWsReceiver.start(this);
            toast("已启动");
            refreshStatus();
        });
        addButton(root, "停止", () -> {
            MultiWsReceiver.stop();
            toast("已停止");
            refreshStatus();
        });
    }

    private void buildStatus(LinearLayout root) {
        addSection(root, "实时状态");

        summaryView = new TextView(this);
        summaryView.setTextColor(COLOR_TEXT);
        summaryView.setTextSize(14);
        summaryView.setGravity(Gravity.CENTER);
        root.addView(summaryView, lp(0, 8));

        for (int i = 0; i < MultiWsReceiver.SLOTS; i++) {
            TextView tv = new TextView(this);
            tv.setText("第" + (i + 1) + "路：未启动");
            tv.setTextColor(COLOR_SUB);
            tv.setTextSize(14);
            tv.setBackgroundColor(COLOR_CARD);
            tv.setPadding(dp(12), dp(12), dp(12), dp(12));
            statusViews[i] = tv;
            root.addView(tv, lp(0, 6));
        }
    }

    private void buildFooter(LinearLayout root) {
        addSection(root, "数据目录");

        pathView = new TextView(this);
        pathView.setTextColor(COLOR_SUB);
        pathView.setTextSize(12);
        pathView.setPadding(dp(12), dp(12), dp(12), dp(12));
        pathView.setBackgroundColor(COLOR_CARD);
        root.addView(pathView, lp(0, 8));

        addButton(root, "复制数据目录", () -> {
            String p = MultiWsReceiver.getDataDir();
            if (p.isEmpty()) {
                toast("请先启动一次，目录才会生成");
                return;
            }
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("wsdata", p));
                toast("已复制：" + p);
            }
        });
        addButton(root, "清空待解文件", () -> new AlertDialog.Builder(this)
                .setTitle("清空待解文件")
                .setMessage("确定清空 pending.txt？清空后这些密文将无法再解。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> {
                    int n = MultiWsReceiver.clearPending();
                    toast("已清空 " + n + " 条");
                    refreshStatus();
                }).show());
    }

    // ==================== 刷新 ====================

    private void refreshStatus() {
        int pending = MultiWsReceiver.getPendingCount();
        int done = MultiWsReceiver.getDecryptedCount();

        if (summaryView != null) {
            summaryView.setText(MultiWsReceiver.isRunning()
                    ? ("运行中　待解密文 " + pending + " 条　已解文件 " + done + " 个")
                    : ("未运行　待解密文 " + pending + " 条　已解文件 " + done + " 个"));
            summaryView.setTextColor(MultiWsReceiver.isRunning() ? COLOR_OK : COLOR_SUB);
        }

        if (modeView != null) {
            boolean single = MultiWsReceiver.MODE_SINGLE.equals(
                    MultiWsReceiver.getMode(this));
            modeView.setText(single ? "当前模式：单连接（只连第1路）" : "当前模式：多连接（五路各一条）");
        }

        for (int i = 0; i < MultiWsReceiver.SLOTS; i++) {
            TextView tv = statusViews[i];
            if (tv == null) continue;
            String s = MultiWsReceiver.getStatus(i);
            if (s == null || s.isEmpty()) s = "未启动";
            tv.setText("第" + (i + 1) + "路：" + s);

            int color = COLOR_SUB;
            if (s.contains("已接收到")) color = COLOR_OK;
            else if (s.contains("断开") || s.contains("重连")) color = COLOR_WARN;
            else if (s.contains("已连接")) color = COLOR_ACCENT;
            tv.setTextColor(color);
        }

        if (pathView != null) {
            pathView.setText(MultiWsReceiver.getDataDir().isEmpty()
                    ? "尚未启动，启动后显示路径"
                    : MultiWsReceiver.getDataDir());
        }
    }

    // ==================== 工具 ====================

    private String[] collectUrls() {
        String[] out = new String[MultiWsReceiver.SLOTS];
        for (int i = 0; i < MultiWsReceiver.SLOTS; i++) {
            out[i] = urlInputs[i] == null ? "" : urlInputs[i].getText().toString().trim();
        }
        return out;
    }

    private void fillUrlsFromStore() {
        String[] urls = MultiWsReceiver.getUrls(this);
        for (int i = 0; i < MultiWsReceiver.SLOTS; i++) {
            if (urlInputs[i] != null) {
                urlInputs[i].setText(urls[i] == null ? "" : urls[i]);
            }
        }
        refreshStatus();
    }

    private void addSection(LinearLayout root, String label) {
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextColor(COLOR_ACCENT);
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
        button.setBackgroundColor(COLOR_CARD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(12);
        root.addView(button, lp);
        button.setOnClickListener(v -> action.run());
    }

    private LinearLayout.LayoutParams lp(int h, int bottomMarginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, h == 0 ? -2 : h);
        p.bottomMargin = dp(bottomMarginDp);
        return p;
    }

    private int dp(float n) {
        return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
