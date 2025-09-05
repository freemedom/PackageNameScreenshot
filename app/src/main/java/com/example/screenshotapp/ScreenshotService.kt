package com.example.screenshotapp

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*

class ScreenshotService : Service() {
    
    companion object {
        private const val TAG = "ScreenshotService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screenshot_channel" // 1
        
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
    }
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var windowManager: WindowManager? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        // 初始化结果管理器（用于后台通信）
        ScreenshotResultManager.initialize(this) { /* 服务端不需要回调 */ }
    }
    
    /**
     * 服务启动命令处理
     * 当Activity调用startForegroundService()时会触发此方法
     * @param intent 包含截屏所需数据的Intent
     * @param flags 启动标志
     * @param startId 启动ID
     * @return 服务重启策略
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 启动前台服务，显示通知
        // 这是Android 8.0+的要求，前台服务必须显示通知
        startForeground(NOTIFICATION_ID, createNotification())
        
        // 使用安全调用操作符检查intent是否为null
        intent?.let {
            // 从Intent中提取用户授权的结果代码
            val resultCode = it.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            
            // 从Intent中提取MediaProjection授权数据
            val data = it.getParcelableExtra<Intent>(EXTRA_DATA)
            
            // 检查用户是否授权了屏幕录制权限
            if (resultCode == Activity.RESULT_OK && data != null) {
                // 用户已授权，开始执行截屏操作
                startScreenshot(resultCode, data)
            } else {
                // 用户未授权或数据无效，停止服务
                stopSelf()
            }
        }
        
        // 返回START_NOT_STICKY，表示服务被系统杀死后不会自动重启
        // 这对于一次性截屏任务来说是合适的
        return START_NOT_STICKY
    }
    
    private fun startScreenshot(resultCode: Int, data: Intent) {
        try {
            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val dpi = metrics.densityDpi
            
            // 创建ImageReader
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            
            // 获取MediaProjection
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
            
            // Android 14+ 要求注册回调来管理资源
            mediaProjectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    Log.d(TAG, "MediaProjection stopped")
                    cleanup()
                }
                
                override fun onCapturedContentResize(width: Int, height: Int) {
                    super.onCapturedContentResize(width, height)
                    Log.d(TAG, "Captured content resized: $width x $height")
                }
                
                override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                    super.onCapturedContentVisibilityChanged(isVisible)
                    Log.d(TAG, "Captured content visibility changed: $isVisible")
                }
            }
            
            mediaProjection?.registerCallback(mediaProjectionCallback!!, null)
            
            // 创建VirtualDisplay
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null, null
            )
            
            // 等待一帧数据，给系统时间渲染
            Thread.sleep(200) // 增加等待时间
            
            // 先停止VirtualDisplay，防止继续写入数据
            virtualDisplay?.release()
            virtualDisplay = null
            
            // 等待一小段时间确保数据写入完成
            Thread.sleep(50)
            
            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                val bitmap = imageToBitmap(image)
                image.close()
                
                if (bitmap != null && !isBlackBitmap(bitmap)) {
                    // 获取前台应用包名
                    val packageName = getCurrentPackageName()
                    
                    // 生成文件名
                    val timestamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.getDefault()).format(Date())
                    val fileName = "Screenshot_${timestamp}_${packageName ?: "unknown"}.jpg"
                    
                    // 保存截图
                    val success = saveScreenshot(bitmap, fileName)
                    
                    // 使用新的结果管理器（主要解决方案，解决后台广播限制问题）
                    val result = ScreenshotResultManager.ScreenshotResult(
                        success = success,
                        fileName = fileName,
                        error = null
                    )
                    ScreenshotResultManager.saveResult(result)
                    Log.d(TAG, "使用ScreenshotResultManager保存结果: $result")
                    
                    // 发送结果广播（作为备选方案保留）
                    val resultIntent = Intent("com.example.screenshotapp.SCREENSHOT_RESULT")
                    resultIntent.putExtra("success", success)
                    resultIntent.putExtra("fileName", fileName)
                    Log.d(TAG, "发送截屏成功广播: action=${resultIntent.action}, success=$success, fileName=$fileName")
                    
                    // 发送本地广播
                    LocalBroadcastManager.getInstance(this).sendBroadcast(resultIntent)
                    
                    // 发送全局广播（备选方案）
                    val globalIntent = Intent("com.example.screenshotapp.SCREENSHOT_RESULT")
                    globalIntent.putExtra("success", success)
                    globalIntent.putExtra("fileName", fileName)
                    globalIntent.setPackage(packageName) // 限制只发送给自己的应用
                    sendBroadcast(globalIntent)
                    Log.d(TAG, "同时发送全局广播作为备选方案")
                    
                    Log.d(TAG, "Screenshot saved: $fileName, success: $success")
                } else {
                    // 可能是安全内容（FLAG_SECURE）
                    val result = ScreenshotResultManager.ScreenshotResult(
                        success = false,
                        fileName = null,
                        error = "secure_content"
                    )
                    ScreenshotResultManager.saveResult(result)
                    Log.d(TAG, "使用ScreenshotResultManager保存安全内容错误: $result")
                    
                    val resultIntent = Intent("com.example.screenshotapp.SCREENSHOT_RESULT")
                    resultIntent.putExtra("success", false)
                    resultIntent.putExtra("error", "secure_content")
                    Log.d(TAG, "发送安全内容广播: action=${resultIntent.action}")
                    
                    // 发送本地广播
                    LocalBroadcastManager.getInstance(this).sendBroadcast(resultIntent)
                    
                    // 发送全局广播（备选方案）
                    val globalIntent = Intent("com.example.screenshotapp.SCREENSHOT_RESULT")
                    globalIntent.putExtra("success", false)
                    globalIntent.putExtra("error", "secure_content")
                    globalIntent.setPackage(packageName)
                    sendBroadcast(globalIntent)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot failed", e)
            
            // 使用结果管理器保存错误信息
            val result = ScreenshotResultManager.ScreenshotResult(
                success = false,
                fileName = null,
                error = e.message
            )
            ScreenshotResultManager.saveResult(result)
            Log.d(TAG, "使用ScreenshotResultManager保存错误: $result")
            
            val resultIntent = Intent("com.example.screenshotapp.SCREENSHOT_RESULT")
            resultIntent.putExtra("success", false)
            resultIntent.putExtra("error", e.message)
            Log.d(TAG, "发送错误广播: action=${resultIntent.action}, error=${e.message}")
            
            // 发送本地广播
            LocalBroadcastManager.getInstance(this).sendBroadcast(resultIntent)
            
            // 发送全局广播（备选方案）
            val globalIntent = Intent("com.example.screenshotapp.SCREENSHOT_RESULT")
            globalIntent.putExtra("success", false)
            globalIntent.putExtra("error", e.message)
            globalIntent.setPackage(packageName)
            sendBroadcast(globalIntent)
        } finally {
            cleanup()
            stopSelf()
        }
    }
    
    private fun imageToBitmap(image: Image): Bitmap? {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width
        
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        
        return if (rowPadding == 0) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        }
    }
    
    private fun isBlackBitmap(bitmap: Bitmap): Boolean {
        // 简单的黑屏检测：检查前几个像素是否都是黑色
        val pixels = IntArray(100)
        bitmap.getPixels(pixels, 0, 10, 0, 0, 10, 10)
        
        var blackPixels = 0
        for (pixel in pixels) {
            if (pixel == 0xFF000000.toInt()) { // 完全黑色
                blackPixels++
            }
        }
        
        // 如果90%以上的像素都是黑色，认为是黑屏
        return blackPixels > pixels.size * 0.9
    }
    
    private fun getCurrentPackageName(): String? {
        return try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val time = System.currentTimeMillis()
            val usageStats = usageStatsManager.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                time - 1000 * 60, // 1分钟前
                time
            )
            
            usageStats?.maxByOrNull { it.lastTimeUsed }?.packageName
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get current package name", e)
            null
        }
    }
    
    private fun saveScreenshot(bitmap: Bitmap, fileName: String): Boolean {
        return try {
            val contentResolver = contentResolver
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            
            val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            
            uri?.let {
                contentResolver.openOutputStream(it)?.use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                }
                
                // 清除IS_PENDING标志
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(it, contentValues, null, null)
                }
                
                true
            } ?: false
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save screenshot", e)
            false
        }
    }
    
    private fun cleanup() {
        try {
            // 1. 先停止VirtualDisplay，防止继续写入数据
            virtualDisplay?.release()
            virtualDisplay = null
            
            // 2. 等待一小段时间确保所有操作完成
            Thread.sleep(100)
            
            // 3. 取消注册回调
            mediaProjectionCallback?.let { callback ->
                mediaProjection?.unregisterCallback(callback)
            }
            
            // 4. 停止MediaProjection
            mediaProjection?.stop()
            
            // 5. 最后关闭ImageReader
            imageReader?.close()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        } finally {
            // 6. 清理所有引用
            virtualDisplay = null
            mediaProjection = null
            imageReader = null
            mediaProjectionCallback = null
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "截屏服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "截屏服务通知"
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.screenshot_service_notification_title))
            .setContentText(getString(R.string.screenshot_service_notification_content))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
}
