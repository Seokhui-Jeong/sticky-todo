package com.sticky.todo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ArchiveActivity : BaseActivity() {

    private lateinit var list: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var adapter: ArchiveAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_archive)

        list = findViewById(R.id.list)
        emptyView = findViewById(R.id.empty)

        adapter = ArchiveAdapter(
            onRestore = { t ->
                TodoRepo.restore(this, t.id)
                TodoWidget.refresh(this)
                reload()
            },
            onRemove = { t ->
                TodoRepo.removeArchived(this, t.id)
                reload()
            }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<TextView>(R.id.clear).setOnClickListener {
            if (TodoRepo.archived(this).isEmpty()) return@setOnClickListener
            AlertDialog.Builder(this)
                .setMessage(R.string.archive_clear_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ ->
                    TodoRepo.clearArchive(this)
                    reload()
                }
                .show()
        }
        reload()
    }

    private fun reload() {
        SyncScheduler.soon(this)
        val items = TodoRepo.archived(this)
        adapter.submit(items)
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }
}

class ArchiveAdapter(
    private val onRestore: (Task) -> Unit,
    private val onRemove: (Task) -> Unit
) : RecyclerView.Adapter<ArchiveAdapter.VH>() {

    private var items: List<Task> = emptyList()

    fun submit(newItems: List<Task>) {
        items = newItems
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.text)
        val sub: TextView = v.findViewById(R.id.sub)
        val restore: TextView = v.findViewById(R.id.restore)
        val remove: TextView = v.findViewById(R.id.remove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_archive, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = items[position]
        val ctx = holder.itemView.context
        holder.text.text = t.text.ifBlank { ctx.getString(R.string.no_content) }

        val mark = ctx.getString(if (t.done) R.string.mark_done else R.string.mark_undone)
        val dateText = dateLabel(t, style = Settings.dateStyle(ctx))
        holder.sub.text = listOf(mark, dateText).filter { it.isNotBlank() }.joinToString(" · ")

        holder.restore.setOnClickListener { onRestore(t) }
        holder.remove.setOnClickListener { onRemove(t) }
    }
}
