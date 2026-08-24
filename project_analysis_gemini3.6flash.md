# 강의 녹음 & AI 받아쓰기 파이프라인 종합 분석 보고서

본 보고서는 프로젝트의 전체 아키텍처, 데이터 파이프라인 동작 원리, 코드 레벨 버그 및 성능 병목 요인, 그리고 개선 우선순위를 정리한 문서입니다.

---

## 1. 프로젝트 구조 및 시스템 흐름 (Architecture)

본 시스템은 **스마트폰 녹음 ➔ 자동 파일 동기화 ➔ 서버 감지 ➔ Gemini AI STT ➔ 웹/텍스트 스크립트 생성**으로 이어지는 완전 자동화된 파이프라인입니다.

```mermaid
flowchart TD
    subgraph Mobile["📱 안드로이드 앱 (VoiceRecorder)"]
        A[음성 녹음 실행] -->|Foreground Service| B[Recordings/Voice Recorder/[폴더명]/*.m4a]
    end

    subgraph Sync["☁️ 파일 동기화 / 서버 수신"]
        B -->|RaiDrive / SFTP 또는 Web| C[Azure VM: ~/my_files]
    end

    subgraph Watcher["⚙️ 서버 자동 감지 데몬"]
        C -->|inotifywait - close_write| D[Debounce 5s + Mutex Lock]
        D -->|트리거| E[받아쓰기py.py 백그라운드 실행]
    end

    subgraph AI["🤖 AI 받아쓰기 엔진"]
        E -->|pydub 오디오 분할| F[Gemini 2.5 Flash API]
        F -->|타임스탬프 보정 & 세이브포인트| G[강의 녹음 변환/[폴더명]/]
        G --> H1[*.txt 파일]
        G --> H2[*_강의스크립트.html 파일]
    end
```

---

## 2. 영역별 상세 문제점 및 리스크 분석

### 2.1 파이썬 받아쓰기 백엔드 ([받아쓰기py.py](file:///c:/Users/pij19/Desktop/Codes/daglo%20%EA%B5%AC%ED%98%84%ED%95%98%EA%B8%B0/%EB%B0%9B%EC%95%84%EC%93%B0%EA%B8%B0py.py))

> [!CAUTION]
> **1. Gemini `finish_reason` 검증 누락 (데이터 유실 리스크)**
> - `response.candidates[0].finish_reason`을 검사하지 않아, 한국어 강의 긴 분량 처리 중 Output Token Limit(`MAX_TOKENS`)에 걸려 답변이 중간에 끊기더라도 정상 처리로 간주됩니다.
> - 잘린 텍스트가 `_chunk_N.txt` 세이브포인트에 저장되므로, **받아쓰기 내용 일부가 조용히 유실**되는 위험이 존재합니다.

> [!WARNING]
> **2. 청크 단위 과다 (60분)**
> - `CHUNK_LENGTH_MS = 60 * 60 * 1000` (60분) 설정은 1시간 청크당 생성되는 한국어 토큰 수가 많아 Gemini 토큰 제한에 걸릴 확률이 높습니다. 15~20분 단위로 줄여 안정성을 높여야 합니다.

> [!NOTE]
> **3. 기타 백엔드 개선 필요 항목**
> - **HTML 이스케이프 누락**: 받아쓰기 결과 텍스트에 `<`, `>`, `&` 포함 시 HTML 문서 렌더링이 깨집니다 (`html.escape()` 필요).
> - **Gemini 임시 파일 미삭제**: `client.files.upload()`로 업로드한 오디오 파일을 명시적으로 `client.files.delete()` 하지 않아 클라우드 자원이 낭비됩니다.
> - **API 키 자동 전환(Fallback) 미구현**: 무료 키(Free tier) 429 Too Many Requests 발생 시 유료 키로 자동 전환하는 로직이 주석/README에만 있고 구현되어 있지 않습니다.

---

### 2.2 안드로이드 녹음 앱 (`VoiceRecorder`)

