package com.kakario.videoplayer.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.kakario.videoplayer.model.FileEntry
import com.kakario.videoplayer.model.MediaOrigin

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

    companion object {
        @Volatile private var instance: PlaybackStore? = null

        fun get(context: Context): PlaybackStore =
            instance ?: synchronized(this) {
                instance ?: PlaybackStore(context.applicationContext).also { instance = it }
            }
    }
}
