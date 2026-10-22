# -*- coding: utf-8 -*-
import json
import os
import sys
import threading
import webbrowser
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

import requests

import jw_scraper

ROOT = os.path.dirname(os.path.abspath(__file__))
PORT = 8765
APP_URL = f"http://127.0.0.1:{PORT}/"


def allowed_origins():
    origins = {"null", APP_URL.rstrip("/"), f"http://localhost:{PORT}"}
    try:
        cfg = jw_scraper.load_config(os.path.join(ROOT, "config.json"))
        for item in cfg.get("allowed_origins", []):
            origins.add(str(item).rstrip("/"))
    except Exception:
        pass
    return origins


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=ROOT, **kwargs)

    def log_message(self, fmt, *args):
        pass

    def _cors_origin(self):
        origin = self.headers.get("Origin", "")
        if origin in allowed_origins():
            return origin
        return None

    def _send_cors(self):
        origin = self._cors_origin()
        if origin:
            self.send_header("Access-Control-Allow-Origin", origin)
            self.send_header("Vary", "Origin")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")

    def _json(self, obj, status=200):
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self._send_cors()
        self.end_headers()
        self.wfile.write(data)

    def do_OPTIONS(self):
        self.send_response(204)
        self._send_cors()
        self.end_headers()

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == "/api/ping":
            self._json({"ok": True, "app": "course-table", "version": 1})
            return
        if path in ("/", "/index.html"):
            self.path = "/index.html"
        super().do_GET()

    def do_POST(self):
        path = self.path.split("?", 1)[0]
        if path != "/api/auto_import":
            self._json({"ok": False, "error": "接口不存在"}, 404)
            return
        try:
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length else b"{}"
            body = json.loads(raw.decode("utf-8") or "{}")
            if not isinstance(body, dict):
                body = {}
            result = jw_scraper.fetch_courses(
                base_url=(body.get("base_url") or "").strip() or None,
                account=(body.get("account") or "").strip() or None,
                password=body.get("password") or None,
                semester=(body.get("semester") or "").strip() or None,
            )
            print(f"[导入成功] 学期 {result['semester']}，课程 {len(result['courses'])} 条", flush=True)
            self._json(result)
        except requests.exceptions.RequestException:
            self._json({"ok": False, "error": "无法连接教务系统，请检查网络或教务系统地址"}, 502)
        except RuntimeError as exc:
            print(f"[导入失败] {exc}", flush=True)
            self._json({"ok": False, "error": str(exc)}, 400)
        except Exception as exc:
            print(f"[导入异常] {exc!r}", flush=True)
            self._json({"ok": False, "error": f"导入失败：{exc}"}, 500)


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    try:
        server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    except OSError:
        print("助手已经在运行，正在打开课表页面…", flush=True)
        webbrowser.open(APP_URL)
        return
    print("=" * 46, flush=True)
    print("  我的课程表 · 本机助手已启动", flush=True)
    print(f"  地址: {APP_URL}", flush=True)
    print("  保持本窗口开启；关闭窗口即停止服务。", flush=True)
    print("=" * 46, flush=True)
    threading.Timer(0.8, lambda: webbrowser.open(APP_URL)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
