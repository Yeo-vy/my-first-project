package com.recorder.voicenote

import android.content.Context

/**
 * daglo 서버 연동 설정.
 *
 * 서버는 집/연구실 PC 에서 도는 개인 서버라서 주소가 사람마다 다르다. 그래서 빌드에 박지 않고
 * 앱 설정 화면에서 입력받는다.
 *
 * 인증은 웹과 완전히 같은 방식이다 — 웹과 같은 아이디·비밀번호로 로그인하고, 서버가 내려준
 * 세션 쿠키(`daglo_session`)를 저장해 요청마다 실어 보낸다. 예전에는 서버 `.env` 의
 * `DAGLO_API_TOKEN` 을 태블릿에 복사해 넣었는데, 그건 계정 구분이 없는 전역 비밀이라
 * 기기를 잃으면 회수할 방법이 서버 토큰 교체밖에 없었고 계정 메뉴(비밀번호 변경·로그아웃)도
 * 쓸 수 없었다.
 */
class DagloSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 예: "http://192.168.0.10:8000" (끝의 / 는 떼서 보관한다) */
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_SERVER_URL, normalizeUrl(value)).apply()
        }

    /**
     * 서버 `.env` 의 `LOGIN_PATH`. 로그인 화면 주소를 봇이 찾지 못하게 임의 문자열로 바꿔 둘 수
     * 있어서, 앱도 그 값을 알아야 로그인 요청을 보낼 수 있다. 기본값은 서버와 같은 `login`.
     */
    var loginPath: String
        get() = prefs.getString(KEY_LOGIN_PATH, DEFAULT_LOGIN_PATH) ?: DEFAULT_LOGIN_PATH
        set(value) {
            prefs.edit().putString(KEY_LOGIN_PATH, normalizeLoginPath(value)).apply()
        }

    /** 로그인해서 받은 `daglo_session` 쿠키 값. 지우면 로그아웃 상태가 된다. */
    var sessionCookie: String
        get() = prefs.getString(KEY_SESSION_COOKIE, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_SESSION_COOKIE, value.trim()).apply()
        }

    /** 녹음이 끝나면 자동으로 서버에 올릴지 여부 */
    var autoUpload: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPLOAD, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_UPLOAD, value).apply()
        }

    /** 주소가 채워져 있어야 로그인도 업로드도 할 수 있다. */
    val isConfigured: Boolean
        get() = serverUrl.isNotBlank()

    /** 로그인해 둔 세션이 있는지 (서버가 만료시켰을 수도 있으므로 확인은 서버가 한다) */
    val isLoggedIn: Boolean
        get() = sessionCookie.isNotBlank()

    /** 업로드에 쓸 수 있는 상태인지 (로그인 + 자동 업로드까지 켜져 있는지) */
    val canAutoUpload: Boolean
        get() = isConfigured && isLoggedIn && autoUpload

    fun clearSession() {
        prefs.edit().remove(KEY_SESSION_COOKIE).apply()
    }

    companion object {
        private const val PREFS_NAME = "daglo_server"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_LOGIN_PATH = "login_path"
        private const val KEY_SESSION_COOKIE = "session_cookie"
        private const val KEY_AUTO_UPLOAD = "auto_upload"

        const val DEFAULT_LOGIN_PATH = "login"

        /** 서버가 세션을 담아 주는 쿠키 이름 (server/auth.py 의 SESSION_COOKIE) */
        const val SESSION_COOKIE_NAME = "daglo_session"

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

        /** "/gate-abc/" 처럼 적어도 되도록 앞뒤 슬래시를 떼서 보관한다. */
        fun normalizeLoginPath(raw: String): String =
            raw.trim().trim('/').ifBlank { DEFAULT_LOGIN_PATH }
    }
}
