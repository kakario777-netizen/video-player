package com.kakario.videoplayer.ai

import android.content.Context
import com.kakario.videoplayer.data.PlaybackStore
import com.kakario.videoplayer.data.SettingsStore
import com.kakario.videoplayer.ftp.FtpBrowser
import com.kakario.videoplayer.ftp.FtpStreamServer
import com.kakario.videoplayer.local.LocalStorage
import com.kakario.videoplayer.model.FileEntry
import com.kakario.videoplayer.model.MediaOrigin
import com.kakario.videoplayer.model.Subtitles
import com.kakario.videoplayer.model.parentPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 미리 만들기 진행 상황 (null이면 쉬는 중). */
data class AiBatchStatus(
    /** 지금 만드는 영상 이름 (폴더를 살피는 중이면 폴더 이름). */
    val current: String,
    /** 지금 영상 진행률 0~100. */
    val percent: Int,
    /** 기다리는 영상 수. */
    val waiting: Int,
    /** 지금까지 다 만든 수 / 건너뛴 수. */
    val doneCount: Int,
    val skipCount: Int,
    val paused: Boolean,
    /** 지금 하는 일 (예: "1:30 구간 · 음성 인식 중 2분 10초째"). */
    val detail: String = "",
)

/**
 * AI 자막 미리 만들기 대기줄 (코믹뷰어 썸네일 갱신처럼).
 * - 파일이나 폴더를 길게 눌러 넣으면 차례로 처음부터 끝까지 만들고 영상 옆에 .ai.srt로 저장.
 * - 한국어 영상, 이미 자막(파일·AI)이 있는 영상은 건너뛴다.
 * - 영상을 보는 동안에는 잠시 멈춘다 (재생 쪽 AI 자막·영상에 성능 양보).
 * - 화면이 꺼져도 계속되도록 AiBatchService(알림)가 함께 돈다.
 */
object AiBatch {
    private sealed interface Task {
        val origin: MediaOrigin
        data class One(override val origin: MediaOrigin, val entry: FileEntry) : Task
        data class Folder(override val origin: MediaOrigin, val path: String, val name: String) : Task
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = ArrayDeque<Task>()
    private var worker: Job? = null
    @Volatile private var session: AiSubtitleSession? = null

    private val _status = MutableStateFlow<AiBatchStatus?>(null)
    val status: StateFlow<AiBatchStatus?> = _status

    /** 영상 재생 중이면 true (MainActivity가 넣어줌). */
    val paused = MutableStateFlow(false)

    /** 중지 누를 때마다 바뀜 — 예전 작업이 뒤늦게 상태를 다시 띄우지 못하게. */
    @Volatile private var generation = 0
    @Volatile private var stopping = false

    private var doneCount = 0
    private var skipCount = 0

    /** 모델이 준비됐는지 (넣기 전에 확인). */
    fun ready(context: Context): Boolean {
        val store = SettingsStore(context)
        val m = AiModel.entries.firstOrNull { it.name == store.aiWhisperModel } ?: AiModel.WHISPER_TURBO
        return ModelManager(context).isReady(m)
    }

    fun addFile(context: Context, origin: MediaOrigin, entry: FileEntry) =
        enqueue(context, Task.One(origin, entry))

    fun addFolder(context: Context, origin: MediaOrigin, path: String, name: String) =
        enqueue(context, Task.Folder(origin, path, name))

    fun stopAll() {
        stopping = true
        generation++
        synchronized(queue) { queue.clear() }
        session?.stop()
        worker?.cancel()
        _status.value = null
    }

    private fun enqueue(context: Context, task: Task) {
        val app = context.applicationContext
        stopping = false
        synchronized(queue) { queue.addLast(task) }
        if (worker?.isActive != true) {
            doneCount = 0
            skipCount = 0
            worker = scope.launch { run(app) }
        } else {
            publish()
        }
        AiBatchService.start(app)
    }

    private fun waiting() = synchronized(queue) { queue.size }

    private fun publish(current: String? = _status.value?.current, percent: Int = _status.value?.percent ?: 0) {
        if (stopping) return
        _status.value = AiBatchStatus(current ?: "", percent, waiting(), doneCount, skipCount, paused.value)
    }

