@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.recorder.voicenote

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.recorder.voicenote.ui.theme.RecordingRed
import com.recorder.voicenote.ui.theme.TextSecondary
import com.recorder.voicenote.ui.theme.VoiceRecorderTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 앱 안에서 오가는 화면들. 화면 회전에도 유지해야 해서 저장 가능한 문자열로 둔다.
private const val SCREEN_RECORDER = "recorder"
private const val SCREEN_DAGLO = "daglo"
private const val SCREEN_SETTINGS = "settings"

// 이 너비부터는 폴더 목록과 녹음 목록을 좌우로 함께 띄운다.
// 갤럭시 탭 S8 은 가로 약 1280dp, 세로 약 800dp 라서 두 방향 모두 2단으로 열린다.
private val TWO_PANE_MIN_WIDTH = 720.dp

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

    // 앱을 처음 열 때뿐 아니라, 다른 화면에 갔다가 돌아올 때마다(ON_RESUME) 항상 새로고침한다.
    // 파일탐색기 등 앱 밖에서 폴더/파일을 조작했을 수 있기 때문.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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

    // 화면 전환 (녹음 / daglo 웹 / 서버 설정). 화면 회전에도 유지되도록 rememberSaveable 사용.
    var currentScreen by rememberSaveable { mutableStateOf(SCREEN_RECORDER) }

    // 태블릿처럼 넓은 화면이면 폴더 목록과 녹음 목록을 좌우로 함께 띄운다.
    // (멀티윈도우/DeX 에서 창을 줄이면 설정도 따라 바뀌므로 화면이 아니라 창 크기 기준이 된다)
    val isTwoPane = LocalConfiguration.current.screenWidthDp.dp >= TWO_PANE_MIN_WIDTH

    // 2단 화면에서는 오른쪽이 비어 있으면 녹음 버튼도 안 보인다. 기본 폴더를 열어 둔다.
    LaunchedEffect(isTwoPane) {
        if (isTwoPane) viewModel.selectDefaultFolderIfNone()
    }

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

    // 다른 앱이 만든 파일의 이름/위치를 바꾸려면(Android 11+) 시스템 승인 다이얼로그가 필요하다.
    val writeRequestLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onWriteRequestResult(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(uiState.pendingWriteRequest) {
        if (uiState.pendingWriteRequest != null) {
            val intentSender = viewModel.writeRequestIntentSender()
            if (intentSender != null) {
                writeRequestLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            } else {
                viewModel.onWriteRequestUnavailable()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            // 하위 화면(daglo 웹 / 설정)은 자기 상단바를 갖고 있으므로 여기서는 녹음 화면만 그린다.
            if (currentScreen == SCREEN_RECORDER) {
                TopAppBar(
                    title = {
                        Text(
                            text = uiState.selectedFolder ?: "내 녹음",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        if (uiState.selectedFolder != null && !isTwoPane) {
                            IconButton(
                                onClick = { viewModel.goBackToFolderList() },
                                enabled = !uiState.isRecording
                            ) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "뒤로가기")
                            }
                        }
                    },
                    actions = {
                        // 업로드가 도는 동안에는 그 사실을 상단바에 계속 보여 준다
                        if (uiState.uploadingName != null) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        IconButton(
                            onClick = {
                                if (uiState.serverConfigured) {
                                    currentScreen = SCREEN_DAGLO
                                } else {
                                    currentScreen = SCREEN_SETTINGS
                                }
                            }
                        ) {
                            Icon(Icons.Default.Language, contentDescription = "daglo 열기")
                        }
                        IconButton(onClick = { currentScreen = SCREEN_SETTINGS }) {
                            Icon(Icons.Default.Settings, contentDescription = "서버 설정")
                        }
                    }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (currentScreen) {
                SCREEN_SETTINGS -> ServerSettingsScreen(
                    initialServerUrl = uiState.serverUrl,
                    initialApiToken = uiState.apiToken,
                    initialAutoUpload = uiState.autoUpload,
                    isTesting = uiState.isTestingConnection,
                    onSave = { url, token, auto -> viewModel.saveServerSettings(url, token, auto) },
                    onTest = { url, token -> viewModel.testServerConnection(url, token) },
                    onBack = { currentScreen = SCREEN_RECORDER }
                )

                SCREEN_DAGLO -> DagloWebScreen(
                    serverUrl = uiState.serverUrl,
                    onBack = { currentScreen = SCREEN_RECORDER }
                )

                else -> RecorderContent(
                    uiState = uiState,
                    storageLocationLabel = viewModel.storageLocationLabel,
                    viewModel = viewModel,
                    isTwoPane = isTwoPane,
                    onRecordOrRequest = { requestRecordOrStart() }
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

            val deleteFolderTarget = uiState.deleteFolderTarget
            if (deleteFolderTarget != null) {
                val recordingCount = uiState.folders.find { it.name == deleteFolderTarget }?.recordingCount ?: 0
                AlertDialog(
                    onDismissRequest = { viewModel.dismissDeleteFolder() },
                    title = { Text("폴더 삭제", fontWeight = FontWeight.Bold) },
                    text = {
                        Text(
                            if (recordingCount > 0) {
                                "'$deleteFolderTarget' 폴더와 안에 있는 녹음파일 ${recordingCount}개를 삭제할까요?\n이 작업은 되돌릴 수 없습니다."
                            } else {
                                "'$deleteFolderTarget' 폴더를 삭제할까요?\n이 작업은 되돌릴 수 없습니다."
                            }
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { viewModel.confirmDeleteFolder() }) {
                            Text("삭제", color = RecordingRed)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.dismissDeleteFolder() }) {
                            Text("취소")
                        }
                    }
                )
            }

            val deleteRecordingTarget = uiState.deleteRecordingTarget
            if (deleteRecordingTarget != null) {
                val displayName = deleteRecordingTarget.displayName
                    .substringBeforeLast('.', deleteRecordingTarget.displayName)
                AlertDialog(
                    onDismissRequest = { viewModel.dismissDeleteRecording() },
                    title = { Text("녹음파일 삭제", fontWeight = FontWeight.Bold) },
                    text = { Text("'$displayName' 파일을 삭제할까요?\n이 작업은 되돌릴 수 없습니다.") },
                    confirmButton = {
                        TextButton(onClick = { viewModel.confirmDeleteRecording() }) {
                            Text("삭제", color = RecordingRed)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.dismissDeleteRecording() }) {
                            Text("취소")
                        }
                    }
                )
            }

            if (uiState.showStopConfirm) {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissStopConfirm() },
                    title = { Text("녹음 정지", fontWeight = FontWeight.Bold) },
                    text = { Text("정말 녹음을 정지하시겠습니까?") },
                    confirmButton = {
                        TextButton(onClick = { viewModel.confirmStopRecording() }) {
                            Text("정지", color = RecordingRed)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.dismissStopConfirm() }) {
                            Text("취소")
                        }
                    }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------
// 녹음 화면 본문 (화면 크기에 따라 1단/2단)
// ---------------------------------------------------------------------------------
/**
 * 폰에서는 지금까지처럼 폴더 목록 -> 녹음 목록으로 한 화면씩 넘어가고,
 * 태블릿처럼 넓은 화면에서는 둘을 좌우로 함께 보여 준다.
 * 넓은 화면에서 한 번에 한 목록만 띄우면 화면 절반이 비어 버리기 때문이다.
 */
@Composable
private fun RecorderContent(
    uiState: RecorderUiState,
    storageLocationLabel: String,
    viewModel: RecorderViewModel,
    isTwoPane: Boolean,
    onRecordOrRequest: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (isTwoPane) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.width(340.dp)) {
                    FolderListScreen(
                        folders = uiState.folders,
                        storageLocationLabel = storageLocationLabel,
                        onFolderClick = { viewModel.openFolder(it) },
                        onFolderLongClick = { viewModel.requestRenameFolder(it) },
                        onFolderDeleteClick = { viewModel.requestDeleteFolder(it) },
                        onAddFolderClick = { viewModel.openAddFolderDialog() },
                        onRecordClick = onRecordOrRequest,
                        showRecordButton = false
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                Box(modifier = Modifier.weight(1f)) {
                    if (uiState.selectedFolder == null) {
                        EmptyState(
                            modifier = Modifier.align(Alignment.Center),
                            title = "폴더를 선택하세요",
                            subtitle = "왼쪽에서 폴더를 고르면 녹음 목록이 열립니다"
                        )
                    } else {
                        FolderDetailScreen(
                            recordings = uiState.recordings,
                            playingRecordingName = uiState.playingRecordingName,
                            isRecording = uiState.isRecording,
                            isPaused = uiState.isPaused,
                            isSaving = uiState.isSaving,
                            level = uiState.level,
                            elapsedSeconds = uiState.elapsedSeconds,
                            onRecordingClick = { viewModel.onRecordingClick(it) },
                            onRecordingLongClick = { viewModel.requestRenameRecording(it) },
                            onRecordingDeleteClick = { viewModel.requestDeleteRecording(it) },
                            onRecordingUploadClick = { viewModel.uploadRecording(it) },
                            onPauseClick = { viewModel.pauseRecording() },
                            onResumeClick = { viewModel.resumeRecording() },
                            onRecordClick = {
                                if (uiState.isRecording) {
                                    viewModel.requestStopRecording()
                                } else {
                                    onRecordOrRequest()
                                }
                            }
                        )
                    }
                }
            }
        } else {
            if (uiState.selectedFolder == null) {
                FolderListScreen(
                    folders = uiState.folders,
                    storageLocationLabel = storageLocationLabel,
                    onFolderClick = { viewModel.openFolder(it) },
                    onFolderLongClick = { viewModel.requestRenameFolder(it) },
                    onFolderDeleteClick = { viewModel.requestDeleteFolder(it) },
                    onAddFolderClick = { viewModel.openAddFolderDialog() },
                    onRecordClick = onRecordOrRequest
                )
            } else {
                FolderDetailScreen(
                    recordings = uiState.recordings,
                    playingRecordingName = uiState.playingRecordingName,
                    isRecording = uiState.isRecording,
                    isPaused = uiState.isPaused,
                    isSaving = uiState.isSaving,
                    level = uiState.level,
                    elapsedSeconds = uiState.elapsedSeconds,
                    onRecordingClick = { viewModel.onRecordingClick(it) },
                    onRecordingLongClick = { viewModel.requestRenameRecording(it) },
                    onRecordingDeleteClick = { viewModel.requestDeleteRecording(it) },
                    onRecordingUploadClick = { viewModel.uploadRecording(it) },
                    onPauseClick = { viewModel.pauseRecording() },
                    onResumeClick = { viewModel.resumeRecording() },
                    onRecordClick = {
                        if (uiState.isRecording) {
                            viewModel.requestStopRecording()
                        } else {
                            onRecordOrRequest()
                        }
                    }
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
    onFolderDeleteClick: (String) -> Unit,
    onAddFolderClick: () -> Unit,
    onRecordClick: () -> Unit,
    // 태블릿 2단 화면에서는 왼쪽(폴더 목록)에 녹음 버튼을 두지 않는다.
    // 녹음은 오른쪽에서 실제로 열려 있는 폴더에 하는 것이라 헷갈리지 않게 한 곳에만 둔다.
    showRecordButton: Boolean = true
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
                            onLongClick = { onFolderLongClick(folder.name) },
                            onDeleteClick = { onFolderDeleteClick(folder.name) }
                        )
                    }
                }
            }
        }

        BottomActionBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            onAddFolderClick = onAddFolderClick,
            onRecordClick = onRecordClick,
            recordEnabled = true,
            showRecordButton = showRecordButton
        )
    }
}

