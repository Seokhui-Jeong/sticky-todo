package com.sticky.todo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * 할 일 저장소. SharedPreferences 안에 JSON 한 덩어리로 보관한다.
 * 앱 화면과 위젯이 같은 프로세스에서 이 객체를 함께 쓴다.
 */
object TodoRepo {

    private const val PREF = "sticky_todo"
    private const val KEY = "data"

    private var items = mutableListOf<Task>()
    private var archive = mutableListOf<Task>()
    private var seq = 0L
    private var loaded = false

    @Synchronized
    private fun ensure(ctx: Context) {
        if (loaded) return
        loaded = true
        val raw = prefs(ctx).getString(KEY, null) ?: return
        try {
            val root = JSONObject(raw)
            items = readList(root.optJSONArray("items"))
            archive = readList(root.optJSONArray("archive"))
            seq = root.optLong("seq", 0L)
        } catch (e: Exception) {
            items = mutableListOf()
            archive = mutableListOf()
            seq = 0L
        }
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun readList(arr: JSONArray?): MutableList<Task> {
        val out = mutableListOf<Task>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { out.add(Task.fromJson(it)) }
        }
        return out
    }

    @Synchronized
    private fun persist(ctx: Context) {
        val root = JSONObject()
        root.put("items", JSONArray().also { a -> items.forEach { a.put(it.toJson()) } })
        root.put("archive", JSONArray().also { a -> archive.forEach { a.put(it.toJson()) } })
        root.put("seq", seq)
        prefs(ctx).edit().putString(KEY, root.toString()).apply()
    }

    // ── 설정 ──────────────────────────────────────────────

    const val TAP_ADD = "add"    // 빈 곳 탭 -> 할 일 추가
    const val TAP_OPEN = "open"  // 빈 곳 탭 -> 앱 열기

    /** 위젯 빈 곳을 눌렀을 때의 동작 (기본: 할 일 추가) */
    fun widgetTap(ctx: Context): String =
        prefs(ctx).getString("widget_tap", TAP_ADD) ?: TAP_ADD

    fun setWidgetTap(ctx: Context, value: String) {
        prefs(ctx).edit().putString("widget_tap", value).apply()
    }

    /**
     * 위젯 줄 간격 단계 (0=아주 좁게 … 4=아주 넓게, 기본 2).
     * 단계마다 (줄 위아래 여백 dp, 체크박스 크기 dp) 가 정해져 있다.
     */
    val SPACING_PAD = intArrayOf(0, 0, 1, 4, 8)
    val SPACING_CHECK = intArrayOf(22, 26, 26, 26, 26)
    const val SPACING_DEFAULT = 2

    fun widgetSpacing(ctx: Context): Int =
        prefs(ctx).getInt("widget_spacing", SPACING_DEFAULT)
            .coerceIn(0, SPACING_PAD.size - 1)

    fun setWidgetSpacing(ctx: Context, level: Int) {
        prefs(ctx).edit()
            .putInt("widget_spacing", level.coerceIn(0, SPACING_PAD.size - 1))
            .apply()
    }

    // ── 조회 ──────────────────────────────────────────────

    @Synchronized
    fun sortedItems(ctx: Context): List<Task> {
        ensure(ctx)
        return Rules.sorted(items).map { it.copy() }
    }

    @Synchronized
    fun archived(ctx: Context): List<Task> {
        ensure(ctx)
        return archive.map { it.copy() }
    }

    @Synchronized
    fun remaining(ctx: Context): Int {
        ensure(ctx)
        return items.count { !it.done }
    }

    @Synchronized
    fun find(ctx: Context, id: String): Task? {
        ensure(ctx)
        return items.firstOrNull { it.id == id }?.copy()
    }

    // ── 변경 ──────────────────────────────────────────────

    @Synchronized
    fun add(
        ctx: Context,
        text: String,
        dateRaw: String,
        repeatDays: Int = 0,
        star: Boolean = false
    ): Task {
        ensure(ctx)
        seq += 1
        val t = Task(
            text = text.trim(),
            date = Dates.normalize(dateRaw),
            star = star,
            repeatDays = repeatDays.coerceAtLeast(0),
            seq = seq
        )
        items.add(t)
        persist(ctx)
        return t
    }

    @Synchronized
    /** star 가 null 이면 즐겨찾기 상태는 건드리지 않는다 */
    fun update(
        ctx: Context,
        id: String,
        text: String,
        dateRaw: String,
        repeatDays: Int = 0,
        star: Boolean? = null
    ) {
        ensure(ctx)
        items.firstOrNull { it.id == id }?.let {
            it.text = text.trim()
            it.date = Dates.normalize(dateRaw)
            it.repeatDays = repeatDays.coerceAtLeast(0)
            if (star != null) it.star = star
        }
        persist(ctx)
    }

    @Synchronized
    fun toggle(ctx: Context, id: String) {
        ensure(ctx)
        items.firstOrNull { it.id == id }?.let {
            it.done = !it.done
            it.doneAt = if (it.done) Task.now() else null
        }
        persist(ctx)
    }

    /** 즐겨찾기 켜고 끄기. 미완료인 동안에는 목록 맨 위로 올라간다. */
    @Synchronized
    fun toggleStar(ctx: Context, id: String) {
        ensure(ctx)
        items.firstOrNull { it.id == id }?.let { it.star = !it.star }
        persist(ctx)
    }

    @Synchronized
    fun delete(ctx: Context, id: String) {
        ensure(ctx)
        items.removeAll { it.id == id }
        persist(ctx)
    }

    // ── 보관함 ────────────────────────────────────────────

    /**
     * 하루치 정리. 두 가지를 한다.
     *   1. 주기가 돌아온 반복 항목의 체크를 풀어준다
     *   2. 기한이 지난 완료 항목을 보관함으로 옮긴다 (반복 항목은 제외)
     * 보관한 개수를 돌려준다.
     */
    @Synchronized
    fun runAutoArchive(ctx: Context, today: LocalDate = LocalDate.now()): Int {
        ensure(ctx)

        var changed = false
        items.forEach { if (Rules.applyRepeat(it, today)) changed = true }

        val move = items.filter { Rules.shouldArchive(it, today) }
        if (move.isNotEmpty()) {
            move.forEach {
                it.archivedAt = Task.now()
                archive.add(0, it)
            }
            items.removeAll(move.toSet())
        }

        if (changed || move.isNotEmpty()) persist(ctx)
        return move.size
    }

    /** 완료한 항목을 날짜와 상관없이 지금 보관한다. */
    @Synchronized
    fun archiveAllDone(ctx: Context): Int {
        ensure(ctx)
        val done = items.filter { it.done }
        if (done.isEmpty()) return 0
        done.forEach {
            it.archivedAt = Task.now()
            archive.add(0, it)
        }
        items.removeAll(done.toSet())
        persist(ctx)
        return done.size
    }

    @Synchronized
    fun restore(ctx: Context, id: String) {
        ensure(ctx)
        val rec = archive.firstOrNull { it.id == id } ?: return
        archive.remove(rec)
        seq += 1
        rec.done = false
        rec.doneAt = null
        rec.archivedAt = null
        rec.seq = seq
        items.add(rec)
        persist(ctx)
    }

    @Synchronized
    fun removeArchived(ctx: Context, id: String) {
        ensure(ctx)
        archive.removeAll { it.id == id }
        persist(ctx)
    }

    @Synchronized
    fun clearArchive(ctx: Context) {
        ensure(ctx)
        archive.clear()
        persist(ctx)
    }
}
