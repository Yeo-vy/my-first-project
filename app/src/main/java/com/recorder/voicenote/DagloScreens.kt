@file:OptIn(ExperimentalMaterial3Api::class)

package com.recorder.voicenote

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Divider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * daglo 서버 연결 설정 화면.
 *
 * 서버는 각자 자기 PC 에서 돌리는 개인 서버라 주소가 다르다. 주소를 넣어 두면 앱에서 웹과 같은
 * 계정으로 로그인할 수 있고, 녹음이 끝날 때마다 자동으로 올라간다.
 *
 * 로그인 주소는 서버 `.env` 의 `LOGIN_PATH` 다. 인터넷에 열어 둔 서버는 이 값을 임의 문자열로
 * 바꿔 두는 경우가 많아서(봇이 로그인 화면을 찾지 못하게), 앱도 같은 값을 알아야 한다.
 * 그래서 주소 칸에 `192.168.0.10:8000/gate-7f21c9` 처럼 경로까지 적을 수 있게 했다.
 * 저장할 때 기준 주소와 로그인 경로로 갈라 두므로, 업로드가 부르는 `/api/...` 주소에는 섞이지 않는다.
 */
@Composable
fun ServerSettingsScreen(
    initialServerUrl: String,
    initialAutoUpload: Boolean,
    isTesting: Boolean,
    onSave: (String, Boolean) -> Unit,
    onTest: (String) -> Unit,
    onBack: () -> Unit
) {
    var serverUrl by rememberSaveable { mutableStateOf(initialServerUrl) }
    var autoUpload by rememberSaveable { mutableStateOf(initialAutoUpload) }

    val context = LocalContext.current
    // 설정 화면에 들어올 때마다 확인한다 (시스템 대화상자에서 허용하고 돌아오면 바뀌어 있다)
    var batteryUnrestricted by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryUnrestricted = isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("daglo 서버 연결", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "뒤로가기")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = "주소를 저장하면 웹과 같은 계정으로 로그인합니다. 녹음이 끝나면 이 서버로 " +
                    "파일을 보내 자동으로 받아쓰기를 시작합니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(20.dp))

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("서버 주소") },
                placeholder = { Text("192.168.0.10:8000/gate-7f21c9") },
                supportingText = {
                    Text(
                        "http:// 는 빼도 됩니다. 서버 .env 에서 로그인 주소(LOGIN_PATH)를 바꿔 뒀다면 " +
                            "그 경로까지 붙여 적으세요. 안 붙이면 로그인 화면 대신 Not Found 가 뜹니다."
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(14.dp))


            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("녹음 후 자동 업로드", fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "네트워크가 없으면 연결될 때까지 기다렸다가 올립니다",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = autoUpload, onCheckedChange = { autoUpload = it })
            }
            Spacer(modifier = Modifier.height(24.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { onTest(serverUrl) },
                    enabled = !isTesting
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Text("연결 테스트")
                }
                Button(onClick = { onSave(serverUrl, autoUpload) }) {
                    Text("저장")
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
            Text(
                text = "주소를 저장한 뒤 웹 화면에서 웹과 같은 계정으로 한 번 로그인하면 됩니다. " +
                    "그때 받은 로그인 세션을 녹음 업로드도 함께 씁니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(28.dp))
            Divider()
            Spacer(modifier = Modifier.height(20.dp))

            // 삼성 기기는 절전 정책이 강해서, 화면을 끄고 오래 두면 백그라운드 앱을 재운다.
            // 몇 시간짜리 녹음이 중간에 끊기는 가장 흔한 원인이라 여기서 바로 풀 수 있게 한다.
            Text("긴 녹음 안정성", fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (batteryUnrestricted) {
                    "이 앱은 배터리 최적화에서 제외되어 있습니다. 화면을 끄고 길게 녹음해도 " +
                        "시스템이 앱을 재우지 않습니다."
                } else {
                    "화면을 끈 채로 몇 시간씩 녹음하려면 배터리 최적화에서 이 앱을 빼 두세요. " +
                        "그렇지 않으면 시스템이 앱을 재워 녹음이 중간에 끊길 수 있습니다."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = { requestIgnoreBatteryOptimizations(context) },
                enabled = !batteryUnrestricted
            ) {
                Text(if (batteryUnrestricted) "제외되어 있음" else "배터리 최적화에서 제외하기")
            }
        }
    }
}

/** 지금 이 앱이 배터리 최적화에서 빠져 있는지 */
private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    return try {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        power.isIgnoringBatteryOptimizations(context.packageName)
    } catch (e: Exception) {
        false
    }
}

/** 시스템 대화상자를 띄워 배터리 최적화 제외를 요청한다. */
private fun requestIgnoreBatteryOptimizations(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    try {
        context.startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + context.packageName)
            )
        )
    } catch (e: Exception) {
        // 일부 기기는 이 화면을 막아 둔다. 그럴 때는 배터리 설정 목록이라도 열어 준다.
        try {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e2: Exception) {
            /* 그것도 없으면 사용자가 직접 설정에서 풀어야 한다 */
        }
    }
}

