package com.coursetable.app.importer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;

public final class ZfSoft {
    private ZfSoft() {}

    public static JSONObject importCourses(Http http, String enteredBase, String account,
                                           String password, String semester) throws Exception {
        String base = normalizeBase(enteredBase);
        if (base.isEmpty()) throw new Exception("无法确定正方教务系统地址");
        String loginUrl = base + "/xtgl/login_slogin.html?time=" + System.currentTimeMillis();
        Http.Response page = http.get(loginUrl);
        if (page.body.contains("统一身份认证") || page.url.toLowerCase(Locale.ROOT).contains("cas")) {
            throw new Exception("学校使用统一身份认证，请使用「在应用内登录并抓取」");
        }
        if (Pattern.compile("name=[\"']j_captcha[\"']", Pattern.CASE_INSENSITIVE).matcher(page.body).find()
                || Pattern.compile("id=[\"']captcha[\"']", Pattern.CASE_INSENSITIVE).matcher(page.body).find()) {
            throw new Exception("该教务系统登录需要验证码，请使用「在应用内登录并抓取」");
        }
        Map<String, String> hidden = hiddenInputs(page.body);
        String csrf = "";
        for (Map.Entry<String, String> e : hidden.entrySet()) {
            if (e.getKey().toLowerCase(Locale.ROOT).contains("csrf")) {
                csrf = e.getValue();
                break;
            }
        }
        String mm;
        try {
            Http.Response keyResp = http.get(
                    base + "/xtgl/login_getPublicKey.html?time=" + System.currentTimeMillis(), loginUrl);
            JSONObject key = parseJson(keyResp.body);
            mm = rsaEncrypt(password, key.optString("modulus"), key.optString("exponent"));
        } catch (Exception e) {
            mm = password;
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("yhm", account);
        form.put("mm", mm);
        form.put("language", "zh_CN");
        if (!csrf.isEmpty()) form.put("csrftoken", csrf);
        Http.Response login = http.post(
                base + "/xtgl/login_slogin.html?time=" + System.currentTimeMillis(), form, loginUrl);
        String loginBody = login.body;
        if (login.url.toLowerCase(Locale.ROOT).contains("login_slogin")
                || loginBody.contains("用户名或密码错误")
                || loginBody.contains("密码错误")
                || loginBody.contains("验证码错误")) {
            throw new Exception("登录失败：账号或密码不正确。如系统要求验证码，请使用「在应用内登录并抓取」");
        }
        if (loginBody.contains("统一身份认证")) {
            throw new Exception("学校使用统一身份认证，请使用「在应用内登录并抓取」");
        }

        String indexUrl = base + "/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default";
        Http.Response idx = http.get(indexUrl, base + "/xtgl/index_initMenu.html");
        if (idx.url.toLowerCase(Locale.ROOT).contains("login_slogin")) {
            throw new Exception("登录未成功，请稍后重试或使用「在应用内登录并抓取」");
        }
        String xnmSel = selectedOption(idx.body, "xnm");
        String xqmSel = selectedOption(idx.body, "xqm");
        List<String> xnmOptions = optionValues(idx.body, "xnm");
        int[] term = parseSemester(semester);
        String xnm = term != null ? String.valueOf(term[0]) : xnmSel;
        String xqm = term != null ? String.valueOf(term[1]) : xqmSel;
        if (xnm == null || xnm.isEmpty()) xnm = guessYear();
        if (xqm == null || xqm.isEmpty()) xqm = guessTerm();

        JSONObject data = fetchKb(http, base, xnm, xqm, indexUrl);
        JSONArray kb = data.optJSONArray("kbList");
        if ((kb == null || kb.length() == 0) && (semester == null || semester.isEmpty())) {
            String altXqm = "3".equals(xqm) ? "12" : "3";
            JSONObject alt = fetchKb(http, base, xnm, altXqm, indexUrl);
            JSONArray altKb = alt.optJSONArray("kbList");
            if (altKb != null && altKb.length() > 0) {
                kb = altKb;
                xqm = altXqm;
            }
        }
        if (kb == null || kb.length() == 0) {
            throw new Exception("未获取到课程数据，请确认账号权限，或使用「在应用内登录并抓取」");
        }
        JSONArray courses = Courses.mergeAndSort(convert(kb));
        if (courses.length() == 0) throw new Exception("课表数据为空，请确认学期");
        String semesterText = xnm + "-" + (Integer.parseInt(xnm) + 1) + "-" + termNo(xqm);
        List<String> semesters = buildSemesters(xnmOptions, xnm);
        if (!semesters.contains(semesterText)) semesters.add(semesterText);
        java.util.Collections.sort(semesters);
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("via", "native");
        out.put("system", "zf");
        out.put("semester", semesterText);
        out.put("semesters", new JSONArray(semesters));
        out.put("fetchedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date()));
        out.put("notes", "");
        out.put("courses", courses);
        return out;
    }

    static String normalizeBase(String raw) {
        String u = QzJsxsd.normalizeBase(raw);
        if (u.isEmpty()) return "";
        String lower = u.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("/jwglxt");
        if (idx >= 0) return u.substring(0, idx + 7);
        return u;
    }

    static String selectHtml(String html, String id) {
        Matcher m = Pattern.compile("<select[^>]*id=[\"']" + Pattern.quote(id)
                + "[\"'][\\s\\S]*?</select>", Pattern.CASE_INSENSITIVE).matcher(html);
        return m.find() ? m.group() : "";
    }

    static String selectedOption(String html, String id) {
        String sel = selectHtml(html, id);
        if (sel.isEmpty()) return "";
        String first = "";
        Matcher m = Pattern.compile("<option([^>]*)>([\\s\\S]*?)</option>",
                Pattern.CASE_INSENSITIVE).matcher(sel);
        while (m.find()) {
            String attrs = m.group(1);
            String value = Html.attrValue(attrs, "value");
            if (value.isEmpty()) value = Html.stripTags(m.group(2));
            value = value.trim();
            if (first.isEmpty()) first = value;
            if (Pattern.compile("(?i)(^|\\s)selected(\\s|=|$)").matcher(attrs).find()) return value;
        }
        return first;
    }

    static List<String> optionValues(String html, String id) {
        List<String> out = new ArrayList<>();
        String sel = selectHtml(html, id);
        if (sel.isEmpty()) return out;
        Matcher m = Pattern.compile("<option([^>]*)>([\\s\\S]*?)</option>",
                Pattern.CASE_INSENSITIVE).matcher(sel);
        while (m.find()) {
            String value = Html.attrValue(m.group(1), "value");
            if (value.isEmpty()) value = Html.stripTags(m.group(2));
            value = value.trim();
            if (!value.isEmpty() && !out.contains(value)) out.add(value);
        }
        return out;
    }

    static Map<String, String> hiddenInputs(String html) {
        Map<String, String> map = new HashMap<>();
        Matcher m = Pattern.compile("<input[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
        while (m.find()) {
            String tag = m.group();
            if (!"hidden".equalsIgnoreCase(Html.attrValue(tag, "type"))) continue;
            String name = Html.attrValue(tag, "name");
            if (!name.isEmpty()) map.put(name, Html.attrValue(tag, "value"));
        }
        return map;
    }

    static JSONObject parseJson(String text) throws JSONException {
        int a = text.indexOf('{');
        int b = text.lastIndexOf('}');
        if (a >= 0 && b > a) text = text.substring(a, b + 1);
        return new JSONObject(text);
    }

    static String rsaEncrypt(String password, String modulusText, String exponentText) throws Exception {
        if (modulusText == null || modulusText.isEmpty() || exponentText == null || exponentText.isEmpty()) {
            throw new Exception("公钥为空");
        }
        BigInteger modulus = parseBigInt(modulusText);
        BigInteger exponent = parseBigInt(exponentText);
        RSAPublicKeySpec spec = new RSAPublicKeySpec(modulus, exponent);
        PublicKey key = KeyFactory.getInstance("RSA").generatePublic(spec);
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encrypted);
    }

    static BigInteger parseBigInt(String s) {
        s = s.trim();
        if (s.matches("(?i)^[0-9a-f]+$")) {
            if (s.length() % 2 == 1) s = "0" + s;
            return new BigInteger(s, 16);
        }
        return new BigInteger(1, Base64.getMimeDecoder().decode(s));
    }

    static JSONObject fetchKb(Http http, String base, String xnm, String xqm, String referer)
            throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("xnm", xnm);
        form.put("xqm", xqm);
        form.put("kzlx", "ck");
        form.put("xsdm", "");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Requested-With", "XMLHttpRequest");
        headers.put("Accept", "application/json, text/javascript, */*; q=0.01");
        Http.Response r = http.post(base + "/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151",
                form, referer, headers);
        String text = r.body.trim();
        int a = text.indexOf('{');
        int b = text.lastIndexOf('}');
        if (a < 0 || b <= a) {
            throw new Exception("教务系统未返回课表数据，请稍后重试，或使用「在应用内登录并抓取」");
        }
        return new JSONObject(text.substring(a, b + 1));
    }

    static JSONArray convert(JSONArray list) throws JSONException {
        JSONArray out = new JSONArray();
        for (int i = 0; i < list.length(); i++) {
            JSONObject k = list.optJSONObject(i);
            if (k == null) continue;
            String name = Html.stripTags(k.optString("kcmc", "")).trim();
            if (name.isEmpty()) continue;
            int day = toInt(k.optString("xqj", ""), 0);
            if (day < 1 || day > 7) day = dayFromCn(k.optString("xqj", ""));
            if (day < 1) continue;
            int[] se = parsePeriods(k.optString("jcs", ""));
            if (se == null) continue;
            String zcd = k.optString("zcd", "");
            String weekType = weekTypeOf(zcd);
            String weeks = Weeks.compress(Weeks.parse(extractWeeks(zcd), weekType));
            String teacher = k.optString("xm", "").replace(",", "、").replace("，", "、")
                    .replace(" ", "、").trim();
            String room = k.optString("cdmc", "").trim();
            if (room.isEmpty()) room = k.optString("jxdd", "").trim();
            String className = k.optString("jxbmc", "").trim();
            out.put(Courses.make(name, teacher, room, className, day, se[0], se[1],
                    weeks, weekType, "", ""));
        }
        return out;
    }

    static int toInt(String s, int def) {
        Matcher m = Pattern.compile("\\d+").matcher(s == null ? "" : s);
        return m.find() ? Integer.parseInt(m.group()) : def;
    }

    static int dayFromCn(String s) {
        if (s == null) return 0;
        String[] days = {"一", "二", "三", "四", "五", "六", "日", "天"};
        for (int i = 0; i < days.length; i++) {
            if (s.contains(days[i])) return i == 7 ? 7 : i + 1;
        }
        return 0;
    }

    static int[] parsePeriods(String s) {
        if (s == null) return null;
        Matcher range = Pattern.compile("(\\d+)\\s*[-~—]\\s*(\\d+)").matcher(s);
        if (range.find()) {
            int a = Integer.parseInt(range.group(1));
            int b = Integer.parseInt(range.group(2));
            if (a > b) {
                int t = a;
                a = b;
                b = t;
            }
            if (a < 1 || b > 20) return null;
            return new int[]{a, b};
        }
        Matcher one = Pattern.compile("\\d+").matcher(s);
        int min = Integer.MAX_VALUE;
        int max = 0;
        while (one.find()) {
            int v = Integer.parseInt(one.group());
            if (v >= 1 && v <= 20) {
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        return max == 0 ? null : new int[]{min, max};
    }

    static String weekTypeOf(String zcd) {
        if (zcd == null) return "全部";
        boolean single = zcd.contains("单");
        boolean dual = zcd.contains("双");
        if (single && !dual) return "单周";
        if (dual && !single) return "双周";
        return "全部";
    }

    static String extractWeeks(String zcd) {
        if (zcd == null) return "";
        return zcd.replaceAll("[^0-9,\\-~—]", "")
                .replace('~', '-')
                .replace('—', '-');
    }

    static int[] parseSemester(String semester) {
        if (semester == null) return null;
        Matcher m = Pattern.compile("^(\\d{4})-(\\d{4})-([123])$").matcher(semester.trim());
        if (!m.find()) return null;
        int year = Integer.parseInt(m.group(1));
        int term = Integer.parseInt(m.group(3));
        int xqm = term == 1 ? 3 : term == 2 ? 12 : 16;
        return new int[]{year, xqm};
    }

    static String termNo(String xqm) {
        if ("12".equals(xqm)) return "2";
        if ("16".equals(xqm)) return "3";
        return "1";
    }

    static String guessYear() {
        Calendar c = Calendar.getInstance();
        int year = c.get(Calendar.YEAR);
        int month = c.get(Calendar.MONTH) + 1;
        return String.valueOf(month >= 8 ? year : year - 1);
    }

    static String guessTerm() {
        int month = Calendar.getInstance().get(Calendar.MONTH) + 1;
        return (month >= 8 || month == 1) ? "3" : "12";
    }

    static List<String> buildSemesters(List<String> years, String fallbackYear) {
        Set<String> set = new TreeSet<>();
        List<String> source = new ArrayList<>(years);
        if (source.isEmpty()) source.add(fallbackYear);
        for (String y : source) {
            try {
                int n = Integer.parseInt(y.trim());
                set.add(n + "-" + (n + 1) + "-1");
                set.add(n + "-" + (n + 1) + "-2");
            } catch (Exception ignored) {
            }
        }
        try {
            int n = Integer.parseInt(fallbackYear);
            set.add(n + "-" + (n + 1) + "-1");
            set.add(n + "-" + (n + 1) + "-2");
        } catch (Exception ignored) {
        }
        return new ArrayList<>(set);
    }
}
