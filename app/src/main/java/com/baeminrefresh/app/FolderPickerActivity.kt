package com.baeminrefresh.app

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * 폴더를 두 번 눌렀을 때 뜨는 창: 설치된 앱 중에서 골라 앱 스티커로 만들어 그 폴더에 바로 넣는다.
 * 지금 쓰던 화면 위에 투명하게 떠서, 고르고 나면 원래 화면으로 돌아간다.
 */
class FolderPickerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        val folderId = intent.getIntExtra(EXTRA_FOLDER_ID, -1)
        val folders = prefs.getFolders()
        if (folders.none { it.id == folderId }) {
            finish()
            return
        }
        // 이미 어떤 폴더에 들어 있는 앱은 제외
        val inFolder = folders.flatMap { it.members }.toSet()
        val apps = TargetApps.launchableApps(this).filter { "sticker:${it.packageName}" !in inFolder }
        if (apps.isEmpty()) {
            Toast.makeText(this, "추가할 앱이 없어요", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val iconSize = (36 * resources.displayMetrics.density).toInt()
        val pad = (12 * resources.displayMetrics.density).toInt()
        val adapter = object : ArrayAdapter<AppEntry>(
            this, android.R.layout.select_dialog_multichoice, android.R.id.text1, apps
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val tv = v.findViewById<TextView>(android.R.id.text1)
                tv.text = apps[position].label
                val icon = try {
                    packageManager.getApplicationIcon(apps[position].packageName)
                } catch (e: Exception) {
                    null
                }
                icon?.setBounds(0, 0, iconSize, iconSize)
                tv.setCompoundDrawables(icon, null, null, null)
                tv.compoundDrawablePadding = pad
                return v
            }
        }
        val list = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_MULTIPLE
            this.adapter = adapter
        }
        AlertDialog.Builder(this)
            .setTitle("폴더에 넣을 앱을 고르세요")
            .setView(list)
            .setPositiveButton("추가") { _, _ ->
                val checked = list.checkedItemPositions
                val picked = apps.filterIndexed { i, _ -> checked.get(i) }
                if (picked.isNotEmpty()) {
                    // 스티커를 먼저 만들고 폴더에 넣는다 (폴더에 넣을 때 스티커가 이미 있어야 정리되지 않음)
                    for (a in picked) prefs.addSticker(a.packageName)
                    prefs.addToFolder(folderId, picked.map { "sticker:${it.packageName}" })
                    Toast.makeText(this, "${picked.size}개를 폴더에 넣었어요", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .setOnDismissListener { finish() }
            .show()
    }

    companion object {
        const val EXTRA_FOLDER_ID = "folder_id"
    }
}
