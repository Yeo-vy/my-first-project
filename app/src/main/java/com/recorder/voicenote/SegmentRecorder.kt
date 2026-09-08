package com.recorder.voicenote

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * 마이크를 열어 조각 파일로 녹음한다. (m4a / AAC 16kHz 모노 64kbps — 강의 음성에 충분하다)
 *
 * **왜 조각으로 나누는가**: 3시간짜리 녹음을 파일 하나로 받으면, 도중에 앱이 죽었을 때
 * 헤더가 쓰이지 않아 파일 전체가 재생 불가가 된다. [SEGMENT_MAX_BYTES] 마다 파일을 갈아타면
 * 잃는 것은 마지막 조각(최대 17분)뿐이고, 앞 조각들은 그대로 살아 있다.
 *
 * 갈아타기는 MediaRecorder 가 직접 한다. 크기가 차오르면(`MAX_FILESIZE_APPROACHING`) 다음 파일을
 * 미리 걸어 두고(`setNextOutputFile`), 한도에 닿는 순간 녹음이 끊기지 않은 채 다음 파일로 넘어간다.
 */
class SegmentRecorder(private val context: Context) {

    /** 녹음이 스스로 멈춰 버린 경우에만 부른다 (마이크를 빼앗겼거나 저장 공간이 찼을 때). */
    fun interface ErrorListener {
        fun onError(message: String)
    }

    private var recorder: MediaRecorder? = null
    private var sessionDir: File? = null
    private var currentIndex = 0
    private var reservedIndex = -1
    private var errorListener: ErrorListener? = null

    @Volatile
    var isRecording = false
        private set

    @Volatile
    var isPaused = false
        private set

    /** 마이크 입력 세기 0.0~1.0. 소리가 실제로 들어오는지 화면에서 보여 주는 용도. */
    val level: Float
        get() = try {
            if (!isRecording || isPaused) 0f
            else (recorder?.maxAmplitude ?: 0).coerceIn(0, MAX_AMPLITUDE) / MAX_AMPLITUDE.toFloat()
        } catch (e: Exception) {
            0f
        }

    fun start(sessionDir: File, onError: ErrorListener): Boolean {
        if (isRecording) return false
        sessionDir.mkdirs()

        this.sessionDir = sessionDir
        this.errorListener = onError
        currentIndex = 0
        reservedIndex = -1

        return try {
            val mr = newRecorder()
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(BIT_RATE)
            mr.setAudioSamplingRate(SAMPLE_RATE)
            mr.setAudioChannels(1)
            mr.setOutputFile(segmentFile(sessionDir, 0).absolutePath)
            mr.setMaxFileSize(SEGMENT_MAX_BYTES)
            mr.setOnInfoListener { _, what, _ -> onInfo(what) }
            mr.setOnErrorListener { _, what, extra ->
                errorListener?.onError("녹음이 중단되었습니다 (오류 $what/$extra)")
            }
            mr.prepare()
            mr.start()

            recorder = mr
            isRecording = true
            isPaused = false
            true
        } catch (e: Exception) {
            e.printStackTrace()
            release()
            false
        }
    }

    fun pause(): Boolean {
        if (!isRecording || isPaused) return false
        return try {
            recorder?.pause()
            isPaused = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun resume(): Boolean {
        if (!isRecording || !isPaused) return false
        return try {
            recorder?.resume()
            isPaused = false
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 녹음을 멈춘다. 마지막 조각을 닫는 데 실패해도 앞 조각들은 그대로 쓸 수 있으므로
     * 파일은 지우지 않는다.
     */
    fun stop() {
        if (!isRecording) return
        try {
            recorder?.stop()
        } catch (e: Exception) {
            // 녹음이 너무 짧으면 stop() 이 예외를 던진다. 그 조각만 못 쓰고 나머지는 멀쩡하다.
            e.printStackTrace()
        } finally {
            release()
        }
    }

    private fun onInfo(what: Int) {
        when (what) {
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> reserveNext()
            MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> {
                if (reservedIndex >= 0) {
                    currentIndex = reservedIndex
                    reservedIndex = -1
                }
                // 다음 조각도 미리 걸어 둔다 (조각이 끝날 때마다 끊김 없이 이어지도록)
                reserveNext()
            }
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED ->
                // 다음 조각을 걸어 두지 못했을 때만 여기까지 온다. 이 시점엔 녹음이 멈춰 있다.
                errorListener?.onError("저장 한도에 도달해 녹음이 멈췄습니다")
        }
    }

    /** 다음에 이어 쓸 조각 파일을 미리 걸어 둔다. */
    private fun reserveNext() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (reservedIndex >= 0) return
        val dir = sessionDir ?: return
        try {
            recorder?.setNextOutputFile(segmentFile(dir, currentIndex + 1))
            reservedIndex = currentIndex + 1
        } catch (e: Exception) {
            // 걸어 두지 못해도 지금 조각까지는 정상이다. 한도에 닿으면 그때 오류로 알린다.
            e.printStackTrace()
        }
    }

    private fun release() {
        try {
            recorder?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        recorder = null
        sessionDir = null
        errorListener = null
        isRecording = false
        isPaused = false
        currentIndex = 0
        reservedIndex = -1
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    companion object {
        private const val BIT_RATE = 64_000
        private const val SAMPLE_RATE = 16_000
        /** 조각 하나의 최대 크기. 64kbps 기준 8MB 면 약 17분이다. */
        private const val SEGMENT_MAX_BYTES = 8L * 1024 * 1024
        /** MediaRecorder.maxAmplitude 의 최댓값 (16bit PCM 기준) */
        private const val MAX_AMPLITUDE = 32_767

        fun segmentFile(sessionDir: File, index: Int): File =
            File(sessionDir, "seg_" + index.toString().padStart(4, '0') + ".m4a")

        /** 세션 폴더에 쌓인 조각을 순서대로 돌려준다. */
        fun segmentsOf(sessionDir: File): List<File> =
            (sessionDir.listFiles() ?: emptyArray())
                .filter { it.isFile && it.name.startsWith("seg_") && it.length() > 0 }
                .sortedBy { it.name }
    }
}
