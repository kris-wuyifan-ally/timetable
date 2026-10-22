package com.coursetable.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.coursetable.app.importer.Importers;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class WebLoginActivity extends Activity {
    private WebView webView;
    private TextView captureBtn;
    private ProgressBar progress;
    private String reqId;
    private String captureScript = "";
    private boolean pageReady;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true);
        reqId = getIntent().getStringExtra("reqId");
        String url = getIntent().getStringExtra("url");
        captureScript = readAsset("capture.js");
        buildUi();
        if (TextUtils.isEmpty(url)) {
            finishCanceled();
            return;
        }
        if (!url.matches("(?i)^https?://.*")) url = "http://" + url;
        webView.loadUrl(url);
    }

    private void buildUi() {
        int ink = Color.parseColor("#1F2430");
        int muted = Color.parseColor("#6F7581");

        getWindow().setStatusBarColor(Color.WHITE);
        int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.WHITE);
        bar.setPadding(dp(6), 0, dp(8), 0);
        bar.setElevation(dp(3));

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        back.setTextColor(ink);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("返回");
        back.setOnClickListener(v -> {
            if (webView != null && webView.canGoBack()) webView.goBack();
            else finishCanceled();
        });
        bar.addView(back, new LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText("登录教务系统");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTextColor(ink);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.leftMargin = dp(4);
        bar.addView(title, titleLp);

        captureBtn = new TextView(this);
        captureBtn.setText("抓取课表");
        captureBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        captureBtn.setTextColor(Color.WHITE);
        captureBtn.setTypeface(Typeface.DEFAULT_BOLD);
        captureBtn.setGravity(Gravity.CENTER);
        captureBtn.setPadding(dp(15), dp(8), dp(15), dp(8));
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(ink);
        btnBg.setCornerRadius(dp(10));
        captureBtn.setBackground(btnBg);
        captureBtn.setEnabled(false);
        captureBtn.setAlpha(0.45f);
        captureBtn.setOnClickListener(v -> capture());
        LinearLayout.LayoutParams captureLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        captureLp.leftMargin = dp(6);
        bar.addView(captureBtn, captureLp);

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        TextView hint = new TextView(this);
        hint.setText("登录后打开「学生课表 / 我的课表」，再点右上角「抓取课表」");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setTextColor(Color.parseColor("#6B5D40"));
        hint.setBackgroundColor(Color.parseColor("#FFF9EC"));
        hint.setPadding(dp(12), dp(8), dp(12), dp(8));
        hint.setGravity(Gravity.CENTER);
        root.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(Importers.UA);
        if (Build.VERSION.SDK_INT >= 29) settings.setForceDark(WebSettings.FORCE_DARK_OFF);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme() == null ? "" : uri.getScheme();
                return !("http".equals(scheme) || "https".equals(scheme));
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageReady = false;
                progress.setVisibility(View.VISIBLE);
                captureBtn.setEnabled(false);
                captureBtn.setAlpha(0.45f);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                progress.setVisibility(View.GONE);
                captureBtn.setEnabled(true);
                captureBtn.setAlpha(1f);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    toast("页面加载失败，请检查网络后重试");
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                new AlertDialog.Builder(WebLoginActivity.this)
                        .setTitle("证书异常")
                        .setMessage("该网站的安全证书无法验证，继续访问可能不安全。")
                        .setPositiveButton("继续访问", (dialog, which) -> handler.proceed())
                        .setNegativeButton("取消", (dialog, which) -> handler.cancel())
                        .setCancelable(false)
                        .show();
            }
        });

        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private void capture() {
        if (!pageReady) {
            toast("页面还在加载，请稍候");
            return;
        }
        if (TextUtils.isEmpty(captureScript)) {
            toast("抓取脚本缺失，请重新安装应用");
            return;
        }
        captureBtn.setEnabled(false);
        captureBtn.setText("抓取中…");
        String js = captureScript
                + "\n;(window.__captureTable?window.__captureTable():"
                + "{ok:false,error:'抓取脚本未加载'})";
        webView.evaluateJavascript(js, value -> {
            captureBtn.setText("抓取课表");
            captureBtn.setEnabled(true);
            if (value == null || "null".equals(value)) {
                toast("当前页面没有识别到课表");
                return;
            }
            try {
                JSONObject result = new JSONObject(value);
                if (!result.optBoolean("ok")) {
                    toast(result.optString("error", "没有识别到课表，请先打开课表页面"));
                    return;
                }
                Intent data = new Intent();
                data.putExtra("reqId", reqId);
                data.putExtra("result", value);
                setResult(RESULT_OK, data);
                finish();
            } catch (Exception e) {
                toast("抓取结果解析失败，请重试");
            }
        });
    }

    private void finishCanceled() {
        JSONObject result = new JSONObject();
        try {
            result.put("ok", false);
            result.put("error", "已取消登录抓取");
        } catch (Exception ignored) {
        }
        Intent data = new Intent();
        data.putExtra("reqId", reqId);
        data.putExtra("result", result.toString());
        setResult(RESULT_CANCELED, data);
        finish();
    }

    private String readAsset(String name) {
        try (InputStream is = getAssets().open(name)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "";
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else finishCanceled();
    }

    @Override
    protected void onDestroy() {
        CookieManager.getInstance().flush();
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
