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
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
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

    /**
     * launchPkg != null → 해당 앱을 실행하는 스티커
     * restart == true   → 재시작 버튼. restartPkg == null 이면 배민커넥트(기본), 값이 있으면 그 앱
     * pee == true       → 워터 타이머 버튼
     */
    private inner class Bubble(
        val launchPkg: String? = null,
        val pee: Boolean = false,
        val restart: Boolean = false,
        val restartPkg: String? = null,
        val capture: Boolean = false,
        val voice: Boolean = false,
    ) {
        lateinit var frame: FrameLayout
        lateinit var iv: ImageView
        var tv: TextView? = null
        lateinit var lp: WindowManager.LayoutParams
        var spinner: ObjectAnimator? = null
        val isRestart: Boolean get() = restart
        val isSticker: Boolean get() = launchPkg != null
        val restartTarget: String? get() = restartPkg ?: prefs.targetPackage
    }

    private val bubbles = mutableListOf<Bubble>()
    private val peeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val peeTick = object : Runnable {
        override fun run() {
            updatePeeDisplay()
            peeHandler.postDelayed(this, 1000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        pickOverlayHost()
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
        if (intent?.action == ACTION_PEE_RESET) {
            resetPee()
            return START_STICKY
        }
        if (intent?.action == ACTION_WATER_MODE) {
            prefs.waterModeEnabled = !prefs.waterModeEnabled // 리스너가 buildAll() 실행
            updatePeeNotification()
            return START_STICKY
        }
        return START_STICKY
    }

    /** 접근성 서비스가 있으면 접근성 오버레이(통화 화면 통과)로, 없으면 일반 오버레이로. */
    private fun pickOverlayHost() {
        val ax = RestartAccessibilityService.instance
        if (ax != null) {
            wm = ax.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            overlayType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
    }

    /** 접근성이 뒤늦게 연결/해제되면 오버레이 종류를 바꿔 다시 그린다. */
    fun refreshOverlayType() {
        if (!isRunning) return
        val ax = RestartAccessibilityService.instance
        val desired = if (ax != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        if (desired == overlayType) return
        removeAllBubbles() // 기존 wm 으로 먼저 제거
        peeHandler.removeCallbacks(peeTick)
        pickOverlayHost() // wm / overlayType 전환
        buildAll() // 새 오버레이로 다시 올림
    }

    override fun onDestroy() {
        isRunning = false
        if (instance === this) instance = null
        prefs.unregisterListener(this)
        peeHandler.removeCallbacks(peeTick)
        cancelPeeNotification()
        runCatching { recognizer?.destroy() }
        recognizer = null
        abandonAudioFocus()
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
            Prefs.KEY_X, Prefs.KEY_Y -> bubbles.firstOrNull { it.isRestart && it.restartPkg == null }?.let { b ->
                if (prefs.posX < 0) defaultPosition(b) else clampAndApply(b, prefs.posX, prefs.posY)
            }
            Prefs.KEY_STICKERS -> rebuildStickers()
            Prefs.KEY_RESTART_APPS -> buildAll()
            Prefs.KEY_LAB_PAUSE_LANDSCAPE ->
                if (prefs.labPauseStickersLandscape && isLandscape()) removeStickerBubbles()
                else showStickerBubbles()
            Prefs.KEY_PEE_ENABLED, Prefs.KEY_WATER_MODE, Prefs.KEY_SHOW_MAIN_RESTART,
            Prefs.KEY_CAPTURE_ENABLED, Prefs.KEY_VOICE_ENABLED -> buildAll()
            Prefs.KEY_PEE_LAST -> updatePeeDisplay()
        }
    }

    private fun removePeeBubble() {
        val toRemove = bubbles.filter { it.pee }
        for (b in toRemove) runCatching { wm.removeView(b.frame) }
        bubbles.removeAll(toRemove)
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

    /** 워터 모드: 물방울 버튼만 남기고 나머지는 숨긴다. (워터 타이머가 켜져 있을 때만) */
    private fun waterOnly(): Boolean =
        Features.PEE && prefs.peeTrackerEnabled && prefs.waterModeEnabled

    private fun buildAll() {
        removeAllBubbles()
        if (!waterOnly()) {
            if (prefs.showMainRestart) addRestartBubble()
            for (a in prefs.getRestartApps()) addRestartAppBubble(a)
            if (!stickersPaused()) for (s in prefs.getStickers()) addStickerBubble(s)
        }
        if (Features.PEE && prefs.peeTrackerEnabled) addPeeBubble()
        if (!waterOnly() && prefs.captureButtonEnabled) addCaptureBubble()
        if (!waterOnly() && prefs.voiceButtonEnabled) addVoiceBubble()
        updatePeeState()
    }

    private fun rebuildStickers() {
        removeStickerBubbles()
        if (!waterOnly() && !stickersPaused()) for (s in prefs.getStickers()) addStickerBubble(s)
    }

    /** 실험실 설정이 켜져 있고 지금 가로모드면 스티커를 띄우지 않는다. */
    private fun stickersPaused(): Boolean = prefs.labPauseStickersLandscape && isLandscape()

    private fun removeStickerBubbles() {
        val toRemove = bubbles.filter { it.isSticker }
        for (b in toRemove) {
            b.spinner?.cancel()
            runCatching { wm.removeView(b.frame) }
        }
        bubbles.removeAll(toRemove)
    }

    /** 스티커가 하나도 없으면 저장된 스티커를 다시 띄운다. */
    private fun showStickerBubbles() {
        if (waterOnly()) return
        if (bubbles.any { it.isSticker }) return
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
        val b = Bubble(restart = true)
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
        val b = Bubble(launchPkg = pkg)
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

    /** 특정 앱을 재시작하는 버튼 (앱 아이콘 + 재시작 뱃지) */
    private fun addRestartAppBubble(app: Sticker) {
        val pkg = app.packageName
        val iconDrawable = try {
            packageManager.getApplicationIcon(pkg)
        } catch (e: Exception) {
            prefs.removeRestartApp(pkg)
            return
        }
        val sizePx = dp(prefs.buttonSizeDp)
        val b = Bubble(restart = true, restartPkg = pkg)
        b.iv = ImageView(this).apply {
            setImageDrawable(iconDrawable)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        // 재시작 버튼임을 알리는 작은 뱃지(민트 원 + 재시작 아이콘)
        val badge = ImageView(this).apply {
            setImageResource(R.drawable.ic_refresh)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(this@FloatingService, R.color.mint))
                setStroke(dp(1), 0xFFFFFFFF.toInt())
            }
            val p = dp(2)
            setPadding(p, p, p, p)
        }
        b.frame = FrameLayout(this).apply {
            elevation = dp(5).toFloat()
            val pad = dp(1)
            addView(b.iv, FrameLayout.LayoutParams(sizePx - pad * 2, sizePx - pad * 2, Gravity.CENTER))
            addView(
                badge,
                FrameLayout.LayoutParams(dp(20), dp(20), Gravity.BOTTOM or Gravity.END)
            )
        }
        b.lp = newLayoutParams(sizePx)
        b.frame.alpha = prefs.buttonAlpha / 100f
        b.frame.setOnTouchListener(DragTouchListener(b))
        bubbles.add(b)
        addOverlayView(b)

        val desired = if (app.x < 0 || app.y < 0) autoStickerPos(b) else (app.x to app.y)
        val (cx, cy) = resolveCollision(b, desired.first, desired.second)
        clampAndApply(b, cx, cy)
    }

    // ---------------- 화면 캡쳐 버튼 (실험실) ----------------

    private fun addCaptureBubble() {
        if (bubbles.any { it.capture }) return
        val sizePx = dp(prefs.buttonSizeDp)
        val b = Bubble(capture = true)
        b.iv = ImageView(this).apply {
            setImageResource(R.drawable.ic_camera)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        b.frame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF4A5560.toInt())
                setStroke(dp(2), 0xFFFFFFFF.toInt())
            }
            elevation = dp(6).toFloat()
            addView(b.iv, FrameLayout.LayoutParams(sizePx * 3 / 5, sizePx * 3 / 5, Gravity.CENTER))
        }
        b.lp = newLayoutParams(sizePx)
        b.frame.alpha = prefs.buttonAlpha / 100f
        b.frame.setOnTouchListener(DragTouchListener(b))
        bubbles.add(b)
        addOverlayView(b)

        val (_, h) = screenSize()
        val desired = if (prefs.capturePosX < 0 || prefs.capturePosY < 0) dp(12) to (h * 0.45f).roundToInt()
        else prefs.capturePosX to prefs.capturePosY
        val (cx, cy) = resolveCollision(b, desired.first, desired.second)
        clampAndApply(b, cx, cy)
    }

    /** 화면 캡쳐: 플로팅 버튼을 잠깐 숨기고 접근성으로 캡쳐, 상/하단바를 잘라 저장 */
    private fun captureScreen() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            toast("화면 캡쳐는 안드로이드 11 이상에서만 돼요")
            return
        }
        val ax = RestartAccessibilityService.instance
        if (ax == null) {
            toast("접근성 설정에서 '배민리프레시'를 켜 주세요")
            openSettingsScreen()
            return
        }
        // 플로팅 버튼을 잠깐 숨겼다가 캡쳐 후 다시 보이게 (깜빡임 없이)
        setBubblesVisible(false)
        peeHandler.postDelayed({
            ax.captureScreen { bmp ->
                setBubblesVisible(true)
                if (bmp == null) {
                    toast("화면을 캡쳐하지 못했어요")
                    return@captureScreen
                }
                val cropped = cropSystemBars(bmp)
                val ok = saveScreenshot(cropped)
                if (cropped !== bmp) bmp.recycle()
                toast(if (ok) "화면을 저장했어요 (갤러리 확인)" else "저장하지 못했어요")
            }
        }, 160)
    }

    private fun setBubblesVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.INVISIBLE
        for (b in bubbles) b.frame.visibility = v
    }

    /** 상단 상태바, 하단 내비게이션바 높이를 잘라낸다 */
    private fun cropSystemBars(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val top = systemBarHeight("status_bar_height")
        val bottom = systemBarHeight("navigation_bar_height")
        val newH = src.height - top - bottom
        if (top <= 0 && bottom <= 0) return src
        if (newH <= 0 || top < 0 || top >= src.height) return src
        return try {
            android.graphics.Bitmap.createBitmap(src, 0, top, src.width, newH)
        } catch (e: Exception) {
            src
        }
    }

    private fun systemBarHeight(resName: String): Int {
        val id = resources.getIdentifier(resName, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun saveScreenshot(bmp: android.graphics.Bitmap): Boolean {
        val name = "baeminrefresh_" + System.currentTimeMillis() + ".png"
        return try {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_PICTURES + "/BaeminRefresh"
                    )
                }
            }
            val resolver = contentResolver
            val uri = resolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return false
            resolver.openOutputStream(uri)?.use { out ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            } ?: return false
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------------- 음성 입력 버튼 (실험실) ----------------

    private var recognizer: android.speech.SpeechRecognizer? = null
    private var listening = false
    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    private fun addVoiceBubble() {
        if (bubbles.any { it.voice }) return
        val sizePx = dp(prefs.buttonSizeDp)
        val b = Bubble(voice = true)
        b.iv = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        b.frame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (listening) VOICE_COLOR_ACTIVE else VOICE_COLOR)
                setStroke(dp(2), 0xFFFFFFFF.toInt())
            }
            elevation = dp(6).toFloat()
            addView(b.iv, FrameLayout.LayoutParams(sizePx * 3 / 5, sizePx * 3 / 5, Gravity.CENTER))
        }
        b.lp = newLayoutParams(sizePx)
        b.frame.alpha = prefs.buttonAlpha / 100f
        b.frame.setOnTouchListener(DragTouchListener(b))
        bubbles.add(b)
        addOverlayView(b)

        val (w, _) = screenSize()
        val desired = if (prefs.voicePosX < 0 || prefs.voicePosY < 0) (w - sizePx - dp(12)) to (screenSize().second * 0.45f).roundToInt()
        else prefs.voicePosX to prefs.voicePosY
        val (cx, cy) = resolveCollision(b, desired.first, desired.second)
        clampAndApply(b, cx, cy)
    }

    private fun onVoiceTapped() {
        if (!listening) startVoice() else stopVoice()
    }

    private fun startVoice() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            toast("마이크 권한이 필요해요. 앱 설정에서 허용해 주세요")
            openSettingsScreen()
            return
        }
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("이 기기는 음성 인식을 지원하지 않아요")
            return
        }
        if (RestartAccessibilityService.instance == null) {
            toast("글자 입력에 접근성이 필요해요. 접근성을 켜 주세요")
            openSettingsScreen()
            return
        }
        requestAudioFocus() // 음악 등 다른 소리를 잠깐 멈춰 녹음이 되게
        runCatching { recognizer?.destroy() }
        recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(voiceListener)
        }
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        }
        try {
            recognizer?.startListening(intent)
            listening = true
            // 색은 실제 녹음이 시작되는 onReadyForSpeech 에서 바꾼다
            toast("🎙 말하세요… 끝나면 다시 눌러요")
        } catch (e: Exception) {
            listening = false
            abandonAudioFocus()
            toast("음성 인식을 시작하지 못했어요")
        }
    }

    private fun stopVoice() {
        runCatching { recognizer?.stopListening() }
        listening = false
        // 색/오디오포커스는 결과/오류 콜백에서 정리된다
    }

    private fun setVoiceColor(active: Boolean) {
        val b = bubbles.firstOrNull { it.voice } ?: return
        // 크기 변화 없이 색만 바꾼다 (평소 인디고 → 녹음 중 빨강)
        (b.frame.background as? GradientDrawable)?.setColor(if (active) VOICE_COLOR_ACTIVE else VOICE_COLOR)
    }

    private fun requestAudioFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = android.media.AudioFocusRequest.Builder(
                android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            ).setAudioAttributes(attrs).build()
            runCatching { am.requestAudioFocus(req) }
            audioFocusRequest = req
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                am.requestAudioFocus(
                    null,
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }
        }
    }

    private fun abandonAudioFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { runCatching { am.abandonAudioFocusRequest(it) } }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            runCatching { am.abandonAudioFocus(null) }
        }
    }

    private val voiceListener = object : android.speech.RecognitionListener {
        override fun onResults(results: android.os.Bundle) {
            listening = false
            setVoiceColor(false)
            abandonAudioFocus()
            val list = results.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
            val text = list?.firstOrNull()?.trim()
            if (text.isNullOrEmpty()) {
                toast("인식된 말이 없어요")
                return
            }
            val ok = RestartAccessibilityService.instance?.insertText(text) ?: false
            if (!ok) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("voice", text))
                toast("입력창을 못 찾아 클립보드에 복사했어요")
            }
        }

        override fun onError(error: Int) {
            listening = false
            setVoiceColor(false)
            abandonAudioFocus()
            val msg = when (error) {
                android.speech.SpeechRecognizer.ERROR_NO_MATCH -> "말을 인식하지 못했어요"
                android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "말소리가 없었어요"
                android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한을 허용해 주세요"
                android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "잠시 후 다시 눌러 주세요"
                android.speech.SpeechRecognizer.ERROR_AUDIO -> "마이크를 쓸 수 없어요 (다른 앱이 사용 중일 수 있어요)"
                else -> "음성 인식 실패 (다시 시도)"
            }
            toast(msg)
        }

        override fun onReadyForSpeech(params: android.os.Bundle?) {
            // 실제 녹음 시작 → 색 변경
            setVoiceColor(true)
        }

        override fun onBeginningOfSpeech() {
            setVoiceColor(true)
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            // 말 끝 → 처리 단계, 색 원래대로
            setVoiceColor(false)
        }

        override fun onPartialResults(partialResults: android.os.Bundle?) {}
        override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
    }

    // ---------------- 워터 타이머 (개인 실험 기능) ----------------

    private fun addPeeBubble() {
        if (bubbles.any { it.pee }) return
        val sizePx = dp(prefs.buttonSizeDp)
        val b = Bubble(pee = true)
        b.iv = ImageView(this).apply {
            setImageResource(R.drawable.ic_water_splash)
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = 0.9f
        }
        b.tv = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 10f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
        }
        b.frame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(PEE_COLOR_BLUE)
                setStroke(dp(2), 0xFFFFFFFF.toInt())
            }
            elevation = dp(6).toFloat()
            addView(b.iv, FrameLayout.LayoutParams(sizePx / 2, sizePx / 2, Gravity.CENTER or Gravity.TOP).apply {
                topMargin = dp(5)
            })
            addView(b.tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER or Gravity.BOTTOM).apply {
                bottomMargin = dp(5)
            })
        }
        b.lp = newLayoutParams(sizePx)
        b.frame.alpha = prefs.buttonAlpha / 100f
        b.frame.setOnTouchListener(DragTouchListener(b))
        bubbles.add(b)
        addOverlayView(b)

        val desired = if (prefs.peePosX < 0 || prefs.peePosY < 0) defaultPeePos()
        else prefs.peePosX to prefs.peePosY
        val (cx, cy) = resolveCollision(b, desired.first, desired.second)
        clampAndApply(b, cx, cy)
        updatePeeDisplay()
    }

    private fun defaultPeePos(): Pair<Int, Int> {
        val (_, h) = screenSize()
        return dp(12) to (h * 0.75f).roundToInt()
    }

    private fun resetPee() {
        prefs.bumpPeeCount()
        prefs.peeLastReset = System.currentTimeMillis()
        updatePeeDisplay()
        updatePeeNotification()
    }

    private fun updatePeeDisplay() {
        val text = peeElapsedShort()
        val pee = bubbles.firstOrNull { it.pee }
        pee?.tv?.text = text
        // 3시간 지나면 물방울을 황금색으로
        val gold = prefs.peeLastReset > 0L && peeElapsedMillis() >= PEE_GOLD_MS
        (pee?.frame?.background as? GradientDrawable)?.setColor(
            if (gold) PEE_COLOR_GOLD else PEE_COLOR_BLUE
        )
        // 알림은 1초마다가 아니라 분 단위가 바뀔 때만 갱신
        val now = System.currentTimeMillis()
        if (now - lastPeeNotifyAt >= 30_000L) {
            updatePeeNotification()
            lastPeeNotifyAt = now
        }
    }

    /** 틱/알림 시작·종료를 현재 상태에 맞춘다. */
    private fun updatePeeState() {
        peeHandler.removeCallbacks(peeTick)
        if (Features.PEE && prefs.peeTrackerEnabled && bubbles.any { it.pee }) {
            peeHandler.post(peeTick)
            updatePeeNotification()
        } else {
            cancelPeeNotification()
        }
    }

    private fun peeElapsedMillis(): Long {
        val last = prefs.peeLastReset
        if (last <= 0L) return 0L
        return (System.currentTimeMillis() - last).coerceAtLeast(0L)
    }

    /** 버튼용 짧은 표기: 1시간 미만이면 M:SS, 이상이면 H:MM */
    private fun peeElapsedShort(): String {
        val total = peeElapsedMillis() / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d", h, m) else String.format("%d:%02d", m, s)
    }

    /** 알림용 긴 표기 */
    private fun peeElapsedLong(): String {
        val count = prefs.peeCountToday
        if (prefs.peeLastReset <= 0L) return "오늘 ${count}회 — 버튼을 누르면 시작돼요"
        val total = peeElapsedMillis() / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val elapsed = if (h > 0) "${h}시간 ${m}분" else "${m}분"
        return "오늘 ${count}회 · 마지막 후 $elapsed"
    }

    private fun updatePeeNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(PEE_CHANNEL_ID, "워터 타이머", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "마지막 리셋 후 경과 시간"
                    setShowBadge(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val reset = PendingIntent.getService(
            this, 3, Intent(this, FloatingService::class.java).setAction(ACTION_PEE_RESET),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val waterMode = PendingIntent.getService(
            this, 4, Intent(this, FloatingService::class.java).setAction(ACTION_WATER_MODE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val waterLabel = if (prefs.waterModeEnabled) "워터 모드 끄기" else "워터 모드 켜기"
        val count = prefs.peeCountToday
        val n = NotificationCompat.Builder(this, PEE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_water_splash)
            .setContentTitle("💧 워터 타이머 · 오늘 ${count}회")
            .setContentText(peeElapsedLong())
            .setContentIntent(open)
            .addAction(0, "지금 리셋", reset)
            .addAction(0, waterLabel, waterMode)
            .setNumber(count)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        nm.notify(PEE_NOTIFICATION_ID, n)
    }

    private fun cancelPeeNotification() {
        getSystemService(NotificationManager::class.java).cancel(PEE_NOTIFICATION_ID)
    }

    private var lastPeeNotifyAt = 0L

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
        if (b.pee) {
            b.iv.layoutParams = FrameLayout.LayoutParams(sizePx / 2, sizePx / 2, Gravity.CENTER or Gravity.TOP).apply {
                topMargin = dp(5)
            }
        } else if (b.capture || b.voice) {
            b.iv.layoutParams = FrameLayout.LayoutParams(sizePx * 3 / 5, sizePx * 3 / 5, Gravity.CENTER)
        } else {
            // 기본 재시작(배민)은 작은 아이콘, 앱 스티커/앱 재시작은 아이콘이 꽉 차게
            val iconSize = if (b.isRestart && b.restartPkg == null) sizePx / 2 else sizePx - dp(1) * 2
            b.iv.layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
        }
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
        return when {
            b.restartPkg != null -> {
                val a = prefs.getRestartApps().firstOrNull { it.packageName == b.restartPkg }
                if (a == null || a.x < 0 || a.y < 0) autoStickerPos(b) else a.x to a.y
            }
            b.isRestart ->
                if (prefs.posX < 0 || prefs.posY < 0) defaultRestartPos() else prefs.posX to prefs.posY
            b.pee ->
                if (prefs.peePosX < 0 || prefs.peePosY < 0) defaultPeePos() else prefs.peePosX to prefs.peePosY
            b.capture -> {
                val (_, h) = screenSize()
                if (prefs.capturePosX < 0 || prefs.capturePosY < 0) dp(12) to (h * 0.45f).roundToInt()
                else prefs.capturePosX to prefs.capturePosY
            }
            b.voice -> {
                val (w, h) = screenSize()
                if (prefs.voicePosX < 0 || prefs.voicePosY < 0) (w - dp(prefs.buttonSizeDp) - dp(12)) to (h * 0.45f).roundToInt()
                else prefs.voicePosX to prefs.voicePosY
            }
            else -> {
                val s = prefs.getStickers().firstOrNull { it.packageName == b.launchPkg }
                if (s == null || s.x < 0 || s.y < 0) autoStickerPos(b) else s.x to s.y
            }
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
        val index = bubbles.count { it.isSticker } - 1
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
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var dragging = false
        private var longPressFired = false
        private val longPressRunnable = Runnable {
            longPressFired = true
            onLongPress(bubble)
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val lp = bubble.lp
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = e.rawX
                    touchY = e.rawY
                    dragging = false
                    longPressFired = false
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()
                    // 길게 누르고 있으면 떼지 않아도 바로 적용
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        longPressFired = false
                        handler.removeCallbacks(longPressRunnable) // 움직이면 길게누르기 취소
                        v.alpha = 1f
                    }
                    if (dragging) clampAndApply(bubble, startX + dx.roundToInt(), startY + dy.roundToInt())
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    when {
                        longPressFired -> {
                            // 길게 누르는 중에 이미 처리됨 (떼는 동작은 무시)
                            v.alpha = prefs.buttonAlpha / 100f
                        }
                        dragging -> {
                            v.alpha = prefs.buttonAlpha / 100f
                            val (sx, sy) = computeSnap(bubble)
                            val (tx, ty) = resolveCollision(bubble, sx, sy)
                            savePositionAt(bubble, tx, ty)
                            animateTo(bubble, tx, ty)
                        }
                        else -> {
                            v.performClick()
                            onBubbleTapped(bubble)
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    v.alpha = prefs.buttonAlpha / 100f
                }
            }
            return true
        }
    }

    /** 길게 누르기(떼지 않아도 적용): 워터=모드 토글, 그 외 아이콘=삭제/숨김 */
    private fun onLongPress(b: Bubble) {
        when {
            b.pee -> {
                prefs.waterModeEnabled = !prefs.waterModeEnabled
                updatePeeNotification()
            }
            b.capture -> {
                prefs.captureButtonEnabled = false
                toast("화면 캡쳐 버튼을 지웠어요")
            }
            b.voice -> {
                prefs.voiceButtonEnabled = false
                toast("음성 입력 버튼을 지웠어요")
            }
            b.restartPkg != null -> {
                prefs.removeRestartApp(b.restartPkg)
                toast("재시작 버튼을 지웠어요")
            }
            b.isSticker -> {
                prefs.removeSticker(b.launchPkg!!)
                toast("앱 스티커를 지웠어요")
            }
            b.isRestart -> { // 기본 배민커넥트 재시작 버튼 → 숨김(설정에서 다시 켜기)
                prefs.showMainRestart = false
                toast("재시작 버튼을 숨겼어요. 설정에서 다시 켤 수 있어요")
            }
        }
    }

    private fun savePositionAt(b: Bubble, x: Int, y: Int) {
        when {
            b.restartPkg != null -> prefs.updateRestartAppPosition(b.restartPkg, x, y)
            b.isRestart -> {
                prefs.posX = x
                prefs.posY = y
            }
            b.pee -> {
                prefs.peePosX = x
                prefs.peePosY = y
            }
            b.capture -> {
                prefs.capturePosX = x
                prefs.capturePosY = y
            }
            b.voice -> {
                prefs.voicePosX = x
                prefs.voicePosY = y
            }
            else -> b.launchPkg?.let { prefs.updateStickerPosition(it, x, y) }
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

        var wx: Int
        var wy: Int
        when (min) {
            dl -> { wx = 0; wy = b.lp.y }
            dr -> { wx = (w - size).coerceAtLeast(0); wy = b.lp.y }
            dt -> { wx = b.lp.x; wy = 0 }
            else -> { wx = b.lp.x; wy = (h - size).coerceAtLeast(0) }
        }

        // 좌/우 벽에 붙을 때, 반대편 벽의 버튼과 높이가 비슷하면 그 버튼과 줄(Y)을 맞춘다
        val rightWall = (w - size).coerceAtLeast(0)
        if (wx == 0 || wx == rightWall) {
            val alignTh = dp(prefs.buttonSizeDp * 2 / 3)
            val mate = bubbles.asSequence()
                .filter { it !== b }
                .filter { it.lp.x == 0 || it.lp.x == (w - it.lp.width).coerceAtLeast(0) } // 벽에 붙은 버튼
                .filter { it.lp.x != wx } // 반대편 벽
                .minByOrNull { abs(it.lp.y - wy) }
            if (mate != null && abs(mate.lp.y - wy) <= alignTh) wy = mate.lp.y
        }
        return wx to wy
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
        when {
            b.pee -> resetPee()
            b.capture -> captureScreen()
            b.voice -> onVoiceTapped()
            b.isRestart -> onRestartTapped(b)
            else -> launchApp(b.launchPkg!!)
        }
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
        val pkg = b.restartTarget
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
        val name = appLabel(pkg)
        startSpinner(b)
        service.restart(pkg) { result ->
            stopSpinner(b)
            when (result) {
                RestartAccessibilityService.Result.RESTARTED -> toast("${name}를 재시작했어요")
                RestartAccessibilityService.Result.NOT_RUNNING_LAUNCHED -> toast("${name}를 실행했어요")
                RestartAccessibilityService.Result.TIMEOUT_LAUNCHED ->
                    toast("강제 중지 버튼을 찾지 못해 실행만 했어요")
                RestartAccessibilityService.Result.FAILED ->
                    toast("${name}를 실행하지 못했어요")
            }
        }
    }

    private fun appLabel(pkg: String): String =
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            "앱"
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
        private const val PEE_CHANNEL_ID = "pee_timer"
        private const val PEE_NOTIFICATION_ID = 2
        private const val ACTION_STOP = "com.baeminrefresh.app.STOP"
        private const val ACTION_PEE_RESET = "com.baeminrefresh.app.PEE_RESET"
        private const val ACTION_WATER_MODE = "com.baeminrefresh.app.WATER_MODE"
        private const val LONG_PRESS_MS = 600L
        private const val PEE_GOLD_MS = 3L * 60 * 60 * 1000 // 3시간
        private val PEE_COLOR_BLUE = 0xFF2AA7E0.toInt()
        private val PEE_COLOR_GOLD = 0xFFE6A700.toInt()
        private val VOICE_COLOR = 0xFF5C6BC0.toInt() // 평소: 인디고
        private val VOICE_COLOR_ACTIVE = 0xFFF4323C.toInt() // 녹음 중: 선명한 빨강

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var instance: FloatingService? = null
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, FloatingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingService::class.java))
        }
    }
}
