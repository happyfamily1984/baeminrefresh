package com.baeminrefresh.app.income

/** 수입을 기록하는 세 가지 플랫폼 */
enum class Platform(val key: String, val label: String, val colorHex: String) {
    BAEMIN("baemin", "배달의민족", "#2AC1BC"),
    COUPANG("coupang", "쿠팡이츠", "#1A6DFF"),
    AGENCY("agency", "일반대행", "#FF7A00"),
}

/** 하루치 기록. 날짜(yyyy-MM-dd)별로 Firestore 문서 하나. */
data class DayRecord(
    val date: String,
    val counts: MutableMap<String, Long> = mutableMapOf(),
    val amounts: MutableMap<String, Long> = mutableMapOf(),
) {
    fun count(p: Platform): Long = counts[p.key] ?: 0L
    fun amount(p: Platform): Long = amounts[p.key] ?: 0L

    val totalCount: Long get() = Platform.values().sumOf { count(it) }
    val totalAmount: Long get() = Platform.values().sumOf { amount(it) }
    val isEmpty: Boolean get() = totalCount == 0L && totalAmount == 0L

    fun toMap(): Map<String, Any> {
        val m = hashMapOf<String, Any>("date" to date, "ym" to date.substring(0, 7))
        for (p in Platform.values()) {
            m["${p.key}_count"] = count(p)
            m["${p.key}_amount"] = amount(p)
        }
        return m
    }

    companion object {
        fun fromMap(date: String, data: Map<String, Any?>?): DayRecord {
            val r = DayRecord(date)
            if (data == null) return r
            for (p in Platform.values()) {
                (data["${p.key}_count"] as? Number)?.let { r.counts[p.key] = it.toLong() }
                (data["${p.key}_amount"] as? Number)?.let { r.amounts[p.key] = it.toLong() }
            }
            return r
        }
    }
}
