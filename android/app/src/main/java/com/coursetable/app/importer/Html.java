package com.coursetable.app.importer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Html {
    private Html() {}

    public static String stripTags(String html) {
        if (html == null) return "";
        String s = html.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("<[^>]+>", "");
        return unescape(s).trim();
    }

    public static String unescape(String s) {
        if (s == null) return "";
        s = s.replace("&nbsp;", " ");
        s = s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
        Matcher m = Pattern.compile("&#(x?[0-9a-fA-F]+);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String v = m.group(1);
            try {
                int code = v.startsWith("x") || v.startsWith("X")
                        ? Integer.parseInt(v.substring(1), 16)
                        : Integer.parseInt(v);
                m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(code))));
            } catch (Exception e) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static String attrValue(String tag, String attr) {
        if (tag == null) return "";
        Matcher m = Pattern.compile(attr + "\\s*=\\s*([\"'])(.*?)\\1",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(tag);
        return m.find() ? m.group(2) : "";
    }

    public static String firstGroup(String regex, String text, int flags) {
        if (text == null) return "";
        Matcher m = Pattern.compile(regex, flags).matcher(text);
        return m.find() ? m.group(1) : "";
    }
}
