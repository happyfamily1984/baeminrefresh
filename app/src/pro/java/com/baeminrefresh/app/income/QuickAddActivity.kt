package com.baeminrefresh.app.income

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.baeminrefresh.app.R
import com.google.android.material.button.MaterialButton
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 플로팅 버튼(길게 누르기)이나 캘린더에서 열리는 빠른 입력 화면.
 * 세 플랫폼의 완료 건수와 수입을 입력해 하루치로 저장한다.
 */
class QuickAddActivity : AppCompatActivity() {

    private lateinit var repo: IncomeRepository
    private lateinit var date: String

    private data class Row(
        val count: EditText,
        val amount: EditText,
        val promo: EditText,
        val extra: EditText,
    )

    private val rows = linkedMapOf<Platform, Row>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = IncomeRepository.get(this)
        if (!repo.isSignedIn) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_quick_add)

        date = intent.getStringExtra(EXTRA_DATE) ?: LocalDate.now().toString()

        findViewById<TextView>(R.id.qa_date).text = prettyDate(date)

        rows[Platform.BAEMIN] = Row(
            findViewById(R.id.qa_baemin_count), findViewById(R.id.qa_baemin_amount),
            findViewById(R.id.qa_baemin_promo), findViewById(R.id.qa_baemin_extra)
        )
        rows[Platform.COUPANG] = Row(
            findViewById(R.id.qa_coupang_count), findViewById(R.id.qa_coupang_amount),
            findViewById(R.id.qa_coupang_promo), findViewById(R.id.qa_coupang_extra)
        )
        rows[Platform.AGENCY] = Row(
            findViewById(R.id.qa_agency_count), findViewById(R.id.qa_agency_amount),
            findViewById(R.id.qa_agency_promo), findViewById(R.id.qa_agency_extra)
        )

        val totalView = findViewById<TextView>(R.id.qa_total)
        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = updateTotal(totalView)
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        rows.values.forEach {
            it.count.addTextChangedListener(watcher)
            it.amount.addTextChangedListener(watcher)
            it.promo.addTextChangedListener(watcher)
            it.extra.addTextChangedListener(watcher)
        }

        // 기존 기록 불러와 채우기
        repo.loadDay(date) { record ->
            for ((p, row) in rows) {
                if (record.count(p) > 0) row.count.setText(record.count(p).toString())
                if (record.amount(p) > 0) row.amount.setText(record.amount(p).toString())
                if (record.promo(p) > 0) row.promo.setText(record.promo(p).toString())
                if (record.extra(p) > 0) row.extra.setText(record.extra(p).toString())
            }
            updateTotal(totalView)
        }

        findViewById<MaterialButton>(R.id.qa_save).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.qa_delete).setOnClickListener { deleteRecord() }
        findViewById<MaterialButton>(R.id.qa_open_tracker).setOnClickListener {
            startActivity(Intent(this, IncomeActivity::class.java))
            finish()
        }
        findViewById<View>(R.id.qa_close).setOnClickListener { finish() }
    }

    private fun parse(e: EditText): Long = e.text?.toString()?.trim()?.toLongOrNull() ?: 0L

    private fun updateTotal(totalView: TextView) {
        var count = 0L
        var amount = 0L
        for (row in rows.values) {
            count += parse(row.count)
            amount += parse(row.amount) + parse(row.promo) + parse(row.extra)
        }
        totalView.text = "합계  ${Fmt.num(count)}건 · ${Fmt.won(amount)}"
    }

    private fun save() {
        val record = DayRecord(date)
        for ((p, row) in rows) {
            record.counts[p.key] = parse(row.count)
            record.amounts[p.key] = parse(row.amount)
            record.promos[p.key] = parse(row.promo)
            record.extras[p.key] = parse(row.extra)
        }
        repo.save(record) { ok, err ->
            if (ok) {
                Toast.makeText(this, "${prettyDate(date)} 기록을 저장했어요", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, "저장 실패: ${err ?: ""}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun deleteRecord() {
        repo.delete(date) { ok, err ->
            if (ok) {
                Toast.makeText(this, "${prettyDate(date)} 기록을 삭제했어요", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, "삭제 실패: ${err ?: ""}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun prettyDate(d: String): String =
        LocalDate.parse(d).format(DateTimeFormatter.ofPattern("M월 d일 (E)"))

    companion object {
        const val EXTRA_DATE = "date"
    }
}
