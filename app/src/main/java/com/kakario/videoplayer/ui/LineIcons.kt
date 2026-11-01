package com.kakario.videoplayer.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 테두리만 있는 선 아이콘들 (폴더·별·내 폰·FTP 서버·SD카드·회전·메뉴·재생 화면).
 * 앱 안에는 이모지를 쓰지 않고, 새 아이콘도 여기에 같은 모양(선 굵기 1.7, 둥근 끝)으로 추가한다.
 * 색은 아이콘을 그릴 때 진한 파란색(LineBlue)으로 입힌다.
 */
object LineIcons {
    val Folder: ImageVector by lazy {
        lineIcon(
            "Folder",
            // 뒤쪽 폴더 (탭 달린 모양)
            "M3.2,17.8 V6.2 A1.2,1.2 0 0 1 4.4,5 H8.6 L10.6,7.2 H16.8 A1.2,1.2 0 0 1 18,8.4 V10.2",
            // 앞쪽으로 펼쳐진 덮개
            "M3.2,17.8 L6.3,11 A1.2,1.2 0 0 1 7.4,10.3 H20 A0.9,0.9 0 0 1 20.8,11.6 L17.7,18.3 A1.2,1.2 0 0 1 16.6,19 H4.1 A0.9,0.9 0 0 1 3.2,17.8 Z",
        )
    }

    val Star: ImageVector by lazy {
        lineIcon("Star", STAR)
    }

    val StarFilled: ImageVector by lazy {
        lineIcon("StarFilled", STAR, filled = true)
    }

    val Phone: ImageVector by lazy {
        lineIcon(
            "Phone",
            "M8,2.8 H16 A1.8,1.8 0 0 1 17.8,4.6 V19.4 A1.8,1.8 0 0 1 16,21.2 H8 A1.8,1.8 0 0 1 6.2,19.4 V4.6 A1.8,1.8 0 0 1 8,2.8 Z",
            "M10.6,18.2 H13.4",
        )
    }

    val Server: ImageVector by lazy {
        lineIcon(
            "Server",
            "M5,3.8 H19 A1.4,1.4 0 0 1 20.4,5.2 V9 A1.4,1.4 0 0 1 19,10.4 H5 A1.4,1.4 0 0 1 3.6,9 V5.2 A1.4,1.4 0 0 1 5,3.8 Z",
            "M5,13.6 H19 A1.4,1.4 0 0 1 20.4,15 V18.8 A1.4,1.4 0 0 1 19,20.2 H5 A1.4,1.4 0 0 1 3.6,18.8 V15 A1.4,1.4 0 0 1 5,13.6 Z",
            "M7.2,7.1 H7.21",
            "M7.2,16.9 H7.21",
            "M11,7.1 H16.8",
            "M11,16.9 H16.8",
        )
    }

    val SdCard: ImageVector by lazy {
        lineIcon(
            "SdCard",
            "M9.2,2.8 H17.2 A1.2,1.2 0 0 1 18.4,4 V20 A1.2,1.2 0 0 1 17.2,21.2 H6.8 A1.2,1.2 0 0 1 5.6,20 V6.4 Z",
            "M10,6 V8",
            "M12.6,6 V8",
            "M15.2,6 V8",
        )
    }

    /** 화면 돌리기: 휴대폰 + 둥근 화살표 */
    val Rotate: ImageVector by lazy {
        lineIcon(
            "Rotate",
            "M9.5,7.5 H14.5 A1.2,1.2 0 0 1 15.7,8.7 V15.3 A1.2,1.2 0 0 1 14.5,16.5 H9.5 A1.2,1.2 0 0 1 8.3,15.3 V8.7 A1.2,1.2 0 0 1 9.5,7.5 Z",
            "M4,10 A8.2,8.2 0 0 1 17.6,4.6",
            "M17.9,2 V4.9 H15",
            "M20,14 A8.2,8.2 0 0 1 6.4,19.4",
            "M6.1,22 V19.1 H9",
        )
    }

