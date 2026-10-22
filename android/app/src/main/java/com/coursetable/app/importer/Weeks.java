package com.coursetable.app.importer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class Weeks {
    private Weeks() {}

    public static List<Integer> parse(String text, String type) {
        Set<Integer> set = new TreeSet<>();
        if (text != null) {
            for (String part : text.split(",")) {
                part = part.trim();
                if (part.isEmpty()) continue;
                if (part.contains("-")) {
                    String[] ab = part.split("-", 2);
                    try {
                        int a = Integer.parseInt(ab[0].trim());
                        int b = Integer.parseInt(ab[1].trim());
                        if (a > b) {
                            int t = a;
                            a = b;
                            b = t;
                        }
                        for (int i = a; i <= b && i <= 60; i++) set.add(i);
                    } catch (NumberFormatException ignored) {
                    }
                } else {
                    try {
                        int v = Integer.parseInt(part);
                        if (v > 0) set.add(v);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        List<Integer> out = new ArrayList<>();
        for (int w : set) {
            if ("单".equals(type) || "单周".equals(type)) {
                if (w % 2 == 0) continue;
            }
            if ("双".equals(type) || "双周".equals(type)) {
                if (w % 2 == 1) continue;
            }
            out.add(w);
        }
        return out;
    }

    public static String compress(List<Integer> weeks) {
        if (weeks == null || weeks.isEmpty()) return "";
        List<Integer> sorted = new ArrayList<>(new TreeSet<>(weeks));
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < sorted.size()) {
            int j = i;
            while (j + 1 < sorted.size() && sorted.get(j + 1) == sorted.get(j) + 1) j++;
            if (i == j) sb.append(sorted.get(i));
            else sb.append(sorted.get(i)).append('-').append(sorted.get(j));
            if (j + 1 < sorted.size()) sb.append(',');
            i = j + 1;
        }
        return sb.toString();
    }
}
