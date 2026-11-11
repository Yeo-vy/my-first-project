package com.recorder.voicenote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 업로드 결과를 알리는 알림. [UploadWorker] 와 [ApproveWorker] 가 같은 자리를 번갈아 쓴다.
 *
 * 예전 알림은 글자만 있어서 눌러도 아무 일이 없었다. "웹에서 받아쓰기를 시작해 주세요" 라고만
 * 하고 가는 길이 없으니, 앱을 따로 찾아 열고 방금 올린 녹음을 목록에서 다시 찾아야 했다.
 * 지금은 두 가지 길을 낸다.
 *  - 알림을 누르면 앱이 열린다. 어느 녹음인지 알면 그 녹음의 화면으로 바로 간다.
 *  - 받아쓰기 허가를 기다리는 녹음이면 `받아쓰기 시작` 버튼이 붙는다. 앱을 열지 않고 알림에서
 *    바로 허가한다 ([ApproveReceiver]).
 */
object UploadNotice {

    /** 어느 녹음인지 모를 때 (올리지 못했거나, 예전 서버가 번호를 주지 않았을 때) */
    const val NO_BOARD = -1L

    private const val CHANNEL_ID = "daglo_upload"

    /**
     * @param id 알림 자리. 같은 녹음은 같은 자리를 쓰므로 내용만 바뀐다.
     * @param boardId 서버가 준 녹음 번호. 있으면 알림을 눌렀을 때 그 녹음의 화면이 열린다.
     * @param offerApprove `받아쓰기 시작` 버튼을 붙일지 (boardId 가 있을 때만 붙는다)
     * @param label 버튼을 누른 뒤의 알림에 다시 적을 한 줄 (폴더 이름)
     */
    fun show(
        context: Context,
        id: Int,
        title: String,
        text: String,
        boardId: Long = NO_BOARD,
        offerApprove: Boolean = false,
        label: String = ""
    ) {
        val app = context.applicationContext
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "업로드", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val builder = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent(app, id, boardId))

        if (offerApprove && boardId > 0) {
            builder.addAction(0, "받아쓰기 시작", approveIntent(app, id, boardId, label))
        }

        try {
            manager.notify(id, builder.build())
        } catch (e: Exception) {
            // 알림 권한이 없으면 조용히 넘어간다 (업로드 결과는 웹 목록에서 확인할 수 있다)
            e.printStackTrace()
        }
    }

    /** 알림을 누르면 앱을 연다. 녹음 번호가 있으면 그 녹음의 화면으로 간다. */
    private fun openIntent(context: Context, id: Int, boardId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        if (boardId > 0) {
            intent.action = MainActivity.ACTION_OPEN_BOARD
            intent.putExtra(MainActivity.EXTRA_BOARD_ID, boardId)
        }
        // 알림마다 요청 번호를 달리해야 서로의 녹음 번호를 덮어쓰지 않는다
        return PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun approveIntent(context: Context, id: Int, boardId: Long, label: String): PendingIntent {
        val intent = Intent(context, ApproveReceiver::class.java)
            .setAction(ApproveReceiver.ACTION_APPROVE)
            .putExtra(ApproveReceiver.EXTRA_BOARD_ID, boardId)
            .putExtra(ApproveReceiver.EXTRA_NOTIFICATION_ID, id)
            .putExtra(ApproveReceiver.EXTRA_LABEL, label)
        return PendingIntent.getBroadcast(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
