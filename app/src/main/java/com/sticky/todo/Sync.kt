package com.sticky.todo

import org.json.JSONArray
import org.json.JSONObject

/**
 * 기기 사이에 주고받는 한 덩어리.
 *   tasks   : 할 일 전부 (보관함 항목도 archivedAt 이 채워진 채로 함께 들어온다)
 *   deleted : 지운 항목의 기록. id -> 지운 시각(밀리초)
 *
 * 지운 기록을 같이 들고 다녀야 하는 이유는, 그게 없으면 A폰에서 지운 항목이
 * B폰에 남아 있다가 다음 동기화 때 되살아나기 때문이다.
 */
data class SyncData(
    val tasks: List<Task> = emptyList(),
    val deleted: Map<String, Long> = emptyMap()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("format", FORMAT)
        put("tasks", JSONArray().also { a -> tasks.forEach { a.put(it.toJson()) } })
        put("deleted", JSONObject().also { o ->
            deleted.forEach { (id, at) -> o.put(id, at) }
        })
        put("savedAt", System.currentTimeMillis())
    }

    /** 내용이 같은지 가볍게 비교하기 위한 지문 (저장 시각은 뺀다) */
    fun signature(): String {
        val sb = StringBuilder()
        tasks.sortedBy { it.id }.forEach {
            sb.append(it.id).append(':').append(it.updatedAt).append(';')
        }
        sb.append('#')
        deleted.entries.sortedBy { it.key }.forEach {
            sb.append(it.key).append(':').append(it.value).append(';')
        }
        return sb.toString()
    }

    companion object {
        const val FORMAT = 1

        fun fromJson(o: JSONObject): SyncData {
            val tasks = mutableListOf<Task>()
            o.optJSONArray("tasks")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { tasks.add(Task.fromJson(it)) }
                }
            }
            val deleted = mutableMapOf<String, Long>()
            o.optJSONObject("deleted")?.let { d ->
                val keys = d.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    deleted[k] = d.optLong(k, 0L)
                }
            }
            return SyncData(tasks, deleted)
        }

        fun parse(text: String): SyncData? = try {
            fromJson(JSONObject(text))
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * 두 기기의 목록을 합치는 규칙.
 *
 *   1. 같은 항목(id)이 양쪽에 있으면 마지막으로 고친 쪽을 남긴다
 *   2. 한쪽에만 있으면 그대로 가져온다
 *   3. 지운 기록이 그 항목의 마지막 수정보다 나중이면 지운 것으로 본다
 *      (반대로 지운 뒤에 다른 기기에서 고쳤다면 되살아난다)
 *   4. 오래된 지움 기록은 버린다. 계속 쌓이면 짐만 된다
 */
object Merge {

    /** 지움 기록을 보관하는 기간 */
    const val TOMBSTONE_DAYS = 90
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun merge(local: SyncData, remote: SyncData, now: Long = System.currentTimeMillis()): SyncData {
        // 지움 기록 합치기 — 같은 id 면 더 나중에 지운 시각을 남긴다
        val deleted = mutableMapOf<String, Long>()
        for ((id, at) in local.deleted) deleted[id] = at
        for ((id, at) in remote.deleted) {
            val cur = deleted[id]
            if (cur == null || at > cur) deleted[id] = at
        }

        // 항목 합치기 — 같은 id 면 마지막으로 고친 쪽
        val byId = LinkedHashMap<String, Task>()
        for (t in local.tasks) byId[t.id] = t
        for (t in remote.tasks) {
            val cur = byId[t.id]
            if (cur == null || t.updatedAt > cur.updatedAt) byId[t.id] = t
        }

        // 지운 뒤에 고친 게 아니라면 지운 것으로 본다
        val kept = byId.values.filter { t ->
            val gone = deleted[t.id]
            gone == null || t.updatedAt > gone
        }

        // 되살아난 항목은 지움 기록에서 뺀다
        val keptIds = kept.map { it.id }.toSet()
        val cutoff = now - TOMBSTONE_DAYS * DAY_MS
        val trimmed = deleted.filterKeys { it !in keptIds }
            .filterValues { it >= cutoff }

        return SyncData(kept, trimmed)
    }
}
