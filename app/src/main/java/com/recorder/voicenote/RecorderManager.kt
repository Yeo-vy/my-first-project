package com.recorder.voicenote

import android.content.Context
import android.media.MediaRecorder
import android.os.Build

/**
 * MediaRecorder를 감싸서 시작/정지만 신경 쓰면 되도록 단순화한 클래스.
 * 실제 저장 대상(MediaStore 또는 일반 파일)은 RecordingTarget으로 외부에서 주입받는다.
 */
class RecorderManager(private val context: Context) {

    private var recorder: MediaRecorder? = null
    var isRecording: Boolean = false
        private set

    /** 녹음을 시작한다. 성공 시 true. */
    fun start(target: RecordingTarget): Boolean {
        if (isRecording) return false

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
            // 강의 음성 기준 충분한 스펙. 파일이 1/4 수준으로 줄어 SFTP 전송과 Gemini 업로드가 빨라진다.
            mr.setAudioEncodingBitRate(64000)
            mr.setAudioSamplingRate(16000)
            mr.setAudioChannels(1)

            when (target) {
                is RecordingTarget.MediaStoreTarget -> mr.setOutputFile(target.pfd.fileDescriptor)
                is RecordingTarget.FileTarget -> mr.setOutputFile(target.file.absolutePath)
            }

            mr.prepare()
            mr.start()

            recorder = mr
            isRecording = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            releaseInternal()
            false
        }
    }

    /** 녹음을 멈춘다. 성공 시 true. */
    fun stop(): Boolean {
        if (!isRecording) return false
        return try {
            recorder?.apply {
                stop()
                release()
            }
            isRecording = false
            recorder = null
            true
        } catch (e: Exception) {
            e.printStackTrace()
            releaseInternal()
            false
        }
    }

    /** 녹음 도중 취소 */
    fun cancel() {
        if (!isRecording) return
        releaseInternal()
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
