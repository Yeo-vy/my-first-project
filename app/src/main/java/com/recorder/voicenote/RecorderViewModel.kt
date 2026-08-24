package com.recorder.voicenote

import android.app.Application
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 이름 변경 다이얼로그가 어떤 대상을 향한 것인지 나타낸다. */
sealed class RenameTarget {
    data class Folder(val name: String) : RenameTarget()
    data class Recording(val item: RecordingItem) : RenameTarget()
}

/**
 * 다른 앱이 만든 파일 등, 우리 앱이 소유하지 않은 항목을 옮기거나 이름을 바꾸려면
 * Android 11 이상에서는 시스템 승인 다이얼로그(MediaStore.createWriteRequest)가 필요하다.
 * 그 승인을 기다리는 동안의 대기 상태를 나타낸다.
 */
sealed class PendingWriteRequest {
    data class FolderMove(
        val uris: List<Uri>,
        val newRelativePath: String,
        val oldRelativePath: String
    ) : PendingWriteRequest()
    data class RecordingRename(val uri: Uri, val newDisplayName: String) : PendingWriteRequest()
    data class FolderDelete(val uris: List<Uri>, val relativePath: String) : PendingWriteRequest()
    data class RecordingDelete(val uri: Uri, val folderName: String?) : PendingWriteRequest()
}

data class RecorderUiState(
    val folders: List<FolderInfo> = emptyList(),
    // null 이면 폴더 목록(홈) 화면, 값이 있으면 해당 폴더 상세 화면
    val selectedFolder: String? = null,
    val recordings: List<RecordingItem> = emptyList(),
    val isRecording: Boolean = false,
    val elapsedSeconds: Int = 0,
    val showAddFolderDialog: Boolean = false,
    val renameTarget: RenameTarget? = null,
    val deleteFolderTarget: String? = null,
    val deleteRecordingTarget: RecordingItem? = null,
    val showStopConfirm: Boolean = false,
    val pendingWriteRequest: PendingWriteRequest? = null,
    /** 현재 재생 중인 녹음 파일의 이름 (없으면 재생 중이 아님) */
    val playingRecordingName: String? = null,
    val message: String? = null
)

class RecorderViewModel(application: Application) : AndroidViewModel(application) {

    private val store = RecordingStore(application)
    private val playerManager = PlayerManager()

    private val _uiState = MutableStateFlow(RecorderUiState())
    val uiState: StateFlow<RecorderUiState> = _uiState.asStateFlow()

    /** 저장 위치 안내 문구 (예: "내부 저장소 > Recordings > Voice Recorder") */
    val storageLocationLabel: String get() = store.displayLocation

    init {
        // 파일 복사·MediaStore 쿼리가 포함된 초기화 작업은 메인 스레드에서 하면 ANR 위험이 있어 IO로 돌린다.
        viewModelScope.launch(Dispatchers.IO) {
            // 예전 버전에서 앱 전용 저장소에 남아있던 폴더/파일이 있다면 새 위치로 옮겨온다 (최초 1회).
            store.migrateLegacyPrivateStorageIfNeeded()
            // 녹음 도중 프로세스가 죽어 남은(IS_PENDING=1) 항목을 정리한다.
            // 방금 시작한 진짜 녹음과 겹치지 않도록 녹음 중일 때는 건너뛴다.
            if (!RecordingService.state.value.isRecording) {
                store.cleanupPendingRecordings()
            }
            refreshFolders()
        }

        // 실제 녹음은 RecordingService(포그라운드 서비스)가 담당한다.
        // 화면이 꺼지거나 앱이 백그라운드로 가도 서비스가 계속 살아있으므로,
        // 여기서는 서비스가 발행하는 상태를 구독해서 화면에 반영만 한다.
        viewModelScope.launch {
            var wasRecording = false
            RecordingService.state.collect { serviceState ->
                _uiState.value = _uiState.value.copy(
                    isRecording = serviceState.isRecording,
                    elapsedSeconds = serviceState.elapsedSeconds,
                    message = serviceState.errorMessage ?: _uiState.value.message
                )
                if (serviceState.errorMessage != null) {
                    RecordingService.consumeError()
                }
                // 녹음이 막 끝난 시점(true -> false)이면 목록을 새로고침한다.
                if (wasRecording && !serviceState.isRecording) {
                    refreshRecordings()
                    refreshFolders()
                }
                wasRecording = serviceState.isRecording
            }
        }
    }

