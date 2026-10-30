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
 * 테두리만 있는 선 아이콘들 (폴더·별·내 폰·FTP 서버·SD카드·회전).
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
