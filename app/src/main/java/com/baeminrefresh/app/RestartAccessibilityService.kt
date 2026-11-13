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
import android.graphics.Rect

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
        // 접근성이 연결되면, 떠 있는 플로팅 버튼을 접근성 오버레이로 다시 올려 통화 화면에서도 보이게
        FloatingService.instance?.refreshOverlayType()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        FloatingService.instance?.refreshOverlayType()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        FloatingService.instance?.refreshOverlayType()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    /**
     * 현재 포커스된 입력창의 커서 위치에 텍스트를 넣는다.
     * 클립보드 붙여넣기(ACTION_PASTE)로 넣어, 플레이스홀더("메시지")에 영향받지 않고 커서 자리에 삽입된다.
     * 붙여넣기가 안 되는 경우에만 전체 텍스트 설정으로 대체.
     */
    fun insertText(text: String): Boolean {
        val node = findInputNode() ?: return false
        // 포커스가 없는 입력창이면 먼저 눌러서 포커스를 준다
        if (!node.isFocused) {
            if (!node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }

        // 1) 커서 위치에 붙여넣기
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("voice", text))
        if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true

        // 2) 대체: 기존 텍스트 뒤에 붙이기 (플레이스홀더는 제외)
        var existing = node.text?.toString() ?: ""
        val hint = if (android.os.Build.VERSION.SDK_INT >= 26) node.hintText?.toString() else null
        if (!hint.isNullOrEmpty() && existing == hint) existing = ""
        val merged = if (existing.isEmpty()) text else "$existing $text"
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, merged)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        runCatching {
            val sel = android.os.Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, merged.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, merged.length)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        }
        return ok
    }

    /**
     * 글자를 넣을 입력창을 찾는다.
     * 활성 창만 보면 키보드·오버레이 창이 잡혀 실패할 때가 있어서 화면의 모든 앱 창을 뒤진다.
     * 1) 입력 포커스가 있는 편집 가능한 칸  2) 포커스된 칸 안쪽의 편집 칸  3) 화면에 보이는 편집 칸(아래쪽 우선)
     */
    private fun findInputNode(): AccessibilityNodeInfo? {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        runCatching {
            for (w in windows) {
                if (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                if (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                val r = w.root ?: continue
                if (r.packageName == packageName) continue
                roots.add(r)
            }
        }
        val appRoots = roots.filter { it.packageName != packageName }

        // 1) 입력 포커스
        for (r in appRoots) {
            val f = runCatching { r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull() ?: continue
            if (isTextInput(f)) return f
            findEditable(f, onlyFocused = false)?.let { return it }
        }
        // 2) 포커스 표시가 된 편집 칸
        for (r in appRoots) findEditable(r, onlyFocused = true)?.let { return it }
        // 3) 보이는 편집 칸 중 가장 아래쪽(채팅 입력창 등)
        var best: AccessibilityNodeInfo? = null
        var bestBottom = -1
        for (r in appRoots) {
            for (n in collectEditables(r)) {
                val rect = android.graphics.Rect()
                n.getBoundsInScreen(rect)
                if (rect.isEmpty) continue
                if (rect.bottom > bestBottom) {
                    bestBottom = rect.bottom
                    best = n
                }
            }
        }
        return best
    }

    private fun isTextInput(n: AccessibilityNodeInfo): Boolean =
        n.isEditable || (n.className?.toString()?.contains("EditText") == true)

    private fun findEditable(root: AccessibilityNodeInfo, onlyFocused: Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        while (queue.isNotEmpty() && count < 3000) {
            val n = queue.removeFirst()
            count++
            if (isTextInput(n) && n.isVisibleToUser && (!onlyFocused || n.isFocused)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun collectEditables(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        while (queue.isNotEmpty() && count < 3000) {
            val n = queue.removeFirst()
            count++
            if (isTextInput(n) && n.isVisibleToUser) out.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return out
    }

    /** 접근성으로 화면을 캡쳐한다(권한 팝업 없음, 안드로이드 11+). 실패 시 null */
    fun captureScreen(onResult: (android.graphics.Bitmap?) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            onResult(null); return
        }
        try {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val buffer = screenshot.hardwareBuffer
                        val hw = android.graphics.Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        val soft = hw?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        buffer.close()
                        onResult(soft)
                    }

                    override fun onFailure(errorCode: Int) {
                        onResult(null)
                    }
                }
            )
        } catch (e: Exception) {
            onResult(null)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (state == State.WAIT_FORCE_STOP || state == State.WAIT_CONFIRM) step()
        if (event != null) onKakaoEvent(event)
    }

    // ---------------- 실험실: 카카오퀵 주문 알림 자동 닫기 ----------------
    // 화면 좌표를 고정하지 않고, 매번 화면에서 '상세보기'가 있는 팝업을 찾아
    // 그 팝업의 X(닫기) 버튼을 찾아 누른다. (창 위치가 바뀌어도 동작)

    private val prefs by lazy { Prefs(this) }
    private var kakaoPkgCache: String? = null
    private var kakaoPkgCheckedAt = 0L
    private var kakaoSig: String? = null
    private var kakaoSeenAt = 0L
    private var kakaoTries = 0
    private var kakaoLastCheck = 0L
    private val kakaoTimer = Runnable { checkKakao() }

    private fun kakaoPkg(): String? {
        prefs.kakaoPackage?.let { return it }
        val now = SystemClock.uptimeMillis()
        if (kakaoPkgCache == null && now - kakaoPkgCheckedAt > 60_000L || kakaoPkgCheckedAt == 0L) {
            kakaoPkgCheckedAt = now
            kakaoPkgCache = runCatching { TargetApps.detectKakaoQuick(this)?.packageName }.getOrNull()
        }
        return kakaoPkgCache
    }

    private fun onKakaoEvent(event: AccessibilityEvent) {
        if (!prefs.kakaoDismissEnabled) return
        val pkg = kakaoPkg() ?: return
        val evPkg = event.packageName?.toString()
        if (evPkg != pkg && event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        val now = SystemClock.uptimeMillis()
        if (now - kakaoLastCheck < 250L) { // 너무 자주 검사하지 않게
            handler.removeCallbacks(kakaoTimer)
            handler.postDelayed(kakaoTimer, 300L)
            return
        }
        checkKakao()
    }

    private fun checkKakao() {
        kakaoLastCheck = SystemClock.uptimeMillis()
        handler.removeCallbacks(kakaoTimer)
        if (!prefs.kakaoDismissEnabled) return
        val pkg = kakaoPkg() ?: return
        val popup = findKakaoPopup(pkg)
        if (popup == null) { // 팝업이 사라짐 → 초기화
            kakaoSig = null
            kakaoTries = 0
            return
        }
        val (root, detail) = popup
        // 숫자(남은 시간 등)는 빼고 비교 → 같은 주문이면 타이머 유지, 다른 주문이면 새로 시작
        val sig = collectTexts(root).replace(Regex("[0-9.,\\s]"), "")
        val now = SystemClock.uptimeMillis()
        if (sig != kakaoSig) {
            kakaoSig = sig
            kakaoSeenAt = now
            kakaoTries = 0
        }
        val waitMs = prefs.kakaoDismissSeconds.coerceIn(1, 10) * 1000L
        val remain = kakaoSeenAt + waitMs - now
        if (remain > 30L) {
            handler.postDelayed(kakaoTimer, remain)
            return
        }
        if (kakaoTries >= 3) return // 몇 번 눌러도 안 닫히면 포기 (무한 반복 방지)
        kakaoTries++
        dismissKakao(root, detail)
        handler.postDelayed(kakaoTimer, 800L) // 닫혔는지 확인, 안 닫혔으면 다시 시도
    }

    /** 카카오퀵 창 중 '상세보기' 글자가 있는 팝업을 찾는다. */
    private fun findKakaoPopup(pkg: String): Pair<AccessibilityNodeInfo, AccessibilityNodeInfo>? {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        runCatching { for (w in windows) w.root?.let { roots.add(it) } }
        rootInActiveWindow?.let { roots.add(it) }
        for (r in roots) {
            if (r.packageName?.toString() != pkg) continue
            val d = findNode(r) { n ->
                val t = (n.text ?: n.contentDescription)?.toString()?.replace(" ", "") ?: ""
                t.contains("상세보기") && n.isVisibleToUser
            }
            if (d != null) return r to d
        }
        return null
    }

    private fun dismissKakao(root: AccessibilityNodeInfo, detail: AccessibilityNodeInfo) {
        val detailRect = Rect().also { detail.getBoundsInScreen(it) }
        // 1) '닫기/close' 라벨이 붙은 버튼 ('거절'은 누르지 않음)
        val close = findNode(root) { n ->
            val t = listOfNotNull(n.text, n.contentDescription, n.viewIdResourceName)
                .joinToString(" ").lowercase()
            !t.contains("거절") && (t.contains("닫기") || t.contains("close") || t.trim() == "x" || t.trim() == "×")
        }
        if (close != null && clickNodeOrParent(close)) return

        // 2) 주문 글자들보다 왼쪽에 있고 같은 줄에 걸친 누를 수 있는 것 중 가장 왼쪽
        val textLeft = leftmostTextLeft(root, detailRect)
        var best: AccessibilityNodeInfo? = null
        var bestLeft = Int.MAX_VALUE
        forEachNode(root) { n ->
            if (!n.isClickable || !n.isVisibleToUser) return@forEachNode
            val r = Rect().also { n.getBoundsInScreen(it) }
            if (r.isEmpty || r.contains(detailRect)) return@forEachNode
            val sameRow = r.bottom > detailRect.top && r.top < detailRect.bottom
            if (sameRow && textLeft != null && r.right <= textLeft + 4 && r.left < bestLeft) {
                best = n; bestLeft = r.left
            }
        }
        if (best?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return

        // 3) 버튼 정보가 없으면, X가 있는 자리(팝업 왼쪽 끝 ~ 주문 글자 사이)를 직접 탭
        val rootRect = Rect().also { root.getBoundsInScreen(it) }
        val barLeft = leftmostNodeLeft(root, detailRect) ?: rootRect.left
        val x = if (textLeft != null && textLeft > barLeft) (barLeft + textLeft) / 2
        else barLeft + detailRect.height() / 2
        tap(x.toFloat(), detailRect.exactCenterY())
    }

    private fun clickNodeOrParent(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 4) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
            depth++
        }
        return false
    }

    /** 상세보기와 같은 줄에 있는 글자 중 가장 왼쪽 위치 */
    private fun leftmostTextLeft(root: AccessibilityNodeInfo, row: Rect): Int? {
        var min: Int? = null
        forEachNode(root) { n ->
            if (n.text.isNullOrBlank() || !n.isVisibleToUser) return@forEachNode
            val r = Rect().also { n.getBoundsInScreen(it) }
            if (r.isEmpty || r.left >= row.left) return@forEachNode
            if (r.bottom > row.top && r.top < row.bottom) min = minOf(min ?: r.left, r.left)
        }
        return min
    }

    /** 상세보기와 같은 줄에 걸친 화면 요소 중 가장 왼쪽 끝 (= 팝업 왼쪽 끝) */
    private fun leftmostNodeLeft(root: AccessibilityNodeInfo, row: Rect): Int? {
        var min: Int? = null
        forEachNode(root) { n ->
            if (!n.isVisibleToUser) return@forEachNode
            val r = Rect().also { n.getBoundsInScreen(it) }
            if (r.isEmpty || r.contains(row) && r.width() > row.width() * 6) return@forEachNode // 화면 전체 배경은 제외
            if (r.bottom > row.top && r.top < row.bottom) min = minOf(min ?: r.left, r.left)
        }
        return min
    }

    private fun tap(x: Float, y: Float) {
        val path = android.graphics.Path().apply { moveTo(x, y) }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60)
        runCatching {
            dispatchGesture(android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }
    }

    private fun collectTexts(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        forEachNode(root) { n -> n.text?.let { if (sb.length < 500) sb.append(it).append('|') } }
        return sb.toString()
    }

    private fun findNode(root: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        forEachNode(root) { if (found == null && pred(it)) found = it }
        return found
    }

    private fun forEachNode(root: AccessibilityNodeInfo, action: (AccessibilityNodeInfo) -> Unit) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        while (queue.isNotEmpty() && count < 1500) {
            val n = queue.removeFirst()
            count++
            action(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
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
