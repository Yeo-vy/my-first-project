@file:OptIn(ExperimentalMaterial3Api::class)

package com.recorder.voicenote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 보드 상세 화면. 웹의 `#board-detail-view` 를 그대로 옮겼다.
 *
 * 왼쪽은 자막(스크립트) 편집기, 오른쪽은 AI 패널(보드챗·요약·템플릿·북마크), 아래는 재생 바.
 * 화면이 좁으면(세로 태블릿 등) 좌우 2단 대신 탭으로 접는다.
 */
@Composable
fun DagloBoardDetailScreen(viewModel: DagloBoardViewModel, state: DagloUiState) {
    val detail = state.detail ?: return
    val context = LocalContext.current

    var showSpeakerDialog by remember { mutableStateOf<String?>(null) }
    var showExport by remember { mutableStateOf(false) }
    var confirmKind by remember { mutableStateOf<String?>(null) }
    var narrowTab by remember { mutableStateOf(0) }

    // 웹과 같은 단축키. 태블릿에 키보드를 붙여 받아쓰기를 고칠 때 쓴다.
    // 자막을 고치는 중(자식이 포커스를 쥔 상태)에도 미리보기 단계에서 먼저 받는다.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DagloColors.BgMain)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.key == Key.F1 -> { viewModel.seekRelative(-5); true }
                    event.key == Key.F2 -> { viewModel.togglePlay(); true }
                    event.key == Key.F3 -> { viewModel.seekRelative(5); true }
                    event.isCtrlPressed && event.key == Key.S -> {
                        viewModel.saveTranscriptChanges(); true
                    }
                    event.isCtrlPressed && event.key == Key.B -> {
                        viewModel.addCurrentBookmark(); true
                    }
                    event.key == Key.Escape -> { viewModel.exitDetail(); true }
                    else -> false
                }
            }
    ) {
        DetailHeader(
            detail = detail,
            onBack = { viewModel.exitDetail() },
            onStar = { viewModel.toggleStar(detail.id) },
            onTitleChange = { viewModel.saveBoardTitle(it) },
            onGlossary = { viewModel.openGlossary() },
            onRetranscribe = { confirmKind = "retranscribe" },
            onSpeaker = { showSpeakerDialog = "화자 1" },
            onExport = { showExport = true },
            onDelete = { confirmKind = "trash" }
        )

        DetailStatusBar(detail) { viewModel.reprocessBoard(detail.id, "변환 대기열에 다시 넣었습니다.") }

        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val twoPane = maxWidth >= DagloDims.TwoPaneMinWidth
            if (twoPane) {
                Row(modifier = Modifier.fillMaxSize()) {
                    ScriptPanel(
                        viewModel = viewModel,
                        state = state,
                        onSpeakerClick = { showSpeakerDialog = it },
                        modifier = Modifier.weight(1.35f).fillMaxHeight()
                    )
                    Divider(
                        modifier = Modifier.width(1.dp).fillMaxHeight(),
                        color = DagloColors.Border
                    )
                    AiPanel(
                        viewModel = viewModel,
                        state = state,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.fillMaxWidth().background(DagloColors.BgCard)) {
                        listOf("스크립트", "AI 어시스턴트").forEachIndexed { index, label ->
                            TabButton(label, narrowTab == index) { narrowTab = index }
                        }
                    }
                    Divider(color = DagloColors.Border)
                    if (narrowTab == 0) {
                        ScriptPanel(
                            viewModel = viewModel,
                            state = state,
                            onSpeakerClick = { showSpeakerDialog = it },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        AiPanel(viewModel = viewModel, state = state, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }

        PlayerBar(viewModel = viewModel, state = state, enabled = detail.hasAudio)
    }

    showSpeakerDialog?.let { speaker ->
        SpeakerRenameDialog(
            initialOldName = speaker,
            onDismiss = { showSpeakerDialog = null },
            onRename = { oldName, newName ->
                viewModel.renameSpeaker(oldName, newName)
                showSpeakerDialog = null
            }
        )
    }

    if (showExport) {
        ExportDialog(
            onDismiss = { showExport = false },
            onExport = { format ->
                downloadExport(context, DagloClient(DagloSettings(context)), detail.id, detail.title, format)
                showExport = false
            }
        )
    }

    confirmKind?.let { kind ->
        BoardActionConfirmDialog(
            kind = kind,
            onDismiss = { confirmKind = null },
            onConfirm = {
                when (kind) {
                    "retranscribe" -> viewModel.reprocessBoard(
                        detail.id,
                        "다시 받아쓰기를 시작했습니다. 변환이 끝나면 스크립트가 교체됩니다."
                    )
                    "trash" -> viewModel.deleteBoard(detail.id)
                }
                confirmKind = null
            }
        )
    }

    state.glossary?.let { glossary ->
        GlossaryDialog(
            glossary = glossary,
            canRetranscribe = detail.hasAudio,
            onDismiss = { viewModel.closeGlossary() },
            onSave = { folderText, commonText, thenRetranscribe ->
                viewModel.saveGlossary(folderText, commonText, thenRetranscribe)
            }
        )
    }
}

// ------------------------------------------------------------------ 헤더

@Composable
private fun DetailHeader(
    detail: DagloBoardDetail,
    onBack: () -> Unit,
    onStar: () -> Unit,
    onTitleChange: (String) -> Unit,
    onGlossary: () -> Unit,
    onRetranscribe: () -> Unit,
    onSpeaker: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    var title by remember(detail.id, detail.title) { mutableStateOf(detail.title) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DagloColors.BgCard)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, "목록으로", tint = DagloColors.TextMuted)
        }
        IconButton(onClick = onStar) {
            Icon(
                imageVector = if (detail.isStarred) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = "중요 보드",
                tint = if (detail.isStarred) DagloColors.Star else DagloColors.TextSubtle
            )
        }
        Text(detail.folderName, fontSize = 12.5.sp, color = DagloColors.TextSubtle)
        Text(" / ", fontSize = 12.5.sp, color = DagloColors.Border)

        BasicTextField(
            value = title,
            onValueChange = { title = it },
            singleLine = true,
            textStyle = TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = DagloColors.TextMain
            ),
            cursorBrush = SolidColor(DagloColors.Primary),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp)
                .onFocusChanged { if (!it.isFocused) onTitleChange(title) }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            DagloSecondaryButton("단어장", Icons.Default.Book, onGlossary)
            if (detail.hasAudio) {
                DagloSecondaryButton(
                    text = "다시 받아쓰기",
                    icon = Icons.Default.Refresh,
                    onClick = onRetranscribe,
                    enabled = !detail.inFlight
                )
            }
            DagloSecondaryButton("화자 이름 변경", Icons.Default.PersonOutline, onSpeaker)
            DagloSecondaryButton("내보내기", Icons.Default.Download, onExport)
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.DeleteOutline, "삭제", tint = DagloColors.Danger)
            }
        }
    }
    Divider(color = DagloColors.Border)
}

