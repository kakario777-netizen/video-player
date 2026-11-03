package com.kakario.videoplayer.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.kakario.videoplayer.MainActivity
import com.kakario.videoplayer.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * AI 자막 미리 만들기가 도는 동안 알림을 띄워 두고, 화면이 꺼져도 계속 만들게 하는 서비스.
 * 알림에 지금 영상·진행률·대기 수와 '모두 중지' 버튼.
 */
class AiBatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watch: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "썸네일·AI 자막 만들기", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "화면이 꺼져도 계속 만드는 작업의 진행 상황" }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AiBatch.stopAll()
            com.kakario.videoplayer.thumb.VideoThumbs.get(applicationContext).stopFolder()
            stopSelf()
            return START_NOT_STICKY
        }
        val first = build("작업 준비 중…", null, 0)
        try {
            if (Build.VERSION.SDK_INT >= 35) {
                startForeground(ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(ID, first)
            }
        } catch (_: Exception) {
            // 알림 띄우기가 막혀도 앱이 켜져 있는 동안은 계속 만든다
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "videoplayer:ai-subtitles")
                .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        }
        if (wifiLock == null) {
            // 화면이 꺼져도 와이파이가 잠들지 않게 (FTP에서 계속 받아야 함)
            runCatching {
                val wm = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
                else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wm.createWifiLock(mode, "videoplayer:background-work").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        }
        if (watch == null) {
            val thumbs = com.kakario.videoplayer.thumb.VideoThumbs.get(applicationContext)
            watch = scope.launch {
                // AI 자막 미리 만들기 + 썸네일 만들기 둘 다 보고, 둘 다 끝나면 알림을 내린다
                kotlinx.coroutines.flow.combine(AiBatch.status, thumbs.job) { ai, th -> ai to th }
                    .collectLatest { (st, th) ->
                        val nm = getSystemService(NotificationManager::class.java)
                        when {
                            st != null -> {
                                val title = if (st.paused) "AI 자막 잠시 멈춤 (영상 보는 중)" else "AI 자막 만드는 중 ${st.percent}%"
                                val text = buildString {
                                    append(st.current)
                                    if (st.detail.isNotEmpty() && !st.paused) append("\n").append(st.detail)
                                    if (st.waiting > 0) append(" · 대기 ${st.waiting}")
                                    if (st.doneCount > 0) append(" · 완료 ${st.doneCount}")
                                    if (st.skipCount > 0) append(" · 건너뜀 ${st.skipCount}")
                                    if (th != null) append("\n썸네일 ${th.done}/${th.total} · ${th.folder}")
                                }
                                nm.notify(ID, build(title, text, st.percent))
                            }
                            th != null -> {
                                val pct = if (th.total > 0) th.done * 100 / th.total else 0
                                val text = th.folder + (if (th.waiting > 0) " · 대기 ${th.waiting}" else "")
                                nm.notify(ID, build("썸네일 만드는 중 ${th.done}/${th.total}", text, pct))
                            }
                            else -> {
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf()
                            }
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(title: String, text: String?, percent: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AiBatchService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ai)
            .setContentTitle(title)
            .apply {
                if (text != null) {
                    setContentText(text.replace("\n", " · "))
                    setStyle(Notification.BigTextStyle().bigText(text))
                }
            }
            .setProgress(100, percent, percent <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "모두 중지", stop).build())
            .build()
    }

    override fun onDestroy() {
        watch?.cancel()
        scope.cancel()
        runCatching { wakeLock?.release() }
        runCatching { wifiLock?.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "ai_subtitles"
        private const val ID = 2001
        private const val ACTION_STOP = "stop"

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, AiBatchService::class.java))
            }
        }
    }
}
