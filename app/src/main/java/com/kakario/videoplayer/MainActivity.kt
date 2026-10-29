package com.kakario.videoplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.kakario.videoplayer.data.Favorite
import com.kakario.videoplayer.data.FavoriteStore
import com.kakario.videoplayer.data.FtpServer
import com.kakario.videoplayer.data.ServerStore
import com.kakario.videoplayer.data.SettingsStore
import com.kakario.videoplayer.data.ThemeMode
import com.kakario.videoplayer.ftp.FtpBrowser
import com.kakario.videoplayer.model.FileEntry
import com.kakario.videoplayer.model.MediaOrigin
import com.kakario.videoplayer.model.folderName
import com.kakario.videoplayer.ui.FavoritesScreen
import com.kakario.videoplayer.ui.FtpLocation
import com.kakario.videoplayer.ui.FtpTab
import com.kakario.videoplayer.ui.LocalGridColumns
import com.kakario.videoplayer.ui.LocalTab
import com.kakario.videoplayer.ui.PlayerScreen
import com.kakario.videoplayer.ui.SettingsScreen
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

val DarkColors = darkColorScheme(
    primary = Color(0xFF5B9DFF),
    onPrimary = Color.White,
    background = Color(0xFF121418),
    surface = Color(0xFF1C1F25),
    surfaceVariant = Color(0xFF252931),
    onBackground = Color(0xFFECECEC),
    onSurface = Color(0xFFECECEC),
    onSurfaceVariant = Color(0xFFB5B9C0),
    error = Color(0xFFFF8A80),
)

val LightColors = lightColorScheme(
    primary = Color(0xFF1F6FE5),
    onPrimary = Color.White,
    background = Color(0xFFF7F7F8),
    surface = Color.White,
    surfaceVariant = Color(0xFFE9EBEE),
    onBackground = Color(0xFF1A1C1F),
    onSurface = Color(0xFF1A1C1F),
    onSurfaceVariant = Color(0xFF5F6368),
    error = Color(0xFFC62828),
)

/** 재생할 영상과, 같은 폴더의 영상 목록(다음 화 찾기용). */
data class OpenVideo(
    val origin: MediaOrigin,
    val entry: FileEntry,
    val siblings: List<FileEntry>,
) {
    val next: FileEntry?
        get() {
            val i = siblings.indexOfFirst { it.path == entry.path }
            return if (i >= 0) siblings.getOrNull(i + 1) else null
        }
}

private val TABS = listOf("⭐ 즐겨찾기", "🗄️ FTP", "📱 내 폰")
private const val TAB_FAVORITES = 0
private const val TAB_FTP = 1
private const val TAB_LOCAL = 2

class MainActivity : ComponentActivity() {

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val serverStore = ServerStore(applicationContext)
        val favoriteStore = FavoriteStore(applicationContext)
        val settingsStore = SettingsStore(applicationContext)

        setContent {
            var themeMode by remember { mutableStateOf(settingsStore.themeMode) }
            var gridColumns by remember { mutableIntStateOf(settingsStore.gridColumns) }
            val dark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // 상태바·내비게이션바 아이콘 색도 테마에 맞춘다
            DisposableEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }

