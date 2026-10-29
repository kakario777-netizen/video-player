package com.kakario.videoplayer.data

import android.content.Context

/** 화면 테마: 시스템 설정을 따르거나, 항상 밝게 / 항상 어둡게. */
enum class ThemeMode(val label: String) {
    SYSTEM("시스템 설정 따르기"),
    LIGHT("밝게 (흰색)"),
    DARK("어둡게 (검은색)"),
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
}