> [!IMPORTANT]
> **1. Audio Recording 비트레이트 과다 설정**
> - 현재 [RecorderManager.kt](file:///c:/Users/pij19/Desktop/Codes/daglo%20%EA%B5%AC%ED%98%84%ED%95%98%EA%B8%B0/app/src/main/java/com/recorder/voicenote/RecorderManager.kt)에 AAC 128kbps / 44.1kHz (음악용 스펙)로 설정되어 있습니다.
> - 강의 음성 STT 목적에는 **64kbps / 16kHz 모노**로 변경해도 인식률 차이가 없으며, **파일 용량이 1/4로 줄어들어** SFTP 전송 및 AI 업로드 시간이 크게 단축됩니다.

> [!WARNING]
> **2. 타이머 드리프트 (Timer Drift)**
> - [RecordingService.kt](file:///c:/Users/pij19/Desktop/Codes/daglo%20%EA%B5%AC%ED%98%84%ED%95%98%EA%B8%B0/app/src/main/java/com/recorder/voicenote/RecordingService.kt)의 타이머가 `delay(1000)` 단순 누적 방식입니다.
> - 1~3시간 장시간 녹음 시 doze 모드나 OS 스로틀링에 의해 UI 타이머와 실제 녹음 시간 간 누적 오차가 발생합니다. (`SystemClock.elapsedRealtime()` 기준 변경 필요)

> [!NOTE]
> **3. UI / 스토리지 버그**
> - **`isSecurityRestricted()` 전달인자 미사용**: [RecordingStore.kt](file:///c:/Users/pij19/Desktop/Codes/daglo%20%EA%B5%AC%ED%98%84%ED%95%98%EA%B8%B0/app/src/main/java/com/recorder/voicenote/RecordingStore.kt)에서 인자 검사 없이 `Build.VERSION.SDK_INT >= R`을 unconditional 반환하여 불필요한 시스템 승인 팝업이 유발됩니다.
> - **Main Thread I/O**: [RecorderViewModel.kt](file:///c:/Users/pij19/Desktop/Codes/daglo%20%EA%B5%AC%ED%98%84%ED%95%98%EA%B8%B0/app/src/main/java/com/recorder/voicenote/RecorderViewModel.kt) init 시 메인 스레드에서 파일 마이그레이션 및 목록을 동기 조회하여 파일이 많아지면 ANR 위험이 존재합니다.

---

### 2.3 서버 환경 (Linux 데몬)

> [!WARNING]
> **systemd 로케일 환경 변수 미설정**
> - 서버의 `file-watcher.service` 데몬은 기본 셸 환경변수를 물려받지 않아 `LANG=POSIX` (ascii)로 동작할 수 있습니다.
> - 이 상태에서 한글 파일명이 들어오면 `pydub` ➔ `ffmpeg` subprocess 호출 시 `'ascii' codec can't encode` 오류가 발생합니다.

---

## 3. 우선순위별 개선 권장사항 (Action Plan)

| 순위 | 문제점 / 기능 | 파일 | 작업 내용 | 위험도/효과 |
| :---: | :--- | :--- | :--- | :--- |
| **P0** | `finish_reason` 검증 및 청크 축소 | [받아쓰기py.py](file:///c:/Users/pij19/Desktop/Codes/daglo%20%EA%B5%AC%ED%98%84%ED%95%98%EA%B8%B0/%EB%B0%9B%EC%95%84%EC%93%B0%EA%B8%B0py.py) | 20분 청크 + `MAX_TOKENS` 검사 후 에러 처리 | 🚨 데이터 유실 방지 |
| **P0** | HTML 이스케이프 적용 | [받아쓰기py.py](file:///c:/Users/pij19/Desktop/Codes/daglo%20%EA%B5%AC%ED%98%84%ED%95%98%EA%B8%B0/%EB%B0%9B%EC%95%84%EC%93%B0%EA%B8%B0py.py) | `html.escape()`로 스크립트 출력 보호 | 🛠 웹 UI 깨짐 방지 |
| **P1** | 오디오 녹음 비트레이트 하향 | [RecorderManager.kt](file:///c:/Users/pij19/Desktop/Codes/daglo%20%EA%B5%AC%ED%98%84%ED%95%98%EA%B8%B0/app/src/main/java/com/recorder/voicenote/RecorderManager.kt) | 64kbps / 16kHz 모노 설정 적용 | ⚡ 용량 75% 절감 & 속도 향상 |
| **P1** | 타이머 드리프트 수정 | [RecordingService.kt](file:///c:/Users/pij19/Desktop/Codes/daglo%20%EA%B5%AC%ED%98%84%ED%95%98%EA%B8%B0/app/src/main/java/com/recorder/voicenote/RecordingService.kt) | `SystemClock.elapsedRealtime()` 기반 경과 시간 산출 | ⏱️ 장시간 녹음 시간 정확도 보장 |
| **P1** | systemd 로케일 추가 | Azure VM 서비스 유닛 | `Environment=LANG=C.UTF-8` 설정 추가 | 🌐 한글 파일명 오류 해결 |
| **P2** | IO 디스패처 적용 | [RecorderViewModel.kt](file:///c:/Users/pij19/Desktop/Codes/daglo%20%EA%B5%AC%ED%98%84%ED%95%98%EA%B8%B0/app/src/main/java/com/recorder/voicenote/RecorderViewModel.kt) | `viewModelScope.launch(Dispatchers.IO)` 적용 | 📱 ANR 방지 |
| **P2** | `.gitignore` 보강 | [.gitignore](file:///c:/Users/pij19/Desktop/Codes/daglo%20%EA%B5%AC%ED%98%84%ED%95%98%EA%B8%B0/.gitignore) | Gradle/Android 빌드 생성 파일 패턴 추가 | 🧹 레포지토리 위생 유지 |

---

## 4. Azure VM 서버 설정 안내 (한글 파일명 오류 해결)

Azure VM SSH 접속 후 아래 명령어로 `file-watcher.service` 유닛을 수정하세요:

```bash
sudo systemctl edit file-watcher.service
```

열리는 편집기 창의 `[Service]` 섹션에 아래 내용 추가 후 저장:

```ini
[Service]
Environment=LANG=C.UTF-8
Environment=LC_ALL=C.UTF-8
Environment=PYTHONUTF8=1
```

저장 후 데몬 재시작:

```bash
sudo systemctl daemon-reload && sudo systemctl restart file-watcher.service
```