            MaterialTheme(colorScheme = if (dark) DarkColors else LightColors) {
              CompositionLocalProvider(LocalGridColumns provides gridColumns) {
                var settingsOpen by remember { mutableStateOf(false) }
                val servers = remember { mutableStateListOf<FtpServer>().apply { addAll(serverStore.load()) } }
                val favorites = remember { mutableStateListOf<Favorite>().apply { addAll(favoriteStore.load()) } }
                var tab by remember { mutableIntStateOf(TAB_FAVORITES) }

                // 각 탭의 현재 위치 — 탭을 옮겨 다녀도 그대로 남는다.
                var ftpLocation by remember { mutableStateOf<FtpLocation?>(null) }
                var localPath by remember { mutableStateOf<String?>(null) }

                // 열려 있는 서버의 FTP 연결 (서버가 바뀌거나 닫히면 끊는다)
                val openServer = ftpLocation?.server
                val ftpBrowser = remember(openServer) { openServer?.let { FtpBrowser(it) } }
                DisposableEffect(ftpBrowser) {
                    onDispose { ftpBrowser?.let { b -> GlobalScope.launch { b.close() } } }
                }

                fun isFavorite(serverId: Long?, path: String) =
                    favorites.any { it.matches(serverId, path) }

                fun toggleFavorite(serverId: Long?, path: String) {
                    val existing = favorites.firstOrNull { it.matches(serverId, path) }
                    if (existing != null) {
                        favorites.remove(existing)
                    } else {
                        favorites.add(Favorite(System.currentTimeMillis(), serverId, path, folderName(path)))
                    }
                    favoriteStore.save(favorites)
                }

                // 지금 재생 중인 영상 (null이면 재생 화면 닫힘)
                var playing by remember { mutableStateOf<OpenVideo?>(null) }

                fun openVideo(video: OpenVideo) {
                    playing = video
                }

                if (settingsOpen) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .windowInsetsPadding(WindowInsets.systemBars)
                    ) {
                        SettingsScreen(
                            themeMode = themeMode,
                            onThemeChange = {
                                themeMode = it
                                settingsStore.themeMode = it
                            },
                            gridColumns = gridColumns,
                            onGridColumnsChange = {
                                gridColumns = it
                                settingsStore.gridColumns = it
                            },
                            onClose = { settingsOpen = false },
                        )
                    }
                } else {
                Box(Modifier.fillMaxSize()) {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        // 상태바(시간·아이콘) 바로 아래에 탭 + 오른쪽 끝 설정 버튼
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background)
                                .windowInsetsPadding(WindowInsets.statusBars),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TabRow(
                                selectedTabIndex = tab,
                                modifier = Modifier.weight(1f),
                                containerColor = MaterialTheme.colorScheme.background,
                                contentColor = MaterialTheme.colorScheme.primary,
                                divider = {},
                            ) {
                                TABS.forEachIndexed { i, label ->
                                    Tab(
                                        selected = tab == i,
                                        onClick = { tab = i },
                                        text = { Text(label, maxLines = 1) },
                                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            IconButton(onClick = { settingsOpen = true }) {
                                Icon(
                                    Icons.Filled.Settings,
                                    contentDescription = "설정",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                ) { padding ->
                    Box(Modifier.padding(padding).fillMaxSize()) {
                        when (tab) {
                            TAB_FAVORITES -> FavoritesScreen(
                                favorites = favorites,
                                servers = servers,
                                onOpen = { fav ->
                                    if (fav.isLocal) {
                                        localPath = fav.path
                                        tab = TAB_LOCAL
                                    } else {
                                        val server = servers.firstOrNull { it.id == fav.serverId }
                                        if (server != null) {
                                            // 같은 서버가 이미 열려 있으면 연결을 재사용, root도 유지
                                            val keepRoot = ftpLocation?.takeIf { it.server.id == server.id }?.root
                                            ftpLocation = FtpLocation(server, fav.path, keepRoot)
                                            tab = TAB_FTP
                                        }
                                    }
                                },
                                onRemove = { fav ->
                                    favorites.removeAll { it.id == fav.id }
                                    favoriteStore.save(favorites)
                                },
                            )

                            TAB_FTP -> FtpTab(
                                servers = servers,
                                location = ftpLocation,
                                browser = ftpBrowser,
                                onLocationChange = { ftpLocation = it },
                                onOpenVideo = { entry, siblings ->
                                    ftpLocation?.let { openVideo(OpenVideo(MediaOrigin(it.server), entry, siblings)) }
                                },
                                onSaveServer = { server ->
                                    val idx = servers.indexOfFirst { it.id == server.id }
                                    if (idx >= 0) servers[idx] = server else servers.add(server)
                                    serverStore.save(servers)
                                },
                                onDeleteServer = { server ->
                                    servers.removeAll { it.id == server.id }
                                    serverStore.save(servers)
                                    if (favorites.removeAll { it.serverId == server.id }) {
                                        favoriteStore.save(favorites)
                                    }
                                },
                                isFavorite = { id, path -> isFavorite(id, path) },
                                onToggleFavorite = { id, path -> toggleFavorite(id, path) },
                            )

                            TAB_LOCAL -> LocalTab(
                                path = localPath,
                                onPathChange = { localPath = it },
                                onOpenVideo = { entry, siblings -> openVideo(OpenVideo(MediaOrigin(null), entry, siblings)) },
                                isFavorite = { isFavorite(null, it) },
                                onToggleFavorite = { toggleFavorite(null, it) },
                            )
                        }
                    }
                }
                // 재생 화면은 목록 위에 덮어서 띄운다 (닫으면 보던 목록 그대로)
                playing?.let { video ->
                    PlayerScreen(
                        origin = video.origin,
                        entry = video.entry,
                        nextEntry = video.next,
                        onOpenNext = { next -> playing = video.copy(entry = next) },
                        onClose = { playing = null },
                    )
                }
                }
                } // else (설정 화면이 아닐 때)
              }
            }
        }
    }
}
