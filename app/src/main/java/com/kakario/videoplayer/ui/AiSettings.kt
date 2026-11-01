package com.kakario.videoplayer.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kakario.videoplayer.ai.AiModel
import com.kakario.videoplayer.ai.AiLibs
import com.kakario.videoplayer.ai.AiTranslator
import com.kakario.videoplayer.ai.AiShowMode
import com.kakario.videoplayer.ai.AiSubtitleSession
import com.kakario.videoplayer.ai.LlamaNative
import com.kakario.videoplayer.ai.ModelManager
import com.kakario.videoplayer.ai.ModelState
import com.kakario.videoplayer.ai.WhisperNative
import com.kakario.videoplayer.data.SettingsStore
import kotlinx.coroutines.delay

/**
 * 설정 > 자막 > 오프라인 AI 자동 자막·번역.
 * 모델 받기·지우기와 켜기·끄기. 받은 뒤에는 폰 안에서만 동작한다.
 */
@Composable
fun AiSubtitleSettings() {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val models = remember { ModelManager(context) }
    var enabled by remember { mutableStateOf(store.aiSubtitles) }
    var whisper by remember { mutableStateOf(store.aiWhisperModel) }
    var translate by remember { mutableStateOf(store.aiTranslate) }
    var show by remember { mutableStateOf(store.aiShowMode) }
    var onlyNoSubs by remember { mutableStateOf(store.aiOnlyWithoutSubs) }
    // 다운로드 상태는 1초마다 새로 읽는다
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    var deleteAsk by remember { mutableStateOf<AiModel?>(null) }
    var mobileAsk by remember { mutableStateOf<AiModel?>(null) }
    var dlError by remember { mutableStateOf<String?>(null) }
    var fastCpu by remember { mutableStateOf(store.aiFastCpu) }
    var liveFast by remember { mutableStateOf(store.aiLiveFast) }
    var prewarm by remember { mutableStateOf(store.aiPrewarm) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    // 받기: 와이파이면 바로, 모바일 데이터면 용량 알려주고 물어보기
    fun startDownload(m: AiModel) {
        if (models.onMobileData()) mobileAsk = m else dlError = models.download(m)
        tick++
    }
    var clearAsk by remember { mutableIntStateOf(0) }

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = enabled, onValueChange = {
                    enabled = it
                    store.aiSubtitles = it
                })
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("오프라인 자동 자막·번역 사용", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    "오로지 폰에서만 작동해요 — 서버 연결 전혀 없음.\n영상 소리를 AI가 알아듣고 자막을 만들어요 (모델 받을 때만 인터넷 사용)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = enabled, onCheckedChange = null)
        }

        if (!WhisperNative.available || !LlamaNative.available) {
            Text(
                "이 기기에서 AI 엔진을 불러오지 못했어요",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }

        if (enabled) {
            SubHeader("① 음성 인식 모델 (하나 고르고 받기)")
            AiModel.entries.filter { it.isWhisper }.forEach { m ->
                ModelRow(
                    model = m,
                    state = remember(tick, m) { models.state(m) },
                    selected = whisper == m.name,
                    onSelect = {
                        whisper = m.name
                        store.aiWhisperModel = m.name
                    },
                    onDownload = { startDownload(m) },
                    onCancel = { models.cancel(m); tick++ },
                    onDelete = { deleteAsk = m },
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = liveFast, onValueChange = {
                        liveFast = it
                        store.aiLiveFast = it
                    })
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("볼 때는 빠른 모델(small)로 실시간 자막", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        if (models.isReady(AiModel.WHISPER_SMALL))
                            "큰 모델은 폰에서 재생 속도를 못 따라가요. 보면서는 small, 미리 만들기는 위에서 고른 모델을 써요"
                        else
                            "small 모델을 받아야 켜져요 — 큰 모델만으로는 재생 중 자막이 많이 늦어요",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (models.isReady(AiModel.WHISPER_SMALL)) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = liveFast, onCheckedChange = null)
            }

            SubHeader("② 한국어 번역")
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = translate, onValueChange = {
                        translate = it
                        store.aiTranslate = it
                    })
                    .padding(horizontal = 24.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "외국어 영상은 한국어로 번역",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = translate, onCheckedChange = null)
            }
            if (translate) {
                ModelRow(
                    model = AiModel.QWEN3_4B,
                    state = remember(tick) { models.state(AiModel.QWEN3_4B) },
                    selected = null,
                    onSelect = {},
                    onDownload = { startDownload(AiModel.QWEN3_4B) },
                    onCancel = { models.cancel(AiModel.QWEN3_4B); tick++ },
                    onDelete = { deleteAsk = AiModel.QWEN3_4B },
                )
            }

            // 번역 엔진 미리 준비(예열) 시점
            if (translate) {
                Text(
                    "번역 엔진 미리 준비(예열)",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 10.dp),
                )
                Text(
                    "처음 한 번 30초쯤 준비가 필요해요. 미리 해 두면 영상에서 바로 번역돼요. " +
                        "폰 여유 메모리가 4GB 이상일 때만 하고, 5분 안 쓰면 내려놓아요",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                listOf("APP" to "앱 켤 때 (추천)", "VIDEO" to "영상 틀 때", "OFF" to "미리 안 함 (번역이 필요할 때 준비)").forEach { (k, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = prewarm == k, onClick = {
                                prewarm = k
                                store.aiPrewarm = k
                            })
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = prewarm == k, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                    }
                }
            }

            // 번역 시험: 영상 없이 번역 모델만 돌려서 결과와 걸린 시간을 보여준다
            if (translate && models.isReady(AiModel.QWEN3_4B)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        enabled = !testing,
                        onClick = {
                            testing = true
                            testResult = "번역 모델 여는 중…"
                            Thread {
                                val sb = StringBuilder()
                                try {
                                    if (!LlamaNative.available) {
                                        sb.append("번역 엔진을 불러오지 못했어요")
                                    } else {
                                        val t0 = System.currentTimeMillis()
                                        val threads = AiLibs.bigCores
                                        val h = LlamaNative.init(models.file(AiModel.QWEN3_4B).path, 2048, threads)
                                        val tOpen = System.currentTimeMillis()
                                        if (h == 0L) {
                                            sb.append("번역 모델을 열지 못했어요 (${(tOpen - t0) / 1000}초)")
                                        } else {
                                            testResult = "예열 중… (모델 여는 데 ${(tOpen - t0) / 1000}초)"
                                            val warm = AiTranslator.warm(h)
                                            val t1 = System.currentTimeMillis()
                                            testResult = "번역 중…"
                                            val lines = listOf("Hey, are you coming to the party tonight?", "I don't know, I'm really tired.")
                                            // 두 번 번역: 실제 자막처럼 매번 다른 대사로
                                            val r = AiTranslator.translate(h, lines, emptyList(), "en")
                                            val t2 = System.currentTimeMillis()
                                            val s1 = LlamaNative.lastStats(h)
                                            testResult = "두 번째 번역 중… (첫 번째 ${(t2 - t1) / 1000}초)"
                                            val lines2 = listOf("We need to leave before the storm hits.", "Fine, but you're driving this time.")
                                            val r2 = AiTranslator.translate(h, lines2, lines, "en")
                                            val t3 = System.currentTimeMillis()
                                            val s2 = LlamaNative.lastStats(h)
                                            LlamaNative.free(h)
                                            fun speed(st: LongArray): String {
                                                val pp = if (st[2] > 0) st[0] * 1000 / st[2] else 0
                                                val tg = if (st[3] > 0) st[1] * 1000.0 / st[3] else 0.0
                                                return "입력 ${st[0]}토큰 ${st[2] / 1000.0}초(${pp}/초) · 생성 ${st[1]}토큰 ${st[3] / 1000.0}초(${String.format("%.1f", tg)}/초)"
                                            }
                                            sb.append("모델 열기 ${(tOpen - t0) / 1000}초 · 예열 ${if (warm >= 0) "${warm / 1000.0}초" else "실패"} · 엔진 ${if (AiLibs.fastLoaded) "고성능(i8mm)" else "기본"} · 스레드 ${s1[4]}\n")
                                            sb.append("1회차 ${(t2 - t1) / 1000}초: ${speed(s1)}\n")
                                            sb.append("2회차 ${(t3 - t2) / 1000}초: ${speed(s2)}\n")
                                            sb.append("상태: ${r.info}\n")
                                            lines.forEachIndexed { i, l -> sb.append("• $l\n  → ${r.ko.getOrNull(i) ?: "(번역 없음)"}\n") }
                                            lines2.forEachIndexed { i, l -> sb.append("• $l\n  → ${r2.ko.getOrNull(i) ?: "(번역 없음)"}\n") }
                                        }
                                    }
                                } catch (e: Throwable) {
                                    sb.append("오류: ${e.message ?: e.javaClass.simpleName}")
                                }
                                testResult = sb.toString().trim()
                                testing = false
                            }.start()
                        },
                    ) { Text(if (testing) "시험 중…" else "번역 시험해 보기") }
                }
                testResult?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                }
            }

            // 음성 인식 속도 측정: 영상 없이 30초 분량을 스레드 수를 바꿔 가며 분석 → 폰 계산 속도 확인용
            var benchRunning by remember { mutableStateOf(false) }
            var benchResult by remember { mutableStateOf<String?>(null) }
            val benchModel = listOf(AiModel.WHISPER_SMALL, AiModel.WHISPER_TURBO, AiModel.WHISPER_LARGE).firstOrNull { models.isReady(it) }
            if (benchModel != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        enabled = !benchRunning,
                        onClick = {
                            benchRunning = true
                            benchResult = "측정 준비 중… (영상은 끄고 기다려 주세요)"
                            Thread({
                                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
                                val sb = StringBuilder()
                                try {
                                    val cores = (0 until Runtime.getRuntime().availableProcessors()).map { i ->
                                        runCatching {
                                            java.io.File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() / 1000
                                        }.getOrDefault(0L)
                                    }
                                    sb.append("모델 ${benchModel.name.lowercase().removePrefix("whisper_")} · 빠른 코어 ${AiLibs.bigCores}개 · 엔진 ${if (AiLibs.fastLoaded) "고성능" else "기본"}\n")
                                    sb.append("코어 최고 속도(MHz): ${cores.joinToString(" ")}\n")
                                    val h = WhisperNative.init(models.file(benchModel).path)
                                    if (h == 0L) {
                                        sb.append("음성 인식 모델을 열지 못했어요")
                                    } else {
                                        // 아주 작은 잡음 30초 (말소리 없음 → 분석 시간만 거의 그대로 잼)
                                        val rnd = java.util.Random(1)
                                        val pcm = FloatArray(16_000 * 30) { (rnd.nextFloat() - 0.5f) * 0.01f }
                                        val counts = listOf(2, 3, 4, AiLibs.bigCores, Runtime.getRuntime().availableProcessors()).distinct().sorted()
                                        for (t in counts) {
                                            benchResult = sb.toString() + "스레드 ${t}개 측정 중…"
                                            val t0 = System.currentTimeMillis()
                                            val raw = WhisperNative.transcribe(h, pcm, "en", t, false, true)
                                            val total = System.currentTimeMillis() - t0
                                            val head = raw?.let { String(it, Charsets.UTF_8).substringBefore('\n').split('\t') }
                                            val enc = head?.getOrNull(1)?.toLongOrNull()
                                            sb.append("스레드 ${t}개: 분석 ${enc?.let { "%.1f초".format(it / 1000.0) } ?: "?"} · 전체 ${"%.1f초".format(total / 1000.0)}\n")
                                        }
                                        WhisperNative.free(h)
                                        sb.append("(정상이라면 small 기준 분석이 몇 초 이내)")
                                    }
                                } catch (e: Throwable) {
                                    sb.append("오류: ${e.message ?: e.javaClass.simpleName}")
                                }
                                benchResult = sb.toString().trim()
                                benchRunning = false
                            }, "ai-bench").start()
                        },
                    ) { Text(if (benchRunning) "측정 중…" else "음성 인식 속도 측정") }
                }
                benchResult?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                }
            }

            SubHeader("③ 폰 성능 기능 더 쓰기")
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = fastCpu && AiLibs.hasI8mm, enabled = AiLibs.hasI8mm, onValueChange = {
                        fastCpu = it
                        store.aiFastCpu = it
                    })
                    .padding(horizontal = 24.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "AI 계산 전용 명령어(i8mm) 사용",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (AiLibs.hasI8mm) 1f else 0.4f),
                    )
                    Text(
                        when {
                            !AiLibs.hasI8mm -> "이 폰은 지원하지 않아요 — 기본 엔진으로 동작해요"
                            fastCpu && AiLibs.fastLoaded -> "이 폰은 지원해요 — 자동으로 켜져서 고성능 엔진으로 동작 중"
                            fastCpu -> "이 폰은 지원해요 — 자동으로 켜짐 (앱을 다시 켜면 고성능 엔진 적용)"
                            else -> "꺼짐 — 앱을 다시 켜면 기본 엔진으로 동작해요"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (AiLibs.hasI8mm && fastCpu) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = fastCpu && AiLibs.hasI8mm, onCheckedChange = null, enabled = AiLibs.hasI8mm)
            }

            SubHeader("④ 자막 보여주기")
            AiShowMode.entries.forEach { mode ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = show == mode.name, onClick = {
                            show = mode.name
                            store.aiShowMode = mode.name
                        })
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = show == mode.name, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(mode.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = onlyNoSubs, onValueChange = {
                        onlyNoSubs = it
                        store.aiOnlyWithoutSubs = it
                    })
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("자막 파일이 있으면 AI 자막 안 만들기", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        "끄면 자막 파일이 있어도 AI 자막을 만들어요 (재생 중 자막 메뉴에서도 켜고 끌 수 있어요)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = onlyNoSubs, onCheckedChange = null)
            }
            Text(
                "재생을 시작하면 지금 위치부터 앞쪽 10분까지 미리 자막을 만들어요. 만든 자막은 저장돼서 다음에는 바로 나와요. " +
                    "AVI·WMV·RMVB·FLV·MPG·MKV·MP4 등 모든 영상에서 동작해요",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
            TextButton(onClick = { clearAsk = 1 }, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text("만들어 둔 AI 자막 모두 지우기")
            }
        }
    }

    dlError?.let { msg ->
        AlertDialog(
            onDismissRequest = { dlError = null },
            title = { Text("받기 실패") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { dlError = null }) { Text("확인") } },
        )
    }

    mobileAsk?.let { m ->
        AlertDialog(
            onDismissRequest = { mobileAsk = null },
            title = { Text("모바일 데이터로 받을까요?") },
            text = {
                Text(
                    "지금 와이파이가 아니에요.\n'${m.title}'은 약 ${formatBytes(m.approxBytes)}라 데이터 요금이 나올 수 있어요.\n\n" +
                        "'와이파이 기다리기'를 고르면 와이파이에 연결될 때 자동으로 받아요."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    dlError = models.download(m, allowMobile = true)
                    mobileAsk = null
                    tick++
                }) { Text("데이터로 받기") }
            },
            dismissButton = {
                TextButton(onClick = {
                    dlError = models.download(m, allowMobile = false)
                    mobileAsk = null
                    tick++
                }) { Text("와이파이 기다리기") }
            },
        )
    }

    deleteAsk?.let { m ->
        AlertDialog(
            onDismissRequest = { deleteAsk = null },
            title = { Text("모델 지우기") },
            text = { Text("'${m.title}' 모델을 폰에서 지울까요?\n다시 쓰려면 새로 받아야 해요.") },
            confirmButton = {
                TextButton(onClick = {
                    models.delete(m)
                    deleteAsk = null
                    tick++
                }) { Text("지우기", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteAsk = null }) { Text("취소") } },
        )
    }
    if (clearAsk > 0) {
        AlertDialog(
            onDismissRequest = { clearAsk = 0 },
            title = { Text(if (clearAsk == 1) "AI 자막 지우기" else "정말 지울까요?") },
            text = { Text(if (clearAsk == 1) "만들어 둔 AI 자막을 모두 지울까요?" else "다시 보려면 처음부터 다시 만들어야 해요.") },
            confirmButton = {
                TextButton(onClick = {
                    if (clearAsk == 1) clearAsk = 2 else {
                        AiSubtitleSession.clearAll(context)
                        clearAsk = 0
                    }
                }) {
                    Text(
                        if (clearAsk == 1) "다음" else "네, 지울게요",
                        color = if (clearAsk == 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            },
            dismissButton = { TextButton(onClick = { clearAsk = 0 }) { Text("취소") } },
        )
    }
}

@Composable
private fun SubHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 4.dp),
    )
}

