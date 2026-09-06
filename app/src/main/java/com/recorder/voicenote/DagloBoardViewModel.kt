package com.recorder.voicenote

import android.app.Application
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 사이드바의 네 가지 기본 보기. 웹의 changeFilter() 와 같은 값을 쓴다. */
enum class DagloFilter(val key: String, val title: String) {
    ALL("all", "전체 보드"),
    STARRED("starred", "중요 보드"),
    PROCESSING("processing", "미완료 / 변환 중 녹음"),
    TRASH("trash", "휴지통")
}

/** 보드챗 말풍선. 서버에 저장된 기록과 지금 흘러들어오는 답변을 같은 모양으로 다룬다. */
data class ChatBubble(val isUser: Boolean, val text: String, val pending: Boolean = false)

/** AI 패널의 탭. 웹의 switchAiTab() 과 같다. */
enum class AiTab(val label: String) {
    CHAT("보드챗"), SUMMARY("기본 요약"), TEMPLATE("템플릿 요약"), BOOKMARKS("북마크")
}

data class DagloUiState(
    val serverConfigured: Boolean = false,
    val serverUrl: String = "",

    // 로그인 (웹과 같은 계정·같은 세션 쿠키)
    val authChecked: Boolean = false,
    val loggedIn: Boolean = false,
    val setupRequired: Boolean = false,
    val user: DagloUser? = null,
    val authBusy: Boolean = false,
    val authError: String? = null,

    // 목록 화면
    val filter: DagloFilter = DagloFilter.ALL,
    val folderId: Int? = null,
    val folderTitle: String? = null,
    val folders: List<DagloFolder> = emptyList(),
    val boards: List<DagloBoard> = emptyList(),
    val search: String = "",
    val selectedIds: Set<Int> = emptySet(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,

    // 상세 화면
    val detail: DagloBoardDetail? = null,
    val segments: List<DagloSegment> = emptyList(),
    val transcriptDirty: Boolean = false,
    val savingTranscript: Boolean = false,
    val keywords: List<String> = emptyList(),
    val keywordsLoading: Boolean = false,
    val scriptQuery: String = "",
    val aiTab: AiTab = AiTab.CHAT,
    val chats: List<ChatBubble> = emptyList(),
    val chatSending: Boolean = false,
    val summaryBasic: String? = null,
    val summaryTemplate: String? = null,
    val summaryLoading: String? = null,
    val bookmarks: List<DagloBookmark> = emptyList(),

    // 재생
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val activeSegmentId: Int? = null,
    /** 자막을 이 시각의 문단으로 데려가라는 1회성 신호 (웹의 scrollToBlockAt) */
    val scrollRequestMs: Long? = null,

    // 단어장 모달
    val glossary: DagloGlossary? = null,
    val glossaryLoading: Boolean = false,

    val toast: String? = null
) {
    val currentTitle: String get() = folderTitle ?: filter.title
    val isTrash: Boolean get() = filter == DagloFilter.TRASH && folderId == null
}

/**
 * daglo 웹 화면(server/static/app.js)이 하던 일을 그대로 하는 화면 상태 보관소.
 *
 * 화면 구성·동작을 웹과 맞추는 것이 목표라, 함수 이름도 웹 쪽과 나란히 뒀다
 * (changeFilter / loadBoards / openBoardDetail / saveTranscriptChanges ...).
 */
class DagloBoardViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = DagloSettings(app)
    private var client = DagloClient(settings)

    private val _state = MutableStateFlow(
        DagloUiState(serverConfigured = settings.isConfigured, serverUrl = settings.serverUrl)
    )
    val state: StateFlow<DagloUiState> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var progressJob: Job? = null
    private var searchJob: Job? = null
    private var chatJob: Job? = null
    private var pollJob: Job? = null

    init {
        startPolling()
    }

    /** 설정 화면에서 주소를 바꾸고 돌아왔을 때 다시 붙는다. */
    fun reloadSettings() {
        client = DagloClient(settings)
        _state.update {
            it.copy(serverConfigured = settings.isConfigured, serverUrl = settings.serverUrl)
        }
        if (settings.isConfigured) checkAuth()
    }

    // ------------------------------------------------------------ 로그인

    /**
     * 저장해 둔 세션이 아직 살아 있는지 서버에 물어본다.
     * 이 경로(`/{LOGIN_PATH}/status`)는 로그인 없이 열려 있어서 서버 주소 확인도 겸한다.
     */
    fun checkAuth() = viewModelScope.launch {
        if (!settings.isConfigured) {
            _state.update { it.copy(authChecked = true, loggedIn = false) }
            return@launch
        }
        _state.update { it.copy(authBusy = true) }
        try {
            val status = client.authStatus()
            val user = status.user ?: if (status.authenticated) runCatching { client.me() }.getOrNull() else null
            _state.update {
                it.copy(
                    authChecked = true,
                    loggedIn = status.authenticated,
                    setupRequired = status.setupRequired,
                    user = user,
                    authBusy = false,
                    authError = null
                )
            }
            if (status.authenticated) {
                loadFolders()
                loadBoards()
            } else {
                settings.clearSession()
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    authChecked = true,
                    loggedIn = false,
                    authBusy = false,
                    authError = (e as? DagloHttpException)?.message ?: e.message
                        ?: "서버에 연결할 수 없습니다"
                )
            }
        }
    }

    fun login(username: String, password: String) = viewModelScope.launch {
        if (username.isBlank() || password.isBlank()) {
            _state.update { it.copy(authError = "아이디와 비밀번호를 입력하세요.") }
            return@launch
        }
        _state.update { it.copy(authBusy = true, authError = null) }
        try {
            applyLogin(client.login(username, password))
        } catch (e: Exception) {
            _state.update {
                it.copy(authBusy = false, authError = (e as? DagloHttpException)?.message
                    ?: e.message ?: "로그인에 실패했습니다")
            }
        }
    }

    /** 서버에 계정이 하나도 없을 때 첫 관리자 계정을 만들고 그대로 로그인한다. */
    fun setupFirstAdmin(username: String, password: String, displayName: String) =
        viewModelScope.launch {
            _state.update { it.copy(authBusy = true, authError = null) }
            try {
                applyLogin(client.setupFirstAdmin(username, password, displayName))
            } catch (e: Exception) {
                _state.update {
                    it.copy(authBusy = false, authError = (e as? DagloHttpException)?.message
                        ?: e.message ?: "계정을 만들지 못했습니다")
                }
            }
        }

    private fun applyLogin(result: DagloLoginResult) {
        settings.sessionCookie = result.sessionCookie
        client = DagloClient(settings)
        _state.update {
            it.copy(
                loggedIn = true,
                setupRequired = false,
                user = result.user,
                authBusy = false,
                authError = null,
                authChecked = true
            )
        }
        loadFolders()
        loadBoards()
    }

    fun logout() = viewModelScope.launch {
        runCatching { client.logout() }   // 서버가 못 받아도 앱에서는 세션을 버린다
        settings.clearSession()
        client = DagloClient(settings)
        _state.update {
            DagloUiState(
                serverConfigured = settings.isConfigured,
                serverUrl = settings.serverUrl,
                authChecked = true,
                loggedIn = false,
                toast = "로그아웃했습니다."
            )
        }
    }

    fun changePassword(currentPassword: String, newPassword: String) = run {
        client.changePassword(currentPassword, newPassword)
        toast("비밀번호를 변경했습니다.")
    }

    fun consumeToast() = _state.update { it.copy(toast = null) }

    fun consumeScrollRequest() = _state.update { it.copy(scrollRequestMs = null) }

    private fun toast(message: String) = _state.update { it.copy(toast = message) }

    private fun fail(e: Throwable) {
        val http = e as? DagloHttpException
        if (http?.code == 401) {
            // 세션이 만료됐다. 웹이 로그인 페이지로 되돌리는 것과 같은 자리다.
            settings.clearSession()
            _state.update {
                it.copy(loggedIn = false, user = null, detail = null, authChecked = true)
            }
        }
        toast(http?.message ?: e.message ?: "요청에 실패했습니다")
    }

    private fun run(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: Exception) {
            fail(e)
        }
    }

    // ------------------------------------------------------------ 폴더 / 필터

    fun loadFolders() = run {
        _state.update { it.copy(folders = client.getFolders()) }
    }

    fun changeFilter(filter: DagloFilter) {
        exitDetail()
        _state.update {
            it.copy(filter = filter, folderId = null, folderTitle = null, selectedIds = emptySet())
        }
        loadBoards()
    }

    fun selectFolder(folder: DagloFolder) {
        exitDetail()
        _state.update {
            it.copy(folderId = folder.id, folderTitle = folder.name, selectedIds = emptySet())
        }
        loadBoards()
    }

    /** 로고를 누르면 검색어·필터를 처음 상태로 되돌린다 (웹의 goHome). */
    fun goHome() {
        _state.update { it.copy(search = "") }
        loadFolders()
        changeFilter(DagloFilter.ALL)
    }

    fun createFolder(name: String) = run {
        client.createFolder(name.trim())
        toast("새 폴더가 생성되었습니다.")
        loadFolders()
    }

    fun deleteFolder(folder: DagloFolder, withBoards: Boolean) = run {
        val result = client.deleteFolder(folder.id, withBoards)
        var message = "폴더를 삭제했습니다."
        val trashed = result.optInt("trashed_boards", 0)
        val moved = result.optInt("moved_boards", 0)
        if (trashed > 0) message = "폴더를 삭제하고 보드 ${trashed}개를 휴지통으로 옮겼습니다."
        else if (moved > 0) message = "폴더를 삭제하고 보드 ${moved}개를 기본 폴더로 옮겼습니다."
        val leftover = result.optInt("leftover_files", 0)
        if (leftover > 0) message += " (서버가 모르는 파일 ${leftover}개가 남아 탐색기 폴더는 두었습니다)"
        toast(message)

        if (_state.value.folderId == folder.id) changeFilter(DagloFilter.ALL) else loadBoards()
        loadFolders()
    }

    // ------------------------------------------------------------ 보드 목록

    fun loadBoards(showSpinner: Boolean = true) = run {
        val s = _state.value
        if (!s.serverConfigured) return@run
        if (showSpinner) _state.update { it.copy(loading = true) }
        try {
            val boards = client.getBoards(
                folderId = s.folderId,
                filterType = if (s.folderId == null) s.filter.key else null,
                search = s.search
            )
            _state.update { it.copy(boards = boards, loading = false) }
        } finally {
            _state.update { it.copy(loading = false) }
        }
    }

    /** 입력할 때마다 서버를 때리지 않도록 잠깐 기다렸다 검색한다. */
    fun onSearchChange(query: String) {
        _state.update { it.copy(search = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            loadBoards(showSpinner = false)
        }
    }

    fun refreshBoards() = run {
        if (_state.value.refreshing) return@run
        _state.update { it.copy(refreshing = true) }
        var result: org.json.JSONObject? = null
        try {
            result = client.refreshBoards()
        } catch (e: Exception) {
            // 폴더 검사에 실패해도 목록만이라도 다시 읽는다 (웹과 같은 동작)
        }
        try {
            loadFolders()
            loadBoards(showSpinner = false)
        } finally {
            _state.update { it.copy(refreshing = false) }
        }
        val added = result?.optInt("added", 0) ?: 0
        toast(
            when {
                result == null -> "목록만 다시 읽었습니다. (폴더 검사에 실패했습니다)"
                added > 0 -> "새 녹음 ${added}개를 찾았습니다. 변환을 시작합니다."
                !result.optBoolean("scanned", true) -> "폴더를 검사하는 중입니다. 잠시 뒤 목록에 반영됩니다."
                else -> "목록을 최신 상태로 맞췄습니다."
            }
        )
    }

    fun toggleSelect(boardId: Int, checked: Boolean) = _state.update {
        val next = it.selectedIds.toMutableSet()
        if (checked) next.add(boardId) else next.remove(boardId)
        it.copy(selectedIds = next)
    }

    fun toggleSelectAll(checked: Boolean) = _state.update {
        it.copy(selectedIds = if (checked) it.boards.map { b -> b.id }.toSet() else emptySet())
    }

    fun toggleStar(boardId: Int) = run {
        client.toggleStar(boardId)
        loadBoards(showSpinner = false)
        val detail = _state.value.detail
        if (detail?.id == boardId) {
            val starred = !detail.isStarred
            _state.update { it.copy(detail = detail.copy(isStarred = starred)) }
            toast(if (starred) "중요 보드로 설정되었습니다." else "중요 보드가 해제되었습니다.")
        }
    }

    fun deleteBoard(boardId: Int) = run {
        client.deleteBoard(boardId)
        toast("휴지통으로 이동되었습니다. (원본 파일도 함께 이동)")
        if (_state.value.detail?.id == boardId) exitDetail()
        loadBoards(showSpinner = false)
        loadFolders()
    }

    fun deleteBoardPermanent(boardId: Int) = run {
        client.deleteBoard(boardId, permanent = true)
        toast("원본 파일까지 영구 삭제되었습니다.")
        loadBoards(showSpinner = false)
    }

    fun restoreBoard(boardId: Int) = run {
        client.restoreBoard(boardId)
        toast("보드가 복원되었습니다.")
        loadBoards(showSpinner = false)
        loadFolders()
    }

    fun batchDelete() = run {
        val s = _state.value
        if (s.selectedIds.isEmpty()) return@run
        client.batchDelete(s.selectedIds, permanent = s.isTrash)
        _state.update { it.copy(selectedIds = emptySet()) }
        toast(
            if (s.isTrash) "원본 파일까지 영구 삭제되었습니다."
            else "휴지통으로 이동되었습니다. (원본 파일도 함께 이동)"
        )
        loadBoards(showSpinner = false)
        loadFolders()
    }

    fun batchMove(folderId: Int) = run {
        val ids = _state.value.selectedIds
        if (ids.isEmpty()) return@run
        client.batchMove(ids, folderId)
        _state.update { it.copy(selectedIds = emptySet()) }
        toast("폴더가 이동되었습니다.")
        loadBoards(showSpinner = false)
        loadFolders()
    }

    /** 실패한 변환 재시도 / 다시 받아쓰기. 확인 절차는 화면에서 거친다. */
    fun reprocessBoard(boardId: Int, message: String) = run {
        client.reprocessBoard(boardId)
        toast(message)
        val detail = _state.value.detail
        if (detail?.id == boardId) {
            _state.update {
                it.copy(
                    detail = detail.copy(status = "PENDING", progressPercent = 0, errorMessage = null)
                )
            }
        }
        loadBoards(showSpinner = false)
    }

    // ------------------------------------------------------------ 보드 상세

    fun openBoardDetail(boardId: Int) = run {
        val detail = client.getBoardDetail(boardId)
        _state.update {
            it.copy(
                detail = detail,
                segments = detail.segments,
                transcriptDirty = false,
                keywords = detail.keywords,
                bookmarks = detail.bookmarks,
                summaryBasic = detail.summaryOf("BASIC"),
                summaryTemplate = null,
                aiTab = AiTab.CHAT,
                scriptQuery = "",
                chats = emptyList(),
                activeSegmentId = null,
                positionMs = 0,
                durationMs = (detail.durationSeconds * 1000).toLong()
            )
        }
        preparePlayer(detail)
        loadChatHistory(boardId)
    }

    fun exitDetail() {
        // 목록으로 나가면서 재생을 놔두면 다른 보드를 열었을 때 이전 녹음이 계속 들린다
        stopPlayback()
        _state.update {
            it.copy(detail = null, segments = emptyList(), chats = emptyList(), transcriptDirty = false)
        }
    }

    fun saveBoardTitle(title: String) = run {
        val detail = _state.value.detail ?: return@run
        if (title.isBlank() || title.trim() == detail.title) return@run
        client.updateBoardTitle(detail.id, title.trim())
        _state.update { it.copy(detail = detail.copy(title = title.trim())) }
        toast("제목이 변경되었습니다.")
        loadBoards(showSpinner = false)
    }

    fun onSegmentChanged(index: Int, content: String) = _state.update { s ->
        val segments = s.segments.toMutableList()
        if (index !in segments.indices) return@update s
        segments[index] = segments[index].copy(content = content)
        s.copy(segments = segments, transcriptDirty = true)
    }

    /** 문단에서 포커스가 빠질 때(웹의 blur) 또는 저장 버튼으로 호출한다. */
    fun saveTranscriptChanges(silent: Boolean = false) = run {
        val s = _state.value
        val detail = s.detail ?: return@run
        if (!s.transcriptDirty) return@run
        _state.update { it.copy(savingTranscript = true) }
        try {
            client.saveTranscript(detail.id, s.segments)
            _state.update { it.copy(transcriptDirty = false) }
            if (!silent) toast("저장되었습니다.")
        } finally {
            _state.update { it.copy(savingTranscript = false) }
        }
    }

    fun onScriptQueryChange(query: String) = _state.update { it.copy(scriptQuery = query) }

    fun regenerateKeywords() = run {
        val detail = _state.value.detail ?: return@run
        _state.update { it.copy(keywordsLoading = true) }
        try {
            toast("AI가 키워드를 새로 추출하는 중...")
            val keywords = client.generateKeywords(detail.id)
            _state.update { it.copy(keywords = keywords) }
            toast("키워드가 갱신되었습니다.")
        } finally {
            _state.update { it.copy(keywordsLoading = false) }
        }
    }

    fun renameSpeaker(oldName: String, newName: String) = run {
        val detail = _state.value.detail ?: return@run
        if (oldName.isBlank() || newName.isBlank()) return@run
        client.renameSpeaker(detail.id, oldName.trim(), newName.trim())
        toast("화자 이름이 '${newName.trim()}'(으)로 변경되었습니다.")
        openBoardDetail(detail.id)
    }

    // ------------------------------------------------------------ AI 패널

    fun switchAiTab(tab: AiTab) = _state.update { it.copy(aiTab = tab) }

    private fun loadChatHistory(boardId: Int) = run {
        val history = client.getChats(boardId).map { ChatBubble(it.isUser, it.message) }
        _state.update { if (it.detail?.id == boardId) it.copy(chats = history) else it }
    }

    fun sendChat(message: String) {
        val detail = _state.value.detail ?: return
        val text = message.trim()
        if (text.isBlank() || _state.value.chatSending) return

        _state.update {
            it.copy(
                chats = it.chats + ChatBubble(true, text) + ChatBubble(false, "", pending = true),
                chatSending = true,
                aiTab = AiTab.CHAT
            )
        }

        chatJob?.cancel()
        chatJob = viewModelScope.launch {
            val buffer = StringBuilder()
            try {
                client.streamChat(detail.id, text) { chunk ->
                    buffer.append(chunk)
                    _state.update { s ->
                        val chats = s.chats.toMutableList()
                        val last = chats.lastIndex
                        if (last >= 0) chats[last] = ChatBubble(false, buffer.toString(), pending = true)
                        s.copy(chats = chats)
                    }
                }
                _state.update { s ->
                    val chats = s.chats.toMutableList()
                    val last = chats.lastIndex
                    if (last >= 0) {
                        val body = buffer.toString().ifBlank { "답변을 받지 못했습니다." }
                        chats[last] = ChatBubble(false, body)
                    }
                    s.copy(chats = chats, chatSending = false)
                }
            } catch (e: Exception) {
                _state.update { s ->
                    val chats = s.chats.toMutableList()
                    val last = chats.lastIndex
                    val body = buffer.toString().ifBlank {
                        "답변을 불러오는 중 오류가 발생했습니다: " + (e.message ?: "알 수 없는 오류")
                    }
                    if (last >= 0) chats[last] = ChatBubble(false, body)
                    s.copy(chats = chats, chatSending = false)
                }
            }
        }
    }

    fun requestSummary(type: String) = run {
        val detail = _state.value.detail ?: return@run
        val isBasic = type == "BASIC"
        _state.update {
            it.copy(
                summaryLoading = type,
                aiTab = if (isBasic) AiTab.SUMMARY else AiTab.TEMPLATE
            )
        }
        try {
            val content = client.generateSummary(detail.id, type)
            _state.update {
                if (isBasic) it.copy(summaryBasic = content) else it.copy(summaryTemplate = content)
            }
        } finally {
            _state.update { it.copy(summaryLoading = null) }
        }
    }

    // ------------------------------------------------------------ 북마크

    fun addBookmarkAt(ms: Long, tsStr: String, note: String = "") = run {
        val detail = _state.value.detail ?: return@run
        val bookmark = client.addBookmark(detail.id, ms, tsStr, note)
        _state.update { it.copy(bookmarks = (it.bookmarks + bookmark).sortedBy { b -> b.timestampMs }) }
        toast("북마크가 추가되었습니다.")
    }

    fun addCurrentBookmark(note: String = "") {
        val ms = _state.value.positionMs
        addBookmarkAt(ms, "[${formatClock(ms)}]", note)
    }

    fun deleteBookmark(bookmarkId: Int) = run {
        client.deleteBookmark(bookmarkId)
        _state.update { it.copy(bookmarks = it.bookmarks.filter { b -> b.id != bookmarkId }) }
        toast("북마크가 삭제되었습니다.")
    }

    // ------------------------------------------------------------ 단어장

    fun openGlossary() = run {
        val detail = _state.value.detail
        _state.update { it.copy(glossaryLoading = true) }
        try {
            val glossary = client.getGlossary(detail?.folderId)
            _state.update { it.copy(glossary = glossary) }
        } finally {
            _state.update { it.copy(glossaryLoading = false) }
        }
    }

    fun closeGlossary() = _state.update { it.copy(glossary = null) }

    fun saveGlossary(folderText: String, commonText: String, thenRetranscribe: Boolean) = run {
        val glossary = _state.value.glossary ?: return@run
        if (glossary.folderId != null) {
            client.putGlossary(glossary.folderId, folderText.toGlossaryTerms())
        }
        client.putGlossary(null, commonText.toGlossaryTerms())
        _state.update { it.copy(glossary = null) }
        toast("단어장을 저장했습니다.")
        val detail = _state.value.detail
        if (thenRetranscribe && detail != null) {
            reprocessBoard(detail.id, "다시 받아쓰기를 시작했습니다. 변환이 끝나면 스크립트가 교체됩니다.")
        }
    }

    // ------------------------------------------------------------ 업로드

    fun uploadAudio(uri: Uri, displayName: String, folderId: Int?) = run {
        toast("업로드 중...")
        client.uploadAudio(getApplication<Application>(), uri, displayName, folderId)
        toast("파일이 업로드되었습니다. 백그라운드 AI 변환이 시작됩니다.")
        loadBoards(showSpinner = false)
        loadFolders()
    }

    // ------------------------------------------------------------ 재생

    private fun preparePlayer(detail: DagloBoardDetail) {
        releasePlayer()
        if (!detail.hasAudio) return
        try {
            val mp = MediaPlayer()
            mp.setDataSource(
                getApplication<Application>(),
                Uri.parse(client.audioUrl(detail.id)),
                client.authHeaders
            )
            mp.setOnPreparedListener { prepared ->
                _state.update { it.copy(durationMs = prepared.duration.toLong()) }
                applySpeedIfPlaying(prepared)
            }
            mp.setOnCompletionListener {
                stopProgressLoop()
                _state.update { it.copy(playing = false) }
            }
            mp.setOnErrorListener { _, _, _ ->
                toast("녹음을 재생할 수 없습니다.")
                true
            }
            mp.prepareAsync()
            player = mp
        } catch (e: Exception) {
            toast("녹음을 열 수 없습니다: ${e.message}")
        }
    }

    fun togglePlay() {
        val mp = player ?: return
        if (mp.isPlaying) {
            mp.pause()
            stopProgressLoop()
            _state.update { it.copy(playing = false) }
        } else {
            applySpeedIfPlaying(mp)
            mp.start()
            startProgressLoop()
            _state.update { it.copy(playing = true) }
        }
    }

    /** 자막 문단·북마크·채팅 시각 링크에서 부른다. scrollScript=true 면 자막도 그 자리로 데려간다. */
    fun playAtMs(ms: Long, scrollScript: Boolean = true) {
        val mp = player
        if (mp == null) {
            // 원본 녹음이 없는 보드에서는 자막만 고칠 수 있게 두고 재생은 넘어간다
            if (scrollScript) _state.update { it.copy(scrollRequestMs = ms) }
            return
        }
        runCatching {
            mp.seekTo(ms.toInt())
            applySpeedIfPlaying(mp)
            mp.start()
            startProgressLoop()
        }
        _state.update {
            it.copy(
                playing = true,
                positionMs = ms,
                activeSegmentId = activeSegmentIdAt(ms),
                scrollRequestMs = if (scrollScript) ms else it.scrollRequestMs
            )
        }
    }

    fun seekRelative(seconds: Int) {
        val mp = player ?: return
        val target = (mp.currentPosition + seconds * 1000).coerceIn(0, mp.duration.coerceAtLeast(0))
        mp.seekTo(target)
        _state.update { it.copy(positionMs = target.toLong(), activeSegmentId = activeSegmentIdAt(target.toLong())) }
    }

    fun seekTo(ms: Long, scrollScript: Boolean = true) {
        val mp = player
        mp?.seekTo(ms.toInt())
        _state.update {
            it.copy(
                positionMs = ms,
                activeSegmentId = activeSegmentIdAt(ms),
                scrollRequestMs = if (scrollScript) ms else it.scrollRequestMs
            )
        }
    }

    /** 드래그하는 동안에는 시간 표시만 따라 움직인다 (웹의 onSeekInput). */
    fun previewSeek(ms: Long) = _state.update { it.copy(positionMs = ms) }

    fun changeSpeed(speed: Float) {
        _state.update { it.copy(speed = speed) }
        val mp = player ?: return
        runCatching {
            val wasPlaying = mp.isPlaying
            mp.playbackParams = PlaybackParams().setSpeed(speed)
            // setPlaybackParams 는 정지 상태에서도 재생을 시작시키므로 원래 상태로 되돌린다
            if (!wasPlaying) mp.pause()
        }
    }

    private fun applySpeedIfPlaying(mp: MediaPlayer) {
        runCatching { mp.playbackParams = PlaybackParams().setSpeed(_state.value.speed) }
    }

    private fun startProgressLoop() {
        stopProgressLoop()
        progressJob = viewModelScope.launch {
            while (true) {
                val mp = player ?: break
                val pos = runCatching { mp.currentPosition.toLong() }.getOrDefault(0L)
                _state.update {
                    it.copy(positionMs = pos, activeSegmentId = activeSegmentIdAt(pos))
                }
                delay(200)
            }
        }
    }

    private fun stopProgressLoop() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun stopPlayback() {
        stopProgressLoop()
        releasePlayer()
        _state.update { it.copy(playing = false, positionMs = 0, activeSegmentId = null) }
    }

    private fun releasePlayer() {
        runCatching {
            player?.stop()
            player?.release()
        }
        player = null
    }

    /** 재생 위치를 포함하는(= 시작 시각이 ms 를 넘지 않는 마지막) 문단. 웹의 밑줄 규칙과 같다. */
    private fun activeSegmentIdAt(ms: Long): Int? =
        _state.value.segments.lastOrNull { it.startTimeMs <= ms }?.id

    // ------------------------------------------------------------ 변환 중 보드 폴링

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                delay(4000)
                val s = _state.value
                if (!s.serverConfigured) continue
                try {
                    val detail = s.detail
                    if (detail == null) {
                        if (s.boards.any { it.inFlight }) loadBoards(showSpinner = false)
                        continue
                    }
                    if (!detail.inFlight) continue

                    val fresh = client.getBoardDetail(detail.id)
                    val finished = !fresh.inFlight
                    _state.update { cur ->
                        if (cur.detail?.id != fresh.id) return@update cur
                        if (finished) {
                            cur.copy(
                                detail = fresh,
                                // 고치는 중이던 내용은 덮어쓰지 않는다
                                segments = if (cur.transcriptDirty) cur.segments else fresh.segments,
                                keywords = fresh.keywords,
                                summaryBasic = fresh.summaryOf("BASIC") ?: cur.summaryBasic,
                                toast = if (fresh.status == "COMPLETED") "변환이 완료되었습니다."
                                else "변환에 실패했습니다."
                            )
                        } else {
                            cur.copy(detail = fresh)
                        }
                    }
                } catch (e: Exception) {
                    // 잠깐 끊긴 것일 수 있으므로 조용히 넘어가고 다음 주기에 다시 시도한다
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopProgressLoop()
        releasePlayer()
    }
}

/** 00:00 / 1:02:03 형식. 웹의 formatTime() 과 같은 자리에서 쓴다. */
fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
