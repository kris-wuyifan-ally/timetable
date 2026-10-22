# -*- coding: utf-8 -*-
"""宜春学院教务系统课表抓取器（强智 jsxsd 框架）

用法:
    python jw_scraper.py                      # 抓当前学期，打印摘要
    python jw_scraper.py --semester 2025-2026-2 --out courses.json
"""
import argparse
import base64
import html as html_lib
import json
import os
import re
import sys
from datetime import datetime

import requests

CONFIG_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.json")
USER_AGENT = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")


def load_config(path=CONFIG_PATH):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def strip_tags(text):
    text = re.sub(r"<br\s*/?>", "\n", text, flags=re.I)
    text = re.sub(r"<[^>]+>", "", text)
    return html_lib.unescape(text).strip()


def make_session(referer):
    s = requests.Session()
    s.headers.update({"User-Agent": USER_AGENT, "Referer": referer})
    return s


def login(session, base_url, account, password):
    base = base_url.rstrip("/")
    session.get(base + "/", timeout=20)
    encoded = (base64.b64encode(account.encode("utf-8")).decode()
               + "%%%"
               + base64.b64encode(password.encode("utf-8")).decode())
    r = session.post(base + "/xk/LoginToXk",
                     data={"encoded": encoded, "jzmmid": "1"},
                     timeout=25, allow_redirects=True)
    if "logintoxk" in r.url.lower() or 'id="userAccount"' in r.text:
        raise RuntimeError("登录失败：账号或密码不正确")
    return r


def decode_page(resp):
    raw = resp.content
    for enc in ("utf-8", "gb18030"):
        try:
            text = raw.decode(enc)
            if text.count("\ufffd") < 5:
                return text
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", errors="replace")


def parse_week_list(weeks_text, week_type):
    weeks = set()
    for part in str(weeks_text).split(","):
        part = part.strip()
        if not part:
            continue
        if "-" in part:
            try:
                a, b = part.split("-", 1)
                a, b = int(a), int(b)
                weeks.update(range(min(a, b), max(a, b) + 1))
            except ValueError:
                continue
        else:
            try:
                weeks.add(int(part))
            except ValueError:
                continue
    if week_type in ("单", "单周"):
        weeks = {w for w in weeks if w % 2 == 1}
    elif week_type in ("双", "双周"):
        weeks = {w for w in weeks if w % 2 == 0}
    return sorted(weeks)


def parse_cell_blocks(cell_html):
    blocks = []
    for div in re.findall(r'<div[^>]*class="kbcontent"[^>]*>([\s\S]*?)</div>', cell_html):
        for block in re.split(r"-{3,}", div):
            block = re.sub(r"^(?:\s*<br\s*/?>\s*)+", "", block)
            if block.strip():
                blocks.append(block)
    return blocks


def parse_block(block, day, start, end):
    name_html = re.split(r"<br\s*/?>", block, maxsplit=1)[0]
    name_text = strip_tags(name_html)
    hours = ctype = ""
    m = re.match(r"^(.*?)\s*\[([^\]]+)\]\s*\[([^\]]+)\]", name_text)
    if m:
        name_text, hours, ctype = m.group(1).strip(), m.group(2).strip(), m.group(3).strip()
    if not name_text:
        return None

    teachers = [strip_tags(t) for t in re.findall(r"<font title='老师'>([\s\S]*?)</font>", block)]
    teachers = [t for t in teachers if t]

    class_raw = re.search(r"<font title='班级'>([\s\S]*?)</font>", block)
    class_text = strip_tags(class_raw.group(1)).replace("\n", "") if class_raw else ""
    if not class_text:
        fonts = re.findall(r"<font title='周次\(节次\)'>([\s\S]*?)</font>", block)
        class_text = strip_tags(fonts[0]).replace("\n", "") if fonts else ""

    weeks = ""
    week_type = "全部"
    mw = re.search(r"(\d[\d,\-]*)\s*\((全部|单周|双周|单|双)\)", class_text)
    if mw:
        weeks, week_type = mw.group(1), mw.group(2)

    class_name = re.sub(r"\(\d+\).*$", "", class_text)
    class_name = re.sub(r"(\d[\d,\-]*)\((全部|单周|双周|单|双)\).*$", "", class_name).strip()

    room = ""
    for pat in (r"<font title='上课地点'>([\s\S]*?)</font>",
                r"<font title='教室'>([\s\S]*?)</font>"):
        mr = re.search(pat, block)
        if mr:
            room = strip_tags(mr.group(1)).replace("\n", " ").strip()
            break

    return {
        "name": name_text,
        "teacher": "、".join(teachers),
        "className": class_name,
        "room": room,
        "day": day,
        "start": start,
        "end": end,
        "weeks": weeks,
        "weekType": week_type,
        "hours": hours,
        "courseType": ctype,
        "weeksList": parse_week_list(weeks, week_type),
    }


