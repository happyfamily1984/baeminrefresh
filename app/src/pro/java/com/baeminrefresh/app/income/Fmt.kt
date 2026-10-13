package com.baeminrefresh.app.income

import java.text.NumberFormat
import java.util.Locale

object Fmt {
    private val nf = NumberFormat.getInstance(Locale.KOREA)

    fun won(amount: Long): String = "${nf.format(amount)}원"
    fun num(n: Long): String = nf.format(n)

    /** 캘린더 셀처럼 좁은 곳: 1.2만, 35.0만 식으로 짧게 */
    fun shortWon(amount: Long): String = when {
        amount == 0L -> ""
        amount >= 10000 -> String.format(Locale.KOREA, "%.1f만", amount / 10000.0)
        else -> nf.format(amount)
    }
}
