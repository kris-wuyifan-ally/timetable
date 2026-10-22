package com.coursetable.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.util.ArrayList;

public class MainActivity extends Activity {
    static final int REQ_FILE = 1201;

    WebView webView;
    NativeBridge bridge;
    ValueCallback<Uri[]> fileCallback;
    String lastWebReqId;
    private long lastBack;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true);
        webView = new WebView(this);
        webView.setBackgroundColor(0xFFF5F4EF);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= 29) settings.setForceDark(WebSettings.FORCE_DARK_OFF);
        settings.setUserAgentString(settings.getUserAgentString() + " CourseTable/1.0");

        bridge = new NativeBridge(this, webView);
        webView.addJavascriptInterface(bridge, "AndroidBridge");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivityForResult(Intent.createChooser(intent, "选择课表 JSON"), REQ_FILE);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String url = uri.toString();
                String scheme = uri.getScheme();
                if (url.startsWith("file:///android_asset/") || "about".equals(scheme)) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                }
                return true;
            }
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl("file:///android_asset/index.html");
        }
        setupWindow();
        AutoUpdate.ensureScheduled(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        WebView view = webView;
        if (view != null) {
            view.post(() -> {
                if (webView == view) {
                    view.evaluateJavascript(
                            "window.__autoUpdateTick&&window.__autoUpdateTick()", null);
                }
            });
        }
    }

    private void setupWindow() {
        getWindow().setStatusBarColor(0xFFF5F4EF);
        getWindow().setNavigationBarColor(0xFFFFFFFF);
        int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) webView.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (webView == null) {
            super.onBackPressed();
            return;
        }
        webView.evaluateJavascript(
                "(function(){try{return !!(window.__androidBack&&window.__androidBack());}catch(e){return false}})()",
                value -> {
                    if ("true".equals(value)) return;
                    long now = System.currentTimeMillis();
                    if (now - lastBack < 2200) {
                        finish();
                    } else {
                        lastBack = now;
                        Toast.makeText(MainActivity.this, "再按一次退出", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == NativeBridge.REQ_WEB_LOGIN) {
            String reqId = data != null ? data.getStringExtra("reqId") : null;
            String result = data != null ? data.getStringExtra("result") : null;
            if (reqId == null) reqId = lastWebReqId;
            lastWebReqId = null;
            if (reqId != null) {
                if (result == null) result = "{\"ok\":false,\"error\":\"已取消登录抓取\"}";
                bridge.deliver(reqId, result);
            }
            return;
        }
        if (requestCode == REQ_FILE) {
            if (fileCallback != null) {
                Uri[] result = null;
                if (resultCode == RESULT_OK && data != null) {
                    if (data.getData() != null) {
                        result = new Uri[]{data.getData()};
                    } else if (data.getClipData() != null) {
                        ArrayList<Uri> uris = new ArrayList<>();
                        for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                            uris.add(data.getClipData().getItemAt(i).getUri());
                        }
                        result = uris.toArray(new Uri[0]);
                    }
                }
                fileCallback.onReceiveValue(result);
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onDestroy() {
        if (bridge != null) bridge.shutdown();
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidBridge");
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
