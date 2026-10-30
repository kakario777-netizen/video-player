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

/** 썸네일로 쓸 장면 위치. 첫 장면은 검거나 비슷한 경우가 많아 기본은 30초. */
enum class ThumbTime(val label: String, val ms: Long, val percent: Int = 0) {
    S10("10초", 10_000),
    S30("30초 (기본)", 30_000),
    M1("1분", 60_000),
    M3("3분", 180_000),
    P10("영상 길이의 10%", 0, 10),
}

/** 영상이 끝났을 때. */
enum class AutoNext(val label: String) {
    OFF("멈추기"),
    COUNTDOWN("5초 뒤 다음 화 자동 재생"),
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

    var thumbTime: ThumbTime
        get() = prefs.getString("thumb_time", null)
            ?.let { name -> ThumbTime.entries.firstOrNull { it.name == name } }
            ?: ThumbTime.S30
        set(value) {
            prefs.edit().putString("thumb_time", value.name).apply()
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

    var sortOrder: SortOrder
        get() = prefs.getString("sort", null)
            ?.let { name -> SortOrder.entries.firstOrNull { it.name == name } }
            ?: SortOrder.NAME
        set(value) {
            prefs.edit().putString("sort", value.name).apply()
        }
}
