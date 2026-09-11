import os
import re
import json
import subprocess
import threading
import time
import datetime
from typing import AsyncGenerator, List, Dict, Optional, Callable, TypeVar
from dotenv import load_dotenv
from google import genai
from sqlalchemy.orm import Session

try:
    from pydub import AudioSegment
except ImportError:
    AudioSegment = None

load_dotenv()

# API 키는 12칸까지: GEMINI_API_KEY, GEMINI_API_KEY_2 ... GEMINI_API_KEY_12.
# GEMINI_API_KEY_PAID 는 예전 이름이라 계속 읽되, 돈이 나가는 키라서 항상 맨 마지막에 쓴다.
MAX_API_KEYS = 12
API_KEY_ENV_NAMES = (
    ["GEMINI_API_KEY"]
    + [f"GEMINI_API_KEY_{n}" for n in range(2, MAX_API_KEYS + 1)]
    + ["GEMINI_API_KEY_PAID"]
)


def load_api_keys() -> List[str]:
    keys = []
    for name in API_KEY_ENV_NAMES:
        value = (os.getenv(name) or "").strip()
        if value and value not in keys:
            keys.append(value)
    return keys


api_keys = load_api_keys()

T = TypeVar("T")

# 되돌릴 때는 .env 에 GEMINI_MODEL=gemini-2.5-flash
GEMINI_MODEL = os.getenv("GEMINI_MODEL", "gemini-3.6-flash")

# google-genai SDK 는 httpx 클라이언트에 timeout=None 을 넣는다. 즉 기본값이 '무한 대기'다.
# 응답이 끊기면 워커 스레드가 영원히 묶여 큐가 멈추고, 나머지 보드는 계속 '변환 대기 중'이 된다.
# 그래서 반드시 명시적으로 타임아웃을 건다. (밀리초, 기본 15분)
GEMINI_TIMEOUT_MS = max(60_000, int(os.getenv("GEMINI_TIMEOUT_MS", "900000")))
# 일시적 오류(5xx/타임아웃/연결 끊김)일 때 같은 키로 다시 시도할 최대 횟수
GEMINI_MAX_ATTEMPTS = max(1, int(os.getenv("GEMINI_MAX_ATTEMPTS", "3")))

# ffmpeg 한 번 호출이 이 시간을 넘기면 강제 종료한다 (멈춘 ffmpeg 이 워커를 묶는 것을 막는다)
FFMPEG_TIMEOUT_SEC = max(60, int(os.getenv("FFMPEG_TIMEOUT_SEC", "900")))

# 자막 시각은 받아쓰기에게 묻지 않고 우리가 잰다.
#
# 예전에는 몇 분짜리 청크를 통째로 보내고 [MM:SS] 를 찍어 달라고 했다. 그 시각은 받아쓰기가
# 오디오에서 잰 값이 아니라 본문처럼 지어 쓴 값이라, 한 청크에 [00:00] 하나만 찍거나 20분치를
# [00:00] [00:02] ... 로 눌러 찍는 일이 잦았다(시각 간격과 글자 수의 상관계수가 -0.11 이었다).
# 그런 청크는 글자 수로 어림할 수밖에 없었다.
#
# 지금은 녹음을 말이 쉬는 자리에서 15~30초 조각으로 자르고, 조각마다 <<번호>> 를 붙여 요청
# 하나에 묶어 보낸다. 조각의 시작 시각은 ffmpeg 로 자른 값이라 확실하고, 받아쓰기는 번호만 지키면
# 된다. 실제로 보내 보니 시각은 못 지키던 모델이 번호는 조각 수만큼 정확히 붙였다.
#
# 조각 길이는 화면이 한 덩어리로 보여 주는 길이(app.js 의 DISPLAY_BLOCK_MS 20초)와
# SRT 자막 한 줄의 상한(SUBTITLE_MAX_MS 30초)에 맞췄다.
SEGMENT_MIN_MS = 15 * 1000
SEGMENT_MAX_MS = 30 * 1000
# 쉬는 자리를 찾을 때 음량을 재는 간격
LEVEL_WINDOW_MS = 100
# 음량이 0 인 창(-inf dB)을 대신할 값
SILENCE_DB = -120.0
LEVEL_LINE_PATTERN = re.compile(r"lavfi\.astats\.Overall\.RMS_level=(\S+)")

# 요청 하나에 묶어 보내는 오디오 길이(분). 자막 시각의 정확도와는 상관이 없고, 호출 횟수와
# 번호가 어긋난 요청 하나가 망가뜨리는 범위(그 요청은 글자 수로 편다)를 맞바꾼다.
# 오디오를 요청에 바로 싣기 때문에 요청 한도(20MB)를 넘지 않도록 30분에서 막는다.
CHUNK_MINUTES = min(30, max(1, int(os.getenv("STT_CHUNK_MINUTES", "10"))))
CHUNK_LENGTH_MS = CHUNK_MINUTES * 60 * 1000
# 조각 하나에 적힌 말하기 속도의 상한(초당 글자, 띄어쓰기 포함).
#
# 강의 녹음을 조각으로 받아써 보면 초당 5.6~7.4자다. 번호를 빼먹고 두 조각 분량을 한 번호에
# 몰아 적으면 이 값을 넘는다. 그런 요청은 번호를 믿지 않는다.
SEGMENT_MAX_CHARS_PER_SEC = 12.0
# 속도를 잴 때 조각 길이를 이보다 짧게 치지 않는다 (몇 초짜리 마지막 조각에서 헛경보가 나지 않게)
SEGMENT_PACE_MIN_SEC = 5.0
SEGMENT_LABEL_PATTERN = re.compile(r"<<\s*(\d+)\s*>>")
# 번호를 못 믿는 요청을 펼 때 조각 하나가 넘지 않을 길이
SPREAD_PIECE_MAX_CHARS = 120
# 덩어리를 문장으로 끊을 때 쓰는 자리 (문장부호 뒤)
SENTENCE_SPLIT_PATTERN = re.compile(r'[^.!?…。\n]+[.!?…。]*\s*')
TIMESTAMP_PATTERN = re.compile(r'\[\s*(\d{1,2}:\d{2}(?::\d{2})?)\s*\]')
# 대괄호 안쪽에 공백이 끼어도(`[ 00:12]`) 같은 타임스탬프로 본다.
# 받아쓰기가 가끔 이렇게 내주는데, 못 알아보면 본문에 그대로 남아 자막에 찍힌다.

_ILLEGAL_FILENAME_CHARS = re.compile(r'[<>:"/\\|?*\x00-\x1f]')


def sanitize_filename(name: str, fallback: str = "untitled") -> str:
    """윈도우/리눅스 공통으로 안전한 파일명으로 정리한다 (경로 구분자·예약문자 제거)."""
    cleaned = _ILLEGAL_FILENAME_CHARS.sub("_", (name or "").strip())
    cleaned = cleaned.strip(" .")
    return cleaned[:180] or fallback


