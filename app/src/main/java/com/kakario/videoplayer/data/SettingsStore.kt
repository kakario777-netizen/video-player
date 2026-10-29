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
}
