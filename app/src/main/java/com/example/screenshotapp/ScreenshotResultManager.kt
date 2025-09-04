package com.example.screenshotapp

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 截屏结果管理器
 * 使用SharedPreferences实现跨进程通信，解决后台广播限制问题
 */
object ScreenshotResultManager {
    private const val TAG = "ScreenshotResultManager"
    private const val PREFS_NAME = "screenshot_results"
    private const val KEY_LAST_RESULT = "last_result"
    private const val KEY_LAST_TIMESTAMP = "last_timestamp"
    
    private var context: Context? = null
    private var resultCallback: ((ScreenshotResult) -> Unit)? = null
    private var checkHandler: Handler? = null
    private var checkRunnable: Runnable? = null
    private var lastCheckedTimestamp = 0L
    
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
        
        Log.d(TAG, "ScreenshotResultManager initialized")
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
                
                resultCallback?.invoke(result)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse result: $resultString", e)
        }
    }
}
