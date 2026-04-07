# Clariface Android

一款关于检测伪造人脸的 Android 原生应用工程。

## 1) 编译/安装所需完整环境

### 系统与工具
- Windows 10/11（PowerShell）
- Android Studio（建议新版，已安装 SDK Manager）
- Gradle Wrapper（项目自带：`gradlew.bat`）

### JDK 与 Android 构建要求（来自工程配置）
- JDK 21（`gradle/gradle-daemon-jvm.properties` 中 `toolchainVersion=21`）
- Android Gradle Plugin 9.1.0（`gradle/libs.versions.toml`）
- `compileSdk = 36`，`targetSdk = 36`，`minSdk = 24`（`app/build.gradle.kts`）

### Android SDK 组件
- SDK Platform: Android API 36
- Build-Tools（由 Gradle 自动匹配，建议安装最新）
- Platform-Tools（必须，包含 `adb.exe`）

### 手机侧要求
- 打开开发者选项
- 打开 USB 调试
- USB 连接后在手机上确认调试授权（`adb devices` 显示 `device`）

## 2) 当前工程关键路径（对应路径根据你的真实情况进行修改）

- 工程根目录：`D:/Code/Project/Android/Clariface`
- SDK 路径：`D:/Android/Sdk`
- 包名：`com.example.myapplication`
- 启动 Activity：`com.example.myapplication/.MainActivity`

## 3) 一次完整编译并 USB 安装

> 以下命令可直接复制到 PowerShell 执行。

```powershell
Set-Location "D:\Code\Project\Android\Clariface"

# 1. 确认 SDK 路径有效（local.properties 当前应为 sdk.dir=D\:\Android\Sdk）
Test-Path "D:\Android\Sdk"

# 2. 检查设备连接
& "D:\Android\Sdk\platform-tools\adb.exe" version
& "D:\Android\Sdk\platform-tools\adb.exe" kill-server
& "D:\Android\Sdk\platform-tools\adb.exe" start-server
& "D:\Android\Sdk\platform-tools\adb.exe" devices -l

# 3. 编译
.\gradlew.bat :app:assembleDebug

# 4. 安装（若已装旧签名版本，先卸载）
& "D:\Android\Sdk\platform-tools\adb.exe" uninstall com.example.myapplication
.\gradlew.bat :app:installDebug

# 5. 启动应用
& "D:\Android\Sdk\platform-tools\adb.exe" shell am start -n com.example.myapplication/.MainActivity
```

APK 输出目录：`app/build/outputs/apk/debug/app-debug.apk`

## 4) 常见问题

### `SDK location not found`
检查并修正 `local.properties`：

`sdk.dir=D\:\Android\Sdk`

### `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
手机里已有同包名但不同签名应用，先卸载再安装：

```powershell
& "D:\Android\Sdk\platform-tools\adb.exe" uninstall com.example.myapplication
.\gradlew.bat :app:installDebug
```

### 快速抓崩溃日志
```powershell
& "D:\Android\Sdk\platform-tools\adb.exe" logcat -c
& "D:\Android\Sdk\platform-tools\adb.exe" logcat | Select-String "AndroidRuntime|FATAL EXCEPTION|com.example.myapplication"
```

## 5) 本次实测结果（2026-04-07）

- `:app:assembleDebug`：成功
- `:app:installDebug`：成功（先卸载旧签名包后重装）
- 启动命令：`adb shell am start -n com.example.myapplication/.MainActivity` 成功

