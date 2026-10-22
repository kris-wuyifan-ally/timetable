package com.coursetable.app.importer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

public final class Http {
    public static class Response {
        public final String url;
        public final int code;
        public final String body;
        public final String contentType;

        Response(String url, int code, String body, String contentType) {
            this.url = url;
            this.code = code;
            this.body = body;
            this.contentType = contentType;
        }
    }

    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final String userAgent;

    public Http(String userAgent) {
        this.userAgent = userAgent;
    }

    public Response get(String url) throws IOException {
        return get(url, null);
    }

    public Response get(String url, String referer) throws IOException {
        return request("GET", url, null, referer, null);
    }

    public Response post(String url, Map<String, String> form, String referer) throws IOException {
        return request("POST", url, encodeForm(form), referer, null);
    }

    public Response post(String url, Map<String, String> form, String referer,
                         Map<String, String> headers) throws IOException {
        return request("POST", url, encodeForm(form), referer, headers);
    }

    private static String encodeForm(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        if (form != null) {
            for (Map.Entry<String, String> e : form.entrySet()) {
                if (sb.length() > 0) sb.append('&');
                try {
                    sb.append(URLEncoder.encode(e.getKey() == null ? "" : e.getKey(), "UTF-8"))
                            .append('=')
                            .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8"));
                } catch (UnsupportedEncodingException ignored) {
                }
            }
        }
        return sb.toString();
    }

    private Response request(String method, String url, String body, String referer,
                             Map<String, String> headers) throws IOException {
        String currentUrl = url;
        String currentMethod = method;
        String currentBody = body;
        String currentReferer = referer;
        for (int hop = 0; hop < 12; hop++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(currentUrl).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setUseCaches(false);
            conn.setRequestMethod(currentMethod);
            conn.setRequestProperty("User-Agent", userAgent);
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            conn.setRequestProperty("Connection", "close");
            if (currentReferer != null && !currentReferer.isEmpty()) {
                conn.setRequestProperty("Referer", currentReferer);
            }
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        conn.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
            }
            String cookie = cookieHeader(currentUrl);
            if (!cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
            if ("POST".equals(currentMethod) && currentBody != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                byte[] data = currentBody.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(data.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(data);
                }
            }
            int code = conn.getResponseCode();
            saveCookies(currentUrl, conn.getHeaderFields());
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                if (location != null && !location.isEmpty()) {
                    String next = new URL(new URL(currentUrl), location).toString();
                    String prev = currentUrl;
                    if (code == 303 || ((code == 301 || code == 302) && "POST".equals(currentMethod))) {
                        currentMethod = "GET";
                        currentBody = null;
                    }
                    currentUrl = next;
                    currentReferer = prev;
                    try {
                        conn.disconnect();
                    } catch (Exception ignored) {
                    }
                    continue;
                }
            }
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) throw new IOException("HTTP " + code);
            String encoding = conn.getContentEncoding();
            if (encoding != null && encoding.toLowerCase(Locale.ROOT).contains("gzip")) {
                in = new GZIPInputStream(in);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            try {
                in.close();
            } catch (IOException ignored) {
            }
            byte[] raw = bos.toByteArray();
            String contentType = conn.getContentType();
            String text = decode(raw, charsetOf(contentType));
            return new Response(currentUrl, code, text, contentType == null ? "" : contentType);
        }
        throw new IOException("重定向次数过多");
    }

    private String cookieHeader(String url) {
        try {
            List<HttpCookie> list = cookies.getCookieStore().get(new URI(url));
            if (list == null || list.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (HttpCookie c : list) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(c.getName()).append('=').append(c.getValue());
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void saveCookies(String url, Map<String, List<String>> headers) {
        try {
            cookies.put(new URI(url), headers);
        } catch (Exception ignored) {
        }
    }

    private static String charsetOf(String contentType) {
        if (contentType == null) return "";
        Matcher m = Pattern.compile("(?i)charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)")
                .matcher(contentType);
        return m.find() ? m.group(1) : "";
    }

    private static String decode(byte[] raw, String charset) {
        List<String> candidates = new ArrayList<>();
        if (charset != null && !charset.isEmpty()) candidates.add(charset);
        candidates.add("UTF-8");
        candidates.add("GB18030");
        String fallback = null;
        for (String enc : candidates) {
            try {
                String s = new String(raw, enc);
                if (fallback == null) fallback = s;
                int bad = 0;
                for (int i = 0; i < s.length(); i++) {
                    if (s.charAt(i) == '\uFFFD') bad++;
                }
                if (bad < 5) return s;
            } catch (Exception ignored) {
            }
        }
        return fallback != null ? fallback : new String(raw, StandardCharsets.UTF_8);
    }
}
