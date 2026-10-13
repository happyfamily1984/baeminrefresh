package com.baeminrefresh.app

import android.content.Context
import android.content.Intent

/**
 * rider(지사 배포용) 버전에는 수입 기록 기능이 없다. 모두 설정 화면만 연다.
 */
object IncomeFeature {
    const val enabled = false

    /** 플로팅 버튼 길게 누르기 → 설정 화면 */
    fun onFloatingLongPress(context: Context) {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    /** 메인 화면에서 수입 기록 열기 (rider는 사용 안 함) */
    fun openTracker(context: Context) {}
}
