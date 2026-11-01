package com.kakario.videoplayer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kakario.videoplayer.data.Favorite
import com.kakario.videoplayer.data.FtpServer
import com.kakario.videoplayer.data.PlaybackStore
import com.kakario.videoplayer.data.RecentVideo
import com.kakario.videoplayer.model.MediaOrigin
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun FavoritesScreen(
    favorites: List<Favorite>,
    servers: List<FtpServer>,
    onOpen: (Favorite) -> Unit,
    onRemove: (Favorite) -> Unit,
    onPlayRecent: (RecentVideo) -> Unit,
) {
    var removing by remember { mutableStateOf<Favorite?>(null) }
    var removingRecent by remember { mutableStateOf<RecentVideo?>(null) }
    var clearRecentStep by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val playback = remember { PlaybackStore.get(context) }
    val version = playback.version
    // 끝까지 안 본 최근 영상만 (서버가 지워진 것은 빼고)
    val recent = remember(version, servers.size) {
        playback.recent().filter { r ->
            (r.serverId == null || servers.any { it.id == r.serverId }) &&
                !playback.get(playback.keyFor(MediaOrigin(servers.firstOrNull { it.id == r.serverId }), r.toEntry())).finished
        }.take(15)
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (favorites.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 16.dp),
                ) {
                    LineIcon(LineIcons.Star, size = 56.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "즐겨찾기한 폴더가 없어요",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "FTP나 내 폰 탭에서 폴더의 별을 누르면\n여기에 모여서 바로 열 수 있어요",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LineIcon(LineIcons.StarFilled, size = 18.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "즐겨찾기 폴더",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    Text(
                        "누르면 바로 열리고, 길게 누르면 즐겨찾기에서 뺄 수 있어요",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(favorites, key = { it.id }) { fav ->
                val server = servers.firstOrNull { it.id == fav.serverId }
                val source = if (fav.isLocal) "내 폰" else (server?.name ?: "알 수 없는 서버")
                FavoriteTile(
                    name = fav.name,
                    source = source,
                    local = fav.isLocal,
                    onClick = { onOpen(fav) },
                    onLongClick = { removing = fav },
                )
            }
        }
        // 이어서 보기 (즐겨찾기 아래) — 오른쪽 휴지통으로 최근 시청 기록 모두 지우기
        if (recent.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LineIcon(LineIcons.History, size = 18.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "이어서 보기",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f),
                        )
                        androidx.compose.material3.IconButton(onClick = { clearRecentStep = 1 }) {
                            LineIcon(LineIcons.Trash, size = 20.dp)
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowItems(recent, key = { "${it.serverId}|${it.path}" }) { r ->
                            val server = servers.firstOrNull { it.id == r.serverId }
                            RecentTile(
                                origin = MediaOrigin(server),
                                item = r,
                                source = if (r.serverId == null) "내 폰" else (server?.name ?: ""),
                                local = r.serverId == null,
                                onClick = { onPlayRecent(r) },
                                onLongClick = { removingRecent = r },
                            )
                        }
                    }
                }
            }
        }
    }

    if (clearRecentStep > 0) {
        AlertDialog(
            onDismissRequest = { clearRecentStep = 0 },
            title = { Text(if (clearRecentStep == 1) "최근 시청 기록 지우기" else "정말 지울까요?") },
            text = {
                Text(
                    if (clearRecentStep == 1) "이어서 보기 목록을 모두 지울까요?"
                    else "목록만 비워지고, 영상 파일은 그대로예요."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (clearRecentStep == 1) clearRecentStep = 2 else {
                        playback.clearRecent()
                        clearRecentStep = 0
                    }
                }) {
                    Text(
                        if (clearRecentStep == 1) "다음" else "네, 지울게요",
                        color = if (clearRecentStep == 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            },
            dismissButton = { TextButton(onClick = { clearRecentStep = 0 }) { Text("취소") } },
        )
    }

    removingRecent?.let { r ->
        AlertDialog(
            onDismissRequest = { removingRecent = null },
            title = { Text("이어서 보기에서 빼기") },
            text = { Text("'${r.name}'\n목록에서만 빠지고, 본 위치 기록과 파일은 그대로예요.") },
            confirmButton = {
                TextButton(onClick = {
                    playback.removeRecent(r)
                    removingRecent = null
                }) { Text("빼기") }
            },
            dismissButton = { TextButton(onClick = { removingRecent = null }) { Text("취소") } },
        )
    }

    removing?.let { fav ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("즐겨찾기 해제") },
            text = { Text("'${fav.name}' 폴더를 즐겨찾기에서 뺄까요?\n(실제 폴더나 파일은 지워지지 않아요)") },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(fav)
                    removing = null
                }) { Text("빼기") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("취소") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteTile(
    name: String,
    source: String,
    local: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            LineIcon(LineIcons.Folder, size = 52.dp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        SourceLabel(source, local, Modifier.padding(horizontal = 4.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentTile(
    origin: MediaOrigin,
    item: RecentVideo,
    source: String,
    local: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { PlaybackStore.get(context) }
    val entry = remember(item) { item.toEntry() }
    val p = remember(store.version, item) { store.get(store.keyFor(origin, entry)) }
    Column(
        Modifier
            .width(170.dp)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(LocalThumbRatio.current)
                .clip(RoundedCornerShape(8.dp))
        ) {
            ThumbImage(origin, entry, Modifier.fillMaxSize())
            if (p.started) {
                Text(
                    "${formatTime(p.positionMs)} / ${formatTime(p.lengthMs)}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 5.dp, bottom = 8.dp)
                        .background(Color(0x99000000), RoundedCornerShape(5.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
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
        Spacer(Modifier.height(5.dp))
        Text(
            item.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        SourceLabel(source, local, Modifier.padding(horizontal = 2.dp))
    }
}

/** "내 폰" / 서버 이름 앞에 선 아이콘. */
@Composable
private fun SourceLabel(text: String, local: Boolean, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        LineIcon(if (local) LineIcons.Phone else LineIcons.Server, size = 14.dp)
        Spacer(Modifier.width(4.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
