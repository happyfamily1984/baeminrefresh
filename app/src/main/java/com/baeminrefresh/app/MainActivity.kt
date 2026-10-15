package com.baeminrefresh.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    private lateinit var stepApp: StepCard
    private lateinit var stepOverlay: StepCard
    private lateinit var stepAccessibility: StepCard
    private var stepNotification: StepCard? = null
    private lateinit var stepBattery: StepCard

    private lateinit var progressText: TextView
    private lateinit var floatingSwitch: MaterialSwitch
    private lateinit var floatingStatus: TextView
    private var updatingSwitch = false
    private var forceShowSteps = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) openNotificationSettings()
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        setupEdgeToEdge()

        progressText = findViewById(R.id.progress_text)
        floatingSwitch = findViewById(R.id.floating_switch)
        floatingStatus = findViewById(R.id.floating_status)

        buildSteps()
        setupFloatingControls()
        findViewById<TextView>(R.id.help_text).text = HELP_TEXT

        findViewById<MaterialButton>(R.id.add_sticker_button).setOnClickListener { showStickerPicker() }

        if (!prefs.welcomeShown) showWelcome()
    }

    override fun onResume() {
        super.onResume()
        if (prefs.targetPackage == null) {
            TargetApps.detectBaeminConnect(this)?.let { prefs.targetPackage = it.packageName }
        }
        refresh()
        renderStickers()
    }

    // ---------------- 앱 스티커 ----------------

    private fun renderStickers() {
        val container = findViewById<LinearLayout>(R.id.sticker_container)
        container.removeAllViews()
        val stickers = prefs.getStickers()
        if (stickers.isEmpty()) return
        val inflater = LayoutInflater.from(this)
        for (s in stickers) {
            val label = TargetApps.labelOf(this, s.packageName)
            val row = inflater.inflate(R.layout.item_sticker, container, false)
            val iconView = row.findViewById<android.widget.ImageView>(R.id.sticker_icon)
            try {
                iconView.setImageDrawable(packageManager.getApplicationIcon(s.packageName))
            } catch (e: Exception) {
                iconView.setImageResource(R.drawable.ic_refresh)
            }
            row.findViewById<TextView>(R.id.sticker_label).text = label ?: s.packageName
            row.findViewById<View>(R.id.sticker_remove).setOnClickListener {
                prefs.removeSticker(s.packageName)
                renderStickers()
            }
            container.addView(row)
        }
    }

    private fun showStickerPicker() {
        val existing = prefs.getStickers().map { it.packageName }.toSet()
        val apps = TargetApps.launchableApps(this).filter { it.packageName !in existing }
        if (apps.isEmpty()) {
            Toast.makeText(this, "추가할 앱이 없어요", Toast.LENGTH_SHORT).show()
            return
        }
        val items = apps.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("스티커로 만들 앱을 고르세요")
            .setItems(items) { _, which ->
                prefs.addSticker(apps[which].packageName)
                renderStickers()
                if (!FloatingService.isRunning || !Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "위에서 '플로팅 버튼 표시'를 켜면 스티커가 화면에 나타나요", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        val header = findViewById<View>(R.id.header)
        val scroll = findViewById<View>(R.id.scroll)
        val headerTop = header.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, 0, bars.right, 0)
            header.setPadding(header.paddingLeft, headerTop + bars.top, header.paddingRight, header.paddingBottom)
            scroll.setPadding(0, 0, 0, bars.bottom)
            insets
        }
    }

    // ---------------- 설정 단계 ----------------

    private fun buildSteps() {
        val container = findViewById<LinearLayout>(R.id.steps_container)
        var n = 1

        stepApp = StepCard.inflate(container, n++, "배민커넥트 앱 선택")
        stepApp.setAction("앱 선택") { showAppPicker() }

        stepOverlay = StepCard.inflate(container, n++, "다른 앱 위에 표시 허용")
        stepOverlay.desc.text = "플로팅 버튼을 배민커넥트 화면 위에 띄우기 위해 필요해요."
        stepOverlay.setGuide(
            "① 아래 [권한 설정 열기]를 누르세요\n" +
                "② '배민리프레시'의 스위치(다른 앱 위에 표시 허용)를 켜세요\n" +
                "③ 뒤로 가기를 눌러 이 화면으로 돌아오세요"
        )
        stepOverlay.setAction("권한 설정 열기") {
            safeStart(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }

        stepAccessibility = StepCard.inflate(container, n++, "접근성 서비스 켜기")
        stepAccessibility.desc.text =
            "버튼을 누르면 배민커넥트의 '앱 정보 → 강제 중지'를 대신 눌러 앱을 종료하고 다시 실행해요. " +
                "이 기능에만 사용하며 개인정보를 읽거나 저장하지 않아요."
        stepAccessibility.setGuide(
            "① 아래 [접근성 설정 열기]를 누르세요\n" +
                "② '설치된 앱' (또는 '다운로드한 앱', '설치된 서비스') 메뉴로 들어가세요\n" +
                "③ '배민리프레시 (앱 재시작)'를 누르세요\n" +
                "④ 스위치를 켜고 '허용'을 누르세요\n" +
                "⑤ 뒤로 가기를 눌러 이 화면으로 돌아오세요\n\n" +
                "⚠ 스위치가 회색으로 눌리지 않거나 '제한된 설정' 안내가 뜨면\n" +
                "    → [켜지지 않을 때]를 눌러 따라 하세요"
        )
        stepAccessibility.setAction("접근성 설정 열기") {
            safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        stepAccessibility.setSecondary("켜지지 않을 때") { showRestrictedSettingsHelp() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stepNotification = StepCard.inflate(container, n++, "알림 허용 (권장)").apply {
                desc.text = "플로팅 버튼이 꺼지지 않고 계속 떠 있도록 상단 알림을 표시해요. " +
                    "알림에서 버튼을 바로 끌 수도 있어요."
                setAction("알림 허용") {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

        stepBattery = StepCard.inflate(container, n, "배터리 최적화 제외 (권장)")
        stepBattery.desc.text =
            "휴대폰이 절전을 위해 배민리프레시를 꺼버리면 버튼이 사라질 수 있어요."
        stepBattery.setGuide(
            "① 아래 [최적화 제외하기]를 누르고 '허용'을 선택하세요\n" +
                "② 삼성 갤럭시는 추가로:\n" +
                "    설정 → 애플리케이션 → 배민리프레시 → 배터리 → '제한 없음' 선택"
        )
        stepBattery.setAction("최적화 제외하기") { requestIgnoreBatteryOptimization() }
        stepBattery.setSecondary("앱 정보") { openOwnAppDetails() }
    }

    private fun refresh() {
        // 1. 앱 선택
        val pkg = prefs.targetPackage
        val label = pkg?.let { TargetApps.labelOf(this, it) }
        val appOk = label != null
        stepApp.setDone(appOk)
        stepApp.desc.text = when {
            appOk -> "선택된 앱: $label\n($pkg)"
            pkg != null -> "선택했던 앱이 삭제된 것 같아요. 다시 선택해 주세요."
            else -> "설치된 앱에서 배민커넥트를 자동으로 찾지 못했어요.\n" +
                "[앱 선택]을 눌러 목록에서 '배민커넥트'를 골라 주세요."
        }
        stepApp.action.text = if (appOk) "다른 앱 선택" else "앱 선택"

        // 2. 다른 앱 위에 표시
        val overlayOk = Settings.canDrawOverlays(this)
        stepOverlay.setDone(overlayOk)

        // 3. 접근성
        val accessibilityOk = RestartAccessibilityService.isEnabled(this)
        stepAccessibility.setDone(accessibilityOk)

        // 4. 알림
        stepNotification?.setDone(
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
            optional = true
        )

        // 5. 배터리
        val pm = getSystemService(PowerManager::class.java)
        stepBattery.setDone(pm.isIgnoringBatteryOptimizations(packageName), optional = true)

        val done = listOf(appOk, overlayOk, accessibilityOk).count { it }
        progressText.text = if (done == 3) "✓ 설정 완료"
        else "필수 설정 $done / 3 완료"

        applySetupVisibility(done == 3)
        updateFloatingSwitch()
    }

    /** 필수 설정이 끝나면 설정 단계를 접고, 다시 보기 버튼만 남긴다. */
    private fun applySetupVisibility(allDone: Boolean) {
        val header = findViewById<TextView>(R.id.setup_header)
        val steps = findViewById<LinearLayout>(R.id.steps_container)
        val showButton = findViewById<MaterialButton>(R.id.show_setup_button)

        val showSteps = !allDone || forceShowSteps
        header.visibility = if (showSteps) View.VISIBLE else View.GONE
        steps.visibility = if (showSteps) View.VISIBLE else View.GONE

        if (allDone) {
            showButton.visibility = View.VISIBLE
            showButton.text = if (forceShowSteps) "설정 접기" else "권한·설정 다시 보기"
            showButton.setOnClickListener {
                forceShowSteps = !forceShowSteps
                applySetupVisibility(true)
            }
        } else {
            showButton.visibility = View.GONE
        }
    }

    // ---------------- 플로팅 버튼 설정 ----------------

    private fun setupFloatingControls() {
        floatingSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingSwitch) return@setOnCheckedChangeListener
            if (checked) {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "먼저 2번 '다른 앱 위에 표시'를 허용해 주세요", Toast.LENGTH_LONG).show()
                    updateFloatingSwitch()
                    return@setOnCheckedChangeListener
                }
                prefs.floatingEnabled = true
                FloatingService.start(this)
            } else {
                prefs.floatingEnabled = false
                FloatingService.stop(this)
            }
            updateFloatingSwitch()
        }

        val sizeLabel = findViewById<TextView>(R.id.size_label)
        val sizeSeek = findViewById<SeekBar>(R.id.size_seek)
        sizeSeek.progress = prefs.buttonSizeDp - Prefs.MIN_SIZE_DP
        sizeLabel.text = "버튼 크기: ${prefs.buttonSizeDp}"
        sizeSeek.setOnSeekBarChangeListener(SeekListener { p ->
            val v = Prefs.MIN_SIZE_DP + p
            prefs.buttonSizeDp = v
            sizeLabel.text = "버튼 크기: $v"
        })

        val alphaLabel = findViewById<TextView>(R.id.alpha_label)
        val alphaSeek = findViewById<SeekBar>(R.id.alpha_seek)
        alphaSeek.progress = prefs.buttonAlpha - Prefs.MIN_ALPHA
        alphaLabel.text = "버튼 불투명도: ${prefs.buttonAlpha}%"
        alphaSeek.setOnSeekBarChangeListener(SeekListener { p ->
            val v = Prefs.MIN_ALPHA + p
            prefs.buttonAlpha = v
            alphaLabel.text = "버튼 불투명도: $v%"
        })

        findViewById<MaterialButton>(R.id.reset_position_button).setOnClickListener {
            prefs.resetPosition()
            Toast.makeText(this, "버튼을 기본 위치(오른쪽)로 옮겼어요", Toast.LENGTH_SHORT).show()
        }

        findViewById<MaterialButton>(R.id.test_button).setOnClickListener { testRestart() }
    }

    private fun updateFloatingSwitch() {
        val on = prefs.floatingEnabled && Settings.canDrawOverlays(this)
        updatingSwitch = true
        floatingSwitch.isChecked = on
        updatingSwitch = false
        floatingStatus.text = if (on) "화면에 버튼이 떠 있어요. 끌어서 위치를 옮길 수 있어요"
        else "꺼져 있어요"
    }

    private fun testRestart() {
        val pkg = prefs.targetPackage
        if (pkg == null || TargetApps.labelOf(this, pkg) == null) {
            Toast.makeText(this, "먼저 1번에서 배민커넥트 앱을 선택해 주세요", Toast.LENGTH_LONG).show()
            return
        }
        val service = RestartAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "먼저 3번 접근성 서비스를 켜 주세요", Toast.LENGTH_LONG).show()
            return
        }
        val started = service.restart(pkg) { result ->
            val msg = when (result) {
                RestartAccessibilityService.Result.RESTARTED -> "배민커넥트를 재시작했어요"
                RestartAccessibilityService.Result.NOT_RUNNING_LAUNCHED -> "배민커넥트를 실행했어요"
                RestartAccessibilityService.Result.TIMEOUT_LAUNCHED -> "강제 중지 버튼을 찾지 못해 실행만 했어요"
                RestartAccessibilityService.Result.FAILED -> "배민커넥트를 실행하지 못했어요"
            }
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
        if (!started) {
            Toast.makeText(this, "재시작이 이미 진행 중이에요", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- 대화상자 ----------------

    private fun showWelcome() {
        AlertDialog.Builder(this)
            .setTitle("배민리프레시에 오신 걸 환영해요 👋")
            .setMessage(
                "배민커넥트가 멈추거나 콜이 안 뜰 때, 화면의 동그란 버튼만 누르면 " +
                    "배민커넥트를 완전히 껐다가 다시 켜 줘요.\n\n" +
                    "처음 한 번만 화면의 1~3번 필수 설정을 순서대로 해 주세요. " +
                    "각 단계에 따라 하는 방법이 적혀 있어요.\n\n" +
                    "버튼을 누르면 잠깐 '앱 정보' 화면이 보였다가 사라지는데, 정상 동작이에요."
            )
            .setPositiveButton("시작하기") { _, _ -> prefs.welcomeShown = true }
            .setCancelable(false)
            .show()
    }

    private fun showRestrictedSettingsHelp() {
        AlertDialog.Builder(this)
            .setTitle("접근성이 켜지지 않을 때")
            .setMessage(
                "Play 스토어가 아닌 곳에서 설치한 앱은 안드로이드 보안 정책 때문에 " +
                    "'제한된 설정'으로 막혀 있을 수 있어요. 아래처럼 풀어 주세요.\n\n" +
                    "① 먼저 접근성 설정에서 '배민리프레시'를 한 번 켜 보세요 " +
                    "('제한된 설정' 안내가 뜨면 확인을 누르세요)\n" +
                    "② 아래 [앱 정보 열기]를 누르세요\n" +
                    "③ 오른쪽 위 ⋮ (점 세 개) 메뉴를 누르세요\n" +
                    "④ '제한된 설정 허용'을 누르고 잠금 해제(지문/PIN)를 하세요\n" +
                    "⑤ 다시 [접근성 설정 열기]로 가서 스위치를 켜세요\n\n" +
                    "※ 접근성이 켜져 있는데도 동작하지 않으면 스위치를 껐다 다시 켜 보세요."
            )
            .setPositiveButton("앱 정보 열기") { _, _ -> openOwnAppDetails() }
            .setNeutralButton("접근성 설정 열기") { _, _ ->
                safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun showAppPicker() {
        val apps = TargetApps.launchableApps(this)
        if (apps.isEmpty()) {
            Toast.makeText(this, "설치된 앱 목록을 불러오지 못했어요", Toast.LENGTH_SHORT).show()
            return
        }
        val items = apps.map {
            val star = if (TargetApps.isBaeminConnectLike(it)) "★ " else ""
            "$star${it.label}\n${it.packageName}"
        }.toTypedArray()
        val current = apps.indexOfFirst { it.packageName == prefs.targetPackage }
        AlertDialog.Builder(this)
            .setTitle("재시작할 앱을 고르세요 (배민커넥트)")
            .setSingleChoiceItems(items, current) { dialog, which ->
                prefs.targetPackage = apps[which].packageName
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ---------------- 설정 화면 열기 ----------------

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimization() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "이미 최적화에서 제외되어 있어요", Toast.LENGTH_SHORT).show()
            return
        }
        if (!safeStart(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                showError = false
            )
        ) {
            safeStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun openNotificationSettings() {
        safeStart(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        )
    }

    private fun openOwnAppDetails() {
        safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun safeStart(intent: Intent, showError: Boolean = true): Boolean = try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        if (showError) Toast.makeText(this, "설정 화면을 열 수 없어요. 직접 설정 앱에서 찾아 주세요", Toast.LENGTH_LONG).show()
        false
    }

    // ---------------- 보조 클래스 ----------------

    private class SeekListener(val onChange: (Int) -> Unit) : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    private class StepCard(val root: View) {
        val number: TextView = root.findViewById(R.id.step_number)
        val title: TextView = root.findViewById(R.id.step_title)
        val status: TextView = root.findViewById(R.id.step_status)
        val desc: TextView = root.findViewById(R.id.step_desc)
        val guide: TextView = root.findViewById(R.id.step_guide)
        val action: MaterialButton = root.findViewById(R.id.step_action)
        val secondary: MaterialButton = root.findViewById(R.id.step_secondary)

        fun setAction(text: String, onClick: () -> Unit) {
            action.text = text
            action.setOnClickListener { onClick() }
        }

        fun setSecondary(text: String, onClick: () -> Unit) {
            secondary.text = text
            secondary.visibility = View.VISIBLE
            secondary.setOnClickListener { onClick() }
        }

        fun setGuide(text: String) {
            guide.text = text
            guide.visibility = View.VISIBLE
        }

        fun setDone(done: Boolean, optional: Boolean = false) {
            val ctx = root.context
            status.text = when {
                done -> "완료"
                optional -> "권장"
                else -> "필요"
            }
            val color = when {
                done -> R.color.ok
                else -> R.color.warn
            }
            status.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, color))
            number.text = if (done) "✓" else number.tag as String
            number.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(ctx, if (done) R.color.ok else R.color.mint)
            )
            // 완료된 단계는 안내를 접어서 화면을 간단하게
            if (guide.text.isNotEmpty()) guide.visibility = if (done) View.GONE else View.VISIBLE
        }

        companion object {
            fun inflate(parent: LinearLayout, n: Int, title: String): StepCard {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_step, parent, false)
                parent.addView(v)
                return StepCard(v).apply {
                    number.tag = n.toString()
                    number.text = n.toString()
                    this.title.text = title
                }
            }
        }
    }

    companion object {
        private val HELP_TEXT = """
            • 버튼을 눌렀는데 '앱 정보' 화면에서 멈춰요
              → 접근성 스위치를 껐다가 다시 켜 주세요. 휴대폰을 재부팅한 뒤에도 가끔 필요해요.

            • 플로팅 버튼이 사라졌어요
              → 이 앱을 열고 '플로팅 버튼 표시'를 다시 켜 주세요. 자주 사라지면 5번 배터리 설정을 해 주세요.

            • 버튼이 너무 크거나 화면을 가려요
              → 위에서 버튼 크기와 불투명도를 조절하거나, 버튼을 끌어서 구석으로 옮겨 주세요.

            • 다른 앱이 재시작돼요
              → 1번에서 '배민커넥트'를 정확히 골랐는지 확인해 주세요.

            • 재시작 후 배민커넥트에서 다시 '운행 시작'이 필요할 수 있어요. 앱을 완전히 껐다 켜는 것과 같아요.
        """.trimIndent()
    }
}
