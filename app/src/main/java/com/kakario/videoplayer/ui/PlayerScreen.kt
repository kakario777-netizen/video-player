package com.kakario.videoplayer.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kakario.videoplayer.ftp.FtpStreamServer
import com.kakario.videoplayer.model.FileEntry
import com.kakario.videoplayer.model.MediaOrigin
import kotlinx.coroutines.delay
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

/**
 * 영상 재생 화면 (VLC 엔진).
 * - 가운데 탭: 조작 버튼 보이기/숨기기
 * - 왼쪽/오른쪽 두 번 탭: 10초 뒤로/앞으로
 * - 아래 막대: 원하는 위치로 이동
 */
@Composable
fun PlayerScreen(
    origin: MediaOrigin,
    entry: FileEntry,
    nextEntry: FileEntry?,
    onOpenNext: (FileEntry) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity

    // ── 전체 화면 + 가로 + 화면 꺼짐 방지 (닫으면 원래대로)
    DisposableEffect(activity) {
        val window = activity?.window
        val oldOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.requestedOrientation = oldOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // ── VLC 엔진 (화면이 닫히면 정리)
    val libVlc = remember {
        LibVLC(
            context.applicationContext,
            arrayListOf(
                "--network-caching=2000",   // 2초 미리 받기 (외부망 끊김 완화)
                "--file-caching=500",
                "--subsdec-encoding=CP949", // 한국어 SMI/SRT 자막이 깨지지 않게
                "--audio-time-stretch",     // 배속 재생 때 음 높이 유지
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
    var seekHint by remember { mutableStateOf<String?>(null) }
    var seekHintKey by remember { mutableIntStateOf(0) }

    DisposableEffect(player) {
        player.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> { playing = true; buffering = false; ended = false; error = null }
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> playing = false
                MediaPlayer.Event.Buffering -> buffering = e.buffering < 100f
                MediaPlayer.Event.TimeChanged -> { timeMs = e.timeChanged; buffering = false }
                MediaPlayer.Event.LengthChanged -> lengthMs = e.lengthChanged
                MediaPlayer.Event.EndReached -> { playing = false; ended = true; controlsVisible = true }
                MediaPlayer.Event.EncounteredError -> {
                    playing = false
                    buffering = false
                    error = "영상을 재생하지 못했어요.\n서버 연결 또는 파일 형식을 확인해 주세요."
                }
            }
        }
        onDispose {
            player.setEventListener(null)
            player.stop()
            player.detachViews()
            player.release()
            libVlc.release()
        }
    }

    // 앱이 뒤로 가거나 화면이 꺼지면 일시정지
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        if (player.isPlaying) player.pause()
        controlsVisible = true
    }

    // ── 영상 열기 (다음 화로 바뀌면 다시)
    DisposableEffect(entry.path) {
        val server = origin.server
        val url = if (server != null) FtpStreamServer.urlFor(server, entry.path, entry.size) else null
        val media = if (url != null) Media(libVlc, Uri.parse(url)) else Media(libVlc, Uri.fromFile(File(entry.path)))
        media.setHWDecoderEnabled(true, false)
        player.media = media
        media.release()
        timeMs = 0; lengthMs = 0; ended = false; error = null; buffering = true
        player.play()
        onDispose { url?.let { FtpStreamServer.release(it) } }
    }

    // ── 재생 중이면 3초 뒤 조작 버튼 숨기기
    LaunchedEffect(controlsVisible, playing, interaction) {
        if (controlsVisible && playing) {
            delay(3000)
            controlsVisible = false
        }
    }
    LaunchedEffect(seekHintKey) {
        if (seekHint != null) {
            delay(700)
            seekHint = null
        }
    }

    fun seekBy(deltaMs: Long) {
        val target = (player.time + deltaMs).coerceIn(0, if (lengthMs > 0) lengthMs else Long.MAX_VALUE)
        player.time = target
        timeMs = target
        seekHint = if (deltaMs > 0) "10초 ▶▶" else "◀◀ 10초"
        seekHintKey++
        interaction++
    }

    fun togglePlay() {
        if (ended) {
            player.time = 0
            player.play()
        } else if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
        interaction++
    }

    BackHandler(onBack = onClose)

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

        // 탭 영역: 가운데 한 번 = 버튼 보이기/숨기기, 양옆 두 번 = 10초 이동
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            controlsVisible = !controlsVisible
                            interaction++
                        },
                        onDoubleTap = { pos ->
                            when {
                                pos.x < size.width / 3f -> seekBy(-10_000)
                                pos.x > size.width * 2f / 3f -> seekBy(10_000)
                                else -> togglePlay()
                            }
                        },
                    )
                }
        )

        if (buffering && error == null && !ended) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }

        seekHint?.let {
            Text(
                it,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0x99000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }

        // ── 조작 버튼들
        AnimatedVisibility(
            visible = controlsVisible || error != null || ended,
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
                // 위: 뒤로 + 제목
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
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
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
                        Spacer(Modifier.size(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { togglePlay() }) { Text("처음부터", color = Color.White) }
                            if (nextEntry != null) {
                                Button(onClick = { onOpenNext(nextEntry) }) {
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
                        RoundButton("⟲10", 52) { seekBy(-10_000) }
                        RoundButton(if (playing) "❚❚" else "▶", 72) { togglePlay() }
                        RoundButton("10⟳", 52) { seekBy(10_000) }
                    }
                }

                // 아래: 시간 + 이동 막대
                SeekBar(
                    timeMs = timeMs,
                    lengthMs = lengthMs,
                    onSeek = { ms ->
                        player.time = ms
                        timeMs = ms
                        interaction++
                    },
                    onDragging = { interaction++ },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
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
    modifier: Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val fraction = if (lengthMs > 0) (timeMs.toFloat() / lengthMs).coerceIn(0f, 1f) else 0f
    val shown = if (dragging) dragValue else fraction

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
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

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}
