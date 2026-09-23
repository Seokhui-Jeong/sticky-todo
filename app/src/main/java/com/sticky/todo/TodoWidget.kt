package com.sticky.todo

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews

/**
 * 홈 화면 위젯.
 * - 목록은 ListView 라서 위젯 크기를 키우면 그만큼 더 보이고, 넘치면 스크롤된다.
 * - 체크박스를 누르면 완료 토글, 글자를 누르면 편집창, ＋ 를 누르면 추가창이 열린다.
 * - 위젯 높이가 낮으면 상단 머리글을 숨겨 할 일이 한 줄이라도 더 보이게 한다.
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

    /** 사용자가 위젯 크기를 바꾸면 머리글을 보일지 다시 판단한다 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        render(context, appWidgetManager, appWidgetId)
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_list)
    }

    companion object {

        /** 이 높이(dp) 아래로 작아지면 머리글을 숨긴다 */
        private const val HEADER_MIN_HEIGHT_DP = 100

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

            // 위젯이 낮으면 머리글과 구분선을 숨겨 목록에 자리를 내준다
            val heightDp = try {
                mgr.getAppWidgetOptions(widgetId)
                    .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            } catch (e: Exception) {
                0
            }
            val showHeader = heightDp == 0 || heightDp >= HEADER_MIN_HEIGHT_DP
            val headerVisibility = if (showHeader) View.VISIBLE else View.GONE
            rv.setViewVisibility(R.id.widget_header, headerVisibility)
            rv.setViewVisibility(R.id.widget_divider, headerVisibility)

            // 앱 열기 / 할 일 추가, 두 가지 동작을 미리 만들어 둔다
            val openApp = PendingIntent.getActivity(
                ctx, widgetId * 10,
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val addTask = PendingIntent.getActivity(
                ctx, widgetId * 10 + 1,
                Intent(ctx, TaskEditActivity::class.java)
                    .putExtra(TaskEditActivity.EXTRA_MODE, TaskEditActivity.MODE_ADD)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 제목은 언제나 앱, ＋ 는 언제나 추가.
            // 빈 곳(테두리 여백과 목록이 비었을 때의 안내문)만 설정을 따른다.
            val tapIsAdd = TodoRepo.widgetTap(ctx) == TodoRepo.TAP_ADD
            val blankTap = if (tapIsAdd) addTask else openApp

            rv.setOnClickPendingIntent(R.id.widget_title, openApp)
            rv.setOnClickPendingIntent(R.id.widget_count, openApp)
            rv.setOnClickPendingIntent(R.id.widget_add, addTask)
            rv.setOnClickPendingIntent(R.id.widget_root, blankTap)
            rv.setOnClickPendingIntent(R.id.widget_empty, blankTap)

            rv.setTextViewText(
                R.id.widget_empty,
                ctx.getString(if (tapIsAdd) R.string.empty_tap_add else R.string.empty)
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
