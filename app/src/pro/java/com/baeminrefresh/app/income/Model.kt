package com.baeminrefresh.app.income

/** 수입을 기록하는 세 가지 플랫폼 */
enum class Platform(val key: String, val label: String, val colorHex: String) {
    BAEMIN("baemin", "배달의민족", "#2AC1BC"),
    COUPANG("coupang", "쿠팡이츠", "#1A6DFF"),
    AGENCY("agency", "일반대행", "#FF7A00"),
}

/**
 * 하루치 기록. 날짜(yyyy-MM-dd)별로 Firestore 문서 하나.
 * 플랫폼마다 완료건수 / 배달료 / 프로모션 / 과적금액을 따로 기록한다.
 */
data class DayRecord(
    val date: String,
    val counts: MutableMap<String, Long> = mutableMapOf(),
    val amounts: MutableMap<String, Long> = mutableMapOf(),
    val promos: MutableMap<String, Long> = mutableMapOf(),
    val extras: MutableMap<String, Long> = mutableMapOf(),
) {
    fun count(p: Platform): Long = counts[p.key] ?: 0L
    fun amount(p: Platform): Long = amounts[p.key] ?: 0L
    fun promo(p: Platform): Long = promos[p.key] ?: 0L
    fun extra(p: Platform): Long = extras[p.key] ?: 0L

    /** 플랫폼 하나의 총수입 = 배달료 + 프로모션 + 과적 */
    fun platformTotal(p: Platform): Long = amount(p) + promo(p) + extra(p)

    val totalCount: Long get() = Platform.values().sumOf { count(it) }
    val totalBase: Long get() = Platform.values().sumOf { amount(it) }
    val totalPromo: Long get() = Platform.values().sumOf { promo(it) }
    val totalExtra: Long get() = Platform.values().sumOf { extra(it) }

    /** 하루 총수입 = 모든 플랫폼의 배달료 + 프로모션 + 과적 */
    val totalAmount: Long get() = totalBase + totalPromo + totalExtra
    val isEmpty: Boolean get() = totalCount == 0L && totalAmount == 0L

    fun toMap(): Map<String, Any> {
        val m = hashMapOf<String, Any>("date" to date, "ym" to date.substring(0, 7))
        for (p in Platform.values()) {
            m["${p.key}_count"] = count(p)
            m["${p.key}_amount"] = amount(p)
            m["${p.key}_promo"] = promo(p)
            m["${p.key}_extra"] = extra(p)
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
                (data["${p.key}_promo"] as? Number)?.let { r.promos[p.key] = it.toLong() }
                (data["${p.key}_extra"] as? Number)?.let { r.extras[p.key] = it.toLong() }
            }
            return r
        }
    }
}
