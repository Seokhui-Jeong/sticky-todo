package com.sticky.todo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 위젯과 앱이 함께 쓰는 작은 편집 창.
 *  - MODE_ADD    : 새 할 일 추가
 *  - MODE_EDIT   : 기존 할 일 수정 / 삭제
 *  - MODE_TOGGLE : 화면 없이 완료 토글만 하고 바로 닫힘 (위젯 체크박스)
 */
class TaskEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_ID = "id"
        const val MODE_ADD = "add"
        const val MODE_EDIT = "edit"
        const val MODE_TOGGLE = "toggle"

        fun add(ctx: Context) = Intent(ctx, TaskEditActivity::class.java)
            .putExtra(EXTRA_MODE, MODE_ADD)

        fun edit(ctx: Context, id: String) = Intent(ctx, TaskEditActivity::class.java)
            .putExtra(EXTRA_MODE, MODE_EDIT)
            .putExtra(EXTRA_ID, id)
    }

    private lateinit var textField: EditText
    private lateinit var dateField: EditText
    private lateinit var repeatValue: TextView
    private lateinit var starIcon: ImageView
    private lateinit var starLabel: TextView

    private var taskId: String? = null
    private var starOn = false
    private var repeatMode = Repeat.NONE
    private var repeatDays = 0
    private var repeatWeekdays = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_ADD
        if (mode == MODE_TOGGLE) {
            // 창을 전혀 띄우지 않는다 (AppCompat 계열이어야 한다)
            setTheme(R.style.InvisibleTheme)
        }
        super.onCreate(savedInstanceState)

        if (mode == MODE_TOGGLE) {
            intent?.getStringExtra(EXTRA_ID)?.let {
                TodoRepo.toggle(this, it)
                TodoRepo.runAutoArchive(this)
            }
            TodoWidget.refresh(this)
            finish()
            overridePendingTransition(0, 0)
            return
        }

        setContentView(R.layout.activity_edit)
        setFinishOnTouchOutside(true)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)

        textField = findViewById(R.id.edit_text)
        dateField = findViewById(R.id.edit_date)
        repeatValue = findViewById(R.id.repeat_value)
        starIcon = findViewById(R.id.edit_star)
        starLabel = findViewById(R.id.edit_star_label)

        findViewById<View>(R.id.repeat_row).setOnClickListener { chooseRepeatMode() }
        findViewById<View>(R.id.star_row).setOnClickListener {
            starOn = !starOn
            paintStar()
        }

        val deleteBtn = findViewById<TextView>(R.id.btn_delete)
        val cancelBtn = findViewById<TextView>(R.id.btn_cancel)
        val saveBtn = findViewById<TextView>(R.id.btn_save)

        if (mode == MODE_EDIT) {
            taskId = intent.getStringExtra(EXTRA_ID)
            val t = taskId?.let { TodoRepo.find(this, it) }
            if (t == null) {
                finish()
                return
            }
            textField.setText(t.text)
            dateField.setText(Dates.format(t.localDate()).ifEmpty { t.date })
            repeatMode = t.repeatMode
            repeatDays = t.repeatDays
            repeatWeekdays = t.repeatWeekdays
            starOn = t.star
            textField.setSelection(textField.text.length)
            deleteBtn.visibility = TextView.VISIBLE
            deleteBtn.setOnClickListener {
                taskId?.let { TodoRepo.delete(this, it) }
                done()
            }
        }

        paintStar()
        paintRepeat()

        textField.requestFocus()
        cancelBtn.setOnClickListener { finish() }
        saveBtn.setOnClickListener { save() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    // ── 반복 설정 ─────────────────────────────────────────

    /** 1단계: 방식 고르기 */
    private fun chooseRepeatMode() {
        val modes = arrayOf(Repeat.NONE, Repeat.DUE, Repeat.DONE, Repeat.WEEK)
        val labels = arrayOf(
            getString(R.string.repeat_none),
            getString(R.string.repeat_due),
            getString(R.string.repeat_done),
            getString(R.string.repeat_week)
        )
        val current = modes.indexOf(repeatMode).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.repeat_mode_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                when (modes[which]) {
                    Repeat.NONE -> {
                        repeatMode = Repeat.NONE
                        paintRepeat()
                    }
                    Repeat.WEEK -> chooseWeekdays()
                    else -> chooseDays(modes[which])
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 2단계(기한/체크일 기준): 며칠마다 */
    private fun chooseDays(mode: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.repeat_days_hint)
            if (repeatDays > 0) setText(repeatDays.toString())
            setSelection(text.length)
        }
        val box = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.repeat_days_title)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val n = input.text.toString().trim().toIntOrNull() ?: 0
                if (n > 0) {
                    repeatMode = mode
                    repeatDays = n
                } else {
                    repeatMode = Repeat.NONE
                }
                paintRepeat()
            }
            .show()
    }

    /** 2단계(고정 요일): 요일 고르기 */
    private fun chooseWeekdays() {
        val checked = BooleanArray(7) { repeatWeekdays and (1 shl it) != 0 }
        AlertDialog.Builder(this)
            .setTitle(R.string.repeat_week_title)
            .setMultiChoiceItems(Repeat.SHORT, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                var mask = 0
                checked.forEachIndexed { i, on -> if (on) mask = mask or (1 shl i) }
                if (mask != 0) {
                    repeatMode = Repeat.WEEK
                    repeatWeekdays = mask
                } else {
                    repeatMode = Repeat.NONE
                }
                paintRepeat()
            }
            .show()
    }

    /** 반복 줄에 지금 설정을 적는다 */
    private fun paintRepeat() {
        val text = when {
            repeatMode == Repeat.WEEK && repeatWeekdays != 0 ->
                getString(R.string.repeat_week) + " · " + Repeat.weekdayText(repeatWeekdays)
            repeatMode == Repeat.DUE && repeatDays > 0 ->
                getString(R.string.repeat_due) + " · ${repeatDays}일마다"
            repeatMode == Repeat.DONE && repeatDays > 0 ->
                getString(R.string.repeat_done) + " · ${repeatDays}일마다"
            else -> getString(R.string.repeat_none)
        }
        val on = repeatMode != Repeat.NONE
        repeatValue.text = text
        repeatValue.setTextColor(
            ContextCompat.getColor(this, if (on) R.color.accent else R.color.muted)
        )
    }

    // ── 즐겨찾기 ──────────────────────────────────────────

    private fun paintStar() {
        starIcon.setImageResource(
            if (starOn) R.drawable.ic_star_on else R.drawable.ic_star_off
        )
        starIcon.alpha = if (starOn) 1f else 0.45f
        starLabel.setText(
            if (starOn) R.string.star_hint_on else R.string.star_hint_off
        )
        starLabel.setTextColor(
            ContextCompat.getColor(this, if (starOn) R.color.text else R.color.muted)
        )
    }

    // ── 저장 ──────────────────────────────────────────────

    private fun save() {
        val text = textField.text.toString().trim()
        val date = dateField.text.toString().trim()
        if (text.isEmpty() && date.isEmpty()) {
            finish()
            return
        }
        val id = taskId
        if (id == null) {
            TodoRepo.add(this, text, date, repeatMode, repeatDays, repeatWeekdays, starOn)
        } else {
            TodoRepo.update(this, id, text, date, repeatMode, repeatDays, repeatWeekdays, starOn)
        }
        TodoRepo.runAutoArchive(this)
        done()
    }

    private fun done() {
        TodoWidget.refresh(this)
        setResult(RESULT_OK)
        finish()
    }
}
