package com.example.screenshotapp

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.screenshotapp.databinding.ActivityMainBinding

/**
 * 截屏应用主界面Activity
 * 负责处理用户交互、权限管理和截屏流程控制
 */
class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
    // 视图绑定对象，用于安全地访问布局中的视图
    private lateinit var binding: ActivityMainBinding
    
    // 本地广播管理器，用于接收截屏服务的广播消息
    private lateinit var localBroadcastManager: LocalBroadcastManager
    
    // 倒计时定时器引用，用于在需要时取消倒计时
    private var countdownTimerRef: android.os.CountDownTimer? = null
    
    // 屏幕录制权限请求启动器
    // 当用户授权或拒绝屏幕录制权限时，会回调这个lambda
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // 用户授权成功，开始倒计时然后启动截屏服务
            startCountdownAfterPermission(result.resultCode, result.data!!)
        } else {
            // 用户拒绝授权，更新状态显示并重新启用按钮
            updateStatus("截屏权限被拒绝")
            binding.screenshotButton.isEnabled = true
        }
    }
    
    // 使用情况访问权限请求启动器
    // 当用户从设置页面返回时，重新检查权限状态
    private val usageStatsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        checkUsageStatsPermission()
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 初始化视图绑定，将布局文件与Activity关联
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // 获取本地广播管理器实例，用于接收截屏服务的广播消息
        localBroadcastManager = LocalBroadcastManager.getInstance(this)
        
        // 设置用户界面（按钮点击事件等）
        setupUI()
        
        // 检查使用情况访问权限，这是获取前台应用包名的必要权限
        checkUsageStatsPermission()
    }
    
    /**
     * 设置用户界面
     * 配置按钮点击事件等用户交互
     */
    private fun setupUI() {
        // 设置截屏按钮的点击事件
        binding.screenshotButton.setOnClickListener {
            // 先检查使用情况访问权限，如果已授权则开始倒计时截屏
            if (checkUsageStatsPermission()) {
                startCountdownScreenshot()
            }
        }
    }
    
    /**
     * 检查使用情况访问权限
     * 这个权限用于获取当前前台应用的包名
     * @return true 如果已授权，false 如果未授权
     */
    private fun checkUsageStatsPermission(): Boolean {
        // 获取应用操作管理器
        val appOpsManager = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        
        // 根据Android版本使用不同的API检查权限
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 使用新的API
            appOpsManager.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
        } else {
            // Android 9 及以下使用旧API
            @Suppress("DEPRECATION")
            appOpsManager.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
        }
        
        // 检查权限状态是否为已授权
        val hasPermission = mode == AppOpsManager.MODE_ALLOWED
        
        // 如果未授权，显示权限请求对话框
        if (!hasPermission) {
            showUsageStatsPermissionDialog()
        }
        
        return hasPermission
    }
    
    /**
     * 显示使用情况访问权限请求对话框
     * 引导用户前往系统设置页面开启权限
     */
    private fun showUsageStatsPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permission_usage_stats_title))
            .setMessage(getString(R.string.permission_usage_stats_message))
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                // 用户点击确定，打开系统设置页面
                openUsageStatsSettings()
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                // 用户点击取消，显示提示信息
                Toast.makeText(this, getString(R.string.permission_usage_stats_denied), Toast.LENGTH_LONG).show()
            }
            .setCancelable(false) // 不允许通过返回键取消对话框
            .show()
    }
    
    /**
     * 打开系统使用情况访问设置页面
     * 用户需要手动开启本应用的使用情况访问权限
     */
    private fun openUsageStatsSettings() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        usageStatsLauncher.launch(intent)
    }
    
    /**
     * 开始倒计时截屏
     * 先获取截屏权限，然后显示3秒倒计时，最后启动截屏服务
     */
    private fun startCountdownScreenshot() {
        // 禁用截屏按钮，防止重复点击
        binding.screenshotButton.isEnabled = false
        
        // 先请求屏幕录制权限
        requestScreenCapture()
    }
    
    /**
     * 请求屏幕录制权限
     * 创建MediaProjection授权Intent并启动权限请求
     */
    private fun requestScreenCapture() {
        // 获取媒体投影管理器
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        
        // 创建屏幕捕获授权Intent
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        
        // 启动权限请求
        screenCaptureLauncher.launch(captureIntent)
    }
    
    /**
     * 权限获取成功后开始倒计时
     * 显示3秒倒计时，然后启动截屏服务
     * @param resultCode 授权结果代码
     * @param data 包含授权数据的Intent
     */
    private fun startCountdownAfterPermission(resultCode: Int, data: Intent) {
        // 开始3秒倒计时
        var countdown = 3
        updateStatus("准备截屏，${countdown}秒后开始...")
        
        // 创建倒计时定时器
        val countdownTimer = object : android.os.CountDownTimer(3000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                countdown--
                if (countdown > 0) {
                    updateStatus("准备截屏，${countdown}秒后开始...")
                }
            }
            
            override fun onFinish() {
                updateStatus("开始截屏...")
                // 倒计时结束，启动截屏服务
                startScreenshotService(resultCode, data)
            }
        }
        
        // 启动倒计时
        countdownTimer.start()
        
        // 保存定时器引用，以便在需要时取消
        countdownTimerRef = countdownTimer
    }
    
    /**
     * 启动截屏服务
     * 将用户授权的MediaProjection数据传递给截屏服务
     * @param resultCode 授权结果代码
     * @param data 包含授权数据的Intent
     */
    private fun startScreenshotService(resultCode: Int, data: Intent) {
        // 更新状态显示
        updateStatus("正在截屏...")
        // 这里打印一下日志
        Log.d(TAG, "startScreenshotService: resultCode = $resultCode, data = $data") // Unresolved reference: Log
        
        // 重新启用截屏按钮
        binding.screenshotButton.isEnabled = true
        
        // 创建截屏服务Intent并传递授权数据
        val serviceIntent = Intent(this, ScreenshotService::class.java).apply {
            putExtra(ScreenshotService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenshotService.EXTRA_DATA, data)
        }
        
        // 启动前台服务
        startForegroundService(serviceIntent)
    }
    
    /**
     * 更新状态文本显示
     * @param message 要显示的状态消息
     */
    private fun updateStatus(message: String) {
        binding.statusText.text = message
    }
    
    /**
     * 显示长时间Toast消息
     * @param message 要显示的消息
     * @param duration 显示时长（毫秒）
     */
    private fun showLongToast(message: String, duration: Long = 3000) {
        val toast = Toast.makeText(this, message, Toast.LENGTH_LONG)
        
        // 使用反射设置显示时长
        try {
            val field = toast.javaClass.getDeclaredField("mTN")
            field.isAccessible = true
            val tn = field.get(toast)
            
            val showField = tn.javaClass.getDeclaredField("mShow")
            showField.isAccessible = true
            
            val show = showField.get(tn)
            val showClass = show.javaClass
            
            val hideMethod = showClass.getDeclaredMethod("hide")
            hideMethod.isAccessible = true
            
            // 延迟隐藏Toast
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    hideMethod.invoke(show)
                } catch (e: Exception) {
                    // 忽略异常
                }
            }, duration)
            
        } catch (e: Exception) {
            // 如果反射失败，使用默认的LENGTH_LONG
            Log.w(TAG, "Failed to set custom toast duration, using default")
        }
        
        toast.show()
    }
    
    /**
     * 显示多次Toast消息（简单替代方案）
     * @param message 要显示的消息
     * @param times 显示次数
     */
    private fun showMultipleToast(message: String, times: Int = 2) {
        repeat(times) { index ->
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }, index * 2000L) // 每2秒显示一次
        }
    }
    
    /**
     * Activity恢复时注册广播接收器
     * 用于接收截屏服务的结果广播
     */
    override fun onResume() {
        super.onResume()
        // registerScreenshotReceiver()
    }
    
    /**
     * Activity暂停时注销广播接收器
     * 避免内存泄漏
     */
    override fun onPause() {
        super.onPause()
        // unregisterScreenshotReceiver()
    }
    
    /**
     * Activity销毁时清理资源
     */
    override fun onDestroy() {
        super.onDestroy()
        // 取消倒计时定时器
        countdownTimerRef?.cancel()
        countdownTimerRef = null
    }
    
    /**
     * 注册截屏结果广播接收器
     * 监听截屏服务发送的结果消息
     */
    private fun registerScreenshotReceiver() {
        val filter = IntentFilter("com.example.screenshotapp.SCREENSHOT_RESULT")
        localBroadcastManager.registerReceiver(screenshotResultReceiver, filter)
    }
    
    /**
     * 注销截屏结果广播接收器
     * 防止内存泄漏
     */
    private fun unregisterScreenshotReceiver() {
        try {
            localBroadcastManager.unregisterReceiver(screenshotResultReceiver)
        } catch (e: Exception) {
            // 接收器可能已经注销，忽略异常
        }
    }
    
    /**
     * 截屏结果广播接收器
     * 接收截屏服务发送的结果消息并更新UI
     */
    private val screenshotResultReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "screenshotResultReceiver: intent = $intent")
            // 从广播Intent中获取结果数据
            val success = intent?.getBooleanExtra("success", false) ?: false
            val fileName = intent?.getStringExtra("fileName")
            val error = intent?.getStringExtra("error")
            
            // 根据截屏结果更新UI和显示相应提示
            when {
                success && fileName != null -> {
                    // 截屏成功，显示文件名和成功提示
                    updateStatus("截图已保存: $fileName")
                    showLongToast("截图已保存: $fileName", 5000) // 显示5秒
                }
                error == "secure_content" -> {
                    // 遇到安全内容（FLAG_SECURE），显示相应提示
                    updateStatus("当前页面不支持截屏")
                    showLongToast("当前页面不支持截屏", 4000) // 显示4秒
                }
                else -> {
                    // 其他错误情况，显示失败提示
                    updateStatus("截屏失败")
                    showLongToast("截屏失败", 4000) // 显示4秒
                }
            }
        }
    }
}
