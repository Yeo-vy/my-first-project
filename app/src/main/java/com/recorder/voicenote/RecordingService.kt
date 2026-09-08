package com.recorder.voicenote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 웹 화면(JS 브리지)이 들여다보는 녹음 상태 한 장. */
data class RecordingSnapshot(
    val recording: Boolean = false,
    val paused: Boolean = false,
    /** 녹음을 멈추고 조각을 합쳐 올리는 중 */
    val saving: Boolean = false,
    val elapsedMs: Long = 0,
    val level: Float = 0f,
    val folderName: String = "",
    /** 화면에 그대로 띄울 안내/오류 문구 (없으면 빈 문자열) */
    val message: String = ""
)

/**
 * 녹음을 맡는 포그라운드 서비스. 이 앱이 웹으로 못 하는 일은 이것 하나뿐이다.
 *
 * **왜 서비스인가**: 브라우저 녹음(MediaRecorder)은 화면을 끄면 탭이 재워지거나 메모리 회수로
 * 죽어 녹음이 통째로 사라진다. 이 앱의 주 용도가 "화면 끄고 3시간 강의 녹음"이라, 녹음만
 * 포그라운드 서비스 + 진행 중 알림으로 앱이 맡는다. 알림이 떠 있는 동안 시스템은 이 프로세스를
 * 함부로 죽이지 않고, 부분 웨이크 락으로 CPU 도 잠들지 않게 한다.
 *
 * 정지하면 조각을 하나의 m4a 로 합쳐 [UploadWorker] 에 넘긴다. 그 뒤(받아쓰기·스크립트)는
 * 웹에서 파일을 올렸을 때와 완전히 같다.
 */
