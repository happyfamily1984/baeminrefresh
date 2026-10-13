package com.baeminrefresh.app.income

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/**
 * google-services 플러그인 없이 코드로 Firebase를 초기화한다.
 * 여기 값들은 앱에 넣으라고 제공되는 공개 설정값(google-services.json)이라 비밀이 아니다.
 */
object FirebaseInit {
    @Volatile
    private var app: FirebaseApp? = null

    fun app(context: Context): FirebaseApp {
        app?.let { return it }
        return synchronized(this) {
            app ?: run {
                val ctx = context.applicationContext
                val existing = FirebaseApp.getApps(ctx).firstOrNull()
                val created = existing ?: FirebaseApp.initializeApp(
                    ctx,
                    FirebaseOptions.Builder()
                        .setApplicationId("1:586690307950:android:9a815904fb651bba02c37f")
                        .setApiKey("AIzaSyAWgfOl6NFHABaIqBP5_-BGIr5V_g8XIKo")
                        .setProjectId("baeminrefresh-ace2c")
                        .setGcmSenderId("586690307950")
                        .setStorageBucket("baeminrefresh-ace2c.firebasestorage.app")
                        .build()
                )
                app = created
                created
            }
        }
    }
}
