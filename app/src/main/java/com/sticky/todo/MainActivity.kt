package com.sticky.todo

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var countView: TextView
    private lateinit var adapter: TaskAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        list = findViewById(R.id.list)
        emptyView = findViewById(R.id.empty)
        countView = findViewById(R.id.count)

        adapter = TaskAdapter(
            onToggle = { t ->
                TodoRepo.toggle(this, t.id)
                TodoRepo.runAutoArchive(this)
                reload()
            },
            onClick = { t -> startActivity(TaskEditActivity.edit(this, t.id)) },
            onLongClick = { t -> confirmDelete(t) }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<View>(R.id.add).setOnClickListener {
            startActivity(TaskEditActivity.add(this))
        }
        findViewById<ImageView>(R.id.menu).setOnClickListener { v -> showMenu(v) }
    }

    override fun onResume() {
        super.onResume()
        TodoRepo.runAutoArchive(this)
        reload()
    }

    private fun reload() {
        val items = TodoRepo.sortedItems(this)
        adapter.submit(items)
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val left = items.count { !it.done }
        countView.text = if (left > 0) left.toString() else ""
        TodoWidget.refresh(this)
    }

    private fun confirmDelete(t: Task) {
        AlertDialog.Builder(this)
            .setMessage("'" + t.text.ifBlank { "(내용 없음)" } + "' 을(를) 삭제할까요?")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                TodoRepo.delete(this, t.id)
                reload()
            }
            .show()
    }

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, getString(R.string.archive))
        menu.menu.add(0, 2, 1, getString(R.string.archive_done))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    startActivity(Intent(this, ArchiveActivity::class.java))
                    true
                }
                2 -> {
                    val n = TodoRepo.archiveAllDone(this)
                    reload()
                    Toast.makeText(
                        this,
                        if (n > 0) "${n}건을 보관함으로 옮겼습니다" else "완료한 할 일이 없습니다",
                        Toast.LENGTH_SHORT
                    ).show()
                    true
                }
                else -> false
            }
        }
        menu.show()
    }
}

class TaskAdapter(
    private val onToggle: (Task) -> Unit,
    private val onClick: (Task) -> Unit,
    private val onLongClick: (Task) -> Unit
) : RecyclerView.Adapter<TaskAdapter.VH>() {

    private var items: List<Task> = emptyList()

    fun submit(newItems: List<Task>) {
        items = newItems
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val check: ImageView = v.findViewById(R.id.check)
        val text: TextView = v.findViewById(R.id.text)
        val date: TextView = v.findViewById(R.id.date)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_task, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = items[position]
        val ctx = holder.itemView.context

        holder.check.setImageResource(
            if (t.done) R.drawable.ic_check_on else R.drawable.ic_check_off
        )
        holder.text.text = t.text.ifBlank { "(내용 없음)" }
        holder.text.paintFlags =
            if (t.done) holder.text.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            else holder.text.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
        holder.text.setTextColor(
            ContextCompat.getColor(ctx, if (t.done) R.color.done_text else R.color.text)
        )

        val d = t.localDate()
        holder.date.text = if (d != null) Dates.format(d) else t.date
        val colorRes = when {
            t.done -> R.color.done_text
            else -> when (Dates.state(d)) {
                DateState.PAST -> R.color.overdue
                DateState.TODAY -> R.color.accent
                else -> R.color.muted
            }
        }
        holder.date.setTextColor(ContextCompat.getColor(ctx, colorRes))

        holder.check.setOnClickListener { onToggle(t) }
        holder.itemView.setOnClickListener { onClick(t) }
        holder.itemView.setOnLongClickListener {
            onLongClick(t)
            true
        }
    }
}
