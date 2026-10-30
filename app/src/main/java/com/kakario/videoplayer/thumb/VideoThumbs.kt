package com.kakario.videoplayer.thumb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.LruCache
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.kakario.videoplayer.data.FtpServer
import com.kakario.videoplayer.ftp.FtpRangeReader
import com.kakario.videoplayer.model.FileEntry
import com.kakario.videoplayer.model.MediaOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** 화면 어디서든 썸네일 로더를 꺼내 쓰기 위한 통로. */
val LocalVideoThumbs = staticCompositionLocalOf<VideoThumbs?> { null }

/** 폴더 전체 썸네일 만들기 진행 상황 (null이면 쉬는 중). */
data class ThumbJobStatus(val folder: String, val done: Int, val total: Int, val waiting: Int = 0)

/**
 * 영상 썸네일(기본 30초 지점 장면)을 만들고 기억해두는 곳.
 *
 * - 메모리 → 디스크 캐시(앱을 다시 켜도 유지) → 실제 추출 순서로 찾는다.
 * - FTP는 영상을 통째로 받지 않고, 재생기(안드로이드 기본 추출기)가 필요한 부분만
 *   FTP 이어받기로 조금씩 읽어서 장면 하나를 뽑는다.
 * - 고른 장면이 너무 어두우면(페이드·검은 화면) 조금 뒤 장면을 다시 시도한다.
 * - 영상을 보는 중에는 멈춰서 재생 쪽에 속도를 양보한다.
 */
