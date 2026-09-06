@file:OptIn(ExperimentalMaterial3Api::class)

package com.recorder.voicenote

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * daglo 태블릿 화면의 뿌리.
 *
 * 웹(server/static/index.html)의 `사이드바 + 메인 영역` 2단 구성을 그대로 옮겼다.
 * 메인 영역은 목록(대시보드)과 보드 상세를 갈아 끼우는 자리다.
 */
@Composable
fun DagloHomeScreen(
    viewModel: DagloBoardViewModel,
    onOpenRecorder: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWeb: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    var showNewFolder by remember { mutableStateOf(false) }
    var folderPendingDelete by remember { mutableStateOf<DagloFolder?>(null) }
    var showUpload by remember { mutableStateOf(false) }
    var showBatchMove by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.reloadSettings()
        viewModel.loadBoards()
    }

    // 상세 화면에서 뒤로가기는 목록으로. 목록에서는 앱의 기본 동작(녹음 화면)으로 넘긴다.
    BackHandler(enabled = state.detail != null) { viewModel.exitDetail() }

    Box(modifier = Modifier.fillMaxSize().background(DagloColors.BgMain)) {
        Row(modifier = Modifier.fillMaxSize()) {
            DagloSidebar(
                state = state,
                onHome = { viewModel.goHome() },
                onFilter = { viewModel.changeFilter(it) },
                onFolder = { viewModel.selectFolder(it) },
                onNewFolder = { showNewFolder = true },
                onDeleteFolder = { folderPendingDelete = it },
                onUpload = { showUpload = true },
                onOpenRecorder = onOpenRecorder,
                onOpenSettings = onOpenSettings,
                onOpenWeb = onOpenWeb
            )

            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (!state.serverConfigured) {
                    ServerNotConfigured(onOpenSettings)
                } else if (state.detail != null) {
                    DagloBoardDetailScreen(viewModel = viewModel, state = state)
                } else {
                    DagloDashboard(
                        state = state,
                        viewModel = viewModel,
                        onUpload = { showUpload = true },
                        onBatchMove = { showBatchMove = true }
                    )
                }
            }
        }

        // 웹의 토스트. 화면 아래 가운데에 잠깐 떴다 사라진다.
        state.toast?.let { message ->
            DagloToast(message) { viewModel.consumeToast() }
        }
    }

    if (showNewFolder) {
        NewFolderDialog(
            onDismiss = { showNewFolder = false },
            onCreate = {
                viewModel.createFolder(it)
                showNewFolder = false
            }
        )
    }

    folderPendingDelete?.let { folder ->
        DeleteFolderDialog(
            folder = folder,
            onDismiss = { folderPendingDelete = null },
            onDelete = { withBoards ->
                viewModel.deleteFolder(folder, withBoards)
                folderPendingDelete = null
            }
        )
    }

    if (showUpload) {
        UploadDialog(
            folders = state.folders,
            defaultFolderId = state.folderId ?: state.folders.firstOrNull()?.id,
            onDismiss = { showUpload = false },
            onUpload = { uri, folderId ->
                viewModel.uploadAudio(uri, displayNameOf(context, uri), folderId)
                showUpload = false
            }
        )
    }

    if (showBatchMove) {
        BatchMoveDialog(
            folders = state.folders,
            count = state.selectedIds.size,
            onDismiss = { showBatchMove = false },
            onMove = {
                viewModel.batchMove(it)
                showBatchMove = false
            }
        )
    }
}

// ------------------------------------------------------------------ 사이드바

