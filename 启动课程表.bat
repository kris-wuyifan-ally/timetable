@echo off
chcp 65001 >nul
title 我的课程表 - 本机助手
cd /d "%~dp0"
where python >nul 2>nul
if errorlevel 1 (
  echo 未找到 Python，请先安装 Python 3 并勾选 Add Python to PATH。
  pause
  exit /b 1
)
python server.py
if errorlevel 1 pause
