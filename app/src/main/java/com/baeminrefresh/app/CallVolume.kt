package com.baeminrefresh.app

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * 실험실: 통화 소리 증폭.
 * 통화 중에 '증폭'이 켜져 있으면 세 가지를 함께 쓴다.
 *  1) 통화 음량을 최대로 (통화 끝나거나 끄면 원래대로)
 *  2) 삼성 통화 화면의 '추가 음량(Extra volume)' 버튼이 보이면 눌러서 켠다 → 일반 전화에 가장 확실
 *  3) 소리 증폭기(LoudnessEnhancer, 최대 +9dB) → 보이스톡 같은 인터넷 전화에 효과
 * 그리고 실제로 증폭되는지 확인한다: 통화 소리가 앱이 다룰 수 있는 경로로 나오는지(Visualizer로 소리 감지).
 *
 * 1.36.2: '추가 음량' 버튼을 통화 내내(통화 화면이 보일 때마다) 찾고, 안 보이면 '더보기' 메뉴에서 한 번 찾는다.
 *         못 찾으면 통화 화면 버튼 글자를 Prefs.callBoostDebug 에 남긴다(설정 카드에 표시).
 *         영상통화(카메라 사용 중)는 증폭하지 않는다.
 */
class CallVolume(private val service: RestartAccessibilityService) {

    enum class Status { OFF, CHECKING, WORKING, NOT_DETECTED }

    private val am = service.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val prefs = Prefs(service)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var enhancer: LoudnessEnhancer? = null
    private var visualizer: Visualizer? = null
    private var savedVolume = -1
    private var boostOn = false
    private var boostStartedAt = 0L
    private var soundSeen = false
    private var extraVolumeOn = false
    private var extraTries = 0
    private var menuTried = false
    private var debugSaved = false

