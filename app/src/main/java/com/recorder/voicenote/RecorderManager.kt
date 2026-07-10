package com.recorder.voicenote

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * MediaRecorder를 감싸서 시작/정지만 신경 쓰면 되도록 단순화한 클래스.
 */
class RecorderManager(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    var isRecording: Boolean = false
        private set

    /** 녹음을 시작하고 저장될 파일을 반환한다. 실패 시 null. */
    fun start(targetFile: File): File? {
        if (isRecording) return null

        return try {
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(128000)
            mr.setAudioSamplingRate(44100)
            mr.setOutputFile(targetFile.absolutePath)
            mr.prepare()
            mr.start()

            recorder = mr
            outputFile = targetFile
            isRecording = true
            targetFile
        } catch (e: Exception) {
            e.printStackTrace()
            releaseInternal()
            outputFile?.delete()
            null
        }
    }

    /** 녹음을 멈춘다. 성공하면 저장된 파일 경로, 실패하면 null. */
    fun stop(): File? {
        if (!isRecording) return null
        val savedFile = outputFile
        return try {
            recorder?.apply {
                stop()
                release()
            }
            isRecording = false
            recorder = null
            savedFile
        } catch (e: Exception) {
            e.printStackTrace()
            releaseInternal()
            savedFile?.delete()
            null
        } finally {
            outputFile = null
        }
    }

    /** 녹음 도중 취소(파일 삭제) */
    fun cancel() {
        if (!isRecording) return
        val fileToDelete = outputFile
        releaseInternal()
        fileToDelete?.delete()
        outputFile = null
    }

    private fun releaseInternal() {
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
        isRecording = false
    }
}
