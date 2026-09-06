package com.recorder.voicenote

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 앱 전용 저장공간(getExternalFilesDir) 아래에 녹음 폴더 구조를 관리한다.
 * 별도의 저장소 런타임 권한이 필요 없다.
 */
class FolderRepository(context: Context) {

    // 모든 녹음 폴더들의 최상위 루트 디렉토리
    val rootDir: File = File(
        context.getExternalFilesDir(null) ?: context.filesDir,
        "Recordings"
    ).apply { if (!exists()) mkdirs() }

    /** 루트 바로 아래에 있는 폴더 목록 (이름순 정렬) */
    fun listFolders(): List<File> {
        return rootDir.listFiles { f -> f.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    /** 특정 폴더 안에 저장된 녹음 파일 목록 (최신순 정렬) */
    fun listRecordings(folder: File): List<File> {
        return folder.listFiles { f -> f.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** 새 폴더 생성. 중복 이름이면 (1), (2) 등을 붙인다. */
    fun createFolder(name: String): File {
        val safeName = sanitizeName(name).ifBlank { "새 폴더" }
        var candidate = File(rootDir, safeName)
        var index = 1
        while (candidate.exists()) {
            candidate = File(rootDir, "$safeName(${index})")
            index++
        }
        candidate.mkdirs()
        return candidate
    }

    /**
     * 녹음 시작 시점에 저장될 최종 파일 경로를 만든다.
     * 파일명 형식: [가장 하위 폴더 이름]_[녹음시작시간].m4a
     */
    fun createRecordingFile(folder: File, startTime: Date): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(startTime)
        val fileName = "${folder.name}_${timeStamp}.m4a"
        return File(folder, fileName)
    }

    private fun sanitizeName(name: String): String {
        return name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }
}
