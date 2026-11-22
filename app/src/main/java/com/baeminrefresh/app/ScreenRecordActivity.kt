package com.baeminrefresh.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * 화면 녹화 버튼을 누르면 뜨는 투명 창: 안드로이드 '화면 녹화 허용' 창을 띄우고,
 * 허용하면 FloatingService 에 녹화를 시작하라고 넘긴 뒤 바로 닫힌다. (허용 창은 보안 규칙상 매번 뜬다)
 */
class ScreenRecordActivity : AppCompatActivity() {

    private val ask = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) {
            val i = Intent(this, FloatingService::class.java)
                .setAction(FloatingService.ACTION_SCREENREC_START)
                .putExtra(FloatingService.EXTRA_REC_CODE, r.resultCode)
                .putExtra(FloatingService.EXTRA_REC_DATA, data)
            try {
                startService(i)
            } catch (e: Exception) {
                Toast.makeText(this, "녹화를 시작하지 못했어요", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "화면 녹화를 취소했어요", Toast.LENGTH_SHORT).show()
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // 허용 창이 떠 있는 동안 다시 만들어진 경우
        val mpm = getSystemService(MediaProjectionManager::class.java)
        try {
            ask.launch(mpm.createScreenCaptureIntent())
        } catch (e: Exception) {
            Toast.makeText(this, "이 폰에서는 화면 녹화를 쓸 수 없어요", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