@Composable
private fun DagloSidebar(
    state: DagloUiState,
    onHome: () -> Unit,
    onFilter: (DagloFilter) -> Unit,
    onFolder: (DagloFolder) -> Unit,
    onNewFolder: () -> Unit,
    onDeleteFolder: (DagloFolder) -> Unit,
    onUpload: () -> Unit,
    onOpenRecorder: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWeb: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(DagloDims.SidebarWidth)
            .fillMaxHeight()
            .background(DagloColors.BgCard)
            .border(width = 1.dp, color = DagloColors.Border)
    ) {
        // 브랜드 배지. 웹은 여기에 로그인 계정을 보여 주지만, 앱은 API 토큰으로 붙으므로
        // 대신 접속 중인 서버 주소를 적어 둔다.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onHome() }
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(DagloColors.Primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("da", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("daglo AI", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = DagloColors.TextMain)
                Text(
                    text = state.serverUrl.removePrefix("http://").removePrefix("https://")
                        .ifBlank { "서버 미설정" },
                    fontSize = 11.5.sp,
                    color = DagloColors.TextSubtle,
                    maxLines = 1
                )
            }
        }

        NavItem("전체 보드", Icons.Default.GridView, state.filter == DagloFilter.ALL && state.folderId == null) {
            onFilter(DagloFilter.ALL)
        }
        NavItem("중요 보드", Icons.Default.Star, state.filter == DagloFilter.STARRED && state.folderId == null, DagloColors.Star) {
            onFilter(DagloFilter.STARRED)
        }
        NavItem("미완료 녹음", Icons.Default.HourglassEmpty, state.filter == DagloFilter.PROCESSING && state.folderId == null, DagloColors.Primary) {
            onFilter(DagloFilter.PROCESSING)
        }
        NavItem("휴지통", Icons.Default.DeleteOutline, state.filter == DagloFilter.TRASH && state.folderId == null) {
            onFilter(DagloFilter.TRASH)
        }

        Spacer(modifier = Modifier.height(14.dp))
        Divider(color = DagloColors.Border)

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "폴더",
                modifier = Modifier.weight(1f),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                color = DagloColors.TextSubtle
            )
            IconButton(onClick = onNewFolder, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.Add, contentDescription = "새 폴더 추가", tint = DagloColors.TextMuted, modifier = Modifier.size(17.dp))
            }
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(state.folders, key = { it.id }) { folder ->
                FolderRow(
                    folder = folder,
                    active = state.folderId == folder.id,
                    onClick = { onFolder(folder) },
                    onDelete = { onDeleteFolder(folder) }
                )
            }
        }

        Divider(color = DagloColors.Border)
        Column(modifier = Modifier.padding(14.dp)) {
            DagloPrimaryButton(
                text = "새 받아쓰기",
                icon = Icons.Default.Add,
                onClick = onUpload,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // 웹에는 없는, 태블릿에서만 쓰는 통로들 (녹음 / 웹 화면 / 서버 설정)
                DagloSecondaryButton("녹음", Icons.Default.Mic, onOpenRecorder, Modifier.weight(1f))
                IconButton(onClick = onOpenWeb) {
                    Icon(Icons.Default.Language, contentDescription = "웹 화면으로 열기", tint = DagloColors.TextMuted)
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "서버 설정", tint = DagloColors.TextMuted)
                }
            }
        }
    }
}

@Composable
private fun NavItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    iconTint: Color = DagloColors.TextMuted,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .background(
                if (active) DagloColors.PrimaryLight else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) DagloColors.Primary else iconTint,
            modifier = Modifier.size(17.dp)
        )
        Spacer(modifier = Modifier.width(11.dp))
        Text(
            text = label,
            fontSize = 13.5.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            color = if (active) DagloColors.Primary else DagloColors.TextMain
        )
    }
}

@Composable
private fun FolderRow(
    folder: DagloFolder,
    active: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 1.dp)
            .background(
                if (active) DagloColors.PrimaryLight else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .clickable { onClick() }
            .padding(start = 10.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Folder,
            contentDescription = null,
            tint = DagloColors.Warning,
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.width(9.dp))
        Text(
            text = folder.name,
            modifier = Modifier.weight(1f),
            fontSize = 13.sp,
            maxLines = 1,
            color = if (active) DagloColors.Primary else DagloColors.TextMain
        )
        Text(
            text = folder.boardCount.toString(),
            fontSize = 11.sp,
            color = DagloColors.TextSubtle,
            modifier = Modifier
                .background(DagloColors.BgMain, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
        if (folder.canDelete) {
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "폴더 삭제",
                    tint = DagloColors.TextSubtle,
                    modifier = Modifier.size(15.dp)
                )
            }
        } else {
            Spacer(modifier = Modifier.width(28.dp))
        }
    }
}

// ------------------------------------------------------------------ 대시보드

