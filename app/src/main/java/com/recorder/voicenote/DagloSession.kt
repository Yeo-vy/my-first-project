package com.recorder.voicenote

import android.webkit.CookieManager

/**
 * 앱의 daglo 로그인 상태.
 *
 * 앱은 로그인 화면을 따로 두지 않는다. 본 화면인 daglo 웹 화면(WebView)에서 웹과 똑같이
 * 로그인하면 서버가 내려준 세션 쿠키가 WebView 의 CookieManager 에 저장되고, 녹음 업로드는
 * 그 쿠키를 그대로 실어 보낸다. 그래서 앱이 비밀번호를 알 필요도, 따로 보관할 필요도 없다.
 */
object DagloSession {

    /** 서버가 세션을 담아 주는 쿠키 이름 (server/auth.py 의 SESSION_COOKIE) */
    private const val SESSION_COOKIE_NAME = "daglo_session"

    /** 업로드 요청에 그대로 넣을 Cookie 헤더 값. 로그인 전이면 null. */
    fun cookieHeader(serverUrl: String): String? {
        if (serverUrl.isBlank()) return null
        val raw = try {
            CookieManager.getInstance().getCookie(serverUrl)
        } catch (e: Exception) {
            null
        }
        return raw?.takeIf { it.contains("$SESSION_COOKIE_NAME=") }
    }

    fun hasSession(serverUrl: String): Boolean = cookieHeader(serverUrl) != null

    /**
     * WebView 가 받은 쿠키를 디스크에 내려 둔다.
     *
     * 업로드는 WorkManager 가 별도 프로세스 시점에 돌 수 있고, 앱이 죽었다 살아난 뒤에도
     * 이어서 돈다. 화면이 살아 있는 동안 flush 해 두지 않으면 그때 쿠키가 비어 있을 수 있다.
     */
    fun persist() {
        try {
            CookieManager.getInstance().flush()
        } catch (e: Exception) {
            /* 쿠키 저장에 실패해도 화면 동작에는 영향이 없다 */
        }
    }

    /** 서버 주소가 바뀌면 예전 서버의 세션은 쓸모가 없다. */
    fun clear(serverUrl: String) {
        if (serverUrl.isBlank()) return
        try {
            val manager = CookieManager.getInstance()
            manager.setCookie(serverUrl, "$SESSION_COOKIE_NAME=; Max-Age=0; Path=/")
            manager.flush()
        } catch (e: Exception) {
            /* 무시 */
        }
    }
}
