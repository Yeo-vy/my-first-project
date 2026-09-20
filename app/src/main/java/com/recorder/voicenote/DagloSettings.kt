package com.recorder.voicenote

import android.content.Context

/**
 * 앱이 기억하는 것은 서버 주소와, 오프라인 녹음 화면이 쓸 최근 폴더 이름뿐이다.
 *
 * 로그인·목록·편집·요약은 전부 웹 화면(WebView)이 하므로 앱에는 계정도 토큰도 저장하지 않는다.
 * 업로드는 웹에서 로그인하며 받은 세션 쿠키를 그대로 쓴다([DagloSession]).
 *
 * 주소는 한 줄로 받되 안에서 둘로 나눠 둔다.
 *  - 기준 주소: `http://192.168.0.10:8000` — 업로드가 `/api/...` 를 붙여 부르는 곳
 *  - 로그인 경로: `gate-7f21c9` — 서버 `.env` 의 `LOGIN_PATH`. 봇을 피해 로그인 화면 주소를
 *    임의 문자열로 바꿔 둔 서버가 많고, 그 경로로 들어가지 않으면 루트(/)도 404 다.
 * 둘을 합친 [webUrl] 을 웹 화면이 연다.
 */
class DagloSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 서버 기준 주소. 예: "http://192.168.0.10:8000" (경로는 떼고 보관한다) */
    var serverUrl: String
        get() = splitAddress(prefs.getString(KEY_SERVER_URL, "") ?: "").first
        set(value) {
            prefs.edit().putString(KEY_SERVER_URL, splitAddress(value).first).apply()
        }

    /** 로그인 화면 경로. 서버 `.env` 의 `LOGIN_PATH` 와 같아야 한다. 비어 있으면 루트로 연다. */
    var loginPath: String
        get() = prefs.getString(KEY_LOGIN_PATH, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_LOGIN_PATH, value.trim().trim('/')).apply()
        }

    /** 웹 화면이 여는 주소 = 기준 주소 + 로그인 경로 */
    val webUrl: String
        get() {
            val base = serverUrl
            if (base.isEmpty()) return ""
            val path = loginPath
            return if (path.isEmpty()) base else "$base/$path"
        }

    val isConfigured: Boolean
        get() = serverUrl.isNotEmpty()

    /**
     * 배터리 최적화 제외를 이미 한 번 권했는지.
     *
     * 삼성 절전 정책은 백그라운드 앱을 재워 긴 녹음을 끊을 수 있어 한 번은 권해야 하지만,
     * 거절한 사람에게 열 때마다 물으면 성가시다. 그래서 딱 한 번만 묻는다.
     */
    var askedBatteryExemption: Boolean
        get() = prefs.getBoolean(KEY_ASKED_BATTERY, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ASKED_BATTERY, value).apply()
        }

    /**
     * 최근에 녹음을 넣은 폴더 이름들 (새것부터).
     *
     * 폴더 목록은 원래 서버가 준다. 서버에 닿지 않는 곳에서 녹음할 때는 그 목록을 받아올 수
     * 없으므로, 녹음할 때마다 쓴 이름을 여기 남겨 두고 오프라인 화면에서 고를 수 있게 한다.
     * 서버는 없는 이름으로 올리면 그 폴더를 만들어 주므로 이름만 알면 충분하다.
     */
    val recentFolders: List<String>
        get() = (prefs.getString(KEY_RECENT_FOLDERS, "") ?: "")
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** 방금 쓴 폴더 이름을 맨 앞에 둔다 (같은 이름은 한 번만 남는다). */
    fun rememberFolder(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val next = (listOf(clean) + recentFolders.filter { !it.equals(clean, ignoreCase = true) })
            .take(MAX_RECENT_FOLDERS)
        prefs.edit().putString(KEY_RECENT_FOLDERS, next.joinToString("\n")).apply()
    }

    /** 설정 화면에서 받은 주소 한 줄을 기준 주소와 로그인 경로로 나눠 저장한다. */
    fun save(rawAddress: String) {
        val (base, path) = splitAddress(rawAddress)
        prefs.edit()
            .putString(KEY_SERVER_URL, base)
            .putString(KEY_LOGIN_PATH, path)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "daglo"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_LOGIN_PATH = "login_path"
        private const val KEY_ASKED_BATTERY = "asked_battery"
        private const val KEY_RECENT_FOLDERS = "recent_folders"
        /** 오프라인 화면에 한눈에 들어올 만큼만 남긴다 */
        private const val MAX_RECENT_FOLDERS = 8

        /**
         * 주소 한 줄을 (기준 주소, 로그인 경로) 로 나눈다.
         *
         * "192.168.0.10:8000/gate-7f21c9" -> ("http://192.168.0.10:8000", "gate-7f21c9")
         * "192.168.0.10:8000"             -> ("http://192.168.0.10:8000", "")
         *
         * 업로드는 기준 주소에 `/api/...` 를 붙여 부르기 때문에, 사용자가 로그인 경로까지 적어도
         * 그 경로가 API 주소에 섞이면 안 된다. 그래서 저장할 때 갈라 둔다.
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
    }
}