/**
 * 앱의 본 화면. daglo 웹 화면을 그대로 띄운다.
 *
 * 보드 목록·스크립트 편집·요약·AI 채팅은 이미 서버 웹 화면에 다 있으므로 같은 기능을
 * 네이티브로 다시 만들지 않는다. 태블릿 가로 화면에서는 데스크톱 레이아웃이 그대로 잘 맞는다.
 * 로그인도 여기서 웹과 똑같이 한 번 하면 되고, 그때 받은 세션 쿠키를 녹음 업로드가 함께 쓴다.
 *
 * 앱이 웹 대신 하는 일은 '녹음' 하나다. 브라우저 녹음은 화면을 끄면 끊기지만, 앱은 포그라운드
 * 서비스로 알림을 띄우고 녹음하므로 화면을 끄고 몇 시간을 두어도 이어진다.
 */
@Composable
fun DagloWebScreen(
    serverUrl: String,
    isRecording: Boolean,
    elapsedSeconds: Int,
    onOpenRecorder: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var webView by remember { mutableStateOf<WebView?>(null) }

    // 뒤로가기는 웹 화면 안에서만 처리한다. 여기가 앱의 첫 화면이라 더 뒤로 갈 곳이 없으면
    // 시스템 기본 동작(앱을 벗어남)에 맡긴다.
    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("daglo", fontWeight = FontWeight.Bold) },
                actions = {
                    // 녹음 중에는 경과 시간을 상단바에 계속 띄워 둔다
                    if (isRecording) {
                        Text(
                            text = formatElapsed(elapsedSeconds),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    Button(onClick = onOpenRecorder) {
                        Icon(Icons.Default.Mic, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isRecording) "녹음 중" else "녹음")
                    }
                    IconButton(onClick = { webView?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "새로고침")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "서버 설정")
                    }
                }
            )
        }
    ) { padding ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // 녹음 재생이 사용자 제스처 없이도 시작될 수 있게 한다 (스크립트 클릭 → 해당 위치 재생)
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // 로그인 직후 받은 세션 쿠키를 디스크에 내려 둔다.
                            // 녹음 업로드(WorkManager)는 앱이 꺼진 뒤에도 도는데, 그때 이 쿠키를 쓴다.
                            DagloSession.persist()
                        }

                        // 로그인 주소를 숨겨 둔 서버(.env 의 LOGIN_PATH)는 그 경로가 아니면 404 를 준다.
                        // 맨 화면에 "Not Found" 만 뜨면 무엇이 잘못됐는지 알 길이 없어서 안내를 대신 띄운다.
                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?
                        ) {
                            super.onReceivedHttpError(view, request, errorResponse)
                            if (request?.isForMainFrame != true) return
                            if (errorResponse?.statusCode != 404) return
                            val target = view ?: return
                            // 콜백 안에서 바로 다시 로드하지 않고 다음 차례로 넘긴다
                            target.post {
                                target.loadDataWithBaseURL(
                                    serverUrl, loginPathHintHtml(serverUrl), "text/html", "utf-8", null
                                )
                            }
                        }
                    }

                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    // 웹의 '내보내기(txt/srt/md)'는 브라우저 다운로드라서, 안드로이드 다운로드 관리자로 넘긴다.
                    setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                        downloadWithSession(context, url, contentDisposition, mimeType)
                    }

                    loadUrl(serverUrl)
                    webView = this
                }
            }
        )
    }
}

/**
 * 서버가 404 를 돌려줬을 때 대신 띄우는 안내.
 *
 * 서버는 `.env` 의 `LOGIN_PATH` 주소에서만 로그인 화면을 연다(봇이 찾지 못하게). 그 경로를
 * 빼고 주소를 저장하면 루트(/)도 404 라서, 앱에는 "Not Found" 한 줄만 뜬다.
 * 로그인이 풀린 뒤 웹 화면 안쪽 주소를 열 때도 같은 404 가 온다. 그래서 설정한 로그인 주소로
 * 되돌아갈 수 있는 링크를 함께 준다.
 */
private fun loginPathHintHtml(loginUrl: String): String = """
    <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
    <body style="font-family:sans-serif; padding:32px; line-height:1.7; color:#1f2937">
      <h2 style="margin:0 0 12px">이 주소에서 화면을 열 수 없습니다 (404)</h2>
      <p>로그인이 풀렸거나, 서버 <code>.env</code> 의 <code>LOGIN_PATH</code> 를
      서버 주소 뒤에 붙이지 않았을 수 있습니다.</p>
      <p><a href="LOGIN_URL_PLACEHOLDER" style="font-size:17px">로그인 화면 다시 열기</a></p>
      <p style="color:#6b7280">설정(⚙)에서 주소를 이렇게 적으면 됩니다:
      <code>192.168.0.10:8000/gate-7f21c9</code></p>
    </body></html>
""".replace("LOGIN_URL_PLACEHOLDER", loginUrl)

/** 로그인 쿠키를 그대로 실어 다운로드한다. 쿠키가 없으면 서버가 로그인 페이지를 돌려주기 때문. */
private fun downloadWithSession(
    context: Context,
    url: String,
    contentDisposition: String?,
    mimeType: String?
) {
    try {
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            setTitle(fileName)
            setMimeType(mimeType)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        }
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.enqueue(request)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

/** 상단바에 띄우는 녹음 경과 시간 (mm:ss / h:mm:ss) */
private fun formatElapsed(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val h = safe / 3600
    val m = (safe % 3600) / 60
    val s = safe % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
