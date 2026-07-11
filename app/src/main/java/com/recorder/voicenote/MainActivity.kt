@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.recorder.voicenote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.recorder.voicenote.ui.theme.RecordingRed
import com.recorder.voicenote.ui.theme.TextSecondary
import com.recorder.voicenote.ui.theme.VoiceRecorderTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VoiceRecorderTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VoiceRecorderApp()
                }
            }
        }
    }
}

@Composable
fun VoiceRecorderApp(viewModel: RecorderViewModel = viewModel()) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Android 10(API 29) 미만에서는 공용 저장소에 직접 쓰기 위해 WRITE_EXTERNAL_STORAGE 도 필요하고,
    // Android 10~12는 다른 앱이 만든 파일까지 읽으려면 READ_EXTERNAL_STORAGE,
    // Android 13(API 33) 이상은 READ_MEDIA_AUDIO 와 POST_NOTIFICATIONS(녹음 중 알림 표시)가 필요하다.
    val requiredPermissions = remember {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> {
                    add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
                else -> {
                    add(Manifest.permission.READ_MEDIA_AUDIO)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    var hasPermissions by remember {
        mutableStateOf(
            requiredPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }
    // 권한 승인 후 바로 녹음을 시작하기 위한 플래그
    var pendingRecordAfterPermission by remember { mutableStateOf(false) }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasPermissions = result.values.all { it }
        if (hasPermissions) {
            viewModel.refresh()
            if (pendingRecordAfterPermission) {
                viewModel.startRecording()
            }
        }
        pendingRecordAfterPermission = false
    }

    fun requestRecordOrStart() {
        if (hasPermissions) {
            viewModel.startRecording()
        } else {
            pendingRecordAfterPermission = true
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        }
    }

    LaunchedEffect(Unit) {
        if (!hasPermissions) {
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        }
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.selectedFolder ?: "내 녹음",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    if (uiState.selectedFolder != null) {
                        IconButton(
                            onClick = { viewModel.goBackToFolderList() },
                            enabled = !uiState.isRecording
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "뒤로가기")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (uiState.selectedFolder == null) {
                FolderListScreen(
                    folders = uiState.folders,
                    storageLocationLabel = viewModel.storageLocationLabel,
                    onFolderClick = { viewModel.openFolder(it) },
                    onFolderLongClick = { viewModel.requestRenameFolder(it) },
                    onAddFolderClick = { viewModel.openAddFolderDialog() },
                    onRecordClick = { requestRecordOrStart() }
                )
            } else {
                FolderDetailScreen(
                    recordings = uiState.recordings,
                    playingRecordingName = uiState.playingRecordingName,
                    isRecording = uiState.isRecording,
                    elapsedSeconds = uiState.elapsedSeconds,
                    onRecordingClick = { viewModel.onRecordingClick(it) },
                    onRecordingLongClick = { viewModel.requestRenameRecording(it) },
                    onRecordClick = {
                        if (uiState.isRecording) {
                            viewModel.stopRecording()
                        } else {
                            requestRecordOrStart()
                        }
                    }
                )
            }

            if (uiState.showAddFolderDialog) {
                InputDialog(
                    title = "새 폴더",
                    placeholder = "폴더 이름",
                    confirmLabel = "추가",
                    onDismiss = { viewModel.dismissAddFolderDialog() },
                    onConfirm = { name -> viewModel.confirmAddFolder(name) }
                )
            }

            val renameTarget = uiState.renameTarget
            if (renameTarget != null) {
                val initialValue = when (renameTarget) {
                    is RenameTarget.Folder -> renameTarget.name
                    is RenameTarget.Recording ->
                        renameTarget.item.displayName.substringBeforeLast('.', renameTarget.item.displayName)
                }
                InputDialog(
                    title = "이름 변경",
                    initialValue = initialValue,
                    placeholder = "새 이름",
                    confirmLabel = "변경",
                    onDismiss = { viewModel.dismissRename() },
                    onConfirm = { name -> viewModel.confirmRename(name) }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------
// 폴더 목록 화면 (홈)
// ---------------------------------------------------------------------------------
@Composable
fun FolderListScreen(
    folders: List<FolderInfo>,
    storageLocationLabel: String,
    onFolderClick: (String) -> Unit,
    onFolderLongClick: (String) -> Unit,
    onAddFolderClick: () -> Unit,
    onRecordClick: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = storageLocationLabel,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (folders.isEmpty()) {
                EmptyState(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    title = "폴더가 없습니다",
                    subtitle = "오른쪽 아래 + 버튼으로 폴더를 추가하세요"
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(folders, key = { it.name }) { folder ->
                        FolderCard(
                            folder = folder,
                            onClick = { onFolderClick(folder.name) },
                            onLongClick = { onFolderLongClick(folder.name) }
                        )
                    }
                }
            }
        }

        BottomActionBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            onAddFolderClick = onAddFolderClick,
            onRecordClick = onRecordClick,
            recordEnabled = true
        )
    }
}

@Composable
fun FolderCard(folder: FolderInfo, onClick: () -> Unit, onLongClick: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showMenu = true }
            )
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = folder.name,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "녹음파일 ${folder.recordingCount}개",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = TextSecondary
                )
            }

            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text("이름 변경") },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    onClick = {
                        showMenu = false
                        onLongClick()
                    }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------
