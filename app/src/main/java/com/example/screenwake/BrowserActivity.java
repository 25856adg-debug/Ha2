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
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
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

    private Button moveButton;
    private boolean barMoved = false;

    private EditText address;
    private WebView web;

    private ValueCallback<Uri[]> fileCallback;

    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    private static final int FILE_REQUEST = 1001;

    private int dp(float n) {
        return (int) (n * getResources()
                .getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

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

        // ================= 网页 =================

        web = new WebView(this);

        WebSettings settings = web.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);

        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(true);

        // 让 target=_blank 链接尽量在当前 WebView 打开
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);

        // 网页文字长按选择和复制
        web.setLongClickable(true);
        web.setHapticFeedbackEnabled(true);

        web.setWebViewClient(new WebViewClient() {

            // 新版 Android 网页跳转
            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    WebResourceRequest request) {

                if (request == null || request.getUrl() == null) {
                    return false;
                }

                return handleUrl(view, request.getUrl().toString());
            }

            // 兼容旧版网页跳转
            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    String url) {

                return handleUrl(view, url);
            }

            @Override
            public void onPageStarted(
                    WebView view,
                    String url,
                    android.graphics.Bitmap favicon) {

                super.onPageStarted(view, url, favicon);

                if (address != null && url != null) {
                    address.setText(url);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {

                super.onPageFinished(view, url);

                if (address != null && url != null) {
                    address.setText(url);
                    address.setSelection(
                            address.getText().length()
                    );
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {

            // 文件上传
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

        // ================= 下载 =================

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

        // 网页区域
        content.addView(
                web,
                new LinearLayout.LayoutParams(-1, 0, 1)
        );

        // ================= 控制栏 =================

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(dp(3), dp(4), dp(3), dp(4));
        bottomBar.setBackgroundColor(Color.rgb(235, 238, 243));

        // 放大镜按钮
        moveButton = makeButton("⌕");
        moveButton.setTextSize(22);

        bottomBar.addView(
                moveButton,
                new LinearLayout.LayoutParams(dp(42), dp(48))
        );

        moveButton.setOnClickListener(v -> {
            if (!barMoved) {
                moveBarToCenter();
            } else {
                moveBarToBottom();
            }
        });

        // 地址栏
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

        // 点击地址栏时，自动移动到中间
        address.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && !barMoved) {
                moveBarToCenter();
            }
        });

        // 打开按钮
        Button go = makeButton("打开");

        bottomBar.addView(
                go,
                new LinearLayout.LayoutParams(dp(60), dp(48))
        );

        go.setOnClickListener(v -> loadAddress());

        // 刷新按钮
        Button refresh = makeButton("刷新");

        bottomBar.addView(
                refresh,
                new LinearLayout.LayoutParams(dp(60), dp(48))
        );

        refresh.setOnClickListener(v -> {
            if (web != null) {
                web.reload();
            }
        });

        // 键盘回车打开
        address.setOnEditorActionListener((v, actionId, event) -> {

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
        });

        // 初始放在底部
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

    // ================= 网页链接处理 =================

    private boolean handleUrl(WebView view, String url) {

        if (url == null || url.isEmpty()) {
            return false;
        }

        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();

        // 普通网页在当前 WebView 加载
        if (scheme == null ||
                scheme.equalsIgnoreCase("http") ||
                scheme.equalsIgnoreCase("https") ||
                scheme.equalsIgnoreCase("file") ||
                scheme.equalsIgnoreCase("about") ||
                scheme.equalsIgnoreCase("javascript") ||
                scheme.equalsIgnoreCase("data")) {

            return false;
        }

        // 电话、邮件等交给系统处理
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "无法打开此链接",
                    Toast.LENGTH_SHORT
            ).show();
        }

        return true;
    }

    // ================= 创建按钮 =================

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }

    // ================= 控制栏移动到中间 =================

    private void moveBarToCenter() {

        if (bottomBar == null || root == null || barMoved) {
            return;
        }

        if (bottomBar.getParent() instanceof ViewGroup) {
            ((ViewGroup) bottomBar.getParent())
                    .removeView(bottomBar);
        }

        FrameLayout.LayoutParams params =
                new FrameLayout.LayoutParams(
                        -1,
                        -2,
                        Gravity.CENTER
                );

        params.leftMargin = dp(4);
        params.rightMargin = dp(4);

        bottomBar.setElevation(dp(12));

        root.addView(bottomBar, params);

        barMoved = true;
        moveButton.setText("⌄");
    }

    // ================= 控制栏移回底部 =================

    private void moveBarToBottom() {

        if (bottomBar == null || content == null || !barMoved) {
            return;
        }

        if (bottomBar.getParent() instanceof ViewGroup) {
            ((ViewGroup) bottomBar.getParent())
                    .removeView(bottomBar);
        }

        bottomBar.setElevation(0);

        content.addView(
                bottomBar,
                new LinearLayout.LayoutParams(-1, -2)
        );

        barMoved = false;
        moveButton.setText("⌕");
    }

    // ================= 放大镜切换 =================

    private void toggleBarPosition() {

        if (barMoved) {
            moveBarToBottom();
        } else {
            moveBarToCenter();
        }
    }

    // ================= 主页 =================

    private void loadHome() {
        web.loadUrl("https://www.hao123.com");
    }

    // ================= 打开网址 =================

    private void loadAddress() {

        String url = address.getText()
                .toString().trim();

        if (url.isEmpty()) {
            return;
        }

        if (!url.contains("://")) {

            if (url.contains(".") && !url.contains(" ")) {
                url = "https://" + url;
            } else {
                url = "https://www.hao123.com/s?wd=" +
                        Uri.encode(url);
            }
        }

        // 直接在当前 WebView 打开
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

        // 回到底部
        if (barMoved) {
            moveBarToBottom();
        }
    }

    // ================= 退出视频全屏 =================

    private void hideCustomView() {

        if (customView == null) {
            return;
        }

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

