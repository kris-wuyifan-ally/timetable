package com.coursetable.app.importer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public final class Courses {
    private Courses() {}

    public static JSONObject make(String name, String teacher, String room, String className,
                                  int day, int start, int end, String weeks, String weekType,
                                  String hours, String courseType) throws JSONException {
        String wt = weekType == null || weekType.isEmpty() ? "全部" : weekType;
        JSONObject c = new JSONObject();
        c.put("name", name == null ? "" : name);
        c.put("teacher", teacher == null ? "" : teacher);
        c.put("room", room == null ? "" : room);
        c.put("className", className == null ? "" : className);
        c.put("day", day);
        c.put("start", start);
        c.put("end", end);
        c.put("weeks", weeks == null ? "" : weeks);
        c.put("weekType", wt);
        c.put("hours", hours == null ? "" : hours);
        c.put("courseType", courseType == null ? "" : courseType);
        c.put("weeksList", new JSONArray(Weeks.parse(weeks, wt)));
        return c;
    }

    public static JSONArray mergeAndSort(JSONArray input) throws JSONException {
        LinkedHashMap<String, JSONObject> map = new LinkedHashMap<>();
        for (int i = 0; i < input.length(); i++) {
            JSONObject c = input.optJSONObject(i);
            if (c == null) continue;
            String name = c.optString("name", "").trim();
            if (name.isEmpty()) continue;
            String key = name + "|" + c.optString("teacher") + "|" + c.optString("room") + "|"
                    + c.optInt("day") + "|" + c.optInt("start") + "|" + c.optInt("end") + "|"
                    + c.optString("weekType", "全部");
            JSONObject old = map.get(key);
            if (old == null) {
                map.put(key, c);
            } else {
                List<Integer> all = new ArrayList<>(Weeks.parse(old.optString("weeks"), old.optString("weekType")));
                all.addAll(Weeks.parse(c.optString("weeks"), c.optString("weekType")));
                String compressed = Weeks.compress(all);
                old.put("weeks", compressed);
                old.put("weeksList", new JSONArray(Weeks.parse(compressed, old.optString("weekType"))));
                if (old.optString("className").isEmpty()) old.put("className", c.optString("className"));
                if (old.optString("hours").isEmpty()) old.put("hours", c.optString("hours"));
                if (old.optString("courseType").isEmpty()) old.put("courseType", c.optString("courseType"));
            }
        }
        List<JSONObject> list = new ArrayList<>(map.values());
        list.sort((a, b) -> {
            int r = Integer.compare(a.optInt("day"), b.optInt("day"));
            if (r != 0) return r;
            r = Integer.compare(a.optInt("start"), b.optInt("start"));
            if (r != 0) return r;
            int wa = firstWeek(a);
            int wb = firstWeek(b);
            if (wa != wb) return Integer.compare(wa, wb);
            return a.optString("name").compareTo(b.optString("name"));
        });
        JSONArray out = new JSONArray();
        for (JSONObject c : list) out.put(c);
        return out;
    }

    private static int firstWeek(JSONObject c) {
        JSONArray arr = c.optJSONArray("weeksList");
        if (arr != null && arr.length() > 0) return arr.optInt(0, 0);
        return 0;
    }
}
