// 영상에서 소리만 꺼내 16kHz 모노 float로 바꾸는 FFmpeg JNI (AVI·WMV·RMVB·FLV·MPG 등 모든 형식)
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswresample/swresample.h>
#include <libavutil/opt.h>
#include <libavutil/channel_layout.h>

#define OUT_RATE 16000

typedef struct {
    AVFormatContext *fmt;
    AVCodecContext *dec;
    SwrContext *swr;
    AVPacket *pkt;
    AVFrame *frame;
    int stream;
    int64_t stream_pos_ms; // 이어서 읽을 위치 (-1이면 모름)
    int64_t skip_until_ms; // 이 시각 전 소리는 버림
    float *carry;          // 지난번에 남은 소리 (16k 모노)
    int carry_n;
    int eof;
} Dec;

static void push(float **buf, int *n, int *cap, const float *src, int cnt) {
    if (*n + cnt > *cap) {
        int nc = (*cap) * 2;
        if (nc < *n + cnt) nc = *n + cnt;
        *buf = realloc(*buf, sizeof(float) * nc);
        *cap = nc;
    }
    memcpy(*buf + *n, src, sizeof(float) * cnt);
    *n += cnt;
}

JNIEXPORT jlong JNICALL
Java_com_kakario_videoplayer_ai_AudioNative_open(JNIEnv *env, jclass clazz, jstring jsrc) {
    const char *src = (*env)->GetStringUTFChars(env, jsrc, NULL);
    Dec *d = calloc(1, sizeof(Dec));
    AVDictionary *opts = NULL;
    av_dict_set(&opts, "rw_timeout", "20000000", 0);   // 20초 응답 없으면 포기
    av_dict_set(&opts, "reconnect", "1", 0);
    int ok = avformat_open_input(&d->fmt, src, NULL, &opts) == 0;
    av_dict_free(&opts);
    (*env)->ReleaseStringUTFChars(env, jsrc, src);
    if (!ok) { free(d); return 0; }
    if (avformat_find_stream_info(d->fmt, NULL) < 0) goto fail;
    const AVCodec *codec = NULL;
    d->stream = av_find_best_stream(d->fmt, AVMEDIA_TYPE_AUDIO, -1, -1, &codec, 0);
    if (d->stream < 0 || !codec) goto fail;
    d->dec = avcodec_alloc_context3(codec);
    avcodec_parameters_to_context(d->dec, d->fmt->streams[d->stream]->codecpar);
    if (avcodec_open2(d->dec, codec, NULL) < 0) goto fail;
    // 다른 트랙은 읽지 않음 (영상 데이터 무시)
    for (unsigned i = 0; i < d->fmt->nb_streams; i++)
        if ((int) i != d->stream) d->fmt->streams[i]->discard = AVDISCARD_ALL;

    AVChannelLayout mono = AV_CHANNEL_LAYOUT_MONO;
    AVChannelLayout in_layout;
    if (d->dec->ch_layout.nb_channels > 0) av_channel_layout_copy(&in_layout, &d->dec->ch_layout);
    else av_channel_layout_default(&in_layout, 2);
    if (swr_alloc_set_opts2(&d->swr, &mono, AV_SAMPLE_FMT_FLT, OUT_RATE,
                            &in_layout, d->dec->sample_fmt, d->dec->sample_rate, 0, NULL) < 0 ||
        swr_init(d->swr) < 0) {
        av_channel_layout_uninit(&in_layout);
        goto fail;
    }
    av_channel_layout_uninit(&in_layout);
    d->pkt = av_packet_alloc();
    d->frame = av_frame_alloc();
    d->stream_pos_ms = -1;
    return (jlong) (intptr_t) d;
fail:
    if (d->dec) avcodec_free_context(&d->dec);
    if (d->fmt) avformat_close_input(&d->fmt);
    free(d);
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_kakario_videoplayer_ai_AudioNative_durationMs(JNIEnv *env, jclass clazz, jlong h) {
    Dec *d = (Dec *) (intptr_t) h;
    if (!d || d->fmt->duration <= 0) return 0;
    return d->fmt->duration / 1000; // AV_TIME_BASE(µs) → ms
}

/** startMs부터 lenMs 길이의 16kHz 모노 소리. 끝이면 남은 만큼, 없으면 null. */
JNIEXPORT jfloatArray JNICALL
Java_com_kakario_videoplayer_ai_AudioNative_read(JNIEnv *env, jclass clazz, jlong h, jlong startMs, jlong lenMs) {
    Dec *d = (Dec *) (intptr_t) h;
    if (!d) return NULL;
    if (d->stream_pos_ms < 0 || llabs(startMs - d->stream_pos_ms) > 300) {
        av_seek_frame(d->fmt, -1, startMs * 1000, AVSEEK_FLAG_BACKWARD);
        avcodec_flush_buffers(d->dec);
        swr_init(d->swr);
        d->carry_n = 0;
        d->eof = 0;
        d->skip_until_ms = startMs;
    }
    int need = (int) (lenMs * OUT_RATE / 1000);
    int cap = need + OUT_RATE, n = 0;
    float *buf = malloc(sizeof(float) * cap);
    if (d->carry_n > 0) push(&buf, &n, &cap, d->carry, d->carry_n);
    d->carry_n = 0;

    AVRational tb = d->fmt->streams[d->stream]->time_base;
    float *tmp = NULL;
    int tmp_cap = 0;
    while (n < need && !d->eof) {
        int r = av_read_frame(d->fmt, d->pkt);
        if (r < 0) {
            d->eof = 1;
            avcodec_send_packet(d->dec, NULL);
        } else if (d->pkt->stream_index != d->stream) {
            av_packet_unref(d->pkt);
            continue;
        } else {
            avcodec_send_packet(d->dec, d->pkt);
            av_packet_unref(d->pkt);
        }
        while (avcodec_receive_frame(d->dec, d->frame) == 0) {
            int out_max = swr_get_out_samples(d->swr, d->frame->nb_samples) + 32;
            if (out_max > tmp_cap) { tmp = realloc(tmp, sizeof(float) * out_max); tmp_cap = out_max; }
            uint8_t *outp[1] = {(uint8_t *) tmp};
            int got = swr_convert(d->swr, outp, out_max, (const uint8_t **) d->frame->extended_data, d->frame->nb_samples);
            int from = 0;
            if (d->skip_until_ms > 0 && d->frame->best_effort_timestamp != AV_NOPTS_VALUE) {
                int64_t ms = av_rescale_q(d->frame->best_effort_timestamp, tb, (AVRational) {1, 1000});
                if (ms < d->skip_until_ms) {
                    from = (int) ((d->skip_until_ms - ms) * OUT_RATE / 1000);
                    if (from > got) from = got;
                }
                if (ms + (int64_t) got * 1000 / OUT_RATE >= d->skip_until_ms) d->skip_until_ms = 0;
            }
            if (got > from) push(&buf, &n, &cap, tmp + from, got - from);
            av_frame_unref(d->frame);
        }
    }
    free(tmp);
    if (n == 0) { free(buf); return NULL; }
    int take = n < need ? n : need;
    if (n > take) {
        d->carry = realloc(d->carry, sizeof(float) * (n - take));
        memcpy(d->carry, buf + take, sizeof(float) * (n - take));
        d->carry_n = n - take;
    }
    d->stream_pos_ms = startMs + lenMs;
    jfloatArray arr = (*env)->NewFloatArray(env, take);
    (*env)->SetFloatArrayRegion(env, arr, 0, take, buf);
    free(buf);
    return arr;
}

JNIEXPORT void JNICALL
Java_com_kakario_videoplayer_ai_AudioNative_close(JNIEnv *env, jclass clazz, jlong h) {
    Dec *d = (Dec *) (intptr_t) h;
    if (!d) return;
    if (d->swr) swr_free(&d->swr);
    if (d->frame) av_frame_free(&d->frame);
    if (d->pkt) av_packet_free(&d->pkt);
    if (d->dec) avcodec_free_context(&d->dec);
    if (d->fmt) avformat_close_input(&d->fmt);
    free(d->carry);
    free(d);
}
