package com.kakario.videoplayer.ftp

import com.kakario.videoplayer.data.FtpServer
import org.apache.commons.net.ftp.FTPClient
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * FTP 영상을 재생기(VLC)에 넘겨주는 폰 안의 작은 중계 서버.
 *
 * 재생기는 http://127.0.0.1:포트/... 주소로 "몇 바이트부터 주세요"(Range)라고 요청하고,
 * 이 서버가 FTP 이어받기(REST)로 그 위치부터 받아서 그대로 흘려준다.
 * → 영상 전체를 받지 않고 바로 재생, 앞뒤로 건너뛰기도 그 위치부터 다시 받는다.
 *
 * VLC가 FTP에 직접 붙지 않고 이 서버를 거치는 이유:
 *  - 목록 화면과 같은 접속 방식을 써서 외부(LTE/5G) 접속·패시브 모드가 똑같이 동작
 *  - EUC-KR 한글 파일명도 서버 설정 그대로 처리
 */
object FtpStreamServer {
    private data class Target(val server: FtpServer, val path: String, val size: Long)

    private val targets = ConcurrentHashMap<String, Target>()

    // ── 테스트용 접속 기록 (누가: "AI" = 자막용 FFmpeg, "재생" = VLC)
    private class Stat {
        var requests = 0
        var fails = 0
        var connectMsTotal = 0L
        var lastConnectMs = 0L
        var active = 0
        var lastError: String? = null
    }
    private val stats = ConcurrentHashMap<String, Stat>()

    /** 예) "요청 12 · 거절 1 · 접속 평균 1.4초(최근 0.9) · 동시 2" — 기록 없으면 null. */
    fun statLine(who: String): String? {
        val st = stats[who] ?: return null
        synchronized(st) {
            val ok = (st.requests - st.fails).coerceAtLeast(1)
            val avg = st.connectMsTotal / ok
            return buildString {
                append("요청 ${st.requests} · 거절 ${st.fails} · 접속 평균 ${"%.1f".format(avg / 1000.0)}초")
                append("(최근 ${"%.1f".format(st.lastConnectMs / 1000.0)}) · 동시 ${st.active}")
                st.lastError?.let { append(" · 마지막 오류: ").append(it.take(30)) }
            }
        }
    }
    private val workers = Executors.newCachedThreadPool()
    private var serverSocket: ServerSocket? = null

    /** 재생기에 넘길 주소를 만든다. 끝나면 release()로 정리. */
    @Synchronized
    fun urlFor(server: FtpServer, path: String, size: Long): String {
        val socket = serverSocket?.takeIf { !it.isClosed } ?: start()
        val id = UUID.randomUUID().toString().replace("-", "")
        targets[id] = Target(server, path, size)
        val ext = path.substringAfterLast('.', "bin").lowercase()
        return "http://127.0.0.1:${socket.localPort}/v/$id/video.$ext"
    }

    fun release(url: String) {
        val id = url.substringAfter("/v/", "").substringBefore('/')
        if (id.isNotEmpty()) targets.remove(id)
    }

    private fun start(): ServerSocket {
        val ss = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        thread(isDaemon = true, name = "ftp-stream-accept") {
            while (!ss.isClosed) {
                val s = try {
                    ss.accept()
                } catch (_: IOException) {
                    break
                }
                workers.execute { handle(s) }
            }
        }
        return ss
    }

    private fun handle(sock: Socket) {
        try {
            sock.use {
                sock.soTimeout = 30_000
                sock.tcpNoDelay = true
                val input = BufferedInputStream(sock.getInputStream())
                val out = sock.getOutputStream()

                val requestLine = readLine(input) ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }

                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0) ?: return
                val uri = parts.getOrNull(1) ?: return
                val id = uri.substringAfter("/v/", "").substringBefore('/')
                val target = targets[id]
                if (target == null) {
                    writeStatus(out, "404 Not Found", 0)
                    return
                }

                val size = target.size
                var start = 0L
                var end = size - 1
                val range = headers["range"]
                val match = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
                if (match != null) {
                    val (a, b) = match.destructured
                    if (a.isNotEmpty()) {
                        start = a.toLong()
                        if (b.isNotEmpty()) end = minOf(b.toLong(), size - 1)
                    } else if (b.isNotEmpty()) {
                        // "bytes=-500" → 마지막 500바이트
                        start = maxOf(0L, size - b.toLong())
                    }
                }
                if (size <= 0 || start >= size) {
                    out.write(
                        ("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\n" +
                            "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray()
                    )
                    out.flush()
                    return
                }
                val length = end - start + 1

                if (method == "HEAD") {
                    writeHeaders(out, match != null, start, end, size, length, target.path)
                    return
                }

                // 누가 요청했는지 (VLC = 재생, FFmpeg(Lavf) = AI 자막)
                val agent = headers["user-agent"].orEmpty()
                val who = if (agent.contains("Lavf", true) || agent.contains("ffmpeg", true)) "AI" else "재생"
                val st = stats.getOrPut(who) { Stat() }
                synchronized(st) {
                    st.requests++
                    st.active++
                }

                // FTP를 먼저 열어보고, 실패하면 재생기에 오류를 알려준다
                val client = FTPClient()
                client.bufferSize = 256 * 1024
                val c0 = System.currentTimeMillis()
                val data: InputStream = try {
                    client.connectAndLogin(target.server)
                    if (start > 0) client.setRestartOffset(start)
                    (client.retrieveFileStream(target.path)
                        ?: throw IOException("파일 열기 실패 (응답 ${client.replyCode})")).also {
                        val took = System.currentTimeMillis() - c0
                        synchronized(st) {
                            st.connectMsTotal += took
                            st.lastConnectMs = took
                        }
                    }
                } catch (e: Exception) {
                    runCatching { client.disconnect() }
                    synchronized(st) {
                        st.fails++
                        st.active--
                        st.lastError = e.message ?: e.javaClass.simpleName
                    }
                    writeStatus(out, "502 Bad Gateway", 0)
                    return
                }

                try {
                    writeHeaders(out, match != null, start, end, size, length, target.path)
                    val buf = ByteArray(64 * 1024)
                    var remaining = length
                    while (remaining > 0) {
                        val n = data.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (n < 0) break
                        out.write(buf, 0, n)
                        remaining -= n
                    }
                    out.flush()
                } catch (_: IOException) {
                    // 재생기가 건너뛰기 등으로 연결을 끊은 것 — 정상
                } finally {
                    // 받는 도중에 끊으면 FTP 응답 순서가 꼬이므로 연결째 닫는다
                    runCatching { data.close() }
                    runCatching { client.disconnect() }
                    synchronized(st) { st.active-- }
                }
            }
        } catch (_: IOException) {
        }
    }

    private fun writeHeaders(
        out: OutputStream,
        partial: Boolean,
        start: Long,
        end: Long,
        size: Long,
        length: Long,
        path: String,
    ) {
        val sb = StringBuilder()
        sb.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        sb.append("Content-Type: ").append(mimeFor(path)).append("\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Content-Length: ").append(length).append("\r\n")
        if (partial) sb.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(size).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
        out.flush()
    }

    private fun writeStatus(out: OutputStream, status: String, length: Int) {
        out.write("HTTP/1.1 $status\r\nContent-Length: $length\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
    }

    private fun mimeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "ts", "m2ts", "mts" -> "video/mp2t"
        "wmv", "asf" -> "video/x-ms-asf"
        "mov" -> "video/quicktime"
        else -> "application/octet-stream"
    }

    /** HTTP 요청의 한 줄 (CRLF 제외). 연결이 끝나면 null. */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }
}
