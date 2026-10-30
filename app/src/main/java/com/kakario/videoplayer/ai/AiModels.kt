package com.kakario.videoplayer.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File

/** 받을 수 있는 AI 모델. 받은 뒤에는 폰 안에서만 쓰인다. */
enum class AiModel(
    val title: String,
    val desc: String,
    val fileName: String,
    val url: String,
    val approxBytes: Long,
    val isWhisper: Boolean,
) {
    WHISPER_SMALL(
        "빠름 (Whisper small)", "가볍고 빠르지만 정확도는 보통",
        "ggml-small-q5_1.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
        190_000_000L, true,
    ),
    WHISPER_TURBO(
        "추천 (Whisper large-v3-turbo)", "정확도 높고 속도도 쓸 만함",
        "ggml-large-v3-turbo-q5_0.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin",
        574_000_000L, true,
    ),
    WHISPER_LARGE(
        "최고 정확도 (Whisper large-v3)", "가장 정확하지만 많이 느림 — 미리 만들어 둘 때용",
        "ggml-large-v3-q5_0.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-q5_0.bin",
        1_080_000_000L, true,
    ),
    QWEN3_4B(
        "한국어 번역 (Qwen3 4B)", "중국어·영어·일본어 등 → 자연스러운 한국어",
        "Qwen3-4B-Q4_K_M.gguf",
        "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf",
        2_500_000_000L, false,
    ),
}

/** 다운로드 상태. */
sealed interface ModelState {
    data object None : ModelState
    data class Downloading(val done: Long, val total: Long, val waitingWifi: Boolean) : ModelState
    data object Ready : ModelState
    data class Failed(val reason: String) : ModelState
}

/**
 * 모델 받기·지우기. 안드로이드 다운로드 관리자를 써서 앱을 내려도 계속 받고,
 * 와이파이에서만 받는다 (용량이 커서). 받은 파일은 앱 전용 폴더(models)에 둔다.
 */
class ModelManager(context: Context) {
    private val ctx = context.applicationContext
    private val dm = ctx.getSystemService(DownloadManager::class.java)
    private val prefs = ctx.getSharedPreferences("ai_models", Context.MODE_PRIVATE)
    private val dir: File get() = File(ctx.getExternalFilesDir(null), "models").apply { mkdirs() }

    fun file(m: AiModel) = File(dir, m.fileName)
    private fun partFile(m: AiModel) = File(dir, m.fileName + ".part")

    fun isReady(m: AiModel): Boolean = state(m) == ModelState.Ready

    fun state(m: AiModel): ModelState = try {
        stateUnsafe(m)
    } catch (e: Exception) {
        ModelState.Failed("상태를 읽지 못했어요: ${e.message ?: e.javaClass.simpleName}")
    }

    private fun stateUnsafe(m: AiModel): ModelState {
        val id = prefs.getLong(m.name, -1L)
        if (id >= 0) {
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (c.moveToFirst()) {
                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    return when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            val part = partFile(m)
                            if (part.exists()) part.renameTo(file(m))
                            prefs.edit().remove(m.name).apply()
                            if (file(m).exists()) ModelState.Ready else ModelState.Failed("파일을 찾지 못했어요")
                        }
                        DownloadManager.STATUS_FAILED -> {
                            prefs.edit().remove(m.name).apply()
                            partFile(m).delete()
                            ModelState.Failed("받기 실패 (코드 $reason) — 다시 받아 주세요")
                        }
                        else -> ModelState.Downloading(
                            done, if (total > 0) total else m.approxBytes,
                            waitingWifi = status == DownloadManager.STATUS_PAUSED &&
                                (reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI || reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK),
                        )
                    }
                }
            }
            // 기록은 있는데 다운로드 관리자에서 사라짐
            prefs.edit().remove(m.name).apply()
        }
        return if (file(m).exists() && file(m).length() > 1_000_000) ModelState.Ready else ModelState.None
    }

    /** 지금 모바일 데이터(LTE·5G)로 연결돼 있는지 (와이파이가 아니면 true). */
    fun onMobileData(): Boolean = try {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps != null &&
            !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) &&
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
    } catch (_: Exception) {
        false
    }

    /** allowMobile = 모바일 데이터로도 받기 (사용자가 확인했을 때만). */
    /** 받기 시작. 실패하면 이유(한국어)를 돌려준다, 성공하면 null. */
    fun download(m: AiModel, allowMobile: Boolean = false): String? = try {
        startDownload(m, allowMobile)
        null
    } catch (e: Exception) {
        "받기를 시작하지 못했어요: ${e.message ?: e.javaClass.simpleName}"
    }

    private fun startDownload(m: AiModel, allowMobile: Boolean) {
        if (state(m) is ModelState.Downloading) return
        dir.mkdirs()
        partFile(m).delete()
        val req = DownloadManager.Request(Uri.parse(m.url))
            .setTitle("AI 모델 받는 중: ${m.title}")
            .setDescription("받은 뒤에는 폰 안에서만 쓰여요")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(allowMobile) // 기본은 와이파이에서만, 확인하면 데이터로도
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(ctx, null, "models/" + m.fileName + ".part")
        val id = dm.enqueue(req)
        prefs.edit().putLong(m.name, id).apply()
    }

    fun cancel(m: AiModel) {
        val id = prefs.getLong(m.name, -1L)
        if (id >= 0) runCatching { dm.remove(id) }
        prefs.edit().remove(m.name).apply()
        partFile(m).delete()
    }

    fun delete(m: AiModel) {
        cancel(m)
        file(m).delete()
    }
}
