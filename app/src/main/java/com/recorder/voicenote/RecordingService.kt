package com.recorder.voicenote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Date

/** 서비스가 외부(ViewModel/UI)에 노출하는 녹음 상태 */
data class RecordingServiceState(
    val isRecording: Boolean = false,
    val folderName: String? = null,
    val elapsedSeconds: Int = 0,
    val errorMessage: String? = null
)

/**
 * 실제 마이크 녹음을 담당하는 포그라운드 서비스.
 * 화면이 꺼지거나 앱이 백그라운드로 가도 상태 표시줄에 알림을 띄운 채로 녹음을 계속 진행한다.
 */
class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.recorder.voicenote.action.START"
        const val ACTION_STOP = "com.recorder.voicenote.action.STOP"
        const val ACTION_CANCEL = "com.recorder.voicenote.action.CANCEL"
        const val EXTRA_FOLDER_NAME = "extra_folder_name"

        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIFICATION_ID = 1001

        // 프로세스 내에서 공유되는 녹음 상태 (Activity/ViewModel이 재생성되어도 그대로 관찰 가능)
        private val _state = MutableStateFlow(RecordingServiceState())
        val state: StateFlow<RecordingServiceState> = _state.asStateFlow()

        fun consumeError() {
            _state.value = _state.value.copy(errorMessage = null)
        }
    }

    private lateinit var store: RecordingStore
    private lateinit var recorderManager: RecorderManager
    private var currentTarget: RecordingTarget? = null

    private val serviceScope = CoroutineScope(SupervisorJob())
    private var timerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        store = RecordingStore(applicationContext)
        recorderManager = RecorderManager(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val folder = intent.getStringExtra(EXTRA_FOLDER_NAME)
                if (folder != null) startRecording(folder)
            }
            ACTION_STOP -> stopRecording()
            ACTION_CANCEL -> cancelRecording()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRecording(folderName: String) {
        if (_state.value.isRecording) return

        val fileName = store.buildFileName(folderName, Date())
        val target = store.prepareRecordingTarget(folderName, fileName)
        if (target == null) {
            _state.value = RecordingServiceState(errorMessage = "녹음 파일을 준비할 수 없습니다")
            stopSelf()
            return
        }

        val started = recorderManager.start(target)
        if (!started) {
            store.discardRecording(target)
            _state.value = RecordingServiceState(errorMessage = "녹음을 시작할 수 없습니다")
            stopSelf()
            return
        }

        currentTarget = target
        _state.value = RecordingServiceState(isRecording = true, folderName = folderName, elapsedSeconds = 0)

        val notification = buildNotification(folderName, 0)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
        )
        startTimer(folderName)
    }

    private fun stopRecording() {
        val success = recorderManager.stop()
        timerJob?.cancel()

        currentTarget?.let { target ->
            if (success) store.finalizeRecording(target) else store.discardRecording(target)
        }
        currentTarget = null

        _state.value = RecordingServiceState(isRecording = false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cancelRecording() {
        recorderManager.cancel()
        timerJob?.cancel()
        currentTarget?.let { store.discardRecording(it) }
        currentTarget = null

        _state.value = RecordingServiceState(isRecording = false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startTimer(folderName: String) {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            // delay 누적으로 초를 세면 doze/스로틀링에서 드리프트가 쌓이므로,
            // 부팅 후 흐른 실제 시간(elapsedRealtime) 기준으로 경과 시간을 계산한다.
            val startedAt = SystemClock.elapsedRealtime()
            while (true) {
                delay(1000)
                val elapsedSeconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000L).toInt()
                _state.value = _state.value.copy(elapsedSeconds = elapsedSeconds)
                updateNotification(folderName, elapsedSeconds)
            }
        }
    }

    private fun buildNotification(folderName: String, elapsedSeconds: Int): Notification {
        val stopIntent = Intent(this, RecordingService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPendingIntent = openAppIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("$folderName 녹음 중")
            .setContentText(formatTime(elapsedSeconds))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "정지", stopPendingIntent)
            .build()
    }

    private fun updateNotification(folderName: String, elapsedSeconds: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(folderName, elapsedSeconds))
    }

    private fun formatTime(elapsedSeconds: Int): String {
        val m = elapsedSeconds / 60
        val s = elapsedSeconds % 60
        return String.format("%02d:%02d", m, s)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "녹음 진행 상태",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "녹음이 진행 중일 때 표시되는 알림입니다"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        timerJob?.cancel()
        serviceScope.cancel()
        if (recorderManager.isRecording) {
            recorderManager.cancel()
            currentTarget?.let { store.discardRecording(it) }
        }
    }
}
