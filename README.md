# 음성녹음 (VoiceRecorder)

Kotlin + Jetpack Compose로 만든 간단한 폴더 기반 음성녹음 앱입니다.

## 주요 기능
- **홈 화면**: 폴더 목록 + `폴더 추가` 버튼 + `녹음` 버튼
- 폴더를 탭하면 해당 폴더 안의 녹음파일 목록 화면으로 이동
- **폴더 안에 들어간 상태에서 녹음 버튼을 누르면** 녹음이 시작되고,
  정지 시 파일명이 `[해당 폴더 이름]_[녹음 시작 시각].m4a` 형식으로 저장됩니다.
  - 예: `회의록` 폴더에서 2026년 7월 10일 14시 32분 5초에 녹음을 시작하면
    → `회의록_20260710_143205.m4a`
- 홈 화면(폴더에 들어가지 않은 상태)에서 녹음 버튼을 누르면
  "먼저 폴더를 선택해주세요" 안내가 표시됩니다. (요청하신 동작대로 폴더 진입 상태에서만 녹음됨)
- **녹음은 포그라운드 서비스(Foreground Service)로 동작**하기 때문에, 화면을 끄거나
  다른 앱으로 전환하거나 앱을 최소화해도 녹음이 계속됩니다. 녹음 중에는 상태 표시줄에
  진행 시간이 표시되는 알림이 뜨고, 알림의 `정지` 버튼으로도 녹음을 끝낼 수 있습니다.
  (알림을 강제로 스와이프해서 지우는 것만으로는 녹음이 멈추지 않습니다 - 앱으로 돌아오거나
  알림의 정지 버튼을 눌러야 종료됩니다.)
- 녹음 파일은 **`내부 저장소 > Recordings > Voice Recorder > [폴더명]`** 에 저장되어,
  기본 파일 관리자 앱 등에서도 바로 찾아볼 수 있습니다.
  - Android 10(API 29) 이상: `MediaStore`(Scoped Storage) API로 저장하므로 별도의 저장소 권한이 필요 없습니다.
    단, 빈 폴더(아직 녹음이 하나도 없는 폴더)는 앱 안에서만 표시되고, 파일 관리자에는 첫 녹음이 저장된 후에 폴더가 나타납니다.
  - Android 9(API 28) 이하: 실제 디렉터리로 바로 생성되며, `저장소` 권한(WRITE_EXTERNAL_STORAGE) 허용이 필요합니다.
- **기존에 있던 폴더/녹음파일도 자동으로 불러옵니다.**
  - 이전 버전의 앱(앱 전용 저장소에 저장하던 버전)을 쓰고 있었다면, 최초 실행 시 자동으로
    지금의 공용 저장 위치로 옮겨줍니다.
  - 다른 앱이나 파일 관리자로 `Recordings/Voice Recorder` 폴더 안에 미리 넣어둔 파일이 있다면,
    앱 실행 시 요청하는 **저장소 읽기 권한**(Android 13+: `READ_MEDIA_AUDIO`, Android 10~12:
    `READ_EXTERNAL_STORAGE`)을 허용하면 그 파일들도 함께 표시됩니다.
- **폴더/녹음파일 이름을 길게 누르면** "이름 변경" 버튼이 있는 메뉴가 뜨고,
  눌러서 새 이름을 입력하면 바로 변경됩니다.
- **녹음파일을 탭하면 바로 재생됩니다.** 재생 중인 파일을 다시 탭하면 정지하고,
  재생이 끝까지 진행되면 자동으로 정지 상태로 돌아갑니다.

## 열기 / 실행 방법
1. Android Studio (최신 버전 권장, Hedgehog 이상)에서 `VoiceRecorder` 폴더를 **Open**으로 엽니다.
2. Gradle Sync가 끝날 때까지 기다립니다. (처음 열 때 Gradle wrapper jar를
   자동으로 내려받습니다. 인터넷 연결이 필요합니다.)
3. 실제 기기 또는 마이크가 동작하는 에뮬레이터를 연결하고 Run ▶ 을 누릅니다.
4. 앱 실행 후 녹음 버튼을 처음 누르면 마이크 권한을 요청합니다. 허용해주세요.

