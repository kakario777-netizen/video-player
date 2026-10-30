package com.kakario.videoplayer.ai

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.Closeable
import java.nio.ByteOrder

/** 영상에서 16kHz 모노 소리를 원하는 구간만큼 꺼내는 통로. */
interface AudioSource : Closeable {
    override val durationMs: Long
    fun read(startMs: Long, lenMs: Long): FloatArray?

    companion object {
        /** FFmpeg(모든 형식) 먼저, 안 되면 안드로이드 기본 디코더. */
        fun open(source: String, isUrl: Boolean): AudioSource {
            if (AudioNative.available) {
                val h = AudioNative.open(source)
                if (h != 0L) return FfmpegAudio(h)
            }
            return AudioChunker(source, isUrl)
        }
    }
}

/** FFmpeg로 꺼내기 — AVI·WMV·RMVB·FLV·MPG·VOB·MKV·MP4 등 거의 모든 형식. */
class FfmpegAudio(private val handle: Long) : AudioSource {
    override val durationMs: Long = AudioNative.durationMs(handle)
    override fun read(startMs: Long, lenMs: Long): FloatArray? = AudioNative.read(handle, startMs, lenMs)
    override fun close() = AudioNative.close(handle)
}

/**
 * 안드로이드 기본 디코더로 소리 꺼내기 (FFmpeg가 안 될 때 예비).
 * MP4·MKV·TS·WEBM(AAC·MP3·AC3·Opus 등)이 대상.
 * 원하는 위치부터 원하는 길이만큼 읽고, 이어서 읽으면 되감지 않고 계속 디코딩한다.
 */
class AudioChunker(source: String, isUrl: Boolean) : AudioSource {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private var srcRate = 44100
    private var srcChannels = 2
    private var floatPcm = false
    val durationMs: Long

    /** 지금 디코딩 흐름이 어디까지 왔는지(ms). 다음 read가 여기서 시작하면 되감지 않는다. */
    private var streamPosMs = -1L
    private var skipUntilUs = 0L
    /** 지난번에 쓰고 남은 소리(원래 샘플레이트, 모노). */
    private var carry = FloatArray(0)
    private var inputDone = false
    private var outputDone = false

    init {
        if (isUrl) extractor.setDataSource(source, emptyMap()) else extractor.setDataSource(source)
        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                track = i
                format = f
                break
            }
        }
        if (track < 0 || format == null) {
            extractor.release()
            throw IllegalStateException("소리 트랙이 없어요")
        }
        extractor.selectTrack(track)
        durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
        srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        srcChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
    }

    /** startMs부터 lenMs 길이의 16kHz 모노 소리. 영상 끝이면 남은 만큼, 아무것도 없으면 null. */
    override fun read(startMs: Long, lenMs: Long): FloatArray? {
        if (streamPosMs < 0 || kotlin.math.abs(startMs - streamPosMs) > 300) {
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec.flush()
            carry = FloatArray(0)
            inputDone = false
            outputDone = false
            skipUntilUs = startMs * 1000
        }
        val need = (lenMs * srcRate / 1000).toInt()
        val buf = FloatList(need + srcRate)
        buf.addAll(carry)
        carry = FloatArray(0)

        val info = MediaCodec.BufferInfo()
        while (buf.size < need && !outputDone) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(5_000)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx)!!
                    val n = extractor.readSampleData(inBuf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 5_000)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    srcChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
                outIdx >= 0 -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (info.size > 0) {
                        val out = codec.getOutputBuffer(outIdx)!!
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val mono = toMono(out)
                        // 원하는 시작점 이전 소리는 버린다 (되감으면 앞쪽 키프레임부터 나오므로)
                        var from = 0
                        if (info.presentationTimeUs < skipUntilUs) {
                            from = ((skipUntilUs - info.presentationTimeUs) * srcRate / 1_000_000).toInt()
                                .coerceIn(0, mono.size)
                        }
                        for (i in from until mono.size) buf.add(mono[i])
                        if (info.presentationTimeUs + mono.size * 1_000_000L / srcRate > skipUntilUs) skipUntilUs = 0
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }
        }
        if (buf.size == 0) return null
        val take = minOf(need, buf.size)
        carry = buf.copyRange(take, buf.size)
        streamPosMs = startMs + lenMs
        return resample(buf.copyRange(0, take), srcRate)
    }

    private fun toMono(bb: java.nio.ByteBuffer): FloatArray {
        bb.order(ByteOrder.LITTLE_ENDIAN)
        val ch = srcChannels.coerceAtLeast(1)
        return if (floatPcm) {
            val fb = bb.asFloatBuffer()
            val frames = fb.remaining() / ch
            FloatArray(frames) { i ->
                var s = 0f
                for (c in 0 until ch) s += fb.get(i * ch + c)
                s / ch
            }
        } else {
            val sb = bb.asShortBuffer()
            val frames = sb.remaining() / ch
            FloatArray(frames) { i ->
                var s = 0f
                for (c in 0 until ch) s += sb.get(i * ch + c)
                s / ch / 32768f
            }
        }
    }

    /** 원래 샘플레이트 → 16kHz (선형 보간, 말소리 인식에는 충분). */
    private fun resample(src: FloatArray, rate: Int): FloatArray {
        if (rate == 16000) return src
        val outLen = (src.size.toLong() * 16000 / rate).toInt()
        val ratio = rate.toDouble() / 16000
        return FloatArray(outLen) { i ->
            val pos = i * ratio
            val i0 = pos.toInt().coerceAtMost(src.size - 1)
            val i1 = (i0 + 1).coerceAtMost(src.size - 1)
            val t = (pos - i0).toFloat()
            src[i0] * (1 - t) + src[i1] * t
        }
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
    }
}

/** 크기가 늘어나는 float 배열 (박싱 없이). */
private class FloatList(initial: Int) {
    private var data = FloatArray(initial.coerceAtLeast(16))
    var size = 0
        private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun addAll(a: FloatArray) {
        if (size + a.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + a.size))
        System.arraycopy(a, 0, data, size, a.size)
        size += a.size
    }

    fun copyRange(from: Int, to: Int): FloatArray = data.copyOfRange(from, to)
}
