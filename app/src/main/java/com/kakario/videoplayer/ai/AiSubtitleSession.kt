package com.kakario.videoplayer.ai

import com.kakario.videoplayer.ftp.FtpStreamServer

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
    /** 영상 전체 자막을 다 만들었을 때 (파일로 저장). 저장했으면 true. 작업 스레드에서 불린다. */
    private val onComplete: suspend (List<AiCue>, String?) -> Boolean = { _, _ -> false },
    /** 미리 만들기(재생 없이): 처음부터 끝까지 만들고 파일로 저장한 뒤 끝낸다. */
    private val batch: Boolean = false,
) {
    /** 미리 만들기가 끝까지 완료됐는지. */
    @Volatile var completed = false
        private set
    /** 완성본을 파일로 저장했는지 (다시 저장하지 않게). */
    @Volatile private var exported = false

    /** 전체 중 만든 비율 (0~1). */
    val progress: Float
        get() {
            if (durationMs <= 0) return 0f
            val total = ((durationMs - 1) / CHUNK_MS + 1).toInt()
            return (done.size.toFloat() / total).coerceIn(0f, 1f)
        }
    private val appCtx = context.applicationContext
    private val cacheFile = File(File(appCtx.filesDir, "aisubs").apply { mkdirs() }, sha1(videoKey) + ".json")

    /** 시작 시간 순으로 정렬된 자막 (화면에서 읽음). */
    var cues by mutableStateOf<List<AiCue>>(emptyList())
        private set
    /** 화면 상태 안내 (null이면 조용). */
    var status by mutableStateOf<String?>(null)
        private set
    /** 지금 하는 일 (소리 받는 중 / 음성 인식 중 / 번역 중)과 시작 시각 — 화면에 경과 시간 표시. */
    var stage by mutableStateOf<String?>(null)
        private set
    var stageSince by mutableLongStateOf(0L)
        private set
    private fun stage(s: String?) {
        stage = s
        stageSince = System.currentTimeMillis()
    }
    /** 지금 만들고 있는 덩어리의 시작 위치(ms, 없으면 -1)와 만들기 시작한 시각. */
    var workStartMs by mutableLongStateOf(-1L)
        private set
    private var workSince = 0L

    /** 재생 화면이 일시정지 중인지 — 멈춰 있으면 지금 위치부터 먼저 만든다. */
    @Volatile var playerPaused = false

    /** 단계별 평균 걸린 시간 (따로 재야 남은 시간이 정확함). */
    @Volatile private var whisperMs = if (whisperModel.name.contains("small")) 10_000L else 30_000L
    @Volatile private var translateMs = 8_000L
    private val translateWanted get() = llmModel != null && language != "ko"

    /** 음성 인식까지 끝난 구간 (번역 전이어도). */
    private val heard: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 재생 막대 칠하기용: 번역까지 끝남 / 음성 인식만 됨 / 대사 없음. */
    data class ChunkMap(val translated: Set<Int>, val heard: Set<Int>, val silent: Set<Int>) {
        companion object { val EMPTY = ChunkMap(emptySet(), emptySet(), emptySet()) }
    }

    fun chunkMap(): ChunkMap {
        val byChunk = cues.groupBy { (it.startMs / CHUNK_MS).toInt() }
        val tr = HashSet<Int>()
        val hd = HashSet<Int>()
        val si = HashSet<Int>()
        for (i in done) {
            val list = byChunk[i]
            when {
                list.isNullOrEmpty() -> si += i
                !translateWanted || list.all { it.ko != null } -> tr += i
                else -> hd += i
            }
        }
        for (i in heard) if (i !in done && !byChunk[i].isNullOrEmpty()) hd += i
        return ChunkMap(tr, hd, si)
    }

    /** 번역까지 끝난 비율 (대사 없는 구간 포함) — 오른쪽 위 원형 표시. */
    fun translatedFraction(map: ChunkMap): Float {
        if (durationMs <= 0) return 0f
        val total = ((durationMs - 1) / CHUNK_MS + 1).toInt()
        return ((map.translated.size + map.silent.size).toFloat() / total).coerceIn(0f, 1f)
    }
    fun heardFraction(map: ChunkMap): Float {
        if (durationMs <= 0) return 0f
        val total = ((durationMs - 1) / CHUNK_MS + 1).toInt()
        return ((map.translated.size + map.silent.size + map.heard.size).toFloat() / total).coerceIn(0f, 1f)
    }

    /** 쓰는 음성 모델 이름 (small / turbo / large). */
    val whisperName: String = when {
        whisperModel.name.contains("small") -> "small"
        whisperModel.name.contains("turbo") -> "turbo"
        else -> "large"
    }

    /** 지금 구간에서 끝난 단계의 걸린 시간 (-1 = 아직). 오른쪽 위 3줄 표시용. */
    var curAudioMs by mutableLongStateOf(-1L)
        private set
    var curWhisperMs by mutableLongStateOf(-1L)
        private set
    /** 음성 인식 세부 (분석·글자 만들기 시간, 글자 수) — 테스트 표시용. */
    var curWhisperDetail by mutableStateOf("")
        private set

    /**
     * 오른쪽 위 3줄: 소리 받기 / 음성 인식 / 한글 번역 — 각 단계가 지금 어떤지.
     * 다 만들었거나 표시할 게 없으면 null.
     */
    fun stageLines(now: Long): List<Pair<String, String>>? {
        if (korean || failed != null) return null
        val warm = LlmHolder.warmRemainSec(now)
        if (progress >= 1f && warm == null) return null
        val st = stage
        val el = ((now - stageSince) / 1000).coerceAtLeast(0)
        fun secs(ms: Long) = String.format("%.1f초", ms / 1000.0)
        val audio = when {
            st == "영상 여는 중" -> "영상 여는 중 ${el}초"
            st == "소리 받는 중" -> "받는 중 ${el}초"
            curAudioMs >= 0 -> "완료 ${secs(curAudioMs)}"
            else -> "대기"
        }
        val whisper = when {
            st == "음성 엔진 여는 중" -> "엔진 여는 중 ${el}초"
            st == "음성 인식 차례 기다림" -> "번역 엔진 계산 끝나길 기다리는 중 ${el}초"
            st == "음성 인식 중" -> {
                val left = ((whisperMs - (now - stageSince)) / 1000).coerceAtLeast(1)
                "진행 중 ${el}초 · 약 ${left}초 남음"
            }
            curWhisperMs >= 0 -> "완료 ${secs(curWhisperMs)}$curWhisperDetail"
            else -> "대기"
        }
        val lines = mutableListOf("소리 받기" to audio, "음성 인식 ($whisperName)" to whisper)
        if (llmModel != null && language != "ko") {
            val pct = "전체 ${(progress * 100).toInt()}%"
            val tr = when {
                warm != null -> "엔진 준비 중 · 약 ${warm}초 남음"
                st == "번역 중" -> "진행 중 ${el}초 · $pct"
                st == "음성 인식 중" -> "약 ${((whisperMs - (now - stageSince)) / 1000).coerceAtLeast(1)}초 뒤 시작 · $pct"
                st == "소리 받는 중" -> "대기 · $pct"
                else -> pct
            }
            lines += "한글 번역" to tr
        }
        // 테스트용: 직전 구간에서 단계별로 걸린 시간 (인식은 분석·글자로 나눠서)
        timings.firstOrNull()?.let { lines += "직전 구간" to it }
        // 테스트용: FTP 영상이면 중계 접속 기록 한 줄 더
        if (isUrl) {
            FtpStreamServer.statLine("AI")?.let { lines += "FTP (AI)" to it }
            FtpStreamServer.statLine("재생")?.let { lines += "FTP (재생)" to it }
        }
        return lines
    }

    /** 최근 구간별 걸린 시간 (자막 메뉴에서 확인). 최신이 앞. */
    var timings by mutableStateOf<List<String>>(emptyList())
        private set
    private fun addTiming(line: String) {
        timings = (listOf(line) + timings).take(6)
    }
    val chunkMs get() = CHUNK_MS

    /** 지금 하는 덩어리가 끝날 때까지 남은 ms (대략, 넉넉히). */
    private fun workRemainMs(now: Long): Long {
        val elapsed = now - stageSince
        return when (stage) {
            "소리 받는 중" -> whisperMs + (if (translateWanted) translateMs else 0)
            "음성 인식 중" -> (whisperMs - elapsed).coerceAtLeast(1_000) + (if (translateWanted) translateMs else 0)
            "번역 중" -> (translateMs - elapsed).coerceAtLeast(1_000)
            else -> whisperMs + (if (translateWanted) translateMs else 0)
        }
    }

    /**
     * 오른쪽 위 안내. 예) "번역 12% · 음성 인식 중 · 약 8초 뒤 번역 시작", "번역 12% · 번역 중".
     * 다 만들었으면 null.
     */
    fun topLabel(now: Long): String? {
        if (korean || failed != null) return null
        LlmHolder.warmRemainSec(now)?.let { return "번역 엔진 준비 중 · 약 ${it}초 남음" }
        if (progress >= 1f) return null
        val head = whisperName
        return when (stage) {
            "소리 받는 중", "음성 인식 중" -> {
                if (translateWanted) {
                    val left = (((whisperMs - (now - stageSince)).coerceAtLeast(1_000) + 999) / 1000)
                    "$head · 음성 인식 중 · 약 ${left}초 뒤 번역 시작"
                } else "$head · 음성 인식 중"
            }
            "번역 중" -> "$head · 번역 중"
            else -> if (status != null) "$head · 준비 중" else head
        }
    }

    /**
     * 지금 위치에 자막이 아직 없을 때 띄울 일시정지 안내. 있으면 null.
     * 재생 중: "처음부터 자막을 원하시면 약 40초간 일시정지해 주세요"
     * 멈춤 중: "약 25초 후 첫 번역 자막이 나와요" / 준비되면 "번역 자막 준비됐어요 · 재생해 주세요"
     */
    fun pauseHint(ph: Long, now: Long, paused: Boolean): String? {
        if (korean || failed != null || durationMs <= 0) return null
        val chunk = (ph / CHUNK_MS).toInt()
        val word = if (translateWanted) "번역 자막" else "AI 자막"
        if (chunk in done) {
            if (!paused) hintShown = false
            return if (paused && hintShown) "$word 준비됐어요 · 재생해 주세요" else null
        }
        val warm = (LlmHolder.warmRemainSec(now) ?: 0) * 1000
        val ws = workStartMs
        val full = whisperMs + (if (translateWanted) translateMs else 0)
        // 지금 위치 덩어리를 만드는 중이면 남은 만큼, 아니면 하던 것 끝내고 한 덩어리 더
        val wait = when {
            ws >= 0 && (ws / CHUNK_MS).toInt() == chunk -> workRemainMs(now)
            ws >= 0 -> workRemainMs(now) + full
            else -> full
        } + warm + 2_000 // 넉넉히
        val sec = (wait + 999) / 1000
        hintShown = true
        return if (paused) "약 ${sec}초 후 첫 ${word}이 나와요"
        else "처음부터 자막을 원하시면 약 ${sec}초간 일시정지해 주세요"
    }
    private var hintShown = false

    /** 지금 위치부터 끊김 없이 자막이 준비된 지점(ms). */
    var readyUntilMs by mutableLongStateOf(0L)
        private set
    /** 감지한 말소리 언어 (ko, ja, zh, en …). */
    var language by mutableStateOf<String?>(null)
    /** 잠깐 띄웠다 사라지는 안내 (예: 한국어 영상이라 안 만듦). */
    var notice by mutableStateOf<String?>(null)
    /** 한국어 영상으로 판단해 자막을 만들지 않기로 했는지. */
    var korean by mutableStateOf(false)
        private set
    private val koreanMark = File(File(appCtx.filesDir, "aisubs"), sha1(videoKey) + ".ko")
    /** 처음 한국어로 감지된 덩어리 (다른 덩어리에서 한 번 더 한국어면 한국어 영상). */
    private var firstKoreanChunk = -1
        private set
    var failed by mutableStateOf<String?>(null)
        private set

    /** 30초 덩어리 하나를 인식·번역하는 데 걸리는 시간(ms, 평균). 처음엔 넉넉히 잡는다. */
    @Volatile private var procMs = if (whisperModel.name.contains("small")) 15_000L else 40_000L

    private val done: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private var durationMs = 0L
    private var job: Job? = null
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "ai-subtitles").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val dispatcher = worker.asCoroutineDispatcher()
    @Volatile private var whisperHandle = 0L
    @Volatile private var llmHandle = 0L

    /** 끝날 때까지 기다리기 (미리 만들기용). */
    suspend fun join() {
        job?.join()
    }

    fun start(scope: CoroutineScope, playhead: () -> Long) {
        if (job?.isActive == true) return
        job = scope.launch(dispatcher) {
            var chunker: AudioSource? = null
            try {
                if (koreanMark.exists()) {
                    korean = true
                    notice = "한국어 영상이라 AI 자막을 만들지 않아요"
                    return@launch
                }
                loadCache()
                if (!WhisperNative.available) {
                    failed = "AI 엔진을 불러오지 못했어요"
                    return@launch
                }
                status = "AI 자막 엔진 준비 중…"
                stage("음성 엔진 여는 중")
                whisperHandle = WhisperNative.init(whisperModel.path)
                if (whisperHandle == 0L) {
                    failed = "음성인식 모델을 열지 못했어요. 설정에서 다시 받아 주세요"
                    return@launch
                }
                status = "영상 소리 여는 중…"
                stage("영상 여는 중")
                chunker = try {
                    AudioSource.open(source, isUrl).also { stage(null) }
                } catch (e: Exception) {
                    failed = "이 영상에서 소리를 꺼내지 못했어요"
                    return@launch
                }
                durationMs = chunker.durationMs
                val threads = AiLibs.bigCores

                while (isActive) {
                    val ph = playhead()
                    updateReady(ph)
                    val first = (ph / CHUNK_MS).toInt()
                    val last = if (durationMs > 0) ((durationMs - 1) / CHUNK_MS).toInt() else first + LOOKAHEAD
                    // 지금 보는 위치부터 끝까지 먼저, 그다음 앞쪽 빈 곳 (영상 전체를 다 만든다).
                    // 한 덩어리 처리에 걸리는 시간만큼 앞을 내다봐서, 결과가 나왔을 때 아직 안 지나간 구간을 고른다
                    // (처리가 재생보다 느려도 자막이 화면에 제때 뜨도록).
                    val readyAt = if (batch || playerPaused) 0L else ph + procMs + 1_500
                    val next = (first..last).firstOrNull { it !in done && (it + 1) * CHUNK_MS > readyAt }
                        ?: (first..last).firstOrNull { it !in done }
                        ?: (0 until minOf(first, last + 1)).firstOrNull { it !in done }
                    if (next == null) {
                        status = null
                        // 다 만들었으면 자막 파일로 저장 (한 번만)
                        if (durationMs > 0 && !exported) {
                            val list = cues
                            val ok = runCatching { onComplete(list, language) }.getOrDefault(false)
                            if (ok) {
                                exported = true
                                saveCache()
                            }
                        }
                        if (batch) {
                            completed = true
                            return@launch
                        }
                        delay(1000)
                        continue
                    }
                    val startMs = next * CHUNK_MS
                    val chunkStartedAt = System.currentTimeMillis()
                    workSince = chunkStartedAt
                    withContext(Dispatchers.Main) { workStartMs = startMs }
                    status = if (startMs <= ph + 2_000) "AI 자막 만드는 중…"
                    else "AI 자막 ${mmss(startMs)}부터 준비 중"
                    withContext(Dispatchers.Main) {
                        curAudioMs = -1L
                        curWhisperMs = -1L
                        curWhisperDetail = ""
                    }
                    stage("소리 받는 중")
                    val aT0 = System.currentTimeMillis()
                    val pcm = chunker.read(startMs, CHUNK_MS)
                    val audioTook = System.currentTimeMillis() - aT0
                    withContext(Dispatchers.Main) { curAudioMs = audioTook }
                    if (pcm == null || rms(pcm) < SILENCE) {
                        addTiming("${mmss(startMs)} · 소리 ${sec1(audioTook)} · 대사 없음")
                        done += next
                        saveCache()
                        continue
                    }
                    // 번역 엔진(예열 등)이 CPU를 쓰는 중이면 끝날 때까지 기다렸다가 (동시에 돌면 둘 다 몇 배 느려짐)
                    if (!AiLibs.compute.tryLock()) {
                        stage("음성 인식 차례 기다림")
                        AiLibs.compute.lock()
                    }
                    stage("음성 인식 중")
                    val wT0 = System.currentTimeMillis()
                    val raw = try {
                        WhisperNative.transcribe(whisperHandle, pcm, language ?: "auto", threads, false, !batch)
                    } finally {
                        AiLibs.compute.unlock()
                    }
                    val whisperTook = System.currentTimeMillis() - wT0
                    withContext(Dispatchers.Main) { curWhisperMs = whisperTook }
                    whisperMs = (whisperMs * 0.6 + whisperTook * 0.4).toLong()
                    stage(null)
                    if (!isActive) return@launch
                    if (raw == null) {
                        done += next
                        continue
                    }
                    val lines = String(raw, Charsets.UTF_8).split('\n')
                    val head = lines.firstOrNull().orEmpty().split('\t')
                    val detected = head.getOrNull(0)?.takeIf { it.isNotBlank() }
                    val encMs = head.getOrNull(1)?.toLongOrNull() ?: -1
                    val decMs = head.getOrNull(2)?.toLongOrNull() ?: -1
                    val steps = head.getOrNull(3)?.toLongOrNull() ?: -1
                    val whisperDetail = if (encMs >= 0) " (분석 ${sec1(encMs)} · 글자 ${sec1(decMs)}, ${steps}개)" else ""
                    withContext(Dispatchers.Main) { curWhisperDetail = whisperDetail }
                    val segs = lines.drop(1).mapNotNull { line ->
                        val p = line.split('\t', limit = 3)
                        if (p.size < 3) return@mapNotNull null
                        val t0 = p[0].toLongOrNull() ?: return@mapNotNull null
                        val t1 = p[1].toLongOrNull() ?: return@mapNotNull null
                        val text = p[2].trim()
                        if (text.isEmpty() || isHallucination(text)) return@mapNotNull null
                        AiCue(startMs + t0, startMs + minOf(t1, CHUNK_MS).coerceAtLeast(t0 + 500), text, null)
                    }
                    // 언어 판단: 말소리가 있는 덩어리에서만. 서로 다른 두 덩어리가 한국어면 한국어 영상 → 자막 안 만듦
                    if (language == null && segs.isNotEmpty() && detected != null) {
                        if (detected == "ko") {
                            if (firstKoreanChunk >= 0 && firstKoreanChunk != next) {
                                korean = true
                                status = null
                                notice = "한국어 영상이라 AI 자막을 만들지 않아요"
                                runCatching { koreanMark.writeText("ko") }
                                runCatching { cacheFile.delete() }
                                withContext(Dispatchers.Main) { cues = emptyList() }
                                return@launch
                            }
                            firstKoreanChunk = next
                            done += next
                            continue
                        } else {
                            language = detected
                            // 앞에서 한국어로 잘못 들은 덩어리는 다시 만들기
                            if (firstKoreanChunk >= 0) done -= firstKoreanChunk
                        }
                    }
                    if (language == null) {
                        // 아직 말소리가 없음 (음악·효과음 등)
                        done += next
                        continue
                    }
                    heard += next
                    // 인식 결과(원어)를 먼저 바로 띄우고, 번역은 끝나는 대로 바꿔 끼운다
                    if (segs.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            cues = (cues.filter { it.startMs < startMs || it.startMs >= startMs + CHUNK_MS } + segs)
                                .sortedBy { it.startMs }
                        }
                    }
                    var translateTook = -1L
                    val translated = if (llmModel != null && language != null && language != "ko" && segs.isNotEmpty()) {
                        stage("번역 중")
                        val tT0 = System.currentTimeMillis()
                        val wasOpen = LlmHolder.isOpen
                        translate(segs).also {
                            stage(null)
                            // 예열 시간이 섞인 첫 번역은 빼고 평균
                            translateTook = System.currentTimeMillis() - tT0
                            if (wasOpen) translateMs = (translateMs * 0.6 + translateTook * 0.4).toLong()
                        }
                    } else segs
                    if (!isActive) return@launch
                    withContext(Dispatchers.Main) {
                        cues = (cues.filter { it.startMs < startMs || it.startMs >= startMs + CHUNK_MS } + translated)
                            .sortedBy { it.startMs }
                    }
                    done += next
                    addTiming(
                        "${mmss(startMs)} · 소리 ${sec1(audioTook)} · 인식 ${sec1(whisperTook)}$whisperDetail" +
                            (if (translateTook >= 0) " · 번역 ${sec1(translateTook)} (${segs.size}줄)" else "") +
                            " · 합계 ${sec1(System.currentTimeMillis() - chunkStartedAt)}"
                    )
                    // 한 덩어리 처리 시간(평균) 갱신 — 다음에 얼마나 앞을 고를지 정하는 데 씀
                    val took = System.currentTimeMillis() - chunkStartedAt
                    procMs = (procMs * 0.6 + took * 0.4).toLong().coerceIn(5_000, 180_000)
                    withContext(Dispatchers.Main) { workStartMs = -1L }
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
                llmHandle = 0 // 번역 모델은 LlmHolder가 들고 있다가 안 쓰면 알아서 내려놓음
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

    /** 번역 상태 (자막 메뉴에서 확인용). */
    var translateInfo by mutableStateOf<String?>(null)
        private set
    @Volatile private var translateBroken = false

    private fun translate(segs: List<AiCue>): List<AiCue> {
        if (llmModel == null) return segs
        if (!LlamaNative.available) {
            translateInfo = "번역 엔진을 불러오지 못했어요"
            return segs
        }
        if (translateBroken) return segs
        val out = ArrayList<AiCue>(segs.size)
        LlmHolder.use(llmModel.path, onOpening = { status = "번역 모델 준비 중…" }) { h ->
            if (h == 0L) {
                translateBroken = true
                translateInfo = "번역 모델을 열지 못했어요 (파일이 깨졌거나 메모리 부족) — 원어 자막만 보여요"
                notice = "번역 모델을 열지 못했어요 — 원어 자막만 보여요"
                out += segs
                return@use
            }
            llmHandle = h
            try {
                segs.chunked(8).forEach { group ->
                    val context = cues.filter { it.startMs < group.first().startMs }.takeLast(3).map { it.text }
                    val t0 = System.currentTimeMillis()
                    val r = AiTranslator.translate(h, group.map { it.text }, context, language)
                    translateInfo = "${r.info} · ${group.size}줄 ${(System.currentTimeMillis() - t0) / 1000}초"
                    group.forEachIndexed { i, c -> out += c.copy(ko = r.ko.getOrNull(i)) }
                }
            } finally {
                llmHandle = 0
            }
        }
        if (translateBroken) return segs
        if (out.none { it.ko != null }) notice = "번역이 안 됐어요: $translateInfo"
        return out
    }

    // ───────────────────────────── 기타

    private fun sec1(ms: Long) = String.format("%.1f초", ms / 1000.0)

    private fun mmss(ms: Long): String {
        val t = ms / 1000
        return if (t >= 3600) String.format("%d:%02d:%02d", t / 3600, (t % 3600) / 60, t % 60)
        else String.format("%d:%02d", t / 60, t % 60)
    }

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
            exported = o.optBoolean("exported", false)
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
            o.put("exported", exported)
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

/** AI 자막을 SRT 자막 파일 내용(UTF-8)으로. mode에 따라 번역/원어/두 줄. */
fun aiCuesToSrt(cues: List<AiCue>, mode: AiShowMode): ByteArray {
    fun ts(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms / 60_000) % 60
        val s = (ms / 1000) % 60
        val x = ms % 1000
        return String.format("%02d:%02d:%02d,%03d", h, m, s, x)
    }
    val sb = StringBuilder()
    var n = 1
    cues.sortedBy { it.startMs }.forEach { c ->
        val text = when (mode) {
            AiShowMode.ORIGINAL -> c.text
            AiShowMode.KOREAN -> c.ko ?: c.text
            AiShowMode.BOTH -> if (c.ko != null) c.ko + "\n" + c.text else c.text
        }
        if (text.isBlank()) return@forEach
        sb.append(n++).append("\r\n")
            .append(ts(c.startMs)).append(" --> ").append(ts(c.endMs)).append("\r\n")
            .append(text.replace("\n", "\r\n")).append("\r\n\r\n")
    }
    return byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + sb.toString().toByteArray(Charsets.UTF_8)
}
