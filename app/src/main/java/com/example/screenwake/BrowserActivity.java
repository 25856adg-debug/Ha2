package com.example.screenwake;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
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

        buildBrowser();
        loadHome();
    }

    private void buildBrowser() {

        root = new FrameLayout(this);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Color.WHITE);

        // 网页区域：占据上方剩余空间
        web = new WebView(this);

        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setDatabaseEnabled(true);
        web.getSettings().setSupportZoom(true);
        web.getSettings().setBuiltInZoomControls(true);
        web.getSettings().setDisplayZoomControls(false);
        web.getSettings().setAllowFileAccess(true);

        web.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                address.setText(url);
                address.setSelection(address.getText().length());
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

                root.addView(customView,
                        new FrameLayout.LayoutParams(-1, -1));
            }

            @Override
            public void onHideCustomView() {

                if (customView == null) return;

                root.removeView(customView);

                customView = null;

                content.setVisibility(View.VISIBLE);

                if (customCallback != null) {
                    customCallback.onCustomViewHidden();
                    customCallback = null;
                }
            }
        });

        // 下载文件
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

        content.addView(web,
                new LinearLayout.LayoutParams(-1, 0, 1));

        // 底部固定控制区
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setPadding(dp(5), dp(4), dp(5), dp(4));
        bottomBar.setBackgroundColor(Color.rgb(235, 238, 243));

        // 第一行：网址输入框 + 打开按钮
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextSize(14);
        address.setHint("输入网址或搜索内容");
        address.setSelectAllOnFocus(true);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);

        row1.addView(address,
                new LinearLayout.LayoutParams(0, dp(48), 1));

        Button go = makeButton("打开");

        row1.addView(go,
                new LinearLayout.LayoutParams(dp(76), dp(48)));

        go.setOnClickListener(v -> loadAddress());

        // 键盘回车 / 前往键打开网址
        address.setOnEditorActionListener((v, actionId, event) -> {
            loadAddress();
            return true;
        });

        bottomBar.addView(row1);

        // 第二行：只保留刷新按钮
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER);

        Button refresh = makeButton("刷新");

        row2.addView(refresh,
                new LinearLayout.LayoutParams(-1, dp(42)));

        refresh.setOnClickListener(v -> web.reload());

        bottomBar.addView(row2);

        // 内容在上，底栏在下
        content.addView(bottomBar,
                new LinearLayout.LayoutParams(-1, -2));

        root.addView(content,
                new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }

    private void loadHome() {
        web.loadUrl("https://www.baidu.com");
    }

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
                    address.getWindowToken(), 0);
        }

        address.clearFocus();
    }

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

    @Override
    public void onBackPressed() {

        if (customView != null) {

            if (web != null) {
                web.evaluateJavascript(
                        "document.exitFullscreen && document.exitFullscreen()",
                        null
                );
            }

            return;
        }

        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }

        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {

        if (fileCallback != null) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }

        if (web != null) {
            web.stopLoading();
            web.destroy();
        }

        super.onDestroy();
    }
}

