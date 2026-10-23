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
    private var overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    /** launchPkg == null 이면 배민커넥트 재시작 버튼, 아니면 해당 앱을 실행하는 스티커 */
    private inner class Bubble(val launchPkg: String?) {
        lateinit var frame: FrameLayout
        lateinit var iv: ImageView
        lateinit var lp: WindowManager.LayoutParams
        var spinner: ObjectAnimator? = null
        val isRestart: Boolean get() = launchPkg == null
    }

    private val bubbles = mutableListOf<Bubble>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        // 접근성 서비스가 켜져 있으면, 접근성 오버레이로 그린다.
        // 접근성 오버레이는 통화 화면 등에서 일반 오버레이를 숨기는 동작을 통과한다.
        val ax = RestartAccessibilityService.instance
        if (ax != null) {
            wm = ax.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            overlayType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "'다른 앱 위에 표시' 권한이 필요해요", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        buildAll()
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
        removeAllBubbles()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        // 실험실: 가로모드에서는 앱 스티커를 숨기고, 세로로 돌아오면 다시 표시
        if (prefs.labPauseStickersLandscape) {
            if (landscape) removeStickerBubbles() else showStickerBubbles()
        }
        // 저장된 자리로 되돌린 뒤 서로 겹치지 않게 재배치 (회전 시 겹침 방지)
        repositionAll()
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        when (key) {
            Prefs.KEY_SIZE, Prefs.KEY_ALPHA -> bubbles.forEach { applyAppearance(it) }
            Prefs.KEY_X, Prefs.KEY_Y -> bubbles.firstOrNull { it.isRestart }?.let { b ->
                if (prefs.posX < 0) defaultPosition(b) else clampAndApply(b, prefs.posX, prefs.posY)
            }
            Prefs.KEY_STICKERS -> rebuildStickers()
            Prefs.KEY_LAB_PAUSE_LANDSCAPE ->
                if (prefs.labPauseStickersLandscape && isLandscape()) removeStickerBubbles()
                else showStickerBubbles()
        }
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

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

    // ---------------- 버튼 생성/제거 ----------------

    private fun buildAll() {
        removeAllBubbles()
        addRestartBubble()
        if (!stickersPaused()) for (s in prefs.getStickers()) addStickerBubble(s)
    }

    private fun rebuildStickers() {
        removeStickerBubbles()
        if (!stickersPaused()) for (s in prefs.getStickers()) addStickerBubble(s)
    }

    /** 실험실 설정이 켜져 있고 지금 가로모드면 스티커를 띄우지 않는다. */
    private fun stickersPaused(): Boolean = prefs.labPauseStickersLandscape && isLandscape()

    private fun removeStickerBubbles() {
        val toRemove = bubbles.filter { !it.isRestart }
        for (b in toRemove) {
            b.spinner?.cancel()
            runCatching { wm.removeView(b.frame) }
        }
        bubbles.removeAll(toRemove)
    }

    /** 스티커가 하나도 없으면 저장된 스티커를 다시 띄운다. */
    private fun showStickerBubbles() {
        if (bubbles.any { !it.isRestart }) return
        for (s in prefs.getStickers()) addStickerBubble(s)
    }

    private fun removeAllBubbles() {
        for (b in bubbles) {
            b.spinner?.cancel()
            runCatching { wm.removeView(b.frame) }
        }
        bubbles.clear()
    }

    private fun addRestartBubble() {
        val sizePx = dp(prefs.buttonSizeDp)
        val b = Bubble(null)
        b.iv = ImageView(this).apply {
            setImageResource(R.drawable.ic_refresh)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        b.frame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(this@FloatingService, R.color.mint))
                setStroke(dp(2), 0xFFFFFFFF.toInt())
            }
            elevation = dp(6).toFloat()
            addView(b.iv, FrameLayout.LayoutParams(sizePx / 2, sizePx / 2, Gravity.CENTER))
        }
        b.lp = newLayoutParams(sizePx)
        b.frame.alpha = prefs.buttonAlpha / 100f
        b.frame.setOnTouchListener(DragTouchListener(b))
        bubbles.add(b)
        addOverlayView(b)

        if (prefs.posX < 0 || prefs.posY < 0) defaultPosition(b)
        else clampAndApply(b, prefs.posX, prefs.posY)
    }

    private fun addStickerBubble(sticker: Sticker) {
        val pkg = sticker.packageName
        val iconDrawable = try {
            packageManager.getApplicationIcon(pkg)
        } catch (e: Exception) {
            // 앱이 삭제됨 → 스티커 정리
            prefs.removeSticker(pkg)
            return
        }
        val sizePx = dp(prefs.buttonSizeDp)
        val b = Bubble(pkg)
        b.iv = ImageView(this).apply {
            setImageDrawable(iconDrawable)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        b.frame = FrameLayout(this).apply {
            elevation = dp(5).toFloat()
            val pad = dp(1)
            addView(
                b.iv,
                FrameLayout.LayoutParams(sizePx - pad * 2, sizePx - pad * 2, Gravity.CENTER)
            )
        }
        b.lp = newLayoutParams(sizePx)
        b.frame.alpha = prefs.buttonAlpha / 100f
        b.frame.setOnTouchListener(DragTouchListener(b))
        bubbles.add(b)
        addOverlayView(b)

        val desired = if (sticker.x < 0 || sticker.y < 0) autoStickerPos(b) else (sticker.x to sticker.y)
        val (cx, cy) = resolveCollision(b, desired.first, desired.second)
        clampAndApply(b, cx, cy)
    }

    private fun newLayoutParams(sizePx: Int) = WindowManager.LayoutParams(
        sizePx, sizePx,
        overlayType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    /** 버튼 창을 올린다. 접근성 오버레이가 거부되면 일반 오버레이로 되돌려 다시 시도한다. */
    private fun addOverlayView(b: Bubble) {
        try {
            wm.addView(b.frame, b.lp)
        } catch (e: Exception) {
            if (overlayType != WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) {
                overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                b.lp.type = overlayType
                runCatching { wm.addView(b.frame, b.lp) }
            }
        }
    }

    private fun applyAppearance(b: Bubble) {
        val sizePx = dp(prefs.buttonSizeDp)
        b.lp.width = sizePx
        b.lp.height = sizePx
        val iconSize = if (b.isRestart) sizePx / 2 else sizePx - dp(1) * 2
        b.iv.layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
        b.frame.alpha = prefs.buttonAlpha / 100f
        clampAndApply(b, b.lp.x, b.lp.y)
    }

    private fun defaultPosition(b: Bubble) {
        val (x, y) = defaultRestartPos()
        clampAndApply(b, x, y)
    }

    private fun defaultRestartPos(): Pair<Int, Int> {
        val (w, h) = screenSize()
        val size = dp(prefs.buttonSizeDp)
        return (w - size - dp(12)) to (h * 0.6f).roundToInt()
    }

    /** 각 버튼의 저장된 위치(없으면 기본 위치). */
    private fun savedPosOf(b: Bubble): Pair<Int, Int> {
        return if (b.isRestart) {
            if (prefs.posX < 0 || prefs.posY < 0) defaultRestartPos() else prefs.posX to prefs.posY
        } else {
            val s = prefs.getStickers().firstOrNull { it.packageName == b.launchPkg }
            if (s == null || s.x < 0 || s.y < 0) autoStickerPos(b) else s.x to s.y
        }
    }

    /** 회전 등으로 다시 배치: 저장된 자리로 되돌린 뒤 서로 겹치지 않게 벌린다. */
    private fun repositionAll() {
        val done = mutableListOf<Bubble>()
        for (b in bubbles) {
            val (sx, sy) = savedPosOf(b)
            val (x, y) = resolveCollisionAgainst(b, sx, sy, done)
            clampAndApply(b, x, y)
            done.add(b)
        }
    }

    private fun autoStickerPos(b: Bubble): Pair<Int, Int> {
        // 스티커는 왼쪽에 세로로 쌓이게
        val (_, h) = screenSize()
        val size = dp(prefs.buttonSizeDp)
        val index = bubbles.count { !it.isRestart } - 1
        val y = (h * 0.3f).roundToInt() + index * (size + dp(10))
        return dp(12) to y
    }

    private fun clampAndApply(b: Bubble, x: Int, y: Int) {
        val (w, h) = screenSize()
        b.lp.x = x.coerceIn(0, (w - b.lp.width).coerceAtLeast(0))
        b.lp.y = y.coerceIn(0, (h - b.lp.height).coerceAtLeast(0))
        runCatching { wm.updateViewLayout(b.frame, b.lp) }
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

    private inner class DragTouchListener(private val bubble: Bubble) : View.OnTouchListener {
        private val touchSlop = ViewConfiguration.get(this@FloatingService).scaledTouchSlop
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var downAt = 0L
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val lp = bubble.lp
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
                    if (dragging) clampAndApply(bubble, startX + dx.roundToInt(), startY + dy.roundToInt())
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    if (dragging) {
                        v.alpha = prefs.buttonAlpha / 100f
                        val (sx, sy) = computeSnap(bubble)
                        val (tx, ty) = resolveCollision(bubble, sx, sy)
                        savePositionAt(bubble, tx, ty)
                        animateTo(bubble, tx, ty)
                    } else if (SystemClock.uptimeMillis() - downAt >= LONG_PRESS_MS) {
                        openSettingsScreen()
                    } else {
                        v.performClick()
                        onBubbleTapped(bubble)
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

    private fun savePositionAt(b: Bubble, x: Int, y: Int) {
        if (b.isRestart) {
            prefs.posX = x
            prefs.posY = y
        } else {
            b.launchPkg?.let { prefs.updateStickerPosition(it, x, y) }
        }
    }

    /**
     * 드래그를 놓았을 때 붙일 위치 계산.
     * 1) 다른 버튼 근처면 그 버튼에 세로로 붙인다(겹치지 않게 바로 위/아래).
     * 2) 아니면, 벽에 아이콘 반 크기보다 가까울 때만 그 벽에 붙인다.
     * 3) 둘 다 아니면 놓은 자리에 그대로 둔다. (겹침은 resolveCollision이 막는다)
     */
    private fun computeSnap(b: Bubble): Pair<Int, Int> {
        val (w, h) = screenSize()
        val size = b.lp.width
        val gap = dp(2)
        val wallThreshold = dp(prefs.buttonSizeDp / 2)
        val snapDist = size + dp(prefs.buttonSizeDp / 2)

        // 1) 다른 버튼 근처 → 세로로 붙이기
        val near = bubbles.filter { it !== b }.minByOrNull {
            abs(b.lp.x - it.lp.x) + abs(b.lp.y - it.lp.y)
        }
        if (near != null &&
            abs(b.lp.x - near.lp.x) < snapDist && abs(b.lp.y - near.lp.y) < snapDist
        ) {
            val x = near.lp.x
            val y = if (b.lp.y >= near.lp.y) near.lp.y + size + gap else near.lp.y - size - gap
            return x.coerceIn(0, (w - size).coerceAtLeast(0)) to
                y.coerceIn(0, (h - size).coerceAtLeast(0))
        }

        // 2) 벽에 반 개보다 가까우면 벽에 붙이기
        val dl = b.lp.x
        val dr = w - (b.lp.x + size)
        val dt = b.lp.y
        val db = h - (b.lp.y + size)
        val min = minOf(dl, dr, dt, db)
        if (min > wallThreshold) return b.lp.x to b.lp.y

        return when (min) {
            dl -> 0 to b.lp.y
            dr -> (w - size).coerceAtLeast(0) to b.lp.y
            dt -> b.lp.x to 0
            else -> b.lp.x to (h - size).coerceAtLeast(0)
        }
    }

    /** 주어진 위치가 다른 버튼과 겹치면, 겹치지 않는 가장 가까운 자리로(주로 위/아래로) 밀어낸다. */
    private fun resolveCollision(b: Bubble, x: Int, y: Int): Pair<Int, Int> =
        resolveCollisionAgainst(b, x, y, bubbles.filter { it !== b })

    private fun resolveCollisionAgainst(b: Bubble, x: Int, y: Int, others: List<Bubble>): Pair<Int, Int> {
        val (_, h) = screenSize()
        val size = b.lp.width
        val gap = dp(6)

        fun overlaps(px: Int, py: Int): Boolean = others.any {
            abs(px - it.lp.x) < size + gap && abs(py - it.lp.y) < size + gap
        }
        if (!overlaps(x, y)) return x to y

        val maxY = (h - size).coerceAtLeast(0)
        var offset = gap
        while (offset <= h) {
            val down = (y + offset).coerceAtMost(maxY)
            if (!overlaps(x, down)) return x to down
            val up = (y - offset).coerceAtLeast(0)
            if (!overlaps(x, up)) return x to up
            if (down == maxY && up == 0) break
            offset += gap
        }
        return x to y
    }

    private fun animateTo(b: Bubble, tx: Int, ty: Int) {
        val sx = b.lp.x
        val sy = b.lp.y
        if (sx == tx && sy == ty) return
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 160
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                b.lp.x = (sx + (tx - sx) * f).roundToInt()
                b.lp.y = (sy + (ty - sy) * f).roundToInt()
                runCatching { wm.updateViewLayout(b.frame, b.lp) }
            }
            start()
        }
    }

    // ---------------- 탭 동작 ----------------

    private fun onBubbleTapped(b: Bubble) {
        if (b.isRestart) onRestartTapped(b) else launchApp(b.launchPkg!!)
    }

    private fun launchApp(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            toast("이 앱을 실행할 수 없어요")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("앱을 실행하지 못했어요")
        }
    }

    // ---------------- 재시작 ----------------

    private fun onRestartTapped(b: Bubble) {
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
        startSpinner(b)
        service.restart(pkg) { result ->
            stopSpinner(b)
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

    private fun startSpinner(b: Bubble) {
        b.spinner?.cancel()
        b.spinner = ObjectAnimator.ofFloat(b.iv, View.ROTATION, 0f, 360f).apply {
            duration = 700
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopSpinner(b: Bubble) {
        b.spinner?.cancel()
        b.spinner = null
        b.iv.rotation = 0f
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
