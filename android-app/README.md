# 丁丁快传 Android 1.0

这是丁丁快传网页版的 Android 轻量客户端，默认连接到 `https://dd.hxzz.uno`。

## 功能

- WebView 方式保留网页端的发送文件、接收文件、口令和二维码流程
- 支持 Android 文件选择器上传多文件
- 支持系统分享菜单。其他应用分享单个文件到“丁丁快传”后，应用会直接上传并显示链接和取件码
- 应用图标复用 Worker 内置的橙色 Cloudflare W 图标

## 构建

```powershell
$env:ANDROID_SDK_ROOT = "D:\\AI\\ChatGPT\\android-sdk"
.\\gradlew.bat assembleDebug
```

APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`。
