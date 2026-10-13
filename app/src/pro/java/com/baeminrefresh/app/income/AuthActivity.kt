package com.baeminrefresh.app.income

import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.baeminrefresh.app.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

/**
 * 이메일/비밀번호로 로그인하거나 가입한다. 성공하면 수입 기록 화면으로 넘어간다.
 */
class AuthActivity : AppCompatActivity() {

    private lateinit var repo: IncomeRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)
        repo = IncomeRepository.get(this)

        if (repo.isSignedIn) {
            goToTracker()
            return
        }

        val email = findViewById<TextInputEditText>(R.id.auth_email)
        val password = findViewById<TextInputEditText>(R.id.auth_password)
        val loginButton = findViewById<MaterialButton>(R.id.auth_login)
        val signupButton = findViewById<MaterialButton>(R.id.auth_signup)
        val resetButton = findViewById<MaterialButton>(R.id.auth_reset)

        fun inputs(): Pair<String, String>? {
            val e = email.text?.toString()?.trim().orEmpty()
            val p = password.text?.toString().orEmpty()
            if (!Patterns.EMAIL_ADDRESS.matcher(e).matches()) {
                toast("이메일 형식을 확인해 주세요"); return null
            }
            if (p.length < 6) {
                toast("비밀번호는 6자 이상이어야 해요"); return null
            }
            return e to p
        }

        fun setBusy(busy: Boolean) {
            loginButton.isEnabled = !busy
            signupButton.isEnabled = !busy
        }

        loginButton.setOnClickListener {
            val (e, p) = inputs() ?: return@setOnClickListener
            setBusy(true)
            repo.signIn(e, p) { ok, err ->
                setBusy(false)
                if (ok) goToTracker() else toast("로그인 실패: ${err ?: "다시 시도해 주세요"}")
            }
        }

        signupButton.setOnClickListener {
            val (e, p) = inputs() ?: return@setOnClickListener
            setBusy(true)
            repo.signUp(e, p) { ok, err ->
                setBusy(false)
                if (ok) {
                    toast("가입 완료! 이 계정으로 기록돼요")
                    goToTracker()
                } else {
                    toast("가입 실패: ${err ?: "다시 시도해 주세요"}")
                }
            }
        }

        resetButton.setOnClickListener {
            val e = email.text?.toString()?.trim().orEmpty()
            if (!Patterns.EMAIL_ADDRESS.matcher(e).matches()) {
                toast("비밀번호를 찾을 이메일을 먼저 입력해 주세요"); return@setOnClickListener
            }
            repo.sendPasswordReset(e) { ok, err ->
                toast(if (ok) "재설정 메일을 보냈어요" else "실패: ${err ?: ""}")
            }
        }
    }

    private fun goToTracker() {
        startActivity(android.content.Intent(this, IncomeActivity::class.java))
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
