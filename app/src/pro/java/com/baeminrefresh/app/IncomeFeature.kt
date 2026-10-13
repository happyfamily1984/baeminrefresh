package com.baeminrefresh.app

import android.content.Context
import android.content.Intent
import com.baeminrefresh.app.income.IncomeActivity
import com.baeminrefresh.app.income.QuickAddActivity

/**
 * pro 버전: 수입 기록 기능이 켜져 있다.
 */
object IncomeFeature {
    const val enabled = true

    /** 플로팅 버튼 길게 누르기 → 오늘 수입 빠른 입력 */
    fun onFloatingLongPress(context: Context) {
        context.startActivity(
            Intent(context, QuickAddActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** 메인 화면 '수입 기록 열기' → 캘린더/통계 */
    fun openTracker(context: Context) {
        context.startActivity(Intent(context, IncomeActivity::class.java))
    }
}
