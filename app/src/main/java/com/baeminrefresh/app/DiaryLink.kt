package com.baeminrefresh.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * 일기장 앱(io.github.happyfamily1984.diary)과의 연결.
 * 버튼은 배민리프레시가 그리고, 녹음·전송은 일기장 앱이 한다.
 *  - call("attach"/"detach")  : 우리가 버튼을 보여 주는 동안 일기장 앱의 자기 버튼을 숨김/되돌림
 *  - call("toggle", kind)     : 탭 → 녹음 시작/끝. "need_open" 이면 일기장 앱을 한 번 열어야 녹음을 시작할 수 있음
 *  - 일기장 앱이 STATE_ACTION 방송으로 상태(idle/recording/sending)를 알려 줌
 */
object DiaryLink {
    const val PKG = "io.github.happyfamily1984.diary"
    const val STATE_ACTION = "io.github.happyfamily1984.diary.STATE"
    const val KIND_DIARY = "diary"
    const val KIND_IDEA = "idea"
    private val URI: Uri = Uri.parse("content://io.github.happyfamily1984.diary.link")

    fun isInstalled(ctx: Context): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(PKG, 0) }.isSuccess

    /** 일기장 앱에 명령을 보낸다. 앱이 없거나 연결을 지원하지 않는 옛 버전이면 null. (메인 스레드에서 부르지 말 것) */
    fun call(ctx: Context, method: String, arg: String? = null): Bundle? =
        runCatching { ctx.contentResolver.call(URI, method, arg, null) }.getOrNull()

    /** 일기장 앱 화면 열기. open = 최근 일기/아이디어 페이지로, record = 녹음을 시작하고 바로 돌아옴 */
    fun activityIntent(open: String? = null, record: String? = null): Intent =
        Intent().setComponent(ComponentName(PKG, "$PKG.MainActivity")).apply {
            if (open != null) putExtra("open", open)
            if (record != null) putExtra("record", record)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
}
