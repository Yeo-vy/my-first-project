package com.recorder.voicenote

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
    val newRelativePath: String
)

/** 녹음 파일 이름 변경 결과 */
sealed class RenameRecordingResult {
    object Success : RenameRecordingResult()
    object Failed : RenameRecordingResult()
    /** 다른 앱이 만든 파일이라 시스템 승인이 별도로 필요한 경우 */
    data class NeedsPermission(val uri: Uri, val newDisplayName: String) : RenameRecordingResult()
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
        val names = if (isScopedStorage) {
            val registered = prefs.getStringSet(KEY_FOLDER_NAMES, emptySet()) ?: emptySet()
            (registered + queryFolderNamesFromMediaStore())
                .toSortedSet(String.CASE_INSENSITIVE_ORDER)
                .toList()
        } else {
            val subDirs = legacyRootDir.listFiles { f -> f.isDirectory }?.map { it.name } ?: emptyList()
            val hasRootLevelFiles = legacyRootDir.listFiles { f -> f.isFile }?.isNotEmpty() == true
            val all = if (hasRootLevelFiles) subDirs + ROOT_FOLDER_NAME else subDirs
            all.distinct().sortedBy { it.lowercase(Locale.getDefault()) }
        }
        return names.map { FolderInfo(it, listRecordings(it).size) }
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
            items
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
            return RenameFolderResult(oldName, emptyList(), relativePathFor(oldName))
        }
        if (safeBase == oldName) {
            return RenameFolderResult(oldName, emptyList(), relativePathFor(oldName))
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
            return RenameFolderResult(finalName, emptyList(), newRelativePath)
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
                val moved = tryUpdateRelativePath(uri, newRelativePath)
                if (!moved) pendingUris.add(uri)
            }
        }
        return RenameFolderResult(finalName, pendingUris, newRelativePath)
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
            when {
                tryUpdateDisplayName(uri, newDisplayName) -> RenameRecordingResult.Success
                isSecurityRestricted(uri, MediaStore.Audio.Media.DISPLAY_NAME, newDisplayName) ->
                    RenameRecordingResult.NeedsPermission(uri, newDisplayName)
                else -> RenameRecordingResult.Failed
            }
        } else {
            val path = item.filePath ?: return RenameRecordingResult.Failed
            val oldFile = File(path)
            val newFile = File(oldFile.parentFile, newDisplayName)
            if (oldFile.renameTo(newFile)) RenameRecordingResult.Success else RenameRecordingResult.Failed
        }
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
    fun applyPendingFolderMove(uris: List<Uri>, newRelativePath: String) {
        val values = ContentValues().apply { put(MediaStore.Audio.Media.RELATIVE_PATH, newRelativePath) }
        for (uri in uris) {
            try {
                context.contentResolver.update(uri, values, null, null)
            } catch (_: Exception) {
            }
        }
    }

    /** 사용자가 시스템 승인 다이얼로그에서 허용한 뒤, 파일 이름 변경을 마저 적용한다. */
    fun applyPendingRename(uri: Uri, newDisplayName: String): Boolean {
        return tryUpdateDisplayName(uri, newDisplayName)
    }

    private fun tryUpdateRelativePath(uri: Uri, newRelativePath: String): Boolean {
        return try {
            val values = ContentValues().apply { put(MediaStore.Audio.Media.RELATIVE_PATH, newRelativePath) }
            context.contentResolver.update(uri, values, null, null) > 0
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun tryUpdateDisplayName(uri: Uri, newDisplayName: String): Boolean {
        return try {
            val values = ContentValues().apply { put(MediaStore.Audio.Media.DISPLAY_NAME, newDisplayName) }
            context.contentResolver.update(uri, values, null, null) > 0
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    /** 위 업데이트가 실패한 이유가 "다른 앱 소유"로 인한 권한 문제였는지 대략적으로 판단한다. */
    private fun isSecurityRestricted(uri: Uri, column: String, value: String): Boolean {
        // Android 11(R) 이상에서만 createWriteRequest로 복구 가능하다.
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
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
                    registerFolderName(folderDir.name)
                }
            }
            legacyPrivateRoot.deleteRecursively()
        }

        prefs.edit().putBoolean(KEY_MIGRATED_LEGACY_PRIVATE, true).apply()
    }

    private fun registerFolderName(name: String) {
        if (!isScopedStorage) return
        val updated = (prefs.getStringSet(KEY_FOLDER_NAMES, emptySet()) ?: emptySet()).toMutableSet()
        updated.add(name)
        prefs.edit().putStringSet(KEY_FOLDER_NAMES, updated).apply()
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
