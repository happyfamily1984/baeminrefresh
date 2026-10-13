package com.baeminrefresh.app.income

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.baeminrefresh.app.R
import com.google.android.material.button.MaterialButton
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * 월별 캘린더 + 요약 + 통계 화면. 배달판처럼 플랫폼별 수입을 모아 보여 준다.
 */
class IncomeActivity : AppCompatActivity() {

    private lateinit var repo: IncomeRepository
    private var month: YearMonth = YearMonth.now()
    private var monthData: Map<String, DayRecord> = emptyMap()

    private val importPicker =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) handlePickedFile(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = IncomeRepository.get(this)
        if (!repo.isSignedIn) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_income)

        findViewById<TextView>(R.id.income_email).text = repo.userEmail ?: ""
        findViewById<View>(R.id.income_logout).setOnClickListener { confirmLogout() }
        findViewById<View>(R.id.month_prev).setOnClickListener { month = month.minusMonths(1); reload() }
        findViewById<View>(R.id.month_next).setOnClickListener { month = month.plusMonths(1); reload() }
        findViewById<MaterialButton>(R.id.income_add_today).setOnClickListener {
            openDate(LocalDate.now().toString())
        }
        findViewById<MaterialButton>(R.id.income_import).setOnClickListener {
            try {
                importPicker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*"))
            } catch (e: Exception) {
                Toast.makeText(this, "파일 선택기를 열 수 없어요", Toast.LENGTH_SHORT).show()
            }
        }
        buildWeekdayHeader()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        findViewById<TextView>(R.id.month_label).text =
            month.format(DateTimeFormatter.ofPattern("yyyy년 M월"))
        repo.loadMonth(month.toString()) { data, err ->
            if (err != null) {
                Toast.makeText(this, "불러오기 실패: $err", Toast.LENGTH_SHORT).show()
            }
            monthData = data
            renderSummary()
            renderPlatforms()
            renderCalendar()
            loadComparison()
        }
    }

    // ---------------- 요약 ----------------

    private fun renderSummary() {
        val totalCount = monthData.values.sumOf { it.totalCount }
        val totalAmount = monthData.values.sumOf { it.totalAmount }
        val workingDays = monthData.values.count { !it.isEmpty }
        val perDelivery = if (totalCount > 0) (totalAmount.toDouble() / totalCount).roundToInt().toLong() else 0L
        val perDay = if (workingDays > 0) (totalAmount.toDouble() / workingDays).roundToInt().toLong() else 0L

        val promo = monthData.values.sumOf { it.totalPromo }
        val extra = monthData.values.sumOf { it.totalExtra }

        findViewById<TextView>(R.id.sum_count).text = Fmt.num(totalCount)
        findViewById<TextView>(R.id.sum_amount).text = Fmt.won(totalAmount)
        findViewById<TextView>(R.id.sum_sub).text =
            "근무 ${workingDays}일 · 건당 ${Fmt.won(perDelivery)} · 일 평균 ${Fmt.won(perDay)}\n" +
                "프로모션 ${Fmt.won(promo)} · 과적 ${Fmt.won(extra)} 포함"
    }

    private fun loadComparison() {
        val prev = month.minusMonths(1)
        repo.loadMonth(prev.toString()) { data, _ ->
            val prevTotal = data.values.sumOf { it.totalAmount }
            val cur = monthData.values.sumOf { it.totalAmount }
            val view = findViewById<TextView>(R.id.sum_compare)
            if (prevTotal == 0L) {
                view.text = "지난달 기록이 없어요"
                view.setTextColor(ContextCompat.getColor(this, R.color.text_sub))
                return@loadMonth
            }
            val diff = cur - prevTotal
            val pct = (diff.toDouble() / prevTotal * 100).roundToInt()
            when {
                diff > 0 -> {
                    view.text = "지난달보다 ▲ ${Fmt.won(diff)} (+${pct}%)"
                    view.setTextColor(ContextCompat.getColor(this, R.color.ok))
                }
                diff < 0 -> {
                    view.text = "지난달보다 ▼ ${Fmt.won(-diff)} (${pct}%)"
                    view.setTextColor(ContextCompat.getColor(this, R.color.warn))
                }
                else -> {
                    view.text = "지난달과 같아요"
                    view.setTextColor(ContextCompat.getColor(this, R.color.text_sub))
                }
            }
        }
    }

    // ---------------- 플랫폼별 ----------------

    private fun renderPlatforms() {
        val container = findViewById<LinearLayout>(R.id.platform_container)
        container.removeAllViews()
        val maxAmount = Platform.values().maxOf { p -> monthData.values.sumOf { it.platformTotal(p) } }.coerceAtLeast(1)
        for (p in Platform.values()) {
            val amount = monthData.values.sumOf { it.platformTotal(p) }
            val count = monthData.values.sumOf { it.count(p) }
            container.addView(platformBar(p, count, amount, maxAmount))
        }
    }

    private fun platformBar(p: Platform, count: Long, amount: Long, maxAmount: Long): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(TextView(this).apply {
            text = p.label
            setTextColor(Color.parseColor(p.colorHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(TextView(this).apply {
            text = "${Fmt.num(count)}건 · ${Fmt.won(amount)}"
            setTextColor(ContextCompat.getColor(this@IncomeActivity, R.color.text_main))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })
        row.addView(top)

        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)).apply {
                topMargin = dp(4)
            }
            background = ContextCompat.getDrawable(this@IncomeActivity, R.drawable.bg_bar_track)
        }
        val frac = (amount.toDouble() / maxAmount).toFloat().coerceIn(0f, 1f)
        if (frac > 0f) {
            track.addView(View(this).apply {
                background = ContextCompat.getDrawable(this@IncomeActivity, R.drawable.bg_bar_fill)
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(p.colorHex))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, frac)
            })
        }
        track.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f - frac)
        })
        row.addView(track)
        return row
    }

    // ---------------- 캘린더 ----------------

    private fun buildWeekdayHeader() {
        val header = findViewById<LinearLayout>(R.id.weekday_header)
        val days = listOf("일", "월", "화", "수", "목", "금", "토")
        for ((i, d) in days.withIndex()) {
            header.addView(TextView(this).apply {
                text = d
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(
                    when (i) {
                        0 -> Color.parseColor("#E53935")
                        6 -> Color.parseColor("#1E88E5")
                        else -> ContextCompat.getColor(this@IncomeActivity, R.color.text_sub)
                    }
                )
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    private fun renderCalendar() {
        val grid = findViewById<GridLayout>(R.id.calendar_grid)
        grid.removeAllViews()
        grid.columnCount = 7

        val first = month.atDay(1)
        // 일요일 시작 기준 앞쪽 빈칸 수
        val lead = first.dayOfWeek.value % 7 // MONDAY=1..SUNDAY=7 → SUNDAY→0
        val daysInMonth = month.lengthOfMonth()
        val today = LocalDate.now().toString()

        val cellWidth = (resources.displayMetrics.widthPixels - dp(56)) / 7

        for (i in 0 until lead) grid.addView(emptyCell(cellWidth))
        for (day in 1..daysInMonth) {
            val date = month.atDay(day).toString()
            grid.addView(dayCell(day, date, monthData[date], date == today, cellWidth))
        }
    }

    private fun emptyCell(width: Int): View = View(this).apply {
        layoutParams = GridLayout.LayoutParams().apply {
            this.width = width
            this.height = dp(58)
        }
    }

    private fun dayCell(day: Int, date: String, record: DayRecord?, isToday: Boolean, width: Int): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), dp(4), dp(2), dp(4))
            layoutParams = GridLayout.LayoutParams().apply {
                this.width = width
                this.height = dp(58)
            }
            background = ContextCompat.getDrawable(this@IncomeActivity, R.drawable.bg_day_cell)
            val cellColor = if (record != null && !record.isEmpty) R.color.tip_bg else R.color.card
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this@IncomeActivity, cellColor)
            )
            isClickable = true
            setOnClickListener { openDate(date) }
        }
        val dow = LocalDate.parse(date).dayOfWeek
        cell.addView(TextView(this).apply {
            text = day.toString()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(
                when {
                    isToday -> Color.parseColor("#2AC1BC")
                    dow == DayOfWeek.SUNDAY -> Color.parseColor("#E53935")
                    dow == DayOfWeek.SATURDAY -> Color.parseColor("#1E88E5")
                    else -> ContextCompat.getColor(this@IncomeActivity, R.color.text_main)
                }
            )
            if (isToday) setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        if (record != null && !record.isEmpty) {
            cell.addView(TextView(this).apply {
                text = Fmt.shortWon(record.totalAmount)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTextColor(ContextCompat.getColor(this@IncomeActivity, R.color.mint_dark))
                maxLines = 1
            })
            cell.addView(TextView(this).apply {
                text = "${record.totalCount}건"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                setTextColor(ContextCompat.getColor(this@IncomeActivity, R.color.text_sub))
                maxLines = 1
            })
        }
        return cell
    }

    private fun openDate(date: String) {
        startActivity(Intent(this, QuickAddActivity::class.java).putExtra(QuickAddActivity.EXTRA_DATE, date))
    }

    // ---------------- CSV 불러오기 ----------------

    private fun handlePickedFile(uri: android.net.Uri) {
        val text = try {
            contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        } catch (e: Exception) {
            null
        }
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "파일을 읽지 못했어요", Toast.LENGTH_SHORT).show()
            return
        }
        val result = CsvImport.parse(text)
        if (result.records.isEmpty()) {
            val detail = result.errors.take(3).joinToString("\n")
            AlertDialog.Builder(this)
                .setTitle("불러올 내용이 없어요")
                .setMessage("형식을 확인해 주세요.\n\n줄 형식: date,platform,count,amount,promo,extra\n예: 2026-10-01,배민,12,85000,3000,0" +
                    if (detail.isNotEmpty()) "\n\n$detail" else "")
                .setPositiveButton("확인", null)
                .show()
            return
        }

        val dates = result.records.map { it.date }.sorted()
        val totalAmount = result.records.sumOf { it.totalAmount }
        val warnExisting = dates.count { monthData.containsKey(it) }
        val msg = buildString {
            append("${result.records.size}일치 기록을 불러와요.\n")
            append("기간: ${dates.first()} ~ ${dates.last()}\n")
            append("합계: ${Fmt.won(totalAmount)}\n")
            if (result.errors.isNotEmpty()) append("\n읽지 못한 줄 ${result.errors.size}개는 건너뛰어요.\n")
            append("\n같은 날짜가 이미 있으면 새 값으로 덮어써요.")
        }
        AlertDialog.Builder(this)
            .setTitle("CSV 불러오기")
            .setMessage(msg)
            .setPositiveButton("불러오기") { _, _ -> doImport(result.records) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun doImport(records: List<DayRecord>) {
        val progress = Toast.makeText(this, "저장 중...", Toast.LENGTH_SHORT).also { it.show() }
        repo.saveAll(records) { saved, err ->
            progress.cancel()
            if (err != null) {
                Toast.makeText(this, "일부만 저장됨(${saved}일): $err", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "${saved}일치 기록을 불러왔어요", Toast.LENGTH_LONG).show()
            }
            reload()
        }
    }

    // ---------------- 기타 ----------------

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("로그아웃")
            .setMessage("로그아웃해도 기록은 클라우드에 남아 있어요. 다시 로그인하면 볼 수 있어요.")
            .setPositiveButton("로그아웃") { _, _ ->
                repo.signOut()
                startActivity(Intent(this, AuthActivity::class.java))
                finish()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
}
