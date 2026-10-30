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
import com.kakario.videoplayer.data.AutoNext
import com.kakario.videoplayer.data.ThumbRatio
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.style.TextAlign
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
    thumbSeconds: Int,
    onThumbSecondsChange: (Int) -> Unit,
    thumbSkipDark: Boolean,
    onThumbSkipDarkChange: (Boolean) -> Unit,
    ftpThumbs: Boolean,
    onFtpThumbsChange: (Boolean) -> Unit,
    thumbsOnMobile: Boolean,
    onThumbsOnMobileChange: (Boolean) -> Unit,
    autoNext: AutoNext,
    onAutoNextChange: (AutoNext) -> Unit,
    pip: Boolean,
    onPipChange: (Boolean) -> Unit,
    thumbRatio: ThumbRatio,
    onThumbRatioChange: (ThumbRatio) -> Unit,
    lockButton: Boolean,
    onLockButtonChange: (Boolean) -> Unit,
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
            // ── 썸네일 비율
            SectionTitle("썸네일 비율")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ThumbRatio.entries.forEach { r ->
                    val on = r == thumbRatio
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .border(
                                if (on) 2.dp else 1.dp,
                                if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(10.dp),
                            )
                            .selectable(selected = on, onClick = { onThumbRatioChange(r) })
                            .padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .height(40.dp)
                                .aspectRatio(r.ratio)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            r.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            }

            Divider()

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
            GridPreview(cols, thumbRatio.ratio)

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
            SectionTitle("자막 · 오프라인 AI 자동 자막")
            AiSubtitleSettings()

            Divider()

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
            SettingSwitch(
                title = "화면 잠금 버튼 사용",
                subtitle = "켜면 재생 화면 위쪽에 🔒 버튼이 생겨요. 누르면 화면 터치가 잠겨요",
                checked = lockButton,
                onChange = onLockButtonChange,
            )

            Divider()

            // ── 썸네일
            SectionTitle("썸네일")
            val thumbs = LocalVideoThumbs.current
            var cacheBytes by remember { mutableLongStateOf(thumbs?.diskBytes() ?: 0L) }
            var confirmClear by remember { mutableIntStateOf(0) }

            // 썸네일 장면 위치: 처음 ~ 2분, 10초 단위
            var thumbSlider by remember { mutableFloatStateOf(thumbSeconds / 10f) }
            val sliderSec = thumbSlider.roundToInt() * 10
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "썸네일로 쓸 장면 위치",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    secondsLabel(sliderSec),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepButton("−") {
                    val v = (thumbSlider.roundToInt() - 1).coerceAtLeast(0)
                    thumbSlider = v.toFloat()
                    onThumbSecondsChange(v * 10)
                }
                Slider(
                    value = thumbSlider,
                    onValueChange = { thumbSlider = it },
                    onValueChangeFinished = { onThumbSecondsChange(thumbSlider.roundToInt() * 10) },
                    valueRange = 0f..12f,
                    steps = 11, // 0, 10, 20 … 120초
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
                StepButton("+") {
                    val v = (thumbSlider.roundToInt() + 1).coerceAtMost(12)
                    thumbSlider = v.toFloat()
                    onThumbSecondsChange(v * 10)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp)) {
                Text("처음", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("1분", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Text("2분", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
            Text(
                "10초 단위로 정할 수 있어요. 이보다 짧은 영상은 가운데 장면을 써요. 바꾼 위치는 새로 만드는 썸네일부터 적용돼요",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            TextButton(
                onClick = { confirmClear = 1 },
                enabled = cacheBytes > 0,
                modifier = Modifier.padding(horizontal = 12.dp),
            ) { Text("기존 썸네일도 이 위치로 모두 다시 만들기") }
            SettingSwitch(
                title = "어두운 장면이면 조금 뒤 장면 찾기",
                subtitle = "페이드·검은 화면이면 15초, 45초 뒤 장면을 자동으로 골라요. 끄면 정한 위치 그대로",
                checked = thumbSkipDark,
                onChange = onThumbSkipDarkChange,
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
private fun GridPreview(columns: Int, ratio: Float) {
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
                    .aspectRatio(ratio)
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

private fun secondsLabel(sec: Int): String = when {
    sec == 0 -> "처음 (0초)"
    sec < 60 -> "${sec}초"
    sec % 60 == 0 -> "${sec / 60}분"
    else -> "${sec / 60}분 ${sec % 60}초"
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        modifier = Modifier.size(40.dp),
    ) { Text(label, style = MaterialTheme.typography.titleMedium) }
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
