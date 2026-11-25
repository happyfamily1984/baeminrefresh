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
        callVolume.start() // 실험실: 통화 소리 조절 (꺼져 있으면 아무것도 안 함)
        // 접근성이 연결되면, 떠 있는 플로팅 버튼을 접근성 오버레이로 다시 올려 통화 화면에서도 보이게
        FloatingService.instance?.refreshOverlayType()
    }

    val callVolume by lazy { CallVolume(this) }

    /**
     * 삼성 통화 화면의 '추가 음량(Extra volume)' 버튼을 켜거나 끈다. 통화 화면이 보일 때만 가능.
     * @return 원하는 상태로 만들었으면 true
     */
    private fun isCallScreenPkg(pkg: String): Boolean =
        pkg.contains("incallui") || pkg.contains("dialer") || pkg.contains("telecom") ||
            pkg == "com.samsung.android.app.telephonyui"

    private fun callScreenRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        runCatching { for (w in windows) w.root?.let { roots.add(it) } }
        return roots.filter { isCallScreenPkg(it.packageName?.toString() ?: "") }
    }

    /** 통화 화면이 지금 보이는지 */
    fun callScreenVisible(): Boolean = callScreenRoots().isNotEmpty()

    private fun isExtraVolumeNode(n: AccessibilityNodeInfo): Boolean {
        val t = listOfNotNull(n.text, n.contentDescription).joinToString(" ").replace(" ", "").lowercase()
        return n.isVisibleToUser && EXTRA_VOLUME_LABELS.any { t.contains(it) }
    }

    /** 통화 화면에 보이는 버튼·글자 (못 찾았을 때 안내용) */
    fun callScreenLabels(): String {
        val seen = LinkedHashSet<String>()
        for (r in callScreenRoots()) forEachNode(r) { n ->
            if (!n.isVisibleToUser) return@forEachNode
            val t = listOfNotNull(n.text, n.contentDescription).joinToString(" ").trim()
            if (t.isNotEmpty() && t.length <= 20 && !t.any { it.isDigit() }) seen += t // 번호·시간은 빼서 개인정보가 안 남게
        }
        return if (seen.isEmpty()) "통화 화면을 못 찾음" else seen.take(15).joinToString(", ")
    }

    private var extraMenuTried = false

    /** 새 통화마다 '더보기 메뉴에서 찾기'를 다시 한 번 할 수 있게 */
    fun resetExtraVolumeMenuTry() { extraMenuTried = false }

    /**
     * '추가 음량'이 통화 화면에 바로 안 보이면 '더보기(⋮)' 메뉴를 한 번 열어 그 안에서 찾는다.
     * 메뉴에도 없으면 메뉴만 닫는다(뒤로). 통화마다 한 번만.
     */
    fun trySamsungExtraVolumeViaMenu(onDone: (Boolean) -> Unit) {
        if (extraMenuTried) { onDone(false); return }
        val root = rootInActiveWindow
        if (root == null || !isCallScreenPkg(root.packageName?.toString() ?: "")) { onDone(false); return }
        val more = findNode(root) { n ->
            val t = listOfNotNull(n.contentDescription, n.text).joinToString(" ").replace(" ", "").lowercase()
            n.isVisibleToUser && (t.contains("더보기") || t.contains("추가옵션") || t.contains("moreoptions") || t == "more")
        }
        if (more == null) { onDone(false); return }
        extraMenuTried = true
        val windowsBefore = runCatching { windows.size }.getOrDefault(0)
        if (!clickableOf(more).performAction(AccessibilityNodeInfo.ACTION_CLICK)) { onDone(false); return }
        handler.postDelayed({
            var ok = false
            val roots = mutableListOf<AccessibilityNodeInfo>()
            runCatching { for (w in windows) w.root?.let { roots.add(it) } }
            for (r in roots) {
                val node = findNode(r) { isExtraVolumeNode(it) } ?: continue
                ok = clickableOf(node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (ok) break
            }
            if (!ok && runCatching { windows.size }.getOrDefault(0) > windowsBefore) {
                performGlobalAction(GLOBAL_ACTION_BACK) // 열린 메뉴만 닫기
            }
            onDone(ok)
        }, 700L)
    }

    fun setSamsungExtraVolume(on: Boolean): Boolean {
        for (r in callScreenRoots()) {
            val node = findNode(r) { isExtraVolumeNode(it) } ?: continue
            // 켜짐 상태를 알 수 있으면 다를 때만 누른다
            var target: AccessibilityNodeInfo? = node
            var depth = 0
            while (target != null && !target.isClickable && depth < 4) { target = target.parent; depth++ }
            val t = target ?: return false
            val stateKnown = t.isCheckable || node.isCheckable
            val isOn = t.isChecked || node.isChecked || t.isSelected || node.isSelected
            if (stateKnown && isOn == on) return true
            if (!stateKnown && !on) return t.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return t.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        return false
    }

    override fun onDestroy() {
        callVolume.stop()
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

    // ---------------- 답변 추천: 대화 읽기 / 입력창 채우기 ----------------

    /**
     * mode = "chat"(카카오톡·텔레그램 대화방: text = 화면 대화) 또는 "thread"(스레드 답글: text = 답글 달 사람의 글만,
     * target = 그 사람 아이디). draft = 입력창에 쓰던 글.
     */
    class ChatScreen(
        val pkg: String, val text: String, val draft: String,
        val mode: String = "chat", val target: String = "", val debug: String = "",
    )

    private fun isThreadsPkg(pkg: String?): Boolean = pkg == THREADS_PKG

    private fun isChatPkg(pkg: String?): Boolean =
        pkg != null && (pkg == "com.kakao.talk" || pkg.contains("telegram") || pkg == "org.thunderdog.challegram" ||
            isThreadsPkg(pkg))

    /** 지금 화면에 떠 있는 텔레그램·카카오톡·스레드 창 (없으면 null) */
    private fun chatRoot(): AccessibilityNodeInfo? {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        runCatching {
            for (w in windows) {
                if (w.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue
                w.root?.let { roots.add(it) }
            }
        }
        return roots.firstOrNull { isChatPkg(it.packageName?.toString()) }
    }

    private fun chatInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        collectEditables(root).maxByOrNull { n -> Rect().also { n.getBoundsInScreen(it) }.bottom }

    /**
     * 텔레그램·카카오톡 화면에 보이는 글자를 위→아래 순서로 읽는다. (사용자가 버튼을 눌렀을 때만 호출)
     * 말풍선이 오른쪽에 있으면 [오른쪽](대개 내가 보낸 말), 왼쪽이면 [왼쪽] 표시를 붙인다.
     * 텔레그램·카카오톡이 아니면 null.
     */
    fun readChat(): ChatScreen? {
        val root = chatRoot() ?: return null
        if (isThreadsPkg(root.packageName?.toString())) return readThread(root)
        val input = chatInput(root)
        val inputTop = input?.let { n -> Rect().also { n.getBoundsInScreen(it) }.top } ?: Int.MAX_VALUE
        val screenW = resources.displayMetrics.widthPixels
        class Item(val top: Int, val left: Int, val line: String)
        val items = ArrayList<Item>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        while (queue.isNotEmpty() && count < 4000) {
            val n = queue.removeFirst()
            count++
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            if (isTextInput(n) || !n.isVisibleToUser) continue
            val t = (n.text ?: n.contentDescription)?.toString()?.trim() ?: continue
            if (t.isEmpty()) continue
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.isEmpty || r.top >= inputTop) continue // 입력창 줄과 그 아래(보내기 버튼, 키보드)는 제외
            val side = when {
                r.width() >= screenW * 0.9f -> ""
                r.centerX() > screenW * 0.55f -> "[오른쪽] "
                r.centerX() < screenW * 0.45f -> "[왼쪽] "
                else -> ""
            }
            items.add(Item(r.top, r.left, side + t.take(600).replace('\n', ' ')))
        }
        items.sortWith(compareBy({ it.top }, { it.left }))
        val lines = ArrayList<String>()
        for (it in items) if (lines.lastOrNull() != it.line) lines.add(it.line)
        // 너무 길면 아래쪽(최근 대화)만 남긴다
        var total = 0
        val kept = ArrayList<String>()
        for (l in lines.asReversed()) {
            if (total + l.length > 3000 && kept.isNotEmpty()) break
            kept.add(l)
            total += l.length
        }
        var draft = input?.text?.toString() ?: ""
        val hint = input?.hintText?.toString()
        if (!hint.isNullOrEmpty() && draft == hint) draft = ""
        return ChatScreen(root.packageName.toString(), kept.asReversed().joinToString("\n"), draft.trim())
    }

    // ---------------- 스레드(Threads) 답글 ----------------
    // 답글 입력칸의 안내 글자("siwon9012님에게 답글 남기기…")에서 상대 아이디를 알아내고,
    // 화면에서 그 아이디 바로 아래에 있는 본문만 읽는다. 원글·다른 답글·이미지 속 글자·링크 미리보기·
    // 좋아요 수 같은 화면 글자는 넣지 않는다(엉뚱한 답이 나오지 않게).

    private class TNode(val node: AccessibilityNodeInfo, val text: String, val rect: Rect)

    private fun nodeLabel(n: AccessibilityNodeInfo): String {
        val parts = listOfNotNull(n.text, n.hintText, n.contentDescription)
        return parts.joinToString(" ") { it.toString() }
    }

    /** 답글 입력칸(접힌 칸 포함): "…님에게 답글" / "Reply to …" 글자가 있는 가장 아래쪽 노드 */
    private fun threadReplyBox(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestTop = -1
        forEachNode(root) { n ->
            val l = nodeLabel(n)
            if (THREAD_HINT_KO.containsMatchIn(l) || THREAD_HINT_EN.containsMatchIn(l)) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (!r.isEmpty && r.top > bestTop) {
                    bestTop = r.top
                    best = n
                }
            }
        }
        return best
    }

    private fun threadTarget(box: AccessibilityNodeInfo?): String {
        val l = box?.let { nodeLabel(it) } ?: return ""
        THREAD_HINT_KO.find(l)?.let { return it.groupValues[1].trim() }
        THREAD_HINT_EN.find(l)?.let { return it.groupValues[1].trim().trimEnd('.', '…') }
        return ""
    }

    private fun readThread(root: AccessibilityNodeInfo): ChatScreen {
        val pkg = root.packageName.toString()
        val box = threadReplyBox(root)
        val input = chatInput(root)
        val target = threadTarget(box ?: input)
        val boxTop = listOfNotNull(box, input).minOfOrNull { n -> Rect().also { n.getBoundsInScreen(it) }.top }
            ?: Int.MAX_VALUE

        // 화면의 글자 칸(text)과 설명(contentDescription)을 따로 모은다.
        // 본문은 글자 칸에서만 읽고(사진 속 글자·이미지 설명에 흔들리지 않게), 설명은 아이디 찾기에만 쓴다.
        val texts = ArrayList<TNode>()
        val descs = ArrayList<TNode>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        while (queue.isNotEmpty() && count < 4000) {
            val n = queue.removeFirst()
            count++
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            if (isTextInput(n) || !n.isVisibleToUser) continue
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.isEmpty || r.top >= boxTop) continue
            n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { texts.add(TNode(n, it, r)) }
            n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { descs.add(TNode(n, it, r)) }
        }
        val order = compareBy<TNode>({ it.rect.top }, { it.rect.left })
        texts.sortWith(order)
        descs.sortWith(order)

        // 아이디 칸: 글자가 아이디와 같거나("siwon9012"), 아이디로 시작하는 칸("siwon9012 16시간", "siwon9012, …")
        fun isName(t: String): Boolean = target.isNotEmpty() &&
            (t == target || t.startsWith("$target ") || t.startsWith("$target,") || t.startsWith("$target\n") ||
                t.startsWith("$target·") || t.startsWith("$target •") || t == "@$target")
        val nameText = texts.lastOrNull { isName(it.text) }
        val nameDesc = descs.lastOrNull { isName(it.text) }
        val name: TNode? = when {
            target.isEmpty() -> texts.lastOrNull { HANDLE.matches(it.text) } // 안내 글자가 없으면 가장 아래쪽 아이디
            nameText != null && nameDesc != null -> if (nameDesc.rect.top > nameText.rect.top) nameDesc else nameText
            else -> nameText ?: nameDesc
        }

        var body = ""
        if (name != null) {
            val lines = ArrayList<String>()
            for (it in texts) {
                if (it.rect.top < name.rect.top) continue
                if (it.node == name.node || it.text == name.text) continue
                // 아이디와 같은 줄(시간 "16시간", "> 커뮤니티" 등)은 건너뜀
                if (it.rect.centerY() in name.rect.top..name.rect.bottom) continue
                // 아이디 칸이 글 전체를 감싸는 큰 칸이면 그 안의 글자만
                if (name.rect.height() > dpPx(80) && !name.rect.contains(it.rect.centerX(), it.rect.centerY())) break
                val t = it.text
                if (THREAD_SKIP.matches(t)) continue
                // 여기서부터는 본문이 아님: 링크 미리보기, 좋아요·답글 수, '활동 보기', 다음 사람 아이디
                if (DOMAIN.matches(t) || COUNT.matches(t) || t == "활동 보기" || t == "View activity") break
                if (lines.isNotEmpty() && (HANDLE.matches(t) || isName(t))) break
                lines.add(t)
                if (lines.sumOf { l -> l.length } > 2000) break
            }
            body = lines.joinToString("\n")
            // 아이디·시간·본문이 한 칸에 합쳐진 화면: 그 칸의 글에서 아이디와 시간을 떼어 낸다
            if (body.isBlank() && name.text.length > target.length + 3) {
                body = name.text.removePrefix("@").removePrefix(target)
                    .replace(Regex("^[\\s,·•]*[0-9]+\\s*(초|분|시간|일|주|개월|년|s|m|h|d|w)(\\s*전)?[\\s,·•]*"), "")
                    .trim(' ', ',', '·', '•', '\n')
            }
        }
        var draft = input?.text?.toString()?.trim() ?: ""
        val hint = input?.hintText?.toString()
        if ((!hint.isNullOrEmpty() && draft == hint) || THREAD_HINT_KO.containsMatchIn(draft) ||
            THREAD_HINT_EN.containsMatchIn(draft)) draft = ""
        // 못 찾았을 때 안내용: 입력칸 바로 위쪽에서 읽힌 글자 몇 개
        val debug = if (body.isNotBlank()) "" else (texts + descs).sortedWith(order).takeLast(8)
            .joinToString(" | ") { it.text.replace('\n', ' ').take(25) }
        return ChatScreen(pkg, body, draft, mode = "thread", target = target, debug = debug)
    }

    private fun dpPx(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * 답글을 입력칸에 넣는다(전송·게시는 누르지 않음). 스레드처럼 입력칸이 접혀 있으면 먼저 눌러 펼친 뒤 다시 시도.
     * 결과는 onDone(성공 여부)로.
     */
    fun fillReply(text: String, onDone: (Boolean) -> Unit) {
        val h = Handler(Looper.getMainLooper())
        fun attempt(left: Int) {
            val root = chatRoot()
            val hasInput = root?.let { chatInput(it) } != null
            if (hasInput || root == null || !isThreadsPkg(root.packageName?.toString())) {
                onDone(setChatInput(text))
                return
            }
            if (left <= 0) {
                onDone(false)
                return
            }
            // 접힌 답글 칸("…님에게 답글 남기기")을 눌러 입력창을 연다
            threadReplyBox(root)?.let { clickNodeOrParent(it) }
            h.postDelayed({ attempt(left - 1) }, 700L)
        }
        attempt(3)
    }

    /** 대화방 입력창의 글을 통째로 바꾼다(전송은 누르지 않음). 안 되면 붙여넣기로 대체. */
    fun setChatInput(text: String): Boolean {
        val node = chatRoot()?.let { chatInput(it) } ?: return insertText(text)
        if (!node.isFocused) {
            if (!node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return insertText(text)
        runCatching {
            val sel = android.os.Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        }
        return true
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

    /**
     * 픽커 주문 알림 팝업을 찾는다.
     * 오른쪽 버튼 글자가 '상세보기' / '제휴오더 상세' / '수락' 등으로 계속 바뀌므로 버튼 글자에 의존하지 않는다.
     * 대신 이런 창을 알림 팝업으로 본다:
     *  - 픽커 앱의 창이고, 거리 글자(예: 800m, 15.9km)가 있으며
     *  - 화면 일부만 차지하는 작은 팝업이거나 앱 본 화면이 아닌 창 (앱 안의 주문 화면은 제외)
     * 반환: (창, 주문 글자들이 놓인 줄의 영역)
     */
    private fun findKakaoPopup(pkg: String): Pair<AccessibilityNodeInfo, Rect>? {
        val screenH = resources.displayMetrics.heightPixels
        val roots = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
        runCatching { for (w in windows) w.root?.let { roots.add(it to w.type) } }
        rootInActiveWindow?.let { roots.add(it to -1) }
        val distance = Regex("\\d+(\\.\\d+)?\\s*k?m")
        for ((r, type) in roots) {
            if (r.packageName?.toString() != pkg) continue
            val rb = Rect().also { r.getBoundsInScreen(it) }
            val smallPopup = !rb.isEmpty && rb.height() < screenH * 0.6f
            val notMainWindow = type != -1 &&
                type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
            if (!smallPopup && !notMainWindow) continue
            var hasDistance = false
            var row: Rect? = null
            forEachNode(r) { n ->
                if (!n.isVisibleToUser) return@forEachNode
                val t = (n.text ?: n.contentDescription)?.toString()?.trim() ?: ""
                if (t.isEmpty()) return@forEachNode
                if (distance.containsMatchIn(t.replace(" ", ""))) hasDistance = true
                val b = Rect().also { n.getBoundsInScreen(it) }
                if (b.isEmpty) return@forEachNode
                row = row?.apply { union(b) } ?: Rect(b)
            }
            val rowRect = row ?: continue
            if (!hasDistance) continue
            return r to rowRect
        }
        return null
    }

    /** 누르면 안 되는 버튼 (주문 수락·상세·거절) */
    private fun isForbidden(n: AccessibilityNodeInfo): Boolean {
        val t = listOfNotNull(n.text, n.contentDescription).joinToString(" ").replace(" ", "")
        return t.contains("수락") || t.contains("상세") || t.contains("거절") || t.contains("배차")
    }

    private fun dismissKakao(root: AccessibilityNodeInfo, row: Rect) {
        // 1) '닫기/close' 라벨이 붙은 버튼
        val close = findNode(root) { n ->
            val t = listOfNotNull(n.text, n.contentDescription, n.viewIdResourceName)
                .joinToString(" ").lowercase()
            !isForbidden(n) && (t.contains("닫기") || t.contains("close") || t.trim() == "x" || t.trim() == "×")
        }
        if (close != null && clickNodeOrParent(close)) return

        // 주문 글자 중 가장 왼쪽 위치 (X 버튼은 그보다 왼쪽에 있다)
        val textLeft = leftmostTextLeft(root, row)

        // 2) 주문 글자보다 왼쪽, 같은 줄에 걸친 누를 수 있는 것 중 가장 왼쪽
        var best: AccessibilityNodeInfo? = null
        var bestLeft = Int.MAX_VALUE
        forEachNode(root) { n ->
            if (!n.isClickable || !n.isVisibleToUser || isForbidden(n)) return@forEachNode
            val r = Rect().also { n.getBoundsInScreen(it) }
            if (r.isEmpty) return@forEachNode
            val sameRow = r.bottom > row.top && r.top < row.bottom
            if (sameRow && textLeft != null && r.right <= textLeft + 4 && r.left < bestLeft) {
                best = n; bestLeft = r.left
            }
        }
        if (best?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return

        // 3) 버튼 정보가 없으면, X 자리(팝업 왼쪽 끝 ~ 주문 글자 사이, 줄 가운데)를 직접 탭
        val rootRect = Rect().also { root.getBoundsInScreen(it) }
        val barLeft = leftmostNodeLeft(root, row) ?: rootRect.left
        val left = textLeft ?: row.left
        val x = if (left > barLeft + 8) (barLeft + left) / 2 else barLeft + row.height() / 4
        tap(x.toFloat(), row.exactCenterY())
    }

    private fun clickNodeOrParent(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 4) {
            if (isForbidden(cur)) return false
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
            depth++
        }
        return false
    }

    /** 줄에 있는 글자 중 가장 왼쪽 위치 */
    private fun leftmostTextLeft(root: AccessibilityNodeInfo, row: Rect): Int? {
        var min: Int? = null
        forEachNode(root) { n ->
            if (n.text.isNullOrBlank() || !n.isVisibleToUser) return@forEachNode
            val r = Rect().also { n.getBoundsInScreen(it) }
            if (r.isEmpty) return@forEachNode
            if (r.bottom > row.top && r.top < row.bottom) min = minOf(min ?: r.left, r.left)
        }
        return min
    }

    /** 줄에 걸친 화면 요소 중 가장 왼쪽 끝 (= 팝업 왼쪽 끝) */
    private fun leftmostNodeLeft(root: AccessibilityNodeInfo, row: Rect): Int? {
        val screenW = resources.displayMetrics.widthPixels
        var min: Int? = null
        forEachNode(root) { n ->
            if (!n.isVisibleToUser) return@forEachNode
            val r = Rect().also { n.getBoundsInScreen(it) }
            if (r.isEmpty || r.width() >= screenW && r.height() > row.height() * 3) return@forEachNode // 화면 전체 배경 제외
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
    fun restart(pkg: String, onDone: (Result) -> Unit): Boolean = begin(pkg, onDone, relaunch = true)

    /**
     * 강제 중지만 하고 다시 실행하지는 않는다 (폰 최적화용).
     * 결과: RESTARTED = 종료함, NOT_RUNNING_LAUNCHED = 이미 꺼져 있었음, 그 외 = 실패
     */
    fun forceStopOnly(pkg: String, onDone: (Result) -> Unit): Boolean = begin(pkg, onDone, relaunch = false)

    private var relaunchAfter = true

    private fun begin(pkg: String, onDone: (Result) -> Unit, relaunch: Boolean): Boolean {
        if (isBusy) return false
        relaunchAfter = relaunch
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
        lastBusyAt = SystemClock.elapsedRealtime()
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
        if (!launch || !relaunchAfter || pkg == null) {
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

    // ---------------- 폰 최적화 도우미 ----------------

    /** 지금 화면에 떠 있는 앱의 패키지 (모르면 null) */
    fun currentForegroundPackage(): String? =
        runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()

    /** 이전에 쓰던 앱으로 돌아간다 (앱 상태는 그대로, 새로 시작하지 않음) */
    fun resumeApp(pkg: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    /** 전원 메뉴(끄기/다시 시작)를 연다. 재시작은 직접 눌러야 한다. */
    fun openPowerMenu(): Boolean = performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)

    /**
     * 알림창을 내려서 '모두 지우기' 버튼을 접근성으로 누른다.
     * 버튼은 id/글자로 찾고, 눌러지지 않으면 그 자리를 직접 탭한다. 안 보이면 알림 목록을 내려가며 찾는다.
     * @param onDone (성공 여부, 실패했을 때 화면에서 본 글자들 — 원인 파악용)
     */
    fun clearNotificationsViaShade(onDone: (Boolean, String) -> Unit) {
        if (!performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)) {
            onDone(false, "알림창을 열지 못했어요")
            return
        }
        var tries = 0
        var clicks = 0
        val r = object : Runnable {
            override fun run() {
                tries++
                val roots = shadeRoots()
                val node = roots.firstNotNullOfOrNull { root -> findNode(root) { isClearAllNode(it) } }
                if (node != null) {
                    if (clicks >= 3) { // 눌러도 계속 남아 있음
                        finishShade(false, onDone)
                        return
                    }
                    clicks++
                    var ok = clickNodeOrParent(node)
                    if (!ok) {
                        val b = Rect().also { node.getBoundsInScreen(it) }
                        if (!b.isEmpty) {
                            tap(b.exactCenterX(), b.exactCenterY())
                            ok = true
                        }
                    }
                    // 누른 뒤 버튼이 사라졌는지 확인하러 다시 온다
                    handler.postDelayed(this, 900L)
                    return
                }
                if (clicks > 0) { // 눌렀고 버튼이 사라짐 = 성공
                    finishShade(true, onDone)
                    return
                }
                if (tries >= 18) {
                    val seen = shadeTexts(roots)
                    finishShade(false, onDone, "화면에서 찾은 글자: $seen")
                    return
                }
                // 아직 안 보임: 알림창이 열리는 중이거나, 알림이 많아서 버튼이 아래에 있음
                if (tries >= 4 && tries % 3 == 1) scrollShadeDown(roots)
                handler.postDelayed(this, 450L)
            }
        }
        handler.postDelayed(r, 900L)
    }

    private fun finishShade(ok: Boolean, onDone: (Boolean, String) -> Unit, hint: String = "") {
        performGlobalAction(GLOBAL_ACTION_BACK) // 알림창 닫기
        onDone(ok, hint)
    }

    /** 알림창을 그리는 시스템 화면(systemui)의 창들 */
    private fun shadeRoots(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        runCatching {
            for (w in windows) {
                val r = w.root ?: continue
                if (r.packageName?.toString() == "com.android.systemui") out.add(r)
            }
        }
        return out
    }

    private fun isClearAllNode(n: AccessibilityNodeInfo): Boolean {
        if (!n.isVisibleToUser) return false
        val id = n.viewIdResourceName?.lowercase() ?: ""
        if (id.contains("clear_all") || id.contains("clearall") || id.contains("dismiss_all")) return true
        val t = listOfNotNull(n.text, n.contentDescription).joinToString("").replace(" ", "").lowercase()
        if (t.isEmpty() || t.length > 12) return false // 긴 글자는 알림 내용이라 제외
        return t.contains("모두지우기") || t.contains("모두삭제") || t.contains("전체삭제") ||
            t.contains("clearall") || t.contains("dismissall")
    }

    private fun shadeTexts(roots: List<AccessibilityNodeInfo>): String {
        val seen = LinkedHashSet<String>()
        for (r in roots) forEachNode(r) { n ->
            val t = listOfNotNull(n.text, n.contentDescription).joinToString("").trim()
            if (t.isNotEmpty() && t.length <= 12 && n.isVisibleToUser && (n.isClickable || n.isLongClickable)) seen += t
        }
        return if (seen.isEmpty()) "없음" else seen.take(10).joinToString(", ")
    }

    private fun scrollShadeDown(roots: List<AccessibilityNodeInfo>) {
        val scrolled = roots.any { r ->
            findNode(r) { it.isScrollable && it.isVisibleToUser }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
        }
        if (!scrolled) { // 스크롤 정보를 못 주는 화면이면 직접 위로 쓸어 올린다
            val dm = resources.displayMetrics
            swipe(dm.widthPixels / 2f, dm.heightPixels * 0.70f, dm.heightPixels * 0.30f)
        }
    }

    private fun swipe(x: Float, y1: Float, y2: Float) {
        val path = android.graphics.Path().apply {
            moveTo(x, y1)
            lineTo(x, y2)
        }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 250)
        runCatching {
            dispatchGesture(android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }
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
        /** 마지막으로 재시작·강제 중지 단계를 밟은 시각(elapsedRealtime) — 꺼짐 방지가 우리 동작에 반응하지 않게 */
        @Volatile
        var lastBusyAt = 0L
        private val EXTRA_VOLUME_LABELS = listOf(
            "추가음량", "추가볼륨", "음량추가", "볼륨추가", "음량높이기", "볼륨높이기", "음량키우기",
            "음량증폭", "볼륨부스트", "extravolume", "volumeboost"
        )
        const val THREADS_PKG = "com.instagram.barcelona"
        private val THREAD_HINT_KO = Regex("^\\s*(.+?)님에게 답글")
        private val THREAD_HINT_EN = Regex("^\\s*Reply to (.+?)(\\.\\.\\.|…|$)")
        private val HANDLE = Regex("^(?=.*[A-Za-z])[A-Za-z0-9._]{2,30}$")
        private val DOMAIN = Regex("^[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.(com|net|org|kr|co|io|me|tv|ly|be|app|news)(/\\S*)?$")
        private val COUNT = Regex("^[0-9][0-9,.]*\\s*[천만KkMm]?$")
        private val THREAD_SKIP = Regex("^(더 보기|번역 보기|원문 보기|수정됨|작성자|See translation|more|Edited|Author|[0-9]+\\s*(초|분|시간|일|주|개월|년|s|m|h|d|w))$")

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
