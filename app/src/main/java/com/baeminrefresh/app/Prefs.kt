package com.baeminrefresh.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** 앱 스티커: 특정 앱의 아이콘으로 된 플로팅 버튼. 누르면 그 앱이 실행된다. */
data class Sticker(val packageName: String, val x: Int = -1, val y: Int = -1)

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("baemin_refresh", Context.MODE_PRIVATE)

    var targetPackage: String?
        get() = sp.getString(KEY_TARGET, null)
        set(value) = sp.edit().putString(KEY_TARGET, value).apply()

    /** 사용자가 플로팅 버튼을 켜 둔 상태인지 (재부팅 후 자동 표시용) */
    var floatingEnabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

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

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)

    companion object {
        const val KEY_TARGET = "target_package"
        const val KEY_ENABLED = "floating_enabled"
        const val KEY_SIZE = "button_size_dp"
        const val KEY_ALPHA = "button_alpha"
        const val KEY_X = "pos_x"
        const val KEY_Y = "pos_y"
        const val KEY_WELCOME = "welcome_shown"
        const val KEY_STICKERS = "app_stickers"

        const val MIN_SIZE_DP = 40
        const val DEFAULT_SIZE_DP = 51
        const val MIN_ALPHA = 30
        const val DEFAULT_ALPHA = 60
    }
}
