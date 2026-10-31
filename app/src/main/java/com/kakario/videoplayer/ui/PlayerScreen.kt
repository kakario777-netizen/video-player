package com.kakario.videoplayer.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kakario.videoplayer.data.PlaybackStore
import com.kakario.videoplayer.data.SettingsStore
import com.kakario.videoplayer.ai.AiModel
import com.kakario.videoplayer.ai.AiShowMode
import com.kakario.videoplayer.ai.AiSubtitleSession
import com.kakario.videoplayer.ai.ModelManager
import com.kakario.videoplayer.ai.aiCuesToSrt
import com.kakario.videoplayer.ftp.FtpStreamServer
import com.kakario.videoplayer.model.FileEntry
import com.kakario.videoplayer.model.MediaOrigin
import com.kakario.videoplayer.model.Subtitles
import com.kakario.videoplayer.model.parentPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** 재생 화면에서 쓰는 설정 (설정 화면에서 바꿈). */
data class PlayerPrefs(
    val subtitleScale: Int,
    val resume: Boolean,
    val skipSeconds: Int,
    /** 끝나면 5초 뒤 다음 화 자동 재생. */
    val autoNext: Boolean,
    /** 위쪽에 화면 잠금 버튼을 보일지 (설정). */
    val lockButton: Boolean = false,
)

/**
 * 재생 화면 ↔ MainActivity 사이 연락 통로.
 * 홈 버튼을 눌렀을 때 작은 창(PIP)으로 들어갈지 정하려면 지금 재생 중인지, 영상 비율이 얼마인지 알아야 한다.
 */
object PlayerBridge {
    @Volatile var active: Boolean = false
    @Volatile var playing: Boolean = false
    @Volatile var videoWidth: Int = 16
    @Volatile var videoHeight: Int = 9
}

private enum class Sheet { SUBTITLE, AUDIO, SPEED, ASPECT }
private enum class DragMode { SEEK, BRIGHTNESS, VOLUME }

private val SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
private val ASPECTS = listOf(
    MediaPlayer.ScaleType.SURFACE_BEST_FIT to "화면에 맞춤",
    MediaPlayer.ScaleType.SURFACE_FIT_SCREEN to "꽉 채우기 (가장자리 잘림)",
    MediaPlayer.ScaleType.SURFACE_FILL to "늘려서 채우기",
    MediaPlayer.ScaleType.SURFACE_16_9 to "16:9",
    MediaPlayer.ScaleType.SURFACE_4_3 to "4:3",
    MediaPlayer.ScaleType.SURFACE_ORIGINAL to "원본 크기",
)
/** 회전 버튼을 누를 때마다 자동 → 가로 → 세로 순. 처음엔 자동(폰을 든 방향 그대로). */
private val ROTATIONS = listOf(
    ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR to "자동",
    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE to "가로",
    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT to "세로",
)

/**
 * 영상 재생 화면 (VLC 엔진). N플레이어식 조작:
 * - 한 번 탭: 버튼 보이기/숨기기, 양옆 두 번 탭: 앞뒤로 건너뛰기, 가운데 두 번 탭: 일시정지
 * - 왼쪽 위아래 끌기: 밝기, 오른쪽 위아래 끌기: 볼륨, 좌우 끌기: 탐색
 * - 위쪽 버튼: 자막·음성·배속·화면비·회전·잠금
 */