# -----------------
# ffmpeg 스트리밍 유틸
# -----------------
# 예전에는 pydub 으로 파일 전체를 PCM 으로 디코딩해 메모리에 올린 뒤 잘랐다.
# 1시간짜리 44.1kHz 스테레오 녹음이면 원본만 600MB 를 넘고, 청크 목록까지 한꺼번에
# 만들면 그 두 배가 든다. 워커 2개가 동시에 이러면 작은 서버는 OOM 으로 죽는다.
# 그래서 필요한 구간만 ffmpeg 으로 잘라 쓰고, 메모리 사용량을 파일 길이와 무관하게 만든다.
_active_procs = set()
_procs_lock = threading.Lock()


def ffmpeg_bin() -> str:
    return getattr(AudioSegment, "converter", None) or "ffmpeg"


def ffprobe_bin() -> str:
    try:
        from pydub.utils import get_prober_name
        return get_prober_name() or "ffprobe"
    except Exception:
        return "ffprobe"


def run_tool(cmd: list, timeout: int):
    """외부 도구를 돌린다. 시간이 넘으면 죽여서 워커가 묶이지 않게 한다."""
    try:
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    except FileNotFoundError:
        raise RuntimeError(
            f"{os.path.basename(cmd[0])} 를 찾을 수 없습니다. ffmpeg 이 설치되어 있는지 확인하세요."
        )
    with _procs_lock:
        _active_procs.add(proc)
    try:
        out, err = proc.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.communicate()
        raise RuntimeError(f"{os.path.basename(cmd[0])} 가 {timeout}초를 넘겨 중단했습니다.")
    finally:
        with _procs_lock:
            _active_procs.discard(proc)
    return proc.returncode, out, (err or b"").decode("utf-8", "replace").strip()


def terminate_active_media_processes() -> int:
    """서버가 내려갈 때 남아 있는 ffmpeg 자식 프로세스를 정리한다."""
    with _procs_lock:
        procs = list(_active_procs)
    for proc in procs:
        try:
            proc.kill()
        except Exception:
            pass
    return len(procs)


# 헤더에 길이가 없어 받자마자 컨테이너를 다시 써야 하는 형식 (브라우저 녹음)
STREAMING_AUDIO_EXTS = (".webm", ".ogg")


def probe_duration_seconds(audio_path: str) -> float:
    """파일을 디코딩하지 않고 길이만 알아낸다."""
    code, out, err = run_tool(
        [
            ffprobe_bin(), "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            audio_path,
        ],
        timeout=120,
    )
    if code == 0:
        try:
            duration = float(out.decode("utf-8", "replace").strip())
            if duration > 0:
                return duration
        except ValueError:
            pass

    # 브라우저 녹음(MediaRecorder)은 헤더에 길이를 안 적어 두는 경우가 있다.
    # 그럴 때는 한 번 훑어서 실제 길이를 알아낸다 (디코딩만 하고 버리므로 메모리는 안 든다).
    scanned = scan_duration_seconds(audio_path)
    if scanned > 0:
        return scanned

    raise RuntimeError(f"오디오 길이를 읽지 못했습니다: {err or '알 수 없는 오류'}")


def scan_duration_seconds(audio_path: str) -> float:
    """헤더에 길이가 없는 파일을 끝까지 훑어 길이를 알아낸다."""
    # -v error 만 주면 진행 표시가 안 나온다. -stats 를 함께 줘야 time= 줄이 stderr 로 온다.
    code, _out, err = run_tool(
        [ffmpeg_bin(), "-v", "error", "-stats", "-nostdin", "-i", audio_path, "-f", "null", "-"],
        timeout=FFMPEG_TIMEOUT_SEC,
    )
    if code != 0:
        return 0.0
    # ffmpeg 은 진행 상황을 stderr 에 `time=00:12:34.56` 형식으로 남긴다. 마지막 값이 전체 길이다.
    matches = re.findall(r"time=(\d+):(\d{2}):(\d{2}(?:\.\d+)?)", err or "")
    if not matches:
        return 0.0
    hours, minutes, seconds = matches[-1]
    return int(hours) * 3600 + int(minutes) * 60 + float(seconds)


def rewrite_container(audio_path: str) -> bool:
    """컨테이너만 다시 써서 길이·탐색 정보를 채워 넣는다 (재인코딩 없음).

    브라우저 MediaRecorder 가 만든 webm/ogg 는 스트리밍용이라 헤더에 전체 길이가 없고
    탐색 색인도 없다. 그대로 두면 길이가 0 으로 잡히고, 구간을 잘라 쓰는 STT 단계에서
    `-ss` 탐색이 어긋난다. 받자마자 한 번 다시 써 두면 이후 단계가 파일 종류를 신경 쓸 필요가 없다.
    """
    if not os.path.exists(audio_path):
        return False
    # ffmpeg 은 출력 확장자로 컨테이너를 고른다. 원래 확장자를 유지해야 한다.
    base, ext = os.path.splitext(audio_path)
    temp_path = base + ".rewriting" + ext
    code, _out, _err = run_tool(
        [ffmpeg_bin(), "-v", "error", "-y", "-nostdin", "-i", audio_path, "-c", "copy", temp_path],
        timeout=FFMPEG_TIMEOUT_SEC,
    )
    if code != 0 or not os.path.exists(temp_path) or os.path.getsize(temp_path) == 0:
        if os.path.exists(temp_path):
            try:
                os.remove(temp_path)
            except OSError:
                pass
        return False
    try:
        os.replace(temp_path, audio_path)
        return True
    except OSError:
        return False


def extract_segment_mp3(audio_path: str, start_ms: int, length_ms: int) -> bytes:
    """원본에서 [start, start+length) 구간만 잘라 STT 용 mp3(16kHz 모노 32k) 바이트로 돌려준다.

    30초 조각이 120KB 남짓이라 파일로 쓰지 않고 파이프로 받는다.
    """
    cmd = [
        ffmpeg_bin(), "-v", "error", "-nostdin",
        "-ss", f"{start_ms / 1000.0:.3f}",
        "-t", f"{length_ms / 1000.0:.3f}",
        "-i", audio_path,
        "-vn", "-ac", "1", "-ar", "16000",
        "-c:a", "libmp3lame", "-b:a", "32k",
        "-f", "mp3", "-",
    ]
    code, out, err = run_tool(cmd, timeout=FFMPEG_TIMEOUT_SEC)
    if code != 0 or not out:
        start_label = f"{start_ms // 60000}분 {start_ms // 1000 % 60}초 지점"
        raise RuntimeError(f"오디오 구간 추출 실패 ({start_label}): {err or f'ffmpeg 종료코드 {code}'}")
    return out


