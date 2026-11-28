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
        val callBoost: Boolean = false,
        val folderId: Int = -1,
        val optimize: Boolean = false,
        /** 일기장 버튼: "diary"(📖 일기) 또는 "idea"(💡 아이디어) */
        val diary: String? = null,
        /** 답변 추천 버튼 (텔레그램·카카오톡·스레드) */
        val reply: Boolean = false,
        /** 화면 녹화 버튼 (녹화 중에는 정지 버튼) */
        val screenRec: Boolean = false,
        /** 볼륨 버튼: +1 = 볼륨 올리기, -1 = 볼륨 내리기, 0 = 아님 */
        val volume: Int = 0,
    ) {
        /** 펼쳐진 폴더 안에 보이는 중이면 그 폴더 id (끌기 불가, 폴더 옆에 자동 배치) */
        var inFolder: Int = -1
        val isFolder: Boolean get() = folderId >= 0

        /** 폴더에 담을 때 쓰는 버튼 식별 키 (폴더·통화증폭 버튼은 null) */
        val key: String? get() = when {
            isFolder || callBoost -> null
            pee -> "pee"
            capture -> "capture"
            voice -> "voice"
            optimize -> "optimize"
            diary != null -> diary
            reply -> "reply"
            screenRec -> "screenrec"
            volume > 0 -> "volup"
            volume < 0 -> "voldown"
            restartPkg != null -> "rapp:$restartPkg"
            restart -> "main"
            launchPkg != null -> "sticker:$launchPkg"
            else -> null
        }
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
    /** 지금 화면에 쓰는 폴더 목록(없어진 버튼은 뺀 것) / 펼쳐진 폴더 id (메모리에만 기억) */
    private var folders: List<Folder> = emptyList()
    private val expanded = mutableSetOf<Int>()
    private val rebuildForSize = Runnable { if (isRunning) buildAll() }
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
        appliedGapDp = prefs.buttonGapDp
        pickOverlayHost()
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "'다른 앱 위에 표시' 권한이 필요해요", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        buildAll()
        prefs.registerListener(this)
        PhoneOptimizer.addListener(optimizeListener)
        ContextCompat.registerReceiver(
            this, diaryStateReceiver, android.content.IntentFilter(DiaryLink.STATE_ACTION),
            ContextCompat.RECEIVER_EXPORTED
        )
        diaryReceiverOn = true
        if (prefs.diaryEnabled) diaryAttach(true)
        isRunning = true
        KeepAlive.start(this)
        runCatching {
            registerReceiver(screenStateReceiver, android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            })
            screenStateOn = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs.floatingEnabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SCREENREC_START) {
            val code = intent.getIntExtra(EXTRA_REC_CODE, 0)
            val data = androidx.core.content.IntentCompat.getParcelableExtra(intent, EXTRA_REC_DATA, Intent::class.java)
            if (data != null) startScreenRec(code, data)
            return START_STICKY
        }
        if (intent?.action == ACTION_SCREENREC_STOP) {
            stopScreenRec(null)
            return START_STICKY
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
        KeepAlive.stop()
        if (screenStateOn) runCatching { unregisterReceiver(screenStateReceiver) }
        screenStateOn = false
        if (instance === this) instance = null
        prefs.unregisterListener(this)
        PhoneOptimizer.removeListener(optimizeListener)
        if (diaryReceiverOn) runCatching { unregisterReceiver(diaryStateReceiver) }
        if (prefs.diaryEnabled) diaryAttach(false) // 우리 버튼이 사라지니 일기장 앱이 자기 버튼을 다시 보여 주게
        peeHandler.removeCallbacks(peeTick)
        cancelPeeNotification()
        if (screenRecording) {
            screenRecording = false
            runCatching { recRecorder?.stop() }
            runCatching { recRecorder?.release() }
            recRecorder = null
            cleanupRec(saved = true)
        }
        voiceWanted = false
        gemRecorder?.let { r ->
            runCatching { r.stop() }
            runCatching { r.release() }
        }
        gemRecorder = null
        unmuteBeep()
        runCatching { recognizer?.destroy() }
        recognizer = null
        abandonAudioFocus()
        removeAllBubbles()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        // 실험실: 가로모드에서는 플로팅 아이콘을 모두 숨기고, 세로로 돌아오면 다시 표시
        if (prefs.labPauseStickersLandscape) {
            val shown = bubbles.isNotEmpty()
            if (landscape == shown) buildAll()
        }
        // 저장된 자리로 되돌린 뒤 서로 겹치지 않게 재배치 (회전 시 겹침 방지)
        repositionAll()
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        when (key) {
            Prefs.KEY_SIZE -> { // 뱃지·글자까지 크기에 맞춰 다시 그린다 (슬라이더 끄는 동안은 모아서 한 번)
                peeHandler.removeCallbacks(rebuildForSize)
                peeHandler.postDelayed(rebuildForSize, 250L)
            }
            Prefs.KEY_ALPHA -> bubbles.forEach { applyAppearance(it) }
            Prefs.KEY_GAP -> respaceStacks()
            Prefs.KEY_X, Prefs.KEY_Y -> bubbles.firstOrNull { it.isRestart && it.restartPkg == null }?.let { b ->
                if (prefs.posX < 0) defaultPosition(b) else clampAndApply(b, prefs.posX, prefs.posY)
            }
            Prefs.KEY_STICKERS -> rebuildStickers()
            Prefs.KEY_RESTART_APPS, Prefs.KEY_FOLDERS, Prefs.KEY_OPT_ENABLED -> buildAll()
            Prefs.KEY_REPLY_ENABLED, Prefs.KEY_SCREENREC_ENABLED, Prefs.KEY_VOLUME_ENABLED -> buildAll()
            Prefs.KEY_DIARY_ENABLED -> {
                diaryAttach(prefs.diaryEnabled)
                buildAll()
            }
            Prefs.KEY_LAB_PAUSE_LANDSCAPE -> buildAll()
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

    /** withMic: 녹음하는 동안 '마이크 사용' 포그라운드 서비스로 올려 다른 앱 위에서도 녹음되게 */
    private fun startAsForeground(withMic: Boolean = false) {
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
        if (withMic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val ok = runCatching {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification, type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            }.isSuccess
            if (ok) return
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    // ---------------- 버튼 생성/제거 ----------------

    /** 워터 모드: 물방울 버튼만 남기고 나머지는 숨긴다. (워터 타이머가 켜져 있을 때만) */
    private fun waterOnly(): Boolean =
        Features.PEE && prefs.peeTrackerEnabled && prefs.waterModeEnabled

    private fun buildAll() {
        removeAllBubbles()
        if (screenRecording) {
            // 녹화 중에는 버튼을 모두 숨긴다. (옵션이 켜졌거나 알림이 꺼져 있어 끝낼 방법이 없으면 ■ 정지 버튼만)
            val notifOff = !androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
            if (prefs.screenRecStopButton || notifOff) addScreenRecBubble()
            return
        }
        if (floatingPaused()) {
            peeHandler.removeCallbacks(peeTick) // 알림은 그대로 둔다
            return
        }
        if (!screenFullyOn()) {
            // 화면 꺼짐·Always On Display·잠금화면에서는 숨긴다 (통화 중 잠금화면이면 통화 증폭 버튼만)
            peeHandler.removeCallbacks(peeTick)
            if (screenInteractive() && callBubbleWanted()) addCallBubble()
            return
        }
        refreshFolders()
        if (!waterOnly()) {
            for (f in folders) addFolderBubble(f)
            if (prefs.showMainRestart) addRestartBubble()
            for (a in prefs.getRestartApps()) addRestartAppBubble(a)
            for (s in prefs.getStickers()) addStickerBubble(s)
        }
        if (Features.PEE && prefs.peeTrackerEnabled) addPeeBubble()
        if (!waterOnly() && prefs.captureButtonEnabled) addCaptureBubble()
        if (!waterOnly() && prefs.voiceButtonEnabled) addVoiceBubble()
        if (!waterOnly() && prefs.optimizeEnabled) addOptimizeBubble()
        if (!waterOnly() && prefs.diaryEnabled) {
            addDiaryBubble(DiaryLink.KIND_DIARY)
            addDiaryBubble(DiaryLink.KIND_IDEA)
        }
        if (!waterOnly() && prefs.replyEnabled) addReplyBubble()
        if (!waterOnly() && prefs.screenRecEnabled) addScreenRecBubble()
        if (!waterOnly() && prefs.volumeButtonsEnabled) {
            addVolumeBubble(+1)
            addVolumeBubble(-1)
        }
        if (callBubbleWanted()) addCallBubble()
        layoutExpandedFolders()
        updatePeeState()
    }

    private fun rebuildStickers() {
        removeStickerBubbles()
        if (!waterOnly() && !floatingPaused()) for (s in prefs.getStickers()) addStickerBubble(s)
        layoutExpandedFolders()
    }

    // ---------------- 폰 최적화 버튼 (실험실) ----------------

    private val optimizeListener = object : PhoneOptimizer.Listener {
        override fun onProgress(done: Int, total: Int) = styleOptimize()
        override fun onFinished() = styleOptimize()
    }

    private fun addOptimizeBubble() {
        if (bubbles.any { it.optimize } || hiddenByFolder("optimize")) return
        val sizePx = dp(prefs.buttonSizeDp)
        val b = Bubble(optimize = true)
        b.iv = ImageView(this).apply {
            setImageResource(R.drawable.ic_bolt)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        b.tv = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = (prefs.buttonSizeDp * 0.27f).coerceIn(7f, 14f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
        }
        b.frame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(OPT_COLOR)
                setStroke(dp(2), 0xFFFFFFFF.toInt())
            }
            elevation = dp(6).toFloat()
            addView(b.iv, FrameLayout.LayoutParams(sizePx * 3 / 5, sizePx * 3 / 5, Gravity.CENTER))
            addView(b.tv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER
            ))
        }
        b.lp = newLayoutParams(sizePx)
        b.frame.alpha = prefs.buttonAlpha / 100f
        b.frame.setOnTouchListener(DragTouchListener(b))
        bubbles.add(b)
        addOverlayView(b)
        styleOptimize()
        val (sx, sy) = savedPosOf(b)
        val (cx, cy) = resolveCollision(b, sx, sy)
        clampAndApply(b, cx, cy)
    }

    /** 평소 주황 → 진행 중 노랑 + 진행 숫자(n/m) → 다 끝나면 파랑 (다음에 시작할 때까지 유지) */
    private fun styleOptimize() {
        val b = bubbles.firstOrNull { it.optimize } ?: return
        val running = PhoneOptimizer.running
        val color = when {
            running -> OPT_RUNNING_COLOR
            PhoneOptimizer.lastCompleted -> OPT_DONE_COLOR
            else -> OPT_COLOR
        }
        (b.frame.background as? GradientDrawable)?.setColor(color)
        b.tv?.setTextColor(0xFF3A3000.toInt()) // 노랑 위에서 잘 보이게 진한 글자
        val label = if (!running) "" else if (PhoneOptimizer.totalCount > 0) "${PhoneOptimizer.doneCount}/${PhoneOptimizer.totalCount}" else "…"
        b.tv?.text = label
        b.iv.visibility = if (label.isEmpty()) View.VISIBLE else View.INVISIBLE
    }

    private fun onOptimizeTapped() {
        if (PhoneOptimizer.running) {
            PhoneOptimizer.cancel()
            toast("최적화를 멈출게요")
            return
        }
        if (prefs.optimizeDeep && RestartAccessibilityService.instance == null) {
            toast("접근성을 켜면 깊게 정리해요. 지금은 가볍게만 정리할게요")
        } else {
            toast("최적화를 시작해요")
        }
        PhoneOptimizer.start(this, prefs.optimizeDeep)
        styleOptimize()
    }

    // ---------------- 일기장 쓰기 버튼 (실험실) ----------------
    // 버튼은 여기서 그리고, 녹음·전송은 일기장 앱이 한다. (DiaryLink 참고)

    private var diaryState = "idle" // idle / recording / sending
    private var diaryKind = ""      // 녹음·전송 중인 버튼 ("diary" / "idea")
    private var diaryReceiverOn = false

    private val diaryStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action != DiaryLink.STATE_ACTION) return
            setDiaryState(i.getStringExtra("state"), i.getStringExtra("kind"))
        }
    }

    private fun setDiaryState(state: String?, kind: String?) {
        diaryState = state ?: "idle"
        diaryKind = kind ?: ""
        styleDiary()
    }

    /** 일기장 앱에 "버튼은 내가 보여 줄게"(on) / "이제 네가 보여 줘"(off)를 알린다. */
    private fun diaryAttach(on: Boolean) {
        val app = applicationContext
        Thread {
            val r = DiaryLink.call(app, if (on) "attach" else "detach")
            if (on && r != null) peeHandler.post { setDiaryState(r.getString("state"), r.getString("kind")) }
        }.start()
    }

    private fun addDiaryBubble(kind: String) {
        if (bubbles.any { it.diary == kind } || hiddenByFolder(kind)) return
        val sizePx = dp(prefs.buttonSizeDp)
        val b = Bubble(diary = kind)
        b.iv = ImageView(this)
        b.tv = TextView(this).apply {
            text = if (kind == DiaryLink.KIND_DIARY) "📖" else "💡"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sizePx * 0.45f)
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
        }
        b.frame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), 0xFFFFFFFF.toInt())
            }
            elevation = dp(6).toFloat()
            addView(b.iv, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
            addView(b.tv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER
            ))
        }
        b.lp = newLayoutParams(sizePx)
        b.frame.alpha = prefs.buttonAlpha / 100f
        b.frame.setOnTouchListener(DragTouchListener(b))
        bubbles.add(b)
        addOverlayView(b)
        styleDiary()
        val (sx, sy) = savedPosOf(b)
        val (cx, cy) = resolveCollision(b, sx, sy)
        clampAndApply(b, cx, cy)
    }

    /** 평소 📖 보라 · 💡 주황 → 녹음 중 빨강 → 보내는 중 파랑 (일기장 앱과 같은 색) */
    private fun styleDiary() {
        for (b in bubbles) {
            val kind = b.diary ?: continue
            val color = when {
                kind != diaryKind || diaryState == "idle" ->
                    if (kind == DiaryLink.KIND_DIARY) DIARY_COLOR else IDEA_COLOR
                diaryState == "recording" -> DIARY_REC_COLOR
                else -> DIARY_SEND_COLOR
            }
            (b.frame.background as? GradientDrawable)?.setColor(color)
        }
    }

    /** 탭 = 녹음 시작/끝. 일기장 앱의 녹음 서비스가 꺼져 있으면 앱을 잠깐 열어 시작한다. */
    private fun onDiaryTapped(kind: String) {
        val app = applicationContext
        Thread {
            val r = DiaryLink.call(app, "toggle", kind)
            val installed = DiaryLink.isInstalled(app)
            peeHandler.post {
                if (!isRunning) return@post
                when {
                    r == null && !installed -> toast("일기장 앱이 설치되어 있지 않아요")
                    r == null -> toast("일기장 앱을 최신 버전으로 업데이트해 주세요")
                    r.getString("result") == "need_open" -> {
                        toast("일기장 앱을 잠깐 열어 녹음을 시작해요")
                        try {
                            startActivity(DiaryLink.activityIntent(record = kind))
                        } catch (e: Exception) {
                            toast("일기장 앱을 열지 못했어요")
                        }
                    }
                    else -> {}
                }
            }
        }.start()
    }

    /** 길게 누르기 = 일기장 앱의 최근 일기(📖) / 아이디어(💡) 페이지 열기 */
    private fun openDiaryPage(kind: String) {
        try {
            startActivity(DiaryLink.activityIntent(open = kind))
        } catch (e: Exception) {
            toast(if (DiaryLink.isInstalled(this)) "일기장 앱을 열지 못했어요" else "일기장 앱이 설치되어 있지 않아요")
        }
    }

    // ---------------- 볼륨 +/− 버튼 (실험실) ----------------
    // 폰 옆 볼륨 키를 누른 것과 같다: 지금 소리 나는 쪽(통화 중이면 통화, 음악·영상이면 미디어, 아니면 기기 기본)을
    // 한 칸 올리거나 내리고, 시스템 볼륨 창을 보여 준다.

    private fun addVolumeBubble(dir: Int) {
        val key = if (dir > 0) "volup" else "voldown"
        if (bubbles.any { it.volume == dir } || hiddenByFolder(key)) return
        val sizePx = dp(prefs.buttonSizeDp)
        val b = Bubble(volume = dir)
        b.iv = ImageView(this)
        b.tv = TextView(this).apply {
            text = if (dir > 0) "+" else "−"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sizePx * 0.6f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
        }
        b.frame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(VOLUME_COLOR)
                setStroke(dp(2), 0xFFFFFFFF.toInt())
            }
            elevation = dp(6).toFloat()
            addView(b.iv, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
            addView(b.tv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER
            ))
        }
        b.lp = newLayoutParams(sizePx)
        b.frame.alpha = prefs.buttonAlpha / 100f
        b.frame.setOnTouchListener(DragTouchListener(b))
        bubbles.add(b)
        addOverlayView(b)
        val (sx, sy) = savedPosOf(b)
        val (cx, cy) = resolveCollision(b, sx, sy)
        clampAndApply(b, cx, cy)
    }

    private fun onVolumeTapped(dir: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        runCatching {
            am.adjustSuggestedStreamVolume(
                if (dir > 0) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER,
                android.media.AudioManager.USE_DEFAULT_STREAM_TYPE,
                android.media.AudioManager.FLAG_SHOW_UI
            )
        }
    }

    // ---------------- 화면 녹화 (실험실) ----------------
    // 탭 → ScreenRecordActivity 가 '화면 녹화 허용'을 받아 ACTION_SCREENREC_START 로 넘김 → 여기서 녹화.
    // 녹화 중에는 플로팅 버튼을 모두 숨기고(옵션: 작은 ■ 정지 버튼만 남김), 알림의 '녹화 끝내기'·화면 끄기·■ 로 끝낸다.
    // 영상은 갤러리 Movies/배민리프레시 에 mp4 로 저장.

    var screenRecording = false
        private set
    private var recProjection: android.media.projection.MediaProjection? = null
    private var recRecorder: android.media.MediaRecorder? = null
    private var recDisplay: android.hardware.display.VirtualDisplay? = null
    private var recUri: android.net.Uri? = null
    private var recPfd: android.os.ParcelFileDescriptor? = null
    private var recFile: java.io.File? = null
    private var recStartedAt = 0L
    private var recScreenOffOn = false

    private val recScreenOff = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == Intent.ACTION_SCREEN_OFF) stopScreenRec("화면이 꺼져서 녹화를 끝냈어요")
        }
    }

    private val recCallback = object : android.media.projection.MediaProjection.Callback() {
        override fun onStop() {
            peeHandler.post { stopScreenRec(null) } // 시스템(상태 표시줄 녹화 표시 등)에서 멈춘 경우
        }
    }

    private fun addScreenRecBubble() {
        if (bubbles.any { it.screenRec }) return
        if (!screenRecording && hiddenByFolder("screenrec")) return
        val sizePx = dp(prefs.buttonSizeDp)
        val b = Bubble(screenRec = true)
        b.iv = ImageView(this).apply {
            setImageResource(if (screenRecording) R.drawable.ic_stop_square else R.drawable.ic_screenrec)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        b.frame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (screenRecording) VOICE_COLOR_ACTIVE else REC_COLOR)
                setStroke(dp(2), 0xFFFFFFFF.toInt())
            }
            elevation = dp(6).toFloat()
            addView(b.iv, FrameLayout.LayoutParams(sizePx * 3 / 5, sizePx * 3 / 5, Gravity.CENTER))
        }
        b.lp = newLayoutParams(sizePx)
        // 녹화 중 정지 버튼은 영상에 덜 보이게 반투명
        b.frame.alpha = if (screenRecording) 0.45f else prefs.buttonAlpha / 100f
        b.frame.setOnTouchListener(DragTouchListener(b))
        bubbles.add(b)
        addOverlayView(b)
        val (sx, sy) = savedPosOf(b)
        val (cx, cy) = resolveCollision(b, sx, sy)
        clampAndApply(b, cx, cy)
    }

    private fun onScreenRecTapped() {
        if (screenRecording) {
            stopScreenRec(null)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            toast("화면 녹화는 안드로이드 10 이상에서 돼요")
            return
        }
        try {
            startActivity(
                Intent(this, ScreenRecordActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        } catch (e: Exception) {
            toast("녹화 허용 창을 열지 못했어요")
        }
    }

    private fun recNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(REC_CHANNEL_ID, "화면 녹화", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "화면 녹화 중 표시와 저장 알림"
                    setShowBadge(false)
                }
            )
        }
        val stop = PendingIntent.getService(
            this, 7, Intent(this, FloatingService::class.java).setAction(ACTION_SCREENREC_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, REC_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_screenrec)
            .setContentTitle("● 화면 녹화 중")
            .setContentText("눌러서 녹화 끝내기 · 화면을 꺼도 끝나요")
            .setContentIntent(stop)
            .addAction(0, "■ 녹화 끝내기", stop)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
            .build()
    }

    private fun startScreenRec(code: Int, data: Intent) {
        if (screenRecording) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val withMic = prefs.screenRecMic && ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        // 안드로이드 14+: 화면 녹화용 포그라운드 서비스로 먼저 올라가야 녹화 권한을 받을 수 있다
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (withMic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, recNotification(), type)
        } catch (e: Exception) {
            toast("화면 녹화를 시작할 수 없어요")
            runCatching { startAsForeground() }
            return
        }
        val mpm = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        val proj = runCatching { mpm.getMediaProjection(code, data) }.getOrNull()
        if (proj == null) {
            toast("화면 녹화 허용을 받지 못했어요")
            runCatching { startAsForeground() }
            return
        }
        proj.registerCallback(recCallback, peeHandler)
        recProjection = proj

        // 영상 크기: 화면 비율 그대로, 긴 쪽 최대 1920 (짝수)
        val (sw, sh) = realScreenSize()
        val scale = minOf(1f, 1920f / maxOf(sw, sh))
        val vw = ((sw * scale).toInt() / 2) * 2
        val vh = ((sh * scale).toInt() / 2) * 2
        val dpi = resources.displayMetrics.densityDpi

        // 저장 위치: 갤러리 Movies/배민리프레시
        val name = "배민리프레시_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".mp4"
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) android.media.MediaRecorder(this)
        else @Suppress("DEPRECATION") android.media.MediaRecorder()
        try {
            if (withMic) rec.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            rec.setVideoSource(android.media.MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/배민리프레시")
                put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(
                android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY), values
            ) ?: throw IllegalStateException("no uri")
            recUri = uri
            val pfd = contentResolver.openFileDescriptor(uri, "w") ?: throw IllegalStateException("no fd")
            recPfd = pfd
            rec.setOutputFile(pfd.fileDescriptor)
            rec.setVideoEncoder(android.media.MediaRecorder.VideoEncoder.H264)
            if (withMic) {
                rec.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                rec.setAudioEncodingBitRate(128_000)
                rec.setAudioSamplingRate(44_100)
            }
            rec.setVideoSize(vw, vh)
            rec.setVideoFrameRate(30)
            rec.setVideoEncodingBitRate(8_000_000)
            rec.prepare()
        } catch (e: Exception) {
            runCatching { rec.release() }
            cleanupRec(saved = false)
            toast("녹화 준비에 실패했어요")
            return
        }
        recRecorder = rec
        screenRecording = true
        recStartedAt = System.currentTimeMillis()
        // 버튼을 먼저 숨기고, 허용 창이 사라질 시간을 잠깐 둔 뒤 녹화 시작 (버튼·허용 창이 영상에 안 찍히게)
        buildAll()
        if (!recScreenOffOn) {
            ContextCompat.registerReceiver(
                this, recScreenOff, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED
            )
            recScreenOffOn = true
        }
        peeHandler.postDelayed({
            if (!screenRecording || recRecorder !== rec) return@postDelayed
            try {
                recDisplay = proj.createVirtualDisplay(
                    "BaeminRefreshScreenRec", vw, vh, dpi,
                    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    rec.surface, null, null
                )
                rec.start()
                recStartedAt = System.currentTimeMillis()
            } catch (e: Exception) {
                toast("녹화를 시작하지 못했어요")
                stopScreenRec(null, failed = true)
            }
        }, 500L)
    }

    /** 녹화를 끝내고 저장한다. message: 끝난 이유 안내(없으면 기본 문구) */
    private fun stopScreenRec(message: String?, failed: Boolean = false) {
        if (!screenRecording) return
        screenRecording = false
        val rec = recRecorder
        recRecorder = null
        val stoppedOk = !failed && rec != null && runCatching { rec.stop() }.isSuccess
        runCatching { rec?.release() }
        cleanupRec(saved = stoppedOk)
        runCatching { startAsForeground() }
        buildAll()
        if (stoppedOk) {
            toast(message ?: "녹화를 저장했어요 (갤러리 › Movies › 배민리프레시)")
        } else if (!failed) {
            toast("녹화가 너무 짧아 저장하지 못했어요")
        }
    }

    private fun cleanupRec(saved: Boolean) {
        runCatching { recDisplay?.release() }
        recDisplay = null
        recProjection?.let { p ->
            runCatching { p.unregisterCallback(recCallback) }
            runCatching { p.stop() }
        }
        recProjection = null
        runCatching { recPfd?.close() }
        recPfd = null
        if (recScreenOffOn) {
            runCatching { unregisterReceiver(recScreenOff) }
            recScreenOffOn = false
        }
        val uri = recUri
        recUri = null
        if (uri != null) {
            if (saved) {
                runCatching {
                    val v = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    contentResolver.update(uri, v, null, null)
                }
                notifySaved(uri)
            } else {
                runCatching { contentResolver.delete(uri, null, null) }
            }
        }
    }

    /** '녹화 저장됨' 알림 — 누르면 영상 재생 */
    private fun notifySaved(uri: android.net.Uri) {
        runCatching {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(this, 8, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val secs = ((System.currentTimeMillis() - recStartedAt) / 1000).coerceAtLeast(0)
            val n = NotificationCompat.Builder(this, REC_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_screenrec)
                .setContentTitle("화면 녹화를 저장했어요 (${secs / 60}분 ${secs % 60}초)")
                .setContentText("눌러서 보기 · 갤러리 › Movies › 배민리프레시")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(REC_SAVED_NOTIFICATION_ID, n)
        }
    }

    /** 상태 표시줄까지 포함한 실제 화면 크기(픽셀) */
    private fun realScreenSize(): Pair<Int, Int> {
        val w = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = w.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            w.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    // ---------------- 답변 추천 버튼 (실험실) ----------------
    // 탭 → 접근성으로 텔레그램·카카오톡 화면의 대화를 읽고 → 제미나이로 답장을 받아 → 입력창에 넣는다(전송 안 함).
    // 같은 대화에서 한 번 더 누르면 앞의 추천과 다른 답장으로 바꾼다.

    private var replyBusy = false
    private var replyCtx = ""
    private val replyPrev = mutableListOf<String>()

    private fun addReplyBubble() {
        if (bubbles.any { it.reply } || hiddenByFolder("reply")) return
        val sizePx = dp(prefs.buttonSizeDp)
        val b = Bubble(reply = true)
        b.iv = ImageView(this).apply {
            setImageResource(R.drawable.ic_chat)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        b.frame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
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
        styleReply()
        val (sx, sy) = savedPosOf(b)
        val (cx, cy) = resolveCollision(b, sx, sy)
        clampAndApply(b, cx, cy)
    }

    /** 평소 청록 → 답변을 만드는 중 노랑 */
    private fun styleReply() {
        val b = bubbles.firstOrNull { it.reply } ?: return
        (b.frame.background as? GradientDrawable)?.setColor(if (replyBusy) OPT_RUNNING_COLOR else REPLY_COLOR)
    }

    private fun longToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun onReplyTapped() {
        if (replyBusy) {
            toast("답변을 만드는 중이에요")
            return
        }
        val ax = RestartAccessibilityService.instance
        if (ax == null) {
            longToast("접근성을 켜야 대화를 읽을 수 있어요 (배민리프레시 앱에서 켜 주세요)")
            return
        }
        if (!ReplyAi.hasBundledKeys()) {
            longToast("이 빌드에는 API 키가 들어 있지 않아요")
            return
        }
        val pw = prefs.replyPassword
        if (pw.isEmpty()) {
            longToast("배민리프레시 앱 실험실의 '답변 추천'에서 비밀번호를 먼저 입력해 주세요")
            return
        }
        val chat = ax.readChat()
        if (chat == null) {
            longToast("텔레그램·카카오톡 대화방이나 스레드 답글 화면에서 눌러 주세요")
            return
        }
        if (chat.text.isBlank()) {
            longToast(
                if (chat.mode == "thread") {
                    if (chat.target.isEmpty()) "답글을 달 글을 찾지 못했어요. 답글 쓰는 칸이 보이는 화면에서 눌러 주세요"
                    else {
                        prefs.replyLastDebug = "${chat.target}님 글을 못 찾음 · 화면에서 찾은 글자: ${chat.debug}"
                        "${chat.target}님의 글을 찾지 못했어요. 배민리프레시 앱의 '답변 추천' 칸에 화면 글자를 남겨 뒀어요"
                    }
                } else "화면에서 대화 내용을 찾지 못했어요"
            )
            return
        }
        // 같은 대화 화면이면 앞의 추천을 기억해 두고 이번에는 다르게 받는다
        val ctx = chat.pkg + ":" + chat.text.takeLast(300).hashCode()
        if (ctx != replyCtx) {
            replyCtx = ctx
            replyPrev.clear()
        }
        val draft = if (chat.draft in replyPrev) "" else chat.draft // 입력창에 남은 지난 추천은 초안이 아님
        val prev = replyPrev.toList()
        replyBusy = true
        styleReply()
        Thread {
            val r = ReplyAi.suggest(pw, chat.pkg, chat.text, draft, prev, chat.mode, chat.target)
            peeHandler.post {
                replyBusy = false
                styleReply()
                if (!isRunning) return@post
                val text = r.text
                if (text == null) {
                    longToast(r.error ?: "답변을 받지 못했어요")
                    return@post
                }
                replyPrev.add(text)
                if (replyPrev.size > 5) replyPrev.removeAt(0)
                val axNow = RestartAccessibilityService.instance
                if (axNow == null) {
                    longToast("입력창을 찾지 못했어요")
                    return@post
                }
                axNow.fillReply(text) { ok -> if (!ok) longToast("입력창을 찾지 못했어요") }
            }
        }.start()
    }

    // ---------------- 폴더 ----------------

    private fun validFolderKeys(): Set<String> = buildSet {
        if (prefs.showMainRestart) add("main")
        prefs.getRestartApps().forEach { add("rapp:${it.packageName}") }
        prefs.getStickers().forEach { add("sticker:${it.packageName}") }
        if (prefs.captureButtonEnabled) add("capture")
        if (prefs.voiceButtonEnabled) add("voice")
        if (prefs.optimizeEnabled) add("optimize")
        if (prefs.diaryEnabled) {
            add(DiaryLink.KIND_DIARY)
            add(DiaryLink.KIND_IDEA)
        }
        if (prefs.replyEnabled) add("reply")
        if (prefs.screenRecEnabled) add("screenrec")
        if (prefs.volumeButtonsEnabled) {
            add("volup")
            add("voldown")
        }
        if (Features.PEE && prefs.peeTrackerEnabled) add("pee")
    }

    /** 저장된 폴더에서 없어진 버튼을 뺀 목록을 만든다. (저장 정리는 잠시 뒤 따로 — 재구성 중 중첩 방지) */
    private fun refreshFolders() {
        val valid = validFolderKeys()
        val raw = prefs.getFolders()
        val cleaned = raw.map { f -> f.copy(members = f.members.filter { it in valid }) }
            .filter { it.members.size >= 2 }
        if (cleaned != raw) peeHandler.post { prefs.pruneFolders(validFolderKeys()) }
        folders = if (waterOnly()) emptyList() else cleaned // 워터 모드에서는 폴더 무시
        expanded.retainAll(folders.map { it.id }.toSet())
    }

    /** 닫힌 폴더 안에 들어 있어서 화면에 올리지 않는 버튼인지 */
    private fun hiddenByFolder(key: String): Boolean =
        folders.any { key in it.members && it.id !in expanded }

    private fun defaultFolderPos(): Pair<Int, Int> = dp(12) to (screenSize().second * 0.5f).roundToInt()

    private fun addFolderBubble(f: Folder) {
        val sizePx = dp(prefs.buttonSizeDp)
        val b = Bubble(folderId = f.id)
        b.iv = ImageView(this).apply {
            setImageResource(R.drawable.ic_folder)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val badge = (sizePx * 0.4f).toInt().coerceAtLeast(dp(12))
        b.tv = TextView(this).apply {
            text = f.members.size.toString()
            setTextColor(0xFFFFFFFF.toInt())
            textSize = (prefs.buttonSizeDp * 0.22f).coerceIn(6f, 12f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF2F3A44.toInt())
                setStroke(dp(1), 0xFFFFFFFF.toInt())
            }
        }
        b.frame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(FOLDER_COLOR)
                setStroke(dp(2), 0xFFFFFFFF.toInt())
            }
            elevation = dp(6).toFloat()
            addView(b.iv, FrameLayout.LayoutParams(sizePx * 3 / 5, sizePx * 3 / 5, Gravity.CENTER))
            addView(b.tv, FrameLayout.LayoutParams(badge, badge, Gravity.BOTTOM or Gravity.END))
        }
        styleFolder(b)
        b.lp = newLayoutParams(sizePx)
        b.frame.alpha = prefs.buttonAlpha / 100f
        b.frame.setOnTouchListener(DragTouchListener(b))
        bubbles.add(b)
        addOverlayView(b)
        val (sx, sy) = savedPosOf(b)
        val (cx, cy) = resolveCollision(b, sx, sy)
        clampAndApply(b, cx, cy)
    }

    /** 펼쳐져 있으면 민트색, 닫혀 있으면 회청색 */
    private fun styleFolder(b: Bubble) {
        val c = if (b.folderId in expanded) ContextCompat.getColor(this, R.color.mint) else FOLDER_COLOR
        (b.frame.background as? GradientDrawable)?.setColor(c)
    }

    private fun toggleFolder(id: Int) {
        if (id in expanded) collapseFolder(id) else expandFolder(id)
    }

    private fun expandFolder(id: Int) {
        val f = folders.firstOrNull { it.id == id } ?: return
        expanded.add(id)
        for (k in f.members) if (bubbles.none { it.key == k }) addBubbleByKey(k)
        layoutFolder(f)
        bubbles.firstOrNull { it.folderId == id }?.let { styleFolder(it) }
        updatePeeState()
    }

    private fun collapseFolder(id: Int) {
        val f = folders.firstOrNull { it.id == id } ?: return
        expanded.remove(id)
        val toRemove = bubbles.filter { b -> b.key?.let { it in f.members } == true }
        for (b in toRemove) {
            b.spinner?.cancel()
            runCatching { wm.removeView(b.frame) }
        }
        bubbles.removeAll(toRemove.toSet())
        bubbles.firstOrNull { it.folderId == id }?.let { styleFolder(it) }
        updatePeeState()
    }

    private fun addBubbleByKey(key: String) {
        when {
            key == "main" -> if (prefs.showMainRestart) addRestartBubble()
            key.startsWith("rapp:") ->
                prefs.getRestartApps().firstOrNull { it.packageName == key.removePrefix("rapp:") }?.let { addRestartAppBubble(it) }
            key.startsWith("sticker:") ->
                prefs.getStickers().firstOrNull { it.packageName == key.removePrefix("sticker:") }?.let { addStickerBubble(it) }
            key == "capture" -> addCaptureBubble()
            key == "voice" -> addVoiceBubble()
            key == "optimize" -> addOptimizeBubble()
            key == "reply" -> if (prefs.replyEnabled) addReplyBubble()
            key == "screenrec" -> if (prefs.screenRecEnabled) addScreenRecBubble()
            key == "volup" -> if (prefs.volumeButtonsEnabled) addVolumeBubble(+1)
            key == "voldown" -> if (prefs.volumeButtonsEnabled) addVolumeBubble(-1)
            key == DiaryLink.KIND_DIARY || key == DiaryLink.KIND_IDEA -> if (prefs.diaryEnabled) addDiaryBubble(key)
            key == "pee" -> addPeeBubble()
        }
    }

    /** 펼친 폴더의 버튼들을 폴더 바로 아래(공간이 없으면 위)에 한 줄로 세운다. */
    private fun layoutFolder(f: Folder) {
        val fb = bubbles.firstOrNull { it.folderId == f.id } ?: return
        val members = f.members.mapNotNull { k -> bubbles.firstOrNull { it.key == k } }
        if (members.isEmpty()) return
        val (_, h) = screenSize()
        val size = fb.lp.width
        val gap = dp(prefs.buttonGapDp).coerceAtLeast(dp(2))
        val down = folderStacksDown(fb, members.size)
        members.forEachIndexed { i, m ->
            m.inFolder = f.id
            val y = if (down) fb.lp.y + (i + 1) * (size + gap) else fb.lp.y - (i + 1) * (size + gap)
            clampAndApply(m, fb.lp.x, y)
        }
    }

    /** 폴더 아래로 펼칠 자리가 있으면 true, 없으면 위로 펼친다. */
    private fun folderStacksDown(fb: Bubble, count: Int): Boolean {
        val (_, h) = screenSize()
        val size = fb.lp.width
        val gap = dp(prefs.buttonGapDp).coerceAtLeast(dp(2))
        return fb.lp.y + (count + 1) * (size + gap) - gap <= h
    }

    /** 펼친 폴더 안에서 버튼을 위아래로 끌어 놓았을 때: 놓은 높이 순서대로 폴더 안 순서를 바꾼다. */
    private fun reorderFolderMember(b: Bubble) {
        val f = folders.firstOrNull { it.id == b.inFolder } ?: return
        val fb = bubbles.firstOrNull { it.folderId == f.id } ?: return
        val shown = f.members.mapNotNull { k -> bubbles.firstOrNull { it.key == k } }
        val byY = shown.sortedBy { it.lp.y }.let { if (folderStacksDown(fb, shown.size)) it else it.reversed() }
        val newOrder = byY.mapNotNull { it.key }
        val order = newOrder + f.members.filter { it !in newOrder }
        if (order != f.members) prefs.setFolderMembers(f.id, order) // 저장되면 다시 그려지며 새 순서로 정렬
        else layoutFolder(f) // 순서가 그대로면 제자리로
    }

    /** 폴더를 두 번 누르면: 이 폴더에 넣을 앱 스티커를 고르는 창을 띄운다. */
    private fun onFolderDoubleTap(id: Int) {
        val intent = Intent(this, FolderPickerActivity::class.java)
            .putExtra(FolderPickerActivity.EXTRA_FOLDER_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("앱 고르는 창을 열지 못했어요")
        }
    }

    private fun layoutExpandedFolders() {
        for (f in folders) if (f.id in expanded) layoutFolder(f)
    }

    /** 폴더를 없애고 버튼들을 폴더가 있던 자리에 차례로 내놓는다. */
    private fun dissolveFolder(id: Int) {
        val f = prefs.getFolders().firstOrNull { it.id == id } ?: return
        val fb = bubbles.firstOrNull { it.folderId == id }
        val (_, h) = screenSize()
        val size = dp(prefs.buttonSizeDp)
        val gap = dp(prefs.buttonGapDp).coerceAtLeast(dp(2))
        val fx = fb?.lp?.x ?: dp(12)
        val fy = fb?.lp?.y ?: dp(100)
        val total = f.members.size * (size + gap) - gap
        val startY = minOf(fy, h - total).coerceAtLeast(0)
        expanded.remove(id)
        f.members.forEachIndexed { i, k -> prefs.setSavedPosOfKey(k, fx, startY + i * (size + gap)) }
        prefs.removeFolder(id) // 마지막에 지워서 한 번에 다시 그린다
        toast("폴더를 풀었어요")
    }

    /** 실험실 설정이 켜져 있고 지금 가로모드면 플로팅 아이콘을 하나도 띄우지 않는다. */
    private fun floatingPaused(): Boolean = prefs.labPauseStickersLandscape && isLandscape()

    // ---------------- 화면 상태: 잠금이 풀려 화면이 완전히 켜졌을 때만 버튼을 보인다 ----------------

    private fun screenInteractive(): Boolean =
        runCatching { (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive }.getOrDefault(true)

    private fun screenFullyOn(): Boolean {
        if (!screenInteractive()) return false // 화면 꺼짐·Always On Display
        val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        return !runCatching { km.isKeyguardLocked }.getOrDefault(false) // 잠금화면
    }

    private var screenStateOn = false
    private val screenStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (!isRunning) return
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> buildAll()
                // 잠금이 없는 폰은 켜지자마자, 잠금이 있으면 풀린 뒤(USER_PRESENT)에 보인다
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> peeHandler.postDelayed({ if (isRunning) buildAll() }, 300L)
            }
        }
    }

    private fun removeStickerBubbles() {
        val toRemove = bubbles.filter { it.isSticker }
        for (b in toRemove) {
            b.spinner?.cancel()
            runCatching { wm.removeView(b.frame) }
        }
        bubbles.removeAll(toRemove)
    }


    private fun removeAllBubbles() {
        for (b in bubbles) {
            b.spinner?.cancel()
            runCatching { wm.removeView(b.frame) }
        }
        bubbles.clear()
    }

    private fun addRestartBubble() {
        if (hiddenByFolder("main")) return
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
        if (hiddenByFolder("sticker:$pkg")) return
        val iconDrawable = TargetApps.iconOf(this, pkg)
        if (iconDrawable == null) {
            // 앱이 삭제됨 → 스티커 정리 (다른 프로필이 잠깐 꺼진 경우는 지우지 않고 건너뜀)
            if (!TargetApps.exists(this, pkg)) prefs.removeSticker(pkg)
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
        if (hiddenByFolder("rapp:$pkg")) return
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
                (sizePx * 0.4f).toInt().coerceAtLeast(dp(12)).let { bs ->
                    FrameLayout.LayoutParams(bs, bs, Gravity.BOTTOM or Gravity.END)
                }
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
        if (bubbles.any { it.capture } || hiddenByFolder("capture")) return
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
    /** 사용자가 다시 누를 때까지 녹음을 이어가는 중인지 (인식기가 말 끊김으로 멈춰도 자동 재시작) */
    private var voiceWanted = false
    /** 녹음 세션이 진행 중(입력 전)인지. 끝난 뒤 늦게 오는 콜백은 무시한다. */
    private var voiceSession = false
    private var voiceEmptyStreak = 0
    private var voiceFailStreak = 0
    /** 인식 중간 결과(아직 확정 전). 인식기가 확정 결과 없이 끊겨도 말한 내용을 살리려고 쓴다. */
    private var voicePartial = ""
    /** 녹음한 말을 모아 두었다가, 다시 누르면 한 번에 입력한다 (중간중간 끊겨 들어가는 것 방지) */
    private val voiceBuffer = mutableListOf<String>()
    /** 안드로이드 13+ 연속 인식(구간 나눔 없이 길게 듣기) 지원 여부: null=아직 모름 */
    private var segmentedOk: Boolean? = null
    private var usingSegmented = false
    private var voiceSessionReady = false
    private var lastVoiceActivity = 0L
    private val voiceWatchdog = object : Runnable {
        override fun run() {
            if (!voiceWanted) return
            if (System.currentTimeMillis() - lastVoiceActivity > VOICE_IDLE_STOP_MS) {
                toast("말소리가 없어 녹음을 멈췄어요")
                stopVoice()
                return
            }
            peeHandler.postDelayed(this, 5000L)
        }
    }
    private val voiceFlushFallback = Runnable { if (!voiceWanted) flushVoice() }
    /** 인식기가 다시 시작할 때마다 나는 '띵/뚝' 소리를 막으려고 잠깐 음소거한 소리 종류 */
    private val mutedStreams = mutableSetOf<Int>()
    private val unmuteRunnable = Runnable { unmuteBeep() }

    private fun muteBeep() {
        peeHandler.removeCallbacks(unmuteRunnable)
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        for (st in BEEP_STREAMS) {
            if (st in mutedStreams) continue
            runCatching {
                if (!am.isStreamMute(st)) {
                    am.adjustStreamVolume(st, android.media.AudioManager.ADJUST_MUTE, 0)
                    mutedStreams.add(st)
                }
            }
        }
    }

    private fun unmuteBeep() {
        peeHandler.removeCallbacks(unmuteRunnable)
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        for (st in mutedStreams) {
            runCatching { am.adjustStreamVolume(st, android.media.AudioManager.ADJUST_UNMUTE, 0) }
        }
        mutedStreams.clear()
    }
    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    private fun addVoiceBubble() {
        if (bubbles.any { it.voice } || hiddenByFolder("voice")) return
        val sizePx = dp(prefs.buttonSizeDp)
        val b = Bubble(voice = true)
        b.iv = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        b.frame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (voiceWanted || gemRecorder != null) VOICE_COLOR_ACTIVE else VOICE_COLOR)
                setStroke(dp(2), 0xFFFFFFFF.toInt())
            }
            elevation = dp(6).toFloat()
            addView(b.iv, FrameLayout.LayoutParams(sizePx * 3 / 5, sizePx * 3 / 5, Gravity.CENTER))
        }
        // 제미나이 받아쓰기 녹음 중에 경과 시간(분:초)을 보여 줄 글자
        b.tv = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = (prefs.buttonSizeDp * 0.24f).coerceIn(7f, 14f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
        }
        b.frame.addView(b.tv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER
        ))
        if (gemRecorder != null) b.iv.visibility = View.INVISIBLE
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

    // ---------------- 통화 증폭 버튼 (실험실, 통화 중에만 보임) ----------------

    private fun callBubbleWanted(): Boolean =
        prefs.callBoostEnabled && RestartAccessibilityService.instance?.callVolume?.inCall == true

    /** 통화 시작/끝, 증폭 상태가 바뀌면 CallVolume 이 부른다 */
    fun onCallBoostChanged() {
        if (!isRunning) return
        val want = callBubbleWanted() && !floatingPaused()
        val b = bubbles.firstOrNull { it.callBoost }
        if (want && b == null) addCallBubble()
        else if (!want && b != null) {
            runCatching { wm.removeView(b.frame) }
            bubbles.remove(b)
        } else if (b != null) styleCallBubble(b)
    }

    private fun addCallBubble() {
        if (bubbles.any { it.callBoost }) return
        val sizePx = dp(prefs.buttonSizeDp)
        val b = Bubble(callBoost = true)
        b.iv = ImageView(this)
        b.tv = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = (11f * prefs.buttonSizeDp / 40f).coerceIn(7f, 16f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
        }
        b.frame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), 0xFFFFFFFF.toInt())
            }
            elevation = dp(6).toFloat()
            addView(b.iv, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
            addView(b.tv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER
            ))
        }
        styleCallBubble(b)
        b.lp = newLayoutParams(sizePx)
        b.frame.alpha = prefs.buttonAlpha / 100f
        b.frame.setOnTouchListener(DragTouchListener(b))
        bubbles.add(b)
        addOverlayView(b)
        val (cx, cy) = resolveCollision(b, savedPosOf(b).first, savedPosOf(b).second)
        clampAndApply(b, cx, cy)
    }

    /** 색과 글자로 상태 표시: 회색=끔, 주황=확인 중, 초록=증폭 중, 빨강=켰지만 효과 확인 안 됨 */
    private fun styleCallBubble(b: Bubble) {
        val st = RestartAccessibilityService.instance?.callVolume?.status ?: CallVolume.Status.OFF
        val (color, text) = when (st) {
            CallVolume.Status.OFF -> 0xFF8A9099.toInt() to "끔"
            CallVolume.Status.CHECKING -> 0xFFF0A020.toInt() to "증폭?"
            CallVolume.Status.WORKING -> 0xFF22B36A.toInt() to "증폭"
            CallVolume.Status.NOT_DETECTED -> 0xFFE0564B.toInt() to "증폭✕"
        }
        (b.frame.background as? GradientDrawable)?.setColor(color)
        b.tv?.text = text
    }

    private fun onCallBubbleTapped() {
        prefs.callBoostActive = !prefs.callBoostActive
        RestartAccessibilityService.instance?.callVolume?.applyNow()
        toast(if (prefs.callBoostActive) "통화 증폭 켰어요" else "통화 증폭 껐어요")
    }

    private fun onVoiceTapped() {
        if (voiceWanted) {
            stopVoice()
            return
        }
        if (gemRecorder != null) {
            stopGeminiVoice()
            return
        }
        if (gemBusy) {
            toast("받아쓰는 중이에요. 잠시만요")
            return
        }
        if (prefs.voiceGemini && ReplyAi.hasBundledKeys() && prefs.replyPassword.isNotEmpty()) {
            // 지난번에 못 보낸 녹음이 있으면 그것부터 받아쓴다
            val pending = gemPendingFiles()
            if (pending.isNotEmpty()) {
                toast("지난번 녹음을 먼저 받아써요")
                transcribeGemini(pending)
                return
            }
            startGeminiVoice()
            return
        }
        if (prefs.voiceGemini) toast("제미나이 받아쓰기는 '답변 추천' 칸에 비밀번호를 넣어야 해요. 지금은 폰 음성 인식으로 할게요")
        startVoice()
    }

    // ---------------- 음성 입력: 녹음 → 임시 파일 → 제미나이 받아쓰기 ----------------
    // 폰 음성 인식처럼 말이 멈춘다고 끊기지 않고, 다시 누를 때까지 계속 녹음한다(최대 2시간).
    // 녹음은 앱 안 임시 폴더에 두고, 받아쓰기에 성공하면 지운다. 실패하면 남겨 두고 다음에 누를 때 먼저 다시 보낸다.

    private var gemRecorder: android.media.MediaRecorder? = null
    private var gemFile: java.io.File? = null
    private var gemBusy = false
    private var gemStartedAt = 0L
    private var gemPeak = 0
    private val gemTick = object : Runnable {
        override fun run() {
            val r = gemRecorder ?: return
            gemPeak = maxOf(gemPeak, runCatching { r.maxAmplitude }.getOrDefault(0))
            val sec = ((System.currentTimeMillis() - gemStartedAt) / 1000).toInt()
            bubbles.firstOrNull { it.voice }?.let { b ->
                b.iv.visibility = View.INVISIBLE
                b.tv?.text = String.format(java.util.Locale.US, "%d:%02d", sec / 60, sec % 60)
            }
            if (sec >= GEM_MAX_SEC) {
                toast("2시간이 넘어 녹음을 멈추고 받아써요")
                stopGeminiVoice()
                return
            }
            peeHandler.postDelayed(this, 500L)
        }
    }

    private fun gemDir(): java.io.File = java.io.File(filesDir, "voice_pending").apply { mkdirs() }

    private fun gemPendingFiles(): List<java.io.File> =
        (gemDir().listFiles { f -> f.name.endsWith(".aac") && f.length() > 0 } ?: emptyArray()).sortedBy { it.name }

    private fun startGeminiVoice() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            toast("마이크 권한이 필요해요. 앱 설정에서 허용해 주세요")
            openSettingsScreen()
            return
        }
        if (RestartAccessibilityService.instance == null) {
            toast("글자 입력에 접근성이 필요해요. 접근성을 켜 주세요")
            openSettingsScreen()
            return
        }
        runCatching { startAsForeground(withMic = true) }
        val f = java.io.File(gemDir(), "${System.currentTimeMillis()}.aac")
        @Suppress("DEPRECATION")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) android.media.MediaRecorder(this)
        else android.media.MediaRecorder()
        try {
            rec.setAudioSource(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION)
            rec.setOutputFormat(android.media.MediaRecorder.OutputFormat.AAC_ADTS)
            rec.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            rec.setAudioChannels(1)
            rec.setAudioSamplingRate(16000)
            rec.setAudioEncodingBitRate(32000)
            rec.setOutputFile(f.absolutePath)
            rec.prepare()
            rec.start()
        } catch (e: Exception) {
            runCatching { rec.release() }
            f.delete()
            runCatching { startAsForeground() }
            toast("마이크를 쓸 수 없어요 (다른 앱이 쓰는 중일 수 있어요)")
            return
        }
        gemRecorder = rec
        gemFile = f
        gemStartedAt = System.currentTimeMillis()
        gemPeak = 0
        requestAudioFocus() // 유튜브/음악을 잠깐 멈춰 녹음이 되게
        setVoiceColor(true)
        peeHandler.removeCallbacks(gemTick)
        peeHandler.post(gemTick)
        toast("🎙 녹음 중… 다 말하면 다시 눌러요 (길이 상관없어요)")
    }

    private fun stopGeminiVoice() {
        val rec = gemRecorder ?: return
        gemRecorder = null
        peeHandler.removeCallbacks(gemTick)
        gemPeak = maxOf(gemPeak, runCatching { rec.maxAmplitude }.getOrDefault(0))
        val stoppedOk = runCatching { rec.stop() }.isSuccess
        runCatching { rec.release() }
        abandonAudioFocus()
        runCatching { startAsForeground() }
        resetVoiceLook()
        val f = gemFile
        gemFile = null
        val sec = (System.currentTimeMillis() - gemStartedAt) / 1000
        if (f == null || !stoppedOk || !f.exists() || f.length() == 0L || sec < 1) {
            f?.delete()
            toast("녹음이 너무 짧아요")
            return
        }
        if (gemPeak in 1 until GEM_SILENCE_PEAK) {
            f.delete()
            toast("말소리가 들리지 않았어요 (다른 앱이 마이크를 쓰는 중일 수도 있어요)")
            return
        }
        transcribeGemini(listOf(f))
    }

    private fun resetVoiceLook() {
        setVoiceColor(false)
        bubbles.firstOrNull { it.voice }?.let { b ->
            b.tv?.text = ""
            b.iv.visibility = View.VISIBLE
        }
    }

    /** 녹음 파일(들)을 차례로 받아써서 입력창에 넣는다. 성공한 파일은 지운다. */
    private fun transcribeGemini(files: List<java.io.File>) {
        gemBusy = true
        bubbles.firstOrNull { it.voice }?.let { (it.frame.background as? GradientDrawable)?.setColor(OPT_RUNNING_COLOR) }
        val pw = prefs.replyPassword
        val hints = VOICE_HINTS.toList()
        Thread {
            val texts = ArrayList<String>()
            var error: String? = null
            for (f in files) {
                val r = ReplyAi.transcribe(pw, f, "audio/aac", hints)
                val t = r.text
                if (t == null) {
                    error = r.error
                    break
                }
                if (t.isNotBlank()) texts.add(t.trim())
                f.delete()
            }
            peeHandler.post {
                gemBusy = false
                resetVoiceLook()
                if (!isRunning) return@post
                if (texts.isNotEmpty()) deliverVoiceText(texts.joinToString(" "))
                when {
                    error != null -> toast("$error. 녹음은 남겨 뒀어요, 다음에 누르면 먼저 다시 보내요")
                    texts.isEmpty() -> toast("알아들을 수 있는 말이 없었어요")
                }
            }
        }.start()
    }

    /** 받아쓴 글을 입력창에 넣고, 입력창이 없으면 클립보드에 복사 */
    private fun deliverVoiceText(text: String) {
        val ok = RestartAccessibilityService.instance?.insertText(text) ?: false
        if (!ok) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("voice", text))
            toast("입력창을 못 찾아 클립보드에 복사했어요")
        }
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
        voiceWanted = true
        voiceSession = true
        voiceEmptyStreak = 0
        voiceFailStreak = 0
        voiceBuffer.clear()
        peeHandler.removeCallbacks(voiceFlushFallback)
        lastVoiceActivity = System.currentTimeMillis()
        peeHandler.removeCallbacks(voiceWatchdog)
        peeHandler.postDelayed(voiceWatchdog, 5000L)
        setVoiceColor(true)
        requestAudioFocus() // 유튜브/음악을 잠깐 멈춰 녹음이 되게
        muteBeep() // 구간마다 나는 시작/종료 효과음 끄기
        recreateRecognizer()
        toast("🎙 말하세요… 다 말하면 다시 눌러요 (그때 한 번에 입력돼요)")
        // 재생 중이던 소리가 실제로 멈출 때까지 잠깐 기다렸다가 듣기 시작 (첫 시도 실패 방지)
        waitForSilenceThen(System.currentTimeMillis()) {
            if (voiceWanted && !listenOnce()) retryAfterFailure()
        }
    }

    private fun recreateRecognizer() {
        runCatching { recognizer?.destroy() }
        recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(voiceListener)
        }
    }

    /** 다른 앱 소리가 멈출 때까지(최대 1.5초) 기다린 뒤 조금 더 쉬고 실행 */
    private fun waitForSilenceThen(startedAt: Long, action: () -> Unit) {
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val elapsed = System.currentTimeMillis() - startedAt
        if (am.isMusicActive && elapsed < 1500L) {
            peeHandler.postDelayed({ if (voiceWanted) waitForSilenceThen(startedAt, action) }, 100L)
        } else {
            peeHandler.postDelayed({ if (voiceWanted) action() }, 250L)
        }
    }

    /** 마이크/인식기 오류 → 인식기를 새로 만들어 몇 번 더 시도 */
    private fun retryAfterFailure(): Boolean {
        if (!voiceWanted || voiceFailStreak >= VOICE_MAX_FAIL) return false
        voiceFailStreak++
        recreateRecognizer()
        requestAudioFocus()
        peeHandler.postDelayed({
            if (voiceWanted) waitForSilenceThen(System.currentTimeMillis()) {
                if (voiceWanted && !listenOnce() && !retryAfterFailure()) {
                    if (voiceBuffer.isEmpty()) {
                        endVoiceSession()
                        toast("음성 인식을 시작하지 못했어요")
                    } else flushVoice()
                }
            }
        }, 300L * voiceFailStreak)
        return true
    }

    /** 인식 한 구간 시작. 말이 잠깐 끊기면 인식기가 스스로 멈추므로 voiceWanted 동안 계속 다시 부른다. */
    private fun listenOnce(): Boolean {
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            if (prefs.voiceOffline) putExtra(android.speech.RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            if (Build.VERSION.SDK_INT >= 33) {
                // 배달에서 자주 나오는 말은 더 잘 알아듣게 힌트를 준다
                putStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_BIASING_STRINGS, VOICE_HINTS)
            }
            // 말 사이 쉬는 시간을 넉넉히 (지원하는 인식기에서만 적용됨)
            putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L)
            putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L)
            putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 10000L)
            usingSegmented = Build.VERSION.SDK_INT >= 33 && segmentedOk != false
            if (usingSegmented) {
                // 안드로이드 13+: 말이 멈춰도 세션을 끊지 않고 구간 결과만 계속 보내 주는 연속 인식
                putExtra(
                    android.speech.RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                    android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS
                )
                putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 10L * 60 * 1000)
            }
        }
        voiceSessionReady = false
        voicePartial = ""
        return try {
            recognizer?.startListening(intent)
            listening = true
            setVoiceColor(true) // 녹음 세션 동안 계속 빨강
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 결과/오류 뒤: 사용자가 아직 안 멈췄으면 바로 다시 듣는다. */
    private fun continueOrEnd() {
        if (voiceWanted && voiceEmptyStreak < VOICE_MAX_EMPTY) {
            peeHandler.postDelayed({
                if (voiceWanted && !listenOnce()) endVoiceSession()
            }, 150L)
        } else {
            if (voiceWanted && voiceEmptyStreak >= VOICE_MAX_EMPTY) toast("말소리가 없어 녹음을 멈췄어요")
            flushVoice()
        }
    }

    /** 모아 둔 말을 한 번에 입력하고 녹음을 끝낸다. */
    private fun flushVoice() {
        peeHandler.removeCallbacks(voiceFlushFallback)
        if (!voiceSession) return // 이미 입력하고 끝난 세션
        voiceSession = false
        takePartial()
        val text = voiceBuffer.joinToString(" ").trim()
        voiceBuffer.clear()
        endVoiceSession()
        if (text.isEmpty()) {
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

    /** 확정 결과 후보 중 신뢰도가 가장 높은 것을 고른다 (신뢰도 정보가 없으면 첫 번째). */
    private fun bestVoiceText(bundle: android.os.Bundle?): String? {
        val list = bundle?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION) ?: return null
        val scores = bundle.getFloatArray(android.speech.SpeechRecognizer.CONFIDENCE_SCORES)
        val i = if (scores != null && scores.size == list.size && scores.any { it > 0f }) {
            scores.indices.maxByOrNull { scores[it] } ?: 0
        } else 0
        return list.getOrNull(i)?.trim()
    }

    /** 확정 결과 없이 끊겼을 때: 마지막 중간 결과라도 모아 둔다. */
    private fun takePartial() {
        val p = voicePartial.trim()
        voicePartial = ""
        if (p.isNotEmpty()) {
            voiceBuffer.add(p)
            voiceEmptyStreak = 0
            lastVoiceActivity = System.currentTimeMillis()
        }
    }

    private fun addVoiceText(bundle: android.os.Bundle?) {
        val t = bestVoiceText(bundle)
        voicePartial = ""
        if (!t.isNullOrEmpty()) {
            voiceBuffer.add(t)
            voiceEmptyStreak = 0
            voiceFailStreak = 0
            lastVoiceActivity = System.currentTimeMillis()
        }
    }

    private fun endVoiceSession() {
        peeHandler.removeCallbacks(voiceWatchdog)
        // 마지막 종료음까지 묻히도록 조금 뒤에 소리 복구
        if (mutedStreams.isNotEmpty()) {
            peeHandler.removeCallbacks(unmuteRunnable)
            peeHandler.postDelayed(unmuteRunnable, 800L)
        }
        voiceSession = false
        voiceWanted = false
        listening = false
        setVoiceColor(false)
        abandonAudioFocus()
    }

    private fun stopVoice() {
        // 사용자가 다시 누름 → 지금 구간 결과까지만 받고 끝낸다
        voiceWanted = false
        setVoiceColor(false)
        if (!listening) { // 구간 사이(재시작 대기 중)라면 콜백이 안 오므로 바로 입력
            flushVoice()
            return
        }
        runCatching { recognizer?.stopListening() }
        listening = false
        // 마지막 구간 결과가 안 오는 경우 대비: 잠시 뒤 모아 둔 것만이라도 입력
        peeHandler.postDelayed(voiceFlushFallback, 3000L)
    }

    private fun setVoiceColor(active: Boolean) {
        val b = bubbles.firstOrNull { it.voice } ?: return
        // 크기 변화 없이 색만 바꾼다 (평소 인디고 → 녹음 중 빨강)
        (b.frame.background as? GradientDrawable)?.setColor(if (active) VOICE_COLOR_ACTIVE else VOICE_COLOR)
    }

    private fun requestAudioFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { runCatching { am.abandonAudioFocusRequest(it) } }
            // 음성 비서 용도 + 독점 포커스 → 유튜브/유튜브 뮤직이 소리를 줄이는 대신 확실히 일시정지
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = android.media.AudioFocusRequest.Builder(
                android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            ).setAudioAttributes(attrs).setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener { }
                .build()
            runCatching { am.requestAudioFocus(req) }
            audioFocusRequest = req
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                am.requestAudioFocus(
                    null,
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
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
            val before = voiceBuffer.size
            val partial = voicePartial
            addVoiceText(results)
            if (voiceBuffer.size == before) { // 확정 결과가 비었으면 중간 결과로 대신
                voicePartial = partial
                takePartial()
            }
            if (voiceBuffer.size == before) voiceEmptyStreak++
            if (voiceWanted) continueOrEnd() else flushVoice()
        }

        // 안드로이드 13+ 연속 인식: 구간마다 결과가 오지만 세션은 계속된다
        override fun onSegmentResults(segmentResults: android.os.Bundle) {
            addVoiceText(segmentResults)
        }

        override fun onEndOfSegmentedSession() {
            listening = false
            segmentedOk = true
            if (voiceWanted) continueOrEnd() else flushVoice()
        }

        override fun onError(error: Int) {
            listening = false
            takePartial() // 확정 결과 없이 끊겨도 말한 내용은 살린다
            // 연속 인식을 이 기기 인식기가 지원하지 않음 → 기존 방식으로 바로 다시
            if (usingSegmented && !voiceSessionReady && segmentedOk == null && voiceWanted) {
                segmentedOk = false
                recreateRecognizer()
                peeHandler.postDelayed({ if (voiceWanted && !listenOnce()) retryAfterFailure() }, 200L)
                return
            }
            // 사용자가 멈춘 뒤 오는 오류 → 모아 둔 것 입력하고 끝
            if (!voiceWanted) {
                flushVoice()
                return
            }
            // 말 사이가 끊겨서 멈춘 것 → 사용자가 안 멈췄으면 조용히 다시 듣기
            if (voiceWanted && (error == android.speech.SpeechRecognizer.ERROR_NO_MATCH ||
                    error == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                    error == android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                    error == android.speech.SpeechRecognizer.ERROR_CLIENT)
            ) {
                voiceEmptyStreak++
                continueOrEnd()
                return
            }
            // 마이크가 아직 다른 앱에 잡혀 있거나 인식 서버가 순간 실패 → 새로 만들어 재시도
            if (voiceWanted && error != android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS &&
                retryAfterFailure()
            ) return
            if (voiceBuffer.isNotEmpty()) {
                flushVoice() // 들은 데까지는 입력
                return
            }
            endVoiceSession()
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
            if (voiceWanted) setVoiceColor(true)
            voiceFailStreak = 0
            voiceSessionReady = true
        }

        override fun onBeginningOfSpeech() {
            lastVoiceActivity = System.currentTimeMillis()
            if (voiceWanted) setVoiceColor(true)
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            // 구간이 끝나도 사용자가 멈추기 전까지는 빨강 유지 (곧 다시 듣는다)
        }

        override fun onPartialResults(partialResults: android.os.Bundle?) {
            val t = partialResults?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim()
            if (!t.isNullOrEmpty()) {
                voicePartial = t
                lastVoiceActivity = System.currentTimeMillis()
            }
        }
        override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
    }

    // ---------------- 워터 타이머 (개인 실험 기능) ----------------

    private fun addPeeBubble() {
        if (bubbles.any { it.pee } || hiddenByFolder("pee")) return
        val sizePx = dp(prefs.buttonSizeDp)
        val b = Bubble(pee = true)
        b.iv = ImageView(this).apply {
            setImageResource(R.drawable.ic_water_splash)
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = 0.9f
        }
        b.tv = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = (10f * prefs.buttonSizeDp / 51f).coerceIn(7f, 14f) // 버튼 크기에 맞춰
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
        } else if (b.callBoost) {
            // 글자만 있는 버튼
        } else if (b.diary != null || b.volume != 0) {
            b.tv?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sizePx * (if (b.volume != 0) 0.6f else 0.45f))
        } else if (b.capture || b.voice || b.isFolder || b.optimize || b.reply || b.screenRec) {
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
            b.isFolder -> {
                val f = prefs.getFolders().firstOrNull { it.id == b.folderId }
                if (f == null || f.x < 0 || f.y < 0) defaultFolderPos() else f.x to f.y
            }
            b.restartPkg != null -> {
                val a = prefs.getRestartApps().firstOrNull { it.packageName == b.restartPkg }
                if (a == null || a.x < 0 || a.y < 0) autoStickerPos(b) else a.x to a.y
            }
            b.isRestart ->
                if (prefs.posX < 0 || prefs.posY < 0) defaultRestartPos() else prefs.posX to prefs.posY
            b.pee ->
                if (prefs.peePosX < 0 || prefs.peePosY < 0) defaultPeePos() else prefs.peePosX to prefs.peePosY
            b.callBoost -> {
                val (w, h) = screenSize()
                if (prefs.callPosX < 0 || prefs.callPosY < 0) (w - dp(prefs.buttonSizeDp) - dp(12)) to (h * 0.3f).roundToInt()
                else prefs.callPosX to prefs.callPosY
            }
            b.diary != null -> {
                val (x, y) = prefs.diaryPos(b.diary)
                if (x < 0 || y < 0) {
                    // 처음에는 오른쪽 벽에 📖 위, 💡 바로 아래
                    val (w, h) = screenSize()
                    val size = dp(prefs.buttonSizeDp)
                    val step = if (b.diary == DiaryLink.KIND_IDEA) size + dp(prefs.buttonGapDp) else 0
                    (w - size) to ((h * 0.2f).roundToInt() + step)
                } else x to y
            }
            b.volume != 0 -> {
                val key = if (b.volume > 0) "volup" else "voldown"
                val (x, y) = prefs.diaryPos(key)
                if (x < 0 || y < 0) {
                    // 처음에는 오른쪽 벽에 + 위, − 바로 아래로 붙여서
                    val (w, h) = screenSize()
                    val size = dp(prefs.buttonSizeDp)
                    val step = if (b.volume < 0) size + dp(prefs.buttonGapDp) else 0
                    (w - size) to ((h * 0.35f).roundToInt() + step)
                } else x to y
            }
            b.screenRec -> {
                val (x, y) = prefs.diaryPos("screenrec")
                if (x < 0 || y < 0) {
                    val (w, h) = screenSize()
                    (w - dp(prefs.buttonSizeDp)) to (h * 0.62f).roundToInt()
                } else x to y
            }
            b.reply -> {
                val (x, y) = prefs.diaryPos("reply")
                if (x < 0 || y < 0) {
                    val (w, h) = screenSize()
                    (w - dp(prefs.buttonSizeDp)) to (h * 0.5f).roundToInt()
                } else x to y
            }
            b.optimize -> {
                val (_, h) = screenSize()
                if (prefs.optPosX < 0 || prefs.optPosY < 0) dp(12) to (h * 0.6f).roundToInt()
                else prefs.optPosX to prefs.optPosY
            }
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

    /**
     * 간격 설정이 바뀌면: 같은 세로줄에 붙어 있는 아이콘들 사이를 새 간격으로 다시 맞춘다.
     * (따로 떨어져 있는 아이콘은 그대로)
     */
    private var appliedGapDp = -1
    private fun respaceStacks() {
        val oldGap = dp(if (appliedGapDp >= 0) appliedGapDp else Prefs.DEFAULT_GAP_DP)
        val newGap = dp(prefs.buttonGapDp)
        appliedGapDp = prefs.buttonGapDp
        val size = dp(prefs.buttonSizeDp)
        val (_, h) = screenSize()
        // '붙어 있다' 판정: 옛 간격보다 조금 여유를 둔다
        val attachTh = size + maxOf(oldGap, dp(6)) + dp(4)
        for ((_, col) in bubbles.groupBy { it.lp.x }) {
            val sorted = col.sortedBy { it.lp.y }
            val origY = sorted.map { it.lp.y } // 붙어 있는지는 옮기기 전 자리로 판단
            for (i in 1 until sorted.size) {
                val prev = sorted[i - 1]
                val cur = sorted[i]
                if (origY[i] - origY[i - 1] <= attachTh && cur.inFolder < 0) {
                    val ny = (prev.lp.y + size + newGap).coerceAtMost((h - size).coerceAtLeast(0))
                    clampAndApply(cur, cur.lp.x, ny)
                    savePositionAt(cur, cur.lp.x, cur.lp.y)
                }
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
        layoutExpandedFolders()
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
        private var pendingTap: Runnable? = null

        /** 폴더는 한 번 누르면 펼침/닫기(잠깐 기다려 더블탭인지 확인), 두 번 누르면 앱 추가 창 */
        private fun handleFolderTap() {
            val p = pendingTap
            if (p != null) {
                handler.removeCallbacks(p)
                pendingTap = null
                onFolderDoubleTap(bubble.folderId)
                return
            }
            val r = Runnable {
                pendingTap = null
                onBubbleTapped(bubble)
            }
            pendingTap = r
            handler.postDelayed(r, DOUBLE_TAP_MS)
        }

        // 폴더는 보통 길게 누르기(0.6초)로는 반응만 하고, 더 길게(1.5초) 누르면 풀린다
        private val folderDissolveRunnable = Runnable {
            bubble.frame.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            dissolveFolder(bubble.folderId)
        }
        private val longPressRunnable = Runnable {
            longPressFired = true
            if (bubble.isFolder) {
                bubble.frame.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                bubble.frame.animate().scaleX(1.15f).scaleY(1.15f).setDuration(120).start() // 곧 풀린다는 표시
                handler.postDelayed(folderDissolveRunnable, FOLDER_DISSOLVE_MS - LONG_PRESS_MS)
            } else {
                onLongPress(bubble)
            }
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
                        handler.removeCallbacks(folderDissolveRunnable)
                        v.alpha = 1f
                    }
                    if (dragging) {
                        if (bubble.inFolder < 0) clampAndApply(bubble, startX + dx.roundToInt(), startY + dy.roundToInt())
                        else clampAndApply(bubble, startX, startY + dy.roundToInt()) // 폴더 안: 위아래로만 (순서 바꾸기)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    handler.removeCallbacks(folderDissolveRunnable)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    when {
                        longPressFired -> {
                            // 길게 누르는 중에 이미 처리됨 (떼는 동작은 무시)
                            v.alpha = prefs.buttonAlpha / 100f
                        }
                        dragging -> {
                            v.alpha = prefs.buttonAlpha / 100f
                            if (bubble.inFolder >= 0) {
                                reorderFolderMember(bubble) // 폴더 안에서는 위치 대신 순서만 바꾼다
                            } else {
                                val (sx, sy) = computeSnap(bubble)
                                val (tx, ty) = resolveCollision(bubble, sx, sy)
                                savePositionAt(bubble, tx, ty)
                                animateTo(bubble, tx, ty)
                                // 펼친 폴더를 옮겼으면 안의 버튼들도 새 자리 옆으로
                                if (bubble.isFolder) peeHandler.postDelayed({ layoutExpandedFolders() }, 220L)
                            }
                        }
                        else -> {
                            v.performClick()
                            if (bubble.isFolder) handleFolderTap() else onBubbleTapped(bubble)
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    handler.removeCallbacks(folderDissolveRunnable)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    v.alpha = prefs.buttonAlpha / 100f
                }
            }
            return true
        }
    }

    /** 길게 누르기(떼지 않아도 적용): 워터=모드 토글, 배민리프레시=앱 열기, 그 외 아이콘=삭제 */
    private fun onLongPress(b: Bubble) {
        when {
            b.pee -> {
                prefs.waterModeEnabled = !prefs.waterModeEnabled
                updatePeeNotification()
            }
            b.callBoost -> onCallBubbleTapped() // 길게 눌러도 켜기/끄기 (지우지 않음)
            b.isFolder -> {} // 폴더는 더 길게 눌러야 풀린다 (DragTouchListener 에서 처리)
            b.diary != null -> { // 일기장 앱의 최근 페이지 열기 (지우지 않음)
                openDiaryPage(b.diary)
                if (b.inFolder >= 0) collapseFolder(b.inFolder)
            }
            b.optimize -> {
                prefs.optimizeEnabled = false
                toast("폰 최적화 버튼을 지웠어요")
            }
            b.reply -> {
                prefs.replyEnabled = false
                toast("답변 추천 버튼을 지웠어요")
            }
            b.volume != 0 -> {
                prefs.volumeButtonsEnabled = false
                toast("볼륨 버튼을 지웠어요")
            }
            b.screenRec -> {
                if (screenRecording) stopScreenRec(null) // 녹화 중 정지 버튼은 길게 눌러도 끝내기
                else {
                    prefs.screenRecEnabled = false
                    toast("화면 녹화 버튼을 지웠어요")
                }
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
            b.isRestart -> openMainApp() // 기본 배민리프레시 버튼 → 배민리프레시 앱 열기
        }
    }

    private fun savePositionAt(b: Bubble, x: Int, y: Int) {
        when {
            b.isFolder -> prefs.updateFolderPosition(b.folderId, x, y)
            b.diary != null -> prefs.setDiaryPos(b.diary, x, y)
            b.reply -> prefs.setDiaryPos("reply", x, y)
            b.screenRec -> prefs.setDiaryPos("screenrec", x, y)
            b.volume != 0 -> prefs.setDiaryPos(if (b.volume > 0) "volup" else "voldown", x, y)
            b.optimize -> {
                prefs.optPosX = x
                prefs.optPosY = y
            }
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
            b.callBoost -> {
                prefs.callPosX = x
                prefs.callPosY = y
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
        val gap = dp(prefs.buttonGapDp)
        val wallThreshold = dp(prefs.buttonSizeDp / 2)
        val snapDist = size + dp(prefs.buttonSizeDp / 2)

        // 1) 다른 버튼 근처 → 세로로 붙이기
        val near = bubbles.filter { it !== b && !related(b, it) }.minByOrNull {
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
        resolveCollisionAgainst(b, x, y, bubbles.filter { it !== b && !related(b, it) })

    /** 같은 폴더의 폴더 버튼과 그 안의 버튼들은 서로 붙어 있는 게 정상이라 겹침/스냅 계산에서 뺀다. */
    private fun related(a: Bubble, b: Bubble): Boolean =
        (a.isFolder && b.inFolder == a.folderId) || (b.isFolder && a.inFolder == b.folderId)

    private fun resolveCollisionAgainst(b: Bubble, x: Int, y: Int, others: List<Bubble>): Pair<Int, Int> {
        val (_, h) = screenSize()
        val size = b.lp.width
        val gap = dp(prefs.buttonGapDp)
        val step = gap.coerceAtLeast(dp(2))

        fun overlaps(px: Int, py: Int): Boolean = others.any {
            abs(px - it.lp.x) < size + gap && abs(py - it.lp.y) < size + gap
        }
        if (!overlaps(x, y)) return x to y

        val maxY = (h - size).coerceAtLeast(0)
        var offset = step
        while (offset <= h) {
            val down = (y + offset).coerceAtMost(maxY)
            if (!overlaps(x, down)) return x to down
            val up = (y - offset).coerceAtLeast(0)
            if (!overlaps(x, up)) return x to up
            if (down == maxY && up == 0) break
            offset += step
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
        if (b.isFolder) {
            toggleFolder(b.folderId)
            return
        }
        when {
            b.pee -> resetPee()
            b.capture -> captureScreen()
            b.voice -> onVoiceTapped()
            b.callBoost -> onCallBubbleTapped()
            b.optimize -> onOptimizeTapped()
            b.diary != null -> onDiaryTapped(b.diary)
            b.reply -> onReplyTapped()
            b.screenRec -> onScreenRecTapped()
            b.volume != 0 -> onVolumeTapped(b.volume)
            b.isRestart -> onRestartTapped(b)
            else -> launchApp(b.launchPkg!!)
        }
        // 폴더에서 꺼낸 버튼을 눌렀으면 폴더를 다시 닫는다 (음성 입력은 녹음 중이라 열어 둠)
        // (볼륨 버튼은 여러 번 누르니까 폴더를 열어 둔다)
        if (b.inFolder >= 0 && !b.voice && b.diary == null && b.volume == 0) collapseFolder(b.inFolder)
    }

    private fun openMainApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("배민리프레시를 열지 못했어요")
        }
    }

    private fun launchApp(pkg: String) {
        if (TargetApps.isClone(pkg)) { // 멀티로그인 개인용 복제본
            if (!TargetApps.launch(this, pkg)) toast("복제본을 실행할 수 없어요 (멀티로그인 개인용이 설치돼 있는지 확인해 주세요)")
            return
        }
        if (TargetApps.isOtherProfile(pkg)) { // 멀티 프로필(업무) 앱
            if (!TargetApps.launch(this, pkg)) toast("이 앱을 실행할 수 없어요 (업무 프로필이 꺼져 있는지 확인해 주세요)")
            return
        }
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
        KeepAlive.pause()
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
        const val ACTION_SCREENREC_START = "com.baeminrefresh.app.SCREENREC_START"
        private const val ACTION_SCREENREC_STOP = "com.baeminrefresh.app.SCREENREC_STOP"
        const val EXTRA_REC_CODE = "rec_code"
        const val EXTRA_REC_DATA = "rec_data"
        private const val REC_CHANNEL_ID = "screen_rec"
        private const val REC_SAVED_NOTIFICATION_ID = 3
        private val VOLUME_COLOR = 0xFF4B5BA6.toInt() // 볼륨 버튼: 남색
        private val REC_COLOR = 0xFF3A3F47.toInt() // 화면 녹화 버튼: 짙은 회색
        private const val LONG_PRESS_MS = 600L
        private const val FOLDER_DISSOLVE_MS = 1500L // 폴더는 이만큼 눌러야 풀린다
        private const val DOUBLE_TAP_MS = 280L // 이 안에 한 번 더 누르면 더블탭
        private val FOLDER_COLOR = 0xFF5B6770.toInt()
        private val REPLY_COLOR = 0xFF0F9D8A.toInt() // 답변 추천: 청록
        private val DIARY_COLOR = 0xFF7C5CFF.toInt() // 📖 평소: 보라
        private val IDEA_COLOR = 0xFFF59F00.toInt() // 💡 평소: 주황
        private val DIARY_REC_COLOR = 0xFFE03131.toInt() // 녹음 중: 빨강
        private val DIARY_SEND_COLOR = 0xFF3B82F6.toInt() // 보내는 중: 파랑
        private val OPT_COLOR = 0xFFF59E0B.toInt()
        private val OPT_RUNNING_COLOR = 0xFFFFD60A.toInt() // 진행 중: 노랑
        private val OPT_DONE_COLOR = 0xFF2F80ED.toInt() // 완료: 파랑
        private const val PEE_GOLD_MS = 3L * 60 * 60 * 1000 // 3시간
        private val PEE_COLOR_BLUE = 0xFF2AA7E0.toInt()
        private val PEE_COLOR_GOLD = 0xFFE6A700.toInt()
        private val VOICE_COLOR = 0xFF5C6BC0.toInt() // 평소: 인디고
        private val VOICE_HINTS = arrayListOf(
            "배민", "배민커넥트", "쿠팡이츠", "카카오 T 픽커", "픽업", "배달", "배달 완료", "주문",
            "고객님", "문 앞", "공동현관", "비밀번호", "도착", "출발", "수락", "거절"
        )
        private const val VOICE_MAX_FAIL = 4 // 오류 시 자동 재시도 횟수
        private val BEEP_STREAMS = intArrayOf(
            android.media.AudioManager.STREAM_MUSIC,
            android.media.AudioManager.STREAM_SYSTEM
        )
        private const val VOICE_IDLE_STOP_MS = 90_000L // 이만큼 말이 없으면 자동 종료
        private const val VOICE_MAX_EMPTY = 8 // 말 없는 구간이 이만큼 연속이면(약 1분) 자동 종료
        private const val GEM_MAX_SEC = 2 * 60 * 60 // 제미나이 받아쓰기 녹음 최대 2시간
        private const val GEM_SILENCE_PEAK = 600 // 녹음 내내 이보다 작으면 말소리 없음
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
