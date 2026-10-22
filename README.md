# 课程表

一个单文件课表网页，以及基于 WebView 的 Android 客户端。支持手动添加课程、从教务系统导入课表、按周查看、横屏/窄屏布局和 Android 端自动更新。

## 目录结构

```text
.
├─ index.html                    # 网页主程序，单文件
├─ server.py                     # 本地导入助手，同时提供网页访问
├─ jw_scraper.py                 # 教务系统抓取逻辑
├─ 启动课程表.bat                 # Windows 启动脚本
├─ config.example.json           # 配置模板
├─ requirements.txt
└─ android/
   ├─ app/src/main/              # Android 源码、资源和 capture.js
   ├─ app/build.gradle
   ├─ build.gradle
   ├─ settings.gradle
   ├─ gradle.properties
   └─ keystore.properties.example
```

## 网页版

1. 安装 Python 3 和依赖：

   ```powershell
   pip install -r requirements.txt
   ```

2. 复制配置模板：

   ```powershell
   Copy-Item config.example.json config.json
   ```

3. 填写 `config.json` 中的学校地址和账号信息。

4. 双击 `启动课程表.bat`，或运行：

   ```powershell
   python server.py
   ```

5. 打开 `http://127.0.0.1:8765/`。

`config.json` 只用于本机，已被 `.gitignore` 排除，请勿提交。

## Android 版

要求：

- JDK 17
- Android SDK 34
- Gradle 8.9（仓库已包含 Gradle Wrapper）

构建 debug：

```powershell
cd android
.\gradlew.bat assembleDebug
```

构建 release：

```powershell
cd android
.\gradlew.bat assembleRelease
```

release 签名需要：

```powershell
Copy-Item keystore.properties.example keystore.properties
```

然后填写自己的 keystore 路径和密码。`keystore.properties`、`*.keystore`、`*.jks` 均不应提交到仓库。

APK 输出目录：

```text
android/app/build/outputs/apk/
```

## 安全说明

- 不要提交 `config.json`。
- 不要提交签名密钥和签名密码。
- 不要提交包含真实课表、账号或调试页面的测试文件。
- 发布 APK 时建议上传到 GitHub Releases，而不是提交进仓库。
