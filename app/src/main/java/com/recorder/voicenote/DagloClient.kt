package com.recorder.voicenote

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 서버가 4xx/5xx 로 거절했거나 연결이 안 될 때. 화면에서는 message 를 그대로 보여 준다. */
class DagloHttpException(val code: Int, message: String) : Exception(message)

/**
 * daglo 서버의 REST API 전부를 감싼 클라이언트.
 *
 * 인증은 웹과 같다 — 웹과 같은 계정으로 로그인해서 받은 세션 쿠키(`daglo_session`)를 요청마다
 * 실어 보낸다. 그래서 계정 메뉴(내 정보·비밀번호 변경·로그아웃)까지 웹과 똑같이 동작한다.
 *
 * 외부 라이브러리 없이 HttpURLConnection 만 쓴다 — 기존 DagloApi 와 같은 방침이다.
 */
class DagloClient(
    val baseUrl: String,
    private val sessionCookie: String,
    private val loginPath: String = DagloSettings.DEFAULT_LOGIN_PATH
) {

    constructor(settings: DagloSettings) :
        this(settings.serverUrl, settings.sessionCookie, settings.loginPath)

    val isConfigured: Boolean get() = baseUrl.isNotBlank()

    /** MediaPlayer·DownloadManager 처럼 우리 request() 를 못 쓰는 곳에 넘겨줄 인증 헤더. */
    val authHeaders: Map<String, String>
        get() = if (sessionCookie.isNotBlank()) {
            mapOf("Cookie" to "${DagloSettings.SESSION_COOKIE_NAME}=$sessionCookie")
        } else {
            emptyMap()
        }

    // ---------------------------------------------------------------- 로그인 / 계정

    /**
     * 로그인 화면이 '최초 설정'을 보여줄지 판단하는 데 쓴다. 이 경로는 로그인 없이 열려 있어서
     * 서버 주소가 맞는지 확인하는 용도로도 쓴다.
     */
    suspend fun authStatus(): DagloAuthStatus =
        DagloAuthStatus.from(JSONObject(get("/$loginPath/status")))

    /** 웹 로그인 화면과 같은 요청. 성공하면 세션 쿠키를 돌려준다. */
    suspend fun login(username: String, password: String): DagloLoginResult {
        val body = JSONObject().put("username", username.trim()).put("password", password)
        val response = sendRaw("POST", "/$loginPath/submit", body)
        return loginResultFrom(response)
    }

    /** 서버에 계정이 하나도 없을 때 첫 관리자 계정을 만든다 (웹 로그인 화면의 최초 설정). */
    suspend fun setupFirstAdmin(
        username: String,
        password: String,
        displayName: String
    ): DagloLoginResult {
        val body = JSONObject()
            .put("username", username.trim())
            .put("password", password)
            .put("display_name", displayName.trim())
        return loginResultFrom(sendRaw("POST", "/$loginPath/setup", body))
    }

    private fun loginResultFrom(response: HttpResponse): DagloLoginResult {
        val user = DagloUser.from(JSONObject(response.body).optJSONObject("user") ?: JSONObject())
        val cookie = response.sessionCookie
            ?: throw DagloHttpException(0, "서버가 세션을 내려주지 않았습니다. 서버 주소를 확인하세요.")
        return DagloLoginResult(user, cookie)
    }

    suspend fun me(): DagloUser = DagloUser.from(JSONObject(get("/api/auth/me")))

    suspend fun logout() {
        post("/api/auth/logout", null)
    }

    suspend fun changePassword(currentPassword: String, newPassword: String) {
        val body = JSONObject()
            .put("current_password", currentPassword)
            .put("new_password", newPassword)
        post("/api/auth/password", body)
    }

    fun audioUrl(boardId: Int): String = "$baseUrl/api/audio/$boardId"

    fun exportUrl(boardId: Int, format: String): String =
        "$baseUrl/api/boards/$boardId/export?format=$format"

    // ---------------------------------------------------------------- 폴더

    suspend fun getFolders(): List<DagloFolder> =
        DagloFolder.listFrom(JSONArray(get("/api/folders")))

    suspend fun createFolder(name: String): DagloFolder =
        DagloFolder.from(JSONObject(post("/api/folders", JSONObject().put("name", name))))

    suspend fun renameFolder(folderId: Int, name: String) {
        send("PATCH", "/api/folders/$folderId", JSONObject().put("name", name))
    }

    /**
     * 폴더 삭제. withBoards=false 면 안의 보드를 기본 폴더로 살려 옮기고, true 면 보드도 휴지통으로.
     * 응답의 moved_boards / trashed_boards / leftover_files 로 웹과 같은 안내 문구를 만든다.
     */
    suspend fun deleteFolder(folderId: Int, withBoards: Boolean): JSONObject =
        JSONObject(send("DELETE", "/api/folders/$folderId?with_boards=$withBoards", null))

    // ---------------------------------------------------------------- 보드 목록

    suspend fun getBoards(
        folderId: Int? = null,
        filterType: String? = null,
        search: String? = null
    ): List<DagloBoard> {
        val params = mutableListOf<String>()
        if (folderId != null) {
            params += "folder_id=$folderId"
        } else if (!filterType.isNullOrBlank()) {
            params += "filter_type=$filterType"
        }
        if (!search.isNullOrBlank()) params += "search=" + URLEncoder.encode(search.trim(), "UTF-8")

        val path = "/api/boards" + if (params.isEmpty()) "" else "?" + params.joinToString("&")
        return DagloBoard.listFrom(JSONArray(get(path)))
    }

    /** 서버가 녹음 폴더를 그 자리에서 한 번 훑는다. 웹의 `새로고침` 버튼과 같다. */
    suspend fun refreshBoards(): JSONObject = JSONObject(post("/api/boards/refresh", null))

    suspend fun getBoardDetail(boardId: Int): DagloBoardDetail =
        DagloBoardDetail.from(JSONObject(get("/api/boards/$boardId")))

    suspend fun updateBoardTitle(boardId: Int, title: String) {
        send("PATCH", "/api/boards/$boardId", JSONObject().put("title", title))
    }

    suspend fun toggleStar(boardId: Int): Boolean =
        JSONObject(post("/api/boards/$boardId/star", null)).optBoolean("is_starred", false)

    suspend fun deleteBoard(boardId: Int, permanent: Boolean = false) {
        send("DELETE", "/api/boards/$boardId?permanent=$permanent", null)
    }

    suspend fun restoreBoard(boardId: Int) {
        post("/api/boards/$boardId/restore", null)
    }

    /** 실패한 변환 재시도 / 다시 받아쓰기. 둘 다 서버에서는 같은 엔드포인트다. */
    suspend fun reprocessBoard(boardId: Int): JSONObject =
        JSONObject(post("/api/boards/$boardId/reprocess", null))

    suspend fun batchDelete(boardIds: Collection<Int>, permanent: Boolean) {
        val body = JSONObject()
            .put("board_ids", JSONArray(boardIds.toList()))
            .put("permanent", permanent)
        post("/api/boards/batch-delete", body)
    }

    suspend fun batchMove(boardIds: Collection<Int>, folderId: Int) {
        val body = JSONObject()
            .put("board_ids", JSONArray(boardIds.toList()))
            .put("folder_id", folderId)
        post("/api/boards/batch-move", body)
    }

    // ---------------------------------------------------------------- 스크립트

    /** 문단 전체를 통째로 보낸다(웹과 동일). 종료 시각은 서버가 이웃 문단을 보고 다시 계산한다. */
    suspend fun saveTranscript(boardId: Int, segments: List<DagloSegment>) {
        val arr = JSONArray()
        segments.forEachIndexed { idx, seg ->
            arr.put(
                JSONObject()
                    .put("start_time_ms", seg.startTimeMs)
                    .put("timestamp_str", seg.timestampStr)
                    .put("speaker", seg.speaker)
                    .put("content", seg.content.trim())
                    .put("sequence", idx)
            )
        }
        send("PUT", "/api/boards/$boardId/transcript", JSONObject().put("segments", arr))
    }

    suspend fun renameSpeaker(boardId: Int, oldName: String, newName: String) {
        val body = JSONObject().put("old_name", oldName).put("new_name", newName)
        post("/api/boards/$boardId/speakers/rename", body)
    }

    suspend fun generateKeywords(boardId: Int): List<String> {
        val arr = JSONObject(post("/api/boards/$boardId/keywords/generate", null))
            .optJSONArray("keywords") ?: JSONArray()
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    }

    suspend fun generateSummary(boardId: Int, type: String): String =
        JSONObject(post("/api/boards/$boardId/summary", JSONObject().put("summary_type", type)))
            .optString("content", "")

    // ---------------------------------------------------------------- 북마크

    suspend fun getChats(boardId: Int): List<DagloChat> {
        val arr = JSONArray(get("/api/boards/$boardId/chats"))
        return (0 until arr.length()).map { DagloChat.from(arr.getJSONObject(it)) }
    }

    suspend fun addBookmark(boardId: Int, ms: Long, tsStr: String, note: String): DagloBookmark {
        val body = JSONObject()
            .put("timestamp_ms", ms)
            .put("timestamp_str", tsStr)
            .put("note", note.ifBlank { "메모 없음" })
        return DagloBookmark.from(JSONObject(post("/api/boards/$boardId/bookmarks", body)))
    }

    suspend fun deleteBookmark(bookmarkId: Int) {
        send("DELETE", "/api/bookmarks/$bookmarkId", null)
    }

    // ---------------------------------------------------------------- 단어장

    suspend fun getGlossary(folderId: Int?): DagloGlossary {
        val path = if (folderId != null) "/api/glossary?folder_id=$folderId" else "/api/glossary"
        return DagloGlossary.from(JSONObject(get(path)))
    }

    suspend fun putGlossary(folderId: Int?, terms: List<DagloGlossaryTerm>) {
        val arr = JSONArray()
        terms.forEach { arr.put(JSONObject().put("term", it.term).put("note", it.note)) }
        val body = JSONObject().put("terms", arr)
        if (folderId != null) body.put("folder_id", folderId) else body.put("folder_id", JSONObject.NULL)
        send("PUT", "/api/glossary", body)
    }

    // ---------------------------------------------------------------- 보드챗 (SSE)

    /**
     * 답변을 토막(SSE `data:` 줄)마다 흘려 준다. 웹과 마찬가지로 서버가 전체 답변을 DB 에
     * 저장하므로, 여기서는 화면에 보여 주기만 하면 된다.
     */
    suspend fun streamChat(boardId: Int, message: String, onChunk: (String) -> Unit) {
        withContext(Dispatchers.IO) {
            val conn = open("/api/boards/$boardId/chat")
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Accept", "text/event-stream")
                conn.outputStream.use { out ->
                    out.write(JSONObject().put("message", message).toString().toByteArray(Charsets.UTF_8))
                }

                val code = conn.responseCode
                if (code !in 200..299) throw httpError(code, readBody(conn, code))

                conn.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (!line.startsWith("data: ")) continue
                        val payload = try {
                            JSONObject(line.substring(6).trim())
                        } catch (e: Exception) {
                            continue
                        }
                        payload.optString("error").takeIf { it.isNotBlank() }?.let {
                            throw DagloHttpException(500, it)
                        }
                        val text = payload.optString("text")
                        if (text.isNotEmpty()) onChunk(text)
                    }
                }
            } finally {
                runCatching { conn.disconnect() }
            }
        }
    }

    // ---------------------------------------------------------------- 업로드

    /** 웹의 `새 받아쓰기` 모달과 같은 업로드. 폴더는 번호로 고른다. */
    suspend fun uploadAudio(
        context: Context,
        uri: Uri,
        displayName: String,
        folderId: Int?
    ): JSONObject = withContext(Dispatchers.IO) {
        val input: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw DagloHttpException(0, "파일을 열 수 없습니다")
        val boundary = "----dagloBoundary${System.currentTimeMillis()}"

        val conn = open("/api/boards/upload")
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setChunkedStreamingMode(CHUNK_SIZE)
            conn.readTimeout = UPLOAD_TIMEOUT_MS
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            input.use { stream ->
                conn.outputStream.use { out ->
                    if (folderId != null) writeField(out, boundary, "folder_id", folderId.toString())
                    writeFilePart(out, boundary, displayName, stream)
                    out.write("--$boundary--\r\n".toByteArray())
                    out.flush()
                }
            }
            val code = conn.responseCode
            val body = readBody(conn, code)
            if (code !in 200..299) throw httpError(code, body)
            JSONObject(body)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    // ---------------------------------------------------------------- 내부 구현

    /** 응답 본문과, 있으면 서버가 새로 내려준 세션 쿠키. */
    private class HttpResponse(val body: String, val sessionCookie: String?)

    private suspend fun get(path: String): String = send("GET", path, null)

    private suspend fun post(path: String, body: JSONObject?): String = send("POST", path, body)

    private suspend fun send(method: String, path: String, body: JSONObject?): String =
        sendRaw(method, path, body).body

    private suspend fun sendRaw(method: String, path: String, body: JSONObject?): HttpResponse =
        withContext(Dispatchers.IO) {
            if (!isConfigured) throw DagloHttpException(0, "서버 주소가 설정되지 않았습니다")
            val conn = open(path)
            try {
                conn.requestMethod = method
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val text = readBody(conn, code)
                if (code !in 200..299) throw httpError(code, text)
                HttpResponse(text, sessionCookieOf(conn))
            } catch (e: DagloHttpException) {
                throw e
            } catch (e: Exception) {
                throw DagloHttpException(0, e.message ?: "서버에 연결할 수 없습니다")
            } finally {
                runCatching { conn.disconnect() }
            }
        }

    /** `Set-Cookie: daglo_session=...; HttpOnly; ...` 에서 값만 꺼낸다. */
    private fun sessionCookieOf(conn: HttpURLConnection): String? {
        val prefix = DagloSettings.SESSION_COOKIE_NAME + "="
        conn.headerFields?.forEach { (name, values) ->
            if (name == null || !name.equals("Set-Cookie", ignoreCase = true)) return@forEach
            values.forEach { raw ->
                val first = raw.split(";").firstOrNull()?.trim().orEmpty()
                if (first.startsWith(prefix)) {
                    val value = first.removePrefix(prefix)
                    if (value.isNotBlank()) return value
                }
            }
        }
        return null
    }

    private fun open(path: String): HttpURLConnection =
        (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            authHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }

    private fun readBody(conn: HttpURLConnection, code: Int): String {
        val stream = try {
            if (code in 200..299) conn.inputStream else conn.errorStream
        } catch (e: Exception) {
            null
        } ?: return ""
        return runCatching { stream.bufferedReader().use { it.readText() } }.getOrDefault("")
    }

    /** FastAPI 는 오류를 {"detail": "..."} 로 준다. 사용자에게 그대로 보여주면 원인 파악이 쉽다. */
    private fun httpError(code: Int, body: String): DagloHttpException {
        val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull()
        val message = when {
            !detail.isNullOrBlank() -> detail
            code == 401 -> "로그인이 필요합니다."
            code == 403 -> "권한이 없습니다."
            else -> "서버 오류 (HTTP $code)"
        }
        return DagloHttpException(code, message)
    }

    private fun writeField(out: OutputStream, boundary: String, name: String, value: String) {
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
        fileName: String,
        input: InputStream
    ) {
        val header = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n"
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
        // AI 요약·키워드 추출은 서버에서 Gemini 응답을 기다리므로 넉넉히 둔다
        private const val READ_TIMEOUT_MS = 180_000
        private const val UPLOAD_TIMEOUT_MS = 300_000
        private const val CHUNK_SIZE = 64 * 1024
    }
}
