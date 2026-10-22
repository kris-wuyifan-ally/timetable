package com.coursetable.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import com.coursetable.app.importer.Importers;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NativeBridge {
    static final int REQ_WEB_LOGIN = 2101;

    private final MainActivity activity;
    private final WebView webView;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());

    NativeBridge(MainActivity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    @JavascriptInterface
    public String getPlatform() {
        return "android";
    }

    @JavascriptInterface
    public String getVersion() {
        return "1.0";
    }

    @JavascriptInterface
    public String getAutoUpdate() {
        return AutoUpdate.state(activity).toString();
    }

    @JavascriptInterface
    public String saveAutoUpdate(final String configJson) {
        try {
            return AutoUpdate.save(activity, new JSONObject(configJson == null ? "{}" : configJson)).toString();
        } catch (Throwable t) {
            return jsonError("保存自动更新设置失败").toString();
        }
    }

    @JavascriptInterface
    public String disableAutoUpdate() {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", true);
            out.put("state", AutoUpdate.disable(activity));
        } catch (Throwable t) {
            return jsonError("关闭自动更新失败").toString();
        }
        return out.toString();
    }

    @JavascriptInterface
    public void markAutoUpdated(final String fetchedAt) {
        AutoUpdate.markUpdated(activity, fetchedAt);
    }

    @JavascriptInterface
    public void ackAutoUpdate(final long pendingAt) {
        AutoUpdate.ack(activity, pendingAt);
    }

    @JavascriptInterface
    public void startAutoUpdate(final String requestId) {
        pool.execute(() -> {
            String result;
            try {
                result = AutoUpdate.runNow(activity).toString();
            } catch (Throwable t) {
                result = jsonError("自动更新失败，稍后会再试").toString();
            }
            deliver(requestId, result);
        });
    }

    @JavascriptInterface
    public void startImport(final String requestId, final String paramsJson) {
        pool.execute(() -> {
            String result;
            try {
                result = Importers.run(paramsJson);
            } catch (Throwable t) {
                JSONObject err = new JSONObject();
                try {
                    err.put("ok", false);
                    err.put("error", "导入失败：" + (t.getMessage() == null ? "未知错误" : t.getMessage()));
                } catch (Exception ignored) {
                }
                result = err.toString();
            }
            deliver(requestId, result);
        });
    }

    @JavascriptInterface
    public void openWebLogin(final String requestId, final String url) {
        main.post(() -> {
            activity.lastWebReqId = requestId;
            Intent intent = new Intent(activity, WebLoginActivity.class);
            intent.putExtra("reqId", requestId);
            intent.putExtra("url", url);
            activity.startActivityForResult(intent, REQ_WEB_LOGIN);
        });
    }

    @JavascriptInterface
    public void toast(final String text) {
        main.post(() -> Toast.makeText(activity, text, Toast.LENGTH_SHORT).show());
    }

    private JSONObject jsonError(String message) {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", false);
            out.put("error", message);
        } catch (Exception ignored) {
        }
        return out;
    }

    void deliver(final String requestId, final String json) {
        main.post(() -> {
            if (activity.isFinishing() || activity.isDestroyed() || webView == null) return;
            String js = "window.__nativeImportResult(" + JSONObject.quote(requestId) + "," + json + ")";
            webView.evaluateJavascript(js, null);
        });
    }

    void shutdown() {
        pool.shutdownNow();
    }
}
