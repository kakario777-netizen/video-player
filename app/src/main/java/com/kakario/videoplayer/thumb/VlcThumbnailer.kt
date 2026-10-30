package com.kakario.videoplayer.thumb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.MediaPlayer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * VLC 엔진으로 썸네일을 뽑는다 — 안드로이드 기본 추출기가 못 여는 AVI·WMV·FLV·RMVB 등용.
 *
 * 화면에 안 보이는 작은 그림판(ImageReader)에 소리 없이 영상을 잠깐 틀어서,
 * 원하는 위치의 장면이 그려지면 그 한 장을 가져온다. (재생 화면과 같은 VLC 코덱을 쓰므로
 * 재생되는 영상이면 썸네일도 나온다)
 * 한 번에 하나씩만 — 호출하는 쪽에서 차례를 지킬 것.
 */
internal class VlcThumbnailer(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())

    private val libVlc: LibVLC by lazy {
        LibVLC(
            context.applicationContext,
            arrayListOf(
                "--no-audio",
                "--no-sub-autodetect-file",
                "--no-spu",
                "--network-caching=1500",
                "--file-caching=300",
                // 그림판에 직접 그리는 출력 방식 (OpenGL 대신)
                "--vout=android_display,android_window,none",
            ),
        )
    }

    /** 고른 장면들과 재생 시간. 하나도 못 뽑으면 frames가 비어 있다. */
    class Result(val frames: List<Pair<Long, Bitmap>>, val durationMs: Long)

    /**
     * uri 영상에서 times(ms) 위치 장면을 차례로 뽑는다. good(bitmap)이 true면 거기서 멈춘다.
     * 화면 출력 형식이 그림판과 안 맞으면 형식을 바꿔 한 번 더 시도.
     */
    fun grab(uri: Uri, times: (durationMs: Long) -> List<Long>, good: (Bitmap) -> Boolean, timeoutMs: Long = 25_000): Result {
        var format = PixelFormat.RGBA_8888
        repeat(2) {
            try {
                return grabWith(uri, times, good, timeoutMs, format)
            } catch (e: FormatMismatch) {
                format = e.producerFormat
            }
        }
        return Result(emptyList(), 0)
    }

    private class FormatMismatch(val producerFormat: Int) : Exception()

    private fun grabWith(
        uri: Uri,
        times: (Long) -> List<Long>,
        good: (Bitmap) -> Boolean,
        timeoutMs: Long,
        format: Int,
    ): Result {
        val reader = ImageReader.newInstance(W, H, format, 3)
        var player: MediaPlayer? = null
        val frames = mutableListOf<Pair<Long, Bitmap>>()
        var duration = 0L
        try {
            // VLC 화면 연결은 메인 스레드에서
            onMain {
                val mp = MediaPlayer(libVlc)
                val vout = mp.vlcVout
                vout.setVideoSurface(reader.surface, null)
                vout.setWindowSize(W, H)
                vout.attachViews()
                val media = Media(libVlc, uri)
                media.setHWDecoderEnabled(false, false) // 그림판에는 소프트웨어 출력이 확실하다
                media.addOption(":no-audio")
                media.addOption(":start-paused=false")
                mp.media = media
                media.release()
                mp.play()
                player = mp
            }
            val mp = player ?: return Result(emptyList(), 0)
            val deadline = System.currentTimeMillis() + timeoutMs

            // 재생이 시작되어 길이를 알 때까지 기다림
            while (System.currentTimeMillis() < deadline && mp.length <= 0 && mp.playerState != IMedia.State.Error) {
                drain(reader)
                Thread.sleep(80)
            }
            duration = mp.length.coerceAtLeast(0)
            if (mp.playerState == IMedia.State.Error) return Result(emptyList(), duration)

            for (t in times(duration)) {
                if (System.currentTimeMillis() > deadline) break
                drain(reader)
                mp.time = t
                val bmp = waitFrame(reader, mp, t, deadline) ?: continue
                frames += t to bmp
                if (good(bmp)) break
            }
            return Result(frames, duration)
        } catch (e: FormatMismatch) {
            frames.forEach { it.second.recycle() }
            throw e
        } catch (_: Exception) {
            return Result(frames, duration)
        } finally {
            val mp = player
            onMain {
                runCatching {
                    mp?.stop()
                    mp?.vlcVout?.detachViews()
                    mp?.release()
                }
            }
            runCatching { reader.close() }
        }
    }

    /** 이동한 위치(t) 근처가 재생된 뒤 그려진 장면을 가져온다. 이동 직후 몇 장은 버린다. */
    private fun waitFrame(reader: ImageReader, mp: MediaPlayer, t: Long, deadline: Long): Bitmap? {
        var seen = 0
        val until = minOf(deadline, System.currentTimeMillis() + 10_000)
        while (System.currentTimeMillis() < until) {
            val img = acquire(reader)
            if (img != null) {
                val nearTarget = mp.time >= t - 1_000
                if (nearTarget) seen++
                if (nearTarget && seen >= 3) {
                    return img
                }
                img.recycle()
            } else {
                Thread.sleep(40)
            }
        }
        return null
    }

    /** 그림판에서 가장 최근 장면 하나를 Bitmap으로. 없으면 null. */
    private fun acquire(reader: ImageReader): Bitmap? {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: UnsupportedOperationException) {
            // "producer output buffer format 0x2 doesn't match ... 0x1" → 그 형식으로 다시
            val m = Regex("format 0x([0-9a-fA-F]+) doesn't match").find(e.message ?: "")
            val f = m?.groupValues?.get(1)?.toIntOrNull(16)
            if (f != null && f != reader.imageFormat) throw FormatMismatch(f)
            return null
        } ?: return null
        return try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val config = if (pixelStride == 2) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            val full = Bitmap.createBitmap(rowStride / pixelStride, image.height, config)
            full.copyPixelsFromBuffer(plane.buffer)
            val cropped = if (full.width != image.width) {
                Bitmap.createBitmap(full, 0, 0, image.width, image.height).also { full.recycle() }
            } else full
            val out = if (cropped.config != Bitmap.Config.ARGB_8888) {
                cropped.copy(Bitmap.Config.ARGB_8888, true).also { cropped.recycle() }
            } else cropped
            // RGBX 출력은 투명도 값이 비어 있을 수 있어 불투명으로 고정
            out.setHasAlpha(false)
            out
        } catch (_: Exception) {
            null
        } finally {
            image.close()
        }
    }

    private fun drain(reader: ImageReader) {
        runCatching { reader.acquireLatestImage()?.close() }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        var error: Throwable? = null
        main.post {
            try {
                block()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        error?.let { throw it }
    }

    private companion object {
        const val W = 384
        const val H = 216
    }
}
