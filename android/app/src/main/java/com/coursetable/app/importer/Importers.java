package com.coursetable.app.importer;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLException;

public final class Importers {
    public static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private Importers() {}

    public static String run(String paramsJson) {
        try {
            JSONObject p = new JSONObject(paramsJson == null ? "{}" : paramsJson);
            String url = p.optString("url", "").trim();
            if (url.isEmpty()) return error("请填写教务系统网址");
            if (!url.matches("(?i)^https?://.*")) url = "http://" + url;
            String account = p.optString("account", "").trim();
            String password = p.optString("password", "");
            String semester = p.optString("semester", "").trim();
            String system = p.optString("system", "auto").trim();
            if (account.isEmpty() || password.isEmpty()) {
                return error("请输入学号和密码；也可使用「在应用内登录并抓取」");
            }
            Http http = new Http(UA);
            Http.Response landing = http.get(url);
            String finalUrl = landing.url;
            String page = landing.body;
            String type = ("auto".equals(system) || system.isEmpty()) ? detect(finalUrl, page) : system;
            if ("unknown".equals(type)) {
                List<String> links = systemLinks(finalUrl, page);
                for (int i = 0; i < Math.min(3, links.size()); i++) {
                    try {
                        Http.Response next = http.get(links.get(i));
                        String nextType = detect(next.url, next.body);
                        if (!"unknown".equals(nextType)) {
                            landing = next;
                            finalUrl = next.url;
                            page = next.body;
                            type = nextType;
                            break;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            if ("unknown".equals(type)) {
                if (page.contains("统一身份认证")) {
                    return error("学校使用统一身份认证，请使用「在应用内登录并抓取」");
                }
                return error("未能识别教务系统类型。请在应用内登录并抓取，或使用手动导入。");
            }
            String base = baseFor(type, finalUrl, page);
            if (base.isEmpty()) return error("无法确定教务系统地址，请填写登录页网址");
            JSONObject out;
            if ("qz".equals(type)) {
                out = QzJsxsd.importCourses(http, base, account, password, semester);
            } else {
                out = ZfSoft.importCourses(http, base, account, password, semester);
            }
            out.put("system", type);
            return out.toString();
        } catch (Throwable t) {
            return error(friendly(t));
        }
    }

    static String detect(String url, String page) {
        String u = url == null ? "" : url.toLowerCase(Locale.ROOT);
        String p = page == null ? "" : page.toLowerCase(Locale.ROOT);
        if (u.contains("jsxsd") || p.contains("logintoxk") || p.contains("xskb_list.do")) return "qz";
        if (u.contains("jwglxt") || p.contains("login_slogin") || p.contains("login_getpublickey")) return "zf";
        if (page != null) {
            if (page.contains("强智")) return "qz";
            if (page.contains("正方")) return "zf";
        }
        return "unknown";
    }

    static String baseFor(String type, String finalUrl, String page) {
        if ("zf".equals(type)) {
            String b = baseFromSegment(finalUrl, "jwglxt", false);
            if (!b.isEmpty()) return b;
            String action = Html.firstGroup(
                    "action\\s*=\\s*[\"']([^\"']*(?:login_slogin|jwglxt)[^\"']*)[\"']",
                    page, Pattern.CASE_INSENSITIVE);
            if (!action.isEmpty()) {
                String abs = resolve(finalUrl, action);
                int idx = abs.toLowerCase(Locale.ROOT).indexOf("/jwglxt");
                if (idx >= 0) return abs.substring(0, idx + 7);
            }
            int idx = finalUrl.toLowerCase(Locale.ROOT).indexOf("/jwglxt");
            if (idx >= 0) return finalUrl.substring(0, idx + 7);
            return "";
        }
        String b = baseFromSegment(finalUrl, "jsxsd", true);
        if (!b.isEmpty()) return b;
        String action = Html.firstGroup(
                "action\\s*=\\s*[\"']([^\"']*LoginToXk[^\"']*)[\"']",
                page, Pattern.CASE_INSENSITIVE);
        if (!action.isEmpty()) {
            String abs = resolve(finalUrl, action);
            int idx = abs.toLowerCase(Locale.ROOT).indexOf("/xk/");
            if (idx >= 0) return abs.substring(0, idx);
        }
        String normalized = QzJsxsd.normalizeBase(finalUrl);
        if (!normalized.isEmpty() && !normalized.equalsIgnoreCase(finalUrl)) return normalized;
        return "";
    }

    static String baseFromSegment(String finalUrl, String marker, boolean suffix) {
        try {
            URL u = new URL(finalUrl);
            String path = u.getPath();
            String[] segs = path.split("/");
            int idx = -1;
            for (int i = 0; i < segs.length; i++) {
                String s = segs[i].toLowerCase(Locale.ROOT);
                if (s.equals(marker) || (suffix && s.endsWith(marker))) {
                    idx = i;
                    if (s.equals(marker)) break;
                }
            }
            if (idx < 0) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= idx; i++) sb.append('/').append(segs[i]);
            return origin(u) + sb;
        } catch (Exception e) {
            return "";
        }
    }

    static String origin(URL u) {
        String port = u.getPort() > 0 && u.getPort() != u.getDefaultPort() ? ":" + u.getPort() : "";
        return u.getProtocol() + "://" + u.getHost() + port;
    }

    static String resolve(String base, String href) {
        try {
            return new URL(new URL(base), href).toString();
        } catch (Exception e) {
            return href;
        }
    }

    static List<String> systemLinks(String finalUrl, String page) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</a>",
                Pattern.CASE_INSENSITIVE).matcher(page);
        while (m.find() && out.size() < 8) {
            String href = m.group(1).trim();
            String text = Html.stripTags(m.group(2));
            String low = href.toLowerCase(Locale.ROOT);
            if (low.startsWith("#") || low.startsWith("javascript") || low.startsWith("mailto")) continue;
            boolean direct = low.contains("jwglxt") || low.contains("jsxsd");
            boolean label = text.contains("教务") || text.contains("课表");
            if (!direct && !label) continue;
            try {
                String abs = new URL(new URL(finalUrl), href).toString();
                if (!abs.matches("(?i)^https?://.*")) continue;
                if (!direct && !sameHost(finalUrl, abs)) continue;
                if (!out.contains(abs)) out.add(abs);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    static boolean sameHost(String a, String b) {
        try {
            return new URI(a).getHost().equalsIgnoreCase(new URI(b).getHost());
        } catch (Exception e) {
            return false;
        }
    }

    static String error(String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("error", message == null || message.isEmpty() ? "导入失败，请稍后重试" : message);
        } catch (JSONException ignored) {
        }
        return o.toString();
    }

    static String friendly(Throwable t) {
        if (t instanceof SSLException) {
            return "学校系统证书异常，请使用「在应用内登录并抓取」";
        }
        if (t instanceof UnknownHostException) {
            return "无法解析学校网址，请检查网络或地址";
        }
        if (t instanceof SocketTimeoutException || t instanceof ConnectException) {
            return "连接教务系统超时，请检查网络或稍后重试";
        }
        if (t instanceof JSONException) {
            return "教务系统返回了无法识别的数据，请使用「在应用内登录并抓取」";
        }
        String msg = t.getMessage();
        if (msg == null || msg.trim().isEmpty()) msg = "导入失败，请稍后重试";
        return msg;
    }
}