class RecordingService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var recorder: SegmentRecorder
    private var wakeLock: PowerManager.WakeLock? = null

    private var sessionDir: File? = null
    private var folderName: String = ""
    private var startedAtElapsed = 0L      // 이번 구간이 시작된 시각 (SystemClock)
    private var accumulatedMs = 0L         // 일시정지로 끊긴 구간까지 합친 길이
    /** 녹음이 스스로 멈춘 이유. 저장이 끝난 뒤 결과 문구 앞에 붙여 화면에 알린다. */
    private var stoppedReason = ""

    private val ticker = object : Runnable {
        override fun run() {
            if (!isActive()) return
            publish()
            updateNotification()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        recorder = SegmentRecorder(applicationContext)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent.getStringExtra(EXTRA_FOLDER) ?: DEFAULT_FOLDER)
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> stop(save = true)
            ACTION_DISCARD -> stop(save = false)
        }
        // 녹음 중이 아닐 때 시스템이 서비스를 되살릴 이유가 없다 (되살아나면 빈 알림만 남는다)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        releaseWakeLock()
        super.onDestroy()
    }

    // ------------------------------------------------------------------------------

    private fun start(folder: String) {
        if (snapshot.recording || snapshot.saving) return

        folderName = folder
        accumulatedMs = 0
        // startForegroundService() 로 불려 왔으므로 5초 안에 반드시 알림을 띄워야 한다.
        // 마이크를 열지 못하는 경우까지 생각해서, 알림을 먼저 올리고 녹음을 시작한다.
        startForegroundNotification()

        val dir = File(sessionsDir(this), "session_${System.currentTimeMillis()}")
        val started = recorder.start(dir) { message -> onRecorderError(message) }
        if (!started) {
            dir.deleteRecursively()
            snapshot = RecordingSnapshot(message = "마이크를 열지 못했습니다. 다른 앱이 쓰고 있는지 확인해 주세요.")
            stopForegroundAndSelf()
            return
        }

        sessionDir = dir
        startedAtElapsed = SystemClock.elapsedRealtime()
        writeFolderName(dir, folder)

        // 화면을 끈 채 몇 시간을 녹음해도 CPU 가 잠들지 않게 한다 (화면은 켜지 않는다)
        acquireWakeLock()

        publish()
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 1000)
    }

    private fun pause() {
        if (!snapshot.recording || snapshot.paused) return
        if (!recorder.pause()) return
        accumulatedMs += SystemClock.elapsedRealtime() - startedAtElapsed
        publish()
        updateNotification()
    }

    private fun resume() {
        if (!snapshot.recording || !snapshot.paused) return
        if (!recorder.resume()) return
        startedAtElapsed = SystemClock.elapsedRealtime()
        publish()
        updateNotification()
    }

    /** 녹음을 멈춘다. save=false 면 조각을 버린다. */
    private fun stop(save: Boolean) {
        if (!snapshot.recording) {
            stopSelf()
            return
        }
        handler.removeCallbacks(ticker)
        if (!snapshot.paused) {
            accumulatedMs += SystemClock.elapsedRealtime() - startedAtElapsed
        }
        recorder.stop()
        releaseWakeLock()

        val dir = sessionDir
        sessionDir = null

        if (!save || dir == null) {
            dir?.deleteRecursively()
            snapshot = RecordingSnapshot(message = if (save) "" else "녹음을 버렸습니다")
            stopForegroundAndSelf()
            return
        }

        // 합치기는 재인코딩이 없어 3시간짜리도 몇 초면 끝나지만, 그동안 알림을 '저장 중' 으로 둔다.
        snapshot = RecordingSnapshot(saving = true, elapsedMs = accumulatedMs, folderName = folderName)
        updateNotification()

        val folder = folderName
        val reason = stoppedReason
        stoppedReason = ""
        Thread {
            val result = mergeAndQueue(applicationContext, dir, folder)
            handler.post {
                snapshot = RecordingSnapshot(
                    message = if (reason.isEmpty()) result else "$reason $result"
                )
                stopForegroundAndSelf()
            }
        }.start()
    }

    private fun onRecorderError(message: String) {
        // 녹음이 스스로 멈춘 경우다. 그때까지 쌓인 조각은 살아 있으므로 그대로 저장까지 마친다.
        handler.post {
            stoppedReason = message
            stop(save = true)
        }
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun isActive(): Boolean = snapshot.recording || snapshot.saving

    /** 지금 상태를 웹 화면이 볼 수 있는 곳에 적어 둔다. */
    private fun publish() {
        val elapsed = accumulatedMs + if (recorder.isRecording && !recorder.isPaused) {
            SystemClock.elapsedRealtime() - startedAtElapsed
        } else {
            0L
        }
        snapshot = RecordingSnapshot(
            recording = recorder.isRecording,
            paused = recorder.isPaused,
            saving = false,
            elapsedMs = elapsed,
            level = recorder.level,
            folderName = folderName,
            message = snapshot.message
        )
    }

    // ---- 알림 ---------------------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "녹음",
            NotificationManager.IMPORTANCE_LOW      // 소리 없이 조용히 떠 있게 한다
        ).apply {
            description = "녹음 중에는 이 알림이 떠 있어야 화면을 꺼도 녹음이 이어집니다."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun startForegroundNotification() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            // 알림 권한이 없으면(안드로이드 13+) 알림만 안 뜬다. 녹음 자체는 계속된다.
            e.printStackTrace()
        }
    }

    private fun buildNotification(): Notification {
        val state = snapshot
        val title = when {
            state.saving -> "녹음 저장 중..."
            state.paused -> "녹음 일시정지"
            else -> "녹음 중"
        }
        val text = buildString {
            append(formatElapsed(state.elapsedMs))
            if (state.folderName.isNotEmpty()) append("  ·  ").append(state.folderName)
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)

        if (!state.saving) {
            if (state.paused) {
                builder.addAction(0, "이어서", actionIntent(ACTION_RESUME))
            } else {
                builder.addAction(0, "일시정지", actionIntent(ACTION_PAUSE))
            }
            builder.addAction(0, "정지 후 올리기", actionIntent(ACTION_STOP))
        }
        return builder.build()
    }

    private fun actionIntent(action: String): PendingIntent {
        val intent = Intent(this, RecordingService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ---- 웨이크 락 -----------------------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "daglo:recording").apply {
                setReferenceCounted(false)
                acquire(MAX_RECORDING_MS)
            }
        } catch (e: Exception) {
            // 락을 못 잡아도 포그라운드 서비스만으로 대개 버틴다
            e.printStackTrace()
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "com.recorder.voicenote.START"
        const val ACTION_PAUSE = "com.recorder.voicenote.PAUSE"
        const val ACTION_RESUME = "com.recorder.voicenote.RESUME"
        const val ACTION_STOP = "com.recorder.voicenote.STOP"
        const val ACTION_DISCARD = "com.recorder.voicenote.DISCARD"
        const val EXTRA_FOLDER = "folder"

        private const val CHANNEL_ID = "daglo_recording"
        private const val NOTIFICATION_ID = 1001
        private const val DEFAULT_FOLDER = "기본 폴더"
        /** 웨이크 락 상한. 강의 한 타임을 훨씬 넘겨 잡아 둔다 (정지하면 그 자리에서 푼다). */
        private const val MAX_RECORDING_MS = 6L * 60 * 60 * 1000

        /** 화면(JS 브리지)이 읽는 현재 상태. 서비스가 죽어도 마지막 문구는 남는다. */
        @Volatile
        var snapshot = RecordingSnapshot()
            private set

        /** 웹 화면이 문구를 한 번 읽고 나면 지운다 (같은 안내를 계속 띄우지 않도록). */
        fun clearMessage() {
            snapshot = snapshot.copy(message = "")
        }

        fun send(context: Context, action: String, folderName: String? = null) {
            val intent = Intent(context, RecordingService::class.java).setAction(action)
            if (folderName != null) intent.putExtra(EXTRA_FOLDER, folderName)
            try {
                if (action == ACTION_START) {
                    context.startForegroundService(intent)
                } else {
                    // 이미 돌고 있는 서비스에 보내는 지시다. 서비스가 이미 끝난 뒤
                    // (오래된 알림 버튼 등) 보내면 시스템이 거부하므로 조용히 넘어간다.
                    context.startService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun formatElapsed(ms: Long): String {
            val total = (ms / 1000).coerceAtLeast(0)
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) {
                String.format(Locale.US, "%d:%02d:%02d", h, m, s)
            } else {
                String.format(Locale.US, "%02d:%02d", m, s)
            }
        }

        /** 녹음 조각이 쌓이는 곳 (앱 전용 저장소 — 권한이 필요 없고 앱을 지우면 함께 사라진다) */
        fun sessionsDir(context: Context): File =
            File(context.filesDir, "sessions").apply { mkdirs() }

        /** 합쳐서 올릴 파일이 잠시 머무는 곳 */
        fun uploadsDir(context: Context): File =
            File(context.filesDir, "uploads").apply { mkdirs() }

        /**
         * 조각을 하나로 합쳐 업로드 큐에 넣는다. 결과 문구를 돌려준다.
         *
         * 앱이 녹음 도중에 죽었다면 세션 폴더가 그대로 남는데, 다음 실행 때 [recoverOrphans] 가
         * 같은 경로로 이 함수를 불러 살려 낸다.
         */
        fun mergeAndQueue(context: Context, sessionDir: File, folderName: String): String {
            val segments = SegmentRecorder.segmentsOf(sessionDir)
            if (segments.isEmpty()) {
                sessionDir.deleteRecursively()
                return "녹음된 내용이 없습니다"
            }

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val safeFolder = folderName.ifBlank { DEFAULT_FOLDER }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val output = File(uploadsDir(context), "${safeFolder}_$stamp.m4a")

            return when (val result = AudioMerger.merge(segments, output)) {
                is AudioMerger.Result.Success -> {
                    sessionDir.deleteRecursively()
                    UploadWorker.enqueue(context, result.output, folderName)
                    "녹음을 올리는 중입니다. 변환이 끝나면 목록에 나타납니다."
                }
                is AudioMerger.Result.Failed -> {
                    // 합치기에 실패해도 조각은 남겨 둔다. 다음 실행 때 다시 시도한다.
                    "녹음을 합치지 못했습니다: ${result.message}"
                }
            }
        }

        /**
         * 앱이 녹음 도중 죽어 남은 세션이 있으면 합쳐서 올린다.
         *
         * 화면을 끄고 몇 시간을 녹음하는 앱이라, "앱이 죽어서 통째로 날렸다" 가 가장 큰 사고다.
         * 조각은 이미 디스크에 있으므로 다음 실행 때 주워 담으면 대부분 살릴 수 있다.
         */
        fun recoverOrphans(context: Context) {
            if (snapshot.recording || snapshot.saving) return
            val dirs = sessionsDir(context).listFiles() ?: return
            for (dir in dirs) {
                if (!dir.isDirectory) continue
                mergeAndQueue(context, dir, readFolderName(dir))
            }
        }

        private fun writeFolderName(sessionDir: File, folderName: String) {
            try {
                File(sessionDir, FOLDER_FILE).writeText(folderName, Charsets.UTF_8)
            } catch (e: Exception) {
                // 못 적어도 기본 폴더로 올라간다
                e.printStackTrace()
            }
        }

        private fun readFolderName(sessionDir: File): String {
            return try {
                val file = File(sessionDir, FOLDER_FILE)
                if (file.isFile) file.readText(Charsets.UTF_8).trim() else DEFAULT_FOLDER
            } catch (e: Exception) {
                DEFAULT_FOLDER
            }
        }

        private const val FOLDER_FILE = "folder.txt"
    }
}
