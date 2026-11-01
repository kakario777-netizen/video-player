package com.kakario.videoplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kakario.videoplayer.model.FileEntry
import com.kakario.videoplayer.data.SortOrder
import com.kakario.videoplayer.ai.AiBatch
import androidx.compose.material3.OutlinedButton
import com.kakario.videoplayer.thumb.LocalVideoThumbs
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.border
import com.kakario.videoplayer.data.PlaybackStore
import androidx.compose.ui.platform.LocalContext
import com.kakario.videoplayer.model.MediaOrigin
import com.kakario.videoplayer.model.UserFacingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private sealed interface LoadState {
    data object Loading : LoadState
    data class Loaded(val entries: List<FileEntry>) : LoadState
    data class Failed(val message: String) : LoadState
}

/**
 * FTP와 내 폰이 함께 쓰는 영상 목록(폴더 탐색) 화면.
 * 지금 보고 있는 폴더(path)는 바깥에서 관리해서, 탭을 옮겨 다녀도 위치가 유지된다.
 */
@Composable
fun FolderBrowser(
    title: String,
    path: String,
    origin: MediaOrigin,
    load: suspend (String) -> List<FileEntry>,
    onUp: () -> Unit,
    onOpenDir: (String) -> Unit,
    /** 영상을 열 때: 누른 영상과, 같은 폴더의 영상 목록(다음 화 찾기용). */
    onOpenVideo: (entry: FileEntry, siblings: List<FileEntry>) -> Unit,
    isFavorite: (String) -> Boolean,
    onToggleFavorite: (path: String) -> Unit,
    /** 파일 삭제 (길게 누르기 메뉴). */
    delete: suspend (FileEntry) -> Unit,
) {
    var state by remember { mutableStateOf<LoadState>(LoadState.Loading) }
    var reloadKey by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 길게 누르기 메뉴 / 삭제 두 번 확인 (1 = 첫 확인, 2 = 마지막 확인)
    var menuFor by remember { mutableStateOf<FileEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<FileEntry?>(null) }
    var deleteStep by remember { mutableIntStateOf(0) }
    var sortMenu by remember { mutableStateOf(false) }
    var rebuildAsk by remember { mutableStateOf<RebuildAsk?>(null) }
    // 여러 개 고르기: 고른 항목 경로들 (selecting이 켜져 있으면 고르기 모드)
    var selecting by remember(path) { mutableStateOf(false) }
    var selected by remember(path) { mutableStateOf<Set<String>>(emptySet()) }
    val thumbs = LocalVideoThumbs.current
    val context = LocalContext.current
    val playback = PlaybackStore.get(context)

    val currentLoad by rememberUpdatedState(load)
    val currentDelete by rememberUpdatedState(delete)
    LaunchedEffect(path, reloadKey) {
        state = LoadState.Loading
        state = try {
            LoadState.Loaded(currentLoad(path))
        } catch (e: CancellationException) {
            throw e
        } catch (e: UserFacingException) {
            LoadState.Failed(e.message ?: "알 수 없는 오류")
        } catch (e: Exception) {
            LoadState.Failed("예상치 못한 오류: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    BackHandler {
        if (selecting) {
            selecting = false
            selected = emptySet()
        } else {
            onUp()
        }
    }

    fun toast(msg: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(msg)
        }
    }

    fun toggle(p: String) {
        val wasOn = isFavorite(p)
        onToggleFavorite(p)
        toast(if (wasOn) "즐겨찾기에서 뺐어요" else "즐겨찾기에 추가했어요 — 첫 탭에서 바로 열 수 있어요")
    }

    val loaded = (state as? LoadState.Loaded)?.entries

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
          if (selecting) {
            val all = loaded?.filter { it.isDirectory || it.isVideo }.orEmpty()
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { selecting = false; selected = emptySet() }) { Text("취소") }
                Text(
                    "${selected.size}개 선택",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                TextButton(onClick = {
                    selected = if (selected.size == all.size) emptySet() else all.map { it.path }.toSet()
                }) { Text(if (all.isNotEmpty() && selected.size == all.size) "모두 해제" else "모두 선택") }
            }
          } else {
            BrowserHeader(
                title = title,
                // 영상·폴더 개수를 앞에, 경로는 뒤에 (길면 뒤가 잘림)
                subtitle = loaded?.let { list ->
                    val files = list.count { it.isVideo }
                    val dirs = list.count { it.isDirectory }
                    buildString {
                        append("영상 ${files}개")
                        if (dirs > 0) append(" · 폴더 ${dirs}개")
                        append("  ·  ")
                        append(path)
                    }
                } ?: path,
                onBack = onUp,
                actions = {
                    val sort = LocalSort.current
                    TextButton(onClick = { sortMenu = true }) {
                        Text(sort.order.label, style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = { selecting = true }) {
                        Text("선택", style = MaterialTheme.typography.labelMedium)
                    }
                    StarButton(on = isFavorite(path), onClick = { toggle(path) })
                    IconButton(onClick = { reloadKey++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "새로고침")
                    }
                },
            )
          }

            val job = thumbs?.job?.collectAsState()?.value
            if (job != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "'${job.folder}' 썸네일 만드는 중 ${job.done}/${job.total}" +
                            if (job.waiting > 0) " · 대기 ${job.waiting}" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { thumbs?.stopFolder() }) { Text("중지") }
                }
            }
            val aiJob = AiBatch.status.collectAsState().value
            if (aiJob != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        buildString {
                            append(if (aiJob.paused) "AI 자막 잠시 멈춤 · " else "AI 자막 ${aiJob.percent}% · ")
                            append(aiJob.current)
                            if (aiJob.waiting > 0) append(" · 대기 ${aiJob.waiting}")
                            if (aiJob.doneCount > 0) append(" · 완료 ${aiJob.doneCount}")
                            if (aiJob.skipCount > 0) append(" · 건너뜀 ${aiJob.skipCount}")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { AiBatch.stopAll() }) { Text("중지") }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val s = state) {
                    LoadState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                    is LoadState.Failed -> ErrorView(
                        message = s.message,
                        onRetry = { reloadKey++ },
                        modifier = Modifier.align(Alignment.Center),
                    )

                    is LoadState.Loaded -> {
                        val sort = LocalSort.current
                        val visible = remember(s.entries, sort.order, sort.foldersFirst) {
                            val dirs = s.entries.filter { it.isDirectory }
                            val videos = s.entries.filter { it.isVideo }
                            val sorted = when (sort.order) {
                                SortOrder.NAME -> videos
                                SortOrder.NEWEST -> videos.sortedByDescending { it.modified }
                                SortOrder.LARGEST -> videos.sortedByDescending { it.size }
                            }
                            if (sort.foldersFirst) dirs + sorted else sorted + dirs
                        }
                        if (visible.isEmpty()) {
                            Text(
                                "이 폴더에는 하위 폴더나 영상 파일이 없어요",
                                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(LocalGridColumns.current),
                                contentPadding = PaddingValues(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                items(visible, key = { it.path }) { entry ->
                                    VideoTile(
                                        subtitleTag = if (entry.isVideo) subtitleTag(context, origin, entry, s.entries, playback.version) else null,
                                        origin = origin,
                                        entry = entry,
                                        favorite = entry.isDirectory && isFavorite(entry.path),
                                        onToggleFavorite = { toggle(entry.path) },
                                        selection = if (selecting) entry.path in selected else null,
                                        onClick = {
                                            when {
                                                selecting -> selected =
                                                    if (entry.path in selected) selected - entry.path else selected + entry.path
                                                entry.isDirectory -> onOpenDir(entry.path)
                                                else -> onOpenVideo(entry, visible.filter { it.isVideo })
                                            }
                                        },
                                        onLongClick = {
                                            if (selecting) {
                                                selected = selected + entry.path
                                            } else {
                                                menuFor = entry
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (selecting) {
            val chosen = loaded?.filter { it.path in selected }.orEmpty()
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    buildString {
                        val d = chosen.count { it.isDirectory }
                        val v = chosen.count { it.isVideo }
                        if (d > 0) append("폴더 ${d}개 ")
                        if (v > 0) append("영상 ${v}개")
                        if (d == 0 && v == 0) append("항목을 눌러 고르세요")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    enabled = chosen.isNotEmpty(),
                    onClick = {
                        val folders = chosen.filter { it.isDirectory }.map { d ->
                            val loader: suspend () -> List<FileEntry> = { currentLoad(d.path) }
                            d.name to loader
                        }
                        val videos = chosen.filter { it.isVideo }
                        // 한 개만 고른 영상이면 바로, 여러 개·폴더면 두 번 확인
                        if (folders.isEmpty() && videos.size == 1) {
                            thumbs?.rebuild(origin, emptyList(), videos, force = true)
                            selecting = false
                            selected = emptySet()
                            toast("썸네일을 다시 만들어요")
                        } else {
                            rebuildAsk = RebuildAsk(
                                description = buildString {
                                    if (folders.isNotEmpty()) append("폴더 ${folders.size}개(안의 영상 전부)")
                                    if (folders.isNotEmpty() && videos.isNotEmpty()) append(" + ")
                                    if (videos.isNotEmpty()) append("영상 ${videos.size}개")
                                },
                                folders = folders,
                                videos = videos,
                                step = 1,
                                fromSelection = true,
                            )
                        }
                    },
                ) { Text("썸네일 다시 만들기") }
                OutlinedButton(
                    enabled = chosen.isNotEmpty(),
                    onClick = {
                        if (!AiBatch.ready(context)) {
                            toast("설정 > 자막에서 AI 음성인식 모델을 먼저 받아 주세요")
                        } else {
                            chosen.forEach { e ->
                                if (e.isDirectory) AiBatch.addFolder(context, origin, e.path, e.name)
                                else if (e.isVideo) AiBatch.addFile(context, origin, e)
                            }
                            selecting = false
                            selected = emptySet()
                            toast("AI 자막 미리 만들기를 대기줄에 넣었어요 (한국 영상·자막 있는 것 제외)")
                        }
                    },
                ) { Text("AI 자막") }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = if (selecting) 64.dp else 0.dp))
    }

    // ── 길게 누르기 메뉴
    menuFor?.let { target ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(target.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    if (target.isVideo) {
                        InfoLine("용량", formatBytes(target.size))
                        InfoLine("형식", target.extension.uppercase())
                        InfoLine("위치", if (origin.server != null) "FTP · ${origin.server.name}" else "내 폰")
                        val watched = remember(target, playback.version) { playback.get(playback.keyFor(origin, target)) }
                        val dur = remember(target) { thumbs?.let { t -> t.duration(t.key(origin, target)) } ?: 0L }
                        if (dur > 0) InfoLine("재생 시간", formatTime(dur))
                        if (watched.started) {
                            InfoLine("본 위치", "${formatTime(watched.positionMs)} / ${formatTime(watched.lengthMs)}")
                        }
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        MenuItem("여러 개 선택", icon = LineIcons.Select) {
                            menuFor = null
                            selecting = true
                            selected = setOf(target.path)
                        }
                        MenuItem("AI 자막 미리 만들기", icon = LineIcons.Subtitle) {
                            menuFor = null
                            if (!AiBatch.ready(context)) {
                                toast("설정 > 자막에서 AI 음성인식 모델을 먼저 받아 주세요")
                            } else {
                                AiBatch.addFile(context, origin, target)
                                toast("AI 자막 만들기를 대기줄에 넣었어요 — 위쪽과 알림창에 진행 표시")
                            }
                        }
                        val aiKey = remember(target) { playback.keyFor(origin, target) }
                        val hasAi = remember(target) { com.kakario.videoplayer.ai.AiSubtitleSession.hasCache(context, aiKey) }
                        MenuItem("AI 자막 초기화 (만들던 것 지우고 처음부터)", icon = LineIcons.Eraser, enabled = hasAi) {
                            menuFor = null
                            com.kakario.videoplayer.ai.AiSubtitleSession.clearOne(context, aiKey)
                            toast("만들던 AI 자막을 지웠어요 — 다음에 볼 때 처음부터 다시 만들어요")
                        }
                        MenuItem("썸네일 다시 만들기", icon = LineIcons.Image) {
                            menuFor = null
                            thumbs?.invalidate(origin, target)
                            scope.launch { thumbs?.load(origin, target, force = true) }
                            toast("썸네일을 다시 만들어요")
                        }
                        MenuItem("시청 기록 지우기 (처음부터 보기)", icon = LineIcons.History, enabled = watched.started) {
                            menuFor = null
                            playback.clear(playback.keyFor(origin, target))
                            toast("시청 기록을 지웠어요")
                        }
                        MenuItem("파일 삭제", icon = LineIcons.Trash, color = MaterialTheme.colorScheme.error) {
                            menuFor = null
                            deleteTarget = target
                            deleteStep = 1
                        }
                    } else {
                        MenuItem("여러 개 선택", icon = LineIcons.Select) {
                            menuFor = null
                            selecting = true
                            selected = setOf(target.path)
                        }
                        MenuItem("이 폴더 영상 AI 자막 모두 만들기", icon = LineIcons.Subtitle) {
                            menuFor = null
                            if (!AiBatch.ready(context)) {
                                toast("설정 > 자막에서 AI 음성인식 모델을 먼저 받아 주세요")
                            } else {
                                AiBatch.addFolder(context, origin, target.path, target.name)
                                toast("'${target.name}' AI 자막 만들기를 대기줄에 넣었어요 (한국 영상·자막 있는 것 제외)")
                            }
                        }
                        MenuItem("이 폴더 썸네일 모두 다시 만들기", icon = LineIcons.Refresh) {
                            menuFor = null
                            val loader: suspend () -> List<FileEntry> = { currentLoad(target.path) }
                            rebuildAsk = RebuildAsk(
                                description = "'${target.name}' 폴더 안의 영상 전부",
                                folders = listOf(target.name to loader),
                                videos = emptyList(),
                                step = 1,
                                fromSelection = false,
                            )
                        }
                        MenuItem("이 폴더 썸네일 모두 만들기 (없는 것만)", icon = LineIcons.Image) {
                            menuFor = null
                            scope.launch {
                                val list = try {
                                    currentLoad(target.path).filter { it.isVideo }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    emptyList()
                                }
                                if (list.isEmpty()) {
                                    toast("'${target.name}'에는 영상이 없어요 (하위 폴더는 따로 해주세요)")
                                } else if (thumbs?.buildFolder(origin, target.name, list) == true) {
                                    toast("'${target.name}' 영상 ${list.size}개 썸네일을 만들어요 — 위쪽에 진행 표시")
                                } else {
                                    toast("다른 폴더를 만드는 중이에요. 끝나거나 중지한 뒤 다시 해주세요")
                                }
                            }
                        }
                        val fav = isFavorite(target.path)
                        MenuItem(if (fav) "즐겨찾기에서 빼기" else "즐겨찾기에 추가", icon = if (fav) LineIcons.Star else LineIcons.StarFilled) {
                            menuFor = null
                            toggle(target.path)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { menuFor = null }) { Text("닫기") } },
        )
    }

    // ── 썸네일 여러 개 다시 만들기: 두 번 확인
    rebuildAsk?.let { ask ->
        AlertDialog(
            onDismissRequest = { rebuildAsk = null },
            title = { Text(if (ask.step == 1) "썸네일을 다시 만들까요?" else "정말 다시 만들까요?") },
            text = {
                Text(
                    if (ask.step == 1) "${ask.description}의 썸네일을 지우고 처음부터 다시 만들어요."
                    else "지금 있는 썸네일은 사라지고, 다 만들 때까지 시간이 걸려요" +
                        (if (origin.server != null) " (FTP는 데이터도 써요)." else ".")
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (ask.step == 1) {
                        rebuildAsk = ask.copy(step = 2)
                        return@TextButton
                    }
                    rebuildAsk = null
                    thumbs?.rebuild(origin, ask.folders, ask.videos, force = true)
                    if (ask.fromSelection) {
                        selecting = false
                        selected = emptySet()
                    }
                    toast("썸네일을 다시 만들어요 — 위쪽에 진행 표시")
                }) {
                    Text(
                        if (ask.step == 1) "다음" else "네, 다시 만들게요",
                        color = if (ask.step == 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            },
            dismissButton = { TextButton(onClick = { rebuildAsk = null }) { Text("취소") } },
        )
    }

    // ── 정렬 선택
    if (sortMenu) {
        val sort = LocalSort.current
        AlertDialog(
            onDismissRequest = { sortMenu = false },
            title = { Text("영상 정렬") },
            text = {
                Column {
                    SortOrder.entries.forEach { o ->
                        MenuItem((if (o == sort.order) "●  " else "○  ") + o.label) {
                            sort.onChange(o)
                            sortMenu = false
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                    Text(
                        "폴더 위치",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    MenuItem((if (sort.foldersFirst) "●  " else "○  ") + "폴더를 위로") {
                        sort.onFoldersFirstChange(true)
                        sortMenu = false
                    }
                    MenuItem((if (!sort.foldersFirst) "●  " else "○  ") + "폴더를 아래로") {
                        sort.onFoldersFirstChange(false)
                        sortMenu = false
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { sortMenu = false }) { Text("닫기") } },
        )
    }

    // ── 삭제 두 번 확인
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(if (deleteStep == 1) "파일을 삭제할까요?" else "정말 삭제할까요?") },
            text = {
                Text(
                    if (deleteStep == 1) "'${target.name}'\n파일을 ${if (origin.server != null) "서버" else "폰"}에서 지워요."
                    else "삭제한 파일은 되돌릴 수 없어요."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (deleteStep == 1) {
                        deleteStep = 2
                        return@TextButton
                    }
                    deleteTarget = null
                    scope.launch {
                        try {
                            currentDelete(target)
                            playback.clear(playback.keyFor(origin, target))
                            toast("삭제했어요")
                            reloadKey++
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: UserFacingException) {
                            toast(e.message ?: "삭제하지 못했어요")
                        } catch (e: Exception) {
                            toast("삭제하지 못했어요: ${e.message ?: e.javaClass.simpleName}")
                        }
                    }
                }) {
                    Text(
                        if (deleteStep == 1) "다음" else "네, 삭제할게요",
                        color = if (deleteStep == 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(60.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format("%.2f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format("%.0f KB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

@Composable
private fun MenuItem(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.35f
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            // 선 아이콘 (삭제처럼 빨간 글씨 메뉴는 아이콘도 같은 색)
            val tint = if (color == MaterialTheme.colorScheme.onSurface) LineBlue else color
            LineIcon(icon, size = 22.dp, tint = tint.copy(alpha = alpha))
            Spacer(Modifier.width(14.dp))
        }
        Text(text, color = color.copy(alpha = alpha), style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * 목록의 한 칸 (16:9 가로 화면 비율).
 * 영상: 30초 지점 장면 썸네일 + 형식·재생시간 표시 + 본 만큼 막대.
 * 폴더: 선 폴더 아이콘 + 오른쪽 위 즐겨찾기 별(선택되면 채운 별).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoTile(
    /** 이름 뒤에 파란 글씨로 붙는 (자막) / (AI자막) 표시. */
    subtitleTag: String? = null,
    origin: MediaOrigin,
    entry: FileEntry,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    /** null이면 고르기 모드가 아님, true/false는 골랐는지. */
    selection: Boolean? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val blue = LineBlue
    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(LocalThumbRatio.current)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(if (selection == true) Modifier.border(3.dp, blue, RoundedCornerShape(8.dp)) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (entry.isDirectory) {
                LineIcon(LineIcons.Folder, size = 46.dp)
                if (selection == null) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onToggleFavorite),
                        contentAlignment = Alignment.Center,
                    ) {
                        LineIcon(if (favorite) LineIcons.StarFilled else LineIcons.Star, size = 22.dp)
                    }
                }
            } else {
                ThumbImage(origin, entry, Modifier.fillMaxSize())
                SmallBadge(entry.extension.uppercase(), Modifier.align(Alignment.TopStart))
                // 재생 시간을 알면 시간, 모르면 용량
                val thumbs = LocalVideoThumbs.current
                val key = remember(origin, entry) { thumbs?.key(origin, entry) }
                val stamp = key?.let { thumbs?.stamp(it) } ?: 0
                val duration = remember(key, stamp) { key?.let { thumbs?.duration(it) } ?: 0L }
                SmallBadge(if (duration > 0) formatTime(duration) else formatBytes(entry.size), Modifier.align(Alignment.BottomEnd))
            }
            if (selection != null) {
                // 고르기 모드: 오른쪽 위 동그라미 체크
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(if (selection) blue else Color(0x88000000))
                        .border(2.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selection) LineIcon(LineIcons.Check, size = 16.dp, tint = Color.White)
                }
                WatchBar(origin, entry, Modifier.align(Alignment.BottomCenter))
            }
        }
        Spacer(Modifier.height(6.dp))
        val blue = LineBlue
        Text(
            if (subtitleTag == null) androidx.compose.ui.text.AnnotatedString(entry.name)
            else androidx.compose.ui.text.buildAnnotatedString {
                append(entry.name)
                append(" ")
                pushStyle(androidx.compose.ui.text.SpanStyle(color = blue, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
                append(subtitleTag)
                pop()
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/** 썸네일 아래쪽: 본 만큼 파란 막대, 다 봤으면 '다 봄' 표시. */
@Composable
private fun WatchBar(origin: MediaOrigin, entry: FileEntry, modifier: Modifier) {
    val ctx = LocalContext.current
    val store = remember { PlaybackStore.get(ctx) }
    val version = store.version // 재생 화면을 닫으면 바뀌어서 다시 그린다
    val p = remember(version, entry.path, entry.size) { store.get(store.keyFor(origin, entry)) }
    if (!p.started) return
    Box(modifier.fillMaxWidth()) {
        if (p.finished) {
            Text(
                "다 봄",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(5.dp)
                    .background(Color(0xCC2E7D32), RoundedCornerShape(5.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        } else {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color(0x66000000))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(p.fraction)
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

@Composable
private fun SmallBadge(text: String, modifier: Modifier) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .padding(5.dp)
            .background(Badge, RoundedCornerShape(5.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** 탭 아래에 들어가는 얇은 제목줄 (뒤로 · 제목/경로 · 버튼들). */
@Composable
fun BrowserHeader(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "뒤로",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}

@Composable
fun StarButton(on: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        LineIcon(if (on) LineIcons.StarFilled else LineIcons.Star, size = 24.dp)
    }
}

val StarColor = Color(0xFFFFC94D)

/** 목록 정렬 (설정에 저장, 모든 폴더 공통). */
data class SortState(
    val order: SortOrder,
    val foldersFirst: Boolean = true,
    val onChange: (SortOrder) -> Unit,
    val onFoldersFirstChange: (Boolean) -> Unit = {},
)
val LocalSort = compositionLocalOf { SortState(SortOrder.NAME, true, {}) }

/** 썸네일 칸 가로:세로 비율 (설정에서 1:1 / 4:3 / 16:9). */
val LocalThumbRatio = compositionLocalOf { 16f / 9f }

/** 목록에서 한 줄에 보여줄 영상 개수 (설정에서 바꿈). */
val LocalGridColumns = compositionLocalOf { 2 }
private val Badge = Color(0x99000000)

@Composable
fun ErrorView(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LineIcon(LineIcons.Warning, size = 44.dp, tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("다시 시도") }
    }
}

/** 썸네일 여러 개 다시 만들기 확인 창 내용 (step 1 = 첫 확인, 2 = 마지막 확인). */
private data class RebuildAsk(
    val description: String,
    val folders: List<Pair<String, suspend () -> List<FileEntry>>>,
    val videos: List<FileEntry>,
    val step: Int,
    val fromSelection: Boolean,
)

/**
 * 영상에 붙은 자막 표시 글씨.
 * - 같은 폴더에 자막 파일(.smi .srt …)이 있으면 "(자막)"
 * - 앱이 만든 AI 자막(.ai.srt 파일 또는 앱 안에 만들어 둔 것)이 있으면 "(AI자막)"
 */
@Composable
private fun subtitleTag(
    context: android.content.Context,
    origin: MediaOrigin,
    entry: FileEntry,
    folder: List<FileEntry>,
    version: Int,
): String? = remember(entry.path, folder, version) {
    val subs = com.kakario.videoplayer.model.Subtitles.findFor(entry, folder)
        .filter { it.name.substringBeforeLast('.').lowercase().startsWith(entry.name.substringBeforeLast('.').lowercase()) }
    val hasAiFile = subs.any { it.name.lowercase().contains(".ai.") }
    val hasNormal = subs.any { !it.name.lowercase().contains(".ai.") }
    val key = PlaybackStore.get(context).keyFor(origin, entry)
    val hasAiCache = java.io.File(
        java.io.File(context.filesDir, "aisubs"),
        com.kakario.videoplayer.ai.AiSubtitleSession.sha1(key) + ".json",
    ).exists()
    when {
        hasNormal && (hasAiFile || hasAiCache) -> "(자막)(AI자막)"
        hasNormal -> "(자막)"
        hasAiFile || hasAiCache -> "(AI자막)"
        else -> null
    }
}
