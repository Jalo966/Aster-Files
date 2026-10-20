# Aster Files

> 原生 Android 文件管理器 · 局域网协议 · AES 隐私保险箱 · 媒体工具链

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-7F52FF?logo=kotlin)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/Compose-Material%203-4285F4?logo=jetpackcompose)](https://developer.android.com/jetpack/compose)
[![Android](https://img.shields.io/badge/Android-26%2B-3DDC84?logo=android)](https://developer.android.com/)
[![No FFmpeg](https://img.shields.io/badge/Media-Android%20Native-orange)](https://developer.android.com/media)

Aster Files 是面向本地与局域网场景的 Android 文件管理器：FileGuard 安全沙箱、FTP/WebDAV/SMB、局域网 Web 传文件、AES-GCM 隐私保险箱、代码高亮、媒体转换/清理与 APK 重签名。

## 核心功能

### 安全文件管理

- FileGuard canonical + lstat + TOCTOU 二次校验
- 软删除回收站、恢复与保留期
- 审计日志、CSV 导出、完整性扫描

### 网络存储

- FTP / WebDAV / SMB 2/3
- 网络多标签、路径标签、排序、多选
- 网络图片缩略图、WebDAV 视频流、FTP DataSource
- 密码 Android Keystore AES-GCM 加密
- 3 路并发传输、总速度、暂停/继续/重试/通知

### 隐私保险箱

- 4 位 PIN + BiometricPrompt
- 文件内容 AES-256-GCM 加密
- 退出/后台 1 分钟自动上锁
- 解密预览临时缓存自动清理

### 媒体工具

- JPG/PNG/WebP 批量转换
- AAC → M4A、MP3 → MP3 提取
- 空文件夹/空文件/缓存残留/相似图片清理
- 相似图片：文件名 + 5% 大小 + 完全一致分辨率

### 体验

- 文件页/分类页滑动多选
- 全屏图片画廊左右滑动
- 视频画中画（PiP 精简控制栏）
- 双击标题回顶、下拉刷新
- 文件夹 2x2 图片拼图

## 截图

> 截图将在 GitHub Releases / 项目主页补充；源码包不包含测试截图。

## 技术栈

- Kotlin + Jetpack Compose + Material 3
- AndroidX Media3 / Coil
- OkHttp / Apache Commons Net / SMBJ / zip4j / NanoHTTPD / ZXing
- apksig / AndroidX Biometric
- 原生 MediaExtractor / MediaMuxer / BitmapFactory
- FileGuard + SQLite 审计

## 快速编译

```powershell
$env:JAVA_HOME="C:\path\to\jdk-17"
$env:ANDROID_HOME="C:\path\to\android-sdk"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

APK：app/build/outputs/apk/debug/app-debug.apk

## 构建变体

| 变体 | 命令 | 开源组件 | 崩溃日志 |
| --- | --- | --- | --- |
| Release | :app:assembleRelease | 隐藏 | 关闭 |
| StandardDebug | :app:assembleStandardDebug | 隐藏 | 开启 |
| Debug | :app:assembleDebug | 显示 | 开启 |

1.0.0 为正式封版：versionCode 重置为 1，三构建变体重新打包，并完成长列表性能优化。

## Release 签名

```powershell
New-Item -ItemType Directory -Force -Path "app\keystore"
keytool -genkeypair -keystore "app\keystore\release.jks" -storetype JKS -storepass 123456 -keypass 123456 -alias aster -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Aster Files, O=Aster, C=CN"
.\gradlew.bat :app:assembleRelease
```

> 默认 release.jks 仅用于本地测试，正式发布请替换并安全保管。

## 开源协议

SPDX 清单：app/src/main/assets/open-source-components.json（28 项）。

主要依赖：Apache Commons Net、OkHttp、SMBJ、zip4j、apksig、AndroidX Biometric、Media3、Coil、NanoHTTPD、ZXing。

## 文档

- ARCHITECTURE.md — 模块架构
- README.md — 项目构建与使用说明

## 版本

当前：1.0.0（正式封版），versionCode 1。

## License

应用代码为 Aster Files 项目自有；第三方组件遵循各自 SPDX 许可证。详见 SPDX JSON 与应用内“开源组件”。
