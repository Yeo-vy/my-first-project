package com.recorder.voicenote

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 화면에 보여줄 녹음 파일 정보 */
data class RecordingItem(
    val displayName: String,
    val dateAddedMillis: Long,
    val sizeBytes: Long
)

/** 화면에 보여줄 폴더 정보 (이름 + 안에 든 파일 개수) */
data class FolderInfo(
    val name: String,
    val recordingCount: Int
)

/** 녹음이 실제로 기록될 대상. 스코프드 스토리지 대응을 위해 두 가지 방식을 지원한다. */
sealed class RecordingTarget {
    data class MediaStoreTarget(val uri: Uri, val pfd: ParcelFileDescriptor) : RecordingTarget()
    data class FileTarget(val file: File) : RecordingTarget()
}

/**
 * "내부 저장소 > Recordings > Voice Recorder > [폴더명]" 위치에 녹음 파일을 저장/조회한다.
 *
 * - Android 10(API 29) 이상: MediaStore(Scoped Storage)를 통해 공용 Recordings 폴더에 저장.
 *   빈 폴더는 파일 시스템에 실체가 없으므로 SharedPreferences에 폴더 이름을 별도로 기록해 관리한다.
 * - Android 9(API 28) 이하: 공용 저장소에 실제 디렉터리/파일로 직접 저장 (WRITE_EXTERNAL_STORAGE 필요).
 */
class RecordingStore(private val context: Context) {

    companion object {
        private const val ROOT_FOLDER_NAME = "Voice Recorder"
        private const val PREFS_NAME = "voice_recorder_folders"
        private const val KEY_FOLDER_NAMES = "folder_names"
    }

    private val isScopedStorage = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** MediaStore RELATIVE_PATH 기준 경로. 예: "Recordings/Voice Recorder/" */
    private val basePath: String = "${Environment.DIRECTORY_RECORDINGS}/$ROOT_FOLDER_NAME/"

    /** API 28 이하에서 사용할 실제 공용 폴더: 내부 저장소/Recordings/Voice Recorder */
    private val legacyRootDir: File by lazy {
        File(Environment.getExternalStorageDirectory(), "Recordings/$ROOT_FOLDER_NAME").apply {
            if (!exists()) mkdirs()
        }
    }

    /** 사용자에게 보여줄 저장 위치 안내 문구 */
    val displayLocation: String get() = "내부 저장소 > Recordings > $ROOT_FOLDER_NAME"

    // ----------------------------------------------------------------------------------
    // 폴더 목록
    // ----------------------------------------------------------------------------------

    fun listFolders(): List<FolderInfo> {
        val names = if (isScopedStorage) {
            val registered = prefs.getStringSet(KEY_FOLDER_NAMES, emptySet()) ?: emptySet()
            (registered + queryFolderNamesFromMediaStore())
                .toSortedSet(String.CASE_INSENSITIVE_ORDER)
                .toList()
        } else {
            legacyRootDir.listFiles { f -> f.isDirectory }
                ?.map { it.name }
                ?.sortedBy { it.lowercase(Locale.getDefault()) }
                ?: emptyList()
        }
        return names.map { FolderInfo(it, listRecordings(it).size) }
    }

    fun createFolder(name: String): String {
        val safeBase = sanitize(name).ifBlank { "새 폴더" }
        val existing = listFolders().map { it.name }.toSet()
        var candidate = safeBase
        var index = 1
        while (candidate in existing) {
            candidate = "$safeBase(${index})"
            index++
        }

        if (isScopedStorage) {
            val updated = (prefs.getStringSet(KEY_FOLDER_NAMES, emptySet()) ?: emptySet()).toMutableSet()
            updated.add(candidate)
            prefs.edit().putStringSet(KEY_FOLDER_NAMES, updated).apply()
        } else {
            File(legacyRootDir, candidate).mkdirs()
        }
        return candidate
    }

