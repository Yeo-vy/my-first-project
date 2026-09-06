package com.recorder.voicenote

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri

/**
 * 녹음 파일 재생을 담당하는 간단한 MediaPlayer 래퍼.
 * 한 번에 하나의 파일만 재생한다 (새로 재생을 시작하면 이전 재생은 자동으로 멈춘다).
 */
class PlayerManager {

    private var mediaPlayer: MediaPlayer? = null

    /** [uri]가 있으면 MediaStore 항목으로, 없으면 [filePath]를 직접 파일로 재생한다. */
    fun play(context: Context, uri: Uri?, filePath: String?, onCompletion: () -> Unit): Boolean {
        stop()
        return try {
            val player = MediaPlayer()
            when {
                uri != null -> player.setDataSource(context, uri)
                filePath != null -> player.setDataSource(filePath)
                else -> return false
            }
            player.setOnCompletionListener {
                onCompletion()
                releaseInternal()
            }
            player.setOnErrorListener { _, _, _ ->
                onCompletion()
                releaseInternal()
                true
            }
            player.prepare()
            player.start()
            mediaPlayer = player
            true
        } catch (e: Exception) {
            e.printStackTrace()
            releaseInternal()
            false
        }
    }

    fun stop() {
        releaseInternal()
    }

    private fun releaseInternal() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (_: Exception) {
        }
        mediaPlayer = null
    }
}
