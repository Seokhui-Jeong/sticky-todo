package com.sticky.todo

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews

/**
 * 홈 화면 위젯.
 * - 목록은 ListView 라서 위젯 크기를 키우면 그만큼 더 보이고, 넘치면 스크롤된다.
 * - 체크박스를 누르면 완료 토글, 글자를 누르면 편집창, + 를 누르면 추가창이 열린다.
 */
class TodoWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        TodoRepo.runAutoArchive(context)
        appWidgetIds.forEach { render(context, appWidgetManager, it) }
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widget_list)
    }

    companion object {

        /** 데이터가 바뀐 뒤 어디서든 호출하면 모든 위젯이 갱신된다. */
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, TodoWidget::class.java))
            if (ids.isEmpty()) return
            ids.forEach { render(ctx, mgr, it) }
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, widgetId: Int) {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_todo)

            val left = TodoRepo.remaining(ctx)
            rv.setTextViewText(R.id.widget_count, if (left > 0) left.toString() else "")

            // 제목 → 앱 열기
            rv.setOnClickPendingIntent(
                R.id.widget_title,
                PendingIntent.getActivity(
                    ctx, widgetId * 10,
                    Intent(ctx, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            // ＋ → 추가창
            rv.setOnClickPendingIntent(
                R.id.widget_add,
                PendingIntent.getActivity(
                    ctx, widgetId * 10 + 1,
                    Intent(ctx, TaskEditActivity::class.java)
                        .putExtra(TaskEditActivity.EXTRA_MODE, TaskEditActivity.MODE_ADD)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            // 목록 어댑터 (위젯마다 별도 data URI 로 구분)
            val svc = Intent(ctx, WidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            @Suppress("DEPRECATION")
            rv.setRemoteAdapter(R.id.widget_list, svc)
            rv.setEmptyView(R.id.widget_list, R.id.widget_empty)

            // 항목 클릭 공통 틀 (세부 동작은 각 항목의 fill-in intent 가 채운다)
            rv.setPendingIntentTemplate(
                R.id.widget_list,
                PendingIntent.getActivity(
                    ctx, widgetId * 10 + 2,
                    Intent(ctx, TaskEditActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
            )

            mgr.updateAppWidget(widgetId, rv)
        }
    }
}
