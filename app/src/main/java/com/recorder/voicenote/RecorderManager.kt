package com.recorder.voicenote

import android.content.Context
import android.media.MediaRecorder
import android.os.Build

/**
 * MediaRecorder 를 감싸 녹음 한 건을 관리한다.
 *
 * 강의 녹음처럼 몇 시간씩 이어지는 경우를 기준으로 만들었다.
 * - **조각 녹음**: 파일 하나가 [SEGMENT_MAX_BYTES] 에 가까워지면 다음 조각으로 이어 받는다.
 *   MediaRecorder 가 직접 갈아타므로 소리가 끊기지 않는다. 도중에 앱이 죽어도 이미 닫힌
 *   조각들은 온전한 m4a 로 남는다. (정지할 때 [AudioMerger] 로 하나로 합친다)
 * - **일시정지/재개**: 쉬는 시간에 파일을 나누지 않고 멈췄다 이어 갈 수 있다.
 * - **오류 감지**: 인코더가 죽거나 저장공간이 차면 조용히 실패하지 않고 즉시 알린다.
 *   (예전에는 3시간 녹음이 중간에 멈춰도 화면에는 계속 '녹음 중' 으로 보였다)
 * - **입력 레벨**: [maxAmplitude] 로 소리가 실제로 들어오는지 눈으로 확인할 수 있다.
 */
class RecorderManager(private val context: Context) {

    /** 녹음 도중 일어난 일을 서비스에 알린다. 콜백은 MediaRecorder 를 만든 스레드(메인)로 온다. */
    interface Callback {
        /** 다음 조각으로 넘어갔다 (조각 번호는 0부터) */
        fun onSegmentStarted(index: Int)

        /** 더 이상 녹음을 이어갈 수 없는 오류. 서비스는 여기서 녹음을 마무리(저장)해야 한다. */
        fun onError(message: String)
    }

    private var recorder: MediaRecorder? = null
    private var session: RecordingSession? = null
    private var callback: Callback? = null

    /** 지금 쓰고 있는 조각 번호 */
    private var currentSegment = 0

    /** 다음 조각으로 예약해 둔 번호 (예약 전이면 -1) */
    private var reservedSegment = -1

    var isRecording: Boolean = false
        private set

    var isPaused: Boolean = false
        private set

    /**
     * 직전 호출 이후의 최대 입력 레벨(0~32767). 화면의 레벨 표시에 쓴다.
     * 녹음 중이 아니거나 일시정지 상태면 0.
     */
    val maxAmplitude: Int
        get() = if (isRecording && !isPaused) {
            try {
                recorder?.maxAmplitude ?: 0
            } catch (e: Exception) {
                0
            }
        } else {
            0
        }

    /** 녹음을 시작한다. 성공 시 true. */
    fun start(session: RecordingSession, callback: Callback): Boolean {
        if (isRecording) return false

        val first = session.segmentFile(0)
        first.parentFile?.mkdirs()

        return try {
            // 콜백/세션은 준비 단계에서 오류가 나도 알릴 수 있도록 먼저 걸어 둔다.
            this.session = session
            this.callback = callback
            currentSegment = 0
            reservedSegment = -1

            val mr = newRecorder()
            configure(mr)
            mr.setOutputFile(first.absolutePath)
            // 이 크기에 가까워지면 다음 조각을 예약하고, 도달하면 MediaRecorder 가 알아서 갈아탄다.
            mr.setMaxFileSize(SEGMENT_MAX_BYTES)
            mr.setOnInfoListener { _, what, _ -> handleInfo(what) }
            mr.setOnErrorListener { _, what, extra ->
                fail("녹음이 중단되었습니다 (오류 $what/$extra)")
            }
            mr.prepare()
            mr.start()

            recorder = mr
            isRecording = true
            isPaused = false
            true
        } catch (e: Exception) {
            e.printStackTrace()
            releaseInternal()
            false
        }
    }

