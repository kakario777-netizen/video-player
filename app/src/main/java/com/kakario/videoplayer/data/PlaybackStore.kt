package com.kakario.videoplayer.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.kakario.videoplayer.model.FileEntry
import com.kakario.videoplayer.model.MediaOrigin
import org.json.JSONArray
import org.json.JSONObject

/** 최근에 본 영상 하나 (첫 화면 '이어서 보기' 줄). serverId가 null이면 내 폰. */
data class RecentVideo(
    val serverId: Long?,
    val path: String,
    val name: String,
    val size: Long,
    val modified: Long,
    val watchedAt: Long,
) {
    fun toEntry() = FileEntry(name, path, isDirectory = false, size = size, modified = modified)
}

/** 영상 하나의 시청 상태. positionMs = 마지막으로 본 위치, lengthMs = 전체 길이(모르면 0). */
data class WatchProgress(val positionMs: Long, val lengthMs: Long) {
    val started: Boolean get() = positionMs > 0 && lengthMs > 0
    /** 끝에서 2% 또는 30초 안쪽이면 다 본 것으로 친다. */
    val finished: Boolean
        get() = lengthMs > 0 && positionMs >= lengthMs - maxOf(30_000L, lengthMs / 50)
    val fraction: Float get() = if (lengthMs > 0) (positionMs.toFloat() / lengthMs).coerceIn(0f, 1f) else 0f
}

/**
 * 영상마다 마지막 재생 위치를 기억한다 (이어보기 + 목록에 시청 막대 표시).
 * 앱 전체에서 하나만 쓴다.
 */
class PlaybackStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)

    /** 재생 화면을 닫을 때마다 올라가는 번호 — 목록이 시청 막대를 다시 그리게 하는 신호. */
    var version by mutableIntStateOf(0)
        private set

    fun keyFor(origin: MediaOrigin, entry: FileEntry): String =
        "${origin.tag}|${entry.path}|${entry.size}"

    fun get(key: String): WatchProgress =
        WatchProgress(prefs.getLong(key, 0L), prefs.getLong("$key#len", 0L))

    fun set(key: String, positionMs: Long, lengthMs: Long) {
        prefs.edit().putLong(key, positionMs).putLong("$key#len", lengthMs).apply()
    }

    fun clear(key: String) {
        prefs.edit().remove(key).remove("$key#len").apply()
        version++
    }

    fun bump() {
        version++
    }

    /** 최근 본 영상 (최신이 앞, 최대 30개). */
    fun recent(): List<RecentVideo> {
        val raw = prefs.getString(RECENT, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RecentVideo(
                    serverId = if (o.isNull("server")) null else o.getLong("server"),
                    path = o.getString("path"),
                    name = o.getString("name"),
                    size = o.optLong("size"),
                    modified = o.optLong("mod"),
                    watchedAt = o.optLong("at"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addRecent(origin: MediaOrigin, entry: FileEntry) {
        val sid = origin.server?.id
        val list = listOf(
            RecentVideo(sid, entry.path, entry.name, entry.size, entry.modified, System.currentTimeMillis())
        ) + recent().filterNot { it.serverId == sid && it.path == entry.path }
        saveRecent(list.take(30))
    }

    fun removeRecent(item: RecentVideo) {
        saveRecent(recent().filterNot { it.serverId == item.serverId && it.path == item.path })
        version++
    }

    /** 이어서 보기 목록 모두 비우기 (본 위치 기록은 그대로). */
    fun clearRecent() {
        saveRecent(emptyList())
        version++
    }

    /** 서버를 지우면 그 서버의 기록도 지운다. */
    fun removeServer(serverId: Long) {
        saveRecent(recent().filterNot { it.serverId == serverId })
        version++
    }

    private fun saveRecent(list: List<RecentVideo>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(
                JSONObject()
                    .put("server", r.serverId ?: JSONObject.NULL)
                    .put("path", r.path)
                    .put("name", r.name)
                    .put("size", r.size)
                    .put("mod", r.modified)
                    .put("at", r.watchedAt)
            )
        }
        prefs.edit().putString(RECENT, arr.toString()).apply()
    }

    companion object {
        private const val RECENT = "__recent_list"

        @Volatile private var instance: PlaybackStore? = null

        fun get(context: Context): PlaybackStore =
            instance ?: synchronized(this) {
                instance ?: PlaybackStore(context.applicationContext).also { instance = it }
            }
    }
}
