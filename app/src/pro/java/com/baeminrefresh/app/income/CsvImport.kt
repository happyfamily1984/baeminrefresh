package com.baeminrefresh.app.income

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * CSV/텍스트를 DayRecord 목록으로 바꾼다.
 *
 * 한 줄에 한 플랫폼씩, 열 순서는:
 *   date,platform,count,amount,promo,extra
 * - date: 2026-10-01 / 2026.10.01 / 2026/10/01 / 10-01(올해)
 * - platform: baemin|coupang|agency 또는 배민|쿠팡|대행(배달의민족/쿠팡이츠/일반대행)
 * - 숫자 칸의 쉼표·원·공백은 자동으로 제거
 * 같은 날짜의 여러 줄은 하나의 하루 기록으로 합쳐진다.
 * 맨 윗줄에 'date'가 들어 있으면 머리글로 보고 건너뛴다.
 */
object CsvImport {

    data class Result(val records: List<DayRecord>, val rowsRead: Int, val errors: List<String>)

    fun parse(text: String): Result {
        val byDate = linkedMapOf<String, DayRecord>()
        val errors = mutableListOf<String>()
        var rows = 0

        val lines = text.split(Regex("\r\n|\r|\n"))
        for ((index, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            // 머리글 줄 건너뛰기
            if (index == 0 && line.lowercase().contains("date")) continue
            if (line.startsWith("#")) continue

            val cols = line.split(",").map { it.trim() }
            if (cols.size < 3) {
                errors.add("${index + 1}번째 줄: 칸이 부족해요 → \"$line\""); continue
            }
            val date = normalizeDate(cols[0])
            if (date == null) {
                errors.add("${index + 1}번째 줄: 날짜를 못 읽었어요 → \"${cols[0]}\""); continue
            }
            val platform = matchPlatform(cols[1])
            if (platform == null) {
                errors.add("${index + 1}번째 줄: 플랫폼을 못 읽었어요 → \"${cols[1]}\""); continue
            }
            val record = byDate.getOrPut(date) { DayRecord(date) }
            record.counts[platform.key] = num(cols.getOrNull(2))
            record.amounts[platform.key] = num(cols.getOrNull(3))
            record.promos[platform.key] = num(cols.getOrNull(4))
            record.extras[platform.key] = num(cols.getOrNull(5))
            rows++
        }
        return Result(byDate.values.toList(), rows, errors)
    }

    private fun num(s: String?): Long =
        s?.replace(Regex("[^0-9-]"), "")?.toLongOrNull() ?: 0L

    private fun matchPlatform(s: String): Platform? {
        val t = s.replace(" ", "").lowercase()
        return when {
            t.contains("baemin") || t.contains("배민") || t.contains("배달의민족") -> Platform.BAEMIN
            t.contains("coupang") || t.contains("쿠팡") -> Platform.COUPANG
            t.contains("agency") || t.contains("대행") -> Platform.AGENCY
            else -> null
        }
    }

    private val outFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private fun normalizeDate(s: String): String? {
        val t = s.trim()
        // yyyy[-./]MM[-./]dd
        Regex("(\\d{4})[-./](\\d{1,2})[-./](\\d{1,2})").find(t)?.let {
            return build(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())
        }
        // MM[-./]dd → 올해
        Regex("^(\\d{1,2})[-./](\\d{1,2})$").find(t)?.let {
            return build(LocalDate.now().year, it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
        // M월 d일
        Regex("(\\d{1,2})월\\s*(\\d{1,2})일").find(t)?.let {
            return build(LocalDate.now().year, it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
        return null
    }

    private fun build(y: Int, m: Int, d: Int): String? = try {
        LocalDate.of(y, m, d).format(outFmt)
    } catch (e: Exception) {
        null
    }
}