## 프로젝트 구조
```
app/src/main/java/com/recorder/voicenote/
 ├─ MainActivity.kt        # 전체 Compose UI (홈 화면 / 폴더 상세 화면)
 ├─ RecorderViewModel.kt   # 화면 상태 관리 (폴더 선택, 녹음 상태, 타이머 등)
 ├─ RecordingStore.kt      # 폴더/녹음파일 저장, 조회, 이름변경 (MediaStore / 레거시 파일 저장 분기)
 ├─ RecorderManager.kt     # MediaRecorder 시작/정지 래퍼
 ├─ RecordingService.kt    # 백그라운드에서도 녹음을 유지하는 포그라운드 서비스
 ├─ PlayerManager.kt       # 녹음파일 재생을 담당하는 MediaPlayer 래퍼
 └─ ui/theme/              # Compose 색상 / 타이포그래피 테마
```

## APK 만들기

### 방법 A. Android Studio에서 바로 만들기 (가장 간단)
1. Android Studio에서 프로젝트를 엽니다.
2. 상단 메뉴 `Build` → `Build Bundle(s) / APK(s)` → `Build APK(s)` 클릭.
3. 빌드가 끝나면 우측 아래 알림에 뜨는 `locate` 링크를 클릭하면
   `app/build/outputs/apk/debug/app-debug.apk` 파일을 바로 찾을 수 있습니다.
4. 이 apk 파일을 폰으로 옮겨서 설치하면 됩니다. (출처를 알 수 없는 앱 설치 허용 필요)

### 방법 B. GitHub에 올려서 자동으로 APK 만들기 (Android Studio 없이 가능)
이 프로젝트에는 이미 `.github/workflows/build-apk.yml` 이 포함되어 있어서,
GitHub에 올리기만 하면 자동으로 APK를 빌드해줍니다.

1. **새 저장소 만들기**
   - GitHub 로그인 → 우측 상단 `+` → `New repository`
   - 이름 입력(예: `voice-recorder`) 후 `Create repository` (Public/Private 상관없음)

2. **프로젝트 업로드**
   - 방금 만든 빈 저장소 페이지에서 `uploading an existing file` 링크 클릭
   - 압축 푼 `VoiceRecorder` 폴더 안의 내용 전체(모든 파일/폴더)를
     드래그 앤 드롭으로 업로드 (크롬/엣지 등 최신 브라우저는 폴더째 드래그 가능)
   - 하단 `Commit changes` 클릭
   - (터미널 사용이 익숙하다면 아래처럼 git으로 올려도 됩니다)
     ```bash
     cd VoiceRecorder
     git init
     git add .
     git commit -m "init"
     git branch -M main
     git remote add origin https://github.com/사용자명/voice-recorder.git
     git push -u origin main
     ```

3. **자동 빌드 확인**
   - 저장소 상단 메뉴의 `Actions` 탭 클릭
   - `Build Debug APK` 워크플로우가 자동으로 실행 중인 것을 확인 (초록 체크가 뜰 때까지 1~3분 대기)

4. **APK 다운로드**
   - 완료된 워크플로우 실행(런) 클릭 → 페이지 하단 `Artifacts` 항목에서
     `VoiceRecorder-debug-apk` 클릭 → zip 파일이 다운로드됨
   - 압축을 풀면 안에 `app-debug.apk` 파일이 들어있습니다. 폰에 옮겨 설치하세요.

> 참고: 이 워크플로우는 서명되지 않은 **디버그 APK**를 만듭니다. 테스트/개인 설치용으로는
> 문제없이 설치·실행되지만, Play 스토어에 올리려면 별도로 릴리즈 서명이 필요합니다.

## 커스터마이징 팁

- 저장 폴더의 상위 경로(`Recordings/Voice Recorder`)를 바꾸고 싶다면
  `RecordingStore.kt`의 `ROOT_FOLDER_NAME` 값을 수정하세요.
- 녹음 포맷을 mp3/wav 등으로 바꾸려면 `RecorderManager.kt`의
  `setOutputFormat` / `setAudioEncoder` 값을 수정하세요.
- 재생 기능이 필요하면 `RecordingCard`에 `MediaPlayer`를 연결해 추가할 수 있습니다.
