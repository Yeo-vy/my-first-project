package com.recorder.voicenote

import org.json.JSONArray
import org.json.JSONObject

/**
 * daglo 서버 REST API 응답을 그대로 옮긴 모델들.
 *
 * 웹 화면(server/static/app.js)이 쓰는 필드와 이름을 맞춰 뒀다. 서버 응답이 바뀌면 여기 파서만
 * 고치면 되도록, 화면 코드에서는 JSONObject 를 직접 만지지 않는다.
 */

/** 로그인한 사용자. 웹 사이드바의 계정 배지에 쓰는 값과 같다 (server/auth.py user_to_dict). */
data class DagloUser(
    val id: Int,
    val username: String,
    val displayName: String,
    val isAdmin: Boolean
) {
    /** 배지에 쓰는 두 글자. 웹도 이름 앞 두 글자를 쓴다. */
    val initials: String get() = displayName.take(2).ifBlank { "··" }

    companion object {
        fun from(o: JSONObject) = DagloUser(
            id = o.optInt("id"),
            username = o.optString("username"),
            displayName = o.optString("display_name").ifBlank { o.optString("username") },
            isAdmin = o.optBoolean("is_admin", false)
        )
    }
}

/** 로그인 화면이 '로그인'을 보여줄지 '최초 계정 만들기'를 보여줄지 판단하는 데 쓴다. */
data class DagloAuthStatus(
    val setupRequired: Boolean,
    val authenticated: Boolean,
    val user: DagloUser?
) {
    companion object {
        fun from(o: JSONObject) = DagloAuthStatus(
            setupRequired = o.optBoolean("setup_required", false),
            authenticated = o.optBoolean("authenticated", false),
            user = o.optJSONObject("user")?.let { DagloUser.from(it) }
        )
    }
}

/** 로그인 성공 결과: 누구인지 + 앞으로 실어 보낼 세션 쿠키. */
data class DagloLoginResult(val user: DagloUser, val sessionCookie: String)

data class DagloFolder(
    val id: Int,
    val name: String,
    val boardCount: Int
) {
    /** 서버도 삭제를 막는 폴더. 웹과 동일하게 삭제 버튼 자체를 만들지 않는다. */
    val canDelete: Boolean get() = name != "기본 폴더"

    companion object {
        fun from(o: JSONObject) = DagloFolder(
            id = o.optInt("id"),
            name = o.optString("name"),
            boardCount = o.optInt("board_count", 0)
        )

        fun listFrom(arr: JSONArray): List<DagloFolder> =
            (0 until arr.length()).map { from(arr.getJSONObject(it)) }
    }
}

/** 목록에 뜨는 보드 한 줄. */
data class DagloBoard(
    val id: Int,
    val folderId: Int?,
    val folderName: String,
    val title: String,
    val durationSeconds: Double,
    val durationStr: String,
    val status: String,
    val progressPercent: Int,
    val errorMessage: String?,
    val isStarred: Boolean,
    val isDeleted: Boolean,
    val keywords: List<String>,
    val hasAudio: Boolean,
    val createdAt: String
) {
    val inFlight: Boolean get() = status in IN_FLIGHT_STATUSES

    companion object {
        val IN_FLIGHT_STATUSES = listOf("PROCESSING", "PENDING")

        fun from(o: JSONObject) = DagloBoard(
            id = o.optInt("id"),
            folderId = o.optInt("folder_id").takeIf { it > 0 },
            folderName = o.optString("folder_name", "기본 폴더"),
            title = o.optString("title"),
            durationSeconds = o.optDouble("duration_seconds", 0.0),
            durationStr = o.optString("duration_str", ""),
            status = o.optString("status", "COMPLETED"),
            progressPercent = o.optInt("progress_percent", 0),
            errorMessage = o.optString("error_message").takeIf { it.isNotBlank() && it != "null" },
            isStarred = o.optBoolean("is_starred", false),
            isDeleted = o.optBoolean("is_deleted", false),
            keywords = jsonStringList(o.optJSONArray("keywords")),
            hasAudio = o.optBoolean("has_audio", false),
            createdAt = o.optString("created_at", "")
        )

        fun listFrom(arr: JSONArray): List<DagloBoard> =
            (0 until arr.length()).map { from(arr.getJSONObject(it)) }
    }
}

data class DagloSegment(
    val id: Int,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val timestampStr: String,
    val speaker: String,
    val content: String,
    val sequence: Int
) {
    companion object {
        fun from(o: JSONObject) = DagloSegment(
            id = o.optInt("id"),
            startTimeMs = o.optLong("start_time_ms", 0L),
            endTimeMs = o.optLong("end_time_ms", 0L),
            timestampStr = o.optString("timestamp_str", "[00:00]"),
            speaker = o.optString("speaker", "화자 1").ifBlank { "화자 1" },
            content = o.optString("content", ""),
            sequence = o.optInt("sequence", 0)
        )
    }
}

data class DagloSummary(
    val id: Int,
    val summaryType: String,
    val title: String,
    val content: String
) {
    companion object {
        fun from(o: JSONObject) = DagloSummary(
            id = o.optInt("id"),
            summaryType = o.optString("summary_type", "BASIC"),
            title = o.optString("title", ""),
            content = o.optString("content", "")
        )
    }
}

