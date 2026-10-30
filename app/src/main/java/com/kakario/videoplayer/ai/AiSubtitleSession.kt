package com.kakario.videoplayer.ai

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** AI 자막 한 줄. ko는 한국어 번역(원래 한국어거나 아직 번역 전이면 null). */
data class AiCue(val startMs: Long, val endMs: Long, val text: String, val ko: String?)

/** AI 자막을 어떻게 보여줄지. */
enum class AiShowMode(val label: String) {
    ORIGINAL("원래 언어 그대로"),
    KOREAN("한국어 번역만"),
    BOTH("한국어 번역 + 원래 언어 (두 줄)"),
}

/**
 * 재생 중인 영상 하나의 AI 자막 만들기.
 * - 소리를 30초씩 잘라 Whisper로 인식하고, 필요하면 Qwen3로 한국어 번역.
 * - 지금 보는 위치부터 앞쪽으로 최대 10분까지 미리 만들어 둔다. 뒤로 건너뛰면 그 위치부터 다시.
 * - 만든 자막은 저장해서 다음에 열면 바로 보인다.
 * - 모든 처리는 폰 안에서만. 네트워크를 쓰지 않는다 (FTP 영상의 소리를 받는 것 제외).
 */
class AiSubtitleSession(
    context: Context,
    private val videoKey: String,
    private val source: String,
    private val isUrl: Boolean,
    private val whisperModel: File,
    /** null이면 번역하지 않음. */
    private val llmModel: File?,
) {
    private val appCtx = context.applicationContext
    private val cacheFile = File(File(appCtx.filesDir, "aisubs").apply { mkdirs() }, sha1(videoKey) + ".json")

    /** 시작 시간 순으로 정렬된 자막 (화면에서 읽음). */
    var cues by mutableStateOf<List<AiCue>>(emptyList())
        private set
    /** 화면 상태 안내 (null이면 조용). */
    var status by mutableStateOf<String?>(null)
        private set
    /** 지금 위치부터 끊김 없이 자막이 준비된 지점(ms). */
    var readyUntilMs by mutableLongStateOf(0L)
        private set
    /** 감지한 말소리 언어 (ko, ja, zh, en …). */
    var language by mutableStateOf<String?>(null)
        private set
    var failed by mutableStateOf<String?>(null)
        private set

    private val done = HashSet<Int>()
    private var durationMs = 0L
    private var job: Job? = null
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "ai-subtitles").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val dispatcher = worker.asCoroutineDispatcher()
    @Volatile private var whisperHandle = 0L
    @Volatile private var llmHandle = 0L

    fun start(scope: CoroutineScope, playhead: () -> Long) {
        if (job?.isActive == true) return
        job = scope.launch(dispatcher) {
            var chunker: AudioChunker? = null
            try {
                loadCache()
                if (!WhisperNative.available) {
                    failed = "AI 엔진을 불러오지 못했어요"
                    return@launch
                }
                status = "AI 자막 엔진 준비 중…"
                whisperHandle = WhisperNative.init(whisperModel.path)
                if (whisperHandle == 0L) {
                    failed = "음성인식 모델을 열지 못했어요. 설정에서 다시 받아 주세요"
                    return@launch
                }
                chunker = try {
                    AudioChunker(source, isUrl)
                } catch (e: Exception) {
                    failed = "이 영상은 소리를 꺼낼 수 없어요 (MP4·MKV·TS·WEBM 지원)"
                    return@launch
                }
                durationMs = chunker.durationMs
                val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)

                while (isActive) {
                    val ph = playhead()
                    updateReady(ph)
                    val first = (ph / CHUNK_MS).toInt()
                    val last = if (durationMs > 0) ((durationMs - 1) / CHUNK_MS).toInt() else first + LOOKAHEAD
                    val next = (first..minOf(first + LOOKAHEAD, last)).firstOrNull { it !in done }
                    if (next == null) {
                        status = null
                        delay(1000)
                        continue
                    }
                    val startMs = next * CHUNK_MS
                    status = if (startMs <= ph + 2_000) "AI 자막 만드는 중…" else null
                    val pcm = chunker.read(startMs, CHUNK_MS)
                    if (pcm == null || rms(pcm) < SILENCE) {
                        done += next
                        saveCache()
                        continue
                    }
                    val raw = WhisperNative.transcribe(whisperHandle, pcm, language ?: "auto", threads, false)
                    if (!isActive) return@launch
                    if (raw == null) {
                        done += next
                        continue
                    }
                    val lines = String(raw, Charsets.UTF_8).split('\n')
                    if (language == null) language = lines.firstOrNull()?.takeIf { it.isNotBlank() }
                    val segs = lines.drop(1).mapNotNull { line ->
                        val p = line.split('\t', limit = 3)
                        if (p.size < 3) return@mapNotNull null
                        val t0 = p[0].toLongOrNull() ?: return@mapNotNull null
                        val t1 = p[1].toLongOrNull() ?: return@mapNotNull null
                        val text = p[2].trim()
                        if (text.isEmpty() || isHallucination(text)) return@mapNotNull null
                        AiCue(startMs + t0, startMs + minOf(t1, CHUNK_MS).coerceAtLeast(t0 + 500), text, null)
                    }
                    val translated = if (llmModel != null && language != null && language != "ko" && segs.isNotEmpty()) {
                        translate(segs)
                    } else segs
                    if (!isActive) return@launch
                    withContext(Dispatchers.Main) {
                        cues = (cues.filter { it.startMs < startMs || it.startMs >= startMs + CHUNK_MS } + translated)
                            .sortedBy { it.startMs }
                    }
                    done += next
                    updateReady(playhead())
                    saveCache()
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failed = "AI 자막 중 문제가 생겼어요: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                status = null
                runCatching { chunker?.close() }
                if (whisperHandle != 0L) WhisperNative.free(whisperHandle)
                whisperHandle = 0
                if (llmHandle != 0L) LlamaNative.free(llmHandle)
                llmHandle = 0
                worker.shutdown()
            }
        }
    }

    /** 멈추기 (재생 화면을 닫을 때). 진행 중인 인식·번역도 바로 중단시킨다. */
    fun stop() {
        whisperHandle.takeIf { it != 0L }?.let { WhisperNative.abort(it) }
        llmHandle.takeIf { it != 0L }?.let { LlamaNative.abort(it) }
        job?.cancel()
    }

    /** 지금 시간에 보여줄 자막. */
    fun cueAt(ms: Long): AiCue? {
        val list = cues
        for (i in list.indices.reversed()) {
            val c = list[i]
            if (c.startMs <= ms) return if (ms < c.endMs) c else null
        }
        return null
    }

    // ───────────────────────────── 번역

    private fun translate(segs: List<AiCue>): List<AiCue> {
        if (llmModel == null || !LlamaNative.available) return segs
        if (llmHandle == 0L) {
            status = "번역 모델 준비 중…"
            val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
            llmHandle = LlamaNative.init(llmModel.path, 4096, threads)
            if (llmHandle == 0L) return segs
        }
        val out = ArrayList<AiCue>(segs.size)
        segs.chunked(10).forEach { group ->
            val context = (cues.filter { it.startMs < group.first().startMs }.takeLast(4).map { it.text })
            val ko = translateLines(group.map { it.text }, context)
            group.forEachIndexed { i, c -> out += c.copy(ko = ko.getOrNull(i)) }
        }
        return out
    }

    private fun translateLines(lines: List<String>, context: List<String>): List<String?> {
        val langName = when (language) {
            "ja" -> "일본어"
            "zh" -> "중국어"
            "en" -> "영어"
            else -> "외국어"
        }
        val sys = "너는 영상 자막 전문 번역가야. 번호가 붙은 각 줄을 자연스러운 한국어 구어체 자막으로 번역해. " +
            "번호와 줄 수를 그대로 지키고, 번역한 줄만 출력해. 설명이나 원문은 쓰지 마."
        val user = buildString {
            if (context.isNotEmpty()) {
                append("앞 대사 (참고만 하고 번역하지 마):\n")
                context.forEach { append(it).append('\n') }
                append('\n')
            }
            append("번역할 $langName 대사:\n")
            lines.forEachIndexed { i, l -> append(i + 1).append(". ").append(l).append('\n') }
            append("/no_think")
        }
        val maxTokens = (lines.sumOf { it.length } * 3 + 64).coerceIn(128, 1200)
        val raw = LlamaNative.generate(
            llmHandle,
            sys.toByteArray(),
            user.toByteArray(),
            "<think>\n\n</think>\n\n".toByteArray(), // 생각 모드 끄기 (바로 답)
            maxTokens,
        ) ?: return List(lines.size) { null }
        val text = String(raw, Charsets.UTF_8).replace(Regex("(?s)<think>.*?</think>"), "")
        val map = HashMap<Int, String>()
        val re = Regex("""^\s*(\d+)\s*[.)：:]\s*(.+)$""")
        text.lines().forEach { l ->
            re.find(l)?.let { m ->
                val n = m.groupValues[1].toIntOrNull()
                if (n != null) map[n] = m.groupValues[2].trim()
            }
        }
        if (map.isEmpty() && lines.size == 1 && text.isNotBlank()) return listOf(text.trim())
        return List(lines.size) { map[it + 1] }
    }

    // ───────────────────────────── 기타

    private fun updateReady(ph: Long) {
        var idx = (ph / CHUNK_MS).toInt()
        while (idx in done) idx++
        readyUntilMs = idx * CHUNK_MS
    }

    private fun rms(a: FloatArray): Float {
        var s = 0.0
        for (v in a) s += v * v
        return kotlin.math.sqrt(s / a.size.coerceAtLeast(1)).toFloat()
    }

    /** 조용한 구간에서 Whisper가 지어내는 흔한 문구 거르기. */
    private fun isHallucination(t: String): Boolean {
        val s = t.trim().trim('.', '。', '!', '！', ' ', '"', '\'').lowercase()
        if (s.length <= 1) return true
        return HALLUCINATIONS.any { s == it || s.startsWith(it) }
    }

    private fun loadCache() {
        if (!cacheFile.exists()) return
        runCatching {
            val o = JSONObject(cacheFile.readText())
            language = o.optString("lang").ifBlank { null }
            val d = o.optJSONArray("done") ?: JSONArray()
            for (i in 0 until d.length()) done += d.getInt(i)
            val arr = o.optJSONArray("cues") ?: JSONArray()
            val list = ArrayList<AiCue>()
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                list += AiCue(c.getLong("s"), c.getLong("e"), c.getString("t"), if (c.isNull("k")) null else c.getString("k"))
            }
            val translateWanted = llmModel != null && language != null && language != "ko"
            if (translateWanted && list.any { it.ko == null }) {
                // 예전에 번역 없이 만든 부분은 다시 만들도록 표시 해제
                list.filter { it.ko == null }.forEach { done -= (it.startMs / CHUNK_MS).toInt() }
            }
            cues = list.sortedBy { it.startMs }
        }
    }

    private fun saveCache() {
        runCatching {
            val o = JSONObject()
            o.put("lang", language ?: "")
            o.put("done", JSONArray(done.sorted()))
            val arr = JSONArray()
            cues.forEach { c ->
                arr.put(JSONObject().put("s", c.startMs).put("e", c.endMs).put("t", c.text).put("k", c.ko ?: JSONObject.NULL))
            }
            o.put("cues", arr)
            cacheFile.writeText(o.toString())
        }
    }

    companion object {
        const val CHUNK_MS = 30_000L
        /** 지금 위치에서 몇 덩어리(30초)까지 미리 만들지 = 10분. */
        private const val LOOKAHEAD = 20
        private const val SILENCE = 0.004f
        private val HALLUCINATIONS = listOf(
            "시청해주셔서 감사합니다", "시청해 주셔서 감사합니다", "구독과 좋아요", "mbc 뉴스", "자막 제공",
            "thank you for watching", "thanks for watching", "please subscribe", "subtitles by",
            "ご視聴ありがとうございました", "チャンネル登録", "字幕", "请不吝点赞", "谢谢观看", "明镜与点点栏目",
        )

        fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        /** 저장해둔 AI 자막 모두 지우기 (설정). */
        fun clearAll(context: Context) {
            File(context.filesDir, "aisubs").listFiles()?.forEach { it.delete() }
        }
    }
}
