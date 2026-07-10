@file:OptIn(ExperimentalMaterial3Api::class)

package com.recorder.voicenote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import java.io.File
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

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    // 권한 승인 후 바로 녹음을 시작하기 위한 플래그
    var pendingRecordAfterPermission by remember { mutableStateOf(false) }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
        if (granted && pendingRecordAfterPermission) {
            viewModel.startRecording()
        }
        pendingRecordAfterPermission = false
    }

    fun requestRecordOrStart() {
        if (hasAudioPermission) {
            viewModel.startRecording()
        } else {
            pendingRecordAfterPermission = true
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
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
                        text = uiState.selectedFolder?.name ?: "내 녹음",
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
                    onFolderClick = { viewModel.openFolder(it) },
                    onAddFolderClick = { viewModel.openAddFolderDialog() },
                    onRecordClick = { requestRecordOrStart() }
                )
            } else {
                FolderDetailScreen(
                    recordings = uiState.recordings,
                    isRecording = uiState.isRecording,
                    elapsedSeconds = uiState.elapsedSeconds,
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
                AddFolderDialog(
                    onDismiss = { viewModel.dismissAddFolderDialog() },
                    onConfirm = { name -> viewModel.confirmAddFolder(name) }
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
    folders: List<File>,
    onFolderClick: (File) -> Unit,
    onAddFolderClick: () -> Unit,
    onRecordClick: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (folders.isEmpty()) {
            EmptyState(
                modifier = Modifier.align(Alignment.Center),
                title = "폴더가 없습니다",
                subtitle = "오른쪽 아래 + 버튼으로 폴더를 추가하세요"
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(folders, key = { it.absolutePath }) { folder ->
                    FolderCard(folder = folder, onClick = { onFolderClick(folder) })
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
fun FolderCard(folder: File, onClick: () -> Unit) {
    val fileCount = folder.listFiles { f -> f.isFile }?.size ?: 0
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
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
                    text = "녹음파일 ${fileCount}개",
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
    }
}

// ---------------------------------------------------------------------------------
// 폴더 상세 화면 (녹음파일 목록 + 녹음)
// ---------------------------------------------------------------------------------
@Composable
fun FolderDetailScreen(
    recordings: List<File>,
    isRecording: Boolean,
    elapsedSeconds: Int,
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
                items(recordings, key = { it.absolutePath }) { file ->
                    RecordingCard(file = file)
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
fun RecordingCard(file: File) {
    val dateText = remember(file) {
        SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
    }
    val sizeText = remember(file) {
        val kb = file.length() / 1024
        if (kb < 1024) "${kb}KB" else String.format(Locale.getDefault(), "%.1fMB", kb / 1024.0)
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
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
                    Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.nameWithoutExtension,
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
        horizontalAlignment = Alignment.CenterHorizontally
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
fun AddFolderDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("새 폴더", fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("폴더 이름") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text("추가")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}
