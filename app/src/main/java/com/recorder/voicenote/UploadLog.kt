package com.recorder.voicenote

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** 녹음 파일 하나의 전송 상태. */
enum class UploadState { PENDING, UPLOADING, DONE, FAILED }

data class UploadRecord(
    val state: UploadState,
    val message: String = "",
    val updatedAt: Long = 0L
)

/**
 * 어떤 녹음이 서버로 갔는지 파일 이름별로 기록해 둔다.
 *
 * 태블릿을 녹음기로만 쓰면 "이 녹음이 서버에 올라갔나?" 가 가장 중요한 정보인데, 업로드는
 * WorkManager 가 백그라운드에서 (앱이 꺼진 동안에도) 돌리기 때문에 화면 상태로는 알 수 없다.
 * 그래서 결과를 SharedPreferences 에 남겨 두고 목록에서 그대로 보여 준다.
 */
object UploadLog {

    private const val PREFS_NAME = "daglo_upload_log"
    private const val KEY_ENTRIES = "entries"
    /** 오래된 기록까지 들고 있을 필요는 없다. 넘치면 오래된 것부터 버린다. */
    private const val MAX_ENTRIES = 400

    /** 기록이 바뀔 때마다 올라간다. 화면은 이 값을 보고 다시 읽는다. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun mark(context: Context, displayName: String, state: UploadState, message: String = "") {
        if (displayName.isBlank()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val root = readRoot(prefs)

        root.put(
            displayName,
            JSONObject()
                .put("state", state.name)
                .put("message", message)
                .put("at", System.currentTimeMillis())
        )
        prune(root)

        prefs.edit().putString(KEY_ENTRIES, root.toString()).apply()
        _version.value = _version.value + 1
    }

    fun all(context: Context): Map<String, UploadRecord> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val root = readRoot(prefs)
        val result = mutableMapOf<String, UploadRecord>()
        root.keys().forEach { name ->
            val entry = root.optJSONObject(name) ?: return@forEach
            val state = runCatching { UploadState.valueOf(entry.optString("state")) }.getOrNull()
                ?: return@forEach
            result[name] = UploadRecord(
                state = state,
                message = entry.optString("message"),
                updatedAt = entry.optLong("at", 0L)
            )
        }
        return result
    }

    /** 이름이 바뀌거나 지워진 녹음의 기록은 남겨 둘 이유가 없다. */
    fun forget(context: Context, displayName: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val root = readRoot(prefs)
        if (!root.has(displayName)) return
        root.remove(displayName)
        prefs.edit().putString(KEY_ENTRIES, root.toString()).apply()
        _version.value = _version.value + 1
    }

    private fun readRoot(prefs: android.content.SharedPreferences): JSONObject {
        val raw = prefs.getString(KEY_ENTRIES, "") ?: ""
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    private fun prune(root: JSONObject) {
        if (root.length() <= MAX_ENTRIES) return
        val byAge = root.keys().asSequence().toList()
            .sortedBy { root.optJSONObject(it)?.optLong("at", 0L) ?: 0L }
        byAge.take(root.length() - MAX_ENTRIES).forEach { root.remove(it) }
    }
}