    private fun refreshFolders() {
        viewModelScope.launch(Dispatchers.IO) {
            val folders = store.listFolders()
            _uiState.value = _uiState.value.copy(folders = folders)
        }
    }

    private fun refreshRecordings() {
        val folder = _uiState.value.selectedFolder ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val recordings = store.listRecordings(folder)
            // 로드하는 동안 다른 폴더로 이동했다면 결과를 무시한다.
            if (_uiState.value.selectedFolder == folder) {
                _uiState.value = _uiState.value.copy(recordings = recordings)
            }
        }
    }

    /** 폴더를 눌러서 안으로 들어간다 (녹음 파일 목록 표시) */
    fun openFolder(folderName: String) {
        stopPlayback()
        _uiState.value = _uiState.value.copy(selectedFolder = folderName, recordings = emptyList())
        refreshRecordings()
    }

    /** 상세화면에서 뒤로가기 -> 폴더 목록 화면 */
    fun goBackToFolderList() {
        if (_uiState.value.isRecording) return // 녹음 중엔 못 나가게
        stopPlayback()
        refreshFolders()
        _uiState.value = _uiState.value.copy(selectedFolder = null, recordings = emptyList())
    }

    fun openAddFolderDialog() {
        _uiState.value = _uiState.value.copy(showAddFolderDialog = true)
    }

    fun dismissAddFolderDialog() {
        _uiState.value = _uiState.value.copy(showAddFolderDialog = false)
    }

    fun confirmAddFolder(name: String) {
        if (name.isNotBlank()) {
            store.createFolder(name)
            refreshFolders()
        }
        _uiState.value = _uiState.value.copy(showAddFolderDialog = false)
    }

    /**
     * 현재 선택된(들어가 있는) 폴더에 녹음을 시작한다.
     * 폴더에 들어가 있지 않으면 안내 메시지만 띄운다.
     * 실제 녹음은 RecordingService에 위임하므로, 화면을 나가도 녹음이 계속된다.
     */
    fun startRecording() {
        val folder = _uiState.value.selectedFolder
        if (folder == null) {
            _uiState.value = _uiState.value.copy(message = "먼저 폴더를 선택해주세요")
            return
        }
        stopPlayback()

        val context = getApplication<Application>()
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_FOLDER_NAME, folder)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    /** 녹음 정지 버튼을 누르면 바로 멈추지 않고 확인부터 받는다. */
    fun requestStopRecording() {
        _uiState.value = _uiState.value.copy(showStopConfirm = true)
    }

    fun dismissStopConfirm() {
        _uiState.value = _uiState.value.copy(showStopConfirm = false)
    }

    fun confirmStopRecording() {
        _uiState.value = _uiState.value.copy(showStopConfirm = false)
        stopRecording()
    }

    fun stopRecording() {
        val context = getApplication<Application>()
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun cancelRecording() {
        val context = getApplication<Application>()
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_CANCEL
        }
        context.startService(intent)
    }

    fun consumeMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    // ----------------------------------------------------------------------------------
    // 재생
    // ----------------------------------------------------------------------------------

    /** 녹음 파일을 누르면 바로 재생한다. 재생 중인 파일을 다시 누르면 정지한다. */
    fun onRecordingClick(item: RecordingItem) {
        val context = getApplication<Application>()
        if (_uiState.value.playingRecordingName == item.displayName) {
            stopPlayback()
            return
        }
        val started = playerManager.play(context, item.contentUri, item.filePath) {
            // 재생이 끝까지 진행되어 자동으로 종료된 경우
            if (_uiState.value.playingRecordingName == item.displayName) {
                _uiState.value = _uiState.value.copy(playingRecordingName = null)
            }
        }
        _uiState.value = if (started) {
            _uiState.value.copy(playingRecordingName = item.displayName)
        } else {
            _uiState.value.copy(message = "재생할 수 없습니다")
        }
    }

    fun stopPlayback() {
        if (_uiState.value.playingRecordingName == null) return
        playerManager.stop()
        _uiState.value = _uiState.value.copy(playingRecordingName = null)
    }

    // ----------------------------------------------------------------------------------
    // 이름 변경 (폴더 / 녹음 파일 길게 누르기)
    // ----------------------------------------------------------------------------------

    fun requestRenameFolder(folderName: String) {
        _uiState.value = _uiState.value.copy(renameTarget = RenameTarget.Folder(folderName))
    }

    fun requestRenameRecording(item: RecordingItem) {
        _uiState.value = _uiState.value.copy(renameTarget = RenameTarget.Recording(item))
    }

    fun dismissRename() {
        _uiState.value = _uiState.value.copy(renameTarget = null)
    }

    fun confirmRename(newName: String) {
        when (val target = _uiState.value.renameTarget) {
            is RenameTarget.Folder -> {
                if (newName.isNotBlank()) {
                    val result = store.renameFolder(target.name, newName)
                    refreshFolders()
                    if (_uiState.value.selectedFolder == target.name) {
                        _uiState.value = _uiState.value.copy(selectedFolder = result.finalName)
                        refreshRecordings()
                    }
                    if (result.pendingUris.isNotEmpty()) {
                        _uiState.value = _uiState.value.copy(
                            pendingWriteRequest = PendingWriteRequest.FolderMove(
                                result.pendingUris, result.newRelativePath, result.oldRelativePath
                            )
                        )
                    }
                }
            }
            is RenameTarget.Recording -> {
                if (newName.isNotBlank()) {
                    when (val result = store.renameRecording(target.item, newName)) {
                        is RenameRecordingResult.Success -> refreshRecordings()
                        is RenameRecordingResult.Failed -> {
                            _uiState.value = _uiState.value.copy(message = "이름을 변경할 수 없습니다")
                        }
                        is RenameRecordingResult.NeedsPermission -> {
                            _uiState.value = _uiState.value.copy(
                                pendingWriteRequest = PendingWriteRequest.RecordingRename(
                                    result.uri, result.newDisplayName
                                )
                            )
                        }
                    }
                }
            }
            null -> Unit
        }
        _uiState.value = _uiState.value.copy(renameTarget = null)
    }

    fun requestDeleteRecording(item: RecordingItem) {
        _uiState.value = _uiState.value.copy(deleteRecordingTarget = item)
    }

    fun dismissDeleteRecording() {
        _uiState.value = _uiState.value.copy(deleteRecordingTarget = null)
    }

    fun confirmDeleteRecording() {
        val item = _uiState.value.deleteRecordingTarget ?: return
        val folder = _uiState.value.selectedFolder
        _uiState.value = _uiState.value.copy(deleteRecordingTarget = null)
        if (_uiState.value.playingRecordingName == item.displayName) {
            stopPlayback()
        }

        when (val result = store.deleteRecording(item)) {
            is DeleteRecordingResult.Success -> {
                // 마지막 파일을 지워서 0개가 되어도 폴더 자체는 앱 목록에 계속 남도록 한다.
                if (folder != null) store.keepFolderRegistered(folder)
                refreshRecordings()
                refreshFolders()
            }
            is DeleteRecordingResult.Failed -> {
                _uiState.value = _uiState.value.copy(message = "삭제할 수 없습니다")
            }
            is DeleteRecordingResult.NeedsPermission -> {
                _uiState.value = _uiState.value.copy(
                    pendingWriteRequest = PendingWriteRequest.RecordingDelete(result.uri, folder)
                )
            }
        }
    }

    fun requestDeleteFolder(folderName: String) {
        _uiState.value = _uiState.value.copy(deleteFolderTarget = folderName)
    }

    fun dismissDeleteFolder() {
        _uiState.value = _uiState.value.copy(deleteFolderTarget = null)
    }

    fun confirmDeleteFolder() {
        val folderName = _uiState.value.deleteFolderTarget ?: return
        _uiState.value = _uiState.value.copy(deleteFolderTarget = null)
        stopPlayback()

        val result = store.deleteFolder(folderName)
        refreshFolders()
        if (_uiState.value.selectedFolder == folderName) {
            _uiState.value = _uiState.value.copy(selectedFolder = null, recordings = emptyList())
        }

        when (result) {
            is DeleteFolderResult.NeedsPermission -> {
                _uiState.value = _uiState.value.copy(
                    pendingWriteRequest = PendingWriteRequest.FolderDelete(result.uris, result.relativePath)
                )
            }
            is DeleteFolderResult.Success -> Unit
        }
    }

    /** MainActivity가 시스템 승인 다이얼로그를 띄울 때 필요한 IntentSender를 요청한다. */
    fun writeRequestIntentSender(): IntentSender? {
        return when (val pending = _uiState.value.pendingWriteRequest) {
            is PendingWriteRequest.FolderMove -> store.createWriteRequestIntentSender(pending.uris)
            is PendingWriteRequest.RecordingRename -> store.createWriteRequestIntentSender(listOf(pending.uri))
            is PendingWriteRequest.FolderDelete -> store.createDeleteRequestIntentSender(pending.uris)
            is PendingWriteRequest.RecordingDelete -> store.createDeleteRequestIntentSender(listOf(pending.uri))
            null -> null
        }
    }

    /** IntentSender를 만들 수 없는 경우(API 30 미만 등) 대기 상태를 정리한다. */
    fun onWriteRequestUnavailable() {
        if (_uiState.value.pendingWriteRequest != null) {
            _uiState.value = _uiState.value.copy(
                message = "일부 항목은 시스템 제한으로 변경하지 못했습니다",
                pendingWriteRequest = null
            )
        }
    }

    /** 시스템 승인 다이얼로그 결과 처리 */
    fun onWriteRequestResult(granted: Boolean) {
        val pending = _uiState.value.pendingWriteRequest
        _uiState.value = _uiState.value.copy(pendingWriteRequest = null)
        if (pending == null) return

        if (granted) {
            when (pending) {
                is PendingWriteRequest.FolderMove ->
                    store.applyPendingFolderMove(pending.uris, pending.newRelativePath, pending.oldRelativePath)
                is PendingWriteRequest.RecordingRename ->
                    store.applyPendingRename(pending.uri, pending.newDisplayName)
                is PendingWriteRequest.FolderDelete ->
                    store.applyPendingFolderDelete(pending.uris, pending.relativePath)
                is PendingWriteRequest.RecordingDelete -> {
                    store.applyPendingRecordingDelete(pending.uri)
                    if (pending.folderName != null) store.keepFolderRegistered(pending.folderName)
                }
            }
            refreshFolders()
            refreshRecordings()
        } else {
            _uiState.value = _uiState.value.copy(message = "권한이 없어 일부 항목을 변경하지 못했습니다")
        }
    }

    /** 저장소 읽기 권한이 새로 승인되었을 때 등, 목록을 다시 불러와야 할 때 호출한다. */
    fun refresh() {
        if (_uiState.value.selectedFolder != null) {
            refreshRecordings()
        }
        refreshFolders()
    }

    override fun onCleared() {
        super.onCleared()
        playerManager.stop()
    }
}
