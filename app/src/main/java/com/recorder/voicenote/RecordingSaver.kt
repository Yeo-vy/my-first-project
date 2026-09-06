package com.recorder.voicenote

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 녹음 조각을 하나로 합쳐 공용 저장소에 넣는 마무리 담당.
 *
 * 정지 버튼을 눌렀을 때뿐 아니라, 녹음 중 앱이 죽어 조각만 남은 경우에도 같은 경로로 처리한다
 * ([recoverLeftovers]). 그래서 "3시간 녹음했는데 앱이 죽어서 통째로 날아가는" 일이 없다.
 *
 * 합치기에 실패하더라도 조각을 절대 버리지 않는다. 조각을 하나씩 따로 저장해서라도 소리를 남기고,
 * 그것마저 실패하면 작업 폴더를 그대로 둬서 다음 실행 때 다시 시도한다.
 */
object RecordingSaver {

    sealed class Result {
        /** [splitCount] 가 1보다 크면 합치기에 실패해 조각을 따로 저장한 경우다. */
        data class Saved(val displayName: String, val splitCount: Int) : Result()
        data class Failed(val message: String) : Result()
    }

    suspend fun save(
        context: Context,
        store: RecordingStore,
        session: RecordingSession
    ): Result = withContext(Dispatchers.IO) {
        val segments = session.segments
        if (segments.isEmpty()) {
            // 녹음 버튼을 누르자마자 정지한 경우 등 — 남길 소리가 없다.
            session.delete()
            return@withContext Result.Failed("녹음된 내용이 없습니다")
        }

        when (val merged = AudioMerger.merge(segments, session.mergedFile)) {
            is AudioMerger.Result.Success -> {
                val imported = store.importRecording(session.folderName, session.fileName, merged.output)
                if (imported != null) {
                    enqueueUpload(context, imported, session.folderName)
                    session.delete()
                    Result.Saved(imported.displayName, splitCount = 1)
                } else {
                    // 공용 저장소에 넣지 못했다. 조각을 남겨 두면 다음 실행 때 다시 시도한다.
                    Result.Failed("녹음을 저장 위치에 넣지 못했습니다. 다음 실행 때 다시 시도합니다")
                }
            }

            is AudioMerger.Result.Failed -> saveSeparately(context, store, session, segments, merged.message)
        }
    }

    /**
     * 앱이 죽어 남은 작업 폴더를 정리한다. 앱을 켤 때 (녹음 중이 아닐 때) 한 번 호출한다.
     * @return 복구해서 저장한 파일 이름들
     */
    suspend fun recoverLeftovers(context: Context, store: RecordingStore): List<String> =
        withContext(Dispatchers.IO) {
            val recovered = mutableListOf<String>()
            for (session in RecordingSession.loadAll(context)) {
                when (val result = save(context, store, session)) {
                    is Result.Saved -> recovered.add(result.displayName)
                    is Result.Failed -> Unit   // 조각이 없으면 save() 가 폴더를 정리했다
                }
            }
            recovered
        }

    /** 합치기에 실패했을 때: 조각을 각각 따로 저장해서 소리라도 남긴다. */
    private fun saveSeparately(
        context: Context,
        store: RecordingStore,
        session: RecordingSession,
        segments: List<File>,
        reason: String
    ): Result {
        val baseName = session.fileName.substringBeforeLast('.', session.fileName)
        var savedCount = 0
        var firstName: String? = null

        segments.forEachIndexed { index, segment ->
            val name = "${baseName}_${index + 1}of${segments.size}.m4a"
            val imported = store.importRecording(session.folderName, name, segment)
            if (imported != null) {
                if (firstName == null) firstName = imported.displayName
                enqueueUpload(context, imported, session.folderName)
                savedCount++
            }
        }

        return if (savedCount > 0) {
            session.delete()
            Result.Saved(firstName ?: session.fileName, splitCount = savedCount)
        } else {
            Result.Failed("녹음을 저장하지 못했습니다: $reason")
        }
    }

    /** 자동 업로드가 켜져 있으면 방금 저장한 파일을 daglo 서버로 보낸다. */
    private fun enqueueUpload(context: Context, imported: ImportedRecording, folderName: String) {
        val settings = DagloSettings(context)
        if (!settings.canAutoUpload) return
        UploadWorker.enqueue(
            context = context,
            contentUri = imported.contentUri,
            filePath = imported.filePath,
            displayName = imported.displayName,
            folderName = folderName
        )
    }
}
