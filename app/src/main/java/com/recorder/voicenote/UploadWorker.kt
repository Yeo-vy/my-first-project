package com.recorder.voicenote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File

/** 아직 못 올리고 기다리는 녹음 한 개 (오프라인 화면이 목록으로 보여 준다). */
data class PendingUpload(
    val folder: String,
    val bytes: Long,
    /** 녹음을 마친 시각 (epoch ms) */
    val savedAt: Long
)

/**
 * 다 합쳐진 녹음 파일 하나를 서버로 올린다.
 *
 * 앱을 나가거나 화면을 꺼도 WorkManager 가 이어서 돌리고, 와이파이가 끊겨 있으면 연결될 때까지
 * 기다렸다가 자동으로 다시 시도한다. 강의가 끝나고 가방에 넣어 둔 사이에 올라가 있는 것이
 * 이 앱이 노리는 그림이다.
 *
 * 올리기 전까지 파일은 앱 전용 저장소에 남아 있고, 성공해야 지운다. 로그인이 풀려 실패한 경우엔
 * 파일을 그대로 두고 다음 실행 때 [retryPending] 이 다시 집어넣는다.
 */
class UploadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val path = inputData.getString(KEY_FILE) ?: return Result.failure()
        val folder = inputData.getString(KEY_FOLDER) ?: DEFAULT_FOLDER
        val file = File(path)
        if (!file.isFile || file.length() == 0L) {
            cleanup(file)
            return Result.failure()
        }

        val settings = DagloSettings(applicationContext)
        if (!settings.isConfigured) {
            notify(file, "서버 주소가 없어 올리지 못했습니다", "앱에서 서버 주소를 넣어 주세요.")
            return Result.failure()
        }

        return when (val result = DagloApi(settings.serverUrl).upload(file, folder)) {
            is ApiResult.Success -> {
                cleanup(file)
                notify(file, "녹음을 올렸습니다", "$folder · 웹에서 받아쓰기를 시작해 주세요")
                Result.success()
            }
            is ApiResult.Retryable -> {
                // 서버가 꺼져 있거나 와이파이 밖인 경우. WorkManager 가 시간을 두고 다시 부른다
                // (30초에서 시작해 두 배씩 늘어나므로, 강의 한 타임을 밖에서 보내도 따라잡는다).
                if (runAttemptCount < MAX_ATTEMPTS) {
                    Result.retry()
                } else {
                    notify(file, "녹음을 아직 올리지 못했습니다", "앱을 열면 다시 시도합니다. (${result.message})")
                    Result.failure()
                }
            }
            is ApiResult.Fatal -> {
                // 로그인이 풀린 경우가 대부분이다. 파일은 지우지 않고 다음 실행 때 다시 올린다.
                notify(file, "녹음을 올리지 못했습니다", result.message)
                Result.failure()
            }
        }
    }

    /** 올린 파일과 곁딸린 폴더 메모를 함께 지운다. */
    private fun cleanup(file: File) {
        try {
            file.delete()
            folderSidecar(file).delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun notify(file: File, title: String, text: String) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "업로드", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            manager.notify(file.name.hashCode(), notification)
        } catch (e: Exception) {
            // 알림 권한이 없으면 조용히 넘어간다 (업로드 결과는 웹 목록에서 확인할 수 있다)
            e.printStackTrace()
        }
    }

    companion object {
        private const val KEY_FILE = "file"
        private const val KEY_FOLDER = "folder"
        private const val CHANNEL_ID = "daglo_upload"
        private const val DEFAULT_FOLDER = "기본 폴더"
        /**
         * 몇 번까지 스스로 다시 시도할지.
         *
         * 네트워크 조건을 걸지 않으므로 와이파이 밖에서도 시도가 소모된다. 그만큼 넉넉히 잡아
         * 두면(30초부터 두 배씩 늘어 열 번이면 여덟 시간 남짓) 강의가 끝나고 돌아오는 동안 대개
         * 저절로 올라간다. 다 써도 파일은 남고 [retryPending] 이 다시 집어넣는다.
         */
        private const val MAX_ATTEMPTS = 10

        fun enqueue(context: Context, file: File, folderName: String) {
            writeSidecar(file, folderName)

            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_FILE, file.absolutePath)
                        .putString(KEY_FOLDER, folderName)
                        .build()
                )
                .setConstraints(
                    // 일부러 네트워크 조건을 걸지 않는다.
                    //
                    // NetworkType.CONNECTED 는 안드로이드 8 부터 '인터넷이 확인된' 연결만 인정한다.
                    // 이 서버는 집·학교 공유기 안(LAN)에 있어서, 공유기가 인터넷에 못 나가는
                    // 순간에도 서버에는 닿는다. 그때 조건을 걸어 두면 올릴 수 있는데도 영영
                    // 기다린다. 그래서 그냥 시도해 보고, 실패하면 아래 Retryable 로 물러난다.
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .build()

            // 파일 경로마다 하나씩만 돌게 해서, 다시 시도할 때 같은 파일이 겹쳐 올라가지 않게 한다.
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork("upload:${file.name}", ExistingWorkPolicy.KEEP, request)
        }

        /**
         * 아직 못 올린 녹음들. 오래된 것부터 (올라가는 순서와 같다).
         *
         * 서버 없이 녹음하고 나면 파일이 여기 쌓이는데, 화면에 아무 흔적이 없으면 녹음이 사라진
         * 줄 안다. 오프라인 화면이 이 목록을 그대로 보여 준다.
         */
        fun pending(context: Context): List<PendingUpload> =
            (RecordingService.uploadsDir(context).listFiles() ?: emptyArray())
                .filter { it.isFile && it.name.endsWith(".m4a") && it.length() > 0 }
                .sortedBy { it.lastModified() }
                .map { PendingUpload(readSidecar(it), it.length(), it.lastModified()) }

        /**
         * 아직 못 올린 녹음을 다시 집어넣는다. 앱을 열 때마다 부른다.
         *
         * 로그인이 풀린 채 녹음을 마쳤거나 서버 밖(와이파이가 없는 강의실)에서 녹음했다면 파일이
         * 남아 있는데, 서버에 다시 닿는 곳에서 앱을 열면 여기서 자동으로 올라간다.
         * 오프라인 화면의 '지금 올려 보기' 와 연결이 돌아온 순간에도 같은 길을 탄다.
         */
        fun retryPending(context: Context) {
            val files = RecordingService.uploadsDir(context).listFiles() ?: return
            for (file in files) {
                if (!file.isFile || !file.name.endsWith(".m4a")) continue
                enqueue(context, file, readSidecar(file))
            }
        }

        /** 어느 폴더로 올릴지 파일 옆에 적어 둔다 (앱이 꺼졌다 켜져도 알 수 있게). */
        private fun writeSidecar(file: File, folderName: String) {
            try {
                folderSidecar(file).writeText(folderName, Charsets.UTF_8)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        private fun readSidecar(file: File): String {
            return try {
                val sidecar = folderSidecar(file)
                if (sidecar.isFile) sidecar.readText(Charsets.UTF_8).trim() else DEFAULT_FOLDER
            } catch (e: Exception) {
                DEFAULT_FOLDER
            }
        }

        private fun folderSidecar(file: File): File = File(file.absolutePath + ".folder")
    }
}
