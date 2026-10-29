package com.kakario.videoplayer.model

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 외부 자막 파일(.smi .srt .ass ...) 찾기와 인코딩 정리.
 *
 * FTP 영상은 재생기가 폰 안 중계 주소로 열기 때문에 옆에 있는 자막을 스스로 못 찾는다.
 * 그래서 앱이 같은 폴더에서 자막을 골라 받아두고, UTF-8로 바꿔서 재생기에 직접 붙인다.
 * (예전 한국 자막은 대부분 EUC-KR/CP949라 그대로 넘기면 글자가 깨진다)
 */
object Subtitles {
    val EXT = setOf("smi", "srt", "ass", "ssa", "sub", "vtt")
    private const val MAX_BYTES = 5L * 1024 * 1024

    fun isSubtitle(e: FileEntry): Boolean = !e.isDirectory && e.extension in EXT && e.size in 1..MAX_BYTES

    /**
     * 영상에 맞는 자막 후보. 이름이 같은 것(영상.smi, 영상.ko.srt 등)을 먼저,
     * 폴더에 영상이 하나뿐이면 이름이 달라도 그 폴더의 자막을 쓴다.
     */
    fun findFor(video: FileEntry, folder: List<FileEntry>): List<FileEntry> {
        val base = video.name.substringBeforeLast('.').lowercase()
        val subs = folder.filter { isSubtitle(it) }
        val exact = subs.filter { it.name.substringBeforeLast('.').lowercase() == base }
        val prefixed = subs.filter { it !in exact && it.name.lowercase().startsWith("$base.") }
        val matched = exact + prefixed
        if (matched.isNotEmpty()) return matched.sortedBy { order(it) }
        val videoCount = folder.count { it.isVideo }
        return if (videoCount == 1) subs.sortedBy { order(it) } else emptyList()
    }

    /** smi → srt → ass 순으로 우선 */
    private fun order(e: FileEntry) = when (e.extension) {
        "smi" -> 0
        "srt" -> 1
        "ass", "ssa" -> 2
        else -> 3
    }

    /** 받아온 자막 바이트를 UTF-8(BOM 포함)로 바꿔 캐시에 저장하고 파일을 돌려준다. */
    fun saveAsUtf8(cacheDir: File, entry: FileEntry, bytes: ByteArray): File {
        val dir = File(cacheDir, "subs").apply { mkdirs() }
        // 오래된 자막 캐시는 정리 (20개 넘으면 오래된 것부터)
        dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(20)?.forEach { it.delete() }
        val safe = entry.name.replace(Regex("[^\\w.가-힣-]"), "_")
        val out = File(dir, "${entry.path.hashCode().toUInt()}_$safe")
        val text = decode(bytes)
        out.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8))
        return out
    }

    private fun decode(bytes: ByteArray): String {
        // BOM이 있으면 그대로 따른다
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        // 올바른 UTF-8이면 UTF-8, 아니면 한국어 윈도우 인코딩(CP949)
        try {
            return Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
        }
        return String(bytes, koreanCharset())
    }

    private fun koreanCharset(): Charset {
        for (name in listOf("MS949", "x-windows-949", "windows-949", "EUC-KR")) {
            try {
                return Charset.forName(name)
            } catch (_: Exception) {
            }
        }
        return Charsets.ISO_8859_1
    }
}
