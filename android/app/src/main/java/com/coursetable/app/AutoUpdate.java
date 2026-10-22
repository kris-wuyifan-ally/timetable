package com.coursetable.app;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

import com.coursetable.app.importer.Importers;

import org.json.JSONObject;

final class AutoUpdate {
    static final int JOB_ID = 4201;
    private static final long DAY = 86400000L;
    private static final Object LOCK = new Object();

    private AutoUpdate() {}

    static JSONObject save(Context context, JSONObject input) {
        synchronized (LOCK) {
            try {
                boolean enabled = input.optBoolean("enabled", false);
                int interval = clamp(input.optInt("intervalDays", 1));
                if (!enabled) {
                    AutoUpdateStore.clear(context);
                    cancel(context);
                    return ok(context);
                }
                JSONObject current = AutoUpdateStore.config(context);
                String url = input.optString("url", "").trim();
                String account = input.optString("account", "").trim();
                String password = input.optString("password", "");
                String system = input.optString("system", "auto").trim();
                String semester = input.optString("semester", "").trim();
                if (current != null) {
                    if (url.isEmpty()) url = current.optString("url", "");
                    if (account.isEmpty()) account = current.optString("account", "");
                    boolean same = url.equals(current.optString("url", ""))
                            && account.equals(current.optString("account", ""));
                    if (password.isEmpty() && same) password = current.optString("password", "");
                }
                if (url.isEmpty()) return error("请先填写教务系统网址");
                if (account.isEmpty()) return error("请先填写学号");
                if (password.isEmpty()) return error("请重新输入密码");
                if (!"qz".equals(system) && !"zf".equals(system)) system = "auto";
                JSONObject config = new JSONObject();
                config.put("url", url);
                config.put("account", account);
                config.put("password", password);
                config.put("system", system);
                config.put("semester", semester);
                AutoUpdateStore.saveConfig(context, config);
                AutoUpdateStore.setIntervalDays(context, interval);
                AutoUpdateStore.setEnabled(context, true);
                if (!schedule(context, interval)) {
                    return errorWithState(context, "系统不支持后台定时更新，仍可在打开应用时更新");
                }
                return ok(context);
            } catch (Throwable t) {
                return errorWithState(context, friendly(t));
            }
        }
    }

    static JSONObject disable(Context context) {
        synchronized (LOCK) {
            AutoUpdateStore.clear(context);
            cancel(context);
            return AutoUpdateStore.state(context);
        }
    }

    static JSONObject runNow(Context context) {
        synchronized (LOCK) {
            if (!AutoUpdateStore.enabled(context)) return error("自动更新已关闭");
            JSONObject config = AutoUpdateStore.config(context);
            if (config == null) return error("自动更新尚未配置");
            long attempt = AutoUpdateStore.lastAttemptAt(context);
            if (attempt > 0 && System.currentTimeMillis() - attempt < 60000L) {
                JSONObject pending = AutoUpdateStore.pending(context);
                if (pending != null) return pending;
                String lastError = AutoUpdateStore.lastError(context);
                if (lastError != null && !lastError.isEmpty()) return error(lastError);
                return error("刚刚更新过，请稍后再试");
            }
            try {
                JSONObject params = new JSONObject();
                params.put("url", config.optString("url", ""));
                params.put("account", config.optString("account", ""));
                params.put("password", config.optString("password", ""));
                params.put("system", config.optString("system", "auto"));
                params.put("semester", config.optString("semester", ""));
                JSONObject result = new JSONObject(Importers.run(params.toString()));
                if (result.optBoolean("ok", false)) {
                    AutoUpdateStore.recordSuccess(context, result);
                } else {
                    AutoUpdateStore.recordFailure(context,
                            result.optString("error", "自动更新失败，稍后会再试"));
                }
                return result;
            } catch (Throwable t) {
                String message = friendly(t);
                AutoUpdateStore.recordFailure(context, message);
                return error(message);
            }
        }
    }

    static void markUpdated(Context context, String fetchedAt) {
        if (AutoUpdateStore.enabled(context)) {
            AutoUpdateStore.markUpdated(context, fetchedAt);
        }
    }

    static void ack(Context context, long pendingAt) {
        AutoUpdateStore.ackPending(context, pendingAt);
    }

    static JSONObject state(Context context) {
        return AutoUpdateStore.state(context);
    }

    static void ensureScheduled(Context context) {
        try {
            if (!AutoUpdateStore.enabled(context)) return;
            JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler == null) return;
            if (scheduler.getPendingJob(JOB_ID) == null) {
                schedule(context, AutoUpdateStore.intervalDays(context));
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean schedule(Context context, int intervalDays) {
        try {
            JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler == null) return false;
            scheduler.cancel(JOB_ID);
            JobInfo info = new JobInfo.Builder(JOB_ID,
                    new ComponentName(context, AutoUpdateJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(Math.max(1, intervalDays) * DAY)
                    .setPersisted(true)
                    .build();
            return scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void cancel(Context context) {
        try {
            JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler != null) scheduler.cancel(JOB_ID);
        } catch (Throwable ignored) {
        }
    }

    private static int clamp(int days) {
        return Math.max(1, Math.min(30, days));
    }

    private static JSONObject ok(Context context) {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", true);
            out.put("state", AutoUpdateStore.state(context));
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static JSONObject error(String message) {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", false);
            out.put("error", message);
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static JSONObject errorWithState(Context context, String message) {
        JSONObject out = error(message);
        try {
            out.put("state", AutoUpdateStore.state(context));
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String friendly(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.trim().isEmpty()) message = "自动更新设置失败，请重试";
        return message;
    }
}
