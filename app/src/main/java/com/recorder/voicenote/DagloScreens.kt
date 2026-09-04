@file:OptIn(ExperimentalMaterial3Api::class)

package com.recorder.voicenote

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
 * 서버는 각자 자기 PC 에서 돌리는 개인 서버라 주소가 다르다. 여기서 주소와 토큰을 넣어 두면
 * 녹음이 끝날 때마다 자동으로 올라가고, 앱 안에서 daglo 화면도 열 수 있다.
 */
@Composable
fun ServerSettingsScreen(
    initialServerUrl: String,
    initialApiToken: String,
    initialAutoUpload: Boolean,
    isTesting: Boolean,
    onSave: (String, String, Boolean) -> Unit,
    onTest: (String, String) -> Unit,
    onBack: () -> Unit
) {
    var serverUrl by rememberSaveable { mutableStateOf(initialServerUrl) }
    var apiToken by rememberSaveable { mutableStateOf(initialApiToken) }
    var autoUpload by rememberSaveable { mutableStateOf(initialAutoUpload) }

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
                text = "녹음이 끝나면 이 서버로 파일을 보내 자동으로 받아쓰기를 시작합니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(20.dp))

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("서버 주소") },
                placeholder = { Text("192.168.0.10:8000") },
                supportingText = { Text("http:// 를 빼고 적어도 됩니다") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(14.dp))

            OutlinedTextField(
                value = apiToken,
                onValueChange = { apiToken = it },
                label = { Text("API 토큰") },
                supportingText = { Text("서버 .env 의 DAGLO_API_TOKEN 과 같은 값") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(18.dp))

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
                    onClick = { onTest(serverUrl, apiToken) },
                    enabled = !isTesting
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Text("연결 테스트")
                }
                Button(onClick = { onSave(serverUrl, apiToken, autoUpload) }) {
                    Text("저장")
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
            Text(
                text = "앱 안에서 daglo 화면을 열면 변환된 스크립트·요약·AI 채팅을 그대로 쓸 수 있습니다. " +
                    "그 화면은 웹과 같은 계정으로 한 번 로그인하면 유지됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 앱 안에서 daglo 웹 화면을 띄운다.
 *
 * 변환 스크립트 편집·요약·AI 채팅은 이미 서버 웹 화면에 다 있으므로, 같은 기능을 네이티브로
 * 다시 만들지 않고 그대로 쓴다. 태블릿 가로 화면에서는 데스크톱 레이아웃이 그대로 잘 맞는다.
 * 로그인은 웹과 동일한 계정으로 한 번만 하면 쿠키가 남아 유지된다.
 */
@Composable
fun DagloWebScreen(
    serverUrl: String,
    onBack: () -> Unit
) {
    var webView by remember { mutableStateOf<WebView?>(null) }

    // 뒤로가기는 웹 화면 안에서 먼저 처리하고, 더 뒤로 갈 곳이 없을 때 앱 화면으로 돌아온다.
    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("daglo", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "녹음 화면으로")
                    }
                },
                actions = {
                    IconButton(onClick = { webView?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "새로고침")
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
                    webViewClient = WebViewClient()

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
