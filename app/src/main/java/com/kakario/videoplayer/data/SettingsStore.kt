package com.kakario.videoplayer.data

import android.content.Context

/** 화면 테마: 시스템 설정을 따르거나, 항상 밝게 / 항상 어둡게. */
enum class ThemeMode(val label: String) {
    SYSTEM("시스템 설정 따르기"),
    LIGHT("밝게 (흰색)"),
    DARK("어둡게 (검은색)"),
}

/** 자막 글자 크기 (VLC --sub-text-scale 퍼센트). */
enum class SubtitleSize(val label: String, val scale: Int) {
    SMALL("작게", 75),
    NORMAL("보통", 100),
    LARGE("크게", 130),
    XLARGE("아주 크게", 165),
}

/** 영상이 끝났을 때. */
enum class AutoNext(val label: String) {
    OFF("멈추기"),
    COUNTDOWN("5초 뒤 다음 화 자동 재생"),
}

/** 목록 썸네일 칸 비율. */
enum class ThumbRatio(val label: String, val ratio: Float) {
    SQUARE("1:1", 1f),
    R4_3("4:3", 4f / 3f),
    R16_9("16:9", 16f / 9f),
}

/** 목록 정렬. */
enum class SortOrder(val label: String) {
    NAME("이름순"),
    NEWEST("최신순"),
    LARGEST("용량 큰 순"),
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = prefs.getString("theme", null)
            ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
            ?: ThemeMode.SYSTEM
        set(value) {
            prefs.edit().putString("theme", value.name).apply()
        }

    /** 목록에서 한 줄에 보여줄 영상 개수 (1~4). */
    var gridColumns: Int
        get() = prefs.getInt("grid_columns", 2).coerceIn(1, 4)
        set(value) {
            prefs.edit().putInt("grid_columns", value.coerceIn(1, 4)).apply()
        }

    var subtitleSize: SubtitleSize
        get() = prefs.getString("subtitle_size", null)
            ?.let { name -> SubtitleSize.entries.firstOrNull { it.name == name } }
            ?: SubtitleSize.NORMAL
        set(value) {
            prefs.edit().putString("subtitle_size", value.name).apply()
        }

    /** 다시 열면 보던 위치부터 이어서 재생. */
    var resumePlayback: Boolean
        get() = prefs.getBoolean("resume", true)
        set(value) {
            prefs.edit().putBoolean("resume", value).apply()
        }

    /** 양옆 두 번 탭 / 버튼으로 건너뛸 초 (5·10·15·30). */
    var skipSeconds: Int
        get() = prefs.getInt("skip_seconds", 10)
        set(value) {
            prefs.edit().putInt("skip_seconds", value).apply()
        }

    /** 썸네일로 쓸 장면 위치(초): 0~120초, 10초 단위. 첫 장면은 검거나 비슷한 경우가 많아 기본 30초. */
    var thumbSeconds: Int
        get() = (prefs.getInt("thumb_seconds", 30) / 10 * 10).coerceIn(0, 120)
        set(value) {
            prefs.edit().putInt("thumb_seconds", (value / 10 * 10).coerceIn(0, 120)).apply()
        }

    /** 고른 장면이 너무 어두우면 자동으로 조금 뒤 장면을 찾을지. */
    var thumbSkipDark: Boolean
        get() = prefs.getBoolean("thumb_skip_dark", true)
        set(value) {
            prefs.edit().putBoolean("thumb_skip_dark", value).apply()
        }

    /** FTP 영상도 썸네일을 자동으로 만들지. */
    var ftpThumbs: Boolean
        get() = prefs.getBoolean("ftp_thumbs", true)
        set(value) {
            prefs.edit().putBoolean("ftp_thumbs", value).apply()
        }

    /** 모바일 데이터(LTE/5G)일 때도 FTP 썸네일을 만들지. */
    var thumbsOnMobile: Boolean
        get() = prefs.getBoolean("thumbs_mobile", true)
        set(value) {
            prefs.edit().putBoolean("thumbs_mobile", value).apply()
        }

    var autoNext: AutoNext
        get() = prefs.getString("auto_next", null)
            ?.let { name -> AutoNext.entries.firstOrNull { it.name == name } }
            ?: AutoNext.COUNTDOWN
        set(value) {
            prefs.edit().putString("auto_next", value.name).apply()
        }

    /** 홈 버튼을 누르면 작은 창(PIP)으로 계속 재생. */
    var pip: Boolean
        get() = prefs.getBoolean("pip", true)
        set(value) {
            prefs.edit().putBoolean("pip", value).apply()
        }

    var thumbRatio: ThumbRatio
        get() = prefs.getString("thumb_ratio", null)
            ?.let { name -> ThumbRatio.entries.firstOrNull { it.name == name } }
            ?: ThumbRatio.R16_9
        set(value) {
            prefs.edit().putString("thumb_ratio", value.name).apply()
        }

    /** 목록에서 폴더를 영상보다 위에 둘지 (false면 아래). */
    var foldersFirst: Boolean
        get() = prefs.getBoolean("folders_first", true)
        set(value) {
            prefs.edit().putBoolean("folders_first", value).apply()
        }

    /** 재생 화면에 화면 잠금 버튼을 보일지. */
    var lockButton: Boolean
        get() = prefs.getBoolean("lock_button", false)
        set(value) {
            prefs.edit().putBoolean("lock_button", value).apply()
        }

    var sortOrder: SortOrder
        get() = prefs.getString("sort", null)
            ?.let { name -> SortOrder.entries.firstOrNull { it.name == name } }
            ?: SortOrder.NAME
        set(value) {
            prefs.edit().putString("sort", value.name).apply()
        }

    // ── 오프라인 AI 자막

    /** 재생할 때 AI로 자막을 만들지 (폰 안에서만 동작). */
    var aiSubtitles: Boolean
        get() = prefs.getBoolean("ai_subs", false)
        set(value) {
            prefs.edit().putBoolean("ai_subs", value).apply()
        }

    /** 쓸 음성인식 모델 (AiModel 이름). */
    var aiWhisperModel: String
        get() = prefs.getString("ai_whisper", "WHISPER_TURBO") ?: "WHISPER_TURBO"
        set(value) {
            prefs.edit().putString("ai_whisper", value).apply()
        }

    /** 외국어를 한국어로 번역할지. */
    var aiTranslate: Boolean
        get() = prefs.getBoolean("ai_translate", true)
        set(value) {
            prefs.edit().putBoolean("ai_translate", value).apply()
        }

    /** AI 자막 보여주는 방식 (AiShowMode 이름). */
    var aiShowMode: String
        get() = prefs.getString("ai_show", "BOTH") ?: "BOTH"
        set(value) {
            prefs.edit().putString("ai_show", value).apply()
        }

    /** 폰 성능 기능 더 쓰기 (i8mm 지원 폰이면 고성능 엔진). 기본 켜짐. */
    var aiFastCpu: Boolean
        get() = prefs.getBoolean("ai_fast_cpu", true)
        set(value) {
            prefs.edit().putBoolean("ai_fast_cpu", value).apply()
        }

    /** 자막 파일이 이미 있으면 AI 자막을 만들지 않기. */
    var aiOnlyWithoutSubs: Boolean
        get() = prefs.getBoolean("ai_only_nosubs", true)
        set(value) {
            prefs.edit().putBoolean("ai_only_nosubs", value).apply()
        }
}
