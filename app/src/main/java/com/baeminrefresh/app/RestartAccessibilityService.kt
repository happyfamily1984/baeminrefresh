package com.baeminrefresh.app

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 대상 앱의 '앱 정보' 화면을 열고 '강제 중지' → '확인'을 자동으로 눌러 앱을 종료한 뒤,
 * 대상 앱을 다시 실행한다. (일반 앱은 다른 앱을 직접 종료할 수 없어서 이 방법을 쓴다)
 */
class RestartAccessibilityService : AccessibilityService() {

    enum class Result { RESTARTED, NOT_RUNNING_LAUNCHED, TIMEOUT_LAUNCHED, FAILED }

    private enum class State { IDLE, WAIT_FORCE_STOP, WAIT_CONFIRM, LAUNCHING }

    private val handler = Handler(Looper.getMainLooper())
    private var state = State.IDLE
    private var targetPkg: String? = null
    private var startedAt = 0L
    private var stateAt = 0L
    private var callback: ((Result) -> Unit)? = null

    private val tick = object : Runnable {
        override fun run() {
            if (state == State.IDLE || state == State.LAUNCHING) return
            step()
            if (state == State.WAIT_FORCE_STOP || state == State.WAIT_CONFIRM) {
                handler.postDelayed(this, POLL_MS)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (state == State.WAIT_FORCE_STOP || state == State.WAIT_CONFIRM) step()
    }

    val isBusy: Boolean get() = state != State.IDLE

    /** @return 시작했으면 true, 이미 진행 중이면 false */
    fun restart(pkg: String, onDone: (Result) -> Unit): Boolean {
        if (isBusy) return false
        targetPkg = pkg
        callback = onDone
        startedAt = SystemClock.uptimeMillis()
        setState(State.WAIT_FORCE_STOP)

        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", pkg, null)
        ).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                Intent.FLAG_ACTIVITY_NO_HISTORY or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        )
        try {
            startActivity(intent)
        } catch (e: Exception) {
            finish(Result.FAILED, launch = false)
            return true
        }
        handler.postDelayed(tick, POLL_MS)
        return true
    }

    private fun setState(s: State) {
        state = s
        stateAt = SystemClock.uptimeMillis()
    }

    private fun step() {
        val now = SystemClock.uptimeMillis()
        if (now - startedAt > TIMEOUT_MS) {
            finish(Result.TIMEOUT_LAUNCHED, launch = true)
            return
        }
        val root = rootInActiveWindow ?: return
        val rootPkg = root.packageName?.toString()
        // 아직 설정 화면이 안 떴으면(대상 앱이나 우리 앱이 보이는 중) 기다린다
        if (rootPkg == targetPkg || rootPkg == packageName) return

        when (state) {
            State.WAIT_FORCE_STOP -> {
                val button = findForceStopButton(root) ?: return
                val clickable = clickableOf(button)
                if (!clickable.isEnabled) {
                    // 이미 꺼져 있는 상태 → 바로 실행만
                    finish(Result.NOT_RUNNING_LAUNCHED, launch = true)
                } else if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    setState(State.WAIT_CONFIRM)
                }
            }
            State.WAIT_CONFIRM -> {
                val ok = findConfirmButton(root)
                if (ok != null && clickableOf(ok).performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    finish(Result.RESTARTED, launch = true)
                } else if (now - stateAt > CONFIRM_WAIT_MS) {
                    // 확인 창 없이 바로 종료되는 기기
                    val button = findForceStopButton(root)
                    val stopped = button != null && !clickableOf(button).isEnabled
                    finish(if (stopped) Result.RESTARTED else Result.TIMEOUT_LAUNCHED, launch = true)
                }
            }
            else -> Unit
        }
    }

    private fun finish(result: Result, launch: Boolean) {
        handler.removeCallbacks(tick)
        val pkg = targetPkg
        val cb = callback
        callback = null
        if (!launch || pkg == null) {
            setState(State.IDLE)
            cb?.invoke(result)
            return
        }
        setState(State.LAUNCHING)
        handler.postDelayed({
            val launched = launchApp(pkg)
            setState(State.IDLE)
            cb?.invoke(if (launched) result else Result.FAILED)
        }, LAUNCH_DELAY_MS)
    }

    private fun launchApp(pkg: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---- 화면에서 버튼 찾기 ----

    private fun findForceStopButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in FORCE_STOP_IDS) {
            root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()?.let { return it }
        }
        return findByExactText(root, FORCE_STOP_TEXTS)
    }

    private fun findConfirmButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 표준 AlertDialog 의 '확인' 버튼
        root.findAccessibilityNodeInfosByViewId("android:id/button1")?.firstOrNull()?.let { return it }
        findByExactText(root, CONFIRM_TEXTS)?.let { return it }
        // 확인 버튼 글자가 '강제 중지'인 기기: 같은 화면에 '취소'가 있을 때만(=대화상자일 때만) 누른다
        if (findByExactText(root, CANCEL_TEXTS) != null) {
            val candidate = findByExactText(root, FORCE_STOP_TEXTS)
            if (candidate != null && candidate.viewIdResourceName !in FORCE_STOP_IDS) return candidate
        }
        return null
    }

    private fun findByExactText(root: AccessibilityNodeInfo, texts: List<String>): AccessibilityNodeInfo? {
        for (t in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(t) ?: continue
            nodes.firstOrNull { n ->
                val s = (n.text ?: n.contentDescription)?.toString()?.trim() ?: return@firstOrNull false
                s.equals(t, ignoreCase = true)
            }?.let { return it }
        }
        return null
    }

    private fun clickableOf(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 5) {
            if (n.isClickable) return n
            n = n.parent
            depth++
        }
        return node
    }

    companion object {
        @Volatile
        var instance: RestartAccessibilityService? = null
            private set

        private const val POLL_MS = 250L
        private const val TIMEOUT_MS = 8000L
        private const val CONFIRM_WAIT_MS = 2500L
        private const val LAUNCH_DELAY_MS = 600L

        private val FORCE_STOP_IDS = listOf("com.android.settings:id/force_stop_button")
        private val FORCE_STOP_TEXTS = listOf(
            "강제 중지", "강제중지", "강제 종료", "강제종료", "Force stop", "Force Stop", "FORCE STOP"
        )
        private val CONFIRM_TEXTS = listOf("확인", "OK", "예")
        private val CANCEL_TEXTS = listOf("취소", "Cancel")

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = ComponentName(context, RestartAccessibilityService::class.java)
            return enabled.split(':').any {
                ComponentName.unflattenFromString(it) == me
            }
        }
    }
}
