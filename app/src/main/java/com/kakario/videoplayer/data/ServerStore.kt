package com.kakario.videoplayer.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** FTP 서버 접속 정보. */
data class FtpServer(
    val id: Long,
    val name: String,
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
    /** "UTF-8" 또는 "EUC-KR" — 한글 파일명이 깨지면 EUC-KR로 바꿔 쓴다. */
    val encoding: String = "UTF-8",
    /** 암호화 연결(FTPS, 명시적 TLS) — 서버가 지원하면 켜는 게 안전. */
    val secure: Boolean = false,
)

/** 서버 목록을 폰 안(SharedPreferences)에 저장/불러오기. */
class ServerStore(context: Context) {
    private val prefs = context.getSharedPreferences("servers", Context.MODE_PRIVATE)

    fun load(): List<FtpServer> = loadRaw().also { list ->
        // 예전 버전이 평문으로 저장한 비밀번호가 남아 있으면 암호화해서 다시 저장
        if (prefs.getString(KEY, null)?.contains("\"password\"") == true && list.isNotEmpty()) save(list)
    }

    private fun loadRaw(): List<FtpServer> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                FtpServer(
                    id = o.getLong("id"),
                    name = o.getString("name"),
                    host = o.getString("host"),
                    port = o.getInt("port"),
                    user = o.optString("user"),
                    // 비밀번호는 안드로이드 보안 저장소(Keystore) 키로 암호화해 둔다. 예전 평문 기록도 읽는다.
                    password = o.optString("pw_enc").takeIf { it.isNotEmpty() }?.let { SecretBox.decrypt(it) }
                        ?: o.optString("password"),
                    encoding = o.optString("encoding", "UTF-8"),
                    secure = o.optBoolean("secure", false),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(servers: List<FtpServer>) {
        val arr = JSONArray()
        servers.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("host", s.host)
                    .put("port", s.port)
                    .put("user", s.user)
                    .put("pw_enc", SecretBox.encrypt(s.password))
                    .put("encoding", s.encoding)
                    .put("secure", s.secure)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "server_list"
    }
}
