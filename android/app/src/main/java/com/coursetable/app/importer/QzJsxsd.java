package com.coursetable.app.importer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class QzJsxsd {
    private QzJsxsd() {}

    public static JSONObject importCourses(Http http, String enteredBase, String account,
                                           String password, String semester) throws Exception {
        String base = normalizeBase(enteredBase);
        if (base.isEmpty()) throw new Exception("教务系统地址无效");
        http.get(base + "/");
        String encoded = Base64.getEncoder().encodeToString(account.getBytes(StandardCharsets.UTF_8))
                + "%%%"
                + Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8));
        Map<String, String> form = new LinkedHashMap<>();
        form.put("encoded", encoded);
        form.put("jzmmid", "1");
        Http.Response r = http.post(base + "/xk/LoginToXk", form, base + "/");
        if (r.url.toLowerCase(Locale.ROOT).contains("logintoxk")
                || r.body.contains("id=\"userAccount\"")
                || r.body.contains("id=userAccount")) {
            throw new Exception("登录失败：账号或密码不正确。如系统要求验证码，请使用「在应用内登录并抓取」");
        }
        r = http.get(base + "/xskb/xskb_list.do", base + "/");
        String page = r.body;
        if (!page.toLowerCase(Locale.ROOT).contains("kbtable")) {
            if (page.contains("userAccount") || page.contains("LoginToXk") || page.contains("请登录")) {
                throw new Exception("登录状态失效，请检查账号密码，或使用「在应用内登录并抓取」");
            }
            throw new Exception("未找到课表页面，请确认教务系统网址是否正确");
        }
        List<String> semesters = parseSemesterOptions(page);
        String current = parseCurrentSemester(page);
        if (semester != null && !semester.isEmpty() && !semester.equals(current)
                && (semesters.isEmpty() || semesters.contains(semester))) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("xnxq01id", semester);
            m.put("zc", "");
            Http.Response changed = http.post(base + "/xskb/xskb_list.do", m,
                    base + "/xskb/xskb_list.do");
            if (changed.body.toLowerCase(Locale.ROOT).contains("kbtable")) {
                page = changed.body;
                current = semester;
            }
        }
        String[] notes = new String[]{""};
        JSONArray raw = parseTimetable(page, notes);
        JSONArray courses = Courses.mergeAndSort(raw);
        if (courses.length() == 0) {
            throw new Exception("课表页面没有课程数据，请确认学期，或使用「在应用内登录并抓取」");
        }
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("via", "native");
        out.put("system", "qz");
        out.put("semester", current == null ? "" : current);
        out.put("semesters", new JSONArray(semesters));
        out.put("fetchedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date()));
        out.put("notes", notes[0] == null ? "" : notes[0]);
        out.put("courses", courses);
        return out;
    }

    public static String normalizeBase(String raw) {
        if (raw == null) return "";
        String u = raw.trim();
        if (u.isEmpty()) return "";
        if (!u.matches("(?i)^https?://.*")) u = "http://" + u;
        u = u.replaceAll("/+$", "");
        String lower = u.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("/framework/");
        if (idx >= 0) u = u.substring(0, idx);
        lower = u.toLowerCase(Locale.ROOT);
        idx = lower.indexOf("/xk/");
        if (idx >= 0) u = u.substring(0, idx);
        lower = u.toLowerCase(Locale.ROOT);
        idx = lower.indexOf("/xskb/");
        if (idx >= 0) u = u.substring(0, idx);
        lower = u.toLowerCase(Locale.ROOT);
        idx = lower.indexOf("/xskb_list");
        if (idx >= 0) u = u.substring(0, idx);
        u = u.replaceAll("/(index|xsMain|login)\\.jsp$", "");
        return u.replaceAll("/+$", "");
    }

    static List<String> parseSemesterOptions(String page) {
        List<String> out = new ArrayList<>();
        Matcher sel = Pattern.compile("<select[^>]*id=[\"']xnxq01id[\"'][\\s\\S]*?</select>",
                Pattern.CASE_INSENSITIVE).matcher(page);
        if (!sel.find()) return out;
        Matcher m = Pattern.compile("<option([^>]*)>([\\s\\S]*?)</option>",
                Pattern.CASE_INSENSITIVE).matcher(sel.group());
        while (m.find()) {
            String value = Html.attrValue(m.group(1), "value");
            if (value.isEmpty()) value = Html.stripTags(m.group(2));
            value = value.trim();
            if (!value.isEmpty() && !out.contains(value)) out.add(value);
        }
        return out;
    }

    static String parseCurrentSemester(String page) {
        Matcher sel = Pattern.compile("<select[^>]*id=[\"']xnxq01id[\"'][\\s\\S]*?</select>",
                Pattern.CASE_INSENSITIVE).matcher(page);
        if (!sel.find()) return "";
        String first = "";
        Matcher m = Pattern.compile("<option([^>]*)>([\\s\\S]*?)</option>",
                Pattern.CASE_INSENSITIVE).matcher(sel.group());
        while (m.find()) {
            String attrs = m.group(1);
            String value = Html.attrValue(attrs, "value");
            if (value.isEmpty()) value = Html.stripTags(m.group(2));
            value = value.trim();
            if (first.isEmpty()) first = value;
            if (Pattern.compile("(?i)(^|\\s)selected(\\s|=|$)").matcher(attrs).find()) {
                return value;
            }
        }
        return first;
    }

    static JSONArray parseTimetable(String page, String[] notesOut) throws Exception {
        Matcher tableM = Pattern.compile("<table[^>]*id=[\"']kbtable[\"'][\\s\\S]*?</table>",
                Pattern.CASE_INSENSITIVE).matcher(page);
        if (!tableM.find()) throw new Exception("未找到课表表格，页面结构可能已变化");
        Matcher rowM = Pattern.compile("<tr[\\s\\S]*?</tr>", Pattern.CASE_INSENSITIVE)
                .matcher(tableM.group());
        JSONArray raw = new JSONArray();
        boolean first = true;
        while (rowM.find()) {
            String row = rowM.group();
            if (first) {
                first = false;
                continue;
            }
            String thHtml = "";
            Matcher th = Pattern.compile("<th[^>]*>([\\s\\S]*?)</th>", Pattern.CASE_INSENSITIVE)
                    .matcher(row);
            if (th.find()) thHtml = th.group(1);
            String label = Html.stripTags(thHtml);
            List<String> cells = new ArrayList<>();
            Matcher td = Pattern.compile("<td[^>]*>[\\s\\S]*?</td>", Pattern.CASE_INSENSITIVE)
                    .matcher(row);
            while (td.find()) cells.add(td.group());
            if (label.contains("备注")) {
                if (!cells.isEmpty() && notesOut != null) {
                    notesOut[0] = Html.stripTags(cells.get(0)).replace("\n", " ").trim();
                }
                continue;
            }
            Matcher pm = Pattern.compile("第(\\d+)\\.(\\d+)节").matcher(label);
            if (!pm.find()) continue;
            int start = Integer.parseInt(pm.group(1));
            int end = Integer.parseInt(pm.group(2));
            for (int i = 0; i < Math.min(7, cells.size()); i++) {
                for (String block : parseCellBlocks(cells.get(i))) {
                    JSONObject c = parseBlock(block, i + 1, start, end);
                    if (c != null) raw.put(c);
                }
            }
        }
        return raw;
    }

    static List<String> parseCellBlocks(String cell) {
        List<String> blocks = new ArrayList<>();
        Matcher m = Pattern.compile(
                "<div[^>]*class=[\"'][^\"']*\\bkbcontent\\b[^\"']*[\"'][^>]*>([\\s\\S]*?)</div>",
                Pattern.CASE_INSENSITIVE).matcher(cell);
        while (m.find()) {
            for (String part : m.group(1).split("-{3,}")) {
                part = part.replaceFirst("^(?:\\s*<br\\s*/?>\\s*)+", "");
                if (!Html.stripTags(part).isEmpty()) blocks.add(part);
            }
        }
        return blocks;
    }

    static JSONObject parseBlock(String block, int day, int start, int end) throws Exception {
        String nameHtml = Pattern.compile("<br\\s*/?>", Pattern.CASE_INSENSITIVE)
                .split(block, 2)[0];
        String nameText = Html.stripTags(nameHtml);
        String hours = "";
        String ctype = "";
        Matcher nm = Pattern.compile("^(.*?)\\s*\\[([^\\]]+)\\]\\s*\\[([^\\]]+)\\]").matcher(nameText);
        if (nm.find()) {
            nameText = nm.group(1).trim();
            hours = nm.group(2).trim();
            ctype = nm.group(3).trim();
        }
        if (nameText.isEmpty()) return null;
        List<String> teachers = new ArrayList<>();
        Matcher tm = Pattern.compile("<font[^>]*title=[\"']老师[\"'][^>]*>([\\s\\S]*?)</font>",
                Pattern.CASE_INSENSITIVE).matcher(block);
        while (tm.find()) {
            String t = Html.stripTags(tm.group(1));
            if (!t.isEmpty()) teachers.add(t);
        }
        String classText = "";
        Matcher cm = Pattern.compile("<font[^>]*title=[\"']班级[\"'][^>]*>([\\s\\S]*?)</font>",
                Pattern.CASE_INSENSITIVE).matcher(block);
        if (cm.find()) classText = Html.stripTags(cm.group(1)).replace("\n", "");
        if (classText.isEmpty()) {
            Matcher wm = Pattern.compile("<font[^>]*title=[\"']周次\\(节次\\)[\"'][^>]*>([\\s\\S]*?)</font>",
                    Pattern.CASE_INSENSITIVE).matcher(block);
            if (wm.find()) classText = Html.stripTags(wm.group(1)).replace("\n", "");
        }
        String weeks = "";
        String weekType = "全部";
        Matcher wm = Pattern.compile("(\\d[\\d,\\-]*)\\s*\\((全部|单周|双周|单|双)\\)").matcher(classText);
        if (wm.find()) {
            weeks = wm.group(1);
            weekType = wm.group(2);
        }
        String className = classText.replaceAll("\\(\\d+\\).*$", "");
        className = className.replaceAll("(\\d[\\d,\\-]*)\\((全部|单周|双周|单|双)\\).*$", "").trim();
        String room = "";
        Matcher rm = Pattern.compile(
                "<font[^>]*title=[\"'](?:上课地点|教室)[\"'][^>]*>([\\s\\S]*?)</font>",
                Pattern.CASE_INSENSITIVE).matcher(block);
        if (rm.find()) room = Html.stripTags(rm.group(1)).replace("\n", " ").trim();
        return Courses.make(nameText, String.join("、", teachers), room, className,
                day, start, end, weeks, weekType, hours, ctype);
    }
}