@Composable
private fun DetailStatusBar(detail: DagloBoardDetail, onRetry: () -> Unit) {
    when {
        detail.inFlight -> {
            val pct = if (detail.status == "PENDING") 0 else detail.progressPercent
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DagloColors.PrimaryLight)
                    .padding(horizontal = 20.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = DagloColors.Primary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (detail.status == "PENDING") "변환 대기 중" else "AI 변환 중 ${pct}%",
                    fontSize = 12.5.sp,
                    color = DagloColors.Primary,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.width(14.dp))
                LinearProgressIndicator(
                    progress = pct / 100f,
                    modifier = Modifier.width(180.dp).height(5.dp),
                    color = DagloColors.Primary,
                    trackColor = DagloColors.Border
                )
            }
        }

        detail.status == "FAILED" -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DagloColors.Danger.copy(alpha = 0.08f))
                    .padding(horizontal = 20.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "변환 실패: ${detail.errorMessage ?: "알 수 없는 오류"}",
                    modifier = Modifier.weight(1f),
                    fontSize = 12.5.sp,
                    color = DagloColors.Danger
                )
                DagloSecondaryButton("다시 시도", Icons.Default.Refresh, onRetry)
            }
        }
    }
}

// ------------------------------------------------------------------ 스크립트 패널

