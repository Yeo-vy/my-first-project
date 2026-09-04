package com.recorder.voicenote

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * 녹음 한 건의 작업 폴더.
 *
 * 녹음을 공용 저장소(MediaStore)에 곧바로 쓰지 않고, 앱 전용 저장소에 **조각(segment) 파일**로
 * 쌓았다가 정지할 때 하나로 합쳐서 내보낸다. 이렇게 하는 이유:
 *
 * - m4a(MPEG-4)는 정지할 때 파일 끝에 색인(moov)을 쓴다. 3시간짜리 녹음 도중 앱이 죽으면
 *   색인이 없어 통째로 재생 불가가 된다. 조각으로 끊어 두면 이미 닫힌 조각들은 온전하다.
 * - 공용 저장소에는 '완성된 파일'만 만들어지므로, 예전처럼 IS_PENDING 상태의 깨진 항목이
 *   목록에 남거나 다음 실행 때 지워지는 일이 없다.
 *
 * 폴더 이름은 `session_<시작시각>_<임의값>` 이고, 안에 조각 파일과 메타(session.json)가 들어간다.
 * 캐시가 아니라 filesDir 를 쓰는 이유는, 저장공간이 부족할 때 시스템이 캐시를 임의로 비우기 때문이다.
 */
class RecordingSession private constructor(
    val dir: File,
    val folderName: String,
    val fileName: String,
    val startedAtMillis: Long
) {

    /** 지금까지 만들어진 조각 파일들 (녹음된 순서). 비어 있는 조각은 걸러낸다. */
    val segments: List<File>
        get() = dir.listFiles { f -> f.isFile && f.name.startsWith(SEGMENT_PREFIX) }
            ?.filter { it.length() > 0 }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun segmentFile(index: Int): File =
        File(dir, String.format(Locale.US, "%s%04d.m4a", SEGMENT_PREFIX, index))

    /** 조각들을 합쳐서 만들 최종 파일 자리 */
    val mergedFile: File get() = File(dir, MERGED_NAME)

    fun delete() {
        dir.deleteRecursively()
    }

    companion object {
        private const val SEGMENT_PREFIX = "seg_"
        private const val MERGED_NAME = "merged.m4a"
        private const val META_NAME = "session.json"
        private const val SESSIONS_DIR = "recording_sessions"

        private fun rootDir(context: Context): File =
            File(context.filesDir, SESSIONS_DIR).apply { if (!exists()) mkdirs() }

        fun create(context: Context, folderName: String, fileName: String): RecordingSession? {
            val startedAt = System.currentTimeMillis()
            val dir = File(rootDir(context), "session_${startedAt}_${(0..9999).random()}")
            if (!dir.mkdirs()) return null

            val session = RecordingSession(dir, folderName, fileName, startedAt)
            if (!session.writeMeta()) {
                dir.deleteRecursively()
                return null
            }
            return session
        }

        /**
         * 남아 있는 작업 폴더를 모두 읽는다.
         * 녹음 도중 앱이 죽으면 이 목록에 조각이 남아 있고, 다음 실행 때 복구에 쓴다.
         */
        fun loadAll(context: Context): List<RecordingSession> {
            val dirs = rootDir(context).listFiles { f -> f.isDirectory } ?: return emptyList()
            return dirs.sortedBy { it.name }.mapNotNull { load(it) }
        }

        private fun load(dir: File): RecordingSession? {
            return try {
                val json = JSONObject(File(dir, META_NAME).readText())
                RecordingSession(
                    dir = dir,
                    folderName = json.optString("folderName"),
                    fileName = json.optString("fileName"),
                    startedAtMillis = json.optLong("startedAtMillis", dir.lastModified())
                )
            } catch (e: Exception) {
                // 메타를 못 읽으면 복구해도 어느 폴더에 넣을지 알 수 없다. 조각만 남은 껍데기이므로 정리한다.
                dir.deleteRecursively()
                null
            }
        }
    }

    private fun writeMeta(): Boolean {
        return try {
            val json = JSONObject()
                .put("folderName", folderName)
                .put("fileName", fileName)
                .put("startedAtMillis", startedAtMillis)
            File(dir, META_NAME).writeText(json.toString())
            true
        } catch (e: Exception) {
            false
        }
    }
}