@Composable
fun PlayerScreen(
    origin: MediaOrigin,
    entry: FileEntry,
    nextEntry: FileEntry?,
    prefs: PlayerPrefs,
    /** 영상이 있는 폴더의 전체 목록 (자막 찾기용). */
    listFolder: suspend (String) -> List<FileEntry>,
    /** 작은 파일(자막) 통째로 읽기. */
    readFile: suspend (FileEntry) -> ByteArray,
    /** 영상 옆에 파일 저장 (AI 자막 완성본). 경로, 내용. */
    writeFile: suspend (String, ByteArray) -> Unit = { _, _ -> },
    onOpenNext: (FileEntry) -> Unit,
    onClose: () -> Unit,
    /** 작은 창(PIP)으로 보는 중이면 버튼을 모두 숨긴다. */
    inPip: Boolean = false,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val store = remember { PlaybackStore.get(context) }
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    var rotation by remember { mutableIntStateOf(0) }

    // ── 전체 화면 + 화면 꺼짐 방지 (닫으면 원래대로)
    DisposableEffect(activity) {
        val window = activity?.window
        val oldOrientation = activity?.requestedOrientation
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // 재생 중에 바꾼 밝기는 앱 밖 설정을 건드리지 않고, 닫으면 시스템 밝기로 돌아간다
            window?.let { w ->
                w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            }
            activity?.requestedOrientation = oldOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    LaunchedEffect(rotation) {
        activity?.requestedOrientation = ROTATIONS[rotation].first
    }

    // ── VLC 엔진 (화면이 닫히면 정리)
    val libVlc = remember {
        LibVLC(
            context.applicationContext,
            arrayListOf(
                "--network-caching=2000",   // 2초 미리 받기 (외부망 끊김 완화)
                "--file-caching=500",
                "--no-sub-autodetect-file", // 자막은 앱이 직접 찾아서 UTF-8로 바꿔 붙인다
                "--subsdec-encoding=CP949", // 그래도 남는 자막(내장 등)은 한국어 기준
                "--sub-text-scale=${prefs.subtitleScale}",
                "--audio-time-stretch",     // 배속 때 음 높이 유지
            ),
        )
    }
    val player = remember { MediaPlayer(libVlc) }

    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var timeMs by remember { mutableLongStateOf(0L) }
    var lengthMs by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var ended by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) } // 조작할 때마다 올라감 → 자동 숨김 타이머 다시 시작
    var locked by remember { mutableStateOf(false) }
    var lockHintVisible by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var speed by remember { mutableFloatStateOf(1f) }
    var aspect by remember { mutableIntStateOf(0) }
    var subDelayMs by remember { mutableLongStateOf(0L) }
    var externalSubs by remember { mutableIntStateOf(0) }
    var subSearchDone by remember { mutableStateOf(false) }
    // 받아둔 자막 파일 (디코더를 바꿔 다시 열 때 다시 붙이기 위해)
    var subFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    // 하드웨어 디코딩이 실패하면 소프트웨어로 한 번 더 시도
    var softwareDecoding by remember { mutableStateOf(false) }
    var swRetried by remember { mutableStateOf(false) }
    var autoSwitch by remember { mutableIntStateOf(0) }
    // 다음 화 자동 재생 카운트다운 (null이면 꺼짐)
    var countdown by remember { mutableStateOf<Int?>(null) }
    var autoCancelled by remember { mutableStateOf(false) }

    // 화면 가운데 잠깐 뜨는 안내 (건너뛰기·밝기·볼륨 등)
    var hint by remember { mutableStateOf<String?>(null) }
    var hintKey by remember { mutableIntStateOf(0) }
    var hintSticky by remember { mutableStateOf(false) } // 끄는 동안엔 계속 표시
    fun showHint(text: String, sticky: Boolean = false) {
        hint = text
        hintSticky = sticky
        hintKey++
    }

    // 이어보기 안내 (몇 초 동안 "처음부터" 버튼)
    var resumedFrom by remember { mutableStateOf<Long?>(null) }

    DisposableEffect(player) {
        player.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> {
                    playing = true; buffering = false; ended = false; error = null
                    PlayerBridge.playing = true
                }
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> {
                    playing = false
                    PlayerBridge.playing = false
                }
                MediaPlayer.Event.Vout -> {
                    player.currentVideoTrack?.let { v ->
                        if (v.width > 0 && v.height > 0) {
                            PlayerBridge.videoWidth = v.width
                            PlayerBridge.videoHeight = v.height
                        }
                    }
                }
                MediaPlayer.Event.Buffering -> buffering = e.buffering < 100f
                MediaPlayer.Event.TimeChanged -> { timeMs = e.timeChanged; buffering = false }
                MediaPlayer.Event.LengthChanged -> lengthMs = e.lengthChanged
                MediaPlayer.Event.EndReached -> {
                    playing = false
                    ended = true
                    controlsVisible = true
                    timeMs = lengthMs
                }
                MediaPlayer.Event.EncounteredError -> {
                    playing = false
                    buffering = false
                    PlayerBridge.playing = false
                    if (!softwareDecoding && !swRetried) {
                        // 하드웨어 디코더가 못 푸는 영상 → 소프트웨어로 자동 재시도
                        swRetried = true
                        softwareDecoding = true
                        autoSwitch++
                    } else {
                        error = "영상을 재생하지 못했어요.\n서버 연결 또는 파일 형식을 확인해 주세요."
                    }
                }
            }
        }
        PlayerBridge.active = true
        onDispose {
            PlayerBridge.active = false
            PlayerBridge.playing = false
            player.setEventListener(null)
            player.stop()
            player.detachViews()
            player.release()
            libVlc.release()
        }
    }

    // 앱이 뒤로 가거나 화면이 꺼지면 일시정지 (작은 창으로 보는 중이면 계속 재생)
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        val pip = android.os.Build.VERSION.SDK_INT >= 24 && activity?.isInPictureInPictureMode == true
        if (!pip && player.isPlaying) player.pause()
        controlsVisible = true
    }
    // 작은 창을 닫거나 완전히 내려가면 멈춤
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (player.isPlaying) player.pause()
    }

    // ── 영상 열기 (다음 화로 바뀌면 다시)
    val key = remember(entry.path, entry.size) { store.keyFor(origin, entry) }
    val currentListFolder by rememberUpdatedState(listFolder)
    val currentReadFile by rememberUpdatedState(readFile)
    val currentWriteFile by rememberUpdatedState(writeFile)
    val server = origin.server
    val streamUrl = remember(key) { if (server != null) FtpStreamServer.urlFor(server, entry.path, entry.size) else null }

    /** 영상을 (다시) 연다. startAtMs 위치부터, 받아둔 자막도 다시 붙인다. */
    fun openMedia(startAtMs: Long, hw: Boolean) {
        val media = if (streamUrl != null) Media(libVlc, Uri.parse(streamUrl)) else Media(libVlc, Uri.fromFile(File(entry.path)))
        media.setHWDecoderEnabled(hw, false)
        if (startAtMs > 0) media.addOption(":start-time=${startAtMs / 1000.0}")
        player.media = media
        media.release()
        subFiles.forEachIndexed { i, f -> player.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(f), i == 0) }
        buffering = true
        error = null
        player.rate = speed
        player.play()
    }

    DisposableEffect(key) {
        store.addRecent(origin, entry)
        // 이어보기: 끝까지 안 본 영상은 보던 위치부터
        val saved = store.get(key)
        resumedFrom = null
        subFiles = emptyList()
        softwareDecoding = false
        swRetried = false
        countdown = null
        autoCancelled = false
        val startAt = if (prefs.resume && saved.started && !saved.finished && saved.positionMs > 5_000) {
            (saved.positionMs - 2_000).coerceAtLeast(0).also { resumedFrom = it }
        } else 0L
        timeMs = startAt
        lengthMs = saved.lengthMs
        ended = false; subDelayMs = 0; externalSubs = 0; subSearchDone = false
        openMedia(startAt, hw = true)

        // 같은 폴더의 자막을 찾아 받아서 붙인다
        val subJob = scope.launch {
            try {
                val folder = currentListFolder(parentPath(entry.path))
                val subs = Subtitles.findFor(entry, folder)
                val files = mutableListOf<File>()
                subs.forEachIndexed { i, sub ->
                    val bytes = currentReadFile(sub)
                    if (bytes.isEmpty()) return@forEachIndexed
                    val file = withContext(Dispatchers.IO) { Subtitles.saveAsUtf8(context.cacheDir, sub, bytes) }
                    files += file
                    if (player.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(file), i == 0)) externalSubs++
                }
                subFiles = files
                if (externalSubs > 0) showHint("자막 연결됨: ${subs.first().name}")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 자막을 못 받아도 영상은 계속 본다
            } finally {
                subSearchDone = true
            }
        }

        onDispose {
            subJob.cancel()
            // 닫거나 다음 화로 넘어갈 때 위치 저장
            if (lengthMs > 0) store.set(key, if (ended) lengthMs else timeMs, lengthMs)
            store.bump()
            streamUrl?.let { FtpStreamServer.release(it) }
        }
    }

    // 하드웨어 디코딩 실패 → 소프트웨어로 같은 위치부터 다시
    LaunchedEffect(autoSwitch) {
        if (autoSwitch > 0) {
            showHint("소프트웨어 디코딩으로 다시 시도해요")
            openMedia(timeMs, hw = false)
        }
    }

    // 다 보면 다음 화 카운트다운
    LaunchedEffect(ended, nextEntry, prefs.autoNext, autoCancelled) {
        if (ended && nextEntry != null && prefs.autoNext && !autoCancelled) {
            for (n in 5 downTo 1) {
                countdown = n
                delay(1000)
            }
            countdown = null
            onOpenNext(nextEntry)
        } else {
            countdown = null
        }
    }

    // ── 오프라인 AI 자막 (폰 안에서만 동작)
    val aiStore = remember { SettingsStore(context) }
    val aiModels = remember { ModelManager(context) }
    // 실시간 시청은 빠른 모델(small)이 있으면 그걸로 — 큰 모델은 폰 CPU로 재생 속도를 못 따라감
    val whisperModel = remember {
        val chosen = AiModel.entries.firstOrNull { it.name == aiStore.aiWhisperModel } ?: AiModel.WHISPER_TURBO
        if (aiStore.aiLiveFast && aiModels.isReady(AiModel.WHISPER_SMALL)) AiModel.WHISPER_SMALL else chosen
    }
    val whisperReady = remember(key) { aiModels.isReady(whisperModel) }
    var aiOn by remember(key) { mutableStateOf(false) }
    var aiShow by remember { mutableStateOf(AiShowMode.entries.firstOrNull { it.name == aiStore.aiShowMode } ?: AiShowMode.BOTH) }
    var aiSession by remember(key) { mutableStateOf<AiSubtitleSession?>(null) }
    // 자막 파일 찾기가 끝나면, 설정에 따라 자동으로 켠다
    LaunchedEffect(key, subSearchDone) {
        if (subSearchDone && aiStore.aiSubtitles && whisperReady &&
            (!aiStore.aiOnlyWithoutSubs || externalSubs == 0)
        ) {
            aiOn = true
        }
    }
    DisposableEffect(key, aiOn) {
        if (!aiOn || !whisperReady) return@DisposableEffect onDispose { }
        val llm = if (aiStore.aiTranslate && aiModels.isReady(AiModel.QWEN3_4B)) aiModels.file(AiModel.QWEN3_4B) else null
        // 번역 모델을 뒤에서 미리 열고 예열 (여유 메모리 4GB 이상일 때만) → 첫 번역부터 바로
        if (llm != null) com.kakario.videoplayer.ai.LlmHolder.prewarmIfWanted(context, "VIDEO")
        val session = AiSubtitleSession(
            context, key,
            source = streamUrl ?: entry.path,
            isUrl = streamUrl != null,
            whisperModel = aiModels.file(whisperModel),
            llmModel = llm,
            onComplete = { list, _ ->
                // 영상 전체를 다 만들면 영상 옆에 "영상이름.ai.srt"로 저장 → 다음엔 일반 자막처럼 바로 붙는다
                if (list.isEmpty()) {
                    true
                } else {
                    val base = entry.path.substringBeforeLast('.')
                    val path = "$base.ai.srt"
                    try {
                        currentWriteFile(path, aiCuesToSrt(list, aiShow))
                        showHint("AI 자막을 파일로 저장했어요: ${path.substringAfterLast('/')}")
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        showHint("AI 자막 파일 저장 실패: ${e.message ?: "쓰기 권한 확인"}")
                        true // 계속 다시 시도하지 않게 (자막은 앱 안에 저장돼 있음)
                    }
                }
            },
        )
        aiSession = session
        session.start(scope) { timeMs.coerceAtLeast(0) }
        onDispose {
            session.stop()
            aiSession = null
        }
    }

    // 보는 동안 5초마다 위치 저장 (앱이 갑자기 꺼져도 이어보기 가능)
    LaunchedEffect(key) {
        while (true) {
            delay(5_000)
            if (playing && lengthMs > 0) store.set(key, timeMs, lengthMs)
        }
    }
    LaunchedEffect(resumedFrom) {
        if (resumedFrom != null) {
            delay(6_000)
            resumedFrom = null
        }
    }

    // ── 재생 중이면 3초 뒤 조작 버튼 숨기기
    LaunchedEffect(controlsVisible, playing, interaction, sheet) {
        if (controlsVisible && playing && sheet == null) {
            delay(3500)
            controlsVisible = false
        }
    }
    LaunchedEffect(hintKey, hintSticky) {
        if (hint != null && !hintSticky) {
            delay(900)
            hint = null
        }
    }
    LaunchedEffect(lockHintVisible) {
        if (lockHintVisible) {
            delay(2500)
            lockHintVisible = false
        }
    }

    val skipMs = prefs.skipSeconds * 1000L

    fun seekBy(deltaMs: Long) {
        val target = (player.time + deltaMs).coerceIn(0, if (lengthMs > 0) lengthMs - 1000 else Long.MAX_VALUE)
        player.time = target
        timeMs = target
        showHint(if (deltaMs > 0) "${prefs.skipSeconds}초 ▶▶" else "◀◀ ${prefs.skipSeconds}초")
        interaction++
    }

    fun togglePlay() {
        if (ended) {
            ended = false
            player.stop()
            player.play()
            player.time = 0
        } else if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
        interaction++
    }

    BackHandler {
        when {
            sheet != null -> sheet = null
            locked -> lockHintVisible = true
            else -> onClose()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 영상
        AndroidView(
            factory = { ctx -> VLCVideoLayout(ctx).also { player.attachViews(it, null, true, false) } },
            modifier = Modifier.fillMaxSize(),
        )

        // ── 손가락 조작 영역
        var dragMode by remember { mutableStateOf<DragMode?>(null) }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(locked, skipMs) {
                    detectTapGestures(
                        onTap = {
                            if (locked) {
                                lockHintVisible = !lockHintVisible
                            } else {
                                controlsVisible = !controlsVisible
                                sheet = null
                                interaction++
                            }
                        },
                        onDoubleTap = { pos ->
                            if (locked) return@detectTapGestures
                            when {
                                pos.x < size.width / 3f -> seekBy(-skipMs)
                                pos.x > size.width * 2f / 3f -> seekBy(skipMs)
                                else -> togglePlay()
                            }
                        },
                    )
                }
                .pointerInput(locked) {
                    if (locked) return@pointerInput
                    var total = Offset.Zero
                    var leftSide = true
                    var startTime = 0L
                    var seekTarget = 0L
                    var startBrightness = 0.5f
                    var startVolume = 0
                    val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    detectDragGestures(
                        onDragStart = { pos ->
                            total = Offset.Zero
                            leftSide = pos.x < size.width / 2f
                            dragMode = null
                        },
                        onDragEnd = {
                            if (dragMode == DragMode.SEEK) {
                                player.time = seekTarget
                                timeMs = seekTarget
                            }
                            dragMode = null
                            hintSticky = false
                            hintKey++
                        },
                        onDragCancel = {
                            dragMode = null
                            hintSticky = false
                            hintKey++
                        },
                        onDrag = { change, delta ->
                            change.consume()
                            total += delta
                            if (dragMode == null) {
                                val slop = 24f
                                if (abs(total.x) > slop && abs(total.x) > abs(total.y)) {
                                    if (lengthMs <= 0) return@detectDragGestures
                                    dragMode = DragMode.SEEK
                                    startTime = player.time
                                } else if (abs(total.y) > slop) {
                                    dragMode = if (leftSide) DragMode.BRIGHTNESS else DragMode.VOLUME
                                    startBrightness = currentBrightness(activity)
                                    startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                                }
                                total = Offset.Zero
                                return@detectDragGestures
                            }
                            when (dragMode) {
                                DragMode.SEEK -> {
                                    // 화면 폭 전체로 끌면 90초 이동
                                    val delta90 = (total.x / size.width * 90_000).toLong()
                                    seekTarget = (startTime + delta90).coerceIn(0, lengthMs - 1000)
                                    val sign = if (delta90 >= 0) "+" else "-"
                                    showHint("$sign${formatTime(abs(delta90))}\n${formatTime(seekTarget)}", sticky = true)
                                }
                                DragMode.BRIGHTNESS -> {
                                    val v = (startBrightness - total.y / (size.height * 0.8f)).coerceIn(0.01f, 1f)
                                    activity?.window?.let { w ->
                                        w.attributes = w.attributes.apply { screenBrightness = v }
                                    }
                                    showHint("☀ 밝기 ${(v * 100).roundToInt()}%", sticky = true)
                                }
                                DragMode.VOLUME -> {
                                    val v = (startVolume - total.y / (size.height * 0.8f) * maxVolume)
                                        .roundToInt().coerceIn(0, maxVolume)
                                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                                    showHint("🔊 볼륨 $v / $maxVolume", sticky = true)
                                }
                                null -> Unit
                            }
                        },
                    )
                }
        )

        if (buffering && error == null && !ended && !inPip) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }

        // ── AI 자막 표시
        aiSession?.let { ai ->
            val cue = ai.cueAt(timeMs)
            if (cue != null) {
                val ko = cue.ko
                val (main, second) = when (aiShow) {
                    AiShowMode.ORIGINAL -> cue.text to null
                    AiShowMode.KOREAN -> (ko ?: cue.text) to null
                    AiShowMode.BOTH -> if (ko != null) ko to cue.text else cue.text to null
                }
                val scale = prefs.subtitleScale / 100f
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(start = 24.dp, end = 24.dp, bottom = if (controlsVisible && !inPip) 120.dp else if (inPip) 4.dp else 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        main,
                        color = Color.White,
                        fontSize = (if (inPip) 11f else 19f * scale).sp,
                        textAlign = TextAlign.Center,
                        style = androidx.compose.ui.text.TextStyle(
                            shadow = androidx.compose.ui.graphics.Shadow(Color.Black, Offset(2f, 2f), 6f),
                        ),
                        modifier = Modifier
                            .background(Color(0x55000000), RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                    if (second != null && !inPip) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            second,
                            color = Color(0xFFE0E0E0),
                            fontSize = (14f * scale).sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .background(Color(0x55000000), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            // 작은 상태 표시: 오른쪽 위, 반투명, 이모티콘 없이
            // 1초마다 남은 시간 다시 계산 (일시정지 중에도)
            var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(1000)
                    nowTick = System.currentTimeMillis()
                }
            }
            val friendly = ai.friendlyLabel(timeMs, nowTick)
            val small = when {
                ai.notice != null -> ai.notice
                ai.korean -> null
                ai.failed != null -> ai.failed
                friendly != null -> friendly
                controlsVisible && ai.progress in 0.001f..0.999f -> "AI 자막 ${(ai.progress * 100).toInt()}%"
                else -> null
            }
            // 안내 문구는 4초 뒤 사라짐
            LaunchedEffect(ai.notice) {
                if (ai.notice != null) {
                    delay(4000)
                    ai.notice = null
                }
            }
            if (small != null && !inPip) {
                Text(
                    small,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(top = if (controlsVisible) 58.dp else 10.dp, end = 12.dp)
                        .background(Color(0x33000000), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }

        hint?.takeIf { !inPip }?.let {
            Text(
                it,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0x99000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }

        // ── 잠금 상태: 탭하면 잠금 해제 버튼만 잠깐
        if (locked) {
            AnimatedVisibility(
                visible = lockHintVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(start = 24.dp),
            ) {
                RoundButton("🔓", 56) {
                    locked = false
                    lockHintVisible = false
                    controlsVisible = true
                    interaction++
                }
            }
        }

        // ── 조작 버튼들
        AnimatedVisibility(
            visible = !inPip && !locked && (controlsVisible || error != null || ended),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000))
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                // 위: 뒤로 + 제목 + 기능 버튼
                Row(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "닫기", tint = Color.White)
                    }
                    Text(
                        entry.name,
                        color = Color.White,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TopChip("자막") { sheet = Sheet.SUBTITLE; interaction++ }
                    TopChip("음성") { sheet = Sheet.AUDIO; interaction++ }
                    TopChip(speedLabel(speed)) { sheet = Sheet.SPEED; interaction++ }
                    TopChip("화면") { sheet = Sheet.ASPECT; interaction++ }
                    if (prefs.lockButton) {
                        TopChip("🔒") {
                            locked = true
                            sheet = null
                            lockHintVisible = true
                            showHint("화면 잠금 — 탭해서 🔓 누르면 풀려요")
                        }
                    }
                }

                // 가운데: 오류 / 끝남 / 재생 버튼
                val err = error
                when {
                    err != null -> Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(err, color = Color.White, textAlign = TextAlign.Center)
                        Spacer(Modifier.size(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = onClose) { Text("닫기", color = Color.White) }
                            Button(onClick = {
                                error = null
                                buffering = true
                                player.stop()
                                player.play()
                            }) { Text("다시 시도") }
                        }
                    }

                    ended -> Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("다 봤어요", color = Color.White, fontSize = 18.sp)
                        val cd = countdown
                        if (cd != null && nextEntry != null) {
                            Spacer(Modifier.size(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${cd}초 뒤 다음 화를 재생해요", color = Color(0xFF8AB8FF), fontSize = 14.sp)
                                Text(
                                    "취소",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .padding(start = 8.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { autoCancelled = true }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }
                        Spacer(Modifier.size(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { togglePlay() }) { Text("처음부터", color = Color.White) }
                            if (nextEntry != null) {
                                Button(
                                    onClick = { onOpenNext(nextEntry) },
                                    modifier = Modifier.widthIn(max = 320.dp),
                                ) {
                                    Text("다음: ${nextEntry.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            } else {
                                Button(onClick = onClose) { Text("닫기") }
                            }
                        }
                    }

                    else -> Row(
                        Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(36.dp),
                    ) {
                        RoundButton("⟲${prefs.skipSeconds}", 52) { seekBy(-skipMs) }
                        RoundButton(if (playing) "❚❚" else "▶", 72) { togglePlay() }
                        RoundButton("${prefs.skipSeconds}⟳", 52) { seekBy(skipMs) }
                    }
                }

                // 아래: 이어보기 안내 + 시간 + 이동 막대
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    resumedFrom?.let { from ->
                        Row(
                            Modifier
                                .padding(start = 8.dp, bottom = 4.dp)
                                .background(Color(0xAA000000), RoundedCornerShape(8.dp))
                                .padding(start = 12.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${formatTime(from)}부터 이어서 재생", color = Color.White, fontSize = 13.sp)
                            Text(
                                "처음부터",
                                color = Color(0xFF8AB8FF),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        player.time = 0
                                        timeMs = 0
                                        resumedFrom = null
                                        interaction++
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 가장 많이 쓰는 회전 버튼: 크게, 왼쪽 아래
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    rotation = (rotation + 1) % ROTATIONS.size
                                    showHint("화면 방향: ${ROTATIONS[rotation].second}")
                                    interaction++
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(Color(0x66000000))
                                    .border(1.5.dp, Color(0xAAFFFFFF), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                LineIcon(LineIcons.Rotate, size = 30.dp, tint = Color.White)
                            }
                            Text(ROTATIONS[rotation].second, color = Color.White, fontSize = 11.sp)
                        }
                        Spacer(Modifier.width(4.dp))
                        Box(Modifier.weight(1f)) {
                            SeekBar(
                                timeMs = timeMs,
                                lengthMs = lengthMs,
                                onSeek = { ms ->
                                    player.time = ms
                                    timeMs = ms
                                    interaction++
                                },
                                onDragging = { interaction++ },
                            )
                        }
                    }
                }
            }
        }

        // ── 오른쪽에서 나오는 선택 창 (자막 / 음성 / 배속 / 화면비)
        sheet?.let { s ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x55000000))
                    .pointerInput(Unit) { detectTapGestures { sheet = null } }
            ) {
                Column(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(300.dp)
                        .background(Color(0xEE1C1F25))
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .pointerInput(Unit) { detectTapGestures { } } // 창 안쪽 탭은 닫지 않음
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 12.dp),
                ) {
                    when (s) {
                        Sheet.SUBTITLE -> {
                            SheetTitle("AI 자막 (오프라인)")
                            if (!whisperReady) {
                                SheetNote("설정 > 자막에서 음성인식 모델을 먼저 받아 주세요.\n폰 안에서만 동작하고 서버 연결은 전혀 없어요.")
                            } else {
                                SheetOption("켜기 — 소리를 듣고 자막 만들기", selected = aiOn) {
                                    if (!aiOn) {
                                        aiOn = true
                                        player.setSpuTrack(-1) // 파일 자막과 겹치지 않게
                                    }
                                }
                                SheetOption("끄기", selected = !aiOn) { aiOn = false }
                                if (aiOn) {
                                    aiSession?.language?.let { SheetNote("감지한 언어: ${langLabel(it)}") }
                                    aiSession?.translateInfo?.let { SheetNote("번역 상태: $it") }
                                    AiShowMode.entries.forEach { m ->
                                        SheetOption(m.label, selected = aiShow == m) {
                                            aiShow = m
                                            aiStore.aiShowMode = m.name
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            SheetTitle("자막 파일")
                            val tracks = remember(externalSubs) { player.spuTracks?.toList() ?: emptyList() }
                            if (tracks.isEmpty()) {
                                SheetNote("이 영상에는 자막이 없어요.\n영상과 같은 이름의 .smi / .srt 파일을 같은 폴더에 두면 자동으로 붙어요.")
                            }
                            var current by remember { mutableIntStateOf(player.spuTrack) }
                            tracks.forEach { t ->
                                SheetOption(trackName(t.name, "자막"), selected = t.id == current) {
                                    player.setSpuTrack(t.id)
                                    current = t.id
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            SheetTitle("자막 싱크")
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                SmallButton("-0.5초") {
                                    subDelayMs -= 500
                                    player.setSpuDelay(subDelayMs * 1000)
                                }
                                Text(
                                    (if (subDelayMs > 0) "+" else "") + String.format("%.1f초", subDelayMs / 1000.0),
                                    color = Color.White,
                                    fontSize = 15.sp,
                                )
                                SmallButton("+0.5초") {
                                    subDelayMs += 500
                                    player.setSpuDelay(subDelayMs * 1000)
                                }
                            }
                            SheetNote("자막이 늦게 나오면 -, 빨리 나오면 + 를 누르세요.\n자막 크기는 앱 설정에서 바꿀 수 있어요.")
                            if (subDelayMs != 0L) {
                                SheetOption("싱크 원래대로", selected = false) {
                                    subDelayMs = 0
                                    player.setSpuDelay(0)
                                }
                            }
                        }

                        Sheet.AUDIO -> {
                            SheetTitle("음성")
                            val tracks = remember { player.audioTracks?.toList() ?: emptyList() }
                            if (tracks.isEmpty()) SheetNote("음성 트랙 정보를 아직 못 읽었어요")
                            var current by remember { mutableIntStateOf(player.audioTrack) }
                            tracks.forEach { t ->
                                SheetOption(trackName(t.name, "음성"), selected = t.id == current) {
                                    player.setAudioTrack(t.id)
                                    current = t.id
                                }
                            }
                        }

                        Sheet.SPEED -> {
                            SheetTitle("재생 속도")
                            SPEEDS.forEach { sp ->
                                SheetOption(speedLabel(sp), selected = sp == speed) {
                                    speed = sp
                                    player.rate = sp
                                    sheet = null
                                    showHint("재생 속도 ${speedLabel(sp)}")
                                }
                            }
                        }

                        Sheet.ASPECT -> {
                            SheetTitle("화면 비율")
                            ASPECTS.forEachIndexed { i, (scale, label) ->
                                SheetOption(label, selected = i == aspect) {
                                    aspect = i
                                    player.videoScale = scale
                                    sheet = null
                                    showHint(label)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            SheetTitle("디코딩")
                            SheetOption("하드웨어 (빠름·배터리 절약)", selected = !softwareDecoding) {
                                if (softwareDecoding) {
                                    softwareDecoding = false
                                    swRetried = true // 자동 전환 다시 하지 않음
                                    openMedia(timeMs, hw = true)
                                    sheet = null
                                }
                            }
                            SheetOption("소프트웨어 (화면이 깨지거나 초록색일 때)", selected = softwareDecoding) {
                                if (!softwareDecoding) {
                                    swRetried = true
                                    softwareDecoding = true
                                    openMedia(timeMs, hw = false)
                                    sheet = null
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun currentBrightness(activity: Activity?): Float {
    if (activity == null) return 0.5f
    val b = activity.window.attributes.screenBrightness
    if (b >= 0f) return b
    return try {
        Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
    } catch (_: Exception) {
        0.5f
    }
}

private fun speedLabel(s: Float): String =
    if (s == s.toInt().toFloat()) "${s.toInt()}.0x" else "${s}x"

/** VLC가 주는 트랙 이름을 보기 좋게 ("Disable" → "끄기"). */
private fun trackName(name: String?, kind: String): String = when {
    name == null -> kind
    name.equals("Disable", ignoreCase = true) -> "끄기"
    else -> name.replace("Track", "트랙")
}

@Composable
private fun TopChip(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color.White,
        fontSize = 13.sp,
        maxLines = 1,
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x44FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text,
        color = Color(0xFF8AB8FF),
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun SheetNote(text: String) {
    Text(
        text,
        color = Color(0xFFB5B9C0),
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

@Composable
private fun SheetOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (selected) "●" else "○",
            color = if (selected) Color(0xFF5B9DFF) else Color(0x88FFFFFF),
            fontSize = 14.sp,
        )
        Spacer(Modifier.width(12.dp))
        Text(label, color = Color.White, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color.White,
        fontSize = 14.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x33FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
private fun RoundButton(label: String, sizeDp: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(Color(0x55000000))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = if (sizeDp >= 64) 26.sp else 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SeekBar(
    timeMs: Long,
    lengthMs: Long,
    onSeek: (Long) -> Unit,
    onDragging: () -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val fraction = if (lengthMs > 0) (timeMs.toFloat() / lengthMs).coerceIn(0f, 1f) else 0f
    val shown = if (dragging) dragValue else fraction

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            formatTime(if (dragging) (dragValue * lengthMs).toLong() else timeMs),
            color = Color.White,
            fontSize = 13.sp,
            modifier = Modifier.width(64.dp),
            textAlign = TextAlign.Center,
        )
        Slider(
            value = shown,
            onValueChange = {
                dragging = true
                dragValue = it
                onDragging()
            },
            onValueChangeFinished = {
                if (lengthMs > 0) onSeek((dragValue * lengthMs).toLong())
                dragging = false
            },
            enabled = lengthMs > 0,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color(0xFF5B9DFF),
                inactiveTrackColor = Color(0x66FFFFFF),
            ),
            modifier = Modifier.weight(1f),
        )
        Text(
            if (lengthMs > 0) formatTime(lengthMs) else "--:--",
            color = Color.White,
            fontSize = 13.sp,
            modifier = Modifier.width(64.dp),
            textAlign = TextAlign.Center,
        )
    }
}

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}

private fun langLabel(code: String): String = when (code) {
    "ko" -> "한국어"
    "ja" -> "일본어"
    "zh" -> "중국어"
    "en" -> "영어"
    else -> code
}
