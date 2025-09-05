package com.example.screenshotapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 截屏结果管理器
 * 使用SharedPreferences实现跨进程通信，解决后台广播限制问题
 */
object ScreenshotResultManager {
    private const val TAG = "ScreenshotResultManager"
    private const val PREFS_NAME = "screenshot_results"
    private const val KEY_LAST_RESULT = "last_result"
    private const val KEY_LAST_TIMESTAMP = "last_timestamp"
    private const val KEY_LAST_PROCESSED_TIMESTAMP = "last_processed_timestamp"
    
    private const val NOTIFICATION_CHANNEL_ID = "screenshot_results" // 2
    private const val NOTIFICATION_ID = 2001
    
    private var context: Context? = null
    private var resultCallback: ((ScreenshotResult) -> Unit)? = null
    private var checkHandler: Handler? = null
    private var checkRunnable: Runnable? = null
    private var lastCheckedTimestamp = 0L
    private var isAppInForeground = false
    
    data class ScreenshotResult(
        val success: Boolean,
        val fileName: String? = null,
        val error: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * 初始化管理器
     */
    fun initialize(context: Context, callback: (ScreenshotResult) -> Unit) {
        this.context = context.applicationContext
        this.resultCallback = callback
        this.lastCheckedTimestamp = System.currentTimeMillis()
        
        // 创建通知渠道
        createNotificationChannel()
        
        Log.d(TAG, "ScreenshotResultManager initialized")
    }
    
    /**
     * 设置应用前台状态
     */
    fun setAppForegroundState(inForeground: Boolean) {
        isAppInForeground = inForeground
        Log.d(TAG, "App foreground state: $inForeground")
    }
    
    /**
     * 开始监听结果
     */
    fun startListening() {
        stopListening() // 先停止之前的监听
        
        checkHandler = Handler(Looper.getMainLooper())
        checkRunnable = object : Runnable {
            override fun run() {
                checkForNewResults()
                checkHandler?.postDelayed(this, 500) // 每500ms检查一次
            }
        }
        
        checkHandler?.post(checkRunnable!!)
        Log.d(TAG, "Started listening for screenshot results")
    }
    
    /**
     * 停止监听结果
     */
    fun stopListening() {
        checkRunnable?.let { runnable ->
            checkHandler?.removeCallbacks(runnable)
        }
        checkHandler = null
        checkRunnable = null
        Log.d(TAG, "Stopped listening for screenshot results")
    }
    
    /**
     * 保存截屏结果（由ScreenshotService调用）
     */
    fun saveResult(result: ScreenshotResult) {
        context?.let { ctx ->
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString(KEY_LAST_RESULT, "${result.success}|${result.fileName ?: "null"}|${result.error ?: "null"}")
                putLong(KEY_LAST_TIMESTAMP, result.timestamp)
                apply()
            }
            Log.d(TAG, "Saved screenshot result: $result")
        }
    }
    
    /**
     * 检查是否有新的结果
     */
    private fun checkForNewResults() {
        context?.let { ctx ->
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            val timestamp = prefs.getLong(KEY_LAST_TIMESTAMP, 0)
            // log一下获取到的timestamp
            Log.d(TAG, "checkForNewResults: timestamp = $timestamp")
            
            if (timestamp > lastCheckedTimestamp) {
                val resultString = prefs.getString(KEY_LAST_RESULT, null)
                resultString?.let { parseAndNotifyResult(it, timestamp) }
                lastCheckedTimestamp = timestamp
            }
        }
    }
    
    /**
     * 解析并通知结果
     */
    private fun parseAndNotifyResult(resultString: String, timestamp: Long) {
        try {
            val parts = resultString.split("|")
            if (parts.size >= 3) {
                val success = parts[0].toBoolean()
                val fileName = if (parts[1] == "null") null else parts[1]
                val error = if (parts[2] == "null") null else parts[2]
                
                val result = ScreenshotResult(success, fileName, error, timestamp)
                Log.d(TAG, "Parsed result: $result")
                
                // 如果应用在前台，使用回调
                if (isAppInForeground) {
                    resultCallback?.invoke(result)
                } else {
                    // 应用在后台，显示通知
                    showResultNotification(result)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse result: $resultString", e)
        }
    }
    
    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        context?.let { ctx ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "截屏结果通知",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "显示截屏操作的结果"
                    setShowBadge(true)
                }
                
                val notificationManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(channel)
                Log.d(TAG, "Notification channel created")
            }
        }
    }
    
    /**
     * 显示结果通知
     */
    private fun showResultNotification(result: ScreenshotResult) {
        context?.let { ctx ->
            val (title, content) = when {
                result.success && result.fileName != null -> {
                    "截图成功" to "已保存: ${result.fileName}"
                }
                result.error == "secure_content" -> {
                    "截图失败" to "当前页面不支持截屏"
                }
                else -> {
                    "截图失败" to (result.error ?: "未知错误")
                }
            }
            
            // 创建点击通知后打开应用的Intent
            val intent = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            
            val pendingIntent = PendingIntent.getActivity(
                ctx, 
                0, 
                intent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val notification = NotificationCompat.Builder(ctx, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            
            val notificationManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
            
            Log.d(TAG, "Result notification shown: $title - $content")
        }
    }
}