/** 화면에 그리는 단위. 웹의 `.script-group`(1분 묶음) + `.script-block`(문단) 구조와 같다. */
private sealed class ScriptItem {
    data class GroupHeader(val label: String, val startMs: Long) : ScriptItem()
    data class Block(val index: Int, val segment: DagloSegment, val showSpeaker: Boolean) : ScriptItem()
}

/**
 * 분 단위로 자르면 01:10 / 01:55 처럼 같은 분에 걸친 문단이 한 묶음이 되지 않으므로,
 * `마지막으로 묶음을 연 시각에서 1분이 지났을 때` 새 묶음을 연다 (웹 renderTranscript 와 동일).
 */
private fun buildScriptItems(segments: List<DagloSegment>): List<ScriptItem> {
    val items = mutableListOf<ScriptItem>()
    var groupStartMs: Long? = null
    var lastSpeaker: String? = null

    segments.forEachIndexed { index, seg ->
        if (groupStartMs == null || seg.startTimeMs - groupStartMs!! >= 60_000) {
            groupStartMs = seg.startTimeMs
            lastSpeaker = null   // 묶음이 바뀌면 화자를 한 번 다시 적어 준다
            items += ScriptItem.GroupHeader(seg.timestampStr.trim('[', ']'), seg.startTimeMs)
        }
        items += ScriptItem.Block(index, seg, seg.speaker != lastSpeaker)
        lastSpeaker = seg.speaker
    }
    return items
}

