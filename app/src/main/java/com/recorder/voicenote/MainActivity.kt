package com.recorder.voicenote

import android.Manifest
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * 앱의 전부: daglo 웹 화면 한 장.
 *
 * 보드 목록·스크립트 편집·요약·AI 채팅·로그인은 이미 웹에 다 있으므로 앱에서 다시 만들지 않는다.
 * 앱이 웹 대신 하는 일은 **녹음** 하나뿐이고([RecordingService]), 그 녹음도 웹 화면의 녹음 버튼이
 * [RecorderBridge] 를 거쳐 부른다. 그래서 화면은 웹과 완전히 같고, 화면을 꺼도 녹음이 이어지는
 * 부분만 달라진다.
 *
 * 서버 주소는 첫 실행 때 한 번 받는다. 나중에 바꾸려면 홈 화면에서 앱 아이콘을 길게 눌러
 * "서버 주소" 바로가기를 쓰거나, 화면이 열리지 않을 때 뜨는 안내에서 바꾼다.
 */
class MainActivity : ComponentActivity() {

    private lateinit var settings: DagloSettings
    private lateinit var webView: WebView
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    /** 웹의 '파일에서 올리기' 가 앱 안에서도 되도록 파일 선택 화면을 띄운다 */
    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val callback = fileChooserCallback
        fileChooserCallback = null
        callback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
    }

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            AlertDialog.Builder(this)
                .setTitle("마이크 권한이 필요합니다")
                .setMessage("녹음하려면 마이크를 허용해 주세요. 설정 > 애플리케이션 > daglo 에서도 바꿀 수 있습니다.")
                .setPositiveButton("확인", null)
                .show()
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 거부해도 녹음은 이어진다. 알림만 보이지 않는다. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = DagloSettings(this)

        webView = WebView(this)
        setContentView(
            webView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        configureWebView()
        setUpBackHandling()
        askNotificationPermission()

        // 지난번에 못 올린 녹음(앱이 죽었거나 로그인이 풀렸던 경우)을 먼저 주워 담는다.
        Thread {
            RecordingService.recoverOrphans(applicationContext)
            UploadWorker.retryPending(applicationContext)
        }.start()

        if (wantsSettings(intent) || !settings.isConfigured) {
            showAddressDialog()
        } else {
            loadWeb()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (wantsSettings(intent)) showAddressDialog()
    }

    /** 홈 화면의 '서버 주소' 바로가기로 들어왔는지 */
    private fun wantsSettings(intent: Intent?): Boolean = intent?.action == ACTION_SETTINGS

    override fun onPause() {
        super.onPause()
        // 로그인하며 받은 세션 쿠키를 디스크에 내려 둔다. 업로드는 앱이 꺼진 뒤에도 이걸 쓴다.
        DagloSession.persist()
    }

    // ---- 웹 화면 -------------------------------------------------------------------

    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 스크립트를 눌러 그 위치부터 듣는 기능이 제스처 없이도 되게 한다
            mediaPlaybackRequiresUserGesture = false
            // 태블릿 가로 화면에서는 웹의 데스크톱 레이아웃이 그대로 잘 맞는다
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // 웹 화면의 녹음 버튼이 이 다리를 거쳐 포그라운드 서비스를 부른다
        webView.addJavascriptInterface(
            RecorderBridge(applicationContext) { runOnUiThread { micPermission.launch(Manifest.permission.RECORD_AUDIO) } },
            RecorderBridge.NAME
        )

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith(SETTINGS_SCHEME)) {
                    showAddressDialog()
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                DagloSession.persist()
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame != true) return
                if (errorResponse?.statusCode != 404) return
                // 로그인 주소를 숨겨 둔 서버(.env 의 LOGIN_PATH)는 그 경로가 아니면 404 를 준다.
                // "Not Found" 한 줄만 뜨면 무엇이 잘못됐는지 알 수 없으므로 안내로 바꿔 준다.
                showHint(
                    "이 주소에서 화면을 열 수 없습니다 (404)",
                    "로그인이 풀렸거나, 서버 .env 의 LOGIN_PATH 를 주소 뒤에 붙이지 않았을 수 있습니다."
                )
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame != true) return
                showHint(
                    "서버에 연결하지 못했습니다",
                    "서버 PC 가 켜져 있는지, 태블릿이 같은 와이파이에 있는지 확인해 주세요."
                )
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                return try {
                    filePicker.launch("*/*")
                    true
                } catch (e: Exception) {
                    fileChooserCallback = null
                    false
                }
            }
        }

        // 웹의 '내보내기(txt/srt/md)' 는 브라우저 다운로드라서 안드로이드 다운로드 관리자로 넘긴다
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            downloadWithSession(url, contentDisposition, mimeType)
        }
    }

    /** 뒤로가기는 웹 화면 안에서만 처리한다. 더 뒤로 갈 곳이 없으면 앱을 벗어난다. */
    private fun setUpBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun loadWeb() {
        val url = settings.webUrl
        if (url.isEmpty()) {
            showAddressDialog()
            return
        }
        webView.loadUrl(url)
        // 주소를 넣고 화면이 뜬 뒤에 권한다 (첫 실행에 대화상자가 겹치지 않게)
        maybeAskBatteryExemption()
    }

    /** 화면을 열지 못했을 때 이유와 빠져나갈 길을 함께 보여 준다. */
    private fun showHint(title: String, body: String) {
        val html = HINT_TEMPLATE
            .replace("{{TITLE}}", title)
            .replace("{{BODY}}", body)
            .replace("{{RETRY}}", settings.webUrl)
            .replace("{{SETTINGS}}", SETTINGS_SCHEME)
            .replace("{{ADDRESS}}", settings.webUrl.ifEmpty { "(없음)" })
        webView.loadDataWithBaseURL(
            settings.serverUrl.ifEmpty { null }, html, "text/html", "utf-8", null
        )
    }

    private fun downloadWithSession(url: String, contentDisposition: String?, mimeType: String?) {
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setTitle(fileName)
                setMimeType(mimeType)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ---- 서버 주소 -----------------------------------------------------------------

    private fun showAddressDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(settings.webUrl)
            hint = "192.168.0.10:8000/gate-7f21c9"
            setSingleLine()
        }
        val pad = (resources.displayMetrics.density * 20).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("서버 주소")
            .setMessage(
                "daglo 서버 주소를 넣어 주세요. http:// 는 빼도 됩니다.\n" +
                    "서버 .env 에서 로그인 주소(LOGIN_PATH)를 바꿔 뒀다면 그 경로까지 붙여 적으세요."
            )
            .setView(box)
            .setCancelable(settings.isConfigured)
            .setPositiveButton("저장") { _, _ ->
                settings.save(input.text.toString())
                loadWeb()
            }
            .setNegativeButton(if (settings.isConfigured) "취소" else "나가기") { _, _ ->
                if (!settings.isConfigured) finish()
            }
            .show()
    }

    /**
     * 삼성 절전 정책은 백그라운드 앱을 재워 긴 녹음을 끊을 수 있다. 포그라운드 서비스와 웨이크
     * 락으로 대부분 버티지만, 배터리 최적화에서 빼 두는 것이 마지막 방어선이라 한 번은 권한다.
     * 거절한 사람에게 열 때마다 묻지 않도록 딱 한 번만 띄운다.
     */
    private fun maybeAskBatteryExemption() {
        if (settings.askedBatteryExemption) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        settings.askedBatteryExemption = true
        AlertDialog.Builder(this)
            .setTitle("긴 녹음을 위해 한 가지만")
            .setMessage(
                "화면을 끄고 몇 시간을 녹음하려면 이 앱을 배터리 최적화에서 빼 두는 것이 안전합니다.\n" +
                    "다음 화면에서 daglo 를 '제한 없음(허용)' 으로 바꿔 주세요."
            )
            .setPositiveButton("설정 열기") { _, _ ->
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    // 기기에 따라 이 화면이 없을 수 있다. 그때는 그냥 넘어간다.
                    e.printStackTrace()
                }
            }
            .setNegativeButton("나중에", null)
            .show()
    }

    /**
     * 안드로이드 13+ 는 알림도 권한이다. 녹음 중 알림이 이 앱의 생명줄이라(화면을 꺼도 녹음이
     * 이어지는 근거) 처음 열 때 미리 물어 둔다.
     */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        /** 홈 화면 바로가기(res/xml/shortcuts.xml)가 보내는 인텐트 액션 */
        const val ACTION_SETTINGS = "com.recorder.voicenote.SETTINGS"
        /** 안내 페이지의 '서버 주소 바꾸기' 링크 */
        private const val SETTINGS_SCHEME = "daglo://settings"

        private val HINT_TEMPLATE = """
            <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
            <body style="font-family:sans-serif; padding:32px; line-height:1.7; color:#1f2937">
              <h2 style="margin:0 0 12px">{{TITLE}}</h2>
              <p>{{BODY}}</p>
              <p style="margin-top:24px">
                <a href="{{RETRY}}" style="font-size:17px">다시 열기</a>
                &nbsp;·&nbsp;
                <a href="{{SETTINGS}}" style="font-size:17px">서버 주소 바꾸기</a>
              </p>
              <p style="color:#6b7280">지금 주소: <code>{{ADDRESS}}</code></p>
            </body></html>
        """.trimIndent()
    }
}
