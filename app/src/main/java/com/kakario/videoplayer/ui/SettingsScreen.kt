package com.kakario.videoplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kakario.videoplayer.data.ThemeMode
import com.kakario.videoplayer.data.ThumbTime
import com.kakario.videoplayer.data.AutoNext
import com.kakario.videoplayer.thumb.LocalVideoThumbs
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import com.kakario.videoplayer.data.SubtitleSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    gridColumns: Int,
    onGridColumnsChange: (Int) -> Unit,
    subtitleSize: SubtitleSize,
    onSubtitleSizeChange: (SubtitleSize) -> Unit,
    resumePlayback: Boolean,
    onResumeChange: (Boolean) -> Unit,
    skipSeconds: Int,
    onSkipSecondsChange: (Int) -> Unit,
    thumbTime: ThumbTime,
    onThumbTimeChange: (ThumbTime) -> Unit,
    ftpThumbs: Boolean,
    onFtpThumbsChange: (Boolean) -> Unit,
    thumbsOnMobile: Boolean,
    onThumbsOnMobileChange: (Boolean) -> Unit,
    autoNext: AutoNext,
    onAutoNextChange: (AutoNext) -> Unit,
    pip: Boolean,
    onPipChange: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "?"
    }

    Column(Modifier.fillMaxSize()) {
        BrowserHeader(title = "설정", subtitle = null, onBack = onClose)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // ── 화면 테마
            SectionTitle("화면 테마")
            ThemeMode.entries.forEach { mode ->
                RadioRow(mode.label, selected = mode == themeMode) { onThemeChange(mode) }
            }

            Divider()

            // ── 목록 크기
            SectionTitle("목록 썸네일 크기")
            var sliderValue by remember { mutableFloatStateOf(gridColumns.toFloat()) }
            val cols = sliderValue.roundToInt()
            Text(
                "한 줄에 영상 ${cols}개" + when (cols) {
                    1 -> " (가장 크게)"
                    4 -> " (가장 작게)"
                    else -> ""
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Row(
                Modifier.padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("크게", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = { onGridColumnsChange(sliderValue.roundToInt()) },
                    valueRange = 1f..4f,
                    steps = 2,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Text("작게", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            GridPreview(cols)

            Divider()

            // ── 재생
            SectionTitle("재생")
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = resumePlayback, onValueChange = onResumeChange)
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("이어보기", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        "다시 열면 보던 위치부터 재생 (화면에 '처음부터' 버튼이 잠깐 떠요)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = resumePlayback, onCheckedChange = null)
            }
            Text(
                "두 번 탭·버튼으로 건너뛰는 시간",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Row(Modifier.padding(horizontal = 12.dp)) {
                listOf(5, 10, 15, 30).forEach { sec ->
                    Row(
                        Modifier
                            .selectable(selected = sec == skipSeconds, onClick = { onSkipSecondsChange(sec) })
                            .padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = sec == skipSeconds, onClick = { onSkipSecondsChange(sec) })
                        Text("${sec}초", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                    }
                }
            }

            Divider()

            // ── 자막
            SectionTitle("자막 크기")
            SubtitleSize.entries.forEach { size ->
                RadioRow(size.label, selected = size == subtitleSize) { onSubtitleSizeChange(size) }
            }
            Text(
                "다음에 영상을 열 때부터 적용돼요. 영상과 같은 이름의 .smi / .srt / .ass 자막은 자동으로 붙어요",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )

            Divider()

            // ── 다 봤을 때 / 작은 창
            SectionTitle("영상이 끝났을 때")
            AutoNext.entries.forEach { a ->
                RadioRow(a.label, selected = a == autoNext) { onAutoNextChange(a) }
            }
            SettingSwitch(
                title = "홈 버튼 누르면 작은 창으로 계속 보기",
                subtitle = "재생 중에 홈으로 나가면 화면 구석의 작은 창(PIP)에서 이어서 재생돼요",
                checked = pip,
                onChange = onPipChange,
            )

            Divider()

            // ── 썸네일
            SectionTitle("썸네일")
            Text(
                "썸네일로 쓸 장면",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            ThumbTime.entries.forEach { t ->
                RadioRow(t.label, selected = t == thumbTime) { onThumbTimeChange(t) }
            }
            Text(
                "장면이 너무 어두우면 자동으로 조금 뒤 장면을 골라요. 바꾼 시점은 새로 만드는 썸네일부터 적용돼요 (기존 것은 길게 눌러 '다시 만들기')",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            SettingSwitch(
                title = "FTP 영상도 썸네일 자동으로 만들기",
                subtitle = "영상 전체가 아니라 필요한 부분만 조금 받아서 만들어요",
                checked = ftpThumbs,
                onChange = onFtpThumbsChange,
            )
            SettingSwitch(
                title = "모바일 데이터(LTE·5G)에서도 만들기",
                subtitle = "끄면 와이파이일 때만 FTP 썸네일을 만들어요",
                checked = thumbsOnMobile,
                onChange = onThumbsOnMobileChange,
                enabled = ftpThumbs,
            )
            val thumbs = LocalVideoThumbs.current
            var cacheBytes by remember { mutableLongStateOf(thumbs?.diskBytes() ?: 0L) }
            var confirmClear by remember { mutableIntStateOf(0) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("저장된 썸네일", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                    Text(formatBytes(cacheBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { confirmClear = 1 }, enabled = cacheBytes > 0) { Text("지우기") }
            }
            if (confirmClear > 0) {
                AlertDialog(
                    onDismissRequest = { confirmClear = 0 },
                    title = { Text(if (confirmClear == 1) "썸네일 지우기" else "정말 지울까요?") },
                    text = {
                        Text(
                            if (confirmClear == 1) "저장된 썸네일 ${formatBytes(cacheBytes)}를 지울까요?\n(영상 파일은 지워지지 않아요)"
                            else "FTP 목록은 썸네일을 다시 받아오느라 처음 열 때 느려질 수 있어요."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (confirmClear == 1) {
                                confirmClear = 2
                            } else {
                                thumbs?.clear()
                                cacheBytes = thumbs?.diskBytes() ?: 0L
                                confirmClear = 0
                            }
                        }) {
                            Text(
                                if (confirmClear == 1) "다음" else "네, 지울게요",
                                color = if (confirmClear == 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    dismissButton = { TextButton(onClick = { confirmClear = 0 }) { Text("취소") } },
                )
            }

            Divider()

            SectionTitle("앱 정보")
            Text(
                "비디오플레이어 버전 $version",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 설정한 개수대로 한 줄 미리보기 (16:9). */
@Composable
private fun GridPreview(columns: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(columns) {
            Box(
                Modifier
                    .weight(1f)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.surfaceVariant)
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, onValueChange = onChange)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground.copy(alpha = alpha))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