    private fun queryFolderNamesFromMediaStore(): Set<String> {
        val names = mutableSetOf<String>()
        val projection = arrayOf(MediaStore.Audio.Media.RELATIVE_PATH)
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("$basePath%")
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection, selection, args, null
        )?.use { cursor ->
            val col = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val relativePath = cursor.getString(col) ?: continue
                if (relativePath.startsWith(basePath)) {
                    val remainder = relativePath.removePrefix(basePath).trim('/')
                    val folderName = remainder.substringBefore('/')
                    if (folderName.isNotBlank()) names.add(folderName)
                }
            }
        }
        return names
    }

    // ----------------------------------------------------------------------------------
    // 녹음 파일 목록
    // ----------------------------------------------------------------------------------

    fun listRecordings(folderName: String): List<RecordingItem> {
        return if (isScopedStorage) {
            val items = mutableListOf<RecordingItem>()
            val relativePath = "$basePath$folderName/"
            val projection = arrayOf(
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.SIZE
            )
            val selection =
                "${MediaStore.Audio.Media.RELATIVE_PATH} = ? AND ${MediaStore.Audio.Media.IS_PENDING} = 0"
            val args = arrayOf(relativePath)
            val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, args, sortOrder
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                while (cursor.moveToNext()) {
                    items.add(
                        RecordingItem(
                            displayName = cursor.getString(nameCol) ?: "",
                            dateAddedMillis = cursor.getLong(dateCol) * 1000L,
                            sizeBytes = cursor.getLong(sizeCol)
                        )
                    )
                }
            }
            items
        } else {
            val dir = File(legacyRootDir, folderName)
            dir.listFiles { f -> f.isFile }
                ?.sortedByDescending { it.lastModified() }
                ?.map { RecordingItem(it.name, it.lastModified(), it.length()) }
                ?: emptyList()
        }
    }

    // ----------------------------------------------------------------------------------
    // 녹음 시작/종료
    // ----------------------------------------------------------------------------------

    fun buildFileName(folderName: String, startTime: Date): String {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(startTime)
        return "${folderName}_${timeStamp}.m4a"
    }

    /** 녹음을 시작하기 전에 저장될 대상을 준비한다. */
    fun prepareRecordingTarget(folderName: String, fileName: String): RecordingTarget? {
        return if (isScopedStorage) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "$basePath$folderName/")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            val pfd = try {
                context.contentResolver.openFileDescriptor(uri, "w")
            } catch (e: Exception) {
                context.contentResolver.delete(uri, null, null)
                null
            } ?: return null
            RecordingTarget.MediaStoreTarget(uri, pfd)
        } else {
            val dir = File(legacyRootDir, folderName).apply { if (!exists()) mkdirs() }
            RecordingTarget.FileTarget(File(dir, fileName))
        }
    }

    /** 녹음이 정상적으로 끝났을 때 호출한다. (IS_PENDING 해제 / 미디어 스캔) */
    fun finalizeRecording(target: RecordingTarget) {
        when (target) {
            is RecordingTarget.MediaStoreTarget -> {
                try { target.pfd.close() } catch (_: Exception) {}
                val values = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                context.contentResolver.update(target.uri, values, null, null)
            }
            is RecordingTarget.FileTarget -> {
                MediaScannerConnection.scanFile(
                    context, arrayOf(target.file.absolutePath), arrayOf("audio/mp4"), null
                )
            }
        }
    }

    /** 녹음이 취소되었거나 실패했을 때 호출한다. (생성했던 항목 삭제) */
    fun discardRecording(target: RecordingTarget) {
        when (target) {
            is RecordingTarget.MediaStoreTarget -> {
                try { target.pfd.close() } catch (_: Exception) {}
                context.contentResolver.delete(target.uri, null, null)
            }
            is RecordingTarget.FileTarget -> {
                target.file.delete()
            }
        }
    }

    private fun sanitize(name: String): String {
        return name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }
}
