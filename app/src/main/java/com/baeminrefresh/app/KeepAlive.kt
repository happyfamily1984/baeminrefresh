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
import org.json.JSONArray
import org.json.JSONObject

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
 * 꺼짐 방지 토글과 상관없이(플로팅 서비스가 도는 동안) 꺼짐은 항상 '꺼짐 기록'에 남긴다.
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
        val fg = RestartAccessibilityService.instance?.currentForegroundPackage()
        val kind = if (becameStopped) "강제 중지됨" else "상시 알림(운행) 멈춤"
        // 다시 켜지 않는 이유가 있으면 기록에 같이 남긴다
        val skip = when {
            SystemClock.elapsedRealtime() < quietUntil || PhoneOptimizer.running ||
                RestartAccessibilityService.instance?.isBusy == true ||
                SystemClock.elapsedRealtime() - RestartAccessibilityService.lastBusyAt < QUIET_AFTER_OUR_ACTION_MS ->
                "배민리프레시 재시작·최적화 직후라 그대로 둠"
            fg == pkg -> "배민커넥트 화면에서 꺼짐(직접 끈 것으로 봄)"
            fg == "com.android.settings" || fg?.contains("settings") == true -> "설정 화면에서 꺼짐(직접 끈 것으로 봄)"
            !prefs.keepAliveEnabled -> "꺼짐 방지가 꺼져 있어 기록만 함"
            else -> null
        }
        if (skip != null) {
            record(c, prefs, kind, fg, skip)
            return
        }
        val result = relaunch(c, pkg, fg, prefs)
        record(c, prefs, kind, fg, result)
    }

    /** 꺼짐 기록 한 줄: 시각, 종류, 그때 보던 앱, 남은 메모리, 절전 모드·화면·배터리, 처리 결과 */
    private fun record(c: Context, prefs: Prefs, kind: String, fg: String?, result: String) {
        try {
            val am = c.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
            val bm = c.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val o = JSONObject()
                .put("t", System.currentTimeMillis())
                .put("kind", kind)
                .put("fg", fg?.let { appLabel(c, it) } ?: "알 수 없음")
                .put("memFree", mi.availMem / (1024 * 1024))
                .put("memTotal", mi.totalMem / (1024 * 1024))
                .put("lowMem", mi.lowMemory)
                .put("saver", pm.isPowerSaveMode)
                .put("screen", pm.isInteractive)
                .put("battery", bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY))
                .put("result", result)
            val arr = runCatching { JSONArray(prefs.keepAliveLog) }.getOrDefault(JSONArray())
            arr.put(o)
            val out = JSONArray()
            for (i in maxOf(0, arr.length() - 60) until arr.length()) out.put(arr.get(i))
            prefs.keepAliveLog = out.toString()
        } catch (e: Exception) {
        }
    }

    private fun appLabel(c: Context, pkg: String): String = runCatching {
        c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** 설정 화면에 보여 줄 글 (최근 것이 위) */
    fun logText(c: Context): String {
        val arr = runCatching { JSONArray(Prefs(c).keepAliveLog) }.getOrDefault(JSONArray())
        if (arr.length() == 0) return "아직 기록이 없어요."
        val fmt = java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.KOREA)
        val sb = StringBuilder()
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            sb.append("• ").append(fmt.format(java.util.Date(o.optLong("t")))).append("  ").append(o.optString("kind")).append('\n')
            sb.append("   보던 앱: ").append(o.optString("fg"))
                .append(" · 남은 메모리 ").append(o.optLong("memFree")).append("/").append(o.optLong("memTotal")).append("MB")
            if (o.optBoolean("lowMem")) sb.append(" (부족)")
            sb.append('\n')
            sb.append("   화면 ").append(if (o.optBoolean("screen")) "켜짐" else "꺼짐")
                .append(" · 배터리 ").append(o.optInt("battery")).append("%")
            if (o.optBoolean("saver")) sb.append(" · 절전 모드")
            sb.append('\n')
            sb.append("   → ").append(o.optString("result")).append("\n\n")
        }
        return sb.toString().trimEnd()
    }

    /** 기록 요약 한 줄 (카드에 표시) */
    fun logSummary(c: Context): String {
        val arr = runCatching { JSONArray(Prefs(c).keepAliveLog) }.getOrDefault(JSONArray())
        if (arr.length() == 0) return "📋 꺼짐 기록: 아직 없음"
        val dayAgo = System.currentTimeMillis() - 24 * 60 * 60_000L
        var today = 0
        var low = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optLong("t") >= dayAgo) today++
            if (o.optBoolean("lowMem")) low++
        }
        return "📋 꺼짐 기록: 최근 24시간 ${today}번 · 전체 ${arr.length()}번" +
            if (low > 0) " (그중 메모리 부족 ${low}번)" else ""
    }

    /** 다시 켜고, 기록에 남길 결과 글을 돌려준다 */
    private fun relaunch(c: Context, pkg: String, prev: String?, prefs: Prefs): String {
        val nowEl = SystemClock.elapsedRealtime()
        relaunches.removeAll { nowEl - it > WINDOW_MS }
        if (relaunches.isNotEmpty() && nowEl - relaunches.last() < MIN_GAP_MS) return "1분 안에 또 꺼져서 이번엔 그대로 둠"
        if (relaunches.size >= MAX_IN_WINDOW) {
            if (!warnedLimit) {
                warnedLimit = true
                Toast.makeText(c, "배민커넥트가 계속 꺼져요. 배터리 설정을 확인해 주세요 (배민리프레시 실험실)", Toast.LENGTH_LONG).show()
            }
            return "30분에 6번 넘게 꺼져서 그대로 둠"
        }
        warnedLimit = false
        val intent = c.packageManager.getLaunchIntentForPackage(pkg) ?: return "다시 켤 수 없음(실행 정보 없음)"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            c.startActivity(intent)
        } catch (e: Exception) {
            return "다시 켜기 실패"
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
        if (locked || !screenOn || !prefs.keepAliveReturn) return "다시 켬"
        handler.postDelayed({
            val ax = RestartAccessibilityService.instance ?: return@postDelayed
            if (prev == null || prev == pkg || isLauncher(c, prev)) ax.goHome()
            else if (!ax.resumeApp(prev)) ax.goHome()
        }, 1800L)
        return "다시 켜고 보던 앱으로 돌아감"
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
