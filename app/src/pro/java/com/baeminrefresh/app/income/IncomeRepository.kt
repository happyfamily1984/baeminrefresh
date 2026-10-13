package com.baeminrefresh.app.income

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

/**
 * Firebase 인증 + Firestore 저장을 담당. 로그인한 사용자별로 users/{uid}/days/{yyyy-MM-dd} 에 하루치 기록을 둔다.
 * Firestore는 기본으로 오프라인 캐시가 켜져 있어, 네트워크가 끊겨도 기록하고 나중에 자동 동기화된다.
 */
class IncomeRepository private constructor(context: Context) {

    private val auth: FirebaseAuth
    private val db: FirebaseFirestore

    init {
        val app = FirebaseInit.app(context)
        auth = FirebaseAuth.getInstance(app)
        db = FirebaseFirestore.getInstance(app)
    }

    val isSignedIn: Boolean get() = auth.currentUser != null
    val userEmail: String? get() = auth.currentUser?.email

    fun signIn(email: String, password: String, onResult: (Boolean, String?) -> Unit) {
        auth.signInWithEmailAndPassword(email.trim(), password)
            .addOnSuccessListener { onResult(true, null) }
            .addOnFailureListener { onResult(false, it.localizedMessage) }
    }

    fun signUp(email: String, password: String, onResult: (Boolean, String?) -> Unit) {
        auth.createUserWithEmailAndPassword(email.trim(), password)
            .addOnSuccessListener { onResult(true, null) }
            .addOnFailureListener { onResult(false, it.localizedMessage) }
    }

    fun sendPasswordReset(email: String, onResult: (Boolean, String?) -> Unit) {
        auth.sendPasswordResetEmail(email.trim())
            .addOnSuccessListener { onResult(true, null) }
            .addOnFailureListener { onResult(false, it.localizedMessage) }
    }

    fun signOut() = auth.signOut()

    private fun days() =
        db.collection("users").document(auth.currentUser!!.uid).collection("days")

    fun save(record: DayRecord, onResult: (Boolean, String?) -> Unit) {
        if (!isSignedIn) {
            onResult(false, "로그인이 필요해요")
            return
        }
        days().document(record.date).set(record.toMap())
            .addOnSuccessListener { onResult(true, null) }
            .addOnFailureListener { onResult(false, it.localizedMessage) }
    }

    fun delete(date: String, onResult: (Boolean, String?) -> Unit) {
        if (!isSignedIn) {
            onResult(false, "로그인이 필요해요")
            return
        }
        days().document(date).delete()
            .addOnSuccessListener { onResult(true, null) }
            .addOnFailureListener { onResult(false, it.localizedMessage) }
    }

    fun loadDay(date: String, onResult: (DayRecord) -> Unit) {
        if (!isSignedIn) {
            onResult(DayRecord(date)); return
        }
        days().document(date).get()
            .addOnSuccessListener { onResult(DayRecord.fromMap(date, it.data)) }
            .addOnFailureListener { onResult(DayRecord(date)) }
    }

    /** 한 달치 기록을 날짜→기록 맵으로 돌려준다. */
    fun loadMonth(ym: String, onResult: (Map<String, DayRecord>, String?) -> Unit) {
        if (!isSignedIn) {
            onResult(emptyMap(), "로그인이 필요해요"); return
        }
        days().whereEqualTo("ym", ym).get()
            .addOnSuccessListener { snap ->
                val map = linkedMapOf<String, DayRecord>()
                for (doc in snap.documents) {
                    map[doc.id] = DayRecord.fromMap(doc.id, doc.data)
                }
                onResult(map, null)
            }
            .addOnFailureListener { onResult(emptyMap(), it.localizedMessage) }
    }

    /** startYm(포함)부터 현재까지의 월별 기록을 ym 오름차순으로. 통계용. */
    fun loadFrom(startYm: String, onResult: (List<DayRecord>, String?) -> Unit) {
        if (!isSignedIn) {
            onResult(emptyList(), "로그인이 필요해요"); return
        }
        days().whereGreaterThanOrEqualTo("ym", startYm)
            .orderBy("ym", Query.Direction.ASCENDING).get()
            .addOnSuccessListener { snap ->
                onResult(snap.documents.map { DayRecord.fromMap(it.id, it.data) }, null)
            }
            .addOnFailureListener { onResult(emptyList(), it.localizedMessage) }
    }

    /** 여러 날짜를 한 번에 저장 (CSV 불러오기용). Firestore 배치 한도(500)를 고려해 나눠서 커밋한다. */
    fun saveAll(records: List<DayRecord>, onResult: (saved: Int, error: String?) -> Unit) {
        if (!isSignedIn) {
            onResult(0, "로그인이 필요해요"); return
        }
        if (records.isEmpty()) {
            onResult(0, null); return
        }
        val chunks = records.chunked(400)
        var saved = 0
        fun commit(i: Int) {
            if (i >= chunks.size) {
                onResult(saved, null); return
            }
            val batch = db.batch()
            for (r in chunks[i]) batch.set(days().document(r.date), r.toMap())
            batch.commit()
                .addOnSuccessListener {
                    saved += chunks[i].size
                    commit(i + 1)
                }
                .addOnFailureListener { onResult(saved, it.localizedMessage) }
        }
        commit(0)
    }

    companion object {
        @Volatile
        private var instance: IncomeRepository? = null

        fun get(context: Context): IncomeRepository =
            instance ?: synchronized(this) {
                instance ?: IncomeRepository(context.applicationContext).also { instance = it }
            }
    }
}
