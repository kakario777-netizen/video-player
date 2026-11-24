package com.kakario.videoplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kakario.videoplayer.data.FtpServer

@Composable
fun ServerListScreen(
    servers: List<FtpServer>,
    onOpen: (FtpServer) -> Unit,
    onSave: (FtpServer) -> Unit,
    onDelete: (FtpServer) -> Unit,
) {
    // null = 창 닫힘, id 0 = 새 서버, 그 외 = 수정 중인 서버
    var editing by remember { mutableStateOf<FtpServer?>(null) }
    var deleting by remember { mutableStateOf<FtpServer?>(null) }
    var deleteStep by remember { mutableStateOf(1) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { com.kakario.videoplayer.data.SettingsStore(context) }
    var grid by remember { mutableStateOf(store.serverGrid) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // 바깥 화면이 이미 상태바·내비게이션바 여백을 처리한다
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = NEW_SERVER },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("FTP 서버 추가") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        if (servers.isEmpty()) {
            EmptyServers(Modifier.padding(padding))
        } else {
            Column(Modifier.padding(padding)) {
                // 오른쪽 위 보기 전환 (윈도우 탐색기처럼): 누를 때마다 격자 ↔ 목록
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "FTP 서버 ${servers.size}개",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        grid = !grid
                        store.serverGrid = grid
                    }) {
                        LineIcon(if (grid) LineIcons.ListView else LineIcons.Grid, size = 22.dp)
                    }
                }
                if (grid) {
                    // 1:1 칸. 폭에 따라 폴드(펼침)는 3칸, 일반 폰은 2칸 정도
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val cols = (maxWidth / 200.dp).toInt().coerceIn(2, 4)
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(cols),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            gridItems(servers, key = { it.id }) { server ->
                                ServerTile(
                                    server = server,
                                    onClick = { onOpen(server) },
                                    onEdit = { editing = server },
                                    onDelete = { deleting = server; deleteStep = 1 },
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(servers, key = { it.id }) { server ->
                            ServerCard(
                                server = server,
                                onClick = { onOpen(server) },
                                onEdit = { editing = server },
                                onDelete = { deleting = server; deleteStep = 1 },
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { target ->
        ServerDialog(
            initial = target,
            onDismiss = { editing = null },
            onSave = {
                onSave(it)
                editing = null
            },
        )
    }

    // 삭제는 두 번 물어본다
    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(if (deleteStep == 1) "서버 삭제" else "정말 지울까요?") },
            text = {
                Text(
                    if (deleteStep == 1) "'${target.name}' 서버를 목록에서 지울까요?"
                    else "'${target.name}' 서버 정보(주소·아이디·비밀번호)가 지워져요. 서버의 파일은 그대로예요."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (deleteStep == 1) deleteStep = 2 else {
                        onDelete(target)
                        deleting = null
                    }
                }) {
                    Text(
                        if (deleteStep == 1) "다음" else "네, 지울게요",
                        color = if (deleteStep == 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } },
        )
    }
}

private val NEW_SERVER = FtpServer(0, "", "", 21, "", "", "UTF-8")

@Composable
private fun EmptyServers(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LineIcon(LineIcons.Server, size = 56.dp)
            Spacer(Modifier.height(12.dp))
            Text(
                "등록된 FTP 서버가 없어요",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "아래 버튼으로 FTP 서버를 추가해 보세요",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ServerCard(
    server: FtpServer,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LineIcon(LineIcons.Server, size = 30.dp)
            Spacer(Modifier.padding(start = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(server.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${server.host}:${server.port}  ·  ${server.user.ifBlank { "익명" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit) { LineIcon(LineIcons.Pencil, size = 22.dp) }
            IconButton(onClick = onDelete) { LineIcon(LineIcons.Trash, size = 22.dp, tint = MaterialTheme.colorScheme.error) }
        }
    }
}

/** 격자 보기의 서버 한 칸 (정사각형). 오른쪽 아래 수정·휴지통. */
@Composable
private fun ServerTile(
    server: FtpServer,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 10.dp).padding(bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LineIcon(LineIcons.Server, size = 44.dp)
                Spacer(Modifier.height(8.dp))
                Text(
                    server.name.ifBlank { server.host },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    server.host,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Row(Modifier.align(Alignment.BottomEnd)) {
                IconButton(onClick = onEdit) { LineIcon(LineIcons.Pencil, size = 20.dp) }
                IconButton(onClick = onDelete) { LineIcon(LineIcons.Trash, size = 20.dp, tint = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun ServerDialog(
    initial: FtpServer,
    onDismiss: () -> Unit,
    onSave: (FtpServer) -> Unit,
) {
    val isNew = initial.id == 0L
    var name by remember { mutableStateOf(initial.name) }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var user by remember { mutableStateOf(initial.user) }
    var password by remember { mutableStateOf(initial.password) }
    var encoding by remember { mutableStateOf(initial.encoding) }
    var secure by remember { mutableStateOf(initial.secure) }

    val canSave = host.isNotBlank() && (port.toIntOrNull() ?: 0) in 1..65535

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "FTP 서버 추가" else "서버 정보 수정") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("이름 (예: 우리집 NAS)") }, singleLine = true,
                )
                OutlinedTextField(
                    value = host, onValueChange = { host = it.trim() },
                    label = { Text("주소 (외부 IP 또는 DDNS)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("포트") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = user, onValueChange = { user = it },
                    label = { Text("아이디 (비우면 익명)") }, singleLine = true,
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("비밀번호") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Text(
                    "한글 파일명 인코딩",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 4.dp),
                )
                EncodingOption("UTF-8 (기본)", selected = encoding == "UTF-8") { encoding = "UTF-8" }
                EncodingOption("EUC-KR (한글이 깨질 때)", selected = encoding == "EUC-KR") { encoding = "EUC-KR" }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { secure = !secure }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Checkbox(checked = secure, onCheckedChange = { secure = it })
                    Column {
                        Text("암호화 연결 (FTPS)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "서버가 지원하면 켜 두세요 — 비밀번호와 영상이 암호화돼서 오가요",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        FtpServer(
                            id = if (isNew) System.currentTimeMillis() else initial.id,
                            name = name.ifBlank { host },
                            host = host,
                            port = port.toInt(),
                            user = user.trim(),
                            password = password,
                            encoding = encoding,
                            secure = secure,
                        )
                    )
                },
            ) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun EncodingOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
