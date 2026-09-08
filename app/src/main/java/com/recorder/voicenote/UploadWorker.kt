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
                notify(file, "녹음을 올렸습니다", "$folder · 받아쓰기가 시작됩니다")
                Result.success()
            }
            is ApiResult.Retryable -> {
                // 서버가 꺼져 있거나 네트워크가 불안정한 경우. WorkManager 가 시간을 두고 다시 부른다.
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
        private const val MAX_ATTEMPTS = 5

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
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            // 파일 경로마다 하나씩만 돌게 해서, 다시 시도할 때 같은 파일이 겹쳐 올라가지 않게 한다.
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork("upload:${file.name}", ExistingWorkPolicy.KEEP, request)
        }

        /**
         * 아직 못 올린 녹음을 다시 집어넣는다. 앱을 열 때마다 부른다.
         *
         * 로그인이 풀린 채 녹음을 마쳤거나 서버가 오래 꺼져 있었다면 파일이 남아 있는데,
         * 웹 화면에서 로그인한 뒤 앱을 다시 열면 여기서 자동으로 올라간다.
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
