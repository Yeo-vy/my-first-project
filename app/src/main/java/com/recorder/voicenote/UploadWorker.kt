package com.recorder.voicenote

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** 화면에 보여줄 업로드 상태 */
data class UploadStatus(
    /** 지금 올리고 있는 파일 이름 (없으면 올리는 중이 아님) */
    val uploadingName: String? = null,
    /** 마지막 결과 안내 문구 */
    val lastMessage: String? = null,
    val lastFailed: Boolean = false
)

/**
 * 녹음 파일을 daglo 서버로 올리는 작업.
 *
 * 강의실 와이파이가 끊겨 있거나 서버 PC 가 꺼져 있을 때가 많으므로, 그 자리에서 한 번 시도하고
 * 마는 대신 WorkManager 에 맡긴다. 네트워크가 없으면 생길 때까지 기다렸다가 알아서 올라가고,
 * 앱을 껐다 켜도 작업은 살아 있다.
 */
class UploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val displayName = inputData.getString(KEY_DISPLAY_NAME) ?: return@withContext Result.failure()
        val folderName = inputData.getString(KEY_FOLDER_NAME) ?: ""
        val uriString = inputData.getString(KEY_URI)
        val filePath = inputData.getString(KEY_FILE_PATH)

        val settings = DagloSettings(applicationContext)
        if (!settings.isConfigured) {
            // 서버 주소가 없으면 재시도해도 소용없다. 조용히 끝낸다.
            return@withContext Result.failure()
        }

        _status.value = UploadStatus(uploadingName = displayName)

        val api = DagloApi(settings)
        val result = api.uploadRecording(
            context = applicationContext,
            contentUri = uriString?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) },
            filePath = filePath?.takeIf { it.isNotBlank() },
            displayName = displayName,
            folderName = folderName
        )

        when (result) {
            is ApiResult.Success -> {
                _status.value = UploadStatus(lastMessage = "서버로 보냈습니다: $displayName")
                Result.success()
            }
            is ApiResult.Fatal -> {
                _status.value = UploadStatus(
                    lastMessage = "업로드 실패: ${result.message}",
                    lastFailed = true
                )
                Result.failure()
            }
            is ApiResult.Retryable -> {
                if (runAttemptCount >= MAX_ATTEMPTS) {
                    _status.value = UploadStatus(
                        lastMessage = "업로드를 여러 번 시도했지만 실패했습니다: ${result.message}",
                        lastFailed = true
                    )
                    Result.failure()
                } else {
                    // 녹음 파일은 폰에 그대로 남아 있으니, 나중에 수동으로 다시 올릴 수도 있다.
                    _status.value = UploadStatus(
                        lastMessage = "서버에 연결하지 못해 나중에 다시 시도합니다",
                        lastFailed = true
                    )
                    Result.retry()
                }
            }
        }
    }

    companion object {
        private const val WORK_PREFIX = "daglo-upload-"
        private const val KEY_URI = "uri"
        private const val KEY_FILE_PATH = "file_path"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_FOLDER_NAME = "folder_name"
        private const val MAX_ATTEMPTS = 5

        private val _status = MutableStateFlow(UploadStatus())
        val status: StateFlow<UploadStatus> = _status.asStateFlow()

        fun consumeMessage() {
            _status.value = _status.value.copy(lastMessage = null)
        }

        /**
         * 업로드를 예약한다. 같은 파일을 두 번 예약해도 (자동 업로드 + 수동 업로드처럼)
         * 이미 걸려 있는 작업이 있으면 그대로 두므로 중복 업로드가 생기지 않는다.
         */
        fun enqueue(
            context: Context,
            contentUri: Uri?,
            filePath: String?,
            displayName: String,
            folderName: String
        ) {
            val data = Data.Builder()
                .putString(KEY_URI, contentUri?.toString() ?: "")
                .putString(KEY_FILE_PATH, filePath ?: "")
                .putString(KEY_DISPLAY_NAME, displayName)
                .putString(KEY_FOLDER_NAME, folderName)
                .build()

            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_PREFIX + displayName,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
