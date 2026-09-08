package com.recorder.voicenote

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** 업로드 결과. 다시 시도할 만한 실패와, 다시 해도 소용없는 실패를 나눠 돌려준다. */
sealed class ApiResult {
    data class Success(val body: String) : ApiResult()
    /** 네트워크 끊김·서버 꺼짐·5xx — 나중에 다시 시도한다 */
    data class Retryable(val message: String) : ApiResult()
    /** 로그인 만료·파일 거부 — 다시 보내도 같으므로 사람이 손대야 한다 */
    data class Fatal(val message: String, val authFailed: Boolean = false) : ApiResult()
}

/**
 * 서버로 녹음을 올리는 것 하나만 하는 HTTP 클라이언트.
 *
 * 외부 라이브러리 없이 HttpURLConnection 만 쓴다. 3시간짜리 녹음도 메모리에 통째로 올리지 않고
 * 스트리밍으로 흘려보낸다.
 */
class DagloApi(private val serverUrl: String) {

    /** 서버가 살아 있고 세션이 유효한지 확인한다. */
    fun ping(): ApiResult {
        if (serverUrl.isEmpty()) return ApiResult.Fatal("서버 주소가 없습니다")
        return request("$serverUrl/api/ping") { conn -> conn.requestMethod = "GET" }
    }

    /**
     * 녹음 파일 하나를 올린다. 서버는 받자마자 받아쓰기 큐에 넣는다.
     * 폴더는 이름으로 보낸다 — 같은 이름이 없으면 서버가 만들어 준다.
     */
    fun upload(file: File, folderName: String): ApiResult {
        if (serverUrl.isEmpty()) return ApiResult.Fatal("서버 주소가 없습니다")
        if (!file.isFile || file.length() == 0L) return ApiResult.Fatal("올릴 녹음 파일이 없습니다")

        val boundary = "----dagloBoundary${System.currentTimeMillis()}"
        return file.inputStream().use { input ->
            request("$serverUrl/api/boards/upload") { conn ->
                conn.requestMethod = "POST"
                conn.doOutput = true
                // 길이를 모른 채 흘려보낸다. 파일 크기와 무관하게 메모리를 거의 쓰지 않는다.
                conn.setChunkedStreamingMode(CHUNK_SIZE)
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                conn.outputStream.use { out ->
                    writeField(out, boundary, "folder_name", folderName)
                    writeFile(out, boundary, file.name, input)
                    out.write("--$boundary--\r\n".toByteArray())
                    out.flush()
                }
            }
        }
    }

    // ------------------------------------------------------------------------------

    private fun request(urlString: String, prepare: (HttpURLConnection) -> Unit): ApiResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                // 웹 화면에서 로그인하며 받은 세션 쿠키를 그대로 실어 보낸다
                DagloSession.cookieHeader(serverUrl)?.let { setRequestProperty("Cookie", it) }
            }
            prepare(conn)

            val code = conn.responseCode
            val body = readBody(conn)
            when {
                code in 200..299 -> ApiResult.Success(body)
                // 401/403 은 세션이 끊긴 것이라 다시 보내도 같다. 웹 화면에서 로그인해야 한다.
                code == 401 || code == 403 -> ApiResult.Fatal(
                    "로그인이 풀렸습니다. 앱에서 다시 로그인한 뒤 올려 주세요.",
                    authFailed = true
                )
                // 404 도 대개 로그인 문제다 (로그인 안 한 요청을 서버가 없는 페이지로 감춘다)
                code == 404 -> ApiResult.Fatal(
                    "서버가 요청을 받지 않았습니다. 로그인 상태와 서버 주소를 확인해 주세요.",
                    authFailed = true
                )
                code == 413 -> ApiResult.Fatal("파일이 서버 허용 크기를 넘습니다.")
                code == 400 -> ApiResult.Fatal(detailOf(body) ?: "서버가 요청을 거부했습니다.")
                else -> ApiResult.Retryable(detailOf(body) ?: "서버 오류 (HTTP $code)")
            }
        } catch (e: Exception) {
            // 연결 실패·타임아웃: 서버가 꺼져 있거나 와이파이가 끊긴 상황이므로 나중에 다시 시도한다
            ApiResult.Retryable(e.message ?: "서버에 연결할 수 없습니다")
        } finally {
            try {
                conn?.disconnect()
            } catch (e: Exception) {
                // 이미 끊긴 연결이라 더 할 일이 없다
            }
        }
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream: InputStream = try {
            if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        } catch (e: Exception) {
            null
        } ?: return ""
        return try {
            stream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
    }

    /** FastAPI 는 오류를 {"detail": "..."} 로 준다. 그대로 보여주면 원인 파악이 쉽다. */
    private fun detailOf(body: String): String? {
        if (body.isBlank()) return null
        return try {
            JSONObject(body).optString("detail").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeField(out: OutputStream, boundary: String, name: String, value: String) {
        val header = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"$name\"\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(value.toByteArray(Charsets.UTF_8))
        out.write("\r\n".toByteArray())
    }

    private fun writeFile(out: OutputStream, boundary: String, fileName: String, input: InputStream) {
        val header = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n" +
            "Content-Type: audio/mp4\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))

        val buffer = ByteArray(CHUNK_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            out.write(buffer, 0, read)
        }
        out.write("\r\n".toByteArray())
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        // 서버가 파일을 다 받고 DB 에 쓸 때까지 기다린다. 큰 파일도 여유 있게.
        private const val READ_TIMEOUT_MS = 180_000
        private const val CHUNK_SIZE = 64 * 1024
    }
}