class VideoThumbs private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val dir = File(context.cacheDir, "vthumbs").apply { mkdirs() }
    private val metaPrefs = context.getSharedPreferences("thumb_meta", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val memory = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 10).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** 썸네일이 새로 생기거나 지워질 때 그 칸만 다시 그리게 하는 번호표. */
    private val stamps = mutableStateMapOf<String, Int>()

    /** 같은 실패를 계속 다시 시도하지 않도록 (앱을 다시 켜면 다시 시도). */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private val failed = ConcurrentHashMap.newKeySet<String>()

    /** 내 폰 영상은 동시에 2개, FTP는 서버마다 연결 하나로 차례대로. */
    private val localPermits = Semaphore(2)
    private val ftpLocks = ConcurrentHashMap<Long, Mutex>()
    private val ftpReaders = ConcurrentHashMap<Long, FtpRangeReader>()

    /** 재생 중이면 true — 새 추출을 시작하지 않고 기다린다. */
    val paused = MutableStateFlow(false)

    /** 썸네일 장면 위치(ms). */
    @Volatile var thumbMs: Long = 30_000
    /** 어두우면 뒤 장면을 더 찾아볼지. */
    @Volatile var skipDark: Boolean = true
    @Volatile var ftpEnabled: Boolean = true
    @Volatile var onMobileData: Boolean = true

    private val _job = MutableStateFlow<ThumbJobStatus?>(null)
    val job: StateFlow<ThumbJobStatus?> = _job
    private var folderJob: Job? = null

    init {
        // 재생을 시작하면 썸네일용 FTP 연결을 닫아 서버 동시 접속 수를 아낀다
        scope.launch {
            paused.collect { p -> if (p) closeFtpReaders() }
        }
    }

    fun key(origin: MediaOrigin, entry: FileEntry): String =
        "${origin.tag}|${entry.path}|${entry.size}|${entry.modified}"

    /** 컴포즈에서 읽으면 그 썸네일이 바뀔 때 다시 그려진다. */
    fun stamp(key: String): Int = stamps[key] ?: 0

    fun cached(key: String): Bitmap? = memory.get(key)

    /** 저장해둔 재생 시간(ms). 썸네일을 만들 때 함께 알아낸다. 모르면 0. */
    fun duration(key: String): Long = metaPrefs.getLong(hash(key), 0L)

    /** 이 영상에 썸네일을 자동으로 만들어도 되는지 (설정·네트워크 확인). */
    fun autoAllowed(origin: MediaOrigin): Boolean {
        if (origin.server == null) return true
        if (!ftpEnabled) return false
        if (!onMobileData && isMobileData()) return false
        return true
    }

    /**
     * 썸네일을 돌려준다. 없으면 만든다 (오래 걸릴 수 있음, 취소 가능).
     * 만들 수 없는 영상(형식 미지원 등)이면 null.
     */
    suspend fun load(origin: MediaOrigin, entry: FileEntry, force: Boolean = false): Bitmap? {
        val key = key(origin, entry)
        memory.get(key)?.let { return it }
        val file = fileFor(key)
        withContext(Dispatchers.IO) { decode(file) }?.let {
            memory.put(key, it)
            return it
        }
        if (!force && (key in failed || !autoAllowed(origin))) return null
        return generateLocked(origin, entry, key)
    }

    /** 표지를 지우고 다시 만들게 한다. */
    fun invalidate(origin: MediaOrigin, entry: FileEntry) {
        val key = key(origin, entry)
        memory.remove(key)
        failed.remove(key)
        fileFor(key).delete()
        bump(key)
    }

    /** 썸네일 만들기 작업 하나 (폴더 하나, 또는 고른 영상 묶음). */
    private class Task(
        val origin: MediaOrigin,
        val name: String,
        val load: suspend () -> List<FileEntry>,
        /** true면 이미 있는 썸네일도 지우고 다시 만든다. */
        val force: Boolean,
    )

    private val queue = ArrayDeque<Task>()

    /** 폴더 안에서 썸네일이 없는 영상만 만든다. */
    fun buildFolder(origin: MediaOrigin, folderName: String, videos: List<FileEntry>): Boolean {
        enqueue(listOf(Task(origin, folderName, { videos }, force = false)))
        return true
    }

    /**
     * 여러 폴더·영상의 썸네일을 차례로 (다시) 만든다.
     * folders: (이름, 그 폴더 영상 목록을 가져오는 함수)
     */
    fun rebuild(
        origin: MediaOrigin,
        folders: List<Pair<String, suspend () -> List<FileEntry>>>,
        videos: List<FileEntry>,
        force: Boolean,
    ) {
        val tasks = mutableListOf<Task>()
        if (videos.isNotEmpty()) tasks += Task(origin, "고른 영상 ${videos.size}개", { videos }, force)
        folders.forEach { (name, load) -> tasks += Task(origin, name, load, force) }
        enqueue(tasks)
    }

    private fun enqueue(tasks: List<Task>) {
        synchronized(queue) { queue.addAll(tasks) }
        if (folderJob?.isActive == true) return
        folderJob = scope.launch {
            try {
                while (isActive) {
                    val task = synchronized(queue) { queue.removeFirstOrNull() } ?: break
                    val waiting = { synchronized(queue) { queue.size } }
                    _job.value = ThumbJobStatus(task.name, 0, 0, waiting())
                    val videos = try {
                        task.load().filter { it.isVideo }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                    val todo = if (task.force) videos else videos.filter { !fileFor(key(task.origin, it)).exists() }
                    todo.forEachIndexed { i, v ->
                        if (!isActive) return@launch
                        _job.value = ThumbJobStatus(task.name, i, todo.size, waiting())
                        if (task.force) invalidate(task.origin, v)
                        val key = key(task.origin, v)
                        if (memory.get(key) == null && !fileFor(key).exists()) {
                            runCatching { generateLocked(task.origin, v, key) }
                        }
                    }
                }
            } finally {
                _job.value = null
                // 끝나는 순간에 새로 들어온 작업이 있으면 이어서 처리
                if (synchronized(queue) { queue.isNotEmpty() }) {
                    val me = folderJob
                    scope.launch {
                        me?.join()
                        enqueue(emptyList())
                    }
                }
            }
        }
    }

    fun stopFolder() {
        synchronized(queue) { queue.clear() }
        folderJob?.cancel()
        _job.value = null
    }

    fun diskBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun clear() {
        stopFolder()
        memory.evictAll()
        failed.clear()
        dir.listFiles()?.forEach { it.delete() }
        metaPrefs.edit().clear().apply()
        main.post { stamps.keys.toList().forEach { stamps[it] = (stamps[it] ?: 0) + 1 } }
    }

    // ───────────────────────────── 내부

    private suspend fun generateLocked(origin: MediaOrigin, entry: FileEntry, key: String): Bitmap? {
        val server = origin.server
        val result = if (server == null) {
            localPermits.withPermit {
                waitWhilePaused()
                withContext(Dispatchers.IO) { memory.get(key) ?: decode(fileFor(key)) ?: extractLocal(entry)?.also { save(key, it) } }
            }
        } else {
            val lock = ftpLocks.getOrPut(server.id) { Mutex() }
            lock.withLock {
                waitWhilePaused()
                withContext(Dispatchers.IO) { memory.get(key) ?: decode(fileFor(key)) ?: extractFtp(server, entry)?.also { save(key, it) } }
            }
        }
        if (result == null) failed.add(key) else memory.put(key, result)
        return result
    }

    private fun save(key: String, bmp: Bitmap) {
        memory.put(key, bmp)
        runCatching {
            FileOutputStream(fileFor(key)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        }
        bump(key)
    }

    /** 번호표는 화면(메인 스레드)에서만 바꾼다 — 백그라운드에서 바꾸면 화면 갱신과 부딪힐 수 있다. */
    private fun bump(key: String) {
        main.post { stamps[key] = (stamps[key] ?: 0) + 1 }
    }

    private suspend fun waitWhilePaused() {
        if (paused.value) paused.first { !it }
    }

    private fun extractLocal(entry: FileEntry): Bitmap? {
        if (entry.extension !in VLC_ONLY) {
            val r = MediaMetadataRetriever()
            val bmp = try {
                r.setDataSource(entry.path)
                pickFrame(r, key = null, entry = entry)
            } catch (_: Exception) {
                null
            } finally {
                runCatching { r.release() }
            }
            if (bmp != null) return bmp
        }
        // 안드로이드 기본 추출기가 못 여는 형식 → FFmpeg 추출기 → 그래도 안 되면 VLC
        extractFfmpeg(MediaOrigin(null), entry, entry.path)?.let { return it }
        return extractVlc(MediaOrigin(null), entry, android.net.Uri.fromFile(File(entry.path)))
    }

    private fun extractFtp(server: FtpServer, entry: FileEntry): Bitmap? {
        val bmp = if (entry.extension in VLC_ONLY) null else extractFtpRetriever(server, entry)
        if (bmp != null) return bmp
        // AVI·WMV·MPG·VOB 등은 FFmpeg 추출기로 — 재생할 때와 같은 폰 안 중계 주소로 연다
        val url = com.kakario.videoplayer.ftp.FtpStreamServer.urlFor(server, entry.path, entry.size)
        return try {
            extractFfmpeg(MediaOrigin(server), entry, url)
                ?: extractVlc(MediaOrigin(server), entry, android.net.Uri.parse(url))
        } finally {
            com.kakario.videoplayer.ftp.FtpStreamServer.release(url)
        }
    }

    /**
     * FFmpeg 추출기(코덱 거의 전부 지원)로 장면 뽑기. source는 파일 경로 또는 http 주소.
     * 안드로이드 기본 추출기와 같은 방식으로 시점 후보·어두운 장면 건너뛰기를 적용한다.
     */
    private fun extractFfmpeg(origin: MediaOrigin, entry: FileEntry, source: String): Bitmap? {
        val r = wseemann.media.FFmpegMediaMetadataRetriever()
        return try {
            r.setDataSource(source)
            val duration = r.extractMetadata(wseemann.media.FFmpegMediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (duration > 0) metaPrefs.edit().putLong(hash(key(origin, entry)), duration).apply()
            var best: Bitmap? = null
            var bestLum = -1f
            for (ms in candidateTimes(duration)) {
                val raw = try {
                    r.getFrameAtTime(ms * 1000, wseemann.media.FFmpegMediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } catch (_: Throwable) {
                    null
                } ?: continue
                val frame = scaleDown(raw)
                val lum = luminance(frame)
                if (lum > bestLum) {
                    best?.recycle()
                    best = frame
                    bestLum = lum
                } else {
                    frame.recycle()
                }
                if (lum >= DARK_LIMIT) break
            }
            if (best == null) {
                // 시점을 못 찾으면 아무 장면이라도 (길이를 모르는 MPG 등)
                best = runCatching { r.frameAtTime }.getOrNull()?.let { scaleDown(it) }
            }
            best
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /** VLC로 장면 뽑기 (한 번에 하나만). 어두우면 조금 뒤 장면을 더 본다. */
    private fun extractVlc(origin: MediaOrigin, entry: FileEntry, uri: android.net.Uri): Bitmap? = synchronized(vlcLock) {
        val result = try {
            vlc.grab(
                uri = uri,
                times = { duration -> candidateTimes(duration) },
                good = { luminance(it) >= DARK_LIMIT },
            )
        } catch (_: Throwable) {
            return null
        }
        if (result.durationMs > 0) metaPrefs.edit().putLong(hash(key(origin, entry)), result.durationMs).apply()
        val best = result.frames.maxByOrNull { luminance(it.second) }?.second
        result.frames.forEach { if (it.second !== best) it.second.recycle() }
        return best?.let { scaleDown(it) }
    }

    private fun extractFtpRetriever(server: FtpServer, entry: FileEntry): Bitmap? {
        val reader = ftpReaders.getOrPut(server.id) { FtpRangeReader(server) }
        val source = FtpMediaSource(reader, entry.path, entry.size, deadlineMs = System.currentTimeMillis() + 60_000)
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(source)
            pickFrame(r, key = null, entry = entry, server = server)
        } catch (_: Exception) {
            reader.close()
            null
        } finally {
            runCatching { r.release() }
            runCatching { source.close() }
        }
    }

    /**
     * 설정한 시점(기본 30초)에서 장면을 뽑고, 너무 어두우면 조금 뒤를 다시 시도한다.
     * 재생 시간도 함께 기록한다.
     */
    private fun pickFrame(r: MediaMetadataRetriever, key: String?, entry: FileEntry, server: FtpServer? = null): Bitmap? {
        val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val origin = MediaOrigin(server)
        if (duration > 0) metaPrefs.edit().putLong(hash(key ?: key(origin, entry)), duration).apply()

        val candidates = candidateTimes(duration)

        var best: Bitmap? = null
        var bestLum = -1f
        for (ms in candidates) {
            val frame = frameAt(r, ms) ?: continue
            val lum = luminance(frame)
            if (lum > bestLum) {
                best?.recycle()
                best = frame
                bestLum = lum
            } else {
                frame.recycle()
            }
            if (lum >= DARK_LIMIT) break
        }
        if (best == null && duration <= 0) {
            // 길이를 모르는 영상: 처음 쪽이라도
            best = frameAt(r, 0)
        }
        return best
    }

    /**
     * 설정한 위치(0~2분, 기본 30초)를 먼저.
     * '어두우면 뒤 장면 찾기'가 켜져 있으면 15초 뒤 → 45초 뒤 → 영상 1/4 지점도 후보로.
     */
    private fun candidateTimes(duration: Long): List<Long> {
        var base = thumbMs
        // 설정 위치보다 짧은 영상은 가운데 장면
        if (duration > 0 && base >= duration * 0.9) base = duration / 2
        if (!skipDark) return listOf(base)
        return buildList {
            add(base)
            add(base + 15_000)
            add(base + 45_000)
            if (duration > 0) add(duration / 4)
        }.filter { duration <= 0 || it < duration }.distinct().take(4)
    }

    private fun frameAt(r: MediaMetadataRetriever, ms: Long): Bitmap? = try {
        val us = ms * 1000
        val raw = if (Build.VERSION.SDK_INT >= 27) {
            r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_W, THUMB_H)
        } else {
            r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
        raw?.let { scaleDown(it) }
    } catch (_: Exception) {
        null
    }

    private fun scaleDown(src: Bitmap): Bitmap {
        if (src.width <= THUMB_W && src.height <= THUMB_H) return src
        val ratio = minOf(THUMB_W.toFloat() / src.width, THUMB_H.toFloat() / src.height)
        val out = Bitmap.createScaledBitmap(src, (src.width * ratio).toInt().coerceAtLeast(1), (src.height * ratio).toInt().coerceAtLeast(1), true)
        if (out !== src) src.recycle()
        return out
    }

    /** 0~255 평균 밝기 (가로 16 × 세로 9 점만 찍어서 빠르게). */
    private fun luminance(b: Bitmap): Float {
        var sum = 0f
        var n = 0
        for (yi in 0 until 9) for (xi in 0 until 16) {
            val x = ((xi + 0.5f) * b.width / 16).toInt().coerceIn(0, b.width - 1)
            val y = ((yi + 0.5f) * b.height / 9).toInt().coerceIn(0, b.height - 1)
            val c = b.getPixel(x, y)
            sum += 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
            n++
        }
        return if (n > 0) sum / n else 0f
    }

    private fun decode(file: File): Bitmap? =
        if (file.exists() && file.length() > 0) runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull() else null

    private fun fileFor(key: String) = File(dir, hash(key) + ".jpg")

    private fun hash(key: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun isMobileData(): Boolean = try {
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    } catch (_: Exception) {
        false
    }

    private fun closeFtpReaders() {
        // 각 서버 잠금을 잡고 닫아서 추출 중인 연결을 끊지 않게 한다
        ftpReaders.forEach { (id, reader) ->
            scope.launch {
                ftpLocks.getOrPut(id) { Mutex() }.withLock { reader.close() }
            }
        }
    }

    private val vlc by lazy { VlcThumbnailer(appContext) }
    private val vlcLock = Any()

    companion object {
        /** 안드로이드 기본 추출기로는 안 되는 형식 — 바로 FFmpeg(→ VLC)로 간다. */
        private val VLC_ONLY = setOf(
            "avi", "wmv", "asf", "flv", "rm", "rmvb", "divx", "xvid", "vob", "ogv", "mpg", "mpeg", "mpe",
            "m2v", "mod", "tod", "k3g", "skm",
        )
        private const val THUMB_W = 384
        private const val THUMB_H = 216
        /** 이보다 어두우면 다른 장면을 더 찾아본다 (0~255). */
        private const val DARK_LIMIT = 28f

        @Volatile private var instance: VideoThumbs? = null

        fun get(context: Context): VideoThumbs =
            instance ?: synchronized(this) {
                instance ?: VideoThumbs(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * 안드로이드 추출기가 "몇 번째 바이트부터 몇 개"를 달라고 하면 FTP에서 그 부분만 받아준다.
 * 256KB 단위로 받아 기억해두고, 이어서 읽는 중이면 1MB씩 미리 받는다.
 */
private class FtpMediaSource(
    private val reader: FtpRangeReader,
    private val path: String,
    private val fileSize: Long,
    private val deadlineMs: Long,
) : MediaDataSource() {
    private val blocks = object : LinkedHashMap<Long, ByteArray>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > 32
    }
    private var lastBlock = -10L

    override fun getSize(): Long = fileSize

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= fileSize) return -1
        if (System.currentTimeMillis() > deadlineMs) return -1 // 너무 오래 걸리면 포기
        var copied = 0
        var pos = position
        try {
            while (copied < size && pos < fileSize) {
                val bi = pos / BLOCK
                val block = block(bi) ?: break
                val inOff = (pos - bi * BLOCK).toInt()
                val n = minOf(size - copied, block.size - inOff)
                if (n <= 0) break
                System.arraycopy(block, inOff, buffer, offset + copied, n)
                copied += n
                pos += n
            }
        } catch (_: Exception) {
            return if (copied > 0) copied else -1
        }
        return if (copied > 0) copied else -1
    }

    private fun block(bi: Long): ByteArray? {
        blocks[bi]?.let { return it }
        // 바로 앞 블록 다음을 읽는 중이면 이어서 4블록(1MB)을 한 번에
        val count = if (bi == lastBlock + 1) 4 else 1
        val start = bi * BLOCK
        val len = minOf(BLOCK.toLong() * count, fileSize - start).toInt()
        if (len <= 0) return null
        val data = reader.read(path, start, len)
        if (data.isEmpty()) return null
        var i = 0
        while (i * BLOCK < data.size) {
            val from = i * BLOCK
            val to = minOf(from + BLOCK, data.size)
            blocks[bi + i] = data.copyOfRange(from, to)
            i++
        }
        lastBlock = bi + i - 1
        return blocks[bi]
    }

    override fun close() {
        blocks.clear()
    }

    private companion object {
        const val BLOCK = 256 * 1024
    }
}
