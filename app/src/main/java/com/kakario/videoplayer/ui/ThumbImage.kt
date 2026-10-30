package com.kakario.videoplayer.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.kakario.videoplayer.model.FileEntry
import com.kakario.videoplayer.model.MediaOrigin
import com.kakario.videoplayer.thumb.LocalVideoThumbs
import kotlinx.coroutines.CancellationException

/**
 * 영상 썸네일 한 장. 만들어지는 동안·만들 수 없을 때는 가운데 ▶ 표시만.
 * 화면에 보이는 칸만 만들고, 스크롤해서 지나가면 기다리던 작업은 취소된다.
 */
@Composable
fun ThumbImage(origin: MediaOrigin, entry: FileEntry, modifier: Modifier = Modifier) {
    val thumbs = LocalVideoThumbs.current
    val key = remember(origin, entry) { thumbs?.key(origin, entry) ?: entry.path }
    val stamp = thumbs?.stamp(key) ?: 0
    var bitmap by remember(key) { mutableStateOf<Bitmap?>(thumbs?.cached(key)) }

    LaunchedEffect(key, stamp) {
        if (thumbs == null) return@LaunchedEffect
        val mem = thumbs.cached(key)
        if (mem != null) {
            bitmap = mem
            return@LaunchedEffect
        }
        bitmap = null
        bitmap = try {
            thumbs.load(origin, entry)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) {
            val img = remember(b) { b.asImageBitmap() }
            Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0x99000000)),
                contentAlignment = Alignment.Center,
            ) {
                Text("▶", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
