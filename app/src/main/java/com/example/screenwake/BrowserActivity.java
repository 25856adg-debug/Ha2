package com.example.screenwake;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.URLUtil;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

public class BrowserActivity extends Activity {

    private FrameLayout root;
    private LinearLayout content;
    private LinearLayout bottomBar;
    private EditText address;
    private WebView web;

    private ValueCallback<Uri[]> fileCallback;

    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    private static final int FILE_REQUEST = 1001;

    private int dp(float n) {
        return (int)(n * getResources()
                .getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        // 键盘弹出时自动调整页面高度
        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        );

        buildBrowser();
        loadHome();
    }

    private void buildBrowser() {

        root = new FrameLayout(this);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Color.WHITE);

        // ================= 网页区域 =================

        web = new WebView(this);

        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setDatabaseEnabled(true);

        // 网页缩放
        web.getSettings().setSupportZoom(true);
        web.getSettings().setBuiltInZoomControls(true);
        web.getSettings().setDisplayZoomControls(false);

        web.getSettings().setAllowFileAccess(true);

        // 支持网页文字选择、复制
        web.setLongClickable(true);
        web.setHapticFeedbackEnabled(true);
        web.setTextSelectionEnabled(true);

        web.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (address != null && url != null) {
                    address.setText(url);
                    address.setSelection(
                            address.getText().length()
                    );
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {

            // 网页文件上传
            @Override
            public boolean onShowFileChooser(
                    WebView view,
                    ValueCallback<Uri[]> callback,
                    FileChooserParams params) {

                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }

                fileCallback = callback;

                try {
                    Intent intent = params.createIntent();
                    startActivityForResult(intent, FILE_REQUEST);
                    return true;

                } catch (Exception e) {
                    fileCallback = null;

                    Toast.makeText(
                            BrowserActivity.this,
                            "无法打开文件选择器",
                            Toast.LENGTH_SHORT
                    ).show();

                    return false;
                }
            }

            // 视频全屏
            @Override
            public void onShowCustomView(
                    View view,
                    CustomViewCallback callback) {

                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }

                customView = view;
                customCallback = callback;

                content.setVisibility(View.GONE);

                root.addView(
                        customView,
                        new FrameLayout.LayoutParams(-1, -1)
                );
            }

