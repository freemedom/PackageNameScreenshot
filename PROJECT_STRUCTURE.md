# 项目结构说明

## 目录结构

```
ScreenshotApp/
├── app/
│   ├── build.gradle                    # 应用级构建配置
│   ├── proguard-rules.pro             # ProGuard 混淆规则
│   └── src/main/
│       ├── AndroidManifest.xml        # 应用清单文件
│       ├── java/com/example/screenshotapp/
│       │   ├── MainActivity.kt        # 主界面 Activity
│       │   └── ScreenshotService.kt   # 截屏服务
│       └── res/
│           ├── layout/
│           │   └── activity_main.xml  # 主界面布局
│           ├── values/
│           │   ├── colors.xml         # 颜色资源
│           │   ├── strings.xml        # 字符串资源
│           │   └── themes.xml         # 主题样式
│           └── xml/
│               ├── backup_rules.xml   # 备份规则
│               └── data_extraction_rules.xml # 数据提取规则
├── build.gradle                       # 项目级构建配置
├── gradle.properties                  # Gradle 属性配置
├── settings.gradle                    # Gradle 设置
├── README.md                          # 项目说明文档
├── USAGE.md                           # 使用说明文档
└── PROJECT_STRUCTURE.md               # 项目结构说明（本文件）
```

## 核心文件说明

### 1. MainActivity.kt
- **功能**：主界面和用户交互
- **职责**：
  - 权限检查和引导
  - 启动截屏服务
  - 显示截屏结果
  - 处理用户操作

### 2. ScreenshotService.kt
- **功能**：截屏核心服务
- **职责**：
  - MediaProjection 屏幕捕获
  - 获取前台应用包名
  - 保存截图到 MediaStore
  - 检测安全内容（FLAG_SECURE）

### 3. AndroidManifest.xml
- **功能**：应用配置和权限声明
- **包含**：
  - 应用基本信息
  - 权限声明
  - 服务注册
  - Activity 配置

### 4. activity_main.xml
- **功能**：主界面布局
- **包含**：
  - 标题文本
  - 说明文本
  - 截屏按钮
  - 状态显示

## 技术架构

### 权限管理
```
用户操作 → 权限检查 → 权限引导 → 系统授权 → 执行截屏
```

### 截屏流程
```
启动服务 → 创建 MediaProjection → 创建 VirtualDisplay → 
获取图像 → 转换为 Bitmap → 获取包名 → 保存文件 → 通知结果
```

### 数据流
```
MediaProjection → VirtualDisplay → ImageReader → Bitmap → 
UsageStatsManager → 包名 → MediaStore → 文件系统
```

## 关键 API 使用

### MediaProjection API
- `MediaProjectionManager.createScreenCaptureIntent()`
- `MediaProjectionManager.getMediaProjection()`
- `MediaProjection.createVirtualDisplay()`

### UsageStats API
- `UsageStatsManager.queryUsageStats()`
- 权限：`PACKAGE_USAGE_STATS`

### MediaStore API
- `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`
- 字段：`DISPLAY_NAME`, `RELATIVE_PATH`, `IS_PENDING`

## 兼容性处理

### Android 版本适配
- **Android 9**：基础 MediaProjection 支持
- **Android 10**：分区存储适配
- **Android 14**：前台服务类型要求

### 权限适配
- **运行时权限**：屏幕录制授权
- **特殊权限**：使用情况访问权限
- **前台服务**：媒体投影服务类型

## 扩展建议

### 功能扩展
1. **批量截屏**：支持连续截屏
2. **截图编辑**：添加标注功能
3. **云端同步**：自动备份截图
4. **定时截屏**：定时任务功能

### 技术优化
1. **性能优化**：内存管理优化
2. **错误处理**：更完善的异常处理
3. **用户体验**：更友好的界面设计
4. **代码质量**：单元测试和代码规范