@Composable
fun FolderCard(
    folder: FolderInfo,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
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
                DropdownMenuItem(
                    text = { Text("삭제", color = RecordingRed) },
                    leadingIcon = {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = RecordingRed)
                    },
                    onClick = {
                        showMenu = false
                        onDeleteClick()
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
    isPaused: Boolean,
    isSaving: Boolean,
    level: Float,
    elapsedSeconds: Int,
    onRecordingClick: (RecordingItem) -> Unit,
    onRecordingLongClick: (RecordingItem) -> Unit,
    onRecordingDeleteClick: (RecordingItem) -> Unit,
    onRecordingUploadClick: (RecordingItem) -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
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
                        onLongClick = { onRecordingLongClick(item) },
                        onDeleteClick = { onRecordingDeleteClick(item) },
                        onUploadClick = { onRecordingUploadClick(item) }
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
            AnimatedVisibility(visible = isRecording || isSaving) {
                RecordingIndicator(
                    elapsedSeconds = elapsedSeconds,
                    isPaused = isPaused,
                    isSaving = isSaving,
                    level = level
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // 녹음 중에만 일시정지 버튼을 띄운다. 쉬는 시간에 파일을 나누지 않고 이어 갈 수 있다.
                if (isRecording) {
                    PauseResumeFab(isPaused = isPaused, onClick = {
                        if (isPaused) onResumeClick() else onPauseClick()
                    })
                }
                RecordFab(
                    isRecording = isRecording,
                    onClick = onRecordClick,
                    enabled = !isSaving
                )
            }
            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
fun RecordingCard(
    item: RecordingItem,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onUploadClick: () -> Unit
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
                // 자동 업로드가 꺼져 있거나, 서버가 꺼져 있어 실패했던 파일을 다시 올릴 때 쓴다.
                DropdownMenuItem(
                    text = { Text("daglo 서버로 보내기") },
                    leadingIcon = { Icon(Icons.Default.CloudUpload, contentDescription = null) },
                    onClick = {
                        showMenu = false
                        onUploadClick()
                    }
                )
                DropdownMenuItem(
                    text = { Text("삭제", color = RecordingRed) },
                    leadingIcon = {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = RecordingRed)
                    },
                    onClick = {
                        showMenu = false
                        onDeleteClick()
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
    recordEnabled: Boolean,
    showRecordButton: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 28.dp, start = 32.dp, end = 32.dp),
        horizontalArrangement = if (showRecordButton) Arrangement.SpaceBetween else Arrangement.Start,
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

        if (showRecordButton) {
            RecordFab(isRecording = false, onClick = onRecordClick, enabled = recordEnabled)
        }
    }
}

@Composable
fun RecordFab(isRecording: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    FloatingActionButton(
        // FloatingActionButton 에는 enabled 가 없어서 클릭 자체를 막고 색을 흐리게 해서 표시한다
        onClick = { if (enabled) onClick() },
        containerColor = when {
            !enabled -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            isRecording -> RecordingRed
            else -> MaterialTheme.colorScheme.primary
        },
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
fun RecordingIndicator(
    elapsedSeconds: Int,
    isPaused: Boolean,
    isSaving: Boolean,
    level: Float
) {
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

    val hours = elapsedSeconds / 3600
    val minutes = (elapsedSeconds % 3600) / 60
    val seconds = elapsedSeconds % 60
    val timeText = if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
    val label = when {
        isSaving -> "저장 중"
        isPaused -> "일시정지 $timeText"
        else -> "녹음 중 $timeText"
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = RecordingRed.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                    color = RecordingRed
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(RecordingRed.copy(alpha = if (isPaused) 0.35f else alpha))
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                color = RecordingRed,
                fontWeight = FontWeight.SemiBold
            )
            // 마이크가 실제로 소리를 받고 있는지 눈으로 확인할 수 있게 입력 레벨을 보여 준다.
            // (마이크가 막혀 3시간을 무음으로 녹음하는 사고를 막는다)
            if (!isSaving) {
                Spacer(modifier = Modifier.width(10.dp))
                LevelMeter(level = if (isPaused) 0f else level)
            }
        }
    }
}

@Composable
private fun LevelMeter(level: Float) {
    val animated by animateFloatAsState(
        targetValue = level.coerceIn(0f, 1f),
        animationSpec = tween(150),
        label = "level"
    )
    Box(
        modifier = Modifier
            .width(64.dp)
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(RecordingRed.copy(alpha = 0.18f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animated)
                .fillMaxHeight()
                .clip(RoundedCornerShape(3.dp))
                .background(RecordingRed)
        )
    }
}

@Composable
fun PauseResumeFab(isPaused: Boolean, onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = Color.White,
        contentColor = RecordingRed,
        shape = CircleShape,
        modifier = Modifier.size(56.dp)
    ) {
        Icon(
            imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
            contentDescription = if (isPaused) "녹음 재개" else "녹음 일시정지"
        )
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
