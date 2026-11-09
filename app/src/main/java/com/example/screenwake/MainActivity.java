package com.example.screenwake;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.webkit.*;
import android.widget.*;

public class MainActivity extends Activity {

    private WebView web;
    private EditText address;
    private FrameLayout page;

    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;

    private ValueCallback<Uri[]> fileCallback;

    private static final int PICK_FILE = 501;

    private final String home =
        "https://www.google.com";

    private int dp(float n) {
        return (int)(n *
            getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);

        getWindow().setStatusBarColor(
            Color.rgb(25,31,45)
        );

        getWindow().setNavigationBarColor(Color.BLACK);

        buildBrowser();

        if (b != null) {
            web.restoreState(b);
        } else {
            web.loadUrl(home);
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                700
            );
        }
    }

    private TextView makeButton(
        String text,
        Runnable action
    ) {
        TextView v = new TextView(this);

        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTextSize(13);
        v.setGravity(Gravity.CENTER);

        v.setPadding(dp(5),0,dp(5),0);

        v.setBackgroundColor(
            Color.rgb(48,59,79)
        );

        v.setOnClickListener(w -> action.run());

        return v;
    }

    private void buildBrowser() {

        LinearLayout root = new LinearLayout(this);

        root.setOrientation(LinearLayout.VERTICAL);

        root.setBackgroundColor(
            Color.rgb(18,23,33)
        );

        // 地址栏

        LinearLayout addressRow = new LinearLayout(this);

        addressRow.setPadding(
            dp(7),dp(6),dp(7),dp(6)
        );

        addressRow.setGravity(Gravity.CENTER_VERTICAL);

        address = new EditText(this);

        address.setSingleLine(true);
        address.setTextSize(14);

        address.setHint("输入网址或搜索内容");

        address.setImeOptions(
            EditorInfo.IME_ACTION_GO
        );

        address.setInputType(
            android.text.InputType.TYPE_CLASS_TEXT |
            android.text.InputType.TYPE_TEXT_VARIATION_URI
        );

        address.setPadding(dp(10),0,dp(10),0);

        address.setBackgroundColor(Color.WHITE);

        addressRow.addView(
            address,
            new LinearLayout.LayoutParams(0,dp(42),1)
        );

        TextView go = makeButton(
            "打开",
            this::openAddress
        );

        LinearLayout.LayoutParams goLp =
            new LinearLayout.LayoutParams(dp(58),dp(42));

        goLp.leftMargin = dp(6);

        addressRow.addView(go,goLp);

        address.setOnEditorActionListener((v,a,e)->{

            if(a == EditorInfo.IME_ACTION_GO ||
                (e != null &&
                 e.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER)) {

                openAddress();
                return true;
            }

            return false;
        });

        root.addView(addressRow);

        // 底部功能按钮

        LinearLayout toolbar = new LinearLayout(this);

        toolbar.setGravity(Gravity.CENTER);

        toolbar.setPadding(
            dp(3),dp(4),dp(3),dp(4)
        );

        toolbar.setBackgroundColor(
            Color.rgb(28,34,48)
        );

        String[] labels = {
            "‹","›","刷新","主页","下载","设置","退出"
        };

        Runnable[] actions = {

            () -> {
                if(web.canGoBack()) web.goBack();
            },

            () -> {
                if(web.canGoForward()) web.goForward();
            },

            () -> web.reload(),

            () -> web.loadUrl(home),

            () -> showDownloads(),

            () -> showSettings(),

            () -> confirmExit()
        };

        for(int i=0;i<labels.length;i++) {

            toolbar.addView(
                makeButton(labels[i],actions[i]),
                new LinearLayout.LayoutParams(
                    0,dp(38),1
                )
            );
        }

        root.addView(toolbar);

        // 网页显示区域

        page = new FrameLayout(this);

        web = new WebView(this);

        WebSettings s = web.getSettings();

        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);

        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);

        s.setMediaPlaybackRequiresUserGesture(false);

        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);

        s.setJavaScriptCanOpenWindowsAutomatically(true);

        // 网页跳转

        web.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(
                WebView v,
                WebResourceRequest r
            ) {
                return false;
            }

            @Override
            public void onPageStarted(
                WebView v,
                String url,
                android.graphics.Bitmap icon
            ) {
                address.setText(url);
            }

            @Override
            public void onPageFinished(
                WebView v,
                String url
            ) {
                address.setText(url);
            }
        });

        // 视频、文件选择

        web.setWebChromeClient(new WebChromeClient() {

            @Override
            public void onProgressChanged(
                WebView v,
                int progress
            ) {
                setTitle("ScreenWake 浏览器 · " + progress + "%");
            }

            // 视频全屏

            @Override
            public void onShowCustomView(
                View view,
                CustomViewCallback callback
            ) {

                if(fullscreenView != null) {
                    callback.onCustomViewHidden();
                    return;
                }

                fullscreenView = view;
                fullscreenCallback = callback;

                page.addView(
                    view,
                    new FrameLayout.LayoutParams(-1,-1)
                );

                web.setVisibility(View.GONE);

                getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                );
            }

            @Override
            public void onHideCustomView() {
                hideFullscreen();
            }

            // 网页文件上传

            @Override
            public boolean onShowFileChooser(
                WebView v,
                ValueCallback<Uri[]> callback,
                FileChooserParams params
            ) {

                if(fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }

                fileCallback = callback;

                Intent i = new Intent(
                    Intent.ACTION_OPEN_DOCUMENT
                );

                i.addCategory(
                    Intent.CATEGORY_OPENABLE
                );

                i.setType("*/*");

                i.putExtra(
                    Intent.EXTRA_ALLOW_MULTIPLE,
                    true
                );

                try {
                    startActivityForResult(i,PICK_FILE);
                } catch(Exception ex) {
                    fileCallback = null;
                    callback.onReceiveValue(null);
                }

                return true;
            }
        });

        // 网页下载

        web.setDownloadListener(
            (url,userAgent,disposition,mime,length) -> {

                try {

                    DownloadManager.Request req =
                        new DownloadManager.Request(
                            Uri.parse(url)
                        );

                    String cookie =
                        CookieManager.getInstance().getCookie(url);

                    if(cookie != null) {
                        req.addRequestHeader("Cookie",cookie);
                    }

                    if(userAgent != null) {
                        req.addRequestHeader("User-Agent",userAgent);
                    }

                    req.setMimeType(mime);

                    req.setTitle(
                        URLUtil.guessFileName(
                            url,disposition,mime
                        )
                    );

                    req.setDescription("网页文件下载");

                    req.setNotificationVisibility(
                        DownloadManager.Request
                            .VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    );

                    req.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        URLUtil.guessFileName(
                            url,disposition,mime
                        )
                    );

                    ((DownloadManager)getSystemService(
                        DOWNLOAD_SERVICE
                    )).enqueue(req);

                    Toast.makeText(
                        this,
                        "已加入下载任务，文件保存到 Download",
                        Toast.LENGTH_LONG
                    ).show();

                } catch(Exception ex) {

                    Toast.makeText(
                        this,
                        "下载失败：" + ex.getMessage(),
                        Toast.LENGTH_LONG
                    ).show();
                }
            }
        );

        page.addView(
            web,
            new FrameLayout.LayoutParams(-1,-1)
        );

        root.addView(
            page,
            new LinearLayout.LayoutParams(-1,0,1)
        );

        // 左右滑动退出栏

        TextView gestureBar = new TextView(this);

        gestureBar.setText(
            "☰   左右滑动此栏退出浏览器   ☰"
        );

        gestureBar.setTextColor(Color.LTGRAY);
        gestureBar.setTextSize(11);
        gestureBar.setGravity(Gravity.CENTER);

        gestureBar.setBackgroundColor(
            Color.rgb(28,34,48)
        );

        final float[] sx = {0};
        final float[] sy = {0};

        gestureBar.setOnTouchListener((v,e)->{

            if(e.getAction() == MotionEvent.ACTION_DOWN) {

                sx[0] = e.getX();
                sy[0] = e.getY();

                return true;
            }

            if(e.getAction() == MotionEvent.ACTION_UP) {

                float dx = Math.abs(e.getX()-sx[0]);
                float dy = Math.abs(e.getY()-sy[0]);

                if(dx > dp(55) && dx > dy * 1.3f) {
                    confirmExit();
                }

                return true;
            }

            return true;
        });

        root.addView(
            gestureBar,
            new LinearLayout.LayoutParams(-1,dp(28))
        );

        setContentView(root);
    }

    // 关闭视频全屏

    private void hideFullscreen() {

        if(fullscreenView == null) return;

        page.removeView(fullscreenView);

        fullscreenView = null;

        web.setVisibility(View.VISIBLE);

        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_VISIBLE
        );

        if(fullscreenCallback != null) {

            fullscreenCallback.onCustomViewHidden();

            fullscreenCallback = null;
        }
    }

    // 地址栏

    private void openAddress() {

        String q = address.getText().toString().trim();

        if(q.isEmpty()) return;

        if(!q.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {

            if(q.contains(".") && !q.contains(" ")) {
                q = "https://" + q;
            } else {
                q = "https://www.google.com/search?q=" +
                    Uri.encode(q);
            }
        }

        web.loadUrl(q);

        ((android.view.inputmethod.InputMethodManager)
            getSystemService(INPUT_METHOD_SERVICE))
            .hideSoftInputFromWindow(
                address.getWindowToken(),0
            );
    }

    // 下载列表

    private void showDownloads() {

        try {

            startActivity(
                new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
            );

        } catch(Exception e) {

            Toast.makeText(
                this,
                "无法打开下载列表",
                Toast.LENGTH_SHORT
            ).show();
        }
    }

    // 设置入口

    private void showSettings() {

        String[] choices = {
            "开启悬浮窗权限",
            "请求忽略电池优化",
            "打开应用详情（自启动等设置）",
            "立即测试：亮屏 5 分钟",
            "开机亮屏说明"
        };

        new AlertDialog.Builder(this)
            .setTitle("浏览器设置")
            .setItems(choices,(d,w)->{

                try {

                    if(w == 0) {

                        startActivity(
                            new Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:"+getPackageName())
                            )
                        );

                    } else if(w == 1 &&
                        Build.VERSION.SDK_INT >= 23) {

                        startActivity(
                            new Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:"+getPackageName())
                            )
                        );

                    } else if(w == 2) {

                        startActivity(
                            new Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:"+getPackageName())
                            )
                        );

                    } else if(w == 3) {

                        startActivity(
                            new Intent(this,PatternActivity.class)
                        );

                    } else {

                        new AlertDialog.Builder(this)
                            .setMessage(
                                "请在 ColorOS 设置中手动允许本应用自启动、后台运行和后台弹出界面。Android 系统可能限制开机后自动启动界面，本应用会尝试唤醒，但无法保证所有系统版本都允许。"
                            )
                            .setPositiveButton("知道了",null)
                            .show();
                    }

                } catch(Exception e) {

                    Toast.makeText(
                        this,
                        "请从系统设置中查找对应权限",
                        Toast.LENGTH_LONG
                    ).show();
                }
            })
            .show();
    }

    // 退出确认

    private void confirmExit() {

        new AlertDialog.Builder(this)
            .setTitle("退出浏览器？")
            .setMessage(
                "退出浏览器不会删除开机亮屏功能。"
            )
            .setNegativeButton("取消",null)
            .setPositiveButton("退出",(d,w)->finish())
            .show();
    }

    // 上传文件返回

    @Override
    protected void onActivityResult(
        int req,
        int result,
        Intent data
    ) {

        super.onActivityResult(req,result,data);

        if(req == PICK_FILE && fileCallback != null) {

            Uri[] uris = null;

            if(result == RESULT_OK && data != null) {

                if(data.getClipData() != null) {

                    int n = data.getClipData().getItemCount();

                    uris = new Uri[n];

                    for(int k=0;k<n;k++) {
                        uris[k] = data.getClipData()
                            .getItemAt(k).getUri();
                    }

                } else if(data.getData() != null) {

                    uris = new Uri[]{data.getData()};
                }
            }

            fileCallback.onReceiveValue(uris);

            fileCallback = null;
        }
    }

    // 手机返回键

    @Override
    public void onBackPressed() {

        if(fullscreenView != null) {
            hideFullscreen();
            return;
        }

        if(web != null && web.canGoBack()) {
            web.goBack();
        } else {
            confirmExit();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {

        super.onSaveInstanceState(out);

        if(web != null) {
            web.saveState(out);
        }
    }

    @Override
    protected void onDestroy() {

        if(fileCallback != null) {
            fileCallback.onReceiveValue(null);
        }

        if(web != null) {
            web.stopLoading();
            web.destroy();
        }

        super.onDestroy();
    }
}