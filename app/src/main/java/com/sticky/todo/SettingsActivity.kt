package com.sticky.todo

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

/**
 * 설정 화면. 바꾸는 즉시 저장되고 위젯에도 바로 반영된다.
 * 항목이 많지 않아 레이아웃 파일 대신 코드로 줄을 만든다.
 */
class SettingsActivity : AppCompatActivity() {

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

        section("위젯")
        choice("머리글 (제목·추가 버튼)", Settings.HEADER_LABELS,
            { Settings.header(this) }, { Settings.setHeader(this, it) })
        toggle("완료한 항목 숨기기", "위젯에서만 숨깁니다. 앱에서는 그대로 보입니다",
            { Settings.hideDoneInWidget(this) }, { Settings.setHideDoneInWidget(this, it) })
        choice("배경 불투명도", Settings.OPACITY_LABELS,
            { Settings.opacityLevel(this) }, { Settings.setOpacityLevel(this, it) })
        choice("글자 크기", Settings.FONT_LABELS,
            { Settings.fontLevel(this) }, { Settings.setFontLevel(this, it) })
        choice("줄 간격", SPACING_LABELS,
            { TodoRepo.widgetSpacing(this) }, { TodoRepo.setWidgetSpacing(this, it) })
        val tapValues = arrayOf(TodoRepo.TAP_ADD, TodoRepo.TAP_OPEN)
        choice(getString(R.string.widget_tap_title),
            arrayOf(getString(R.string.widget_tap_add), getString(R.string.widget_tap_open)),
            { tapValues.indexOf(TodoRepo.widgetTap(this)).coerceAtLeast(0) },
            { TodoRepo.setWidgetTap(this, tapValues[it]) })

        section("날짜")
        choice("표시 방식", Settings.DATE_LABELS,
            { Settings.dateMode(this) }, { Settings.setDateMode(this, it) })
        toggle("요일 함께 표시", "예: 10/5(월)",
            { Settings.showWeekday(this) }, { Settings.setShowWeekday(this, it) })

        section("정리")
        choice("보관함 자동 비우기", Settings.ARCHIVE_KEEP_LABELS,
            { Settings.archiveKeepLevel(this) },
            {
                Settings.setArchiveKeepLevel(this, it)
                TodoRepo.runAutoArchive(this)   // 바로 한 번 정리
                SyncScheduler.soon(this)
            })

        section("동기화")
        toggle("와이파이에서만 자동 동기화", "모바일 데이터에서는 기다렸다가 와이파이에 연결되면 맞춥니다",
            { Settings.wifiOnly(this) },
            {
                Settings.setWifiOnly(this, it)
                SyncScheduler.ensurePeriodic(this, replace = true)
            })
        link("구글 계정 · 지금 동기화",
            if (DriveSync.connected(this)) DriveSync.email(this) ?: "연결됨"
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

    companion object {
        val SPACING_LABELS = arrayOf("아주 좁게", "좁게", "보통 (기본)", "넓게", "아주 넓게")
    }
}