def parse_timetable(page):
    m = re.search(r"<table[^>]*id=\"kbtable\"[\s\S]*?</table>", page, re.I)
    if not m:
        if "userAccount" in page or "登录" in page and "课表" not in page:
            raise RuntimeError("登录状态失效，请重新登录")
        raise RuntimeError("未找到课表表格，页面结构可能已变化")
    table = m.group(0)
    rows = re.findall(r"<tr[\s\S]*?</tr>", table)

    courses = []
    notes = ""
    for row in rows[1:]:
        th = re.search(r"<th[^>]*>([\s\S]*?)</th>", row)
        label = strip_tags(th.group(1)) if th else ""
        cells = re.findall(r"<td[^>]*>[\s\S]*?</td>", row)
        mp = re.search(r"第(\d+)\.(\d+)节", label)
        if mp:
            start, end = int(mp.group(1)), int(mp.group(2))
        elif "备注" in label:
            if cells:
                notes = strip_tags(cells[0]).replace("\n", " ").strip()
            continue
        else:
            continue
        for day, cell in enumerate(cells[:7], start=1):
            for block in parse_cell_blocks(cell):
                item = parse_block(block, day, start, end)
                if item:
                    courses.append(item)

    courses = merge_courses(courses)
    courses.sort(key=lambda c: (c["day"], c["start"], c["weeksList"][0] if c["weeksList"] else 0, c["name"]))
    return courses, notes


def merge_courses(courses):
    merged = {}
    for c in courses:
        key = (c["name"], c["teacher"], c["room"], c["day"], c["start"], c["end"], c["weekType"])
        if key in merged:
            old = merged[key]
            old["weeksList"] = sorted(set(old["weeksList"]) | set(c["weeksList"]))
            old["weeks"] = compress_weeks(old["weeksList"])
        else:
            merged[key] = dict(c)
    return list(merged.values())


def compress_weeks(weeks):
    if not weeks:
        return ""
    weeks = sorted(set(weeks))
    parts, i = [], 0
    while i < len(weeks):
        j = i
        while j + 1 < len(weeks) and weeks[j + 1] == weeks[j] + 1:
            j += 1
        parts.append(str(weeks[i]) if i == j else f"{weeks[i]}-{weeks[j]}")
        i = j + 1
    return ",".join(parts)


def parse_semesters(page):
    m = re.search(r'<select[^>]*id="xnxq01id"[\s\S]*?</select>', page, re.I)
    if not m:
        return [], ""
    options = re.findall(r'<option value="([^"]+)"([^>]*)>([^<]*)</option>', m.group(0))
    semesters = [value for value, _, _ in options if value.strip()]
    current = ""
    for value, attrs, _ in options:
        if "selected" in attrs:
            current = value
            break
    return semesters, current


def fetch_courses(base_url=None, account=None, password=None, semester=None, config_path=CONFIG_PATH):
    cfg = load_config(config_path)
    base = (base_url or cfg["base_url"]).strip().rstrip("/")
    account = account or cfg["account"]
    password = password or cfg["password"]
    session = make_session(cfg.get("referer") or base + "/")
    login(session, base, account, password)

    r = session.get(base + "/xskb/xskb_list.do", timeout=30)
    page = decode_page(r)
    if "userAccount" in page or "kbtable" not in page.lower():
        raise RuntimeError("登录失败或课表页不可用，请检查账号密码")

    semesters, current = parse_semesters(page)
    if semester and semester in semesters and semester != current:
        r = session.post(base + "/xskb/xskb_list.do",
                         data={"xnxq01id": semester, "zc": ""}, timeout=30)
        page = decode_page(r)
        current = semester

    courses, notes = parse_timetable(page)
    return {
        "ok": True,
        "school": "ycust",
        "semester": current,
        "semesters": semesters,
        "fetchedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "notes": notes,
        "courses": courses,
    }


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--semester", default=None)
    ap.add_argument("--out", default=None)
    ap.add_argument("--pretty", action="store_true")
    args = ap.parse_args()

    result = fetch_courses(semester=args.semester)
    text = json.dumps(result, ensure_ascii=False, indent=2 if args.pretty else None)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(text)
        print(f"已保存: {args.out}")
    print(f"学期: {result['semester']}  课程条目: {len(result['courses'])}")
    if result["notes"]:
        print("备注:", result["notes"][:200])
    for c in result["courses"]:
        print(f"  周{c['day']} 第{c['start']}-{c['end']}节 [{c['weeks']}({c['weekType']})] "
              f"{c['name']} {c['teacher']} {c['room']}")


if __name__ == "__main__":
    main()
