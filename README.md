# Android 截屏应用

这是一个基于 MediaProjection API 的 Android 截屏应用，能够自动获取当前前台应用的包名并包含在截图文件名中。

## 功能特性

- ✅ 使用 MediaProjection API 进行屏幕截图
- ✅ 自动获取当前前台应用包名
- ✅ 截图文件名格式：`Screenshot_yyyy-MM-dd-HH-mm-ss-SSS_<package>.jpg`
- ✅ 支持 Android 9+ 到最新版本
- ✅ 兼容分区存储（Android 10+）
- ✅ 检测安全内容（FLAG_SECURE）并提示用户
- ✅ 前台服务支持（Android 14+）

## 权限说明

### 必需权限
1. **屏幕录制权限** - 用户授权后自动获取
2. **使用情况访问权限** - 用于获取前台应用包名
   - 需要在"设置 → 使用情况访问"中手动开启
   - 应用会引导用户前往设置页面

### 可选权限
- **前台服务权限** - 用于长时间截屏操作

## 使用方法

1. **安装应用**
2. **授予使用情况访问权限**：
   - 首次使用时，应用会提示需要此权限
   - 点击"确定"前往设置页面
   - 找到本应用并开启"使用情况访问"权限
3. **开始截屏**：
   - 点击"截屏"按钮
   - 系统会弹出屏幕录制授权对话框
   - 点击"立即开始"授权
   - 截图将自动保存到相册的"Screenshots"文件夹

## 技术实现

### 核心组件

1. **ScreenshotService** - 截屏服务
   - 使用 MediaProjection + VirtualDisplay + ImageReader
   - 支持前台服务（Android 14+）
   - 自动检测黑屏（安全内容）

2. **MainActivity** - 主界面
   - 权限检查和引导
   - 用户交互界面
   - 结果反馈

3. **包名获取** - UsageStatsManager
   - 查询最近的前台应用事件
   - 获取对应的包名

4. **文件保存** - MediaStore API
   - Android 10+ 使用分区存储
   - 保存到 Pictures/Screenshots 目录

### 文件命名格式

```
Screenshot_2025-01-27-14-30-25-123_com.example.app.jpg
```

- `Screenshot_` - 固定前缀
- `2025-01-27-14-30-25-123` - 时间戳（年-月-日-时-分-秒-毫秒）
- `com.example.app` - 当前前台应用包名
- `.jpg` - 文件扩展名

## 兼容性

- **最低支持**：Android 9 (API 28)
- **目标版本**：Android 14 (API 34)
- **测试设备**：支持所有主流 Android 设备

## 注意事项

1. **安全内容**：某些应用（如银行、支付应用）设置了 FLAG_SECURE，无法截屏，应用会显示相应提示
2. **权限要求**：使用情况访问权限需要用户手动在系统设置中开启
3. **存储位置**：截图保存在系统相册的"Screenshots"文件夹中
4. **性能**：单次截图操作，适合日常使用

## 构建说明

1. 使用 Android Studio 打开项目
2. 同步 Gradle 依赖
3. 连接 Android 设备或启动模拟器
4. 点击运行按钮构建并安装应用

## 依赖库

- AndroidX Core KTX
- AndroidX AppCompat
- Material Design Components
- AndroidX Activity
- AndroidX Lifecycle
- LocalBroadcastManager

## 许可证

本项目仅供学习和参考使用。