    /** 일시정지. 같은 파일에 이어서 다시 녹음할 수 있다. */
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

    /** 일시정지 해제 */
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
     * 녹음을 멈추고 마지막 조각을 닫는다.
     * 실패하더라도 앞서 닫힌 조각들은 그대로 쓸 수 있으므로, 결과와 상관없이 조각은 지우지 않는다.
     */
    fun stop(): Boolean {
        if (!isRecording) return false
        val mr = recorder
        return try {
            mr?.stop()
            true
        } catch (e: Exception) {
            // 녹음이 너무 짧으면 stop() 이 예외를 던지고 마지막 조각이 깨질 수 있다.
            e.printStackTrace()
            false
        } finally {
            releaseInternal()
        }
    }

    /** 녹음을 버린다 (파일 정리는 호출한 쪽에서 세션을 지워서 한다). */
    fun cancel() {
        if (!isRecording) return
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        releaseInternal()
    }

    // ------------------------------------------------------------------------------

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    private fun configure(mr: MediaRecorder) {
        mr.setAudioSource(MediaRecorder.AudioSource.MIC)
        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        // 강의 음성 기준 충분한 스펙. 파일이 작아 업로드와 변환이 그만큼 빨라진다.
        mr.setAudioEncodingBitRate(BIT_RATE)
        mr.setAudioSamplingRate(SAMPLE_RATE)
        mr.setAudioChannels(1)
    }

    private fun handleInfo(what: Int) {
        when (what) {
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> reserveNextSegment()
            MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> {
                if (reservedSegment >= 0) {
                    currentSegment = reservedSegment
                    reservedSegment = -1
                }
                callback?.onSegmentStarted(currentSegment)
                // 다음 조각도 미리 예약해 둔다 (한 조각이 끝날 때마다 이어서 갈아타도록)
                reserveNextSegment()
            }
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> {
                // 다음 조각을 걸어 두지 못한 경우에만 여기까지 온다. 이때는 녹음이 멈춘 상태다.
                fail("저장 한도에 도달해 녹음이 멈췄습니다")
            }
        }
    }

    /** 다음에 이어 쓸 조각 파일을 미리 걸어 둔다. (Android 8.0 미만은 이어받기를 지원하지 않는다) */
    private fun reserveNextSegment() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (reservedSegment >= 0) return
        val currentSession = session ?: return
        val next = currentSession.segmentFile(currentSegment + 1)
        try {
            recorder?.setNextOutputFile(next)
            reservedSegment = currentSegment + 1
        } catch (e: Exception) {
            // 예약에 실패해도 지금 조각까지는 정상이다. 한도에 도달하면 그때 오류로 알린다.
            e.printStackTrace()
        }
    }

    /**
     * 더 이상 이어갈 수 없는 오류를 알린다.
     *
     * 여기서 곧바로 release 하면 MediaRecorder 콜백 안에서 자기 자신을 정리하게 되므로,
     * 알리기만 하고 정리는 서비스가 stop() 을 부를 때 한다. (이미 죽은 recorder 의 stop() 은
     * 예외를 던지지만 stop() 안에서 잡고 정리하므로 문제가 없다)
     */
    private fun fail(message: String) {
        isPaused = false
        callback?.onError(message)
    }

    private fun releaseInternal() {
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
        callback = null
        session = null
        isRecording = false
        isPaused = false
        currentSegment = 0
        reservedSegment = -1
    }

    companion object {
        private const val BIT_RATE = 64_000
        private const val SAMPLE_RATE = 16_000

        /**
         * 조각 하나의 최대 크기. 64kbps 기준 8MB 면 약 17분이다.
         * 앱이 죽었을 때 잃는 분량이 이 조각 하나(마지막 조각)뿐이라, 작을수록 안전하고
         * 클수록 합치는 부담이 준다. 강의 한 타임을 기준으로 이 정도가 적당하다.
         */
        private const val SEGMENT_MAX_BYTES = 8L * 1024 * 1024
    }
}
