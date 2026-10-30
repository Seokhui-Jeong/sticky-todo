package com.sticky.todo

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

/**
 * 설정 화면. 바꾸는 즉시 저장되고 위젯에도 바로 반영된다.
 * 항목이 많지 않아 레이아웃 파일 대신 코드로 줄을 만든다.
 */
class SettingsActivity : BaseActivity() {

    private lateinit var box: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        box = findViewById(R.id.settings_list)
    }

    override fun onResume() {
        super.onResume()
        build()   // 동기화 화면에서 돌아왔을 때 계정 표시를 새로 고친다
    }

    private fun build() {
        box.removeAllViews()
        fun arr(id: Int): Array<String> = resources.getStringArray(id)

        section(getString(R.string.sec_language))
        choice(getString(R.string.lang_title), Lang.LABELS, { Lang.mode }, {
            if (it != Lang.mode) {
                Lang.setMode(this, it)
                TodoWidget.refresh(this)   // 위젯 제목·날짜도 새 언어로
                recreate()                 // 이 화면은 바로, 뒤의 화면은 돌아갈 때 다시 그려진다
            }
        })

        section(getString(R.string.sec_widget))
        choice(getString(R.string.header_row), arr(R.array.header_labels),
            { Settings.header(this) }, { Settings.setHeader(this, it) })
        toggle(getString(R.string.hide_done), getString(R.string.hide_done_sub),
            { Settings.hideDoneInWidget(this) }, { Settings.setHideDoneInWidget(this, it) })
        choice(getString(R.string.opacity_title), arr(R.array.opacity_labels),
            { Settings.opacityLevel(this) }, { Settings.setOpacityLevel(this, it) })
        choice(getString(R.string.font_title), arr(R.array.font_labels),
            { Settings.fontLevel(this) }, { Settings.setFontLevel(this, it) })
        choice(getString(R.string.spacing_row), arr(R.array.spacing_labels),
            { TodoRepo.widgetSpacing(this) }, { TodoRepo.setWidgetSpacing(this, it) })
        val tapValues = arrayOf(TodoRepo.TAP_ADD, TodoRepo.TAP_OPEN)
        choice(getString(R.string.widget_tap_title),
            arrayOf(getString(R.string.widget_tap_add), getString(R.string.widget_tap_open)),
            { tapValues.indexOf(TodoRepo.widgetTap(this)).coerceAtLeast(0) },
            { TodoRepo.setWidgetTap(this, tapValues[it]) })

        section(getString(R.string.sec_date))
        choice(getString(R.string.date_mode_title), arr(R.array.date_labels),
            { Settings.dateMode(this) }, { Settings.setDateMode(this, it) })
        toggle(getString(R.string.weekday_title), getString(R.string.weekday_sub),
            { Settings.showWeekday(this) }, { Settings.setShowWeekday(this, it) })

        section(getString(R.string.sec_cleanup))
        choice(getString(R.string.archive_keep_title), arr(R.array.archive_keep_labels),
            { Settings.archiveKeepLevel(this) },
            {
                Settings.setArchiveKeepLevel(this, it)
                TodoRepo.runAutoArchive(this)   // 바로 한 번 정리
                SyncScheduler.soon(this)
            })

        section(getString(R.string.sync_title))
        toggle(getString(R.string.wifi_only), getString(R.string.wifi_only_sub),
            { Settings.wifiOnly(this) },
            {
                Settings.setWifiOnly(this, it)
                SyncScheduler.ensurePeriodic(this, replace = true)
            })
        link(getString(R.string.sync_link),
            if (DriveSync.connected(this)) DriveSync.email(this) ?: getString(R.string.sync_connected)
            else getString(R.string.sync_not_connected)) {
            startActivity(Intent(this, SyncActivity::class.java))
        }
    }

    // ── 줄 만들기 ─────────────────────────────────────────

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private fun section(title: String) {
        if (box.childCount > 0) {
            box.addView(View(this).apply {
                setBackgroundColor(color(R.color.divider))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(8)
            })
        }
        box.addView(TextView(this).apply {
            text = title
            setTextColor(color(R.color.accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(18), dp(18), dp(18), dp(4))
        })
    }

    /** 제목 + 아래 회색 설명 한 줄. 오른쪽에 붙일 뷰가 있으면 함께 */
    private fun row(title: String, sub: String, right: View? = null, onClick: () -> Unit): TextView {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            isClickable = true
            isFocusable = true
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { onClick() }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = title
            setTextColor(color(R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        val subView = TextView(this).apply {
            text = sub
            setTextColor(color(R.color.muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(2), 0, 0)
            visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
        }
        texts.addView(subView)
        line.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (right != null) line.addView(right)
        box.addView(line)
        return subView
    }

    private fun choice(title: String, labels: Array<String>, get: () -> Int, set: (Int) -> Unit) {
        lateinit var sub: TextView
        sub = row(title, labels[get().coerceIn(0, labels.size - 1)]) {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, get().coerceIn(0, labels.size - 1)) { d, which ->
                    set(which)
                    sub.text = labels[which]
                    TodoWidget.refresh(this)
                    d.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun toggle(title: String, sub: String, get: () -> Boolean, set: (Boolean) -> Unit) {
        val sw = SwitchCompat(this).apply {
            isChecked = get()
            isClickable = false   // 줄 전체를 눌러서 바꾼다
            isFocusable = false
        }
        row(title, sub, sw) {
            val v = !get()
            set(v)
            sw.isChecked = v
            TodoWidget.refresh(this)
        }
    }

    private fun link(title: String, sub: String, onClick: () -> Unit) {
        row(title, sub, TextView(this).apply {
            text = "›"
            setTextColor(color(R.color.muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        }, onClick)
    }
}