def measure_levels_db(audio_path: str) -> List[float]:
    """녹음 전체의 음량을 LEVEL_WINDOW_MS 마다 잰다 (dBFS).

    쉬는 자리를 찾는 데 쓴다. 무음 기준값(-35dB 같은)을 정해 두면 교실 소음이나 마이크 감도에
    따라 쉼을 하나도 못 찾기도 하므로, 기준값 없이 구간마다 가장 조용한 곳을 고르도록 음량 자체를
    받아 둔다. ffmpeg 안에서 8kHz 모노로 줄여 재므로 67분짜리가 3초 남짓에 끝나고, 파이썬에 남는
    것은 숫자 목록뿐이다. 못 재면 빈 목록을 돌려주고, 그때는 조각을 일정한 길이로 자른다.
    """
    samples_per_window = 8000 * LEVEL_WINDOW_MS // 1000
    code, _out, err = run_tool(
        [
            ffmpeg_bin(), "-hide_banner", "-nostats", "-nostdin", "-v", "info",
            "-i", audio_path, "-vn",
            "-af", (
                "aformat=channel_layouts=mono,aresample=8000,"
                f"asetnsamples=n={samples_per_window}:p=0,"
                "astats=metadata=1:reset=1:measure_overall=RMS_level:measure_perchannel=none,"
                # 파일(file=)로 받으면 윈도우 경로의 콜론을 필터 문법에 맞춰 이스케이프해야 한다.
                # 로그(stderr)로 받는다.
                "ametadata=mode=print:key=lavfi.astats.Overall.RMS_level"
            ),
            "-f", "null", "-",
        ],
        timeout=FFMPEG_TIMEOUT_SEC,
    )
    if code != 0:
        print(f"[AI-WARN] 음량을 재지 못해 조각을 일정한 길이로 자릅니다: {err[-300:]}", flush=True)
        return []

    levels = []
    for value in LEVEL_LINE_PATTERN.findall(err):
        try:
            levels.append(max(SILENCE_DB, float(value)))    # -inf(완전한 무음)는 SILENCE_DB 로
        except ValueError:
            levels.append(SILENCE_DB)
    return levels

def make_client(api_key: str) -> genai.Client:
    """타임아웃과 자동 재시도를 건 Gemini 클라이언트를 만든다."""
    try:
        return genai.Client(
            api_key=api_key,
            http_options={
                "timeout": GEMINI_TIMEOUT_MS,
                # SDK 기본값은 429 도 같은 키로 세 번 다시 두드린다. 한도에 걸린 키는
                # 기다려도 안 풀리는 경우가 많으니 곧장 올려 보내 ApiKeyPool 이 다음 키로 넘기게 한다.
                "retry_options": {
                    "attempts": 3, "initial_delay": 2.0, "max_delay": 30.0,
                    "http_status_codes": [408, 500, 502, 503, 504],
                },
            },
        )
    except Exception:
        # retry_options 를 모르는 구버전 SDK → 타임아웃만이라도 건다
        try:
            return genai.Client(api_key=api_key, http_options={"timeout": GEMINI_TIMEOUT_MS})
        except Exception:
            return genai.Client(api_key=api_key)

def is_quota_error(err: Exception) -> bool:
    msg = str(err).upper()
    return "429" in msg or "RESOURCE_EXHAUSTED" in msg or "QUOTA" in msg

_TRANSIENT_MARKERS = (
    "500", "502", "503", "504", "UNAVAILABLE", "INTERNAL", "DEADLINE",
    "TIMEOUT", "TIMED OUT", "CONNECTION", "CONNECTERROR", "READERROR",
    "REMOTEPROTOCOLERROR", "TEMPORARILY", "SSLERROR",
)

def is_transient_error(err: Exception) -> bool:
    """다시 시도하면 될 법한 일시적 오류인지 판별한다."""
    msg = f"{type(err).__name__} {err}".upper()
    return any(m in msg for m in _TRANSIENT_MARKERS)

# 한도(429)에 걸린 키를 쉬게 하는 시간(초). 분당 한도는 금방 풀리지만 하루 한도는 태평양 시간
# 자정에야 풀리므로, 그동안 같은 키를 요청마다 먼저 두드리지 않도록 길게 쉬게 한다.
# 쉬는 키도 버리지는 않는다. 쉬지 않는 키를 다 써 본 뒤에는 빨리 풀리는 순으로 다시 시도한다.
KEY_REST_SEC = 60
KEY_REST_DAILY_SEC = 3600

# 429 응답에 실려 오는 대기 시간. `'retryDelay': '37s'` 또는 `Please retry in 37.1s`
_RETRY_DELAY_RE = re.compile(r"retry(?:Delay['\"]?\s*:\s*['\"]?|\s+in\s+)(\d+(?:\.\d+)?)s", re.IGNORECASE)

def quota_rest_seconds(err: Exception) -> float:
    msg = str(err)
    # 하루 한도 응답에도 retryDelay 가 몇십 초로 찍혀 오므로 이것부터 본다.
    if "PERDAY" in msg.upper():
        return KEY_REST_DAILY_SEC
    match = _RETRY_DELAY_RE.search(msg)
    if match:
        return min(KEY_REST_DAILY_SEC, max(10.0, float(match.group(1))))
    return KEY_REST_SEC

class ApiKeyPool:
    """API 키 여러 개를 나눠 쓴다.

    항상 앞 칸 키부터 쓰고, 한도(429)에 걸린 키는 잠시 쉬게 한 뒤 다음 칸으로 넘어간다.
    쉬는 시간이 끝나면 다시 앞 칸부터 쓰므로, 무료 키가 풀리면 맨 뒤의 유료 키에서 저절로 돌아온다.
    STT 워커 여러 개가 함께 쓰므로 상태는 잠금 안에서만 바꾼다.
    """

    def __init__(self, keys: List[str]):
        self.keys = keys
        self._lock = threading.Lock()
        self._resting_until = [0.0] * len(keys)
        self._last_used: Optional[int] = None

    def order(self) -> List[int]:
        """이번 요청에서 시도할 키 순서: 쉬지 않는 키를 앞 칸부터, 그다음 쉬는 키를 빨리 풀리는 순으로."""
        now = time.monotonic()
        with self._lock:
            ready = [i for i in range(len(self.keys)) if self._resting_until[i] <= now]
            resting = sorted(
                (i for i in range(len(self.keys)) if self._resting_until[i] > now),
                key=lambda i: self._resting_until[i],
            )
        return ready + resting

    def rest(self, index: int, err: Exception) -> None:
        seconds = quota_rest_seconds(err)
        with self._lock:
            self._resting_until[index] = time.monotonic() + seconds
        print(
            f"[AI-KEY] API 키 {index + 1}/{len(self.keys)} 한도 초과 → {int(seconds)}초 쉬게 하고 다음 키로 넘어갑니다.",
            flush=True,
        )

    def mark_used(self, index: int) -> None:
        with self._lock:
            changed = self._last_used != index
            self._last_used = index
        if changed and len(self.keys) > 1:
            print(f"[AI-KEY] API 키 {index + 1}/{len(self.keys)} 로 응답을 받았습니다.", flush=True)

