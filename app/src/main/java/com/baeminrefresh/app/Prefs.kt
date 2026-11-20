package com.baeminrefresh.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** 앱 스티커: 특정 앱의 아이콘으로 된 플로팅 버튼. 누르면 그 앱이 실행된다. */
data class Sticker(val packageName: String, val x: Int = -1, val y: Int = -1)

/**
 * 플로팅 버튼 폴더. members 는 버튼 키:
 * "main"(기본 재시작) / "rapp:패키지" / "sticker:패키지" / "capture" / "voice" / "pee"
 */
data class Folder(val id: Int, val x: Int, val y: Int, val members: List<String>)

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("baemin_refresh", Context.MODE_PRIVATE)

    init {
        // 1.21.0: 아이콘이 많아져서 기존 크기를 30% 줄인다 (한 번만)
        if (!sp.getBoolean(KEY_SIZE_SHRINK_V1, false)) {
            val e = sp.edit()
            if (sp.contains(KEY_SIZE)) {
                val old = sp.getInt(KEY_SIZE, DEFAULT_SIZE_DP)
                e.putInt(KEY_SIZE, (old * 0.7f).toInt().coerceIn(MIN_SIZE_DP, MAX_SIZE_DP))
            }
            e.putBoolean(KEY_SIZE_SHRINK_V1, true).apply()
        }
    }

    var targetPackage: String?
        get() = sp.getString(KEY_TARGET, null)
        set(value) = sp.edit().putString(KEY_TARGET, value).apply()

    /** 사용자가 플로팅 버튼을 켜 둔 상태인지 (재부팅 후 자동 표시용) */
    var floatingEnabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    /** 붙어 있는 아이콘 사이 간격 (0 = 딱 붙음) */
    var buttonGapDp: Int
        get() = sp.getInt(KEY_GAP, DEFAULT_GAP_DP)
        set(value) = sp.edit().putInt(KEY_GAP, value).apply()

    /** 실험실: 카카오퀵 주문 알림 팝업을 N초 뒤 자동으로 닫기 */
    var kakaoDismissEnabled: Boolean
        get() = sp.getBoolean(KEY_KAKAO_DISMISS, false)
        set(value) = sp.edit().putBoolean(KEY_KAKAO_DISMISS, value).apply()

    var kakaoDismissSeconds: Int
        get() = sp.getInt(KEY_KAKAO_SECONDS, 3)
        set(value) = sp.edit().putInt(KEY_KAKAO_SECONDS, value).apply()

    /** 카카오퀵 기사용 앱 패키지 (null이면 자동 감지) */
    var kakaoPackage: String?
        get() = sp.getString(KEY_KAKAO_PKG, null)
        set(value) = sp.edit().putString(KEY_KAKAO_PKG, value).apply()

    /** 실험실: 통화 소리 조절/증폭 */
    var callBoostEnabled: Boolean
        get() = sp.getBoolean(KEY_CALL_BOOST, false)
        set(value) = sp.edit().putBoolean(KEY_CALL_BOOST, value).apply()

    /** 통화 중 증폭 켜짐/꺼짐 (통화 중 뜨는 버튼으로 바꿈) */
    var callBoostActive: Boolean
        get() = sp.getBoolean(KEY_CALL_ACTIVE, true)
        set(value) = sp.edit().putBoolean(KEY_CALL_ACTIVE, value).apply()

    /** 증폭 세기 0~100 */
    var callBoostLevel: Int
        get() = sp.getInt(KEY_CALL_LEVEL, 60)
        set(value) = sp.edit().putInt(KEY_CALL_LEVEL, value).apply()

    var callPosX: Int
        get() = sp.getInt(KEY_CALL_X, -1)
        set(value) = sp.edit().putInt(KEY_CALL_X, value).apply()

    var callPosY: Int
        get() = sp.getInt(KEY_CALL_Y, -1)
        set(value) = sp.edit().putInt(KEY_CALL_Y, value).apply()

    var buttonSizeDp: Int
        get() = sp.getInt(KEY_SIZE, DEFAULT_SIZE_DP)
        set(value) = sp.edit().putInt(KEY_SIZE, value).apply()

    /** 0~100 (%) */
    var buttonAlpha: Int
        get() = sp.getInt(KEY_ALPHA, DEFAULT_ALPHA)
        set(value) = sp.edit().putInt(KEY_ALPHA, value).apply()

    var posX: Int
        get() = sp.getInt(KEY_X, -1)
        set(value) = sp.edit().putInt(KEY_X, value).apply()

    var posY: Int
        get() = sp.getInt(KEY_Y, -1)
        set(value) = sp.edit().putInt(KEY_Y, value).apply()

    var welcomeShown: Boolean
        get() = sp.getBoolean(KEY_WELCOME, false)
        set(value) = sp.edit().putBoolean(KEY_WELCOME, value).apply()

    /** 실험실: 가로모드로 바뀌면 플로팅 아이콘을 모두 일시적으로 숨기고, 세로로 돌아오면 다시 표시 */
    var labPauseStickersLandscape: Boolean
        get() = sp.getBoolean(KEY_LAB_PAUSE_LANDSCAPE, false)
        set(value) = sp.edit().putBoolean(KEY_LAB_PAUSE_LANDSCAPE, value).apply()

    /** 실험실(개인용): 워터 타이머 — 버튼을 누르면 리셋되고 경과 시간을 표시 */
    var peeTrackerEnabled: Boolean
        get() = sp.getBoolean(KEY_PEE_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_PEE_ENABLED, value).apply()

    /** 마지막으로 리셋한 시각(millis). 0이면 아직 기록 없음 */
    var peeLastReset: Long
        get() = sp.getLong(KEY_PEE_LAST, 0L)
        set(value) = sp.edit().putLong(KEY_PEE_LAST, value).apply()

    /** 워터 모드: 켜면 다른 플로팅 버튼은 숨기고 물방울 버튼만 남긴다 */
    /**
     * 워터 모드. 켜면 배달을 끝낸 것이라 '배민커넥트 꺼짐 방지'를 끄고, 끄면 배달 중이라 꺼짐 방지를 켠다
     * (반대로 움직임, 사용자 요청 1.33.1)
     */
    var waterModeEnabled: Boolean
        get() = sp.getBoolean(KEY_WATER_MODE, false)
        set(value) = sp.edit().putBoolean(KEY_WATER_MODE, value).putBoolean(KEY_KEEPALIVE_ENABLED, !value).apply()

    /** 기본 배민커넥트 재시작 버튼 표시 여부 (설정에서 켜고 끔) */
    var showMainRestart: Boolean
        get() = sp.getBoolean(KEY_SHOW_MAIN_RESTART, true)
        set(value) = sp.edit().putBoolean(KEY_SHOW_MAIN_RESTART, value).apply()

    /** 실험실: 화면 캡쳐 버튼 */
    var captureButtonEnabled: Boolean
        get() = sp.getBoolean(KEY_CAPTURE_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_CAPTURE_ENABLED, value).apply()

    var capturePosX: Int
        get() = sp.getInt(KEY_CAPTURE_X, -1)
        set(value) = sp.edit().putInt(KEY_CAPTURE_X, value).apply()

    var capturePosY: Int
        get() = sp.getInt(KEY_CAPTURE_Y, -1)
        set(value) = sp.edit().putInt(KEY_CAPTURE_Y, value).apply()

    /** 실험실: 음성 입력 버튼 */
    var voiceButtonEnabled: Boolean
        get() = sp.getBoolean(KEY_VOICE_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_VOICE_ENABLED, value).apply()

    /** 음성 인식: 인터넷 없이(기기 안에서) 인식하는 쪽을 먼저 쓴다 → 연결 끊김·지연 줄이기 */
    var voiceOffline: Boolean
        get() = sp.getBoolean(KEY_VOICE_OFFLINE, false)
        set(value) = sp.edit().putBoolean(KEY_VOICE_OFFLINE, value).apply()

    // ---------------- 폰 최적화 (실험실) ----------------

    /** 켜면 플로팅 최적화 버튼이 뜬다 */
    var optimizeEnabled: Boolean
        get() = sp.getBoolean(KEY_OPT_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_OPT_ENABLED, value).apply()

    /** 강제 중지로 깊게 정리 (접근성 필요) */
    var optimizeDeep: Boolean
        get() = sp.getBoolean(KEY_OPT_DEEP, true)
        set(value) = sp.edit().putBoolean(KEY_OPT_DEEP, value).apply()

    /** 최적화할 때 알림창의 알림도 모두 지우기 */
    var optimizeClearNotif: Boolean
        get() = sp.getBoolean(KEY_OPT_NOTIF, true)
        set(value) = sp.edit().putBoolean(KEY_OPT_NOTIF, value).apply()

    // ---------------- 일기장 쓰기 (실험실) ----------------

    /** 켜면 일기장 앱의 📖 일기 · 💡 아이디어 버튼을 배민리프레시가 대신 보여 준다 */
    var diaryEnabled: Boolean
        get() = sp.getBoolean(KEY_DIARY_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_DIARY_ENABLED, value).apply()

    /** kind = "diary" · "idea" · "reply"(답변 추천 버튼) */
    fun diaryPos(kind: String): Pair<Int, Int> =
        sp.getInt("${kind}_btn_pos_x", -1) to sp.getInt("${kind}_btn_pos_y", -1)

    fun setDiaryPos(kind: String, x: Int, y: Int) =
        sp.edit().putInt("${kind}_btn_pos_x", x).putInt("${kind}_btn_pos_y", y).apply()

    // ---------------- 답변 추천 (실험실) ----------------

    /** 켜면 플로팅 💬버튼이 뜬다 (텔레그램·카카오톡 대화, 스레드 답글을 읽고 답을 입력창에 넣음) */
    var replyEnabled: Boolean
        get() = sp.getBoolean(KEY_REPLY_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_REPLY_ENABLED, value).apply()

    /** APK 에 암호화되어 들어 있는 API 키를 푸는 비밀번호 (이 폰에만 저장) */
    var replyPassword: String
        get() = sp.getString(KEY_REPLY_PASSWORD, "") ?: ""
        set(value) = sp.edit().putString(KEY_REPLY_PASSWORD, value).apply()

    // ---------------- 배민커넥트 꺼짐 방지 (실험실) ----------------

    /** 켜면 배민커넥트가 꺼진 걸 알아채고 다시 켠다 (KeepAlive) */
    var keepAliveEnabled: Boolean
        get() = sp.getBoolean(KEY_KEEPALIVE_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_KEEPALIVE_ENABLED, value).apply()

    /** 다시 켠 뒤 보던 앱으로 돌아가기 */
    var keepAliveReturn: Boolean
        get() = sp.getBoolean("lab_keepalive_return", true)
        set(value) = sp.edit().putBoolean("lab_keepalive_return", value).apply()

    /** 꺼짐 기록 (JSON 배열, 최근 것이 뒤, 최대 60개) */
    var keepAliveLog: String
        get() = sp.getString("lab_keepalive_log", "[]") ?: "[]"
        set(value) = sp.edit().putString("lab_keepalive_log", value).apply()

    var keepAliveLastRelaunch: Long
        get() = sp.getLong("lab_keepalive_last", 0L)
        set(value) = sp.edit().putLong("lab_keepalive_last", value).apply()

    /** 답변 추천이 글을 못 찾았을 때 화면에서 읽힌 글자 (설정 화면에 보여 주고 사용자가 알려 줄 수 있게) */
    var replyLastDebug: String
        get() = sp.getString("lab_reply_debug", "") ?: ""
        set(value) = sp.edit().putString("lab_reply_debug", value).apply()

    var optPosX: Int
        get() = sp.getInt(KEY_OPT_X, -1)
        set(value) = sp.edit().putInt(KEY_OPT_X, value).apply()

    var optPosY: Int
        get() = sp.getInt(KEY_OPT_Y, -1)
        set(value) = sp.edit().putInt(KEY_OPT_Y, value).apply()

    /** 기본 보호 목록에 더한 앱 / 뺀 앱 (패키지 이름) */
    var optProtectAdd: Set<String>
        get() = sp.getStringSet(KEY_OPT_ADD, emptySet())?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_OPT_ADD, HashSet(value)).apply()

    var optProtectRemove: Set<String>
        get() = sp.getStringSet(KEY_OPT_REMOVE, emptySet())?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_OPT_REMOVE, HashSet(value)).apply()

    var voicePosX: Int
        get() = sp.getInt(KEY_VOICE_X, -1)
        set(value) = sp.edit().putInt(KEY_VOICE_X, value).apply()

    var voicePosY: Int
        get() = sp.getInt(KEY_VOICE_Y, -1)
        set(value) = sp.edit().putInt(KEY_VOICE_Y, value).apply()

    /** 물방울 버튼 위치 (-1이면 기본 위치) */
    var peePosX: Int
        get() = sp.getInt(KEY_PEE_X, -1)
        set(value) = sp.edit().putInt(KEY_PEE_X, value).apply()

    var peePosY: Int
        get() = sp.getInt(KEY_PEE_Y, -1)
        set(value) = sp.edit().putInt(KEY_PEE_Y, value).apply()

    /** 오늘 워터 횟수 (날짜가 바뀌면 0부터) */
    val peeCountToday: Int
        get() = if (sp.getString(KEY_PEE_COUNT_DATE, "") == today()) sp.getInt(KEY_PEE_COUNT, 0) else 0

    /** 워터 1회 기록 (오늘 횟수 +1) */
    fun bumpPeeCount() {
        val t = today()
        val c = if (sp.getString(KEY_PEE_COUNT_DATE, "") == t) sp.getInt(KEY_PEE_COUNT, 0) else 0
        sp.edit().putString(KEY_PEE_COUNT_DATE, t).putInt(KEY_PEE_COUNT, c + 1).apply()
    }

    private fun today(): String = java.time.LocalDate.now().toString()

    fun resetPosition() {
        sp.edit().remove(KEY_X).remove(KEY_Y).apply()
    }

    // ---------------- 앱 스티커 ----------------

    fun getStickers(): List<Sticker> {
        val raw = sp.getString(KEY_STICKERS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Sticker(o.getString("pkg"), o.optInt("x", -1), o.optInt("y", -1))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun setStickers(list: List<Sticker>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(JSONObject().put("pkg", s.packageName).put("x", s.x).put("y", s.y))
        }
        sp.edit().putString(KEY_STICKERS, arr.toString()).apply()
    }

    fun addSticker(pkg: String) {
        if (getStickers().any { it.packageName == pkg }) return
        setStickers(getStickers() + Sticker(pkg))
    }

    fun removeSticker(pkg: String) {
        setStickers(getStickers().filterNot { it.packageName == pkg })
    }

    fun updateStickerPosition(pkg: String, x: Int, y: Int) {
        setStickers(getStickers().map { if (it.packageName == pkg) it.copy(x = x, y = y) else it })
    }

    // ---------------- 리프레시(재시작) 앱 ----------------

    fun getRestartApps(): List<Sticker> {
        val raw = sp.getString(KEY_RESTART_APPS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Sticker(o.getString("pkg"), o.optInt("x", -1), o.optInt("y", -1))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun setRestartApps(list: List<Sticker>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(JSONObject().put("pkg", s.packageName).put("x", s.x).put("y", s.y))
        }
        sp.edit().putString(KEY_RESTART_APPS, arr.toString()).apply()
    }

    fun addRestartApp(pkg: String) {
        if (getRestartApps().any { it.packageName == pkg }) return
        setRestartApps(getRestartApps() + Sticker(pkg))
    }

    fun removeRestartApp(pkg: String) {
        setRestartApps(getRestartApps().filterNot { it.packageName == pkg })
    }

    fun updateRestartAppPosition(pkg: String, x: Int, y: Int) {
        setRestartApps(getRestartApps().map { if (it.packageName == pkg) it.copy(x = x, y = y) else it })
    }

    // ---------------- 폴더 ----------------

    fun getFolders(): List<Folder> {
        val raw = sp.getString(KEY_FOLDERS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val m = o.getJSONArray("members")
                Folder(o.getInt("id"), o.optInt("x", -1), o.optInt("y", -1), (0 until m.length()).map { m.getString(it) })
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun setFolders(list: List<Folder>) {
        val arr = JSONArray()
        for (f in list) {
            arr.put(
                JSONObject().put("id", f.id).put("x", f.x).put("y", f.y)
                    .put("members", JSONArray().apply { f.members.forEach { put(it) } })
            )
        }
        sp.edit().putString(KEY_FOLDERS, arr.toString()).apply()
    }

    /** 폴더를 만든다. 폴더 위치는 첫 번째 버튼이 있던 자리(없으면 기본 위치). */
    fun addFolder(members: List<String>) {
        val list = getFolders()
        val id = (list.maxOfOrNull { it.id } ?: 0) + 1
        val (x, y) = members.firstNotNullOfOrNull { k -> savedPosOfKey(k).takeIf { it.first >= 0 && it.second >= 0 } }
            ?: (-1 to -1)
        setFolders(list + Folder(id, x, y, members))
    }

    fun setFolderMembers(id: Int, members: List<String>) {
        setFolders(getFolders().map { if (it.id == id) it.copy(members = members) else it })
    }

    fun addToFolder(id: Int, keys: List<String>) {
        setFolders(getFolders().map { if (it.id == id) it.copy(members = (it.members + keys).distinct()) else it })
    }

    fun removeFolder(id: Int) {
        setFolders(getFolders().filterNot { it.id == id })
    }

    fun updateFolderPosition(id: Int, x: Int, y: Int) {
        setFolders(getFolders().map { if (it.id == id) it.copy(x = x, y = y) else it })
    }

    /** 없어진 버튼은 폴더에서 빼고, 버튼이 2개 미만인 폴더는 없앤다. 바뀐 게 있을 때만 저장. */
    fun pruneFolders(valid: Set<String>) {
        val old = getFolders()
        val new = old.map { f -> f.copy(members = f.members.filter { it in valid }) }.filter { it.members.size >= 2 }
        if (new != old) setFolders(new)
    }

    fun savedPosOfKey(key: String): Pair<Int, Int> = when {
        key == "main" -> posX to posY
        key == "capture" -> capturePosX to capturePosY
        key == "voice" -> voicePosX to voicePosY
        key == "pee" -> peePosX to peePosY
        key == "optimize" -> optPosX to optPosY
        key == "diary" || key == "idea" || key == "reply" -> diaryPos(key)
        key.startsWith("rapp:") -> getRestartApps().firstOrNull { it.packageName == key.removePrefix("rapp:") }
            ?.let { it.x to it.y } ?: (-1 to -1)
        key.startsWith("sticker:") -> getStickers().firstOrNull { it.packageName == key.removePrefix("sticker:") }
            ?.let { it.x to it.y } ?: (-1 to -1)
        else -> -1 to -1
    }

    fun setSavedPosOfKey(key: String, x: Int, y: Int) {
        when {
            key == "main" -> { posX = x; posY = y }
            key == "capture" -> { capturePosX = x; capturePosY = y }
            key == "voice" -> { voicePosX = x; voicePosY = y }
            key == "pee" -> { peePosX = x; peePosY = y }
            key == "optimize" -> { optPosX = x; optPosY = y }
            key == "diary" || key == "idea" || key == "reply" -> setDiaryPos(key, x, y)
            key.startsWith("rapp:") -> updateRestartAppPosition(key.removePrefix("rapp:"), x, y)
            key.startsWith("sticker:") -> updateStickerPosition(key.removePrefix("sticker:"), x, y)
        }
    }

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)

    companion object {
        const val KEY_TARGET = "target_package"
        const val KEY_ENABLED = "floating_enabled"
        const val KEY_SIZE = "button_size_dp"
        const val KEY_FOLDERS = "folders"
        const val KEY_OPT_ENABLED = "lab_opt_enabled"
        const val KEY_OPT_DEEP = "lab_opt_deep"
        const val KEY_OPT_NOTIF = "lab_opt_notif"
        const val KEY_OPT_X = "opt_pos_x"
        const val KEY_OPT_Y = "opt_pos_y"
        const val KEY_DIARY_ENABLED = "lab_diary_enabled"
        const val KEY_REPLY_ENABLED = "lab_reply_enabled"
        const val KEY_KEEPALIVE_ENABLED = "lab_keepalive_enabled"
        const val KEY_REPLY_PASSWORD = "lab_reply_password"
        const val KEY_OPT_ADD = "opt_protect_add"
        const val KEY_OPT_REMOVE = "opt_protect_remove"
        const val KEY_VOICE_OFFLINE = "voice_offline"
        const val KEY_CALL_BOOST = "lab_call_boost"
        const val KEY_CALL_ACTIVE = "lab_call_active"
        const val KEY_CALL_LEVEL = "lab_call_level"
        const val KEY_CALL_X = "call_pos_x"
        const val KEY_CALL_Y = "call_pos_y"
        const val KEY_KAKAO_DISMISS = "lab_kakao_dismiss"
        const val KEY_KAKAO_SECONDS = "lab_kakao_seconds"
        const val KEY_KAKAO_PKG = "lab_kakao_pkg"
        const val KEY_SIZE_SHRINK_V1 = "size_shrink_v1_done"
        const val KEY_ALPHA = "button_alpha"
        const val KEY_X = "pos_x"
        const val KEY_Y = "pos_y"
        const val KEY_WELCOME = "welcome_shown"
        const val KEY_STICKERS = "app_stickers"
        const val KEY_RESTART_APPS = "restart_apps"
        const val KEY_LAB_PAUSE_LANDSCAPE = "lab_pause_stickers_landscape"
        const val KEY_PEE_ENABLED = "pee_tracker_enabled"
        const val KEY_PEE_LAST = "pee_last_reset"
        const val KEY_PEE_COUNT = "pee_count"
        const val KEY_PEE_COUNT_DATE = "pee_count_date"
        const val KEY_WATER_MODE = "water_mode"
        const val KEY_SHOW_MAIN_RESTART = "show_main_restart"
        const val KEY_CAPTURE_ENABLED = "capture_enabled"
        const val KEY_CAPTURE_X = "capture_pos_x"
        const val KEY_CAPTURE_Y = "capture_pos_y"
        const val KEY_VOICE_ENABLED = "voice_enabled"
        const val KEY_VOICE_X = "voice_pos_x"
        const val KEY_VOICE_Y = "voice_pos_y"
        const val KEY_PEE_X = "pee_pos_x"
        const val KEY_PEE_Y = "pee_pos_y"

        const val MIN_SIZE_DP = 24
        const val MAX_SIZE_DP = 100
        const val DEFAULT_SIZE_DP = 40
        const val SIZE_SMALL_DP = 30
        const val MAX_GAP_DP = 120
        const val DEFAULT_GAP_DP = 2
        const val KEY_GAP = "button_gap_dp"
        const val SIZE_LARGE_DP = 50
        const val MIN_ALPHA = 30
        const val DEFAULT_ALPHA = 60
    }
}
