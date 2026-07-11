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

data class RecorderUiState(
    val folders: List<FolderInfo> = emptyList(),
    // null 이면 폴더 목록(홈) 화면, 값이 있으면 해당 폴더 상세 화면
    val selectedFolder: String? = null,
    val recordings: List<RecordingItem> = emptyList(),
    val isRecording: Boolean = false,
    val elapsedSeconds: Int = 0,
    val showAddFolderDialog: Boolean = false,
    val message: String? = null
)

class RecorderViewModel(application: Application) : AndroidViewModel(application) {

    private val store = RecordingStore(application)

    private val _uiState = MutableStateFlow(RecorderUiState())
    val uiState: StateFlow<RecorderUiState> = _uiState.asStateFlow()

    /** 저장 위치 안내 문구 (예: "내부 저장소 > Recordings > Voice Recorder") */
    val storageLocationLabel: String get() = store.displayLocation

    init {
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
        _uiState.value = _uiState.value.copy(
            selectedFolder = folderName,
            recordings = store.listRecordings(folderName)
        )
    }

    /** 상세화면에서 뒤로가기 -> 폴더 목록 화면 */
    fun goBackToFolderList() {
        if (_uiState.value.isRecording) return // 녹음 중엔 못 나가게
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
}
