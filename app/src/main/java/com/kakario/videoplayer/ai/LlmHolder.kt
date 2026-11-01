package com.kakario.videoplayer.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue

/**
 * 번역 모델(2.5GB)을 앱 안에서 하나만 열어 두고 같이 쓴다.
 * - 한 번 열고 예열하면, 다음 영상으로 넘어가도 다시 열거나 예열하지 않는다.
 * - 영상 재생을 시작하면 뒤에서 미리 열고 예열한다 (폰 여유 메모리가 4GB 이상일 때만).
 * - 5분 동안 안 쓰면 메모리에서 내려놓는다.
 * - 번역 엔진은 동시에 둘이 쓸 수 없어서, 쓰는 동안 잠근다.
 */
object LlmHolder {
    private const val IDLE_MS = 5 * 60_000L
    private const val PREWARM_FREE_BYTES = 4L * 1024 * 1024 * 1024

    private val lock = Object()
    @Volatile private var handle = 0L
    private var path: String? = null
    private val main = Handler(Looper.getMainLooper())
    private val releaser = Runnable { releaseIfIdle() }
    @Volatile private var busy = 0

    /** 마지막으로 연 데 걸린 시간 설명 (자막 메뉴 표시용). */
    @Volatile var openInfo: String? = null
        private set

    val isOpen: Boolean get() = handle != 0L

    /** 예열(열기+준비) 시작 시각, 0이면 예열 중 아님 — 화면에 '번역 준비 중 (N초 남음)' 표시용. */
    var warmingSince by mutableLongStateOf(0L)
        private set
    /** 예열에 걸릴 것으로 보는 시간 (지난번 실제 걸린 시간). */
    @Volatile var expectedWarmMs = 35_000L
        private set
    private var prefs: android.content.SharedPreferences? = null

    /** 예열 남은 시간(초, 대략). 예열 중이 아니면 null. */
    fun warmRemainSec(now: Long = System.currentTimeMillis()): Long? {
        val since = warmingSince
        if (since == 0L) return null
        return ((expectedWarmMs - (now - since)) / 1000).coerceAtLeast(1)
    }

    fun attach(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("llm_holder", Context.MODE_PRIVATE).also {
            expectedWarmMs = it.getLong("warm_ms", 35_000L)
        }
    }

    fun threads() = AiLibs.bigCores

    /** 모델을 (필요하면 열고 예열해서) 잠근 채 block 실행. 열기 실패면 block(0). */
    fun <T> use(modelPath: String, onOpening: () -> Unit = {}, block: (Long) -> T): T {
        main.removeCallbacks(releaser)
        synchronized(lock) {
            busy++
            AiLibs.compute.lock()
            try {
                if (handle != 0L && path != modelPath) {
                    LlamaNative.free(handle)
                    handle = 0
                }
                if (handle == 0L && LlamaNative.available) {
                    onOpening()
                    val t0 = System.currentTimeMillis()
                    main.post { warmingSince = t0 }
                    val h = LlamaNative.init(modelPath, 2048, threads())
                    if (h != 0L) {
                        val t1 = System.currentTimeMillis()
                        val w = AiTranslator.warm(h)
                        handle = h
                        path = modelPath
                        openInfo = "열기 ${(t1 - t0) / 1000}초 · 예열 ${if (w >= 0) w / 1000 else -1}초"
                        val took = System.currentTimeMillis() - t0
                        expectedWarmMs = took
                        prefs?.edit()?.putLong("warm_ms", took)?.apply()
                    }
                    main.post { warmingSince = 0L }
                }
                return block(handle)
            } finally {
                AiLibs.compute.unlock()
                busy--
                main.postDelayed(releaser, IDLE_MS)
            }
        }
    }

    /** 설정에 맞춰 미리 예열 (when = "APP" 앱 켤 때 / "VIDEO" 영상 틀 때). 번역 꺼짐·모델 없음이면 안 함. */
    fun prewarmIfWanted(context: Context, `when`: String) {
        val store = com.kakario.videoplayer.data.SettingsStore(context)
        val wanted = store.aiPrewarm
        if (wanted == "OFF") return
        if (`when` == "APP" && wanted != "APP") return
        if (!store.aiTranslate) return
        val models = ModelManager(context)
        if (!models.isReady(AiModel.QWEN3_4B)) return
        prewarm(context, models.file(AiModel.QWEN3_4B).path)
    }

    /** 메모리가 넉넉하면 뒤에서 미리 열고 예열. */
    fun prewarm(context: Context, modelPath: String) {
        attach(context)
        if (handle != 0L || warmingSince != 0L || !LlamaNative.available) return
        if (freeMemory(context) < PREWARM_FREE_BYTES) return
        Thread({ runCatching { use(modelPath) { } } }, "llm-prewarm").apply {
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /** 진행 중인 번역 중단 (재생 화면 닫을 때). */
    fun abort() {
        handle.takeIf { it != 0L }?.let { LlamaNative.abort(it) }
    }

    /** 메모리 부족 알림 등에서 바로 내려놓기 (쓰는 중이면 다음 기회에). */
    fun releaseNow() = releaseIfIdle()

    private fun releaseIfIdle() {
        if (busy > 0) return
        Thread {
            synchronized(lock) {
                if (busy == 0 && handle != 0L) {
                    LlamaNative.free(handle)
                    handle = 0
                    path = null
                }
            }
        }.start()
    }

    fun freeMemory(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.availMem
    }
}