// 폴더 상세 화면 (녹음파일 목록 + 녹음)
// ---------------------------------------------------------------------------------
@Composable
fun FolderDetailScreen(
    recordings: List<RecordingItem>,
    playingRecordingName: String?,
    isRecording: Boolean,
    elapsedSeconds: Int,
    onRecordingClick: (RecordingItem) -> Unit,
    onRecordingLongClick: (RecordingItem) -> Unit,
    onRecordClick: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (recordings.isEmpty() && !isRecording) {
            EmptyState(
                modifier = Modifier.align(Alignment.Center),
                title = "녹음파일이 없습니다",
                subtitle = "아래 녹음 버튼을 눌러 녹음을 시작하세요"
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 160.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(recordings, key = { it.displayName }) { item ->
                    RecordingCard(
                        item = item,
                        isPlaying = playingRecordingName == item.displayName,
                        onClick = { onRecordingClick(item) },
                        onLongClick = { onRecordingLongClick(item) }
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedVisibility(visible = isRecording) {
                RecordingIndicator(elapsedSeconds = elapsedSeconds)
            }
            Spacer(modifier = Modifier.height(12.dp))
            RecordFab(isRecording = isRecording, onClick = onRecordClick)
            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
fun RecordingCard(
    item: RecordingItem,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    val dateText = remember(item.dateAddedMillis) {
        SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault()).format(Date(item.dateAddedMillis))
    }
    val sizeText = remember(item.sizeBytes) {
        val kb = item.sizeBytes / 1024
        if (kb < 1024) "${kb}KB" else String.format(Locale.getDefault(), "%.1fMB", kb / 1024.0)
    }
    val nameWithoutExtension = remember(item.displayName) {
        item.displayName.substringBeforeLast('.', item.displayName)
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isPlaying) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showMenu = true }
            )
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "재생 중" else "재생",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = nameWithoutExtension,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1
                    )
                    Text(
                        text = "$dateText  ·  $sizeText",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }

            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text("이름 변경") },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    onClick = {
                        showMenu = false
                        onLongClick()
                    }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------
// 공통 UI 요소들
// ---------------------------------------------------------------------------------
@Composable
fun BottomActionBar(
    modifier: Modifier = Modifier,
    onAddFolderClick: () -> Unit,
    onRecordClick: () -> Unit,
    recordEnabled: Boolean
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 28.dp, start = 32.dp, end = 32.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        FloatingActionButton(
            onClick = onAddFolderClick,
            containerColor = Color.White,
            contentColor = MaterialTheme.colorScheme.primary,
            shape = CircleShape,
            modifier = Modifier.size(56.dp)
        ) {
            Icon(Icons.Default.CreateNewFolder, contentDescription = "폴더 추가")
        }

        RecordFab(isRecording = false, onClick = onRecordClick, enabled = recordEnabled)
    }
}

@Composable
fun RecordFab(isRecording: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = if (isRecording) RecordingRed else MaterialTheme.colorScheme.primary,
        contentColor = Color.White,
        shape = CircleShape,
        modifier = Modifier.size(72.dp)
    ) {
        Icon(
            imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
            contentDescription = if (isRecording) "녹음 정지" else "녹음 시작",
            modifier = Modifier.size(30.dp)
        )
    }
}

@Composable
fun RecordingIndicator(elapsedSeconds: Int) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val minutes = elapsedSeconds / 60
    val seconds = elapsedSeconds % 60
    val timeText = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

    Surface(
        shape = RoundedCornerShape(50),
        color = RecordingRed.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(RecordingRed.copy(alpha = alpha))
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "녹음 중 $timeText",
                color = RecordingRed,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun EmptyState(modifier: Modifier = Modifier, title: String, subtitle: String) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.FolderOpen,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = title, style = MaterialTheme.typography.titleMedium, color = TextSecondary)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}

@Composable
fun InputDialog(
    title: String,
    initialValue: String = "",
    placeholder: String = "이름",
    confirmLabel: String = "확인",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text(placeholder) },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}
