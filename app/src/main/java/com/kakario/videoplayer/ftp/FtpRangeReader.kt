package com.kakario.videoplayer.ftp

import com.kakario.videoplayer.data.FtpServer
import org.apache.commons.net.ftp.FTPClient
import java.io.IOException

/**
 * 썸네일 전용 FTP 연결 하나. 파일의 원하는 위치만 골라 읽는다 (FTP 이어받기 REST).
 * 화면 탐색·재생과 부딪히지 않도록 목록용 연결과 따로 쓴다.
 * 스레드 안전하지 않음 — 바깥에서 한 번에 하나씩만 호출할 것.
 */
class FtpRangeReader(private val server: FtpServer) {
    private var client: FTPClient? = null

    /** offset부터 length바이트(파일 끝이면 그보다 적게)를 받는다. 끊겨 있으면 다시 접속해서 한 번 더 시도. */
    fun read(path: String, offset: Long, length: Int): ByteArray {
        return try {
            readOnce(path, offset, length)
        } catch (e: IOException) {
            close()
            readOnce(path, offset, length)
        }
    }

    private fun readOnce(path: String, offset: Long, length: Int): ByteArray {
        val c = ensureConnected()
        c.setRestartOffset(offset)
        val input = c.retrieveFileStream(path)
            ?: throw IOException("파일을 열 수 없어요 (응답 ${c.replyCode})")
        val buf = ByteArray(length)
        var read = 0
        try {
            while (read < length) {
                val n = input.read(buf, read, length - read)
                if (n < 0) break
                read += n
            }
        } finally {
            try {
                input.close()
            } catch (_: IOException) {
            }
        }
        // 중간에 끊으면 서버가 426/226 응답을 보낸다. 정리하고, NOOP이 200이 아니면 다음엔 새로 접속.
        try {
            c.completePendingCommand()
            if (c.noop() != 200) close()
        } catch (_: IOException) {
            close()
        }
        client?.setRestartOffset(0)
        return if (read < length) buf.copyOf(read) else buf
    }

    private fun ensureConnected(): FTPClient {
        client?.let { c ->
            if (c.isConnected) return c
        }
        val c = newFtpClient(server)
        c.bufferSize = 256 * 1024
        c.connectAndLogin(server)
        client = c
        return c
    }

    fun close() {
        val c = client ?: return
        client = null
        try {
            if (c.isConnected) c.disconnect()
        } catch (_: IOException) {
        }
    }
}