            @Override
            public void onHideCustomView() {
                hideCustomView();
            }
        });

        // ================= 下载功能 =================

        web.setDownloadListener(new DownloadListener() {

            @Override
            public void onDownloadStart(
                    String url,
                    String userAgent,
                    String contentDisposition,
                    String mimeType,
                    long contentLength) {

                try {

                    String fileName = URLUtil.guessFileName(
                            url,
                            contentDisposition,
                            mimeType
                    );

                    DownloadManager.Request request =
                            new DownloadManager.Request(Uri.parse(url));

                    request.setMimeType(mimeType);

                    String cookie =
                            CookieManager.getInstance().getCookie(url);

                    if (cookie != null) {
                        request.addRequestHeader("Cookie", cookie);
                    }

                    request.addRequestHeader("User-Agent", userAgent);

                    request.setTitle(fileName);
                    request.setDescription("正在下载文件");

                    request.setNotificationVisibility(
                            DownloadManager.Request
                                    .VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    );

                    request.setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS,
                            fileName
                    );

                    DownloadManager manager =
                            (DownloadManager) getSystemService(
                                    DOWNLOAD_SERVICE
                            );

                    manager.enqueue(request);

                    Toast.makeText(
                            BrowserActivity.this,
                            "已加入下载任务：" + fileName,
                            Toast.LENGTH_SHORT
                    ).show();

                } catch (Exception e) {

                    Toast.makeText(
                            BrowserActivity.this,
                            "下载失败：" + e.getMessage(),
                            Toast.LENGTH_LONG
                    ).show();
                }
            }
        });

        // 网页占据上方剩余空间
        content.addView(
                web,
                new LinearLayout.LayoutParams(-1, 0, 1)
        );

        // ================= 底部控制栏 =================

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(dp(5), dp(4), dp(5), dp(4));
        bottomBar.setBackgroundColor(Color.rgb(235, 238, 243));

        // 地址输入框
        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextSize(14);
        address.setHint("输入网址或搜索内容");
        address.setSelectAllOnFocus(true);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);

        bottomBar.addView(
                address,
                new LinearLayout.LayoutParams(0, dp(48), 1)
        );

        // 打开按钮
        Button go = makeButton("打开");

        bottomBar.addView(
                go,
                new LinearLayout.LayoutParams(dp(65), dp(48))
        );

        go.setOnClickListener(v -> loadAddress());

        // 刷新按钮
        Button refresh = makeButton("刷新");

        bottomBar.addView(
                refresh,
                new LinearLayout.LayoutParams(dp(65), dp(48))
        );

        refresh.setOnClickListener(v -> web.reload());

        // 键盘回车 / 前往键打开网址
        address.setOnEditorActionListener(
                (v, actionId, event) -> {

                    if (event != null &&
                            event.getKeyCode() == KeyEvent.KEYCODE_ENTER &&
                            event.getAction() == KeyEvent.ACTION_DOWN) {

                        loadAddress();
                        return true;
                    }

                    if (actionId == EditorInfo.IME_ACTION_GO ||
                            actionId == EditorInfo.IME_ACTION_DONE ||
                            actionId == EditorInfo.IME_ACTION_SEARCH) {

                        loadAddress();
                        return true;
                    }

                    return false;
                }
        );

        // 控制栏固定在网页下方
        content.addView(
                bottomBar,
                new LinearLayout.LayoutParams(-1, -2)
        );

        root.addView(
                content,
                new FrameLayout.LayoutParams(-1, -1)
        );

        setContentView(root);
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }

    // ================= 主页 =================

    private void loadHome() {
        web.loadUrl("https://www.baidu.com");
    }

    // ================= 打开网址 =================

    private void loadAddress() {

        String url = address.getText()
                .toString().trim();

        if (url.isEmpty()) return;

        if (!url.contains("://")) {

            if (url.contains(".") && !url.contains(" ")) {
                url = "https://" + url;
            } else {
                url = "https://www.baidu.com/s?wd=" +
                        Uri.encode(url);
            }
        }

        web.loadUrl(url);

        // 收起键盘
        InputMethodManager imm =
                (InputMethodManager) getSystemService(
                        Context.INPUT_METHOD_SERVICE
                );

        if (imm != null) {
            imm.hideSoftInputFromWindow(
                    address.getWindowToken(), 0
            );
        }

        address.clearFocus();
    }

    // ================= 退出视频全屏 =================

    private void hideCustomView() {

        if (customView == null) return;

        root.removeView(customView);

        customView = null;

        content.setVisibility(View.VISIBLE);

        if (customCallback != null) {
            WebChromeClient.CustomViewCallback callback =
                    customCallback;

            customCallback = null;
            callback.onCustomViewHidden();
        }
    }

    // ================= 文件上传返回 =================

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data) {

        super.onActivityResult(
                requestCode,
                resultCode,
                data
        );

        if (requestCode == FILE_REQUEST &&
                fileCallback != null) {

            Uri[] results = null;

            if (resultCode == RESULT_OK && data != null) {

                if (data.getClipData() != null) {

                    int count = data.getClipData()
                            .getItemCount();

                    results = new Uri[count];

                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData()
                                .getItemAt(i).getUri();
                    }

                } else if (data.getData() != null) {

                    results = new Uri[]{
                            data.getData()
                    };
                }
            }

            fileCallback.onReceiveValue(results);
            fileCallback = null;
        }
    }

    // ================= 手机返回键 =================

    @Override
    public void onBackPressed() {

        if (customView != null) {
            hideCustomView();
            return;
        }

        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }

        super.onBackPressed();
    }

    // ================= 销毁 =================

    @Override
    protected void onDestroy() {

        if (fileCallback != null) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }

        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }

        super.onDestroy();
    }
}



