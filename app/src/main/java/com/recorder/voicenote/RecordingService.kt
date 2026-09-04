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
import kotlinx.coroutines.Dispatchers
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
    val isPaused: Boolean = false,
    /** 녹음을 멈추고 조각을 합쳐 저장하는 중 */
    val isSaving: Boolean = false,
    val folderName: String? = null,
    val elapsedSeconds: Int = 0,
    /** 마이크 입력 세기 0.0~1.0. 소리가 실제로 들어오는지 화면에서 확인하는 용도 */
    val level: Float = 0f,
    val errorMessage: String? = null,
    /** 저장이 끝났을 때 안내할 문구 (파일명 등) */
    val savedMessage: String? = null
)

/**
 * 실제 마이크 녹음을 담당하는 포그라운드 서비스.
 *
 * 화면을 끄거나 앱을 나가도 녹음이 이어지고, 녹음 자체는 [RecorderManager] 가 조각 파일로 쌓는다.
 * 정지하면 조각들을 하나의 m4a 로 합쳐 공용 저장소에 넣는다. 합치기는 3시간 녹음이라도 몇 초면
 * 끝나지만(재인코딩 없이 옮겨 담기만 한다) 그동안 알림에 '저장 중' 을 표시한다.
 */
class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.recorder.voicenote.action.START"
        const val ACTION_STOP = "com.recorder.voicenote.action.STOP"
        const val ACTION_CANCEL = "com.recorder.voicenote.action.CANCEL"
        const val ACTION_PAUSE = "com.recorder.voicenote.action.PAUSE"
        const val ACTION_RESUME = "com.recorder.voicenote.action.RESUME"
        const val EXTRA_FOLDER_NAME = "extra_folder_name"

        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIFICATION_ID = 1001

        // 프로세스 내에서 공유되는 녹음 상태 (Activity/ViewModel이 재생성되어도 그대로 관찰 가능)
        private val _state = MutableStateFlow(RecordingServiceState())
        val state: StateFlow<RecordingServiceState> = _state.asStateFlow()

        fun consumeError() {
            _state.value = _state.value.copy(errorMessage = null)
        }

        fun consumeSavedMessage() {
            _state.value = _state.value.copy(savedMessage = null)
        }
    }

    private lateinit var store: RecordingStore
    private lateinit var recorderManager: RecorderManager
    private var session: RecordingSession? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var timerJob: Job? = null

    /** 일시정지한 시간을 뺀 실제 녹음 길이를 재기 위한 값들 */
    private var segmentStartedAt = 0L
    private var accumulatedMillis = 0L

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
            ACTION_STOP -> stopRecording(save = true)
            ACTION_CANCEL -> stopRecording(save = false)
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRecording(folderName: String) {
        // 저장(합치기)이 끝나기 전에 새 녹음을 받으면, 저장 완료 처리가 새 녹음 상태를 덮어써 버린다.
        if (_state.value.isRecording || _state.value.isSaving) return

        val fileName = store.buildFileName(folderName, Date())
        val newSession = RecordingSession.create(applicationContext, folderName, fileName)
        if (newSession == null) {
            _state.value = RecordingServiceState(errorMessage = "녹음 파일을 준비할 수 없습니다")
            stopSelf()
            return
        }

        val started = recorderManager.start(newSession, object : RecorderManager.Callback {
            override fun onSegmentStarted(index: Int) {
                // 조각이 넘어간 것은 사용자가 알 필요 없다. 로그만 남기고 화면은 그대로 둔다.
                android.util.Log.i("RecordingService", "다음 녹음 조각으로 이어받았습니다 (#$index)")
            }

            override fun onError(message: String) {
                // 여기까지 녹음된 조각은 살려야 하므로, 버리지 않고 저장까지 진행한다.
                _state.value = _state.value.copy(errorMessage = message)
                stopRecording(save = true)
            }
        })

        if (!started) {
            newSession.delete()
            _state.value = RecordingServiceState(errorMessage = "녹음을 시작할 수 없습니다")
            stopSelf()
            return
        }

        session = newSession
        accumulatedMillis = 0L
        segmentStartedAt = SystemClock.elapsedRealtime()
        _state.value = RecordingServiceState(
            isRecording = true,
            folderName = folderName,
            elapsedSeconds = 0
        )

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(folderName, 0, paused = false),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
        )
        startTimer(folderName)
    }

    private fun pauseRecording() {
        if (!_state.value.isRecording || _state.value.isPaused) return
        if (!recorderManager.pause()) return

        accumulatedMillis += SystemClock.elapsedRealtime() - segmentStartedAt
        _state.value = _state.value.copy(isPaused = true, level = 0f)
        updateNotification(_state.value.folderName ?: "", elapsedSeconds(), paused = true)
    }

    private fun resumeRecording() {
        if (!_state.value.isRecording || !_state.value.isPaused) return
        if (!recorderManager.resume()) return

        segmentStartedAt = SystemClock.elapsedRealtime()
        _state.value = _state.value.copy(isPaused = false)
        updateNotification(_state.value.folderName ?: "", elapsedSeconds(), paused = false)
    }

    /**
     * 녹음을 멈춘다.
     * @param save true 면 조각을 합쳐 저장하고, false 면 통째로 버린다(녹음 취소).
     */
    private fun stopRecording(save: Boolean) {
        val current = session
        if (current == null) {
            _state.value = RecordingServiceState()
            stopSelf()
            return
        }

        if (!_state.value.isPaused) {
            accumulatedMillis += SystemClock.elapsedRealtime() - segmentStartedAt
        }
        timerJob?.cancel()

        // stop() 이 실패해도(너무 짧은 녹음, 이미 죽은 인코더) 앞서 닫힌 조각들은 살아 있다.
        recorderManager.stop()
        session = null

        if (!save) {
            current.delete()
            _state.value = RecordingServiceState()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        _state.value = _state.value.copy(isRecording = false, isPaused = false, isSaving = true, level = 0f)
        updateSavingNotification()

        serviceScope.launch {
            val result = RecordingSaver.save(applicationContext, store, current)
            val previousError = _state.value.errorMessage
            _state.value = RecordingServiceState(
                errorMessage = previousError ?: (result as? RecordingSaver.Result.Failed)?.message,
                savedMessage = (result as? RecordingSaver.Result.Saved)?.let {
                    if (it.splitCount > 1) {
                        "녹음을 저장했습니다 (합치지 못해 ${it.splitCount}개로 나뉘어 저장됨)"
                    } else {
                        "녹음을 저장했습니다: ${it.displayName}"
                    }
                }
            )
            if (!_state.value.isRecording) {
                ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun elapsedSeconds(): Int {
        val running = if (_state.value.isPaused || !_state.value.isRecording) {
            0L
        } else {
            SystemClock.elapsedRealtime() - segmentStartedAt
        }
        return ((accumulatedMillis + running) / 1000L).toInt()
    }

    private fun startTimer(folderName: String) {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (true) {
                delay(500)
                if (!_state.value.isRecording) break
                val seconds = elapsedSeconds()
                // 입력 레벨은 32767 이 최대치다. 로그 스케일이 아니라 눈에 잘 보이도록 제곱근을 쓴다.
                val amplitude = recorderManager.maxAmplitude.coerceIn(0, 32767)
                val level = if (amplitude <= 0) 0f else Math.sqrt(amplitude / 32767.0).toFloat()
                val previous = _state.value
                _state.value = previous.copy(elapsedSeconds = seconds, level = level)
                if (seconds != previous.elapsedSeconds) {
                    updateNotification(folderName, seconds, previous.isPaused)
                }
            }
        }
    }

    private fun buildNotification(
        folderName: String,
        elapsedSeconds: Int,
        paused: Boolean
    ): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (paused) "$folderName 녹음 일시정지" else "$folderName 녹음 중")
            .setContentText(formatTime(elapsedSeconds))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())

        if (paused) {
            builder.addAction(
                android.R.drawable.ic_media_play, "재개", serviceAction(ACTION_RESUME, 2)
            )
        } else {
            builder.addAction(
                android.R.drawable.ic_media_pause, "일시정지", serviceAction(ACTION_PAUSE, 1)
            )
        }
        builder.addAction(
            android.R.drawable.ic_menu_save, "정지", serviceAction(ACTION_STOP, 0)
        )
        return builder.build()
    }

    private fun updateSavingNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("녹음 저장 중")
            .setContentText("녹음 조각을 하나로 합치고 있습니다")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setContentIntent(openAppIntent())
            .build()
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    private fun openAppIntent(): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, RecordingService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateNotification(folderName: String, elapsedSeconds: Int, paused: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(folderName, elapsedSeconds, paused))
    }

    private fun formatTime(elapsedSeconds: Int): String {
        val h = elapsedSeconds / 3600
        val m = (elapsedSeconds % 3600) / 60
        val s = elapsedSeconds % 60
        return if (h > 0) {
            String.format("%d:%02d:%02d", h, m, s)
        } else {
            String.format("%02d:%02d", m, s)
        }
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
        // 서비스가 시스템에 의해 죽는 경우에도 조각 파일은 지우지 않는다.
        // 다음 실행 때 RecordingSaver 가 남은 조각을 찾아 복구한다.
        if (recorderManager.isRecording) {
            recorderManager.stop()
        }
        serviceScope.cancel()
    }
}
