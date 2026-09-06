@file:OptIn(ExperimentalMaterial3Api::class)

package com.recorder.voicenote

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

/** 웹의 모달들을 그대로 옮긴 다이얼로그 모음. 문구도 웹과 같은 것을 쓴다. */

@Composable
fun NewFolderDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("새 폴더 만들기", fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("폴더 이름을 입력하세요") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onCreate(name) }) { Text("만들기") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
fun DeleteFolderDialog(
    folder: DagloFolder,
    onDismiss: () -> Unit,
    onDelete: (withBoards: Boolean) -> Unit
) {
    val hasBoards = folder.boardCount > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("'${folder.name}' 폴더 삭제", fontWeight = FontWeight.Bold) },
        text = {
            Text(
                text = if (hasBoards) {
                    "이 폴더에 보드 ${folder.boardCount}개가 있습니다. 보드를 어떻게 할지 고르세요. " +
                        "원본 녹음 파일과 변환 텍스트도 보드를 따라 함께 옮겨집니다. " +
                        "휴지통으로 보낸 보드는 휴지통에서 되돌릴 수 있습니다."
                } else {
                    "빈 폴더입니다. 삭제하면 탐색기의 폴더도 함께 정리됩니다."
                },
                fontSize = 13.5.sp,
                color = DagloColors.TextMuted
            )
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onDelete(false) }) {
                    Text(if (hasBoards) "보드는 기본 폴더로" else "삭제")
                }
                if (hasBoards) {
                    TextButton(onClick = { onDelete(true) }) {
                        Text("보드도 휴지통으로", color = DagloColors.Danger)
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
fun UploadDialog(
    folders: List<DagloFolder>,
    defaultFolderId: Int?,
    onDismiss: () -> Unit,
    onUpload: (Uri, Int?) -> Unit
) {
    var selectedFolderId by remember { mutableStateOf(defaultFolderId) }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var pickedName by remember { mutableStateOf("클릭하여 파일을 선택하세요") }
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            pickedUri = uri
            pickedName = fileDisplayName(context, uri)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("새 오디오 받아쓰기", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "녹음 파일(.m4a, .mp3, .wav, .mp4)을 업로드하면 Gemini가 자동으로 STT 변환합니다.",
                    fontSize = 13.sp,
                    color = DagloColors.TextMuted
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text("저장할 폴더", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DagloColors.TextMuted)
                Spacer(modifier = Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 150.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    folders.forEach { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (selectedFolderId == folder.id) DagloColors.PrimaryLight else Color.Transparent,
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable { selectedFolderId = folder.id }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Folder, null, tint = DagloColors.Warning, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(folder.name, fontSize = 13.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, DagloColors.BorderHover, RoundedCornerShape(10.dp))
                        .clickable { picker.launch("audio/*") }
                        .padding(vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.UploadFile, null, tint = DagloColors.TextSubtle, modifier = Modifier.size(30.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(pickedName, fontSize = 12.5.sp, color = DagloColors.TextMuted)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { pickedUri?.let { onUpload(it, selectedFolderId) } },
                enabled = pickedUri != null
            ) { Text("업로드 및 변환 시작") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
fun BatchMoveDialog(
    folders: List<DagloFolder>,
    count: Int,
    onDismiss: () -> Unit,
    onMove: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("보드 ${count}개 이동", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                Text("옮길 폴더를 고르세요.", fontSize = 13.sp, color = DagloColors.TextMuted)
                Spacer(modifier = Modifier.height(10.dp))
                folders.forEach { folder ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onMove(folder.id) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Folder, null, tint = DagloColors.Warning, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(folder.name, fontSize = 13.5.sp)
                        Spacer(modifier = Modifier.weight(1f))
                        Text("${folder.boardCount}", fontSize = 12.sp, color = DagloColors.TextSubtle)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

/** 웹의 confirm() 자리. 어떤 일이 벌어지는지 문구를 그대로 옮겼다. */
@Composable
fun BoardActionConfirmDialog(kind: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val (title, body, confirmLabel) = when (kind) {
        "retranscribe" -> Triple(
            "다시 받아쓰기",
            "원본 녹음으로 스크립트를 처음부터 다시 만듭니다.\n\n" +
                "· 지금 스크립트는 새 변환이 끝나는 순간 통째로 교체됩니다 (직접 고친 내용도 사라집니다)\n" +
                "· 키워드와 기본 요약도 새로 만들어집니다\n" +
                "· 북마크와 AI 대화 기록은 그대로 남습니다\n" +
                "· 변환이 끝나기 전까지는 기존 스크립트를 그대로 볼 수 있습니다",
            "계속"
        )
        "retry" -> Triple("변환 다시 시도", "변환에 실패한 녹음을 다시 변환 대기열에 넣습니다.", "다시 시도")
        "purge" -> Triple(
            "완전 삭제",
            "이 보드를 영구적으로 삭제하시겠습니까? 원본 녹음 파일과 변환 텍스트가 디스크에서 " +
                "완전히 지워집니다. 되돌릴 수 없습니다.",
            "영구 삭제"
        )
        else -> Triple(
            "휴지통으로 이동",
            "이 보드를 휴지통으로 이동하시겠습니까? 원본 녹음 파일도 휴지통 폴더로 함께 옮겨져 " +
                "탐색기에서 사라집니다. (복원하면 원래 자리로 되돌아옵니다)",
            "이동"
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(body, fontSize = 13.5.sp, color = DagloColors.TextMuted) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (kind == "purge" || kind == "trash") DagloColors.Danger else DagloColors.Primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
fun SpeakerRenameDialog(
    initialOldName: String,
    onDismiss: () -> Unit,
    onRename: (String, String) -> Unit
) {
    var oldName by remember { mutableStateOf(initialOldName) }
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("화자 이름 일괄 변경", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "스크립트 전체에서 특정 화자의 이름을 일괄 변경합니다.",
                    fontSize = 13.sp,
                    color = DagloColors.TextMuted
                )
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedTextField(
                    value = oldName,
                    onValueChange = { oldName = it },
                    label = { Text("변경할 화자") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("새 화자 이름") },
                    placeholder = { Text("예: 김교수님, 발표자") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(oldName, newName) },
                enabled = oldName.isNotBlank() && newName.isNotBlank()
            ) { Text("변경하기") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
fun ExportDialog(onDismiss: () -> Unit, onExport: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("스크립트 내보내기", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "원하는 파일 형식을 선택하면 기기의 다운로드 폴더에 저장됩니다.",
                    fontSize = 13.sp,
                    color = DagloColors.TextMuted
                )
                Spacer(modifier = Modifier.height(12.dp))
                ExportOption(Icons.Default.Description, "텍스트 문서 (.txt)", "타임스탬프 및 텍스트") { onExport("txt") }
                ExportOption(Icons.Default.Subtitles, "자막 파일 (.srt)", "동영상 자막 싱크용") { onExport("srt") }
                ExportOption(Icons.Default.Description, "마크다운 노트 (.md)", "Notion / Obsidian 연동용") { onExport("md") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } }
    )
}

@Composable
private fun ExportOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(1.dp, DagloColors.Border, RoundedCornerShape(9.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = DagloColors.Primary, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 11.5.sp, color = DagloColors.TextSubtle)
        }
    }
}

/**
 * 단어장 모달. 폴더 전용/공통 두 벌을 한 줄에 하나씩 편집한다.
 * 저장만으로는 기존 스크립트가 바뀌지 않으므로, 바로 다시 받아쓰기까지 이어갈 수 있게 뒀다.
 */
@Composable
fun GlossaryDialog(
    glossary: DagloGlossary,
    canRetranscribe: Boolean,
    onDismiss: () -> Unit,
    onSave: (folderText: String, commonText: String, thenRetranscribe: Boolean) -> Unit
) {
    var folderText by remember { mutableStateOf(glossary.folderTerms.toEditorText()) }
    var commonText by remember { mutableStateOf(glossary.commonTerms.toEditorText()) }
    val total = folderText.toGlossaryTerms().size + commonText.toGlossaryTerms().size

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.width(560.dp),
        title = { Text("단어장", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "받아쓰기할 때 AI가 이 표기를 그대로 쓰도록 알려 줍니다. 한 줄에 하나씩 적고, " +
                        "설명이 필요하면 `단어 | 메모` 처럼 세로줄 뒤에 덧붙이세요. " +
                        "등록 후 다시 받아쓰기를 해야 스크립트에 반영됩니다.",
                    fontSize = 12.5.sp,
                    color = DagloColors.TextMuted
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = glossary.folderName?.let { "'$it' 폴더 전용" }
                        ?: "폴더 전용 (이 보드는 폴더에 속해 있지 않습니다)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DagloColors.TextMuted
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = folderText,
                    onValueChange = { folderText = it },
                    enabled = glossary.folderId != null,
                    placeholder = { Text("퍼지 로직 | fuzzy logic\nIB | 투자은행", fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth().height(150.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("모든 폴더 공통", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DagloColors.TextMuted)
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = commonText,
                    onValueChange = { commonText = it },
                    placeholder = { Text("모든 녹음에 공통으로 적용할 단어", fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth().height(110.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (total > glossary.maxTerms)
                        "단어 ${total}개 — 프롬프트에는 앞에서부터 ${glossary.maxTerms}개까지만 들어갑니다."
                    else "단어 ${total}개",
                    fontSize = 11.5.sp,
                    color = DagloColors.TextSubtle
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onSave(folderText, commonText, false) }) { Text("저장") }
                if (canRetranscribe) {
                    TextButton(onClick = { onSave(folderText, commonText, true) }) {
                        Text("저장하고 다시 받아쓰기", fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

/** 웹은 브라우저 다운로드로 내보낸다. 앱에서는 인증 헤더를 실어 다운로드 관리자에 넘긴다. */
fun downloadExport(context: Context, client: DagloClient, boardId: Int, title: String, format: String) {
    runCatching {
        val request = DownloadManager.Request(Uri.parse(client.exportUrl(boardId, format))).apply {
            client.authHeaders.forEach { (k, v) -> addRequestHeader(k, v) }
            val fileName = "${title.replace(Regex("[\\\\/:*?\"<>|]"), "_")}.$format"
            setTitle(fileName)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        }
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }
}

private fun fileDisplayName(context: Context, uri: Uri): String {
    val name = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
    return name ?: uri.lastPathSegment ?: "recording.m4a"
}