api_key_pool = ApiKeyPool(api_keys)

def all_keys_exhausted_message(last_error: Optional[Exception]) -> str:
    return f"API 키 {len(api_keys)}개가 모두 한도에 걸렸습니다. 잠시 뒤 다시 시도하세요. ({last_error})"

def run_with_api_keys(label: str, work: Callable[[genai.Client], T]) -> T:
    """work(client) 를 부르고, 키가 한도에 걸리면 다음 키로 자동으로 넘어간다.

    - 429/할당량 오류 → 그 키를 쉬게 하고 다음 키로 넘어간다
    - 5xx·타임아웃·연결 끊김 같은 일시적 오류 → 같은 키로 백오프 재시도한다
      (한 번 삐끗했다고 보드 전체를 실패시키지 않는다)
    - 그 밖의 오류 → 키를 바꿔도 결과가 같으므로 그대로 올린다
    """
    if not api_keys:
        raise RuntimeError("GEMINI_API_KEY가 설정되지 않았습니다.")

    last_error = None
    for key_idx in api_key_pool.order():
        client = make_client(api_keys[key_idx])
        attempt = 0
        while True:
            try:
                result = work(client)
                api_key_pool.mark_used(key_idx)
                return result
            except Exception as err:
                last_error = err
                if is_quota_error(err):
                    api_key_pool.rest(key_idx, err)
                    break
                attempt += 1
                if attempt < GEMINI_MAX_ATTEMPTS and is_transient_error(err):
                    delay = min(30, 2 ** attempt)
                    print(
                        f"[AI-RETRY] {label} 일시적 오류로 {delay}초 뒤 재시도 "
                        f"({attempt}/{GEMINI_MAX_ATTEMPTS - 1}): {err}",
                        flush=True,
                    )
                    time.sleep(delay)
                    continue
                raise

    raise RuntimeError(all_keys_exhausted_message(last_error)) from last_error

def timestamp_to_seconds(ts_str: str) -> int:
    parts = list(map(int, ts_str.split(':')))
    if len(parts) == 2:
        return parts[0] * 60 + parts[1]
    elif len(parts) == 3:
        return parts[0] * 3600 + parts[1] * 60 + parts[2]
    return 0

