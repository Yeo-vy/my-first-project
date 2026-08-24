package com.recorder.voicenote

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
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
    val sizeBytes: Long,
    /** Android 10 이상(MediaStore) 저장 항목의 재생/수정용 Uri */
    val contentUri: Uri? = null,
    /** Android 9 이하(직접 파일) 저장 항목의 재생/수정용 경로 */
    val filePath: String? = null
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

/** 폴더 이름 변경 결과. 다른 앱이 만든 파일 등 권한이 없어 못 옮긴 항목은 pendingUris로 알려준다. */
data class RenameFolderResult(
    val finalName: String,
    val pendingUris: List<Uri>,
    val newRelativePath: String,
    val oldRelativePath: String
)

/** 녹음 파일 이름 변경 결과 */
sealed class RenameRecordingResult {
    object Success : RenameRecordingResult()
    object Failed : RenameRecordingResult()
    /** 다른 앱이 만든 파일이라 시스템 승인이 별도로 필요한 경우 */
    data class NeedsPermission(val uri: Uri, val newDisplayName: String) : RenameRecordingResult()
}

/** 폴더 삭제 결과 */
sealed class DeleteFolderResult {
    data class Success(val deletedCount: Int) : DeleteFolderResult()
    /** 다른 앱이 만든 파일이 섞여 있어 삭제 승인이 별도로 필요한 경우 */
    data class NeedsPermission(val uris: List<Uri>, val relativePath: String) : DeleteFolderResult()
}

/** 녹음 파일 하나 삭제 결과 */
sealed class DeleteRecordingResult {
    object Success : DeleteRecordingResult()
    object Failed : DeleteRecordingResult()
    /** 다른 앱이 만든 파일이라 시스템 승인이 별도로 필요한 경우 */
    data class NeedsPermission(val uri: Uri) : DeleteRecordingResult()
}

/**
 * "내부 저장소 > Recordings > Voice Recorder > [폴더명]" 위치에 녹음 파일을 저장/조회한다.
 *
 * - Android 10(API 29) 이상: MediaStore(Scoped Storage)를 통해 공용 Recordings 폴더에 저장.
 *   빈 폴더는 파일 시스템에 실체가 없으므로 SharedPreferences에 폴더 이름을 별도로 기록해 관리한다.
 * - Android 9(API 28) 이하: 공용 저장소에 실제 디렉터리/파일로 직접 저장 (WRITE_EXTERNAL_STORAGE 필요).
 *
 * "Voice Recorder" 폴더 바로 아래(하위 폴더 없이)에 있는 파일들은 이름이 [ROOT_FOLDER_NAME]인
 * 가상의 폴더로 취급해서 목록에 노출한다. (예: 일부 제조사 기본 녹음 앱은 하위 폴더 없이 이 위치에 바로 저장한다)
 */
class RecordingStore(private val context: Context) {

