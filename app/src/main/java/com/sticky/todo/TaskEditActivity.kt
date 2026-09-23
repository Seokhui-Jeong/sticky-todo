package com.sticky.todo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

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
    private var taskId: String? = null

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
            textField.setSelection(textField.text.length)
            deleteBtn.visibility = TextView.VISIBLE
            deleteBtn.setOnClickListener {
                taskId?.let { TodoRepo.delete(this, it) }
                done()
            }
        }

        textField.requestFocus()
        cancelBtn.setOnClickListener { finish() }
        saveBtn.setOnClickListener { save() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    private fun save() {
        val text = textField.text.toString().trim()
        val date = dateField.text.toString().trim()
        if (text.isEmpty() && date.isEmpty()) {
            finish()
            return
        }
        val id = taskId
        if (id == null) {
            TodoRepo.add(this, text, date)
        } else {
            TodoRepo.update(this, id, text, date)
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
