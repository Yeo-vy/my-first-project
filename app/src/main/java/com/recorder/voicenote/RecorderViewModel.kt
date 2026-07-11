package com.recorder.voicenote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Date

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
    private val recorderManager = RecorderManager(application)
    private var currentTarget: RecordingTarget? = null

    private val _uiState = MutableStateFlow(RecorderUiState())
    val uiState: StateFlow<RecorderUiState> = _uiState.asStateFlow()

    /** 저장 위치 안내 문구 (예: "내부 저장소 > Recordings > Voice Recorder") */
    val storageLocationLabel: String get() = store.displayLocation

    private var timerJob: Job? = null

    init {
        refreshFolders()
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
     */
    fun startRecording() {
        val folder = _uiState.value.selectedFolder
        if (folder == null) {
            _uiState.value = _uiState.value.copy(message = "먼저 폴더를 선택해주세요")
            return
        }

        val fileName = store.buildFileName(folder, Date())
        val target = store.prepareRecordingTarget(folder, fileName)
        if (target == null) {
            _uiState.value = _uiState.value.copy(message = "녹음 파일을 준비할 수 없습니다")
            return
        }

        val started = recorderManager.start(target)
        if (!started) {
            store.discardRecording(target)
            _uiState.value = _uiState.value.copy(message = "녹음을 시작할 수 없습니다")
            return
        }

        currentTarget = target
        _uiState.value = _uiState.value.copy(isRecording = true, elapsedSeconds = 0)
        startTimer()
    }

    fun stopRecording() {
        val success = recorderManager.stop()
        stopTimer()

        currentTarget?.let { target ->
            if (success) store.finalizeRecording(target) else store.discardRecording(target)
        }
        currentTarget = null

        _uiState.value = _uiState.value.copy(isRecording = false, elapsedSeconds = 0)
        refreshRecordings()
        refreshFolders()
    }

    fun cancelRecording() {
        recorderManager.cancel()
        stopTimer()
        currentTarget?.let { store.discardRecording(it) }
        currentTarget = null
        _uiState.value = _uiState.value.copy(isRecording = false, elapsedSeconds = 0)
        refreshRecordings()
    }

    fun consumeMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                _uiState.value = _uiState.value.copy(elapsedSeconds = _uiState.value.elapsedSeconds + 1)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    override fun onCleared() {
        super.onCleared()
        if (recorderManager.isRecording) {
            recorderManager.cancel()
            currentTarget?.let { store.discardRecording(it) }
        }
    }
}