    // 영상통화 감지: 카메라를 어떤 앱이 쓰는 중이면 영상통화로 본다
    private val camerasInUse = HashSet<String>()
    private val cameraCallback = object : android.hardware.camera2.CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) { camerasInUse.add(cameraId) }
        override fun onCameraAvailable(cameraId: String) { camerasInUse.remove(cameraId) }
    }
    private var cameraWatching = false

    var inCall = false
        private set
    var status = Status.OFF
        private set

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            tick()
            handler.postDelayed(this, if (inCall) 1000L else 1500L)
        }
    }

    fun start() {
        if (running) return
        running = true
        runCatching {
            service.getSystemService(android.hardware.camera2.CameraManager::class.java)
                .registerAvailabilityCallback(cameraCallback, handler)
            cameraWatching = true
        }
        handler.post(poll)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(poll)
        if (cameraWatching) runCatching {
            service.getSystemService(android.hardware.camera2.CameraManager::class.java)
                .unregisterAvailabilityCallback(cameraCallback)
        }
        cameraWatching = false
        camerasInUse.clear()
        boostOff(restoreVolume = true)
        inCall = false
    }

    /** 설정/버튼으로 켜고 끈 뒤 바로 반영 */
    fun applyNow() {
        tick(force = true)
    }

    private fun isCallMode(): Boolean {
        val m = am.mode
        return m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION
    }

    private fun tick(force: Boolean = false) {
        // 영상통화(카메라 사용 중)는 증폭하지 않고 버튼도 띄우지 않는다
        val nowInCall = isCallMode() && prefs.callBoostEnabled && camerasInUse.isEmpty()
        val changedCall = nowInCall != inCall
        inCall = nowInCall
        val want = inCall && prefs.callBoostActive

        if (want && !boostOn) boostOn()
        else if (!want && boostOn) boostOff(restoreVolume = true)
        else if (want && force) applyGain()

        if (boostOn) {
            // 통화 내내, 통화 화면이 보일 때마다 '추가 음량' 버튼을 찾는다
            if (!extraVolumeOn && service.callScreenVisible()) {
                extraTries++
                if (service.setSamsungExtraVolume(true)) extraVolumeOn = true
                else if (extraTries >= 3 && !menuTried) {
                    // 바로 안 보이면 '더보기' 메뉴 안에서 한 번 찾아본다
                    menuTried = true
                    service.trySamsungExtraVolumeViaMenu { ok ->
                        if (ok) {
                            extraVolumeOn = true
                            tick(force = true)
                        }
                    }
                }
            }
            measure()
        }
        val newStatus = when {
            !boostOn -> Status.OFF
            extraVolumeOn || soundSeen -> Status.WORKING
            SystemClock.uptimeMillis() - boostStartedAt < 6000L -> Status.CHECKING
            else -> Status.NOT_DETECTED
        }
        // 못 찾았으면 통화 화면의 버튼 글자를 남겨 둔다(그 폰 라벨에 맞추기 위해)
        if (newStatus == Status.NOT_DETECTED && !debugSaved && service.callScreenVisible() && menuTried) {
            debugSaved = true
            prefs.callBoostDebug = service.callScreenLabels()
        }
        if (newStatus == Status.WORKING && extraVolumeOn) prefs.callBoostDebug = ""
        if (newStatus != status || changedCall || force) {
            status = newStatus
            FloatingService.instance?.onCallBoostChanged()
        }
    }

    private fun boostOn() {
        boostOn = true
        boostStartedAt = SystemClock.uptimeMillis()
        soundSeen = false
        extraVolumeOn = false
        extraTries = 0
        menuTried = false
        debugSaved = false
        service.resetExtraVolumeMenuTry()
        val stream = AudioManager.STREAM_VOICE_CALL
        runCatching {
            savedVolume = am.getStreamVolume(stream)
            am.setStreamVolume(stream, am.getStreamMaxVolume(stream), 0)
        }
        applyGain()
        startVisualizer()
    }

    private fun applyGain() {
        val gainMb = (prefs.callBoostLevel.coerceIn(0, 100) * MAX_GAIN_MB / 100)
        runCatching {
            val e = enhancer ?: LoudnessEnhancer(0).also { enhancer = it }
            e.setTargetGain(gainMb)
            e.enabled = gainMb > 0
        }.onFailure { releaseEnhancer() }
    }

    private fun boostOff(restoreVolume: Boolean) {
        if (!boostOn) return
        boostOn = false
        releaseEnhancer()
        stopVisualizer()
        if (extraVolumeOn) {
            service.setSamsungExtraVolume(false) // 우리가 켠 것만 끈다
            extraVolumeOn = false
        }
        if (restoreVolume && savedVolume >= 0) {
            runCatching { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedVolume, 0) }
        }
        savedVolume = -1
    }

    private fun releaseEnhancer() {
        runCatching { enhancer?.enabled = false }
        runCatching { enhancer?.release() }
        enhancer = null
    }

    // 통화 소리가 앱이 다룰 수 있는 출력 경로(믹서)로 나오는지 감지 → 나오면 증폭기가 먹힌다
    private fun startVisualizer() {
        runCatching {
            val v = Visualizer(0)
            v.captureSize = Visualizer.getCaptureSizeRange()[0]
            v.enabled = true
            visualizer = v
        }.onFailure { stopVisualizer() }
    }

    private fun measure() {
        val v = visualizer ?: return
        runCatching {
            val buf = ByteArray(v.captureSize)
            if (v.getWaveForm(buf) == Visualizer.SUCCESS) {
                var peak = 0
                for (b in buf) {
                    val d = kotlin.math.abs((b.toInt() and 0xFF) - 128)
                    if (d > peak) peak = d
                }
                if (peak > 4) soundSeen = true
            }
        }
    }

    private fun stopVisualizer() {
        runCatching { visualizer?.enabled = false }
        runCatching { visualizer?.release() }
        visualizer = null
    }

    companion object {
        const val MAX_GAIN_MB = 900 // 최대 +9dB (스피커·귀 보호 상한)
    }
}
