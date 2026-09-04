package com.recorder.voicenote

import android.content.Context

/**
 * daglo 서버 연동 설정.
 *
 * 서버는 집/연구실 PC 에서 도는 개인 서버라서 주소가 사람마다 다르다. 그래서 빌드에 박지 않고
 * 앱 설정 화면에서 입력받는다. 인증은 서버의 DAGLO_API_TOKEN(헤더 토큰)을 그대로 쓴다.
 */
class DagloSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 예: "http://192.168.0.10:8000" (끝의 / 는 떼서 보관한다) */
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_SERVER_URL, normalizeUrl(value)).apply()
        }

    /** 서버 .env 의 DAGLO_API_TOKEN 과 같은 값 */
    var apiToken: String
        get() = prefs.getString(KEY_API_TOKEN, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_API_TOKEN, value.trim()).apply()
        }

    /** 녹음이 끝나면 자동으로 서버에 올릴지 여부 */
    var autoUpload: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPLOAD, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_UPLOAD, value).apply()
        }

    /** 주소가 채워져 있어야 업로드도 앱 안의 daglo 화면도 열 수 있다. */
    val isConfigured: Boolean
        get() = serverUrl.isNotBlank()

    /** 업로드에 쓸 수 있는 상태인지 (자동 업로드까지 켜져 있는지) */
    val canAutoUpload: Boolean
        get() = isConfigured && autoUpload

    companion object {
        private const val PREFS_NAME = "daglo_server"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_API_TOKEN = "api_token"
        private const val KEY_AUTO_UPLOAD = "auto_upload"

        /** 사용자가 "192.168.0.10:8000" 처럼 적어도 동작하도록 다듬는다. */
        fun normalizeUrl(raw: String): String {
            var value = raw.trim().trimEnd('/')
            if (value.isEmpty()) return ""
            if (!value.startsWith("http://", ignoreCase = true) &&
                !value.startsWith("https://", ignoreCase = true)
            ) {
                value = "http://$value"
            }
            return value
        }
    }
}
