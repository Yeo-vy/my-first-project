@file:OptIn(ExperimentalMaterial3Api::class)

package com.recorder.voicenote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 앱 로그인 화면. 웹의 `server/static/login.html` 과 같은 자리이고, 같은 계정을 쓴다.
 *
 * 로그인에 성공하면 서버가 `daglo_session` 쿠키를 내려주고, 앱은 그 값을 저장해 이후 모든
 * 요청에 실어 보낸다(웹 브라우저가 하는 일과 같다). 서버에 계정이 하나도 없으면 웹과 마찬가지로
 * 첫 관리자 계정을 만드는 화면이 뜬다.
 */
@Composable
fun DagloLoginScreen(
    state: DagloUiState,
    onLogin: (String, String) -> Unit,
    onSetup: (String, String, String) -> Unit,
    onOpenSettings: () -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    val setup = state.setupRequired

    fun submit() {
        if (setup) onSetup(username, password, displayName) else onLogin(username, password)
    }

    Box(
        modifier = Modifier.fillMaxSize().background(DagloColors.BgMain),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(380.dp)
                .verticalScroll(rememberScrollState())
                .background(DagloColors.BgCard, RoundedCornerShape(16.dp))
                .padding(horizontal = 28.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier.size(52.dp).background(DagloColors.Primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("da", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = if (setup) "첫 관리자 계정 만들기" else "daglo 로그인",
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = DagloColors.TextMain
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (setup) "이 서버에 아직 계정이 없습니다. 웹에서도 이 계정으로 로그인합니다."
                else "웹과 같은 아이디·비밀번호를 쓰세요.",
                fontSize = 12.5.sp,
                color = DagloColors.TextMuted
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = state.serverUrl.removePrefix("http://").removePrefix("https://"),
                fontSize = 11.5.sp,
                color = DagloColors.TextSubtle
            )

            Spacer(modifier = Modifier.height(20.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("아이디") },
                singleLine = true,
                enabled = !state.authBusy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )
            if (setup) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("표시 이름 (선택)") },
                    singleLine = true,
                    enabled = !state.authBusy,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(if (setup) "비밀번호 (8자 이상)" else "비밀번호") },
                singleLine = true,
                enabled = !state.authBusy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth()
            )

            state.authError?.let { error ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(error, fontSize = 12.5.sp, color = DagloColors.Danger)
            }

            Spacer(modifier = Modifier.height(20.dp))
            if (state.authBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.5.dp,
                    color = DagloColors.Primary
                )
            } else {
                DagloPrimaryButton(
                    text = if (setup) "계정 만들고 시작하기" else "로그인",
                    onClick = { submit() },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                TextButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, null, modifier = Modifier.size(15.dp), tint = DagloColors.TextMuted)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("서버 주소 / 로그인 주소 설정", fontSize = 12.sp, color = DagloColors.TextMuted)
                }
            }
        }
    }
}
