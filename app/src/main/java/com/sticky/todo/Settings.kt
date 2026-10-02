package com.sticky.todo

import android.content.Context

/**
 * 사용자 설정 모음. 할 일 목록과 같은 SharedPreferences 파일을 쓰지만 키가 겹치지 않는다.
 * (위젯 빈 곳 탭 동작과 줄 간격은 예전부터 TodoRepo 에 있어 그대로 둔다)
 */
object Settings {

    private const val PREF = "sticky_todo"
    private fun p(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ── 위젯: 머리글 ──────────────────────────────────────

    const val HEADER_AUTO = 0     // 위젯이 낮으면 숨김 (예전 동작)
    const val HEADER_ALWAYS = 1
    const val HEADER_HIDE = 2
    val HEADER_LABELS = arrayOf("자동 (낮으면 숨김)", "항상 표시", "항상 숨김")

    fun header(ctx: Context): Int = p(ctx).getInt("widget_header", HEADER_AUTO).coerceIn(0, 2)
    fun setHeader(ctx: Context, v: Int) = p(ctx).edit().putInt("widget_header", v).apply()

    // ── 위젯: 완료 항목 숨기기 ────────────────────────────

    fun hideDoneInWidget(ctx: Context): Boolean = p(ctx).getBoolean("widget_hide_done", false)
    fun setHideDoneInWidget(ctx: Context, v: Boolean) =
        p(ctx).edit().putBoolean("widget_hide_done", v).apply()

    // ── 위젯: 배경 불투명도 ───────────────────────────────

    val OPACITY = intArrayOf(100, 95, 80, 60, 40, 20, 0)
    val OPACITY_LABELS = arrayOf("100% (불투명)", "95% (기본)", "80%", "60%", "40%", "20%", "0% (완전 투명)")
    const val OPACITY_DEFAULT = 1

    fun opacityLevel(ctx: Context): Int =
        p(ctx).getInt("widget_opacity", OPACITY_DEFAULT).coerceIn(0, OPACITY.size - 1)
    fun setOpacityLevel(ctx: Context, v: Int) = p(ctx).edit().putInt("widget_opacity", v).apply()

    /** ImageView.setImageAlpha 에 넣을 0..255 값 */
    fun opacityAlpha(ctx: Context): Int = OPACITY[opacityLevel(ctx)] * 255 / 100

    // ── 위젯: 글자 크기 ───────────────────────────────────

    val FONT_TEXT = floatArrayOf(12f, 13f, 15f, 17f)   // 할 일 글자 (sp)
    val FONT_SMALL = floatArrayOf(10f, 11f, 12f, 14f)  // 날짜·개수 (sp)
    val FONT_LABELS = arrayOf("작게", "보통 (기본)", "크게", "아주 크게")
    const val FONT_DEFAULT = 1

    fun fontLevel(ctx: Context): Int =
        p(ctx).getInt("widget_font", FONT_DEFAULT).coerceIn(0, FONT_TEXT.size - 1)
    fun setFontLevel(ctx: Context, v: Int) = p(ctx).edit().putInt("widget_font", v).apply()

    // ── 날짜 표시 ─────────────────────────────────────────

    val DATE_LABELS = arrayOf("날짜 (10/5)", "남은 날 (D-3)", "둘 다 (10/5 D-3)")

    fun dateMode(ctx: Context): Int =
        p(ctx).getInt("date_mode", DateStyle.DATE).coerceIn(0, 2)
    fun setDateMode(ctx: Context, v: Int) = p(ctx).edit().putInt("date_mode", v).apply()

    fun showWeekday(ctx: Context): Boolean = p(ctx).getBoolean("date_weekday", false)
    fun setShowWeekday(ctx: Context, v: Boolean) =
        p(ctx).edit().putBoolean("date_weekday", v).apply()

    fun dateStyle(ctx: Context): DateStyle = DateStyle(dateMode(ctx), showWeekday(ctx))

    // ── 보관함 자동 비우기 ────────────────────────────────

    val ARCHIVE_KEEP_DAYS = intArrayOf(0, 30, 90)        // 0 = 끔
    val ARCHIVE_KEEP_LABELS = arrayOf("끔 (계속 보관)", "30일 지나면 삭제", "90일 지나면 삭제")

    fun archiveKeepLevel(ctx: Context): Int =
        p(ctx).getInt("archive_keep", 0).coerceIn(0, ARCHIVE_KEEP_DAYS.size - 1)
    fun setArchiveKeepLevel(ctx: Context, v: Int) = p(ctx).edit().putInt("archive_keep", v).apply()
    fun archiveKeepDays(ctx: Context): Int = ARCHIVE_KEEP_DAYS[archiveKeepLevel(ctx)]

    // ── 동기화 ────────────────────────────────────────────

    fun wifiOnly(ctx: Context): Boolean = p(ctx).getBoolean("sync_wifi_only", false)
    fun setWifiOnly(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("sync_wifi_only", v).apply()
}