    // ── 메뉴·재생 화면용 (모두 같은 선 굵기의 테두리 아이콘)
    val Sun: ImageVector by lazy {
        lineIcon(
            "Sun", "M16,12 A4,4 0 1 1 8,12 A4,4 0 1 1 16,12 Z",
            "M12,2.5 V4.5", "M12,19.5 V21.5", "M2.5,12 H4.5", "M19.5,12 H21.5",
            "M5.3,5.3 L6.7,6.7", "M17.3,17.3 L18.7,18.7", "M5.3,18.7 L6.7,17.3", "M17.3,6.7 L18.7,5.3",
        )
    }
    val Volume: ImageVector by lazy {
        lineIcon("Volume", "M4,9.5 H7.5 L12,5.5 V18.5 L7.5,14.5 H4 Z", "M15.5,9 A4,4 0 0 1 15.5,15", "M18,6.5 A7.5,7.5 0 0 1 18,17.5")
    }
    val Lock: ImageVector by lazy {
        lineIcon(
            "Lock", "M6.5,11 H17.5 A1.3,1.3 0 0 1 18.8,12.3 V19.2 A1.3,1.3 0 0 1 17.5,20.5 H6.5 A1.3,1.3 0 0 1 5.2,19.2 V12.3 A1.3,1.3 0 0 1 6.5,11 Z",
            "M8.5,11 V7.8 A3.5,3.5 0 0 1 15.5,7.8 V11", "M12,14.6 V16.6",
        )
    }
    val Unlock: ImageVector by lazy {
        lineIcon(
            "Unlock", "M6.5,11 H17.5 A1.3,1.3 0 0 1 18.8,12.3 V19.2 A1.3,1.3 0 0 1 17.5,20.5 H6.5 A1.3,1.3 0 0 1 5.2,19.2 V12.3 A1.3,1.3 0 0 1 6.5,11 Z",
            "M8.5,11 V7.8 A3.5,3.5 0 0 1 15.3,6.6", "M12,14.6 V16.6",
        )
    }
    val Play: ImageVector by lazy { lineIcon("Play", "M8,5.5 L18.5,12 L8,18.5 Z", filled = true) }
    val Pause: ImageVector by lazy { lineIcon("Pause", "M7.5,5.5 H10 V18.5 H7.5 Z", "M14,5.5 H16.5 V18.5 H14 Z", filled = true) }
    val Select: ImageVector by lazy {
        lineIcon("Select", "M5.5,4 H18.5 A1.5,1.5 0 0 1 20,5.5 V18.5 A1.5,1.5 0 0 1 18.5,20 H5.5 A1.5,1.5 0 0 1 4,18.5 V5.5 A1.5,1.5 0 0 1 5.5,4 Z", "M8,12.2 L10.8,15 L16.2,9.3")
    }
    val Subtitle: ImageVector by lazy {
        lineIcon(
            "Subtitle", "M5,4.5 H19 A1.5,1.5 0 0 1 20.5,6 V15 A1.5,1.5 0 0 1 19,16.5 H10 L6,20 V16.5 H5 A1.5,1.5 0 0 1 3.5,15 V6 A1.5,1.5 0 0 1 5,4.5 Z",
            "M7.5,9 H16.5", "M7.5,12.2 H13.5",
        )
    }
    val Eraser: ImageVector by lazy { lineIcon("Eraser", "M14.5,4.5 L20,10 L11,19 H6.5 L4,16.5 Z", "M9,10 L14.5,15.5", "M11,19 H20") }
    val Image: ImageVector by lazy {
        lineIcon(
            "Image", "M5,4.5 H19 A1.5,1.5 0 0 1 20.5,6 V18 A1.5,1.5 0 0 1 19,19.5 H5 A1.5,1.5 0 0 1 3.5,18 V6 A1.5,1.5 0 0 1 5,4.5 Z",
            "M3.8,16.5 L8.5,12 L12,15.2 L15,12.5 L20.2,17", "M16.2,8.6 A1.4,1.4 0 1 1 13.4,8.6 A1.4,1.4 0 1 1 16.2,8.6 Z",
        )
    }
    val History: ImageVector by lazy { lineIcon("History", "M4.5,12 A7.5,7.5 0 1 0 6.7,6.7", "M4.2,3.8 V7.2 H7.6", "M12,8 V12 L14.8,13.6") }
    val Refresh: ImageVector by lazy {
        lineIcon("Refresh", "M19.5,12 A7.5,7.5 0 0 1 6.2,16.8", "M4.5,12 A7.5,7.5 0 0 1 17.8,7.2", "M18.2,3.8 V7.4 H14.6", "M5.8,20.2 V16.6 H9.4")
    }
    val Trash: ImageVector by lazy {
        lineIcon(
            "Trash", "M4.5,6.5 H19.5", "M9.5,6.5 V4.8 A0.8,0.8 0 0 1 10.3,4 H13.7 A0.8,0.8 0 0 1 14.5,4.8 V6.5",
            "M6.5,6.5 L7.4,19 A1.3,1.3 0 0 0 8.7,20.2 H15.3 A1.3,1.3 0 0 0 16.6,19 L17.5,6.5", "M10.2,10 V16.5", "M13.8,10 V16.5",
        )
    }
    val Check: ImageVector by lazy { lineIcon("Check", "M5,12.5 L9.8,17 L19,7.5") }
    val Warning: ImageVector by lazy { lineIcon("Warning", "M12,4 L21,19.5 H3 Z", "M12,10 V14", "M12,16.8 V16.81") }

    private const val STAR =
        "M12,3.4 L14.6,8.7 L20.4,9.5 L16.2,13.6 L17.2,19.4 L12,16.7 L6.8,19.4 L7.8,13.6 L3.6,9.5 L9.4,8.7 Z"

    private fun lineIcon(name: String, vararg paths: String, filled: Boolean = false): ImageVector {
        val b = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        paths.forEach { d ->
            b.addPath(
                pathData = addPathNodes(d),
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }
}

/** 아이콘에 쓰는 진한 파란색 (어두운 테마에선 잘 보이게 조금 밝게). */
val LineBlue: Color
    @Composable get() = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF6EA8FF) else Color(0xFF1C4FC4)

/** 선 아이콘 하나 그리기. */
@Composable
fun LineIcon(icon: ImageVector, size: Dp = 24.dp, modifier: Modifier = Modifier, tint: Color = LineBlue) {
    Icon(icon, contentDescription = null, tint = tint, modifier = modifier.size(size))
}
