package com.recorder.voicenote

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** 서버 통신 결과. 재시도해도 소용없는 실패(Fatal)와 잠깐 뒤 다시 해볼 실패(Retryable)를 구분한다. */
sealed class ApiResult {
    data class Success(val body: String) : ApiResult()

    /** 네트워크 끊김·서버 재시작·5xx 처럼 나중에 다시 하면 될 실패 */
    data class Retryable(val message: String) : ApiResult()

    /** 토큰 오류·지원하지 않는 형식처럼 다시 해도 똑같이 실패할 것 */
    data class Fatal(val message: String) : ApiResult()
}

/**
 * daglo 서버와 통신하는 최소한의 HTTP 클라이언트.
 *
 * 외부 라이브러리(OkHttp 등) 없이 HttpURLConnection 만 쓴다. 이 앱이 서버에 하는 일은
 * '연결 확인'과 '녹음 파일 업로드' 둘뿐이라 그 정도면 충분하고, 의존성이 늘지 않는다.
 * 업로드는 파일 전체를 메모리에 올리지 않고 스트리밍으로 흘려보낸다(3시간 녹음도 안전).
 */
class DagloApi(private val serverUrl: String, private val apiToken: String) {

    /** 저장된 설정으로 만들 때 쓰는 편의 생성자 */
    constructor(settings: DagloSettings) : this(settings.serverUrl, settings.apiToken)

    /** 서버가 살아있는지, 주소가 맞는지 확인한다. (로그인 없이 열려 있는 유일한 엔드포인트) */
    fun ping(): ApiResult {
        val base = serverUrl
        if (base.isBlank()) return ApiResult.Fatal("서버 주소가 비어 있습니다")

        return request("$base/api/ping") { conn ->
            conn.requestMethod = "GET"
        }
    }

    /**
     * 녹음 파일 하나를 서버에 올린다. 서버는 받자마자 STT 변환 큐에 넣는다.
     * 폴더는 이름으로 보낸다 — 서버에 같은 이름이 없으면 서버가 만들어 준다.
     */
    fun uploadRecording(
        context: Context,
        contentUri: Uri?,
        filePath: String?,
        displayName: String,
        folderName: String
    ): ApiResult {
        val base = serverUrl
        if (base.isBlank()) return ApiResult.Fatal("서버 주소가 비어 있습니다")

        val stream: InputStream = try {
            when {
                contentUri != null -> context.contentResolver.openInputStream(contentUri)
                filePath != null -> File(filePath).inputStream()
                else -> null
            }
        } catch (e: Exception) {
            null
        } ?: return ApiResult.Fatal("녹음 파일을 열 수 없습니다")

        val boundary = "----dagloBoundary${System.currentTimeMillis()}"
        return stream.use { input ->
            request("$base/api/boards/upload") { conn ->
                conn.requestMethod = "POST"
                conn.doOutput = true
                // 길이를 모른 채 흘려보낸다. 파일 크기와 무관하게 메모리를 거의 쓰지 않는다.
                conn.setChunkedStreamingMode(CHUNK_SIZE)
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                conn.outputStream.use { out ->
                    writeFormField(out, boundary, "folder_name", folderName)
                    writeFilePart(out, boundary, "file", displayName, input)
                    out.write("--$boundary--\r\n".toByteArray())
                    out.flush()
                }
            }
        }
    }

    /** 업로드 응답에서 보드 번호를 꺼낸다. 실패해도 업로드 자체는 성공이므로 null 만 돌려준다. */
    fun parseBoardId(body: String): Int? {
        return try {
            JSONObject(body).optInt("board_id").takeIf { it > 0 }
        } catch (e: Exception) {
            null
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
                val token = apiToken
                if (token.isNotBlank()) setRequestProperty("X-API-Key", token)
            }
            prepare(conn)

            val code = conn.responseCode
            val body = readBody(conn)
            when {
                code in 200..299 -> ApiResult.Success(body)
                // 401/403 은 토큰이 틀린 것이므로 재시도해도 똑같다. 사용자가 설정을 고쳐야 한다.
                code == 401 || code == 403 ->
                    ApiResult.Fatal("서버가 인증을 거부했습니다. API 토큰을 확인하세요. (HTTP $code)")
                code == 413 -> ApiResult.Fatal("파일이 서버 허용 크기를 넘습니다. (HTTP 413)")
                code == 400 -> ApiResult.Fatal(errorMessage(body) ?: "서버가 요청을 거부했습니다. (HTTP 400)")
                else -> ApiResult.Retryable(errorMessage(body) ?: "서버 오류 (HTTP $code)")
            }
        } catch (e: Exception) {
            // 연결 실패·타임아웃: 서버가 꺼져 있거나 와이파이가 끊긴 상황이므로 나중에 다시 시도한다.
            ApiResult.Retryable(e.message ?: "서버에 연결할 수 없습니다")
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = try {
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

    /** FastAPI 는 오류를 {"detail": "..."} 로 준다. 사용자에게 그대로 보여주면 원인 파악이 쉽다. */
    private fun errorMessage(body: String): String? {
        if (body.isBlank()) return null
        return try {
            JSONObject(body).optString("detail").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeFormField(out: OutputStream, boundary: String, name: String, value: String) {
        val header = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"$name\"\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n\r\n"
        out.write(header.toByteArray())
        out.write(value.toByteArray(Charsets.UTF_8))
        out.write("\r\n".toByteArray())
    }

    private fun writeFilePart(
        out: OutputStream,
        boundary: String,
        name: String,
        fileName: String,
        input: InputStream
    ) {
        val header = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"$name\"; filename=\"$fileName\"\r\n" +
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