@Composable
private fun ScriptPanel(
    viewModel: DagloBoardViewModel,
    state: DagloUiState,
    onSpeakerClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val items = remember(state.segments) { buildScriptItems(state.segments) }
    val listState = rememberLazyListState()
    val query = state.scriptQuery.trim().lowercase()

    // 북마크·채팅 시각 링크나 재생 바 이동으로 온 요청이면 해당 문단으로 데려간다
    LaunchedEffect(state.scrollRequestMs) {
        val ms = state.scrollRequestMs ?: return@LaunchedEffect
        val target = items.indexOfLast { it is ScriptItem.Block && it.segment.startTimeMs <= ms }
        if (target >= 0) runCatching { listState.animateScrollToItem(target) }
        viewModel.consumeScrollRequest()
    }

    Column(modifier = modifier.background(DagloColors.BgMain)) {
        // 핵심 키워드
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DagloColors.BgCard)
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("핵심 키워드", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = DagloColors.TextSubtle)
            Spacer(modifier = Modifier.width(12.dp))
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                if (state.keywords.isEmpty()) {
                    Text("추출된 키워드가 없습니다.", fontSize = 11.5.sp, color = DagloColors.TextSubtle)
                } else {
                    state.keywords.forEach { kw ->
                        Text(
                            text = "# $kw",
                            fontSize = 11.5.sp,
                            color = DagloColors.Primary,
                            modifier = Modifier
                                .background(DagloColors.PrimaryLight, RoundedCornerShape(6.dp))
                                .clickable { viewModel.onScriptQueryChange(kw) }
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
            IconButton(
                onClick = { viewModel.regenerateKeywords() },
                enabled = !state.keywordsLoading,
                modifier = Modifier.size(32.dp)
            ) {
                if (state.keywordsLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = DagloColors.Primary)
                } else {
                    Icon(Icons.Default.Refresh, "키워드 새로 추출", tint = DagloColors.TextMuted, modifier = Modifier.size(16.dp))
                }
            }
        }
        Divider(color = DagloColors.Border)

        // 본문 검색
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DagloColors.BgCard)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, null, tint = DagloColors.TextSubtle, modifier = Modifier.size(15.dp))
            Spacer(modifier = Modifier.width(8.dp))
            BasicTextField(
                value = state.scriptQuery,
                onValueChange = { viewModel.onScriptQueryChange(it) },
                singleLine = true,
                textStyle = TextStyle(fontSize = 13.sp, color = DagloColors.TextMain),
                cursorBrush = SolidColor(DagloColors.Primary),
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                decorationBox = { inner ->
                    Box {
                        if (state.scriptQuery.isEmpty()) {
                            Text("본문 검색 (단어 찾기)", fontSize = 13.sp, color = DagloColors.TextSubtle)
                        }
                        inner()
                    }
                }
            )
            if (state.scriptQuery.isNotEmpty()) {
                IconButton(onClick = { viewModel.onScriptQueryChange("") }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, "검색 지우기", tint = DagloColors.TextSubtle, modifier = Modifier.size(15.dp))
                }
            }
            if (state.transcriptDirty) {
                Spacer(modifier = Modifier.width(8.dp))
                DagloSecondaryButton("저장", null, { viewModel.saveTranscriptChanges() }, enabled = !state.savingTranscript)
            }
        }
        Divider(color = DagloColors.Border)

        if (state.segments.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (state.detail?.inFlight == true) "변환이 끝나면 스크립트가 여기에 표시됩니다."
                    else "스크립트 내용이 없습니다.",
                    color = DagloColors.TextSubtle,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp, end = 20.dp, top = 12.dp, bottom = 30.dp
                )
            ) {
                itemsIndexed(items) { _, item ->
                    when (item) {
                        is ScriptItem.GroupHeader -> Text(
                            text = item.label,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = DagloColors.TextSubtle,
                            modifier = Modifier.padding(top = 18.dp, bottom = 4.dp)
                        )

                        is ScriptItem.Block -> ScriptBlock(
                            segment = item.segment,
                            index = item.index,
                            showSpeaker = item.showSpeaker,
                            active = state.activeSegmentId == item.segment.id,
                            matched = query.isBlank() || item.segment.content.lowercase().contains(query),
                            dimmed = query.isNotBlank() && !item.segment.content.lowercase().contains(query),
                            onFocus = { viewModel.playAtMs(item.segment.startTimeMs, scrollScript = false) },
                            onBlur = { viewModel.saveTranscriptChanges() },
                            onChange = { viewModel.onSegmentChanged(item.index, it) },
                            onSpeakerClick = { onSpeakerClick(item.segment.speaker) },
                            onBookmark = {
                                viewModel.addBookmarkAt(item.segment.startTimeMs, item.segment.timestampStr)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScriptBlock(
    segment: DagloSegment,
    index: Int,
    showSpeaker: Boolean,
    active: Boolean,
    matched: Boolean,
    dimmed: Boolean,
    onFocus: () -> Unit,
    onBlur: () -> Unit,
    onChange: (String) -> Unit,
    onSpeakerClick: () -> Unit,
    onBookmark: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.4f else 1f)
            .background(
                when {
                    focused -> DagloColors.BgCard
                    matched && !dimmed && active -> DagloColors.PrimaryLight
                    else -> Color.Transparent
                },
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        if (showSpeaker) {
            Text(
                text = segment.speaker,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = DagloColors.Primary,
                modifier = Modifier
                    .background(DagloColors.PrimaryLight, RoundedCornerShape(5.dp))
                    .clickable { onSpeakerClick() }
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        Row(verticalAlignment = Alignment.Top) {
            BasicTextField(
                value = segment.content,
                onValueChange = onChange,
                textStyle = TextStyle(
                    fontSize = 14.5.sp,
                    lineHeight = 25.sp,
                    color = DagloColors.TextMain,
                    // 재생 중에는 밑줄로 지금 위치만 표시한다 (웹의 .active)
                    textDecoration = if (active) TextDecoration.Underline else null
                ),
                cursorBrush = SolidColor(DagloColors.Primary),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused && !focused) {
                            focused = true
                            onFocus()
                        } else if (!focusState.isFocused && focused) {
                            focused = false
                            onBlur()
                        }
                    }
            )
            IconButton(onClick = onBookmark, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.BookmarkBorder,
                    "이 위치 북마크",
                    tint = DagloColors.TextSubtle,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

// ------------------------------------------------------------------ AI 패널

@Composable
private fun AiPanel(viewModel: DagloBoardViewModel, state: DagloUiState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.background(DagloColors.BgCard)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            AiTab.values().forEach { tab ->
                TabButton(tab.label, state.aiTab == tab) { viewModel.switchAiTab(tab) }
            }
        }
        Divider(color = DagloColors.Border)

        when (state.aiTab) {
            AiTab.CHAT -> ChatTab(viewModel, state, Modifier.weight(1f))
            AiTab.SUMMARY -> SummaryTab(viewModel, state, Modifier.weight(1f))
            AiTab.TEMPLATE -> TemplateTab(viewModel, state, Modifier.weight(1f))
            AiTab.BOOKMARKS -> BookmarksTab(viewModel, state, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TabButton(label: String, active: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            fontSize = 12.5.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            color = if (active) DagloColors.Primary else DagloColors.TextMuted
        )
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(2.dp)
                .background(if (active) DagloColors.Primary else Color.Transparent)
        )
    }
}

@Composable
private fun ChatTab(viewModel: DagloBoardViewModel, state: DagloUiState, modifier: Modifier) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.chats.size, state.chats.lastOrNull()?.text?.length) {
        if (state.chats.isNotEmpty()) runCatching { listState.animateScrollToItem(state.chats.size) }
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                "⭐ 핵심 요약 정리" to "이 강의/회의의 핵심 내용을 3줄로 요약해줘",
                "📝 회의록 정리" to "논의된 안건과 결정사항을 회의록 형태로 정리해줘",
                "🎯 액션 아이템 만들기" to "해야 할 액션 아이템과 할 일을 목록으로 추출해줘"
            ).forEach { (label, prompt) ->
                Text(
                    text = label,
                    fontSize = 11.5.sp,
                    color = DagloColors.TextMain,
                    modifier = Modifier
                        .border(1.dp, DagloColors.Border, RoundedCornerShape(20.dp))
                        .clickable { viewModel.sendChat(prompt) }
                        .padding(horizontal = 11.dp, vertical = 6.dp)
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                ChatBubbleView(
                    ChatBubble(
                        false,
                        "안녕하세요! **${state.detail?.title ?: ""}** 녹음 내용을 모두 학습했습니다.\n" +
                            "궁금한 점이 있거나 요약이 필요하시면 무엇이든 물어보세요!"
                    ),
                    viewModel
                )
            }
            itemsIndexed(state.chats) { _, bubble -> ChatBubbleView(bubble, viewModel) }
        }

        Divider(color = DagloColors.Border)
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                textStyle = TextStyle(fontSize = 13.5.sp, color = DagloColors.TextMain),
                cursorBrush = SolidColor(DagloColors.Primary),
                modifier = Modifier
                    .weight(1f)
                    .background(DagloColors.BgMain, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                decorationBox = { inner ->
                    Box {
                        if (input.isEmpty()) {
                            Text("궁금한 내용을 질문해 보세요", fontSize = 13.5.sp, color = DagloColors.TextSubtle)
                        }
                        inner()
                    }
                }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        if (state.chatSending) DagloColors.BorderHover else DagloColors.Primary,
                        CircleShape
                    )
                    .clickable(enabled = !state.chatSending && input.isNotBlank()) {
                        viewModel.sendChat(input)
                        input = ""
                    },
                contentAlignment = Alignment.Center
            ) {
                if (state.chatSending) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Icon(Icons.Default.ArrowUpward, "보내기", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun ChatBubbleView(bubble: ChatBubble, viewModel: DagloBoardViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (bubble.isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .background(
                    if (bubble.isUser) DagloColors.ChatUserBubble else DagloColors.BgMain,
                    RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            if (bubble.text.isBlank() && bubble.pending) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp, color = DagloColors.Primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("답변 생성 중...", fontSize = 12.5.sp, color = DagloColors.TextMuted)
                }
            } else {
                DagloMarkdown(
                    text = bubble.text,
                    onTimestampClick = { viewModel.playAtMs(it) }
                )
            }
        }
    }
}

@Composable
private fun SummaryTab(viewModel: DagloBoardViewModel, state: DagloUiState, modifier: Modifier) {
    Column(modifier = modifier.padding(14.dp).verticalScroll(rememberScrollState())) {
        DagloPrimaryButton(
            text = "AI 요약 생성/갱신",
            icon = Icons.Default.Refresh,
            onClick = { viewModel.requestSummary("BASIC") },
            enabled = state.summaryLoading == null
        )
        Spacer(modifier = Modifier.height(14.dp))
        when {
            state.summaryLoading == "BASIC" -> LoadingBlock("Gemini가 내용을 분석하여 요약을 작성 중입니다...")
            state.summaryBasic != null -> DagloMarkdown(
                text = state.summaryBasic,
                onTimestampClick = { viewModel.playAtMs(it) }
            )
            else -> Text(
                "상단의 [AI 요약 생성] 버튼을 누르면 본문 전체를 3단 구조로 분석하여 요약합니다.",
                fontSize = 13.sp,
                color = DagloColors.TextSubtle
            )
        }
    }
}

@Composable
private fun TemplateTab(viewModel: DagloBoardViewModel, state: DagloUiState, modifier: Modifier) {
    Column(modifier = modifier.padding(14.dp).verticalScroll(rememberScrollState())) {
        listOf(
            Triple("MEETING", "회의록 템플릿", "의제, 논의 내용, 결정사항 정리"),
            Triple("ACTION_ITEM", "액션 아이템 템플릿", "실행 과제 및 To-Do 목록 정리"),
            Triple("QUIZ", "복습 퀴즈 템플릿", "핵심 개념 퀴즈 3문제와 정답"),
            Triple("SLIDE", "슬라이드 발표 템플릿", "발표 자료 구성 및 페이지별 개요")
        ).forEach { (type, title, subtitle) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .border(1.dp, DagloColors.Border, RoundedCornerShape(9.dp))
                    .clickable(enabled = state.summaryLoading == null) { viewModel.requestSummary(type) }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Column {
                    Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DagloColors.TextMain)
                    Text(subtitle, fontSize = 11.5.sp, color = DagloColors.TextSubtle)
                }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        when {
            state.summaryLoading != null && state.summaryLoading != "BASIC" ->
                LoadingBlock("Gemini가 내용을 분석하여 요약을 작성 중입니다...")
            state.summaryTemplate != null -> DagloMarkdown(
                text = state.summaryTemplate,
                onTimestampClick = { viewModel.playAtMs(it) }
            )
        }
    }
}

@Composable
private fun BookmarksTab(viewModel: DagloBoardViewModel, state: DagloUiState, modifier: Modifier) {
    var note by remember { mutableStateOf("") }

    Column(modifier = modifier.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = note,
                onValueChange = { note = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 13.sp, color = DagloColors.TextMain),
                cursorBrush = SolidColor(DagloColors.Primary),
                modifier = Modifier
                    .weight(1f)
                    .background(DagloColors.BgMain, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                decorationBox = { inner ->
                    Box {
                        if (note.isEmpty()) {
                            Text("현재 재생 위치에 메모 작성...", fontSize = 13.sp, color = DagloColors.TextSubtle)
                        }
                        inner()
                    }
                }
            )
            Spacer(modifier = Modifier.width(8.dp))
            DagloPrimaryButton("추가", null, {
                viewModel.addCurrentBookmark(note)
                note = ""
            })
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (state.bookmarks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Text("저장된 북마크가 없습니다.", fontSize = 12.5.sp, color = DagloColors.TextSubtle)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(state.bookmarks) { _, bookmark ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DagloColors.BgMain, RoundedCornerShape(8.dp))
                            .clickable { viewModel.playAtMs(bookmark.timestampMs) }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = bookmark.timestampStr,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = DagloColors.Primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = bookmark.note.ifBlank { "북마크" },
                            modifier = Modifier.weight(1f),
                            fontSize = 12.5.sp,
                            color = DagloColors.TextMain
                        )
                        IconButton(
                            onClick = { viewModel.deleteBookmark(bookmark.id) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.DeleteOutline, "삭제", tint = DagloColors.TextSubtle, modifier = Modifier.size(15.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingBlock(message: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = DagloColors.Primary, modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
        Spacer(modifier = Modifier.height(10.dp))
        Text(message, fontSize = 12.5.sp, color = DagloColors.TextMuted)
    }
}

// ------------------------------------------------------------------ 재생 바

@Composable
private fun PlayerBar(viewModel: DagloBoardViewModel, state: DagloUiState, enabled: Boolean) {
    var speedMenuOpen by remember { mutableStateOf(false) }
    val duration = if (state.durationMs > 0) state.durationMs else 1L

    Divider(color = DagloColors.Border)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(DagloDims.PlayerHeight)
            .background(DagloColors.BgCard)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { viewModel.seekRelative(-5) }, enabled = enabled) {
            Icon(Icons.Default.Replay5, "5초 이전", tint = DagloColors.TextMuted)
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(if (enabled) DagloColors.Primary else DagloColors.BorderHover, CircleShape)
                .clickable(enabled = enabled) { viewModel.togglePlay() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = "재생/일시정지",
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
        IconButton(onClick = { viewModel.seekRelative(5) }, enabled = enabled) {
            Icon(Icons.Default.Forward5, "5초 이후", tint = DagloColors.TextMuted)
        }

        Spacer(modifier = Modifier.width(14.dp))
        Text(formatClock(state.positionMs), fontSize = 12.sp, color = DagloColors.TextMuted)
        Slider(
            value = (state.positionMs.toFloat() / duration).coerceIn(0f, 1f),
            onValueChange = { viewModel.previewSeek((it * duration).toLong()) },
            onValueChangeFinished = { viewModel.seekTo(state.positionMs) },
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = DagloColors.Primary,
                activeTrackColor = DagloColors.Primary,
                inactiveTrackColor = DagloColors.Border
            ),
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
        )
        Text(formatClock(state.durationMs), fontSize = 12.sp, color = DagloColors.TextMuted)

        Spacer(modifier = Modifier.width(14.dp))
        Box {
            DagloSecondaryButton("${state.speed}x", null, { speedMenuOpen = true })
            DropdownMenu(expanded = speedMenuOpen, onDismissRequest = { speedMenuOpen = false }) {
                listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                    DropdownMenuItem(
                        text = { Text("${speed}x") },
                        onClick = {
                            viewModel.changeSpeed(speed)
                            speedMenuOpen = false
                        }
                    )
                }
            }
        }
        IconButton(onClick = { viewModel.addCurrentBookmark() }, enabled = enabled) {
            Icon(Icons.Default.Bookmark, "북마크 추가", tint = DagloColors.TextMuted)
        }
    }
}
