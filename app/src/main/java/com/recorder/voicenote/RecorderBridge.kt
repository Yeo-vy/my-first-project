package com.recorder.voicenote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * 웹 화면과 네이티브 녹음을 잇는 다리. 웹에서는 `window.DagloNative` 로 보인다.
 *
 * **왜 이렇게 하나**: 앱은 웹 화면을 그대로 띄우는 것이 전부여서, 녹음 UI(모달·폴더 선택·경과
 * 시간·입력 레벨)도 웹의 것을 그대로 쓴다. 앱 안에서 열렸을 때만 그 버튼이 브라우저 녹음 대신
 * 이 다리를 거쳐 포그라운드 서비스를 부른다. 그래서 화면은 웹과 완전히 같고, 화면을 꺼도
 * 녹음이 이어지는 부분만 달라진다.
 *
 * 여기 메서드들은 WebView 의 JS 스레드에서 불린다. 상태는 [RecordingService.snapshot] 한 곳에만
 * 있고 서비스가 갱신하므로, 읽고 쓰는 데 별도 동기화가 필요 없다.
 */
class RecorderBridge(
    private val context: Context,
    /** 마이크 권한이 없을 때 화면에 권한 요청을 띄워 달라고 부탁한다 */
    private val requestMicPermission: () -> Unit
) {

    /** 웹이 "앱 안에서 열렸는지" 판단하는 데 쓴다. */
    @JavascriptInterface
    fun isAvailable(): Boolean = true

    /**
     * 지금 녹음 상태. 웹이 0.5초마다 물어보고 화면(경과 시간·레벨 막대)을 그린다.
     * 페이지를 새로고침하거나 앱을 다시 열어도 여기서 상태를 되찾으므로 화면이 어긋나지 않는다.
     */
    @JavascriptInterface
    fun state(): String {
        val s = RecordingService.snapshot
        return JSONObject()
            .put("recording", s.recording)
            .put("paused", s.paused)
            .put("saving", s.saving)
            .put("elapsedMs", s.elapsedMs)
            .put("level", s.level.toDouble())
            .put("folderName", s.folderName)
            .put("message", s.message)
            .toString()
    }

    /**
     * 녹음을 시작한다.
     * @return "OK" | "NEED_PERMISSION" (권한 요청을 띄웠으니 허용 후 다시 누르면 된다) | "BUSY"
     */
    @JavascriptInterface
    fun start(folderName: String?): String {
        val s = RecordingService.snapshot
        if (s.recording || s.saving) return "BUSY"

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestMicPermission()
            return "NEED_PERMISSION"
        }

        RecordingService.send(context, RecordingService.ACTION_START, folderName ?: "기본 폴더")
        return "OK"
    }

    @JavascriptInterface
    fun pause() = RecordingService.send(context, RecordingService.ACTION_PAUSE)

    @JavascriptInterface
    fun resume() = RecordingService.send(context, RecordingService.ACTION_RESUME)

    /** 정지 → 조각 합치기 → 업로드까지 서비스가 알아서 이어서 한다. */
    @JavascriptInterface
    fun stop() = RecordingService.send(context, RecordingService.ACTION_STOP)

    /** 녹음을 버린다 (올리지 않는다). */
    @JavascriptInterface
    fun discard() = RecordingService.send(context, RecordingService.ACTION_DISCARD)

    /** 화면이 안내 문구를 한 번 보여 준 뒤 지운다. */
    @JavascriptInterface
    fun clearMessage() = RecordingService.clearMessage()

    companion object {
        /** 웹에서 이 이름으로 보인다: `window.DagloNative` */
        const val NAME = "DagloNative"
    }
}
