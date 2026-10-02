package com.sticky.todo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * 할 일 저장소. SharedPreferences 안에 JSON 한 덩어리로 보관한다.
 * 앱 화면과 위젯이 같은 프로세스에서 이 객체를 함께 쓴다.
 *
 * 기기 간 동기화를 위해 두 가지를 더 들고 있다.
 *   - 항목마다 마지막 수정 시각 (Task.updatedAt)
 *   - 지운 항목의 기록 (deleted). 없으면 지운 게 다시 살아난다.
 */
object TodoRepo {

    private const val PREF = "sticky_todo"
    private const val KEY = "data"

    private var items = mutableListOf<Task>()
    private var archive = mutableListOf<Task>()
    private var deleted = mutableMapOf<String, Long>()
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
            deleted = mutableMapOf()
            root.optJSONObject("deleted")?.let { d ->
                val keys = d.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    deleted[k] = d.optLong(k, 0L)
                }
            }
        } catch (e: Exception) {
            items = mutableListOf()
            archive = mutableListOf()
            deleted = mutableMapOf()
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
        root.put("deleted", JSONObject().also { o ->
            deleted.forEach { (id, at) -> o.put(id, at) }
        })
        root.put("seq", seq)
        prefs(ctx).edit().putString(KEY, root.toString()).apply()
    }

    private fun bury(id: String, at: Long = System.currentTimeMillis()) {
        deleted[id] = at
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
        repeatMode: String = Repeat.NONE,
        repeatDays: Int = 0,
        repeatWeekdays: Int = 0,
        star: Boolean = false
    ): Task {
        ensure(ctx)
        seq += 1
        val t = Task(
            text = text.trim(),
            date = Dates.normalize(dateRaw),
            star = star,
            repeatMode = repeatMode,
            repeatDays = repeatDays.coerceAtLeast(0),
            repeatWeekdays = repeatWeekdays,
            seq = seq
        )
        t.touch()
        items.add(t)
        persist(ctx)
        return t
    }

    /** star 가 null 이면 즐겨찾기 상태는 건드리지 않는다 */
    @Synchronized
    fun update(
        ctx: Context,
        id: String,
        text: String,
        dateRaw: String,
        repeatMode: String = Repeat.NONE,
        repeatDays: Int = 0,
        repeatWeekdays: Int = 0,
        star: Boolean? = null
    ) {
        ensure(ctx)
        items.firstOrNull { it.id == id }?.let {
            it.text = text.trim()
            it.date = Dates.normalize(dateRaw)
            it.repeatMode = repeatMode
            it.repeatDays = repeatDays.coerceAtLeast(0)
            it.repeatWeekdays = repeatWeekdays
            if (star != null) it.star = star
            it.touch()
        }
        persist(ctx)
    }

    @Synchronized
    fun toggle(ctx: Context, id: String) {
        ensure(ctx)
        items.firstOrNull { it.id == id }?.let {
            it.done = !it.done
            it.doneAt = if (it.done) Task.now() else null
            it.touch()
        }
        persist(ctx)
    }

    /** 즐겨찾기 켜고 끄기. 미완료인 동안에는 목록 맨 위로 올라간다. */
    @Synchronized
    fun toggleStar(ctx: Context, id: String) {
        ensure(ctx)
        items.firstOrNull { it.id == id }?.let {
            it.star = !it.star
            it.touch()
        }
        persist(ctx)
    }

    @Synchronized
    fun delete(ctx: Context, id: String) {
        ensure(ctx)
        if (items.removeAll { it.id == id }) bury(id)
        persist(ctx)
    }

    // ── 보관함 ────────────────────────────────────────────

    /**
     * 하루치 정리. 세 가지를 한다.
     *   1. 주기가 돌아온 반복 항목의 체크를 풀어준다
     *   2. 기한이 지난 완료 항목을 보관함으로 옮긴다 (반복 항목은 제외)
     *   3. 설정에 따라 오래된 보관함 항목을 지운다
     * 보관한 개수를 돌려준다.
     */
    @Synchronized
    fun runAutoArchive(ctx: Context, today: LocalDate = LocalDate.now()): Int {
        ensure(ctx)

        var changed = false
        items.forEach {
            if (Rules.applyRepeat(it, today)) {
                it.touch()
                changed = true
            }
        }

        val move = items.filter { Rules.shouldArchive(it, today) }
        if (move.isNotEmpty()) {
            move.forEach {
                it.archivedAt = Task.now()
                it.touch()
                archive.add(0, it)
            }
            items.removeAll(move.toSet())
        }

        // 보관함 자동 비우기 (설정한 날수가 지난 것). 지운 기록을 남겨 다른 기기에서도 지워지게 한다.
        var purged = false
        val keepDays = Settings.archiveKeepDays(ctx)
        if (keepDays > 0 && archive.isNotEmpty()) {
            val cutoff = today.minusDays(keepDays.toLong())
            val old = archive.filter { rec ->
                val day = rec.archivedAt?.let {
                    try { LocalDate.parse(it.substring(0, 10)) } catch (e: Exception) { null }
                }
                day != null && day.isBefore(cutoff)
            }
            if (old.isNotEmpty()) {
                val now = System.currentTimeMillis()
                old.forEach { bury(it.id, now) }
                archive.removeAll(old.toSet())
                purged = true
            }
        }

        if (changed || move.isNotEmpty() || purged) persist(ctx)
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
            it.touch()
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
        rec.touch()
        items.add(rec)
        persist(ctx)
    }

    @Synchronized
    fun removeArchived(ctx: Context, id: String) {
        ensure(ctx)
        if (archive.removeAll { it.id == id }) bury(id)
        persist(ctx)
    }

    @Synchronized
    fun clearArchive(ctx: Context) {
        ensure(ctx)
        val now = System.currentTimeMillis()
        archive.forEach { bury(it.id, now) }
        archive.clear()
        persist(ctx)
    }

    // ── 동기화용 입출구 ────────────────────────────────────

    /** 지금 이 기기가 들고 있는 전부를 한 덩어리로 */
    @Synchronized
    fun snapshot(ctx: Context): SyncData {
        ensure(ctx)
        val all = ArrayList<Task>(items.size + archive.size)
        items.forEach { all.add(it.copy()) }
        archive.forEach { all.add(it.copy()) }
        return SyncData(all, HashMap(deleted))
    }

    /**
     * 병합 결과를 이 기기에 적용한다.
     * 실제로 내용이 달라졌으면 true 를 돌려준다 (화면·위젯을 다시 그리기 위해).
     */
    @Synchronized
    fun applyMerged(ctx: Context, data: SyncData): Boolean {
        ensure(ctx)
        val before = snapshotSignature()

        val newItems = mutableListOf<Task>()
        val newArchive = mutableListOf<Task>()
        for (t in data.tasks) {
            if (t.archivedAt.isNullOrBlank()) newItems.add(t) else newArchive.add(t)
        }
        newArchive.sortByDescending { it.archivedAt ?: "" }

        items = newItems
        archive = newArchive
        deleted = HashMap(data.deleted)
        seq = (items.maxOfOrNull { it.seq } ?: 0L)
            .coerceAtLeast(archive.maxOfOrNull { it.seq } ?: 0L)
            .coerceAtLeast(seq)

        persist(ctx)
        return before != snapshotSignature()
    }

    /** 내용이 바뀌었는지만 가볍게 비교하기 위한 지문 */
    private fun snapshotSignature(): String {
        val sb = StringBuilder()
        (items + archive).sortedBy { it.id }.forEach {
            sb.append(it.id).append(':').append(it.updatedAt).append(';')
        }
        sb.append('#').append(deleted.keys.sorted().joinToString(","))
        return sb.toString()
    }
}
