package com.baeminrefresh.app

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * 실험실: 통화 소리 조절/증폭.
 * - 0~100%: 통화 음량(STREAM_VOICE_CALL)을 그 비율로 맞춘다. 통화가 시작될 때 한 번 적용하고,
 *   통화 중 음량 버튼으로 바꾸는 건 그대로 둔다.
 * - 100% 초과(최대 200%): 통화 음량을 최대로 하고, 통화 중에만 소리 증폭기(LoudnessEnhancer)를 켠다.
 *   귀·스피커 보호를 위해 최대 +6dB(약 2배)로 제한. 통화가 끝나면 바로 끈다.
 *   (기기에 따라 일반 전화 소리는 증폭이 안 될 수 있음: 통화 소리가 앱이 손댈 수 없는 경로로 나오는 경우)
 */
class CallVolume(private val context: Context) {

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val prefs = Prefs(context)
    private val handler = Handler(Looper.getMainLooper())
    private var inCall = false
    private var enhancer: LoudnessEnhancer? = null
    private var running = false

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            check()
            handler.postDelayed(this, if (inCall) 1000L else 1500L)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(poll)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(poll)
        releaseEnhancer()
        inCall = false
    }

    /** 설정을 바꿨을 때: 통화 중이면 바로 적용 */
    fun applyNow() {
        if (!prefs.callBoostEnabled) {
            releaseEnhancer()
            return
        }
        if (isCallMode()) apply()
    }

    private fun isCallMode(): Boolean {
        val m = am.mode
        return m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION
    }

    private fun check() {
        if (!prefs.callBoostEnabled) {
            if (enhancer != null) releaseEnhancer()
            inCall = isCallMode()
            return
        }
        val now = isCallMode()
        if (now && !inCall) apply() // 통화 시작
        if (!now && inCall) releaseEnhancer() // 통화 끝
        inCall = now
    }

    private fun apply() {
        val pct = prefs.callVolumePct.coerceIn(0, MAX_PCT)
        val stream = AudioManager.STREAM_VOICE_CALL
        val max = am.getStreamMaxVolume(stream)
        val min = if (Build.VERSION.SDK_INT >= 28) am.getStreamMinVolume(stream) else 0
        val target = if (pct >= 100) max
        else (min + (max - min) * pct / 100f).let { Math.round(it) }.coerceIn(min, max)
        runCatching { am.setStreamVolume(stream, target, 0) }

        if (pct > 100) {
            // 100→200% 를 0→+6dB 로 (2배 소리 ≈ +6dB)
            val gainMb = (20.0 * Math.log10(pct / 100.0) * 100).toInt().coerceIn(0, MAX_GAIN_MB)
            setEnhancer(gainMb)
        } else {
            releaseEnhancer()
        }
    }

    private fun setEnhancer(gainMb: Int) {
        runCatching {
            val e = enhancer ?: LoudnessEnhancer(0).also { enhancer = it }
            e.setTargetGain(gainMb)
            e.enabled = true
            boostWorks = true
        }.onFailure {
            releaseEnhancer()
            boostWorks = false
        }
    }

    private fun releaseEnhancer() {
        runCatching { enhancer?.enabled = false }
        runCatching { enhancer?.release() }
        enhancer = null
    }

    companion object {
        const val MAX_PCT = 200
        const val MAX_GAIN_MB = 600 // +6dB 상한

        /** 마지막 증폭 시도가 됐는지 (null = 아직 시도 안 함) */
        @Volatile
        var boostWorks: Boolean? = null
    }
}
