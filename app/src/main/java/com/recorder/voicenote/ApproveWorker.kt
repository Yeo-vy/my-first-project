package com.recorder.voicenote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * 업로드 알림의 `받아쓰기 시작` 버튼을 받는다.
 *
 * 서버는 올라온 녹음을 곧바로 받아쓰지 않고 허가를 기다린다 (AI 요금이 드는 일이라서다).
 * 그 허가를 하려고 앱을 열어 방금 올린 녹음을 찾아 들어갈 필요가 없도록 알림에서 바로 받는다.
 *
 * 리시버는 오래 살지 못하므로 여기서는 알림만 바꿔 눌렸다는 것을 보여 주고, 서버에 알리는 일은
 * [ApproveWorker] 에 넘긴다. 서버가 잠깐 닿지 않아도 워커가 이어서 다시 시도한다.
 */
class ApproveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_APPROVE) return
        val boardId = intent.getLongExtra(EXTRA_BOARD_ID, UploadNotice.NO_BOARD)
        if (boardId <= 0) return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, boardId.toInt())
        val label = intent.getStringExtra(EXTRA_LABEL) ?: ""

        // 버튼을 뗀 알림으로 바꿔 둔다. 눌렀는데 그대로면 여러 번 누르게 된다.
        UploadNotice.show(
            context,
            notificationId,
            "받아쓰기를 시작하는 중",
            ApproveWorker.withLabel(label, "서버에 알리고 있습니다."),
            boardId = boardId
        )
        ApproveWorker.enqueue(context, boardId, notificationId, label)
    }

    companion object {
        const val ACTION_APPROVE = "com.recorder.voicenote.APPROVE"
        const val EXTRA_BOARD_ID = "board_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_LABEL = "label"
    }
}

/**
 * 녹음 하나의 받아쓰기를 서버에 허가한다 (`POST /api/boards/{id}/approve`).
 *
 * 웹 화면의 `받아쓰기 시작` 버튼이 부르는 것과 같은 주소다. 앱은 허가를 대신 눌러 줄 뿐이고,
 * 받아쓰기 자체는 평소처럼 서버가 한다.
 */
class ApproveWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val boardId = inputData.getLong(KEY_BOARD, UploadNotice.NO_BOARD)
        if (boardId <= 0) return Result.failure()
        val notificationId = inputData.getInt(KEY_NOTIFICATION, boardId.toInt())
        val label = inputData.getString(KEY_LABEL) ?: ""

        val settings = DagloSettings(applicationContext)
        if (!settings.isConfigured) {
            notify(notificationId, boardId, label, "받아쓰기를 시작하지 못했습니다", "앱에서 서버 주소를 넣어 주세요.")
            return Result.failure()
        }

        return when (val result = DagloApi(settings.serverUrl).approve(boardId)) {
            is ApiResult.Success -> {
                notify(
                    notificationId, boardId, label,
                    "받아쓰기를 시작했습니다",
                    "알림을 누르면 앱에서 진행 상황을 볼 수 있습니다."
                )
                Result.success()
            }
            is ApiResult.Retryable -> {
                if (runAttemptCount < MAX_ATTEMPTS) {
                    notify(
                        notificationId, boardId, label,
                        "받아쓰기를 시작하는 중",
                        "서버에 닿지 않아 잠시 뒤 다시 시도합니다. (${result.message})"
                    )
                    Result.retry()
                } else {
                    // 더는 스스로 시도하지 않으므로 버튼을 다시 붙여 준다
                    notify(
                        notificationId, boardId, label,
                        "받아쓰기를 시작하지 못했습니다",
                        "서버에 닿지 않습니다. 다시 누르거나 앱에서 시작해 주세요. (${result.message})",
                        offerApprove = true
                    )
                    Result.failure()
                }
            }
            is ApiResult.Fatal -> {
                if (result.conflict) {
                    // 웹에서 먼저 눌렀거나 이미 끝난 녹음이다. 할 일이 없으니 실패로 보이지 않게 한다.
                    notify(
                        notificationId, boardId, label,
                        "이미 받아쓰기를 시작한 녹음입니다",
                        "알림을 누르면 앱에서 볼 수 있습니다."
                    )
                    Result.success()
                } else {
                    // 로그인이 풀린 경우가 대부분이다. 앱에서 로그인한 뒤 다시 누를 수 있게 버튼을 둔다.
                    notify(
                        notificationId, boardId, label,
                        "받아쓰기를 시작하지 못했습니다",
                        result.message,
                        offerApprove = true
                    )
                    Result.failure()
                }
            }
        }
    }

    private fun notify(
        notificationId: Int,
        boardId: Long,
        label: String,
        title: String,
        text: String,
        offerApprove: Boolean = false
    ) {
        UploadNotice.show(
            applicationContext,
            notificationId,
            title,
            withLabel(label, text),
            boardId = boardId,
            offerApprove = offerApprove,
            label = label
        )
    }

    companion object {
        private const val KEY_BOARD = "board_id"
        private const val KEY_NOTIFICATION = "notification_id"
        private const val KEY_LABEL = "label"
        /** 30초부터 두 배씩 늘어나므로 다섯 번이면 7분 남짓이다. 그 뒤에는 버튼을 돌려준다. */
        private const val MAX_ATTEMPTS = 4

        /** 알림 본문 앞에 폴더 이름을 붙인다 (업로드 알림과 같은 모양). */
        fun withLabel(label: String, text: String): String =
            if (label.isEmpty()) text else "$label · $text"

        fun enqueue(context: Context, boardId: Long, notificationId: Int, label: String) {
            val request = OneTimeWorkRequestBuilder<ApproveWorker>()
                .setInputData(
                    Data.Builder()
                        .putLong(KEY_BOARD, boardId)
                        .putInt(KEY_NOTIFICATION, notificationId)
                        .putString(KEY_LABEL, label)
                        .build()
                )
                .apply {
                    // 버튼을 눌렀으면 바로 돌아야 한다. 보통 작업은 절전이 화면이 꺼진 동안 미룬다.
                    // 12 미만에서는 급한 작업이 포그라운드 알림을 요구해서, 12 부터만 건다
                    // ([UploadWorker] 와 같은 이유).
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    }
                }
                .build()

            // 같은 녹음을 두 번 허가하지 않는다. 이미 돌고 있으면 그대로 둔다.
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork("approve:$boardId", ExistingWorkPolicy.KEEP, request)
        }
    }
}