    private suspend fun run(app: Context) {
        try {
            while (!stopping) {
                val task = synchronized(queue) { queue.removeFirstOrNull() } ?: break
                when (task) {
                    is Task.One -> processVideo(app, task.origin, task.entry, folder = null, browser = null)
                    is Task.Folder -> processFolder(app, task)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            _status.value = null
            stopping = false
        }
    }

    private suspend fun processFolder(app: Context, task: Task.Folder) {
        publish(current = "${task.name} 살펴보는 중", percent = 0)
        val browser = task.origin.server?.let { FtpBrowser(it) }
        try {
            val list = try {
                if (browser != null) browser.list(task.path) else LocalStorage.list(task.path)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return
            }
            val videos = list.filter { it.isVideo }
            // 폴더 안 영상들을 대기줄 맨 앞에 차례로 (같은 연결 재사용을 위해 여기서 바로 처리)
            videos.forEachIndexed { i, v ->
                if (stopping) return
                processVideo(app, task.origin, v, folder = list, browser = browser, extraWaiting = videos.size - i - 1)
            }
        } finally {
            browser?.close()
        }
    }

    /** 영상 하나: 건너뛸지 확인 → 처음부터 끝까지 만들기 → .ai.srt 저장. */
    private suspend fun processVideo(
        app: Context,
        origin: MediaOrigin,
        entry: FileEntry,
        folder: List<FileEntry>?,
        browser: FtpBrowser?,
        extraWaiting: Int = 0,
    ) {
        val server = origin.server
        var ownBrowser: FtpBrowser? = null
        try {
            val ftp = browser ?: server?.let { FtpBrowser(it).also { b -> ownBrowser = b } }
            val listing = folder ?: try {
                if (ftp != null) ftp.list(parentPath(entry.path)) else LocalStorage.list(parentPath(entry.path))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            if (shouldSkip(app, origin, entry, listing)) {
                skipCount++
                publish()
                return
            }

            // 영상 보는 중이면 기다림
            if (paused.value) {
                publish(current = entry.name, percent = 0)
                paused.first { !it }
            }

            val store = SettingsStore(app)
            val models = ModelManager(app)
            val whisper = AiModel.entries.firstOrNull { it.name == store.aiWhisperModel } ?: AiModel.WHISPER_TURBO
            if (!models.isReady(whisper)) return
            val llm = if (store.aiTranslate) AiModel.translator(models, live = false, preferFast = false)?.let { models.file(it) } else null
            val showMode = AiShowMode.entries.firstOrNull { it.name == store.aiShowMode } ?: AiShowMode.BOTH
            val key = PlaybackStore.get(app).keyFor(origin, entry)
            val url = server?.let { FtpStreamServer.urlFor(it, entry.path, entry.size) }

            try {
                while (true) {
                    val s = AiSubtitleSession(
                        app, key,
                        source = url ?: entry.path,
                        isUrl = url != null,
                        whisperModel = models.file(whisper),
                        llmModel = llm,
                        onComplete = { list, _ ->
                            if (list.isNotEmpty()) {
                                val path = aiSrtPath(entry)
                                runCatching {
                                    val bytes = aiCuesToSrt(list, showMode)
                                    if (ftp != null) ftp.upload(path, bytes)
                                    else withContext(Dispatchers.IO) { File(path).writeBytes(bytes) }
                                }
                            }
                            true
                        },
                        batch = true,
                    )
                    if (stopping) return
                    session = s
                    s.start(scope) { 0L }
                    // 진행률 표시 + 영상 보기 시작하면 멈춤 (중지되면 같이 끝남)
                    val gen = generation
                    val watcher = scope.launch {
                        while (isActive && !stopping && gen == generation) {
                            _status.value = AiBatchStatus(
                                entry.name, (s.progress * 100).toInt(), waiting() + extraWaiting,
                                doneCount, skipCount, paused.value, s.batchDetail(System.currentTimeMillis()),
                            )
                            if (paused.value) {
                                s.stop()
                                break
                            }
                            delay(1000)
                        }
                    }
                    try {
                        s.join()
                    } finally {
                        watcher.cancel()
                        session = null
                        if (stopping) s.stop()
                    }
                    if (stopping) return
                    when {
                        s.completed -> {
                            doneCount++
                            break
                        }
                        s.korean -> {
                            skipCount++
                            break
                        }
                        paused.value -> {
                            // 영상 다 보면 이어서 (만든 부분은 저장돼 있음)
                            publish(current = entry.name, percent = (s.progress * 100).toInt())
                            paused.first { !it }
                        }
                        else -> {
                            // 실패 (형식·연결 문제) → 건너뜀
                            skipCount++
                            break
                        }
                    }
                }
            } finally {
                url?.let { FtpStreamServer.release(it) }
            }
            publish()
        } finally {
            ownBrowser?.close()
        }
    }

    /** 한국어 영상(이미 판단됨), 자막 파일이 있는 영상, AI 자막을 이미 다 만든 영상은 건너뜀. */
    private fun shouldSkip(app: Context, origin: MediaOrigin, entry: FileEntry, folder: List<FileEntry>): Boolean {
        val base = entry.name.substringBeforeLast('.').lowercase()
        // 사람이 만든 자막 파일이 있으면 건너뜀 (앱이 만든 .ai.srt는 아래 기록으로 판단)
        val subs = Subtitles.findFor(entry, folder).filter {
            val n = it.name.lowercase()
            n.startsWith(base) && !n.endsWith(".ai.srt")
        }
        if (subs.isNotEmpty()) return true
        val key = PlaybackStore.get(app).keyFor(origin, entry)
        val dir = File(app.filesDir, "aisubs")
        if (File(dir, AiSubtitleSession.sha1(key) + ".ko").exists()) return true
        val cache = File(dir, AiSubtitleSession.sha1(key) + ".json")
        if (cache.exists()) {
            // 완벽 버전까지 다 만들었으면 건너뜀 (보면서 빠르게 만든 것만 있으면 좋은 모델로 다시)
            val text = runCatching { cache.readText() }.getOrDefault("")
            return text.contains("\"allPerfect\":true")
        }
        // 기록은 없는데 .ai.srt가 이미 있으면 (다른 데서 만든 것) 건너뜀
        return folder.any { it.name.lowercase() == base + ".ai.srt" }
    }
}

/** AI 자막 파일 경로: 영상 옆 "영상이름.ai.srt". */
fun aiSrtPath(entry: FileEntry): String = entry.path.substringBeforeLast('.') + ".ai.srt"
