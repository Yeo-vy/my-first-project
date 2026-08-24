# 강의 녹음 파이프라인 개선 — 구현 계획

이전 분석에서 식별된 버그·리스크를 직접 코드 확인 후 검증했습니다. 코드 수정이 가능한 항목을 우선순위별로 정리하고, 각 변경 사항을 구체적으로 제안합니다.

---

## User Review Required

> [!IMPORTANT]
> **MiraeAsset_AIFestival/** 디렉토리가 현재 워크스페이스에 없습니다. 이전 분석은 Y: 드라이브(네트워크 드라이브)에서 수행된 것으로 보이며, 현재 `c:\Users\pij19\Desktop\Codes\daglo 구현하기`에는 해당 폴더가 존재하지 않습니다. 마감이 걸린 프로젝트이므로 별도로 확인해 주세요.

> [!WARNING]
> **systemd 로케일 설정(`LANG=C.UTF-8`)은 서버 직접 설정이 필요합니다.** 코드 수정이 아니라 Azure VM의 `file-watcher.service` 유닛 파일을 편집해야 합니다. 아래 명령어를 서버에서 실행하세요:
> ```bash
> sudo systemctl edit file-watcher.service
> # [Service] 섹션에 추가:
> # Environment=LANG=C.UTF-8
> # Environment=LC_ALL=C.UTF-8
> # Environment=PYTHONUTF8=1
> sudo systemctl daemon-reload && sudo systemctl restart file-watcher.service
> ```

## Open Questions

1. **청크 길이를 몇 분으로 줄이시겠습니까?** 현재 60분 → **20분**(권장) 또는 15분? 청크가 짧을수록 `finish_reason` 잘림 위험이 줄지만, API 호출 횟수가 늘어납니다. 세이브포인트가 있으므로 실질적 손해는 없습니다.
2. **녹음 비트레이트를 어느 수준으로 낮추시겠습니까?** 현재 AAC 128kbps/44.1kHz → **AAC 64kbps/16kHz 모노**(권장, 음성에 충분) 또는 32kbps? 파일 크기가 1/4~1/8로 줄어 SFTP 전송과 Gemini 업로드가 빨라집니다.
3. **청크 오버랩을 추가하시겠습니까?** 30초 오버랩 + 중복 제거 로직을 넣으면 청크 경계 문장 잘림이 해소되지만, 구현 복잡도가 올라갑니다.

---

## Proposed Changes

### 1. 받아쓰기py.py — 핵심 버그 수정 (우선순위: 높음)

#### [MODIFY] [받아쓰기py.py](file:///c:/Users/pij19/Desktop/Codes/daglo%20구현하기/받아쓰기py.py)

**변경 1: `finish_reason` 검사 추가 (Line 138~150)**
- `response.candidates[0].finish_reason`을 확인하여 `MAX_TOKENS`, `SAFETY`, `RECITATION` 등 비정상 종료 시 해당 청크를 저장하지 않고 재시도 또는 에러 처리
- 잘린 텍스트가 캐시에 남아 영구적으로 손실되는 **가장 위험한 버그** 해결

**변경 2: `CHUNK_LENGTH_MS` 축소 (Line 77)**
- `60 * 60 * 1000` → `20 * 60 * 1000` (20분)
- 출력 토큰 한도 초과 확률을 크게 줄임

**변경 3: `html.escape` 적용 (Line 1, 180~189)**
- `import html` 추가
- `text_only` 및 `line`을 `html.escape()`로 감싸서 `<`, `>`, `&` 등이 포함된 텍스트로 인한 HTML 깨짐 방지
- `file_base_name`도 `<title>` 및 JS 변수에 삽입 시 이스케이프

**변경 4: MP3 재인코딩 최적화 (Line 122)**
- `chunk.export(temp_chunk_path, format="mp3")` → 다운샘플링 + 저비트레이트 적용
- `chunk.set_frame_rate(16000).set_channels(1).export(temp_chunk_path, format="mp3", bitrate="32k")`
- STT 목적에 충분하며, 업로드 용량·시간이 크게 감소

**변경 5: 업로드 파일 정리 (Line 141 이후)**
- `client.files.delete(uploaded_file.name)` 호출 추가 (try-except로 감싸서 실패해도 진행)

---

### 2. 받아쓰기py.py — 품질 개선 (우선순위: 중간)

#### [MODIFY] [받아쓰기py.py](file:///c:/Users/pij19/Desktop/Codes/daglo%20구현하기/받아쓰기py.py)

**변경 6: 청크 오버랩 (선택)**
- 30초 오버랩으로 청크 분할 후, 오버랩 구간의 중복 타임스탬프 라인 제거
- Open Question 3의 답변에 따라 적용 여부 결정

**변경 7: 유료 키 폴백 (Line 16~24, 152~157)**
- `.env`에서 `GEMINI_API_KEY_FREE`, `GEMINI_API_KEY_PAID` 두 키를 읽음
- 429 에러 시 유료 키로 클라이언트를 재생성하여 재시도
- README에만 있던 TODO를 실제 구현

---

### 3. VoiceRecorder Android 앱 — 버그 수정 (우선순위: 중간)

#### [MODIFY] [RecordingStore.kt](file:///c:/Users/pij19/Desktop/Codes/daglo%20구현하기/app/src/main/java/com/recorder/voicenote/RecordingStore.kt)

**변경 8: `isSecurityRestricted()` 수정 (Line 627~630)**
- 현재: `Build.VERSION.SDK_INT >= R`만 반환 → 인자(`uri`, `column`, `value`)를 전혀 사용하지 않음
- 수정: `catch (e: RecoverableSecurityException)` 패턴으로 실제 보안 예외를 구분하도록 호출부를 리팩터링
- 또는 최소한 실제 `SecurityException`이 발생한 경우에만 `true`를 반환하도록 변경

**변경 9: `listFolders()` N+1 쿼리 최적화 (Line 127~140)**
- 현재: 폴더마다 `listRecordings(it).size` 호출 → 폴더 수만큼 MediaStore 쿼리 반복
- 수정: `GROUP BY RELATIVE_PATH` 한 번의 쿼리로 폴더별 녹음 개수를 한번에 취득

#### [MODIFY] [RecordingService.kt](file:///c:/Users/pij19/Desktop/Codes/daglo%20구현하기/app/src/main/java/com/recorder/voicenote/RecordingService.kt)

**변경 10: 타이머 드리프트 수정 (Line 148~155)**
- `delay(1000)` 누적 → `SystemClock.elapsedRealtime()` 기준 경과 시간 계산으로 변경
- 1~3시간 강의 녹음 시 doze/스로틀링에 의한 시간 어긋남 방지

#### [MODIFY] [RecorderViewModel.kt](file:///c:/Users/pij19/Desktop/Codes/daglo%20구현하기/app/src/main/java/com/recorder/voicenote/RecorderViewModel.kt)

**변경 11: init 블록 IO 디스패처 적용 (Line 66~69)**
- `store.migrateLegacyPrivateStorageIfNeeded()` 및 `refreshFolders()`를 `viewModelScope.launch(Dispatchers.IO)`로 감싸서 ANR 방지

#### [MODIFY] [RecorderManager.kt](file:///c:/Users/pij19/Desktop/Codes/daglo%20구현하기/app/src/main/java/com/recorder/voicenote/RecorderManager.kt)

**변경 12: 녹음 비트레이트 하향 (Line 32~33)**
- `setAudioEncodingBitRate(128000)` → `64000`
- `setAudioSamplingRate(44100)` → `16000`
- `setAudioChannelCount(1)` 추가 (모노)
- 강의 음성에 충분한 품질이며, 파일 크기 1/4로 감소

---

### 4. .gitignore 보강 (우선순위: 낮음)

#### [MODIFY] [.gitignore](file:///c:/Users/pij19/Desktop/Codes/daglo%20구현하기/.gitignore)

**변경 13: Gradle/Android 빌드 산출물 제외 추가**
```gitignore
# Android / Gradle
.gradle/
app/build/
build/
local.properties
*.apk
*.aab
```

---

## Verification Plan

### Automated Tests
- 빌드/테스트 실행은 현재 환경에서 직접 수행이 어렵습니다(네트워크 드라이브/Android 빌드 환경 필요).

### Manual Verification
- **받아쓰기py.py**: 서버에서 짧은 테스트 오디오(5분)로 실행하여 `finish_reason` 검사 로그, HTML 이스케이프, 파일 크기 감소 확인
- **VoiceRecorder**: Android Studio에서 빌드 후 에뮬레이터에서 30분 녹음 테스트로 타이머 정확도 확인
- **.gitignore**: `git status`로 새로 추가된 패턴이 정상 동작하는지 확인

---

## 작업 범위 요약

| # | 변경 | 파일 | 위험도 | 난이도 |
|---|------|------|--------|--------|
| 1 | `finish_reason` 검사 | 받아쓰기py.py | ⚠️ 높음 (데이터 유실) | 낮음 |
| 2 | 청크 길이 축소 | 받아쓰기py.py | ⚠️ 높음 | 매우 낮음 |
| 3 | HTML 이스케이프 | 받아쓰기py.py | 중간 | 낮음 |
| 4 | MP3 최적화 | 받아쓰기py.py | 낮음 | 낮음 |
| 5 | 업로드 파일 정리 | 받아쓰기py.py | 낮음 | 낮음 |
| 6 | 청크 오버랩 | 받아쓰기py.py | 낮음 | 중간 |
| 7 | 유료 키 폴백 | 받아쓰기py.py | 중간 | 중간 |
| 8 | `isSecurityRestricted` | RecordingStore.kt | 중간 | 중간 |
| 9 | N+1 쿼리 최적화 | RecordingStore.kt | 낮음 | 중간 |
| 10 | 타이머 드리프트 | RecordingService.kt | 중간 | 낮음 |
| 11 | IO 디스패처 | RecorderViewModel.kt | 중간 (ANR) | 낮음 |
| 12 | 녹음 비트레이트 | RecorderManager.kt | 낮음 | 매우 낮음 |
| 13 | .gitignore 보강 | .gitignore | 낮음 | 매우 낮음 |

> [!TIP]
> 변경 1~5와 10~13은 독립적이므로 병렬 적용 가능합니다. 승인 후 바로 코드 수정을 진행하겠습니다.
