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

    /** 서버의 기준 주소. 예: "http://192.168.0.10:8000" (경로는 떼고 보관한다) */
    var serverUrl: String
        // 예전 버전은 입력한 주소를 경로까지 통째로 저장했다. 그 값이 남아 있어도
        // /api 주소에 경로가 섞이지 않도록 읽을 때 한 번 더 다듬는다.
        get() = normalizeUrl(prefs.getString(KEY_SERVER_URL, "") ?: "")
        set(value) {
            prefs.edit().putString(KEY_SERVER_URL, normalizeUrl(value)).apply()
        }

    /**
     * 로그인 화면 경로. 서버 `.env` 의 `LOGIN_PATH` 와 같은 값이어야 한다 (예: "gate-yeovy").
     *
     * 서버는 봇을 피하려고 로그인 주소를 임의 문자열로 바꿔 둘 수 있고, 그때 루트(/)로 들어가면
     * 404 가 돌아온다. 앱도 같은 주소로 들어가야 로그인 화면을 볼 수 있다. 비워 두면 루트로 연다.
     */
    var loginPath: String
        get() {
            val stored = prefs.getString(KEY_LOGIN_PATH, null)
            if (stored != null) return stored
            // 예전 버전은 주소 한 줄만 저장했다. 거기에 경로가 붙어 있으면 그게 로그인 경로다.
            return splitAddress(prefs.getString(KEY_SERVER_URL, "") ?: "").second
        }
        set(value) {
            prefs.edit().putString(KEY_LOGIN_PATH, value.trim().trim('/')).apply()
        }

    /** 웹 화면(WebView)이 열 주소 = 기준 주소 + 로그인 경로. 설정 화면에도 이 형태로 보여 준다. */
    val webUrl: String
        get() {
            val base = serverUrl
            if (base.isBlank()) return ""
            val path = loginPath
            return if (path.isBlank()) base else "$base/$path"
        }

    /** 설정 화면에서 받은 주소 한 줄을 기준 주소와 로그인 경로로 나눠 저장한다. */
    fun saveAddress(raw: String) {
        val (base, path) = splitAddress(raw)
        serverUrl = base
        loginPath = path
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
        private const val KEY_LOGIN_PATH = "login_path"
        private const val KEY_AUTO_UPLOAD = "auto_upload"

        /**
         * 입력한 주소 한 줄을 (기준 주소, 로그인 경로) 로 나눈다.
         *
         * "192.168.0.10:8000/gate-yeovy" -> ("http://192.168.0.10:8000", "gate-yeovy")
         *
         * 업로드·연결 확인은 `/api/...` 를 기준 주소에 붙여 부르기 때문에, 사용자가 로그인 경로까지
         * 적어 넣어도 그 경로가 API 주소에 섞이면 안 된다. 그래서 저장할 때 갈라 둔다.
         */
        fun splitAddress(raw: String): Pair<String, String> {
            var value = raw.trim().trimEnd('/')
            if (value.isEmpty()) return "" to ""
            if (!value.startsWith("http://", ignoreCase = true) &&
                !value.startsWith("https://", ignoreCase = true)
            ) {
                value = "http://$value"
            }
            val schemeEnd = value.indexOf("://") + 3
            val slash = value.indexOf('/', schemeEnd)
            if (slash < 0) return value to ""
            return value.substring(0, slash) to value.substring(slash).trim('/')
        }

        /** 사용자가 "192.168.0.10:8000" 처럼 적어도 동작하도록 다듬는다 (경로는 떼어낸다). */
        fun normalizeUrl(raw: String): String = splitAddress(raw).first
    }
}
