package com.baeminrefresh.app

import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast

/**
 * 실험실 "배민커넥트 꺼짐 방지" — B(자동 다시 켜기).
 *
 * 안드로이드는 다른 앱이 꺼지는 걸 막을 수 없어서, 꺼진 걸 알아채면 다시 켜고 보던 앱으로 돌아온다.
 * 꺼짐 감지(15초마다, 사용 기록 접근 권한 필요):
 *  1) 배민커넥트가 '강제 중지 상태'(FLAG_STOPPED)로 바뀜 — 삼성 '딥 슬립'·시스템 정리
 *  2) 배민커넥트의 포그라운드 서비스(운행 중 상시 알림)가 멈췄는데, 그때 배민커넥트 화면을 보고 있지 않았음
 *     — 사용자가 운행을 끈 경우(배민커넥트 화면 안에서 끔)와 구분
 * 우리 쪽 재시작·폰 최적화 중이거나 직후, 설정 앱을 보는 중(사용자가 직접 강제 중지)에는 건드리지 않는다.
 * 너무 자주 다시 켜지 않게: 1분에 1번, 30분에 6번까지.
 */
object KeepAlive {
    private const val CHECK_MS = 15_000L
    private const val QUIET_AFTER_OUR_ACTION_MS = 45_000L
    private const val MIN_GAP_MS = 60_000L
    private const val WINDOW_MS = 30 * 60_000L
    private const val MAX_IN_WINDOW = 6
    private const val LOOKBACK_MS = 6 * 60 * 60_000L

    private val handler = Handler(Looper.getMainLooper())
    private var ctx: Context? = null
    private var running = false

    private var watchedPkg: String? = null
    private var wasStopped: Boolean? = null
    private var fgsOn = false
    private var lastEventTime = 0L
    private var lastActivityResumed = false
    private val relaunches = ArrayList<Long>()
    private var warnedLimit = false

    /** 마지막으로 다시 켠 시각(설정 화면 표시용, System.currentTimeMillis) */
    @Volatile var lastRelaunchWall = 0L
        private set
    @Volatile var relaunchCount = 0
        private set

    /** 우리가 일부러 끄는 동안(재시작·최적화)엔 감시를 쉰다 */
    @Volatile private var quietUntil = 0L
    fun pause(ms: Long = QUIET_AFTER_OUR_ACTION_MS) {
        quietUntil = maxOf(quietUntil, SystemClock.elapsedRealtime() + ms)
    }

    fun start(context: Context) {
        ctx = context.applicationContext
        if (running) return
        running = true
        resetState()
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    private fun resetState() {
        watchedPkg = null
        wasStopped = null
        fgsOn = false
        lastEventTime = 0L
        lastActivityResumed = false
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            runCatching { check() }
            handler.postDelayed(this, CHECK_MS)
        }
    }

    private fun check() {
        val c = ctx ?: return
        val prefs = Prefs(c)
        if (!prefs.keepAliveEnabled) return
        val pkg = prefs.targetPackage ?: return
        if (pkg != watchedPkg) {
            resetState()
            watchedPkg = pkg
        }
        val now = System.currentTimeMillis()

        // 1) 강제 중지 상태로 바뀌었는지
        val stopped = isStopped(c, pkg)
        val becameStopped = wasStopped == false && stopped
        wasStopped = stopped

        // 2) 포그라운드 서비스(운행 중 알림)가 화면 밖에서 멈췄는지
        var fgsDiedInBackground = false
        if (Build.VERSION.SDK_INT >= 29 && PhoneOptimizer.isUsageAccessGranted(c)) {
            val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val from = if (lastEventTime == 0L) now - LOOKBACK_MS else lastEventTime + 1
            val first = lastEventTime == 0L
            val ev = usm.queryEvents(from, now)
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                if (e.timeStamp > lastEventTime) lastEventTime = e.timeStamp
                if (e.packageName != pkg) continue
                when (e.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> lastActivityResumed = true
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> lastActivityResumed = false
                    UsageEvents.Event.FOREGROUND_SERVICE_START -> fgsOn = true
                    UsageEvents.Event.FOREGROUND_SERVICE_STOP -> {
                        // 처음 읽는 지난 기록은 상태만 맞추고, 그 뒤에 새로 생긴 멈춤만 '꺼짐'으로 본다
                        if (fgsOn && !lastActivityResumed && !first) fgsDiedInBackground = true
                        fgsOn = false
                    }
                }
            }
            if (lastEventTime == 0L) lastEventTime = now
            // 멈춘 뒤 이미 다시 시작됐으면(앱이 스스로 살아남) 다시 켤 필요 없음
            if (fgsOn) fgsDiedInBackground = false
        }

        if (!becameStopped && !fgsDiedInBackground) return
        if (SystemClock.elapsedRealtime() < quietUntil) return
        if (PhoneOptimizer.running || RestartAccessibilityService.instance?.isBusy == true) return
        if (SystemClock.elapsedRealtime() - RestartAccessibilityService.lastBusyAt < QUIET_AFTER_OUR_ACTION_MS) return
        val fg = RestartAccessibilityService.instance?.currentForegroundPackage()
        if (fg == pkg) return
        if (fg == "com.android.settings" || fg?.contains("settings") == true) return // 사용자가 직접 끄는 중
        relaunch(c, pkg, fg, prefs)
    }

    private fun relaunch(c: Context, pkg: String, prev: String?, prefs: Prefs) {
        val nowEl = SystemClock.elapsedRealtime()
        relaunches.removeAll { nowEl - it > WINDOW_MS }
        if (relaunches.isNotEmpty() && nowEl - relaunches.last() < MIN_GAP_MS) return
        if (relaunches.size >= MAX_IN_WINDOW) {
            if (!warnedLimit) {
                warnedLimit = true
                Toast.makeText(c, "배민커넥트가 계속 꺼져요. 배터리 설정을 확인해 주세요 (배민리프레시 실험실)", Toast.LENGTH_LONG).show()
            }
            return
        }
        warnedLimit = false
        val intent = c.packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            c.startActivity(intent)
        } catch (e: Exception) {
            return
        }
        relaunches.add(nowEl)
        relaunchCount++
        lastRelaunchWall = System.currentTimeMillis()
        prefs.keepAliveLastRelaunch = lastRelaunchWall
        wasStopped = false
        Toast.makeText(c, "배민커넥트가 꺼져서 다시 켰어요", Toast.LENGTH_SHORT).show()

        // 보던 앱으로 돌아가기 (화면이 꺼져 있거나 잠겨 있으면 그대로 둠)
        val locked = (c.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        val screenOn = (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        if (locked || !screenOn || !prefs.keepAliveReturn) return
        handler.postDelayed({
            val ax = RestartAccessibilityService.instance ?: return@postDelayed
            if (prev == null || prev == pkg || isLauncher(c, prev)) ax.goHome()
            else if (!ax.resumeApp(prev)) ax.goHome()
        }, 1800L)
    }

    private fun isLauncher(c: Context, pkg: String): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return c.packageManager.queryIntentActivities(home, 0).any { it.activityInfo?.packageName == pkg }
    }

    private fun isStopped(c: Context, pkg: String): Boolean = try {
        (c.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_STOPPED) != 0
    } catch (e: Exception) {
        false
    }

    /** 설정 화면 A: 배민커넥트가 배터리 최적화에서 빠져 있는지 */
    fun isTargetBatteryFree(c: Context, pkg: String): Boolean =
        runCatching { (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(pkg) }
            .getOrDefault(false)
}
