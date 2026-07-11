package com.recorder.voicenote

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 이름 변경 다이얼로그가 어떤 대상을 향한 것인지 나타낸다. */
sealed class RenameTarget {
    data class Folder(val name: String) : RenameTarget()
    data class Recording(val item: RecordingItem) : RenameTarget()
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
        // 예전 버전에서 앱 전용 저장소에 남아있던 폴더/파일이 있다면 새 위치로 옮겨온다 (최초 1회).
        store.migrateLegacyPrivateStorageIfNeeded()
        refreshFolders()

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
        _uiState.value = _uiState.value.copy(folders = store.listFolders())
    }

    private fun refreshRecordings() {
        val folder = _uiState.value.selectedFolder ?: return
        _uiState.value = _uiState.value.copy(recordings = store.listRecordings(folder))
    }

    /** 폴더를 눌러서 안으로 들어간다 (녹음 파일 목록 표시) */
    fun openFolder(folderName: String) {
        stopPlayback()
        _uiState.value = _uiState.value.copy(
            selectedFolder = folderName,
            recordings = store.listRecordings(folderName)
        )
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
                    val finalName = store.renameFolder(target.name, newName)
                    refreshFolders()
                    if (_uiState.value.selectedFolder == target.name) {
                        _uiState.value = _uiState.value.copy(
                            selectedFolder = finalName,
                            recordings = store.listRecordings(finalName)
                        )
                    }
                }
            }
            is RenameTarget.Recording -> {
                if (newName.isNotBlank()) {
                    val success = store.renameRecording(target.item, newName)
                    if (!success) {
                        _uiState.value = _uiState.value.copy(message = "이름을 변경할 수 없습니다")
                    }
                    refreshRecordings()
                }
            }
            null -> Unit
        }
        _uiState.value = _uiState.value.copy(renameTarget = null)
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
