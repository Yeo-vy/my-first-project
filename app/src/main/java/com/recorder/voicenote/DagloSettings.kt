package com.recorder.voicenote

import android.content.Context

/**
 * daglo 서버 연동 설정.
 *
 * 서버는 집/연구실 PC 에서 도는 개인 서버라 주소가 사람마다 다르다. 그래서 빌드에 박지 않고
 * 설정 화면에서 입력받는다.
 *
 * 로그인은 앱이 따로 하지 않는다. 앱의 본 화면이 daglo 웹 화면(WebView)이라, 거기서 웹과 똑같이
 * 로그인하면 세션 쿠키가 WebView 의 CookieManager 에 남는다. 녹음 업로드는 그 쿠키를 그대로
 * 실어 보낸다([DagloSession] 참고). 앱에 비밀번호나 토큰을 따로 저장하지 않는다.
 */
class DagloSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 예: "http://192.168.0.10:8000" (끝의 / 는 떼서 보관한다) */
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_SERVER_URL, normalizeUrl(value)).apply()
        }

    /** 녹음이 끝나면 자동으로 서버에 올릴지 여부 */
    var autoUpload: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPLOAD, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_UPLOAD, value).apply()
        }

    /** 주소가 채워져 있어야 웹 화면도 업로드도 열린다. */
    val isConfigured: Boolean
        get() = serverUrl.isNotBlank()

    /** 웹 화면에서 로그인해 둔 세션이 있는지 */
    val isLoggedIn: Boolean
        get() = DagloSession.hasSession(serverUrl)

    /** 지금 녹음을 올릴 수 있는 상태인지 */
    val canAutoUpload: Boolean
        get() = isConfigured && autoUpload && isLoggedIn

    companion object {
        private const val PREFS_NAME = "daglo_server"
        private const val KEY_SERVER_URL = "server_url"
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