/** 모델 한 줄: (고르기) 이름·설명·용량 + 받기/취소/지우기 + 진행 막대. */
@Composable
private fun ModelRow(
    model: AiModel,
    state: ModelState,
    /** null이면 고르기 없음 (번역 모델). */
    selected: Boolean?,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .border(
                if (selected == true) 2.dp else 1.dp,
                if (selected == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(12.dp),
            )
            .then(if (selected != null) Modifier.selectable(selected = selected, onClick = onSelect) else Modifier)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected != null) {
                RadioButton(selected = selected, onClick = null)
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(model.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    "${model.desc} · 약 ${formatBytes(model.approxBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            when (state) {
                ModelState.Ready -> Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                    LineIcon(LineIcons.Check, size = 16.dp)
                    Spacer(Modifier.width(4.dp))
                    Text("받음", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                    TextButton(onClick = onDelete) { Text("지우기", style = MaterialTheme.typography.labelMedium) }
                }
                is ModelState.Downloading -> OutlinedButton(onClick = onCancel) { Text("취소") }
                else -> Button(onClick = onDownload) { Text("받기") }
            }
        }
        when (state) {
            is ModelState.Downloading -> {
                Spacer(Modifier.height(8.dp))
                val frac = if (state.total > 0) (state.done.toFloat() / state.total).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                Text(
                    if (state.waitingWifi) "와이파이 연결을 기다리는 중… (용량이 커서 와이파이에서만 받아요)"
                    else "받는 중 ${formatBytes(state.done)} / ${formatBytes(state.total)} (${(frac * 100).toInt()}%) — 앱을 나가도 계속 받아요",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            is ModelState.Failed -> Text(
                state.reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
            else -> Unit
        }
    }
}