data class DagloBookmark(
    val id: Int,
    val timestampMs: Long,
    val timestampStr: String,
    val note: String
) {
    companion object {
        fun from(o: JSONObject) = DagloBookmark(
            id = o.optInt("id"),
            timestampMs = o.optLong("timestamp_ms", 0L),
            timestampStr = o.optString("timestamp_str", "[00:00]"),
            note = o.optString("note", "")
        )
    }
}

data class DagloChat(
    val id: Int,
    val role: String,
    val message: String
) {
    val isUser: Boolean get() = role == "user"

    companion object {
        fun from(o: JSONObject) = DagloChat(
            id = o.optInt("id"),
            role = o.optString("role", "assistant"),
            message = o.optString("message", "")
        )
    }
}

/** 보드 상세 화면이 한 번에 받아 오는 전부. */
data class DagloBoardDetail(
    val id: Int,
    val folderId: Int?,
    val folderName: String,
    val title: String,
    val durationSeconds: Double,
    val durationStr: String,
    val status: String,
    val progressPercent: Int,
    val errorMessage: String?,
    val isStarred: Boolean,
    val keywords: List<String>,
    val hasAudio: Boolean,
    val audioPath: String?,
    val segments: List<DagloSegment>,
    val summaries: List<DagloSummary>,
    val bookmarks: List<DagloBookmark>,
    val createdAt: String
) {
    val inFlight: Boolean get() = status in DagloBoard.IN_FLIGHT_STATUSES

    fun summaryOf(type: String): String? =
        summaries.firstOrNull { it.summaryType == type }?.content?.takeIf { it.isNotBlank() }

    companion object {
        fun from(o: JSONObject): DagloBoardDetail {
            fun <T> parse(name: String, block: (JSONObject) -> T): List<T> {
                val arr = o.optJSONArray(name) ?: return emptyList()
                return (0 until arr.length()).map { block(arr.getJSONObject(it)) }
            }

            return DagloBoardDetail(
                id = o.optInt("id"),
                folderId = o.optInt("folder_id").takeIf { it > 0 },
                folderName = o.optString("folder_name", "기본 폴더"),
                title = o.optString("title"),
                durationSeconds = o.optDouble("duration_seconds", 0.0),
                durationStr = o.optString("duration_str", ""),
                status = o.optString("status", "COMPLETED"),
                progressPercent = o.optInt("progress_percent", 0),
                errorMessage = o.optString("error_message").takeIf { it.isNotBlank() && it != "null" },
                isStarred = o.optBoolean("is_starred", false),
                keywords = jsonStringList(o.optJSONArray("keywords")),
                hasAudio = o.optBoolean("has_audio", false),
                audioPath = o.optString("audio_url").takeIf { it.isNotBlank() && it != "null" },
                segments = parse("segments") { DagloSegment.from(it) }.sortedBy { it.sequence },
                summaries = parse("summaries") { DagloSummary.from(it) },
                bookmarks = parse("bookmarks") { DagloBookmark.from(it) }.sortedBy { it.timestampMs },
                createdAt = o.optString("created_at", "")
            )
        }
    }
}

/** 단어장. 폴더 전용 + 모든 폴더 공통 두 벌을 함께 다룬다. */
data class DagloGlossaryTerm(val term: String, val note: String)

data class DagloGlossary(
    val folderId: Int?,
    val folderName: String?,
    val folderTerms: List<DagloGlossaryTerm>,
    val commonTerms: List<DagloGlossaryTerm>,
    val maxTerms: Int
) {
    companion object {
        fun from(o: JSONObject): DagloGlossary {
            fun terms(arr: JSONArray?): List<DagloGlossaryTerm> {
                if (arr == null) return emptyList()
                return (0 until arr.length()).map {
                    val t = arr.getJSONObject(it)
                    DagloGlossaryTerm(t.optString("term"), t.optString("note", ""))
                }
            }
            return DagloGlossary(
                folderId = o.optInt("folder_id").takeIf { it > 0 },
                folderName = o.optString("folder_name").takeIf { it.isNotBlank() && it != "null" },
                folderTerms = terms(o.optJSONArray("folder_terms")),
                commonTerms = terms(o.optJSONArray("common_terms")),
                maxTerms = o.optInt("max_terms", 200)
            )
        }
    }
}

/** 웹의 termsToText / textToTerms 와 같은 규칙: 한 줄에 하나, `단어 | 메모`. */
fun List<DagloGlossaryTerm>.toEditorText(): String =
    joinToString("\n") { if (it.note.isNotBlank()) "${it.term} | ${it.note}" else it.term }

fun String.toGlossaryTerms(): List<DagloGlossaryTerm> =
    split("\n").mapNotNull { line ->
        val parts = line.split("|")
        val term = parts.firstOrNull()?.trim().orEmpty()
        if (term.isBlank()) null else DagloGlossaryTerm(term, parts.drop(1).joinToString("|").trim())
    }

private fun jsonStringList(arr: JSONArray?): List<String> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
}
