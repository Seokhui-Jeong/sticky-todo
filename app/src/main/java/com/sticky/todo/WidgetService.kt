package com.sticky.todo

import android.content.Context
import android.content.Intent
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StrikethroughSpan
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.content.ContextCompat

/** 위젯 목록에 줄을 하나씩 공급하는 서비스 */
class WidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        TodoFactory(applicationContext)
}

private class TodoFactory(private val ctx: Context) : RemoteViewsService.RemoteViewsFactory {

    private var items: List<Task> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        // 위젯이 갱신될 때마다 기한 지난 완료 항목을 정리한다
        TodoRepo.runAutoArchive(ctx)
        items = TodoRepo.sortedItems(ctx)
    }

    override fun onDestroy() {
        items = emptyList()
    }

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_item)
        if (position !in items.indices) return rv
        val t = items[position]

        rv.setImageViewResource(
            R.id.item_check,
            if (t.done) R.drawable.ic_check_on else R.drawable.ic_check_off
        )

        val label = t.text.ifBlank { "(내용 없음)" }
        if (t.done) {
            val sp = SpannableString(label)
            sp.setSpan(StrikethroughSpan(), 0, label.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            rv.setTextViewText(R.id.item_text, sp)
            rv.setTextColor(R.id.item_text, color(R.color.widget_muted))
        } else {
            rv.setTextViewText(R.id.item_text, label)
            rv.setTextColor(R.id.item_text, color(R.color.widget_text))
        }

        val d = t.localDate()
        rv.setTextViewText(R.id.item_date, if (d != null) Dates.format(d) else t.date)
        val dateColor = when {
            t.done -> R.color.widget_muted
            else -> when (Dates.state(d)) {
                DateState.PAST -> R.color.overdue
                DateState.TODAY -> R.color.accent
                else -> R.color.widget_muted
            }
        }
        rv.setTextColor(R.id.item_date, color(dateColor))

        // 체크박스 → 완료 토글
        rv.setOnClickFillInIntent(
            R.id.item_check,
            Intent()
                .putExtra(TaskEditActivity.EXTRA_MODE, TaskEditActivity.MODE_TOGGLE)
                .putExtra(TaskEditActivity.EXTRA_ID, t.id)
        )
        // 글자 → 편집창
        rv.setOnClickFillInIntent(
            R.id.item_body,
            Intent()
                .putExtra(TaskEditActivity.EXTRA_MODE, TaskEditActivity.MODE_EDIT)
                .putExtra(TaskEditActivity.EXTRA_ID, t.id)
        )
        return rv
    }

    private fun color(res: Int) = ContextCompat.getColor(ctx, res)

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long =
        if (position in items.indices) items[position].id.hashCode().toLong()
        else position.toLong()

    override fun hasStableIds(): Boolean = true
}