@Composable
private fun DagloDashboard(
    state: DagloUiState,
    viewModel: DagloBoardViewModel,
    onUpload: () -> Unit,
    onBatchMove: () -> Unit
) {
    var confirmTarget by remember { mutableStateOf<DagloBoard?>(null) }
    var confirmKind by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        // 상단: 검색 + 새로고침 + 새 받아쓰기
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DagloColors.BgCard)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = state.search,
                onValueChange = { viewModel.onSearchChange(it) },
                modifier = Modifier.weight(1f).height(46.dp),
                placeholder = { Text("보드 제목 또는 자막 내용 검색...", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = DagloColors.TextSubtle, modifier = Modifier.size(18.dp)) },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = DagloColors.BgMain,
                    unfocusedContainerColor = DagloColors.BgMain,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
            Spacer(modifier = Modifier.width(12.dp))
            DagloSecondaryButton(
                text = if (state.refreshing) "새로고침 중" else "새로고침",
                icon = Icons.Default.Refresh,
                onClick = { viewModel.refreshBoards() },
                enabled = !state.refreshing
            )
            Spacer(modifier = Modifier.width(8.dp))
            DagloPrimaryButton("새 받아쓰기", Icons.Default.Add, onUpload)
        }
        Divider(color = DagloColors.Border)

        // 제목 + 개수 + 선택 작업
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(state.currentTitle, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = DagloColors.TextMain)
            Spacer(modifier = Modifier.width(9.dp))
            Text(
                text = state.boards.size.toString(),
                fontSize = 12.sp,
                color = DagloColors.TextMuted,
                modifier = Modifier
                    .background(DagloColors.Border, RoundedCornerShape(20.dp))
                    .padding(horizontal = 9.dp, vertical = 2.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            if (state.selectedIds.isNotEmpty()) {
                Text("${state.selectedIds.size}개 선택됨", fontSize = 12.5.sp, color = DagloColors.TextMuted)
                Spacer(modifier = Modifier.width(10.dp))
                if (!state.isTrash) {
                    DagloSecondaryButton("이동", Icons.Default.Folder, onBatchMove)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                DagloSecondaryButton(
                    text = if (state.isTrash) "완전 삭제" else "삭제",
                    icon = Icons.Default.Delete,
                    onClick = { viewModel.batchDelete() },
                    danger = true
                )
            }
        }

        // 표 머리글
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DagloColors.BgCard)
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = state.boards.isNotEmpty() && state.selectedIds.size == state.boards.size,
                onCheckedChange = { viewModel.toggleSelectAll(it) },
                modifier = Modifier.size(34.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Spacer(modifier = Modifier.width(30.dp))
            HeaderCell("보드 이름", Modifier.weight(1f))
            HeaderCell("길이", Modifier.width(80.dp))
            HeaderCell("폴더 위치", Modifier.width(140.dp))
            HeaderCell("생성일", Modifier.width(140.dp))
            Spacer(modifier = Modifier.width(96.dp))
        }
        Divider(color = DagloColors.Border)

        if (state.loading && state.boards.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = DagloColors.Primary)
            }
        } else if (state.boards.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Folder, null, tint = DagloColors.Border, modifier = Modifier.size(46.dp))
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (state.search.isNotBlank()) "검색 결과가 없습니다." else "등록된 보드가 없습니다.",
                        color = DagloColors.TextSubtle,
                        fontSize = 13.5.sp
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.boards, key = { it.id }) { board ->
                    BoardRow(
                        board = board,
                        checked = board.id in state.selectedIds,
                        isTrash = state.isTrash,
                        onCheck = { viewModel.toggleSelect(board.id, it) },
                        onOpen = { viewModel.openBoardDetail(board.id) },
                        onStar = { viewModel.toggleStar(board.id) },
                        onRedo = {
                            confirmTarget = board
                            confirmKind = if (board.status == "FAILED") "retry" else "retranscribe"
                        },
                        onDelete = {
                            confirmTarget = board
                            confirmKind = if (state.isTrash) "purge" else "trash"
                        },
                        onRestore = { viewModel.restoreBoard(board.id) }
                    )
                    Divider(color = DagloColors.Border)
                }
            }
        }
    }

    confirmTarget?.let { board ->
        BoardActionConfirmDialog(
            kind = confirmKind,
            onDismiss = { confirmTarget = null },
            onConfirm = {
                when (confirmKind) {
                    "retry" -> viewModel.reprocessBoard(board.id, "변환 대기열에 다시 넣었습니다.")
                    "retranscribe" -> viewModel.reprocessBoard(
                        board.id,
                        "다시 받아쓰기를 시작했습니다. 변환이 끝나면 스크립트가 교체됩니다."
                    )
                    "trash" -> viewModel.deleteBoard(board.id)
                    "purge" -> viewModel.deleteBoardPermanent(board.id)
                }
                confirmTarget = null
            }
        )
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.Bold,
        color = DagloColors.TextSubtle
    )
}

