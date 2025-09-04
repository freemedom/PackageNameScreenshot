下面给你一套「从 Android 9 到最新版本」都通用的截屏 App 实现思路，重点是两件事：**用 MediaProjection 做截屏**，以及**把前台 App 的包名拿到并拼进文件名**，再**按分区存储规范写入 MediaStore 的“Screenshots”目录**。

---

# 总体架构

1) **发起屏幕捕获授权 → 拿到 MediaProjection**  
- 用 `MediaProjectionManager.createScreenCaptureIntent()` 拉起系统授权窗；用户同意后用 `getMediaProjection(resultCode, data)` 取到实例。核心是基于 `MediaProjection` 创建 `VirtualDisplay` 把屏幕画面输出到 `Surface`（通常来自 `ImageReader`）。这是官方推荐路径，自 Android 5 起可用，9+都支持。([Android Developers](https://developer.android.com/media/grow/media-projection?utm_source=chatgpt.com))

- **Android 14+（targetSdk 34/35）注意前台服务类型：**需要在 `AndroidManifest.xml` 的前台服务上声明 `foregroundServiceType="mediaProjection"`，并在**用户授权后**再启动该 FGS，否则会抛 `SecurityException`。([Android Developers](https://developer.android.com/about/versions/14/changes/fgs-types-required?utm_source=chatgpt.com), [Stack Overflow](https://stackoverflow.com/questions/77307867/screen-capture-mediaprojection-on-android-14?utm_source=chatgpt.com))

2) **一帧截图的采集流程**  
- 用 `ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)` 创建图像源，`mediaProjection.createVirtualDisplay(...)` 绑定到它的 `Surface`；`acquireLatestImage()` 拿到一帧数据，转 `Bitmap`。完成后记得 `close()` Image、`release()` VirtualDisplay、`stop()` MediaProjection，避免泄漏。官方文档把 VirtualDisplay 的创建与参数写得很清楚。([Android Developers](https://developer.android.com/media/grow/media-projection?utm_source=chatgpt.com))

3) **获取“当前前台 App 包名”用于命名**（两种策略，至少实现其一）：  
- **A. UsageStatsManager（官方且合规）**：申请 `PACKAGE_USAGE_STATS`（不是运行时权限，用户需在“设置 → 使用情况访问”里授予）。用 `queryEvents()` 或 `queryUsageStats()` 找**最近一次** `MOVE_TO_FOREGROUND`/`RESUMED` 事件对应的包名。([Android Developers](https://developer.android.com/reference/android/app/usage/UsageStatsManager?utm_source=chatgpt.com), [Android Git Repositories](https://android.googlesource.com/platform/developers/build/%2B/master/prebuilts/gradle/AppUsageStatistics/README.md?utm_source=chatgpt.com))  
- **B. AccessibilityService（实时、但要求用户开启无障碍服务）**：监听 `TYPE_WINDOW_STATE_CHANGED`，可从事件里拿到当前窗口的 `packageName`。适配时要正确配置服务能力（检索窗口内容等）。([Android Developers](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService?utm_source=chatgpt.com))

> 提醒：老 API 的 `getRunningTasks()/getRunningAppProcesses()` 早就不可靠/受限了，官方立场是用 UsageStats 或无障碍。([Stack Overflow](https://stackoverflow.com/questions/34208624/get-real-foreground-process-using-activitymanager-getrunningappprocesses?utm_source=chatgpt.com))

4) **命名与保存（兼容分区存储）**  
- 文件名按你给的格式：  
  `Screenshot_yyyy-MM-dd-HH-mm-ss-SSS_<package>.jpg`（例如 `Screenshot_2025-09-02-22-46-20-340_com.duolingo.jpg`）。时间戳可用 `SimpleDateFormat`/`DateTimeFormatter`，毫秒用 `SSS`。  
- **Android 10+：**通过 **MediaStore** 插入到 `Pictures/Screenshots`，用 `DISPLAY_NAME` 指定文件名、`RELATIVE_PATH` 指定子目录、`MIME_TYPE = image/jpeg`，推荐先设 `IS_PENDING=1`，写完流再清 `IS_PENDING`。这样**不需要** `WRITE_EXTERNAL_STORAGE`。([Android Developers](https://developer.android.com/training/data-storage/shared/media?utm_source=chatgpt.com))  
- **Android 9：**可以走传统外部存储路径（`Environment.DIRECTORY_PICTURES/Screenshots`）写入后再触发媒体扫描；或插入 MediaStore 的旧字段（`DATA`）。([Stack Overflow](https://stackoverflow.com/questions/57030990/how-to-save-an-image-in-a-subdirectory-on-android-q-whilst-remaining-backwards-c?utm_source=chatgpt.com))

5) **不可截内容与边界情况**  
- 任何设置了 `FLAG_SECURE` 的窗口（常见于银行/DRM/隐私页面）都会被系统**强制黑屏或拒绝输出**，无论是系统截图还是 MediaProjection，这一点**无法也不应规避**。设计上要做好失败/全黑图检测与提示。([Android Developers](https://developer.android.com/security/fraud-prevention/activities?utm_source=chatgpt.com))

---

# 关键代码骨架（思路级伪代码）

```kotlin
// 1) 申请屏幕捕获授权（Activity/Fragment）
val mpm = getSystemService(MediaProjectionManager::class.java)
val launcher = registerForActivityResult(StartActivityForResult()) { res ->
    if (res.resultCode == RESULT_OK && res.data != null) {
        startCapture(res.resultCode, res.data!!)
    }
}
launcher.launch(mpm.createScreenCaptureIntent())

// 2) 真正截屏
fun startCapture(resultCode: Int, data: Intent) {
    val metrics = resources.displayMetrics
    val width = metrics.widthPixels
    val height = metrics.heightPixels
    val dpi = metrics.densityDpi

    val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
    val mp = mpm.getMediaProjection(resultCode, data)

    // Android 14+：如需持续截屏，请在此时启动前台服务，声明 type=mediaProjection
    // 并把 mp/Surface 交给 Service 处理（单张截图可不启 FGS）

    val vd = mp.createVirtualDisplay(
        "ScreenCap", width, height, dpi,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        reader.surface, null, null
    )

    val image = reader.acquireLatestImage() // 轮询等待一小段时间更稳妥
    val bitmap = image.toBitmapThenClose() // 自行实现 plane -> Bitmap
    vd.release(); mp.stop(); reader.close()

    val pkg = resolveTopPackageName() // UsageStats 或 Accessibility
    val name = "Screenshot_${now("yyyy-MM-dd-HH-mm-ss-SSS")}_${pkg ?: "unknown"}.jpg"

    saveToScreenshotsViaMediaStore(bitmap, name) // Android 10+ 用 RELATIVE_PATH
}
```

- VirtualDisplay/授权调用流程与参数都来自官方 MediaProjection 文档；Android 14 的前台服务类型必须为 **mediaProjection**。([Android Developers](https://developer.android.com/media/grow/media-projection?utm_source=chatgpt.com))  
- MediaStore 写入 “Pictures/Screenshots” 的做法与字段（`DISPLAY_NAME`、`RELATIVE_PATH`、`IS_PENDING`）为官方建议。([Android Developers](https://developer.android.com/training/data-storage/shared/media?utm_source=chatgpt.com))

---

# 包名获取的两种实现要点

**使用 UsageStatsManager（推荐默认）**  
- Manifest 声明 `<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" tools:ignore="ProtectedPermissions" />`。  
- 引导用户到 `Settings.ACTION_USAGE_ACCESS_SETTINGS` 授权。  
- 用 `UsageStatsManager.queryEvents(begin, now)`，遍历到最近一次前台事件的 `event.packageName`。([Android Developers](https://developer.android.com/reference/android/app/usage/UsageStatsManager?utm_source=chatgpt.com), [Android Git Repositories](https://android.googlesource.com/platform/developers/build/%2B/master/prebuilts/gradle/AppUsageStatistics/README.md?utm_source=chatgpt.com))

**使用 AccessibilityService（作为可选增强）**  
- 提供一个可选开关，用户允许后注册无障碍服务，监听 `TYPE_WINDOW_STATE_CHANGED` 并读取 `event.packageName`。  
- 注意服务配置项（检索窗口内容、回调线程等）正确，否则 `getRootInActiveWindow()` 可能为 `null`。([Android Developers](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService?utm_source=chatgpt.com))

> 兼容提示：个别设备/桌面在 Android 14 上可能导致 UsageStats “最近前台”判断不及时（比如只看到 Launcher）；可以做**双通道**策略：优先无障碍（若启用）→ 退化到 UsageStats → 最后兜底用自身包名。([Stack Overflow](https://stackoverflow.com/questions/77410929/getting-foreground-app-not-working-on-android-14?utm_source=chatgpt.com))

---

# 存储与命名的细节

- **路径**：`MediaStore.Images.Media` + `RELATIVE_PATH = "Pictures/Screenshots"`；系统会把该目录识别为截图集合。([Android Developers](https://developer.android.com/training/data-storage/shared/media?utm_source=chatgpt.com))  
- **文件名**：用 `DISPLAY_NAME` 直接设置；Android 10+ 不再手写外部绝对路径。([Android Developers](https://developer.android.com/training/data-storage/shared/media?utm_source=chatgpt.com))  
- **Android 9 兼容**：可以走外部公共目录 `Environment.getExternalStoragePublicDirectory(DIRECTORY_PICTURES)/Screenshots` 并媒体扫描。([Stack Overflow](https://stackoverflow.com/questions/57030990/how-to-save-an-image-in-a-subdirectory-on-android-q-whilst-remaining-backwards-c?utm_source=chatgpt.com))

---

# 常见坑 & 最佳实践

- **FLAG_SECURE 黑屏**：遇到被保护的窗口（银行、DRM）输出会是黑的，必须向用户提示“该页面不支持截屏”，不要尝试绕过。([Android Developers](https://developer.android.com/security/fraud-prevention/activities?utm_source=chatgpt.com))  
- **尺寸/旋转**：用 `Display`/`WindowMetrics` 获取当前实际尺寸；横竖屏切换时重建 `VirtualDisplay`。([Android Developers](https://developer.android.com/media/grow/media-projection?utm_source=chatgpt.com))  
- **性能**：一张截图即可；若需要连拍，复用 `ImageReader`，并控制频率避免 OOM。  
- **前台服务（长时间录屏/连拍）**：Android 14+ 要求正确的 `foregroundServiceType`，且不要在 `BOOT_COMPLETED` 里直接拉起该类型服务。([Android Developers](https://developer.android.com/about/versions/14/changes/fgs-types-required?utm_source=chatgpt.com))  
- **质量与体积**：JPEG 85% 左右通常够用；如需原始像素可保存 PNG，但体积更大。  
- **元数据**：可把 `packageName` 再写进 EXIF 的 `UserComment` 便于后续检索。  

---

# 你可以按这个任务分工落地

- **模块 A（截屏管道）**：MediaProjection + VirtualDisplay + ImageReader（单测：不同分辨率/旋转）。([Android Developers](https://developer.android.com/media/grow/media-projection?utm_source=chatgpt.com))  
- **模块 B（前台 App 识别）**：UsageStats 实现 + 可选的 Accessibility 实现，带授权引导与回退。([Android Developers](https://developer.android.com/reference/android/app/usage/UsageStatsManager?utm_source=chatgpt.com))  
- **模块 C（存储与命名）**：统一 `FilenameFormatter`，Android 10+ 用 MediaStore，9 用文件系统兼容层。([Android Developers](https://developer.android.com/training/data-storage/shared/media?utm_source=chatgpt.com), [Stack Overflow](https://stackoverflow.com/questions/57030990/how-to-save-an-image-in-a-subdirectory-on-android-q-whilst-remaining-backwards-c?utm_source=chatgpt.com))  
- **模块 D（合规与异常）**：FLAG_SECURE 检测/提示、失败重试、权限状态页。([Android Developers](https://developer.android.com/security/fraud-prevention/activities?utm_source=chatgpt.com))

如果你愿意，我可以把上述骨架扩成一份最小可运行的示例（Kotlin），包含 UsageStats 获取包名 + MediaStore 保存截图的完整代码与清单。