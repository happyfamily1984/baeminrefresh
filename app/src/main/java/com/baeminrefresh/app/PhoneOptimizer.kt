package com.baeminrefresh.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 폰 최적화 (실험실).
 * 1) 최근 쓴 앱 중 보호 목록에 없는 앱을 자동으로 골라 백그라운드에서 종료
 * 2) (켜져 있으면) 접근성으로 '앱 정보 → 강제 중지'를 눌러 깊게 종료 — 이미 꺼진 앱은 건너뜀
 * 3) (켜져 있으면) 알림창의 알림 모두 지우기
 * 4) 쓰던 앱으로 돌아가고 결과(메모리 여유 전/후)를 알려 줌
 * 안드로이드는 다른 앱의 메모리를 직접 비우는 걸 허용하지 않아서, 앱을 종료하는 방식으로만 가능하다.
 */
object PhoneOptimizer {

    interface Listener {
        fun onProgress(done: Int, total: Int) {}
        fun onFinished() {}
    }

    class Plan(val targets: List<String>, val usageKnown: Boolean)
    class Mem(val availMb: Long, val totalMb: Long)
    private class Usage(val lastUsed: Long, val lastFgService: Long)

    private val listeners = CopyOnWriteArraySet<Listener>()
    fun addListener(l: Listener) {
        listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    @Volatile
    var running = false
        private set
    var doneCount = 0
        private set
    var totalCount = 0
        private set
    private var cancelled = false

    /** 마지막 최적화가 끝까지 끝났는지 (플로팅 버튼을 파랗게 표시). 새로 시작하거나 멈추면 false */
    @Volatile
    var lastCompleted = false
        private set
    private val handler = Handler(Looper.getMainLooper())

    /** 기본 보호 앱: 앱 이름에 이 글자가 들어 있으면 보호 (공백 없이, 소문자) */
    private val KEYWORDS = listOf(
        "배민커넥트", "픽커", "쿠팡이츠", "claude", "클로드", "muse", "피클",
        "chatgpt", "네이버지도", "navermap", "유튜브", "youtube", "전화", "문자", "메시지"
    )

    /** 이름이 달라도 확실히 보호할 앱 패키지 */
    private val KNOWN_PACKAGES = listOf(
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.anthropic.claude",
        "com.openai.chatgpt",
        "com.nhn.android.nmap",
        "com.samsung.android.dialer",
        "com.samsung.android.messaging",
        "com.google.android.dialer",
        "com.google.android.apps.messaging"
    )

    // ---------------- 보호 목록 ----------------

    /** 항상 보호: 이 앱, 내가 지정한 앱들, 전화·문자·키보드·홈·접근성·알림 접근 앱 */
    fun alwaysProtected(ctx: Context): Set<String> {
        val prefs = Prefs(ctx)
        val s = HashSet<String>()
        s += ctx.packageName
        prefs.targetPackage?.let { s += it }
        (prefs.kakaoPackage ?: runCatching { TargetApps.detectKakaoQuick(ctx)?.packageName }.getOrNull())?.let { s += it }
        prefs.getStickers().forEach { s += it.packageName }
        prefs.getRestartApps().forEach { s += it.packageName }
        runCatching { ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()?.let { s += it }
        runCatching { Telephony.Sms.getDefaultSmsPackage(ctx) }.getOrNull()?.let { s += it }
        runCatching {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { s += it }
        runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            ctx.packageManager.resolveActivity(home, 0)?.activityInfo?.packageName
        }.getOrNull()?.let { s += it }
        runCatching {
            val am = ctx.getSystemService(AccessibilityManager::class.java)
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .forEach { s += it.resolveInfo.serviceInfo.packageName }
        }
        runCatching { NotificationManagerCompat.getEnabledListenerPackages(ctx).forEach { s += it } }
        return s
    }

    /** 사용자가 고치기 전의 기본 보호 앱 (이름·패키지로 찾은 것) */
    fun keywordDefaults(ctx: Context): Set<String> {
        val set = HashSet<String>()
        for (e in TargetApps.launchableApps(ctx)) {
            val label = e.label.replace(" ", "").lowercase()
            if (KEYWORDS.any { label.contains(it) }) set += e.packageName
        }
        val pm = ctx.packageManager
        for (p in KNOWN_PACKAGES) if (runCatching { pm.getApplicationInfo(p, 0) }.isSuccess) set += p
        return set
    }

    fun effectiveProtected(ctx: Context): Set<String> {
        val prefs = Prefs(ctx)
        return alwaysProtected(ctx) + ((keywordDefaults(ctx) + prefs.optProtectAdd) - prefs.optProtectRemove)
    }

    // ---------------- 대상 고르기 ----------------

    fun isUsageAccessGranted(ctx: Context): Boolean = try {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        false
    }

    private fun recentUsage(ctx: Context, now: Long): Map<String, Usage>? {
        if (!isUsageAccessGranted(ctx)) return null
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val stats = usm.queryAndAggregateUsageStats(now - DAY_MS, now)
            stats.mapValues { (_, u) ->
                Usage(u.lastTimeUsed, if (Build.VERSION.SDK_INT >= 29) u.lastTimeForegroundServiceUsed else 0L)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 최근 24시간 안에 쓴 일반 앱 중 보호 목록에 없는 것. 사용 기록 접근이 없으면 보호 목록 밖의 모든 일반 앱. */
    fun buildTargets(ctx: Context, extraSkip: Set<String>): Plan {
        val pm = ctx.packageManager
        val protectedSet = effectiveProtected(ctx) + extraSkip
        val now = System.currentTimeMillis()
        val usage = recentUsage(ctx, now)
        val out = ArrayList<String>()
        for (e in TargetApps.launchableApps(ctx)) {
            val pkg = e.packageName
            if (pkg in protectedSet) continue
            val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: continue
            if (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) continue
            if (usage != null) {
                val u = usage[pkg] ?: continue
                if (maxOf(u.lastUsed, u.lastFgService) < now - DAY_MS) continue // 하루 넘게 안 쓴 앱은 이미 정리돼 있음
                if (u.lastFgService >= now - FG_SERVICE_SAFE_MS) continue // 방금까지 뭔가 실행 중이던 앱(길안내·음악 등)은 건드리지 않음
            }
            out += pkg
        }
        return Plan(out, usage != null)
    }

    private fun isStopped(ctx: Context, pkg: String): Boolean = try {
        (ctx.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_STOPPED) != 0
    } catch (e: Exception) {
        false
    }

    // ---------------- 상태 ----------------

    fun memory(ctx: Context): Mem {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return Mem(mi.availMem / MB, mi.totalMem / MB)
    }

    fun uptimeDays(): Long = SystemClock.elapsedRealtime() / DAY_MS

    fun statusText(ctx: Context): String {
        val m = memory(ctx)
        val usedPct = if (m.totalMb > 0) (100 - m.availMb * 100 / m.totalMb) else 0
        val freeGb = runCatching { StatFs(Environment.getDataDirectory().path).availableBytes / 1_000_000_000.0 }
            .getOrDefault(0.0)
        val up = SystemClock.elapsedRealtime()
        val days = up / DAY_MS
        val hours = (up % DAY_MS) / (60 * 60 * 1000L)
        val reboot = if (days >= REBOOT_ADVICE_DAYS) " — 재부팅을 권해요" else ""
        return "메모리 사용 ${usedPct}% (여유 ${m.availMb}MB / 전체 ${m.totalMb}MB)\n" +
            "저장공간 여유 ${"%.1f".format(freeGb)}GB\n" +
            "마지막 재부팅 후 ${days}일 ${hours}시간$reboot"
    }

    // ---------------- 실행 ----------------

    fun cancel() {
        if (running) cancelled = true
    }

    fun start(context: Context, deep: Boolean) {
        if (running) return
        val app = context.applicationContext
        val prefs = Prefs(app)
        val svc = RestartAccessibilityService.instance
        running = true
        cancelled = false
        lastCompleted = false
        doneCount = 0
        totalCount = 0

        val prev = svc?.currentForegroundPackage() // 최적화가 끝나면 이 앱으로 돌아간다 (이 앱은 정리하지도 않는다)
        val before = memory(app)
        val plan = try {
            buildTargets(app, setOfNotNull(prev))
        } catch (e: Exception) {
            Plan(emptyList(), false)
        }
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (pkg in plan.targets) runCatching { am.killBackgroundProcesses(pkg) }

        val deepList = if (deep && svc != null) plan.targets.filter { !isStopped(app, it) } else emptyList()
        totalCount = deepList.size
        notifyProgress()

        var forced = 0
        var navigated = false // 설정 화면·알림창을 열었으면 끝에 원래 화면으로 돌아가야 함

        fun report(notifMsg: String?) {
            val after = memory(app)
            val diff = after.availMb - before.availMb
            val lines = ArrayList<String>()
            lines += if (cancelled) "⏹ 최적화를 멈췄어요" else "✅ 최적화 완료"
            lines += "메모리 여유 ${before.availMb}MB → ${after.availMb}MB (${if (diff >= 0) "+" else ""}${diff}MB)"
            if (deep && svc != null) lines += "깊게 정리: ${forced}개 종료"
            else if (deep) lines += "접근성을 켜면 깊게 정리해요"
            notifMsg?.let { lines += it }
            if (!plan.usageKnown) lines += "사용 기록 접근을 허용하면 더 빨라요"
            if (uptimeDays() >= REBOOT_ADVICE_DAYS) lines += "재부팅한 지 ${uptimeDays()}일 — 재부팅을 권해요"
            Toast.makeText(app, lines.joinToString("\n"), Toast.LENGTH_LONG).show()
            lastCompleted = !cancelled
            running = false
            notifyFinished()
        }

        fun conclude(notifMsg: String?) {
            if (navigated && svc != null) returnToPrev(svc, app, prev)
            // 앱이 종료되고 메모리가 정리될 시간을 조금 주고 측정
            handler.postDelayed({ report(notifMsg) }, 1500L)
        }

        fun finish() {
            if (prefs.optimizeClearNotif && !cancelled) {
                clearNotifications(app, svc) { msg, usedShade ->
                    if (usedShade) navigated = true
                    conclude(msg)
                }
            } else {
                conclude(null)
            }
        }

        fun next(i: Int, retry: Int) {
            if (cancelled || svc == null || i >= deepList.size) {
                finish()
                return
            }
            navigated = true
            val started = svc.forceStopOnly(deepList[i]) { result ->
                if (result == RestartAccessibilityService.Result.RESTARTED) forced++
                doneCount = i + 1
                notifyProgress()
                handler.postDelayed({ next(i + 1, 0) }, 150L)
            }
            if (!started) { // 다른 자동 동작이 진행 중이면 잠깐 기다렸다 다시
                if (retry < 10) handler.postDelayed({ next(i, retry + 1) }, 500L) else next(i + 1, 0)
            }
        }

        if (deepList.isEmpty()) finish() else next(0, 0)
    }

    private fun returnToPrev(svc: RestartAccessibilityService, app: Context, prev: String?) {
        val home = runCatching {
            app.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
            )?.activityInfo?.packageName
        }.getOrNull()
        val goHome = prev == null || prev == app.packageName || prev == home || prev == "com.android.systemui"
        if (goHome || !svc.resumeApp(prev!!)) svc.goHome()
    }

    /**
     * 알림창의 알림을 모두 지운다.
     * 알림 접근이 허용돼 있으면 바로 지우고, 아니면 접근성으로 알림창을 내려 '모두 지우기'를 누른다.
     * @param onDone (결과 문구, 알림창을 직접 열었는지)
     */
    fun clearNotifications(ctx: Context, svc: RestartAccessibilityService?, onDone: (String, Boolean) -> Unit) {
        if (NotificationCleaner.clearAll()) {
            onDone("알림창 알림 모두 지움", false)
        } else if (svc != null) {
            svc.clearNotificationsViaShade { ok ->
                onDone(if (ok) "알림창 알림 모두 지움" else "알림 지우기 실패 (알림 접근을 허용해 보세요)", true)
            }
        } else {
            onDone("알림 접근을 허용하면 알림도 지워요", false)
        }
    }

    /** 알림만 지우기 (설정 화면의 버튼) */
    fun clearNotificationsOnly(context: Context) {
        val app = context.applicationContext
        clearNotifications(app, RestartAccessibilityService.instance) { msg, usedShade ->
            if (usedShade) RestartAccessibilityService.instance?.goHome()
            Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun notifyProgress() {
        for (l in listeners) l.onProgress(doneCount, totalCount)
    }

    private fun notifyFinished() {
        for (l in listeners) l.onFinished()
    }

    private const val MB = 1024L * 1024L
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val FG_SERVICE_SAFE_MS = 30L * 60 * 1000
    const val REBOOT_ADVICE_DAYS = 3L
}
