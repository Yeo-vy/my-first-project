package com.recorder.voicenote

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * 녹음 조각(m4a) 여러 개를 하나의 m4a 로 합친다.
 *
 * 오디오를 다시 인코딩하지 않고 AAC 프레임을 그대로 옮겨 담기만 하므로(re-mux) 빠르고
 * 음질 손실이 없다. 안드로이드 기본 API(MediaExtractor/MediaMuxer)만 쓰기 때문에
 * ffmpeg 같은 외부 라이브러리가 필요 없다.
 *
 * 조각마다 타임스탬프가 0부터 다시 시작하므로, 앞 조각들의 길이를 누적해서 더해 준다.
 * 이 보정을 빼먹으면 합친 파일의 재생 길이가 첫 조각 길이로만 잡힌다.
 */
object AudioMerger {

    /** 합치기 결과. 실패하면 호출한 쪽이 조각을 그대로 살려 두도록 이유를 함께 돌려준다. */
    sealed class Result {
        data class Success(val output: File) : Result()
        data class Failed(val message: String) : Result()
    }

    fun merge(segments: List<File>, output: File): Result {
        val usable = segments.filter { it.isFile && it.length() > 0 }
        if (usable.isEmpty()) return Result.Failed("합칠 녹음 조각이 없습니다")

        // 조각이 하나뿐이면(대부분의 짧은 녹음) 다시 쓸 필요 없이 그대로 옮긴다.
        if (usable.size == 1) {
            return try {
                usable[0].copyTo(output, overwrite = true)
                Result.Success(output)
            } catch (e: Exception) {
                Result.Failed(e.message ?: "녹음 파일을 옮기지 못했습니다")
            }
        }

        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var trackIndex = -1
        var offsetUs = 0L
        var wroteAnySample = false

        return try {
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            // AAC 프레임 하나는 몇 KB 수준이라 이 크기면 넉넉하다. 조각마다 새로 잡지 않고 재사용한다.
            val buffer = ByteBuffer.allocate(BUFFER_BYTES)

            for (segment in usable) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(segment.absolutePath)
                    val audioTrack = findAudioTrack(extractor)
                    if (audioTrack < 0) continue          // 소리가 없는 조각은 건너뛴다
                    extractor.selectTrack(audioTrack)
                    val format = extractor.getTrackFormat(audioTrack)

                    if (trackIndex < 0) {
                        trackIndex = muxer.addTrack(format)
                        muxer.start()
                        muxerStarted = true
                    }
                    val info = MediaCodec.BufferInfo()
                    var lastPtsUs = 0L
                    while (true) {
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        info.offset = 0
                        info.size = size
                        info.presentationTimeUs = offsetUs + extractor.sampleTime
                        // MediaExtractor 의 SAMPLE_FLAG_SYNC 와 MediaCodec 의 BUFFER_FLAG_KEY_FRAME 은
                        // 값이 같다. 오디오는 모든 프레임이 키프레임이라 그대로 넘겨도 된다.
                        info.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME
                        muxer.writeSampleData(trackIndex, buffer, info)
                        wroteAnySample = true
                        lastPtsUs = info.presentationTimeUs
                        extractor.advance()
                    }
                    // 다음 조각은 이 조각이 끝난 시점부터 이어 붙인다.
                    offsetUs = lastPtsUs + frameDurationUs(format)
                } finally {
                    try {
                        extractor.release()
                    } catch (_: Exception) {
                    }
                }
            }

            if (!wroteAnySample) {
                Result.Failed("녹음 조각에서 소리를 읽지 못했습니다")
            } else {
                Result.Success(output)
            }
        } catch (e: Exception) {
            Result.Failed(e.message ?: "녹음 조각을 합치지 못했습니다")
        } finally {
            try {
                if (muxerStarted) muxer?.stop()
            } catch (_: Exception) {
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }

    /** AAC 한 프레임(1024 샘플)의 길이. 조각 사이가 한 프레임만큼 겹치거나 벌어지지 않게 한다. */
    private fun frameDurationUs(format: MediaFormat): Long {
        val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else {
            16000
        }
        if (sampleRate <= 0) return 0L
        return 1_024L * 1_000_000L / sampleRate
    }

    private const val BUFFER_BYTES = 256 * 1024
}