@Composable
private fun BoardRow(
    board: DagloBoard,
    checked: Boolean,
    isTrash: Boolean,
    onCheck: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onStar: () -> Unit,
    onRedo: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DagloColors.BgCard)
            .clickable { onOpen() }
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheck, modifier = Modifier.size(34.dp))
        Spacer(modifier = Modifier.width(6.dp))
        IconButton(onClick = onStar, modifier = Modifier.size(30.dp)) {
            Icon(
                imageVector = if (board.isStarred) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = "중요 보드",
                tint = if (board.isStarred) DagloColors.Star else DagloColors.TextSubtle,
                modifier = Modifier.size(17.dp)
            )
        }

        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = board.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = DagloColors.TextMain,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(8.dp))
                StatusBadge(board)
            }
            if (board.keywords.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    board.keywords.take(5).forEach { kw ->
                        Text(
                            text = "# $kw",
                            fontSize = 10.5.sp,
                            color = DagloColors.TextMuted,
                            modifier = Modifier
                                .background(DagloColors.BgMain, RoundedCornerShape(5.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        Text(board.durationStr, modifier = Modifier.width(80.dp), fontSize = 12.5.sp, color = DagloColors.TextMuted)
        Row(modifier = Modifier.width(140.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Folder, null, tint = DagloColors.Warning, modifier = Modifier.size(13.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(board.folderName, fontSize = 12.5.sp, color = DagloColors.TextMuted, maxLines = 1)
        }
        Text(board.createdAt, modifier = Modifier.width(140.dp), fontSize = 12.sp, color = DagloColors.TextSubtle)

        Row(modifier = Modifier.width(96.dp), horizontalArrangement = Arrangement.End) {
            if (isTrash) {
                IconButton(onClick = onRestore, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.Restore, "복원", tint = DagloColors.TextMuted, modifier = Modifier.size(17.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.Close, "완전 삭제", tint = DagloColors.Danger, modifier = Modifier.size(17.dp))
                }
            } else {
                if (board.hasAudio && !board.inFlight) {
                    IconButton(onClick = onRedo, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = if (board.status == "FAILED") "변환 다시 시도" else "다시 받아쓰기",
                            tint = DagloColors.TextMuted,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.DeleteOutline, "삭제", tint = DagloColors.TextMuted, modifier = Modifier.size(17.dp))
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(board: DagloBoard) {
    val (label, color) = when (board.status) {
        "PROCESSING" -> "변환 중 (${board.progressPercent}%)" to DagloColors.Primary
        "PENDING" -> "변환 대기 중" to DagloColors.Warning
        "FAILED" -> "실패" to DagloColors.Danger
        else -> return
    }
    Text(
        text = label,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Medium,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    )
}

@Composable
private fun ServerNotConfigured(onOpenSettings: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Settings, null, tint = DagloColors.Border, modifier = Modifier.size(52.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text("daglo 서버 주소가 아직 설정되지 않았습니다.", color = DagloColors.TextMuted, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text("서버 주소와 API 토큰을 넣으면 웹과 같은 화면을 씁니다.", color = DagloColors.TextSubtle, fontSize = 12.5.sp)
            Spacer(modifier = Modifier.height(16.dp))
            DagloPrimaryButton("서버 설정 열기", Icons.Default.Settings, onOpenSettings)
        }
    }
}

/** 파일 선택기가 준 uri 의 표시 이름. 서버는 이 이름을 보드 제목으로 쓴다. */
private fun displayNameOf(context: Context, uri: Uri): String {
    val fromProvider = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
    return fromProvider ?: uri.lastPathSegment ?: "recording.m4a"
}