    companion object {
        private const val ROOT_FOLDER_NAME = "Voice Recorder"
        private const val PREFS_NAME = "voice_recorder_folders"
        private const val KEY_FOLDER_NAMES = "folder_names"
        private const val KEY_MIGRATED_LEGACY_PRIVATE = "migrated_legacy_private_v1"
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

    /** [folderName]에 해당하는 MediaStore RELATIVE_PATH. 최상위 가상 폴더는 basePath 자체를 가리킨다. */
    private fun relativePathFor(folderName: String): String {
        return if (folderName == ROOT_FOLDER_NAME) basePath else "$basePath$folderName/"
    }

    /** [folderName]에 해당하는 실제 레거시(API 28 이하) 디렉터리. */
    private fun legacyDirFor(folderName: String): File {
        return if (folderName == ROOT_FOLDER_NAME) {
            legacyRootDir
        } else {
            File(legacyRootDir, folderName).apply { if (!exists()) mkdirs() }
        }
    }

    // ----------------------------------------------------------------------------------
    // 폴더 목록
    // ----------------------------------------------------------------------------------

    fun listFolders(): List<FolderInfo> {
        val counts = queryRecordingCountsByPath()
        fun countFor(folderName: String): Int = counts[relativePathFor(folderName)] ?: 0
        val names = if (isScopedStorage) {
            val registered = prefs.getStringSet(KEY_FOLDER_NAMES, emptySet()) ?: emptySet()
            (registered + queryFolderNamesFromMediaStore() + queryPhysicalSubfolderNames())
                .toSortedSet(String.CASE_INSENSITIVE_ORDER)
                .toList()
        } else {
            val subDirs = legacyRootDir.listFiles { f -> f.isDirectory }?.map { it.name } ?: emptyList()
            val hasRootLevelFiles = legacyRootDir.listFiles { f -> f.isFile }?.isNotEmpty() == true
            val all = if (hasRootLevelFiles) subDirs + ROOT_FOLDER_NAME else subDirs
            all.distinct().sortedBy { it.lowercase(Locale.getDefault()) }
        }
        return names.map { FolderInfo(it, countFor(it)) }
    }

    /** 폴더별 녹음 개수를 한 번의 조회로 센다. (폴더마다 목록 쿼리를 다시 돌리는 N+1 방지) */
    private fun queryRecordingCountsByPath(): Map<String, Int> {
        if (!isScopedStorage) {
            val counts = mutableMapOf<String, Int>()
            counts[relativePathFor(ROOT_FOLDER_NAME)] =
                legacyRootDir.listFiles { f -> f.isFile }?.size ?: 0
            legacyRootDir.listFiles { f -> f.isDirectory }?.forEach { dir ->
                counts["${basePath}${dir.name}/"] = dir.listFiles { f -> f.isFile }?.size ?: 0
            }
            return counts
        }

        val counts = mutableMapOf<String, Int>()
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
                counts[relativePath] = (counts[relativePath] ?: 0) + 1
            }
        }
        return counts
    }

    fun createFolder(name: String): String {
        val safeBase = sanitize(name).ifBlank { "새 폴더" }
        val existing = listFolders().map { it.name }.toSet()
        val candidate = uniqueName(safeBase, existing)

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
                when {
                    relativePath == basePath -> {
                        // Voice Recorder 폴더 바로 아래(하위 폴더 없이) 있는 파일 -> 가상 최상위 폴더
                        names.add(ROOT_FOLDER_NAME)
                    }
                    relativePath.startsWith(basePath) -> {
                        val remainder = relativePath.removePrefix(basePath).trim('/')
                        val folderName = remainder.substringBefore('/')
                        if (folderName.isNotBlank()) names.add(folderName)
                    }
                }
            }
        }
        return names
    }

    /** [relativePath]에 대응하는 실제 파일시스템 경로 (파일탐색기가 보는 것과 동일한 위치). */
    private fun physicalDirFor(relativePath: String): File {
        return File(Environment.getExternalStorageDirectory(), relativePath.trimEnd('/'))
    }

    /**
     * MediaStore 색인과 별개로, 파일탐색기 등에서 직접 만들거나 옮긴 실제 하위 디렉터리도
     * 폴더 목록에 잡히도록 파일시스템을 직접 확인한다. (MediaStore만 보면, 폴더 안 파일을
     * 전부 밖으로 빼냈을 때 실제로는 폴더가 남아있는데도 앱에서는 사라져 보이는 문제가 있었다)
     */
    private fun queryPhysicalSubfolderNames(): Set<String> {
        return try {
            physicalDirFor(basePath).listFiles { f -> f.isDirectory }
                ?.map { it.name }
                ?.toSet()
                ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    // ----------------------------------------------------------------------------------
    // 녹음 파일 목록
    // ----------------------------------------------------------------------------------

    fun listRecordings(folderName: String): List<RecordingItem> {
        return if (isScopedStorage) {
            val items = mutableListOf<RecordingItem>()
            val relativePath = relativePathFor(folderName)
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
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
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    items.add(
                        RecordingItem(
                            displayName = cursor.getString(nameCol) ?: "",
                            dateAddedMillis = cursor.getLong(dateCol) * 1000L,
                            sizeBytes = cursor.getLong(sizeCol),
                            contentUri = ContentUris.withAppendedId(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                            )
                        )
                    )
                }
            }

            // MediaStore가 아직 색인하지 못한 파일(파일탐색기로 방금 옮기거나 복사해 넣은 파일 등)도
            // 최대한 함께 보여주고, 다음부터는 정식으로 색인되도록 미디어 스캔을 걸어준다.
            try {
                val knownNames = items.map { it.displayName }.toSet()
                physicalDirFor(relativePath).listFiles { f -> f.isFile }?.forEach { file ->
                    if (file.name !in knownNames) {
                        items.add(
                            RecordingItem(
                                displayName = file.name,
                                dateAddedMillis = file.lastModified(),
                                sizeBytes = file.length(),
                                filePath = file.absolutePath
                            )
                        )
                        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
                    }
                }
            } catch (_: Exception) {
            }

            items.sortedByDescending { it.dateAddedMillis }
        } else {
            val dir = legacyDirFor(folderName)
            dir.listFiles { f -> f.isFile }
                ?.sortedByDescending { it.lastModified() }
                ?.map { RecordingItem(it.name, it.lastModified(), it.length(), filePath = it.absolutePath) }
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
                put(MediaStore.Audio.Media.RELATIVE_PATH, relativePathFor(folderName))
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
            val dir = legacyDirFor(folderName)
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

    private fun uniqueName(base: String, existingNames: Set<String>): String {
        var candidate = base
        var index = 1
        while (candidate in existingNames) {
            candidate = "$base(${index})"
            index++
        }
        return candidate
    }

    // ----------------------------------------------------------------------------------
    // 이름 변경
    // ----------------------------------------------------------------------------------

    /**
     * 폴더 이름을 변경한다. 우리 앱이 소유한 파일은 즉시 옮겨지고,
     * 다른 앱이 만들어서 권한이 없는 파일은 [RenameFolderResult.pendingUris]로 반환된다.
     * (Android 11 이상에서는 createWriteRequestIntentSender로 사용자 승인을 받은 뒤
     * applyPendingRelativePathUpdate를 호출해 마저 처리해야 한다.)
     */
    fun renameFolder(oldName: String, newName: String): RenameFolderResult {
        val safeBase = sanitize(newName).ifBlank {
            return RenameFolderResult(oldName, emptyList(), relativePathFor(oldName), relativePathFor(oldName))
        }
        if (safeBase == oldName) {
            return RenameFolderResult(oldName, emptyList(), relativePathFor(oldName), relativePathFor(oldName))
        }

        val existing = listFolders().map { it.name }.toSet() - oldName
        val finalName = uniqueName(safeBase, existing)
        val newRelativePath = relativePathFor(finalName)

        if (!isScopedStorage) {
            val oldDir = legacyDirFor(oldName)
            val newDir = File(legacyRootDir, finalName)
            if (oldName == ROOT_FOLDER_NAME) {
                // 최상위에 흩어진 파일들을 새 하위 폴더로 옮긴다.
                newDir.mkdirs()
                oldDir.listFiles { f -> f.isFile }?.forEach { file ->
                    file.renameTo(File(newDir, file.name))
                }
            } else {
                oldDir.renameTo(newDir)
            }
            return RenameFolderResult(finalName, emptyList(), newRelativePath, relativePathFor(oldName))
        }

        // 빈 폴더로 등록되어 있었다면 등록된 이름도 같이 갱신
        val set = (prefs.getStringSet(KEY_FOLDER_NAMES, emptySet()) ?: emptySet()).toMutableSet()
        if (set.remove(oldName)) {
            set.add(finalName)
            prefs.edit().putStringSet(KEY_FOLDER_NAMES, set).apply()
        }

        // 폴더 안에 실제 파일이 있다면 각 파일의 RELATIVE_PATH를 새 폴더 경로로 갱신
        val oldRelativePath = relativePathFor(oldName)
        val pendingUris = mutableListOf<Uri>()
        val projection = arrayOf(MediaStore.Audio.Media._ID)
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} = ?"
        val args = arrayOf(oldRelativePath)
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection, selection, args, null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            while (cursor.moveToNext()) {
                val uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idCol)
                )
                // 실제 권한 문제가 있을 때만 승인 대상에 넣는다. (중복 이름 등 일반 실패는
                // 승인 다이얼로그를 띄워도 어차피 다시 실패하므로 제외)
                if (updateMediaItem(uri, MediaStore.Audio.Media.RELATIVE_PATH, newRelativePath)
                    == MediaUpdateOutcome.NeedsPermission
                ) {
                    pendingUris.add(uri)
                }
            }
        }

        // 모든 파일이 옮겨져서 예전 디렉터리가 비었다면, 파일탐색기에 남지 않도록 실제로 삭제한다.
        deleteDirectoryIfEmpty(oldRelativePath)

        return RenameFolderResult(finalName, pendingUris, newRelativePath, oldRelativePath)
    }

    /** 녹음 파일 이름을 변경한다(확장자는 유지). */
    fun renameRecording(item: RecordingItem, newBaseName: String): RenameRecordingResult {
        val safeBase = sanitize(newBaseName)
        if (safeBase.isBlank()) return RenameRecordingResult.Failed
        val extension = item.displayName.substringAfterLast('.', "")
        val newDisplayName = if (extension.isNotEmpty()) "$safeBase.$extension" else safeBase
        if (newDisplayName == item.displayName) return RenameRecordingResult.Success

        return if (isScopedStorage) {
            val uri = item.contentUri ?: return RenameRecordingResult.Failed
            when (updateMediaItem(uri, MediaStore.Audio.Media.DISPLAY_NAME, newDisplayName)) {
                MediaUpdateOutcome.Success -> RenameRecordingResult.Success
                // 실제 권한 문제(다른 앱 소유 등)일 때만 승인 다이얼로그로 연결한다.
                MediaUpdateOutcome.NeedsPermission ->
                    RenameRecordingResult.NeedsPermission(uri, newDisplayName)
                MediaUpdateOutcome.Failed -> RenameRecordingResult.Failed
            }
        } else {
            val path = item.filePath ?: return RenameRecordingResult.Failed
            val oldFile = File(path)
            val newFile = File(oldFile.parentFile, newDisplayName)
            if (oldFile.renameTo(newFile)) RenameRecordingResult.Success else RenameRecordingResult.Failed
        }
    }

    /**
     * 녹음 파일 하나를 삭제한다. 우리 앱이 소유한 파일은 즉시 삭제되고,
     * 다른 앱이 만들어서 권한이 없는 파일은 [DeleteRecordingResult.NeedsPermission]으로 반환된다.
     */
    fun deleteRecording(item: RecordingItem): DeleteRecordingResult {
        return if (isScopedStorage) {
            val uri = item.contentUri ?: return DeleteRecordingResult.Failed
            try {
                val deleted = context.contentResolver.delete(uri, null, null) > 0
                if (deleted) DeleteRecordingResult.Success else DeleteRecordingResult.Failed
            } catch (e: SecurityException) {
                DeleteRecordingResult.NeedsPermission(uri)
            } catch (e: Exception) {
                DeleteRecordingResult.Failed
            }
        } else {
            val path = item.filePath ?: return DeleteRecordingResult.Failed
            if (File(path).delete()) DeleteRecordingResult.Success else DeleteRecordingResult.Failed
        }
    }

    /** 사용자가 삭제 승인 다이얼로그에서 허용한 뒤, 녹음 파일 삭제를 마저 적용한다. */
    fun applyPendingRecordingDelete(uri: Uri) {
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
        }
    }

    /**
     * 폴더를 통째로 삭제한다 (안의 녹음 파일 전부 포함).
     * 우리 앱이 소유한 파일은 즉시 삭제되고, 다른 앱이 만들어서 권한이 없는 파일은
     * [DeleteFolderResult.NeedsPermission]으로 반환된다.
     * (최상위 가상 폴더["Voice Recorder"]를 삭제하면 그 안의 파일들만 지워지고,
     * 앱의 저장 위치 자체는 남아있는다.)
     */
    fun deleteFolder(folderName: String): DeleteFolderResult {
        if (!isScopedStorage) {
            val dir = legacyDirFor(folderName)
            val files = dir.listFiles { f -> f.isFile } ?: emptyArray()
            var deletedCount = 0
            files.forEach { if (it.delete()) deletedCount++ }
            if (folderName != ROOT_FOLDER_NAME) {
                dir.deleteRecursively()
            }
            return DeleteFolderResult.Success(deletedCount)
        }

        // 빈 폴더로 등록되어 있었다면 등록에서도 제거
        val set = (prefs.getStringSet(KEY_FOLDER_NAMES, emptySet()) ?: emptySet()).toMutableSet()
        if (set.remove(folderName)) {
            prefs.edit().putStringSet(KEY_FOLDER_NAMES, set).apply()
        }

        val relativePath = relativePathFor(folderName)
        val pendingUris = mutableListOf<Uri>()
        var deletedCount = 0
        val projection = arrayOf(MediaStore.Audio.Media._ID)
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} = ?"
        val args = arrayOf(relativePath)
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection, selection, args, null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            while (cursor.moveToNext()) {
                val uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idCol)
                )
                val deleted = try {
                    context.contentResolver.delete(uri, null, null) > 0
                } catch (e: SecurityException) {
                    false
                } catch (e: Exception) {
                    false
                }
                if (deleted) deletedCount++ else pendingUris.add(uri)
            }
        }

        return if (pendingUris.isEmpty()) {
            deleteDirectoryIfEmpty(relativePath)
            DeleteFolderResult.Success(deletedCount)
        } else {
            DeleteFolderResult.NeedsPermission(pendingUris, relativePath)
        }
    }

    /** [uris]에 대한 시스템 삭제 승인 요청(IntentSender)을 만든다. Android 11 미만이면 null. */
    fun createDeleteRequestIntentSender(uris: List<Uri>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || uris.isEmpty()) return null
        return try {
            MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
        } catch (e: Exception) {
            null
        }
    }

    /** 사용자가 삭제 승인 다이얼로그에서 허용한 뒤, 남은 항목 삭제를 마저 적용한다. */
    fun applyPendingFolderDelete(uris: List<Uri>, relativePath: String) {
        for (uri in uris) {
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
        }
        deleteDirectoryIfEmpty(relativePath)
    }

    /** [uris]에 대한 시스템 쓰기 승인 요청(IntentSender)을 만든다. Android 11 미만이면 null. */
    fun createWriteRequestIntentSender(uris: List<Uri>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || uris.isEmpty()) return null
        return try {
            MediaStore.createWriteRequest(context.contentResolver, uris).intentSender
        } catch (e: Exception) {
            null
        }
    }

    /** 사용자가 시스템 승인 다이얼로그에서 허용한 뒤, 폴더 이동을 마저 적용한다. */
    fun applyPendingFolderMove(uris: List<Uri>, newRelativePath: String, oldRelativePath: String) {
        val values = ContentValues().apply { put(MediaStore.Audio.Media.RELATIVE_PATH, newRelativePath) }
        for (uri in uris) {
            try {
                context.contentResolver.update(uri, values, null, null)
            } catch (_: Exception) {
            }
        }
        deleteDirectoryIfEmpty(oldRelativePath)
    }

    /** 사용자가 시스템 승인 다이얼로그에서 허용한 뒤, 파일 이름 변경을 마저 적용한다. */
    fun applyPendingRename(uri: Uri, newDisplayName: String): Boolean {
        return tryUpdateDisplayName(uri, newDisplayName)
    }

    /** [updateMediaItem]의 결과. 실패 원인이 사용자 승인으로 풀리는 것인지 구분한다. */
    private enum class MediaUpdateOutcome {
        Success,
        /** 다른 앱 소유 항목 등 — Android 11+ 에서 createWriteRequest로 복구 가능 */
        NeedsPermission,
        /** 중복 이름·잘못된 값 등 승인과 무관한 실패 */
        Failed
    }

    /**
     * MediaStore 항목의 한 컬럼 값을 갱신한다.
     * RecoverableSecurityException(다른 앱 소유 항목)만 승인 필요로 보고하고, 그 외
     * SecurityException은 일반 실패로 구분한다 — 실패 원인을 묻지 않고 무조건 승인
     * 다이얼로그를 띄웠다가 또 실패하던 문제를 막기 위함이다.
     */
    private fun updateMediaItem(uri: Uri, column: String, value: String): MediaUpdateOutcome {
        return try {
            val values = ContentValues().apply { put(column, value) }
            if (context.contentResolver.update(uri, values, null, null) > 0) {
                MediaUpdateOutcome.Success
            } else {
                MediaUpdateOutcome.Failed
            }
        } catch (e: RecoverableSecurityException) {
            MediaUpdateOutcome.NeedsPermission
        } catch (_: SecurityException) {
            MediaUpdateOutcome.Failed
        } catch (_: Exception) {
            MediaUpdateOutcome.Failed
        }
    }

    private fun tryUpdateRelativePath(uri: Uri, newRelativePath: String): Boolean {
        return updateMediaItem(uri, MediaStore.Audio.Media.RELATIVE_PATH, newRelativePath)
            == MediaUpdateOutcome.Success
    }

    private fun tryUpdateDisplayName(uri: Uri, newDisplayName: String): Boolean {
        return updateMediaItem(uri, MediaStore.Audio.Media.DISPLAY_NAME, newDisplayName)
            == MediaUpdateOutcome.Success
    }

    /**
     * MediaStore 상의 RELATIVE_PATH만 바뀌었을 뿐, 실제 파일시스템에는 이제 비어버린
     * 예전 디렉터리가 그대로 남는 경우가 있다 (파일탐색기에는 빈 폴더로 계속 보임).
     * 더 이상 파일이 없는 게 확인되면 실제 디렉터리 자체를 지워서 깔끔하게 정리한다.
     * 우리 앱의 최상위 컨테이너("Recordings/Voice Recorder" 자체)는 절대 지우지 않는다.
     */
    private fun deleteDirectoryIfEmpty(relativePath: String) {
        if (relativePath.isBlank() || relativePath == basePath) return
        try {
            val dir = physicalDirFor(relativePath)
            if (dir.exists() && dir.isDirectory) {
                val remaining = dir.listFiles()
                if (remaining == null || remaining.isEmpty()) {
                    dir.delete()
                }
            }
        } catch (_: Exception) {
            // 일부 기기/버전에서 직접 파일 접근이 제한될 수 있다. 실패해도 앱 동작에는 지장이 없다.
        }
    }

    /**
     * 녹음 도중 프로세스가 죽어 IS_PENDING=1로 남아 버려진 항목을 정리한다.
     * 서비스가 START_NOT_STICKY라 재시작이 안 되고, pending 항목은 목록 쿼리에서도
     * 걸러져 보이지 않으므로 앱 시작 시 우리 소유 항목만 골라 삭제한다.
     * 방금 시작한 진짜 녹음과 겹치지 않도록 녹음 중에는 호출하지 않아야 한다.
     */
    fun cleanupPendingRecordings() {
        if (!isScopedStorage) return
        val selection =
            "${MediaStore.Audio.Media.IS_PENDING} = 1 AND ${MediaStore.Audio.Media.OWNER_PACKAGE_NAME} = ?"
        val args = arrayOf(context.packageName)
        try {
            context.contentResolver.delete(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, selection, args
            )
        } catch (_: Exception) {
            // OWNER_PACKAGE_NAME 을 지원하지 않는 기기 등에서도 앱 동작에는 지장 없도록 무시한다.
        }
    }

    // ----------------------------------------------------------------------------------
    // 이전 버전 마이그레이션
    // ----------------------------------------------------------------------------------

    /**
     * 예전 버전에서 앱 전용 저장소(Android/data/.../files/Recordings)에 저장했던
     * 폴더/녹음파일이 남아있다면, 지금의 공용 저장 위치("내부 저장소 > Recordings > Voice Recorder")로
     * 옮겨온다. 앱 최초 실행 1회만 수행된다.
     */
    fun migrateLegacyPrivateStorageIfNeeded() {
        if (prefs.getBoolean(KEY_MIGRATED_LEGACY_PRIVATE, false)) return

        val legacyPrivateRoot = File(context.getExternalFilesDir(null), "Recordings")
        if (legacyPrivateRoot.exists()) {
            val legacyFolders = legacyPrivateRoot.listFiles { f -> f.isDirectory } ?: emptyArray()
            for (folderDir in legacyFolders) {
                var anyFileMigrated = false
                val legacyFiles = folderDir.listFiles { f -> f.isFile } ?: emptyArray()
                for (sourceFile in legacyFiles) {
                    if (migrateSingleFile(folderDir.name, sourceFile)) {
                        anyFileMigrated = true
                    }
                }
                // 파일이 하나도 없던 빈 폴더라도 목록에 나타나도록 폴더 이름은 등록해둔다.
                if (!anyFileMigrated || isScopedStorage) {
                    keepFolderRegistered(folderDir.name)
                }
            }
            legacyPrivateRoot.deleteRecursively()
        }

        prefs.edit().putBoolean(KEY_MIGRATED_LEGACY_PRIVATE, true).apply()
    }

    /**
     * 폴더가 앱 목록에서 사라지지 않도록 등록해둔다. (예: 폴더 안의 마지막 파일을 지워서
     * 실제 파일이 0개가 되더라도, 사용자가 만든 폴더 자체는 계속 남아있어야 한다)
     */
    fun keepFolderRegistered(name: String) {
        if (!isScopedStorage || name == ROOT_FOLDER_NAME) return
        val updated = (prefs.getStringSet(KEY_FOLDER_NAMES, emptySet()) ?: emptySet()).toMutableSet()
        if (updated.add(name)) {
            prefs.edit().putStringSet(KEY_FOLDER_NAMES, updated).apply()
        }
    }

    /** 예전 파일 하나를 새 저장 위치로 복사한다. 성공하면 true. */
    private fun migrateSingleFile(folderName: String, sourceFile: File): Boolean {
        val target = prepareRecordingTarget(folderName, sourceFile.name) ?: return false
        return try {
            when (target) {
                is RecordingTarget.MediaStoreTarget -> {
                    ParcelFileDescriptor.AutoCloseOutputStream(target.pfd).use { out ->
                        sourceFile.inputStream().use { input -> input.copyTo(out) }
                    }
                    val values = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                    context.contentResolver.update(target.uri, values, null, null)
                }
                is RecordingTarget.FileTarget -> {
                    sourceFile.copyTo(target.file, overwrite = true)
                }
            }
            true
        } catch (e: Exception) {
            discardRecording(target)
            false
        }
    }
}