def ms_to_timestamp_str(ms: int) -> str:
    total_seconds = max(0, ms // 1000)
    hours = total_seconds // 3600
    minutes = (total_seconds % 3600) // 60
    seconds = total_seconds % 60
    if hours > 0:
        return f"[{hours:02d}:{minutes:02d}:{seconds:02d}]"
    return f"[{minutes:02d}:{seconds:02d}]"

# 자막 한 줄이 화면에 머무는 최대 시간. 문단 사이가 길게 비어도(쉬는 시간 등)
# 자막이 몇 분씩 떠 있지 않도록 잘라 준다.
SUBTITLE_MAX_MS = 30 * 1000
SUBTITLE_MIN_MS = 1000


# 문장이 끝났는지 판단한다. 닫는 따옴표/괄호가 뒤에 붙어 있어도 문장 끝으로 본다.
SENTENCE_END_PATTERN = re.compile('[.!?…。][”’")\\]]*$')

# 자막 문단 하나가 담을 목표 길이와, 문장이 끝나지 않아도 강제로 끊는 상한
PARAGRAPH_TARGET_MS = max(10_000, int(os.getenv("PARAGRAPH_TARGET_MS", "60000")))
PARAGRAPH_MAX_MS = max(PARAGRAPH_TARGET_MS, int(os.getenv("PARAGRAPH_MAX_MS", "120000")))


def group_by_sentence(items, target_ms: int = None, max_ms: int = None) -> list:
    """짧은 조각들을 1분 내외의 문단으로 묶는다.

    items: [(start_ms, speaker, text), ...]  (시간순)
    반환:  [(start_ms, speaker, merged_text), ...]

    딱 1분에서 자르면 말이 문장 중간에 끊겨 읽기 나빠진다. 그래서 1분을 넘긴 뒤
    `처음 문장이 끝나는 곳`에서 끊는다. 받아쓰기에 마침표가 없어 문장 끝이 계속
    안 나오는 구간을 대비해 max_ms 에서는 문장 중간이라도 강제로 끊는다.
    화자가 바뀌면 길이와 무관하게 끊는다 (다른 사람 말을 한 문단에 담으면 안 된다).
    """
    target = PARAGRAPH_TARGET_MS if target_ms is None else target_ms
    limit = PARAGRAPH_MAX_MS if max_ms is None else max_ms

    groups = []
    state = {"start": None, "speaker": None, "parts": []}

    def flush():
        if state["parts"]:
            groups.append((state["start"], state["speaker"], " ".join(state["parts"])))
        state["start"], state["parts"] = None, []

    for item_start, item_speaker, text in items:
        text = (text or "").strip()
        if not text:
            continue
        if state["start"] is not None and item_speaker != state["speaker"]:
            flush()
        if state["start"] is None:
            state["start"], state["speaker"] = item_start, item_speaker
        state["parts"].append(text)

        elapsed = item_start - state["start"]
        if (elapsed >= target and SENTENCE_END_PATTERN.search(text)) or elapsed >= limit:
            flush()

    flush()
    return groups


def resolve_end_times(start_ms_list: List[int], total_ms: int = 0) -> List[int]:
    """세그먼트 시작 시각들로 서로 겹치지 않는 종료 시각을 계산한다.

    예전에는 종료 시각을 '시작 + 10초'로 고정했다. 문단 간격이 10초보다 짧은 구간에서는
    앞 자막이 다음 자막을 덮어써 SRT 가 겹쳐 보였다. 종료는 다음 문단이 시작하기 직전까지로
    잡는 것이 맞고, 마지막 문단만 녹음 길이로 닫는다.
    """
    n = len(start_ms_list)
    ends = []
    for i, start in enumerate(start_ms_list):
        start = max(0, int(start or 0))
        if i + 1 < n:
            limit = max(int(start_ms_list[i + 1] or 0), start + SUBTITLE_MIN_MS)
        elif total_ms and total_ms > start:
            limit = total_ms
        else:
            limit = start + SUBTITLE_MAX_MS
        ends.append(min(limit, start + SUBTITLE_MAX_MS))
    return ends


def plan_segments(levels_db: List[float], total_ms: int) -> List[tuple]:
    """녹음을 `(시작ms, 끝ms)` 조각들로 나눈다.

    조각은 SEGMENT_MIN_MS~SEGMENT_MAX_MS 길이이고, 그 범위 안에서 가장 조용한 자리에서 자른다.
    조용한 정도는 앞뒤 창까지 세 창 가운데 가장 시끄러운 값으로 본다. 말 사이의 아주 짧은 틈
    하나를 쉼으로 오인해 낱말 한가운데를 자르지 않게 하려는 것이다.
    음량을 못 쟀거나(빈 목록) 음량이 녹음보다 짧게 끝나면 그 자리는 최대 길이에서 자른다.
    """
    if total_ms <= 0:
        return []

    def loudness_around(i: int) -> float:
        return max(levels_db[i - 1:i + 2])

    cuts = [0]
    while total_ms - cuts[-1] > SEGMENT_MAX_MS:
        start = cuts[-1]
        first = max(1, (start + SEGMENT_MIN_MS) // LEVEL_WINDOW_MS)
        last = min((start + SEGMENT_MAX_MS) // LEVEL_WINDOW_MS, len(levels_db) - 1)
        if first < last:
            quietest = min(range(first, last), key=loudness_around)
            cuts.append(quietest * LEVEL_WINDOW_MS + LEVEL_WINDOW_MS // 2)
        else:
            cuts.append(start + SEGMENT_MAX_MS)
    cuts.append(total_ms)
    return list(zip(cuts, cuts[1:]))


def group_segments(segments: List[tuple], batch_ms: int) -> List[List[tuple]]:
    """조각들을 요청 하나에 실을 묶음으로 나눈다. 묶음 하나는 batch_ms 를 넘지 않는다
    (조각 하나가 그보다 길면 그 조각 혼자 한 묶음이 된다)."""
    batches: List[List[tuple]] = []
    for seg in segments:
        if batches and seg[1] - batches[-1][0][0] <= batch_ms:
            batches[-1].append(seg)
        else:
            batches.append([seg])
    return batches


def parse_segment_transcript(text: str, count: int) -> Optional[List[str]]:
    """`<<번호>> 본문` 꼴 응답을 조각별 본문 목록(길이 count)으로 만든다. 번호를 못 믿으면 None.

    번호가 빠진 조각은 빈 본문이다 (말이 없는 조각은 번호를 건너뛰기도 한다).
    번호가 되감기거나 겹치거나 조각 수를 넘거나, 첫 번호 앞에 글이 있으면 못 믿는다.
    본문에 섞여 온 [MM:SS] 는 걷어낸다.
    """
    from server.migrator import strip_timestamps

    labels = list(SEGMENT_LABEL_PATTERN.finditer(text or ""))
    if not labels or text[:labels[0].start()].strip():
        return None

    texts = [""] * count
    previous = 0
    for label, following in zip(labels, labels[1:] + [None]):
        number = int(label.group(1))
        if not previous < number <= count:
            return None
        body = text[label.end():following.start() if following else len(text)]
        texts[number - 1] = re.sub(r"\s+", " ", strip_timestamps(body)).strip()
        previous = number
    return texts


def find_crowded_segment(texts: List[str], segments: List[tuple]) -> Optional[int]:
    """사람이 말할 수 없는 속도만큼 본문이 몰린 조각의 위치. 없으면 None.

    받아쓰기가 번호를 몇 개 건너뛰고 그 말을 한 번호에 몰아 적으면, 번호 순서는 멀쩡해도
    시각이 그만큼 앞당겨진다. 순서 검사로는 못 잡으므로 글자 수로 잡는다.
    """
    for i, (text, (start_ms, end_ms)) in enumerate(zip(texts, segments)):
        seconds = max((end_ms - start_ms) / 1000, SEGMENT_PACE_MIN_SEC)
        if len(text) > seconds * SEGMENT_MAX_CHARS_PER_SEC:
            return i
    return None


def split_text_for_spreading(text: str) -> List[str]:
    """긴 덩어리를 문장 단위로 쪼갠다 (마침표가 없으면 글자 수로라도 끊는다).

    번호를 못 믿는 요청은 몇 분치 말이 한 덩어리로 남는다. 덩어리인 채로는 어디에 펴 놓아도
    한 점에 뭉치므로, 펴기 전에 먼저 쪼갠다.
    """
    parts = [p.strip() for p in SENTENCE_SPLIT_PATTERN.findall(text)]
    parts = [p for p in parts if p]
    if not parts:
        stripped = text.strip()
        parts = [stripped] if stripped else []

    out = []
    for part in parts:
        # 받아쓰기가 마침표를 거의 안 찍어 준 구간이 있다. 그때는 띄어쓰기에서 끊는다.
        while len(part) > SPREAD_PIECE_MAX_CHARS:
            cut = part.rfind(" ", 0, SPREAD_PIECE_MAX_CHARS)
            if cut <= 0:
                cut = SPREAD_PIECE_MAX_CHARS
            head, part = part[:cut].strip(), part[cut:].strip()
            if head:
                out.append(head)
        if part:
            out.append(part)
    return out


def spread_pieces(pieces: List[tuple], span_ms: int) -> List[tuple]:
    """조각들을 `[0, span_ms)` 안에 글자 수에 비례해 고르게 편다.

    말하는 속도가 일정하다고 치는 셈이라 정확하지는 않지만, 요청 하나(몇 분) 안에서의
    어림이라 오차도 그 안에 갇힌다. 몇 분치가 한 시각에 뭉쳐 있는 것보다는 훨씬 낫다.
    """
    fragments = []
    for _ms, text in pieces:
        fragments.extend(split_text_for_spreading(text))

    total_chars = sum(len(t) for t in fragments)
    if not fragments or total_chars <= 0 or span_ms <= 0:
        return pieces

    spread = []
    before = 0
    for text in fragments:
        spread.append((int(span_ms * before / total_chars), text))
        before += len(text)
    return spread


def transcribe_segments(audio_path: str, segments: List[tuple], label: str, prompt_text: str) -> str:
    """조각 여러 개를 요청 하나로 받아쓴다. 조각마다 앞에 `<<번호>>` 를 붙여 보낸다.

    오디오는 파일로 올리지 않고 요청에 바로 싣는다. 16kHz 모노 32k mp3 라 10분이 2.4MB 로 요청
    한도에 한참 못 미치고, 올린 파일은 그 키의 프로젝트에만 보여서 키를 바꿀 때마다 다시 올려야
    했던 일도 없어진다. 키 전환과 재시도는 run_with_api_keys 가 맡는다.
    """
    contents: list = [prompt_text]
    for number, (start_ms, end_ms) in enumerate(segments, 1):
        contents.append(f"<<{number}>>")
        contents.append(genai.types.Part.from_bytes(
            data=extract_segment_mp3(audio_path, start_ms, end_ms - start_ms),
            mime_type="audio/mp3",
        ))

    def work(client: genai.Client) -> str:
        response = client.models.generate_content(model=GEMINI_MODEL, contents=contents)

        candidate = response.candidates[0] if response.candidates else None
        finish_reason = candidate.finish_reason if candidate else None

        parts = []
        if candidate and candidate.content and candidate.content.parts:
            parts = [p.text for p in candidate.content.parts if p.text]
        transcript_text = "".join(parts).strip()

        if not transcript_text or finish_reason not in (None, genai.types.FinishReason.STOP):
            raise RuntimeError(f"응답이 비정상 종료되었습니다 (finish_reason={finish_reason})")

        return transcript_text

    return run_with_api_keys(label, work)

# 프롬프트에 넣는 용어 개수 상한. 너무 많이 넣으면 정작 본문 받아쓰기 품질이 떨어진다.
GLOSSARY_MAX_TERMS = 200

# 규칙 번호는 build_glossary_prompt 가 5번을 이어 붙이므로 4번까지만 쓴다.
STT_BASE_PROMPT = """
아래에 긴 녹음을 순서대로 잘라 낸 오디오 조각 {count}개가 있어. 조각마다 바로 앞에 <<번호>> 표시가 붙어 있어.
조각마다 처음부터 끝까지 빠짐없이 텍스트로 받아쓰기(Transcription) 해줘. 작성할 때 아래 규칙을 엄격하게 지켜:
1. 조각마다 그 조각의 번호 표시(<<1>>, <<2>> ...)를 먼저 적고 그 뒤에 받아쓴 본문을 적어.
   번호는 1부터 {count}까지 빠짐없이 순서대로 적어.
2. 한 조각에서 들린 말은 그 조각 번호 뒤에만 적어. 앞뒤 조각의 말과 합치거나 옮기지 마.
   조각 경계에서 잘린 말은 들린 만큼만 적어.
3. 말이 없는 조각은 번호만 적고 본문은 비워 둬. 들리지 않는 말을 지어내지 마.
4. 인사말, 부연 설명, [MM:SS] 같은 타임스탬프 없이 번호 표시와 본문만 출력해.
"""


def load_glossary_terms(db, folder_id: Optional[int]) -> List[Dict[str, str]]:
    """해당 폴더의 용어 + 모든 폴더 공통 용어(folder_id 가 비어 있는 것)를 모은다."""
    from server.models import GlossaryTerm

    query = db.query(GlossaryTerm)
    if folder_id:
        query = query.filter(
            (GlossaryTerm.folder_id == folder_id) | (GlossaryTerm.folder_id.is_(None))
        )
    else:
        query = query.filter(GlossaryTerm.folder_id.is_(None))
    return [
        {"term": t.term, "note": t.note or ""}
        for t in query.order_by(GlossaryTerm.folder_id.isnot(None), GlossaryTerm.id).all()
    ]


def build_glossary_prompt(terms: List[Dict[str, str]]) -> str:
    """단어장을 받아쓰기 프롬프트에 덧붙일 문장으로 만든다. 용어가 없으면 빈 문자열."""
    lines = []
    for t in terms[:GLOSSARY_MAX_TERMS]:
        term = (t.get("term") or "").strip()
        if not term:
            continue
        note = (t.get("note") or "").strip()
        lines.append(f"- {term} ({note})" if note else f"- {term}")
    if not lines:
        return ""
    joined = "\n".join(lines)
    return (
        "5. 아래는 이 녹음에 자주 나오는 고유명사·전문용어 목록이야. 비슷하게 들리더라도 "
        "이 목록에 있는 표기를 그대로 써. 목록에 없는 말을 억지로 끼워 넣지는 마:\n"
        + joined
        + "\n"
    )


def process_audio_file_to_board(board_id: int, audio_path: str, db_session_factory, progress_callback: Optional[Callable[[int], None]] = None):
    """오디오 파일을 조각으로 나누고 Gemini STT를 실행하여 Board에 저장하는 완전 자동화 파이프라인"""
    from server.models import Board, TranscriptSegment, BoardSummary, Folder
    from server.migrator import strip_timestamps

    db = db_session_factory()
    board = db.query(Board).filter_by(id=board_id).first()
    if not board:
        db.close()
        return

    try:
        board.status = "PROCESSING"
        board.progress_percent = 5
        db.commit()

        # 폴더 단어장을 프롬프트에 실어 보낸다 (고유명사·전문용어 표기 고정)
        glossary = load_glossary_terms(db, board.folder_id)
        glossary_prompt = build_glossary_prompt(glossary)
        if glossary:
            print(f"[AI-GLOSSARY] Board #{board.id} 용어 {len(glossary)}개를 프롬프트에 적용합니다.", flush=True)

        # 브라우저에서 바로 녹음한 파일(webm/ogg)은 헤더에 길이·탐색 색인이 없다.
        # 청크를 잘라 쓰기 전에 컨테이너만 한 번 다시 써서 이후 단계가 신경 쓸 일을 없앤다.
        if audio_path.lower().endswith(STREAMING_AUDIO_EXTS):
            rewrite_container(audio_path)

        # 파일을 통째로 디코딩하면 긴 녹음에서 수백 MB~GB 를 먹고 서버가 OOM 으로 죽는다.
        # 길이만 먼저 재고, 실제 오디오는 조각 단위로 그때그때 잘라 쓴다.
        total_duration_sec = probe_duration_seconds(audio_path)
        board.duration_seconds = total_duration_sec
        db.commit()

        total_ms = int(total_duration_sec * 1000)
        segments = plan_segments(measure_levels_db(audio_path), total_ms)
        batches = group_segments(segments, CHUNK_LENGTH_MS)
        total_batches = len(batches)
        print(
            f"[AI-TIME] Board #{board.id} 조각 {len(segments)}개를 요청 {total_batches}번에 나눠 받아씁니다",
            flush=True,
        )

        # (절대 ms, 본문) 조각들. 조각의 시작 시각은 우리가 자른 값이다.
        all_pieces: List[tuple] = []
        for i, batch in enumerate(batches):
            # 요청 하나가 오래 걸려도 '정체'로 오인되지 않도록 시작 시점에 살아있음을 알린다
            if progress_callback:
                progress_callback(board.progress_percent or 0)

            prompt = STT_BASE_PROMPT.format(count=len(batch)) + glossary_prompt
            transcript_text = transcribe_segments(
                audio_path, batch, f"board_{board_id}_chunk_{i+1}", prompt
            )

            texts = parse_segment_transcript(transcript_text, len(batch))
            crowded = None if texts is None else find_crowded_segment(texts, batch)
            if texts is not None and crowded is None:
                all_pieces.extend(
                    (start_ms, text) for (start_ms, _end_ms), text in zip(batch, texts) if text
                )
            else:
                # 번호를 못 믿는 요청이다. 요청 하나에 실은 구간 안에 고르게 펴서 최소한 그 밖으로는
                # 새지 않게 한다.
                batch_start, batch_end = batch[0][0], batch[-1][1]
                reason = "번호가 어긋나" if texts is None else f"{crowded + 1}번 조각에 말이 몰려"
                print(
                    f"[AI-TIME] Board #{board.id} chunk {i+1}/{total_batches}: "
                    f"{reason} {(batch_end - batch_start) // 1000}초 안에 고르게 폅니다",
                    flush=True,
                )
                body = SEGMENT_LABEL_PATTERN.sub(" ", transcript_text)
                all_pieces.extend(
                    (batch_start + rel_ms, text)
                    for rel_ms, text in spread_pieces([(0, strip_timestamps(body))], batch_end - batch_start)
                )

            # 진행률 업데이트
            progress = int(10 + (i + 1) / total_batches * 70)
            board.progress_percent = min(85, progress)
            db.commit()
            if progress_callback:
                progress_callback(board.progress_percent)

        # 세그먼트 파싱 & 저장
        db.query(TranscriptSegment).filter_by(board_id=board.id).delete()

        pieces = [(ms, "화자 1", text) for ms, text in all_pieces]
        # 키워드·요약에 넘길 전체 원고 (시각 + 본문)
        full_transcript = "\n".join(f"{ms_to_timestamp_str(ms)} {text}" for ms, text in all_pieces)

        # 조각 하나를 세그먼트 하나로, 시각을 하나도 버리지 않고 그대로 저장한다.
        #
        # 한때는 여기서 1분 문단으로 묶어 저장했는데(읽기 좋으라고), 묶으면 문단 첫 시각만 남고
        # 그 안의 시각은 사라진다. 화면은 없어진 시각을 글자 수로 되짚어 지어내므로, 재생하며
        # 따라가는 밑줄이 문단 안에서 통째로 어긋났다. 문단으로 묶는 일은 읽는 쪽(화면·txt
        # 내보내기)에서 하면 되고, 시각은 여기서 지키는 것이 맞다.
        seq = 0
        built = []
        for start_ms, speaker, text in pieces:
            stamp = ms_to_timestamp_str(start_ms)
            built.append(TranscriptSegment(
                board_id=board.id,
                start_time_ms=start_ms,
                end_time_ms=start_ms,          # 아래에서 이웃 기준으로 다시 채운다
                timestamp_str=stamp,
                speaker=speaker,
                content=text,
                sequence=seq
            ))
            seq += 1

        # 사람이 읽을 txt 는 예전처럼 1분 내외 문단으로 묶는다 (저장된 시각은 그대로 둔다)
        txt_lines = [
            f"{ms_to_timestamp_str(start_ms)} {text}"
            for start_ms, _speaker, text in group_by_sentence(pieces)
        ]

        # 종료 시각은 이웃을 봐야 정해지므로 다 만든 뒤에 한 번에 채운다 (SRT 자막 겹침 방지)
        for seg, end_ms in zip(built, resolve_end_times([s.start_time_ms for s in built], total_ms)):
            seg.end_time_ms = end_ms
            db.add(seg)

        # 텍스트 파일 저장
        if not board.txt_path:
            RESULT_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "강의 녹음 변환")
            folder = db.query(Folder).filter_by(id=board.folder_id).first()
            folder_name = folder.name if folder else "기본 폴더"
            if folder_name == "기본 폴더":
                target_dir = RESULT_DIR
            else:
                target_dir = os.path.join(RESULT_DIR, sanitize_filename(folder_name))
            os.makedirs(target_dir, exist_ok=True)
            board.txt_path = os.path.join(target_dir, f"{sanitize_filename(board.title)}.txt")
            db.commit()

        try:
            with open(board.txt_path, "w", encoding="utf-8") as tf:
                tf.write("\n\n".join(txt_lines))
        except OSError as werr:
            # 세그먼트는 이미 DB 에 확정됐으므로 txt 저장 실패로 변환 전체를 실패 처리하지 않는다
            print(f"[AI-WARN] Board #{board.id} txt save failed: {werr}")

        # 후처리 1: AI 키워드 자동 추출 (실패해도 변환 결과는 유지)
        board.progress_percent = 90
        db.commit()
        try:
            keywords = extract_keywords_ai(full_transcript)
            if keywords:
                board.keywords_json = json.dumps(keywords, ensure_ascii=False)
        except Exception as kerr:
            print(f"[AI-WARN] Board #{board.id} keyword extraction failed: {kerr}")

        # 후처리 2: 기본 3단 요약 자동 생성 (실패해도 변환 결과는 유지)
        board.progress_percent = 95
        db.commit()
        try:
            basic_summary = generate_summary_ai(full_transcript, "BASIC")
            existing_basic = db.query(BoardSummary).filter_by(board_id=board.id, summary_type="BASIC").first()
            if existing_basic:
                existing_basic.content = basic_summary
                existing_basic.created_at = datetime.datetime.utcnow()
            else:
                db.add(BoardSummary(
                    board_id=board.id,
                    summary_type="BASIC",
                    title="기본 요약",
                    content=basic_summary
                ))
        except Exception as serr:
            print(f"[AI-WARN] Board #{board.id} summary generation failed: {serr}")

        board.status = "COMPLETED"
        board.progress_percent = 100
        board.error_message = None
        board.updated_at = datetime.datetime.utcnow()
        db.commit()
        print(f"[AI-COMPLETE] Board #{board.id} ({board.title}) STT & Analysis done.")

    except Exception as e:
        print(f"[AI-ERROR] Board #{board_id} STT failed: {e}", flush=True)
        # 실패한 트랜잭션을 먼저 되돌려야 상태 기록 커밋이 또 터지지 않는다.
        # 여기서 예외가 새어 나가면 워커가 보드를 FAILED 로 마무리해 준다.
        db.rollback()
        board = db.query(Board).filter_by(id=board_id).first()
        if board:
            board.status = "FAILED"
            board.progress_percent = 0
            board.error_message = str(e)[:500]
            db.commit()
    finally:
        db.close()

def extract_keywords_ai(transcript: str) -> List[str]:
    if not api_keys or not transcript.strip():
        # 간단한 빈도 기반 fallback
        words = re.findall(r'[가-힣a-zA-Z0-9_]{2,}', transcript)
        stopwords = {'그래서', '우리가', '여기서', '이런', '저런', '어떤', '때문에', '그리고', '하지만', '이렇게', '그냥', '이제', '있는', '없는'}
        freq = {}
        for w in words:
            if w not in stopwords:
                freq[w] = freq.get(w, 0) + 1
        return [k for k, _ in sorted(freq.items(), key=lambda x: x[1], reverse=True)[:8]]

    prompt = f"""
아래 스크립트에서 가장 중요한 핵심 주제 및 개념 키워드 8개를 추출해줘.
반드시 JSON 문자열 배열 형식으로만 응답해. (예: ["객체지향", "메모리버퍼", "소켓통신", "클래스", "스트림", "스레드", "패킷", "인터페이스"])

스크립트:
{transcript[:5000]}
"""
    try:
        response = run_with_api_keys("키워드 추출", lambda client: client.models.generate_content(
            model=GEMINI_MODEL,
            contents=prompt,
            config={"response_mime_type": "application/json"}
        ))
        data = json.loads(response.text.strip())
        if isinstance(data, list):
            return [str(w) for w in data[:10]]
        elif isinstance(data, dict) and "keywords" in data:
            return [str(w) for w in data["keywords"][:10]]
        return []
    except Exception as e:
        print(f"[AI-KEYWORD-ERROR] {e}")
        return []

def generate_summary_ai(transcript: str, summary_type: str = "BASIC") -> str:
    if not api_keys or not transcript.strip():
        return "Gemini API 키가 설정되지 않았거나 스크립트가 비어 있습니다."

    prompts = {
        "BASIC": """
당신은 최고의 강의/회의록 분석 전문가입니다.
제공된 스크립트를 바탕으로 직관적이고 깔끔한 마크다운(Markdown) 요약본을 작성하세요.

[출력 양식]:
### 📌 3줄 핵심 요약
1. 핵심 요약 1
2. 핵심 요약 2
3. 핵심 요약 3

### 📖 주요 내용 정리
- **[주제 1]**: 상세 설명 및 핵심 개념
- **[주제 2]**: 상세 설명 및 핵심 개념
- **[주제 3]**: 상세 설명 및 핵심 개념

### 💡 핵심 키포인트 & 결론
- 요약 및 기억해야 할 핵심 결론
""",
        "MEETING": """
당신은 프로페셔널한 비즈니스 회의록 작성자입니다.
제공된 대화/회의 스크립트를 분석하여 체계적인 회의록을 작성하세요.

[출력 양식]:
### 🏢 회의 개요 & 목적
- **주요 의제**: ...
- **진행 상황**: ...

### 💬 주요 논의 및 의사결정 사항
- **[안건 1]**: 논의 내용 및 결정된 사항
- **[안건 2]**: 논의 내용 및 결정된 사항

### 🎯 다음 단계 및 결정사항
- 실행 목표 및 후속 일정
""",
        "ACTION_ITEM": """
제공된 스크립트에서 결정된 실행 과제(Action Items)와 할 일 목록(To-Do)을 명확하게 추출하세요.

[출력 양식]:
### 📋 실행 과제 (Action Items)
- [ ] **과제 1**: 구체적 실행 내용 및 목표
- [ ] **과제 2**: 구체적 실행 내용 및 목표
- [ ] **과제 3**: 구체적 실행 내용 및 목표

### ⚠️ 주의사항 및 고려할 제약사항
- 실행 시 유의할 점
""",
        "QUIZ": """
제공된 강의 스크립트 내용을 복습할 수 있는 핵심 퀴즈 3문제와 정답 및 상세 해설을 작성하세요.

[출력 양식]:
### ✍️ 강의 복습 퀴즈
1. **문제 1**: ...
2. **문제 2**: ...
3. **문제 3**: ...

---
### 🔍 정답 및 상세 해설
- **1번 정답**: ... (해설: ...)
- **2번 정답**: ... (해설: ...)
- **3번 정답**: ... (해설: ...)
""",
        "SLIDE": """
제공된 강의/발표 스크립트를 파워포인트/슬라이드 발표 자료 개요 형태로 작성하세요.

[출력 양식]:
### 📊 슬라이드 발표 개요

#### Slide 1: 표지 & 도입
- 제목: ...
- 핵심 메시지: ...

#### Slide 2: 핵심 개념 및 원리
- 주요 포인트: ...
- 상세 설명: ...

#### Slide 3: 적용 사례 및 결론
- 결론: ...
"""
    }

    selected_prompt = prompts.get(summary_type, prompts["BASIC"])
    full_prompt = f"{selected_prompt}\n\n[스크립트 원본]:\n{transcript[:18000]}"

    try:
        response = run_with_api_keys("요약", lambda client: client.models.generate_content(
            model=GEMINI_MODEL,
            contents=full_prompt
        ))
        return response.text or "요약을 생성하지 못했습니다."
    except Exception as e:
        return f"요약 생성 중 오류가 발생했습니다: {str(e)}"

def stream_board_chat(transcript: str, chat_history: List[Dict[str, str]], user_message: str):
    if not api_keys:
        yield "data: " + json.dumps({"text": "⚠️ Gemini API 키가 설정되지 않았습니다. .env 파일을 확인해주세요."}) + "\n\n"
        return

    system_instruction = f"""
당신은 이 녹음/강의 보드의 전담 AI 비서 '다글로 챗봇'입니다.
아래 제공된 [전체 스크립트] 내용을 바탕으로 사용자의 질문에 정확하고 친절하게 답변하세요.

규칙:
1. 스크립트에 언급된 내용을 근거로 명확하게 답변하세요.
2. 관련된 타임스탬프([MM:SS])가 있다면 함께 언급하여 사용자가 오디오를 찾아 들을 수 있게 도와주세요.
3. 스크립트에 없는 내용은 지어내지 말고 솔직하게 "본 녹음 내용에는 해당 내용이 언급되지 않았습니다"라고 답변하세요.
4. 마크다운 문법(굵게, 불릿 기호 등)을 활용해 읽기 쉽게 답변하세요.

[전체 스크립트]:
{transcript[:30000]}
"""

    contents = []
    for c in chat_history[-5:]:
        contents.append({"role": "user" if c["role"] == "user" else "model", "parts": [{"text": c["message"]}]})

    contents.append({"role": "user", "parts": [{"text": f"{system_instruction}\n\n[사용자 질문]:\n{user_message}"}]})

    # 스트리밍은 run_with_api_keys 에 맡길 수 없어 여기서 직접 키를 돌린다.
    # 답이 이미 흘러나가기 시작한 뒤라면 키를 바꿔 처음부터 다시 보낼 수 없으니 오류로 끝낸다.
    last_error = None
    for key_idx in api_key_pool.order():
        client = make_client(api_keys[key_idx])
        sent_any = False
        try:
            stream = client.models.generate_content_stream(
                model=GEMINI_MODEL,
                contents=contents
            )
            for chunk in stream:
                if chunk.text:
                    sent_any = True
                    yield f"data: {json.dumps({'text': chunk.text})}\n\n"
            api_key_pool.mark_used(key_idx)
            return
        except Exception as e:
            last_error = e
            if is_quota_error(e) and not sent_any:
                api_key_pool.rest(key_idx, e)
                continue
            yield f"data: {json.dumps({'error': str(e)})}\n\n"
            return

    yield f"data: {json.dumps({'error': all_keys_exhausted_message(last_error)})}\n\n"
