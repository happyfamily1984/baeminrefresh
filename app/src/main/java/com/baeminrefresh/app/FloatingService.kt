package com.baeminrefresh.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.roundToInt

class FloatingService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private var bubble: FrameLayout? = null
    private var icon: ImageView? = null
    private var params: WindowManager.LayoutParams? = null
    private var spinner: ObjectAnimator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "'다른 앱 위에 표시' 권한이 필요해요", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        addBubble()
        prefs.registerListener(this)
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs.floatingEnabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        prefs.unregisterListener(this)
        spinner?.cancel()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 화면 회전 시 버튼이 화면 밖으로 나가지 않게
        val p = params ?: return
        clampAndApply(p.x, p.y)
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        when (key) {
            Prefs.KEY_SIZE, Prefs.KEY_ALPHA -> applyAppearance()
            Prefs.KEY_X, Prefs.KEY_Y -> if (prefs.posX < 0) moveToDefaultPosition()
        }
    }

    // ---------------- 알림 / 포그라운드 ----------------

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "플로팅 버튼", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "플로팅 버튼이 켜져 있는 동안 표시돼요"
                    setShowBadge(false)
                }
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, FloatingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_refresh)
            .setContentTitle("배민리프레시 플로팅 버튼 사용 중")
            .setContentText("버튼을 누르면 배민커넥트가 재시작돼요")
            .setContentIntent(openApp)
            .addAction(0, "버튼 끄기", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    // ---------------- 플로팅 버튼 ----------------

    private fun addBubble() {
        val sizePx = dp(prefs.buttonSizeDp)
        val iv = ImageView(this).apply {
            setImageResource(R.drawable.ic_refresh)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val frame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(this@FloatingService, R.color.mint))
                setStroke(dp(2), 0xFFFFFFFF.toInt())
            }
            elevation = dp(6).toFloat()
            addView(iv, FrameLayout.LayoutParams(sizePx / 2, sizePx / 2, Gravity.CENTER))
        }
        val lp = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        bubble = frame
        icon = iv
        params = lp
        frame.alpha = prefs.buttonAlpha / 100f
        frame.setOnTouchListener(DragTouchListener())
        wm.addView(frame, lp)

        if (prefs.posX < 0 || prefs.posY < 0) moveToDefaultPosition()
        else clampAndApply(prefs.posX, prefs.posY)
    }

    private fun applyAppearance() {
        val frame = bubble ?: return
        val lp = params ?: return
        val sizePx = dp(prefs.buttonSizeDp)
        lp.width = sizePx
        lp.height = sizePx
        icon?.layoutParams = FrameLayout.LayoutParams(sizePx / 2, sizePx / 2, Gravity.CENTER)
        frame.alpha = prefs.buttonAlpha / 100f
        clampAndApply(lp.x, lp.y)
    }

    private fun moveToDefaultPosition() {
        val (w, h) = screenSize()
        val size = dp(prefs.buttonSizeDp)
        clampAndApply(w - size - dp(12), (h * 0.6f).roundToInt())
    }

    private fun clampAndApply(x: Int, y: Int) {
        val frame = bubble ?: return
        val lp = params ?: return
        val (w, h) = screenSize()
        lp.x = x.coerceIn(0, (w - lp.width).coerceAtLeast(0))
        lp.y = y.coerceIn(0, (h - lp.height).coerceAtLeast(0))
        runCatching { wm.updateViewLayout(frame, lp) }
    }

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    private inner class DragTouchListener : View.OnTouchListener {
        private val touchSlop = ViewConfiguration.get(this@FloatingService).scaledTouchSlop
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var downAt = 0L
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val lp = params ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = e.rawX
                    touchY = e.rawY
                    downAt = SystemClock.uptimeMillis()
                    dragging = false
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        v.alpha = 1f
                    }
                    if (dragging) clampAndApply(startX + dx.roundToInt(), startY + dy.roundToInt())
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    if (dragging) {
                        v.alpha = prefs.buttonAlpha / 100f
                        prefs.posX = lp.x
                        prefs.posY = lp.y
                    } else if (SystemClock.uptimeMillis() - downAt >= LONG_PRESS_MS) {
                        openSettingsScreen()
                    } else {
                        v.performClick()
                        onBubbleTapped()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    v.alpha = prefs.buttonAlpha / 100f
                }
            }
            return true
        }
    }

    // ---------------- 재시작 ----------------

    private fun onBubbleTapped() {
        val pkg = prefs.targetPackage
        if (pkg == null) {
            toast("먼저 배민리프레시 앱에서 배민커넥트 앱을 선택해 주세요")
            openSettingsScreen()
            return
        }
        val service = RestartAccessibilityService.instance
        if (service == null) {
            toast("접근성 설정에서 '배민리프레시'를 켜 주세요")
            openSettingsScreen()
            return
        }
        if (service.isBusy) return
        startSpinner()
        service.restart(pkg) { result ->
            stopSpinner()
            when (result) {
                RestartAccessibilityService.Result.RESTARTED -> toast("배민커넥트를 재시작했어요")
                RestartAccessibilityService.Result.NOT_RUNNING_LAUNCHED -> toast("배민커넥트를 실행했어요")
                RestartAccessibilityService.Result.TIMEOUT_LAUNCHED ->
                    toast("강제 중지 버튼을 찾지 못해 실행만 했어요")
                RestartAccessibilityService.Result.FAILED ->
                    toast("배민커넥트를 실행하지 못했어요. 앱 선택을 확인해 주세요")
            }
        }
    }

    private fun startSpinner() {
        val iv = icon ?: return
        spinner?.cancel()
        spinner = ObjectAnimator.ofFloat(iv, View.ROTATION, 0f, 360f).apply {
            duration = 700
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopSpinner() {
        spinner?.cancel()
        spinner = null
        icon?.rotation = 0f
    }

    private fun openSettingsScreen() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val CHANNEL_ID = "floating"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.baeminrefresh.app.STOP"
        private const val LONG_PRESS_MS = 600L

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, FloatingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingService::class.java))
        }
    }
}
