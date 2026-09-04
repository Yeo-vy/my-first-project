import os
import io
import re
import json
import time
import shutil
import mimetypes
import datetime
import queue
import threading
import unicodedata
from typing import Optional, List
from fastapi import FastAPI, Depends, HTTPException, Query, Request, Response, UploadFile, File, Form
from fastapi.responses import StreamingResponse, FileResponse, JSONResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy import select
from sqlalchemy.orm import Session
from pydantic import BaseModel

from server.database import init_db, get_db, SessionLocal
from server.models import Folder, Board, TranscriptSegment, BoardSummary, BoardChat, Bookmark, GlossaryTerm, User
from server import auth
from server.migrator import (
    sync_filesystem_to_db,
    ms_to_timestamp,
    timestamp_to_ms,
    extract_simple_keywords,
    get_folder_name,
    get_or_create_folder,
)
from server.ai_service import (
    GEMINI_TIMEOUT_MS,
    GLOSSARY_MAX_TERMS,
    load_glossary_terms,
    resolve_end_times,
    terminate_active_media_processes,
    extract_keywords_ai,
    generate_summary_ai,
    stream_board_chat,
    process_audio_file_to_board,
    sanitize_filename,
)

app = FastAPI(title="다글로 (daglo) AI 풀스택 서버", version="3.0.0")

# 쿠키 인증을 쓰므로 와일드카드 오리진은 허용하지 않는다.
# 외부 프론트엔드에서 접근해야 한다면 .env 에 ALLOWED_ORIGINS 를 쉼표로 나열한다.
ALLOWED_ORIGINS = [o.strip() for o in os.getenv("ALLOWED_ORIGINS", "").split(",") if o.strip()]
if ALLOWED_ORIGINS:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=ALLOWED_ORIGINS,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )


# -----------------
# 인증 게이트 미들웨어
# 아래 목록을 뺀 모든 경로(정적 파일·오디오 스트림 포함)는 로그인해야 열린다.
# -----------------
# 로그인 페이지 주소. `/login` 은 스캐너 봇이 가장 먼저 두드리는 경로라서 바꿀 수 있게 했다.
# .env 에 LOGIN_PATH=조금-긴-임의문자열 처럼 적으면 그 주소에서만 로그인 화면이 열린다.
LOGIN_PATH = "/" + (os.getenv("LOGIN_PATH", "login").strip().strip("/") or "login")

# 로그인하지 않은 브라우저가 루트(/)로 들어왔을 때 로그인 페이지로 안내할지 여부.
# off 로 두면 루트조차 404 가 되어, 주소를 아는 사람만 로그인 화면에 닿을 수 있다.
LOGIN_REDIRECT = os.getenv("LOGIN_REDIRECT", "on").strip().lower() not in ("0", "off", "false", "no")

PUBLIC_PATHS = {
    LOGIN_PATH,
    "/api/ping",
    "/api/auth/status",
    "/api/auth/login",
    "/api/auth/setup",
    "/favicon.ico",
}


@app.middleware("http")
async def auth_gate(request: Request, call_next):
    path = request.url.path

    if path in PUBLIC_PATHS or request.method == "OPTIONS":
        return await call_next(request)

    # 자동화 스크립트/외부 클라이언트는 DAGLO_API_TOKEN 헤더로 통과할 수 있다.
    if auth.api_token_ok(request):
        return await call_next(request)

    db = SessionLocal()
    try:
        user = auth.resolve_session_user(db, request.cookies.get(auth.SESSION_COOKIE))
    finally:
        db.close()

    if user is None:
        if path.startswith("/api/"):
            return JSONResponse(status_code=401, content={"detail": "로그인이 필요합니다."})
        # 로그인 안내는 루트로 들어온 사람에게만 한다.
        # /login·/admin·/wp-login.php 처럼 봇이 찍어 보는 경로는 리다이렉트로 힌트를 주지 않고
        # 그냥 없는 페이지로 응답한다. (스캐너 입장에서는 빈 서버로 보인다)
        if LOGIN_REDIRECT and path == "/":
            return RedirectResponse(url=LOGIN_PATH, status_code=302)
        return Response(content="Not Found", status_code=404, media_type="text/plain")

    return await call_next(request)


BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STATIC_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "static")
AUDIO_DIR = os.path.join(BASE_DIR, "녹음파일원본")
RESULT_DIR = os.path.join(BASE_DIR, "강의 녹음 변환")
# 웹에서 지운 파일을 옮겨 두는 곳. 감시 폴더 밖이라 탐색기 목록에서 사라진다.
TRASH_DIR = os.path.join(BASE_DIR, "휴지통")
TIMESTAMP_RE = re.compile(r'\[(\d{1,2}:\d{2}(?::\d{2})?)\]')

# -----------------
# STT 작업 큐 + 워커 (동시 실행 수를 제한해 API/메모리 폭주를 막는다)
# -----------------
VALID_AUDIO_EXTS = (".mp3", ".m4a", ".wav", ".mp4")
STT_WORKERS = max(1, int(os.getenv("STT_WORKERS", "2")))
# 워커가 이 시간(초) 동안 진행 신호를 못 주면 멈춘 것으로 보고 경고를 남긴다
STT_STALL_WARN_SEC = max(60, int(os.getenv("STT_STALL_WARN_SEC", "1800")))
# 큐에 넣은 직후의 짧은 순간을 '유실된 작업'으로 오인하지 않기 위한 유예 시간(초)
STT_GHOST_GRACE_SEC = max(10, int(os.getenv("STT_GHOST_GRACE_SEC", "30")))

stt_queue: "queue.Queue[int]" = queue.Queue()
_queued_board_ids = set()          # 큐에 들어 있거나 워커가 잡고 있는 보드
_queued_at: dict = {}              # board_id -> 큐에 넣은 시각
_inflight: dict = {}               # board_id -> {"worker", "started", "beat"}
_worker_threads: list = []
_ghost_streaks: dict = {}          # board_id -> 유실 의심 연속 관측 횟수
_queue_lock = threading.Lock()


def enqueue_board(board_id: int) -> bool:
    """이미 큐에 있거나 처리 중인 보드는 중복 투입하지 않는다."""
    with _queue_lock:
        if board_id in _queued_board_ids:
            return False
        _queued_board_ids.add(board_id)
        _queued_at[board_id] = time.time()
    stt_queue.put(board_id)
    return True


def _release_board(board_id: int) -> None:
    with _queue_lock:
        _inflight.pop(board_id, None)
        _queued_board_ids.discard(board_id)
        _queued_at.pop(board_id, None)


def _heartbeat(board_id: int) -> None:
    """워커가 살아서 진행 중임을 알린다. 이 신호가 끊기면 정체로 판단한다."""
    with _queue_lock:
        info = _inflight.get(board_id)
        if info is not None:
            info["beat"] = time.time()


def _mark_board_failed(board_id: int, message) -> None:
    """보드를 실패로 기록한다. 여기서 나는 예외는 워커를 죽이지 않도록 삼킨다."""
    db = SessionLocal()
    try:
        board = db.query(Board).filter_by(id=board_id).first()
        if board and board.status in ("PENDING", "PROCESSING"):
            board.status = "FAILED"
            board.progress_percent = 0
            board.error_message = str(message)[:500]
            db.commit()
    except Exception as e:
        print(f"[STT-WORKER-WARN] Board #{board_id} 실패 상태 기록 실패: {e}", flush=True)
        try:
            db.rollback()
        except Exception:
            pass
    finally:
        db.close()


def _run_one_board(board_id: int) -> None:
    db = SessionLocal()
    audio_path = None
    try:
        board = db.query(Board).filter_by(id=board_id).first()
        if board is None:
            return
        if board.audio_path and os.path.exists(board.audio_path):
            audio_path = board.audio_path
            board.status = "PROCESSING"
            board.progress_percent = 1
            board.error_message = None
        else:
            board.status = "FAILED"
            board.progress_percent = 0
            board.error_message = "오디오 파일을 찾을 수 없습니다."
        db.commit()
    finally:
        db.close()

    if audio_path:
        process_audio_file_to_board(
            board_id,
            audio_path,
            SessionLocal,
            progress_callback=lambda _pct, _bid=board_id: _heartbeat(_bid),
        )


def stt_worker():
    """큐에서 보드를 하나씩 꺼내 변환한다.

    이 루프는 어떤 예외에도 절대 빠져나가면 안 된다. 워커 스레드가 조용히 죽으면
    큐를 읽는 사람이 없어져 모든 보드가 '변환 대기 중'에서 영구히 멈춘다.
    """
    name = threading.current_thread().name
    while True:
        try:
            board_id = stt_queue.get()
        except Exception as get_err:
            print(f"[STT-WORKER-FATAL] {name} 큐 읽기 실패: {get_err}", flush=True)
            time.sleep(1)
            continue

        with _queue_lock:
            # 회수 로직과 겹쳐 같은 보드가 두 번 들어왔다면 먼저 잡은 워커에게 맡긴다
            duplicate = board_id in _inflight
            if not duplicate:
                now = time.time()
                _inflight[board_id] = {"worker": name, "started": now, "beat": now}
        if duplicate:
            try:
                stt_queue.task_done()
            except Exception:
                pass
            continue

        try:
            _run_one_board(board_id)
        except Exception as e:
            print(f"[STT-WORKER-ERROR] Board #{board_id}: {e}", flush=True)
            _mark_board_failed(board_id, e)
        finally:
            _release_board(board_id)
            try:
                stt_queue.task_done()
            except Exception:
                pass


def ensure_workers_alive() -> int:
    """죽은 워커 스레드를 채워 넣는다. 새로 띄운 개수를 돌려준다."""
    to_start = []
    with _queue_lock:
        alive = [t for t in _worker_threads if t.is_alive()]
        _worker_threads[:] = alive
        for i in range(STT_WORKERS - len(alive)):
            t = threading.Thread(
                target=stt_worker,
                name=f"stt-worker-{len(_worker_threads) + i + 1}",
                daemon=True,
            )
            _worker_threads.append(t)
            to_start.append(t)
    for t in to_start:
        t.start()
    return len(to_start)


def requeue_pending_boards(db: Session) -> None:
    """원본이 살아 있는 PENDING 보드를 큐에 넣는다 (업로드/재시도/감시 누락 회수)."""
    pending = (
        db.query(Board)
        .filter(Board.status == "PENDING", Board.is_deleted == False)  # noqa: E712
        .all()
    )
    for b in pending:
        if b.audio_path and os.path.exists(b.audio_path):
            enqueue_board(b.id)


def recover_stalled_boards(db: Session) -> None:
    """큐·워커에서 사라졌는데 DB에는 대기/변환 중으로 남은 보드를 되살린다.

    - PROCESSING 인데 잡고 있는 워커가 없다 → 워커가 죽은 것이므로 PENDING 으로 되돌린다.
    - PENDING 인데 '큐에 넣었다'는 표시만 남고 큐도 비어 있고 워커도 안 잡고 있다
      → 작업이 유실된 것이므로 표시를 지우고 다시 넣는다.
    이 두 가지가 '서버에서 변환 대기 중'에서 영원히 안 넘어가던 원인이다.
    """
    now = time.time()
    with _queue_lock:
        queued_ids = set(_queued_board_ids)
        queued_at = dict(_queued_at)
        inflight = {bid: dict(info) for bid, info in _inflight.items()}
    queue_empty = stt_queue.empty()

    # 1) 담당 워커가 사라진 PROCESSING 보드 회수
    orphans = [
        b for b in db.query(Board).filter(Board.status == "PROCESSING").all()
        if b.id not in inflight
    ]
    if orphans:
        for b in orphans:
            b.status = "PENDING"
            b.progress_percent = 0
            b.error_message = None
        db.commit()
        for b in orphans:
            _release_board(b.id)
            print(
                f"[STT-RECOVER] 담당 워커가 사라진 보드 #{b.id} ({b.title}) 를 대기열로 되돌렸습니다.",
                flush=True,
            )

    # 2) 큐 표시만 남고 실제 작업이 사라진 PENDING 보드 회수
    if queue_empty and queued_ids:
        ghosts = (
            db.query(Board)
            .filter(Board.status == "PENDING", Board.is_deleted == False)  # noqa: E712
            .filter(Board.id.in_(queued_ids))
            .all()
        )
        ghost_ids = set()
        for b in ghosts:
            if b.id in inflight:
                continue
            if now - queued_at.get(b.id, now) < STT_GHOST_GRACE_SEC:
                continue
            if not (b.audio_path and os.path.exists(b.audio_path)):
                continue
            ghost_ids.add(b.id)
            # 워커가 큐에서 막 꺼낸 찰나를 유실로 오인하지 않도록 연속 2회 관측을 요구한다
            if _ghost_streaks.get(b.id, 0) + 1 < 2:
                _ghost_streaks[b.id] = _ghost_streaks.get(b.id, 0) + 1
                continue
            _ghost_streaks.pop(b.id, None)
            _release_board(b.id)
            if enqueue_board(b.id):
                print(
                    f"[STT-RECOVER] 큐에서 유실된 보드 #{b.id} ({b.title}) 를 다시 넣었습니다.",
                    flush=True,
                )
        for gone in set(_ghost_streaks) - ghost_ids:
            _ghost_streaks.pop(gone, None)
    else:
        _ghost_streaks.clear()

    # 3) 오래도록 진행 신호가 없는 작업은 경고를 남긴다 (API 응답 지연/네트워크 정체)
    for bid, info in inflight.items():
        silent = now - info.get("beat", info.get("started", now))
        if silent > STT_STALL_WARN_SEC:
            print(
                f"[STT-STALL] 보드 #{bid} 가 {int(silent // 60)}분째 진행 신호가 없습니다 "
                f"(워커 {info.get('worker')}). GEMINI_TIMEOUT_MS 설정을 확인하세요.",
                flush=True,
            )


# -----------------
# 원본 파일 삭제 → 보드 삭제 동기화
# -----------------
# 탐색기에서 원본 녹음을 지우면 보드도 따라 사라지게 한다.
#   trash     : 휴지통으로 보낸다 (기본값 — 실수로 지웠어도 복구할 수 있다)
#   permanent : 보드와 스크립트를 즉시 완전 삭제한다
#   off       : 동기화하지 않는다
FILE_DELETE_SYNC = os.getenv("FILE_DELETE_SYNC", "trash").lower()

# 네트워크 드라이브(RaiDrive/SFTP)가 잠깐 끊긴 것을 삭제로 오인하지 않도록 연속 감지를 요구한다
MISSING_SCANS_BEFORE_DELETE = max(1, int(os.getenv("MISSING_SCANS_BEFORE_DELETE", "3")))
# 한 번의 스캔에서 이보다 많은 보드가 사라졌다면 마운트 장애로 보고 손대지 않는다
MAX_AUTO_DELETE_PER_SCAN = max(1, int(os.getenv("MAX_AUTO_DELETE_PER_SCAN", "50")))


def is_inside(path: str, base: str) -> bool:
    """path 가 base 폴더 안쪽인지 판별한다."""
    if not path:
        return False
    try:
        return os.path.commonpath([
            os.path.normcase(os.path.abspath(path)),
            os.path.normcase(os.path.abspath(base)),
        ]) == os.path.normcase(os.path.abspath(base))
    except ValueError:
        # 드라이브 문자가 다르면 commonpath 가 예외를 낸다 = 바깥
        return False


def is_watched_path(path: str) -> bool:
    """감시 폴더(`녹음파일원본`) 안쪽 경로인지 판별한다."""
    return is_inside(path, AUDIO_DIR)


def is_managed_file(path: str) -> bool:
    """서버가 관리하는 폴더 안의 파일인지 확인한다.

    파일을 옮기거나 지우기 전에 반드시 통과시킨다. 이 밖에 있는 경로는
    사용자가 직접 가리킨 남의 파일일 수 있으므로 절대 건드리지 않는다.
    """
    return any(is_inside(path, base) for base in (AUDIO_DIR, RESULT_DIR, TRASH_DIR))


# -----------------
# 웹에서 삭제 → 탐색기에서도 사라지게 (파일 삭제 동기화, 위 동기화의 반대 방향)
# -----------------
# 웹에서 보드를 지웠을 때 원본 오디오/변환 텍스트를 어떻게 할지 정한다.
#   trash     : `휴지통` 폴더로 옮긴다 (기본값 — 탐색기에서는 사라지고, 복원하면 제자리로 돌아온다)
#   permanent : 휴지통으로 보내는 삭제에서도 파일을 즉시 지운다 (되돌릴 수 없다)
#   off       : 파일을 건드리지 않는다 (예전 동작. 감시 스레드가 보드를 되살릴 수 있다)
WEB_DELETE_SYNC = os.getenv("WEB_DELETE_SYNC", "trash").lower()


def board_trash_dir(board_id: int) -> str:
    return os.path.join(TRASH_DIR, f"board_{board_id}")


def unique_path(path: str) -> str:
    """같은 이름이 이미 있으면 뒤에 _1, _2 를 붙여 겹치지 않는 경로를 만든다."""
    if not os.path.exists(path):
        return path
    stem, ext = os.path.splitext(path)
    for n in range(1, 1000):
        candidate = f"{stem}_{n}{ext}"
        if not os.path.exists(candidate):
            return candidate
    return f"{stem}_{int(time.time())}{ext}"


def read_trash_meta(board_id: int) -> dict:
    """휴지통에 넣을 때 남긴 원래 경로 기록을 읽는다."""
    try:
        with open(os.path.join(board_trash_dir(board_id), "meta.json"), "r", encoding="utf-8") as f:
            meta = json.load(f)
        return meta if isinstance(meta, dict) else {}
    except (OSError, ValueError):
        return {}


def write_trash_meta(board_id: int, meta: dict) -> None:
    target = board_trash_dir(board_id)
    os.makedirs(target, exist_ok=True)
    with open(os.path.join(target, "meta.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)


# 변환 텍스트 옆에 같이 만들어지는 동반 파일들 (받아쓰기py.py / 자막저장서버.py 산출물).
# 보드를 지웠는데 이것만 탐색기에 남아 있으면 '지운 것 같지 않으므로' 함께 정리한다.
TRANSCRIPT_COMPANION_SUFFIXES = ("_강의스크립트.html", "_수정본.txt")


def board_file_targets(board: Board) -> list:
    """보드에 딸린 파일들을 (meta 키, 경로) 목록으로 모은다.

    키가 'audio_path'/'txt_path' 인 항목은 DB 컬럼이 함께 따라다녀야 하는 파일이고,
    'extra:N' 은 동반 파일이라 경로를 DB에 들고 있지 않다.
    """
    targets = []
    for attr in ("audio_path", "txt_path"):
        path = getattr(board, attr, None)
        if path:
            targets.append((attr, path))

    txt_path = getattr(board, "txt_path", None)
    if txt_path:
        stem = os.path.splitext(txt_path)[0]
        for i, suffix in enumerate(TRANSCRIPT_COMPANION_SUFFIXES):
            companion = stem + suffix
            if os.path.isfile(companion):
                targets.append((f"extra:{i}", companion))
    return targets


def move_board_files_to_trash(board: Board) -> bool:
    """보드의 원본 오디오와 변환 텍스트를 `휴지통` 폴더로 옮긴다.

    감시 폴더 밖으로 빼기 때문에 탐색기에서 파일이 사라지고, 감시 스레드가
    '원본이 다시 나타났다'며 휴지통의 보드를 되살리는 일도 없어진다.
    복원하면 기록해 둔 원래 경로로 되돌아간다.
    """
    if WEB_DELETE_SYNC == "off":
        return False
    if WEB_DELETE_SYNC == "permanent":
        return purge_board_files(board)

    meta = read_trash_meta(board.id) or {"board_id": board.id, "files": {}}
    meta.setdefault("files", {})
    meta["title"] = board.title
    meta["deleted_at"] = datetime.datetime.now().isoformat(timespec="seconds")

    moved = []
    for key, src in board_file_targets(board):
        if not os.path.isfile(src):
            continue
        if is_inside(src, TRASH_DIR):      # 이미 휴지통 안이다
            continue
        if not is_managed_file(src):       # 서버가 관리하는 폴더 밖은 손대지 않는다
            continue
        try:
            os.makedirs(board_trash_dir(board.id), exist_ok=True)
            dest = unique_path(os.path.join(board_trash_dir(board.id), os.path.basename(src)))
            shutil.move(src, dest)
        except OSError as e:
            print(f"[WEB-DELETE-WARN] #{board.id} {key} 휴지통 이동 실패 ({src}): {e}", flush=True)
            continue
        meta["files"][key] = {"original": src, "trash": dest}
        if key in ("audio_path", "txt_path"):
            setattr(board, key, dest)
        moved.append(os.path.basename(dest))

    if moved:
        write_trash_meta(board.id, meta)
        print(
            f"[WEB-DELETE] #{board.id} {board.title}: 파일 {len(moved)}개를 휴지통으로 옮겼습니다 "
            f"({', '.join(moved)})",
            flush=True,
        )
    return bool(moved)


def restore_board_files_from_trash(board: Board) -> bool:
    """휴지통으로 옮겼던 파일을 원래 자리로 되돌린다."""
    meta = read_trash_meta(board.id)
    files = meta.get("files") or {}
    if not files:
        return False

    leftover = {}
    restored = []
    # 오디오·텍스트를 먼저 되돌려야 동반 파일 경로 계산이 어긋나지 않는다
    for key in sorted(files, key=lambda k: (k.startswith("extra:"), k)):
        info = files.get(key) or {}
        src, original = info.get("trash"), info.get("original")
        if not src or not original or not os.path.isfile(src):
            continue
        if not is_managed_file(original) or is_inside(original, TRASH_DIR):
            leftover[key] = info
            continue
        try:
            os.makedirs(os.path.dirname(original), exist_ok=True)
            dest = unique_path(original)
            shutil.move(src, dest)
        except OSError as e:
            print(f"[WEB-RESTORE-WARN] #{board.id} {key} 복원 실패 ({src}): {e}", flush=True)
            leftover[key] = info
            continue
        if key in ("audio_path", "txt_path"):
            setattr(board, key, dest)
            if key == "audio_path":
                board.audio_filename = os.path.basename(dest)
        restored.append(os.path.basename(dest))

    if leftover:
        meta["files"] = leftover
        write_trash_meta(board.id, meta)
    else:
        shutil.rmtree(board_trash_dir(board.id), ignore_errors=True)

    if restored:
        print(
            f"[WEB-RESTORE] #{board.id} {board.title}: 파일 {len(restored)}개를 원래 자리로 되돌렸습니다 "
            f"({', '.join(restored)})",
            flush=True,
        )
    return bool(restored)


def purge_board_files(board: Board) -> bool:
    """보드의 파일을 실제로 지운다 (완전 삭제). 휴지통에 남은 사본까지 함께 정리한다."""
    if WEB_DELETE_SYNC == "off":
        return False

    removed = []
    for key, path in board_file_targets(board):
        if not os.path.isfile(path) or not is_managed_file(path):
            continue
        try:
            os.remove(path)
            removed.append(os.path.basename(path))
        except OSError as e:
            print(f"[WEB-DELETE-WARN] #{board.id} {key} 삭제 실패 ({path}): {e}", flush=True)

    target = board_trash_dir(board.id)
    if os.path.isdir(target) and is_inside(target, TRASH_DIR):
        shutil.rmtree(target, ignore_errors=True)

    if removed:
        print(
            f"[WEB-DELETE] #{board.id} {board.title}: 파일 {len(removed)}개를 완전히 삭제했습니다 "
            f"({', '.join(removed)})",
            flush=True,
        )
    return bool(removed)


def repair_stale_media_paths(db: Session) -> None:
    """프로젝트 폴더를 옮겨 경로가 어긋난 보드를 현재 위치로 다시 연결한다.

    DB에는 절대 경로가 저장되므로 폴더를 옮기면 오디오 재생이 끊기고,
    삭제 동기화가 '파일이 사라졌다'고 오해할 수도 있다. 파일 이름이 같은 파일을
    현재 폴더에서 찾아 경로만 갱신한다.
    """
    def build_index(root: str, exts: tuple) -> dict:
        index = {}
        if not os.path.isdir(root):
            return index
        for root_dir, _, files in os.walk(root):
            for f in files:
                if f.lower().endswith(exts):
                    index.setdefault(unicodedata.normalize("NFC", f), os.path.join(root_dir, f))
        return index

    audio_index = build_index(AUDIO_DIR, VALID_AUDIO_EXTS)
    txt_index = build_index(RESULT_DIR, (".txt",))
    repaired = 0

    for board in db.query(Board).all():
        for attr, index in (("audio_path", audio_index), ("txt_path", txt_index)):
            path = getattr(board, attr)
            if not path or os.path.exists(path):
                continue
            found = index.get(unicodedata.normalize("NFC", os.path.basename(path)))
            if found:
                setattr(board, attr, found)
                if attr == "audio_path":
                    board.audio_filename = os.path.basename(found)
                repaired += 1

    if repaired:
        db.commit()
        print(f"[PATH-REPAIR] 위치가 바뀐 파일 경로 {repaired}건을 현재 폴더 기준으로 고쳤습니다.", flush=True)


def find_moved_board(db: Session, new_path: str, mtime: datetime.datetime):
    """탐색기에서 옮기거나 이름만 바꾼 파일인지 찾아본다.

    파일을 옮기거나 이름을 바꿔도 수정시각(mtime)은 그대로다. 그래서 '원본이 사라진 보드' 중
    수정시각이 같은 것이 딱 하나 있으면 같은 녹음으로 보고 그 보드를 따라 옮긴다.
    이렇게 하지 않으면 이름만 바꿔도 새 보드가 생겨 처음부터 다시 받아쓰기를 돌리고,
    기존 스크립트와 직접 고친 내용이 휴지통으로 사라진다.

    후보가 둘 이상이면(같은 시각에 복사된 파일들 등) 잘못 이어 붙일 수 있으므로 포기한다.
    """
    ext = os.path.splitext(new_path)[1].lower()
    candidates = []
    for board in db.query(Board).filter(Board.audio_path.isnot(None)).all():
        if not board.audio_path or os.path.normcase(board.audio_path) == os.path.normcase(new_path):
            continue
        if os.path.splitext(board.audio_path)[1].lower() != ext:
            continue
        if board.recorded_at is None:
            continue
        # 원본이 아직 제자리에 있으면 옮겨진 것이 아니다
        if os.path.exists(board.audio_path):
            continue
        if abs((board.recorded_at - mtime).total_seconds()) <= 2:
            candidates.append(board)

    return candidates[0] if len(candidates) == 1 else None


def sync_deleted_audio_to_boards(db: Session, missing_streaks: dict) -> None:
    """원본 오디오가 사라진 보드를 정리한다. 스캔마다 한 번씩 호출된다."""
    if FILE_DELETE_SYNC == "off":
        return

    # 감시 폴더 자체가 안 보이면 드라이브가 분리된 것이다. 전량 삭제를 막기 위해 판단을 보류한다.
    if not os.path.isdir(AUDIO_DIR):
        missing_streaks.clear()
        return

    boards = (
        db.query(Board)
        .filter(
            Board.is_deleted == False,  # noqa: E712
            Board.audio_path.isnot(None),
            Board.audio_path != "",
            # 변환 중인 보드는 워커가 끝낼 때까지 건드리지 않는다
            Board.status != "PROCESSING",
        )
        .all()
    )

    doomed = []
    live_ids = set()
    for board in boards:
        # 감시 폴더 밖을 가리키는 보드는 '탐색기에서 지웠다'고 볼 수 없다 (옛 경로 등) → 건너뛴다
        if not is_watched_path(board.audio_path):
            missing_streaks.pop(board.id, None)
            continue
        live_ids.add(board.id)
        if os.path.exists(board.audio_path):
            missing_streaks.pop(board.id, None)
            continue
        streak = missing_streaks.get(board.id, 0) + 1
        missing_streaks[board.id] = streak
        if streak >= MISSING_SCANS_BEFORE_DELETE:
            doomed.append(board)

    # 이미 처리된 보드의 추적 정보를 정리한다
    for gone_id in set(missing_streaks) - live_ids:
        missing_streaks.pop(gone_id, None)

    if not doomed:
        return

    if len(doomed) > MAX_AUTO_DELETE_PER_SCAN:
        print(
            f"[FILE-SYNC-SKIP] 원본이 사라진 보드가 {len(doomed)}개로 한도"
            f"({MAX_AUTO_DELETE_PER_SCAN})를 넘었습니다. 저장소 연결 문제일 수 있어 삭제하지 않습니다. "
            f"의도한 삭제라면 MAX_AUTO_DELETE_PER_SCAN 값을 올리세요.",
            flush=True,
        )
        return

    for board in doomed:
        missing_streaks.pop(board.id, None)
        label = f"#{board.id} {board.title}"
        if FILE_DELETE_SYNC == "permanent":
            # 보드를 지우면 스크립트/요약/북마크도 cascade 로 함께 사라진다
            if board.txt_path and os.path.exists(board.txt_path):
                try:
                    os.remove(board.txt_path)
                except OSError as e:
                    print(f"[FILE-SYNC-WARN] 변환 텍스트 삭제 실패 ({board.txt_path}): {e}", flush=True)
            db.delete(board)
            print(f"[FILE-SYNC] 원본이 삭제되어 보드를 완전히 지웠습니다: {label}", flush=True)
        else:
            board.is_deleted = True
            print(f"[FILE-SYNC] 원본이 삭제되어 보드를 휴지통으로 옮겼습니다: {label}", flush=True)

    db.commit()


# -----------------
# Background Auto Folder Watcher (SFTP/RaiDrive 자동 감지 엔진)
# -----------------
def background_audio_watcher():
    """`녹음파일원본`에 복사된 새 오디오를 감지해 PENDING 보드로 등록하고 STT 큐에 넣는다."""
    time.sleep(3)
    seen_sizes = {}
    missing_streaks = {}

    while True:
        db = None
        try:
            db = SessionLocal()
            live_paths = set()

            for root_dir, _, files in os.walk(AUDIO_DIR):
                audio_files = [f for f in files if f.lower().endswith(VALID_AUDIO_EXTS)]
                # 오디오가 없는 빈 디렉터리로 삭제된 폴더가 되살아나지 않도록 건너뛴다
                if not audio_files:
                    continue

                folder = get_or_create_folder(db, get_folder_name(root_dir, AUDIO_DIR))

                for f in audio_files:
                    full_path = os.path.join(root_dir, f)
                    live_paths.add(full_path)
                    try:
                        current_size = os.path.getsize(full_path)
                    except OSError:
                        continue

                    # 복사가 끝났는지 확인: 크기가 4초 이상 변하지 않아야 한다
                    last = seen_sizes.get(full_path)
                    if last is None or last[0] != current_size:
                        seen_sizes[full_path] = (current_size, time.time())
                        continue
                    if time.time() - last[1] < 4:
                        continue

                    base_name = os.path.splitext(f)[0]
                    file_mtime = datetime.datetime.fromtimestamp(os.path.getmtime(full_path))
                    board = db.query(Board).filter_by(folder_id=folder.id, title=base_name).first()

                    if board is None:
                        # 새 파일처럼 보여도, 탐색기에서 이름을 바꾸거나 폴더를 옮긴 것일 수 있다.
                        # 그런 경우 기존 보드를 따라 옮겨서 스크립트를 살리고 재변환도 피한다.
                        moved = find_moved_board(db, full_path, file_mtime)
                        if moved is not None:
                            old_label = f"{moved.title} ({moved.audio_path})"
                            moved.folder_id = folder.id
                            moved.title = base_name
                            moved.audio_path = full_path
                            moved.audio_filename = f
                            if moved.is_deleted:
                                moved.is_deleted = False
                                shutil.rmtree(board_trash_dir(moved.id), ignore_errors=True)
                            db.commit()
                            board = moved
                            print(
                                f"[FILE-SYNC] 파일이 이동/이름변경된 것으로 보고 보드를 따라 옮겼습니다: "
                                f"#{moved.id} {old_label} -> {full_path}",
                                flush=True,
                            )

                    if board is None:
                        board = Board(
                            folder_id=folder.id,
                            title=base_name,
                            audio_path=full_path,
                            audio_filename=f,
                            status="PENDING",
                            progress_percent=0,
                            keywords_json="[]",
                            recorded_at=file_mtime,
                        )
                        db.add(board)
                        db.commit()
                        db.refresh(board)
                        print(f"[AUTO-DETECT] New audio found: {f} (Board #{board.id})")
                    elif not board.audio_path:
                        board.audio_path = full_path
                        board.audio_filename = f
                        db.commit()

                    # 지웠던 원본을 다시 넣으면 휴지통에서 되살린다 (삭제 동기화의 역동작)
                    if board.is_deleted and FILE_DELETE_SYNC != "off":
                        board.is_deleted = False
                        board.audio_path = full_path
                        board.audio_filename = f
                        db.commit()
                        # 사용자가 직접 되돌려 놓은 것이므로 휴지통에 남은 사본은 정리한다
                        shutil.rmtree(board_trash_dir(board.id), ignore_errors=True)
                        print(f"[FILE-SYNC] 원본이 다시 나타나 보드를 복원했습니다: #{board.id} {board.title}", flush=True)

                    if board.status == "PENDING" and not board.is_deleted:
                        if enqueue_board(board.id):
                            print(f"[QUEUE] Board #{board.id} ({board.title}) queued for STT.")

            # 사라진 파일의 추적 정보를 정리해 메모리가 무한히 늘지 않게 한다
            for gone in set(seen_sizes) - live_paths:
                seen_sizes.pop(gone, None)

            # 탐색기에서 원본을 지웠다면 보드도 따라 정리한다
            sync_deleted_audio_to_boards(db, missing_streaks)

        except Exception as e:
            print(f"[WATCHER-ERROR] {e}", flush=True)
        finally:
            if db is not None:
                db.close()

        # 파일 스캔이 실패해도 대기열 회수는 반드시 돌아야 한다.
        # (예전에는 같은 try 안에 있어서, 스캔에서 예외가 한 번 나면
        #  PENDING 보드가 영영 큐에 안 들어가고 '변환 대기 중'에서 멈췄다)
        db = None
        try:
            db = SessionLocal()
            recover_stalled_boards(db)
            requeue_pending_boards(db)
        except Exception as e:
            print(f"[QUEUE-RECOVER-ERROR] {e}", flush=True)
        finally:
            if db is not None:
                db.close()

        # 워커가 죽었으면 다시 띄운다 (워커가 0개가 되면 큐를 읽는 사람이 없어진다)
        try:
            restarted = ensure_workers_alive()
            if restarted:
                print(f"[STT-WORKER] 죽어 있던 워커 {restarted}개를 다시 시작했습니다.", flush=True)
        except Exception as e:
            print(f"[WORKER-SUPERVISOR-ERROR] {e}", flush=True)

        time.sleep(5)


@app.on_event("startup")
def startup_event():
    init_db()
    db = SessionLocal()
    try:
        auth.bootstrap_admin_from_env(db)
        if not auth.has_any_user(db):
            port = os.getenv("PORT", "8000")
            print(f"[AUTH] 등록된 계정이 없습니다. http://localhost:{port}{LOGIN_PATH} 에서 첫 관리자 계정을 만드세요.")
        auth.purge_expired_sessions(db)
        # 프로젝트 폴더 이동으로 끊긴 경로를 먼저 복구해야 삭제 동기화가 오작동하지 않는다
        repair_stale_media_paths(db)
        sync_filesystem_to_db(db)
        # 이전 실행이 종료되며 중단된 변환 작업을 회수한다 (워커 스레드는 프로세스와 함께 죽는다)
        stuck = db.query(Board).filter(Board.status == "PROCESSING").all()
        for b in stuck:
            b.status = "PENDING"
            b.progress_percent = 0
        if stuck:
            db.commit()
            print(f"[INFO] Requeued {len(stuck)} interrupted board(s).")
        print("[INFO] DB initialized and filesystem synced.")
    finally:
        db.close()

    started = ensure_workers_alive()
    print(f"[INFO] {started} STT worker(s) started.")

    # 백그라운드 폴더 감시 스레드 가동
    watcher_thread = threading.Thread(target=background_audio_watcher, daemon=True)
    watcher_thread.start()


@app.on_event("shutdown")
def shutdown_event():
    # 워커 스레드는 데몬이라 프로세스와 함께 사라지지만, 그 아래 ffmpeg 자식은 남는다.
    # 남겨 두면 systemd 가 유닛을 내리지 못하고 stop 타임아웃까지 매달린다.
    killed = terminate_active_media_processes()
    if killed:
        print(f"[SHUTDOWN] 실행 중이던 ffmpeg 프로세스 {killed}개를 정리했습니다.", flush=True)

# -----------------
# Pydantic Schemas
# -----------------
class FolderCreate(BaseModel):
    name: str

class FolderUpdate(BaseModel):
    name: str

class BoardUpdate(BaseModel):
    title: Optional[str] = None
    folder_id: Optional[int] = None
    is_starred: Optional[bool] = None

class BatchDeleteRequest(BaseModel):
    board_ids: List[int]
    permanent: bool = False

class BatchMoveRequest(BaseModel):
    board_ids: List[int]
    folder_id: int

class TranscriptUpdateRequest(BaseModel):
    full_text: Optional[str] = None
    segments: Optional[List[dict]] = None

class SpeakerRenameRequest(BaseModel):
    old_name: str
    new_name: str

class BookmarkCreateRequest(BaseModel):
    timestamp_ms: int
    timestamp_str: str
    note: Optional[str] = ""

class GlossaryItem(BaseModel):
    term: str
    note: Optional[str] = ""


class GlossaryReplaceRequest(BaseModel):
    # folder_id 가 없으면 모든 폴더에 적용되는 공통 용어를 교체한다
    folder_id: Optional[int] = None
    terms: List[GlossaryItem] = []


class SummaryRequest(BaseModel):
    summary_type: str = "BASIC"  # BASIC, MEETING, ACTION_ITEM, QUIZ, SLIDE

class ChatMessageRequest(BaseModel):
    message: str

def srt_timestamp(ms: int) -> str:
    """SRT 규격 타임코드(HH:MM:SS,mmm)를 만든다."""
    ms = max(0, int(ms))
    hours, rem = divmod(ms, 3_600_000)
    minutes, rem = divmod(rem, 60_000)
    seconds, millis = divmod(rem, 1000)
    return f"{hours:02d}:{minutes:02d}:{seconds:02d},{millis:03d}"


def parse_transcript_text(text: str) -> List[dict]:
    """`[MM:SS] 본문` 형식의 평문을 세그먼트 딕셔너리 목록으로 변환한다."""
    segments = []
    last_ms = 0
    for line in (text or "").splitlines():
        line = line.strip()
        if not line:
            continue
        match = TIMESTAMP_RE.search(line)
        if match:
            ts_str = match.group(1)
            last_ms = timestamp_to_ms(ts_str)
            content = line.replace(f"[{ts_str}]", "", 1).strip()
            stamp = f"[{ts_str}]"
        else:
            content = line
            stamp = f"[{ms_to_timestamp(last_ms)}]"
        segments.append({
            "start_time_ms": last_ms,
            "end_time_ms": last_ms + 10000,
            "timestamp_str": stamp,
            "speaker": "화자 1",
            "content": content,
        })
    return segments


def format_seconds(seconds: float) -> str:
    s = int(seconds)
    h = s // 3600
    m = (s % 3600) // 60
    sec = s % 60
    if h > 0:
        return f"{h}:{m:02d}:{sec:02d}"
    return f"{m:02d}:{sec:02d}"

# -----------------
# 0. Auth Endpoints
# -----------------
class LoginRequest(BaseModel):
    username: str
    password: str


class SetupRequest(BaseModel):
    username: str
    password: str
    display_name: Optional[str] = ""


class PasswordChangeRequest(BaseModel):
    current_password: str
    new_password: str


class UserCreateRequest(BaseModel):
    username: str
    password: str
    display_name: Optional[str] = ""
    is_admin: bool = False


@app.get("/api/ping")
def ping():
    """로그인 없이 열린 유일한 상태 확인용 엔드포인트(헬스체크/모니터링)."""
    return {"status": "ok"}


@app.get("/api/auth/status")
def auth_status(request: Request, db: Session = Depends(get_db)):
    """로그인 페이지가 '최초 설정'을 보여줄지 판단하는 데 쓴다."""
    user = auth.resolve_session_user(db, request.cookies.get(auth.SESSION_COOKIE))
    return {
        "setup_required": not auth.has_any_user(db),
        "authenticated": user is not None,
        "user": auth.user_to_dict(user) if user else None,
    }


@app.post("/api/auth/setup")
def auth_setup(req: SetupRequest, request: Request, response: Response, db: Session = Depends(get_db)):
    """계정이 하나도 없을 때만 첫 관리자 계정을 만든다."""
    if auth.has_any_user(db):
        raise HTTPException(status_code=409, detail="이미 계정이 있습니다. 로그인해 주세요.")
    user = auth.create_user(db, req.username, req.password, req.display_name or "", is_admin=True)
    token = auth.create_session(db, user, request)
    auth.set_session_cookie(response, token)
    return {"success": True, "user": auth.user_to_dict(user)}


@app.post("/api/auth/login")
def auth_login(req: LoginRequest, request: Request, response: Response, db: Session = Depends(get_db)):
    ip = auth.client_ip(request)
    remaining = auth.lockout_remaining(ip)
    if remaining:
        raise HTTPException(
            status_code=429,
            detail=f"로그인 시도가 너무 많습니다. {remaining // 60 + 1}분 후에 다시 시도해 주세요.",
        )

    username = (req.username or "").strip().lower()
    user = db.query(User).filter(User.username == username).one_or_none()
    # 아이디가 없어도 같은 비용의 해시 검증을 돌려 계정 존재 여부가 응답 시간으로 새지 않게 한다.
    stored = user.password_hash if user else auth.hash_password("dummy-password-placeholder")
    if not auth.verify_password(req.password or "", stored) or user is None or not user.is_active:
        auth.record_failure(ip)
        raise HTTPException(status_code=401, detail="아이디 또는 비밀번호가 올바르지 않습니다.")

    auth.clear_failures(ip)
    token = auth.create_session(db, user, request)
    auth.set_session_cookie(response, token)
    return {"success": True, "user": auth.user_to_dict(user)}


@app.post("/api/auth/logout")
def auth_logout(request: Request, response: Response, db: Session = Depends(get_db)):
    auth.destroy_session(db, request.cookies.get(auth.SESSION_COOKIE))
    auth.clear_session_cookie(response)
    return {"success": True}


@app.get("/api/auth/me")
def auth_me(user: User = Depends(auth.get_current_user)):
    return auth.user_to_dict(user)


@app.post("/api/auth/password")
def auth_change_password(
    req: PasswordChangeRequest,
    request: Request,
    db: Session = Depends(get_db),
    user: User = Depends(auth.get_current_user),
):
    if not auth.verify_password(req.current_password or "", user.password_hash):
        raise HTTPException(status_code=401, detail="현재 비밀번호가 올바르지 않습니다.")
    auth.validate_password(req.new_password)
    user.password_hash = auth.hash_password(req.new_password)
    db.commit()
    # 비밀번호를 바꾸면 지금 쓰는 브라우저만 남기고 다른 세션을 모두 끊는다.
    auth.destroy_all_sessions_for_user(
        db, user.id, keep_token=request.cookies.get(auth.SESSION_COOKIE)
    )
    return {"success": True}


@app.get("/api/auth/users")
def list_users(db: Session = Depends(get_db), admin: User = Depends(auth.get_current_admin)):
    users = db.query(User).order_by(User.id).all()
    return [auth.user_to_dict(u) for u in users]


@app.post("/api/auth/users")
def add_user(req: UserCreateRequest, db: Session = Depends(get_db),
             admin: User = Depends(auth.get_current_admin)):
    user = auth.create_user(db, req.username, req.password, req.display_name or "", req.is_admin)
    return auth.user_to_dict(user)


@app.delete("/api/auth/users/{user_id}")
def remove_user(user_id: int, db: Session = Depends(get_db),
                admin: User = Depends(auth.get_current_admin)):
    if user_id == admin.id:
        raise HTTPException(status_code=400, detail="자기 계정은 삭제할 수 없습니다.")
    user = db.query(User).filter(User.id == user_id).one_or_none()
    if user is None:
        raise HTTPException(status_code=404, detail="사용자를 찾을 수 없습니다.")
    if user.is_admin and db.query(User).filter(User.is_admin == True).count() <= 1:  # noqa: E712
        raise HTTPException(status_code=400, detail="마지막 관리자 계정은 삭제할 수 없습니다.")
    db.delete(user)
    db.commit()
    return {"success": True}


# -----------------
# 1. Folder Endpoints
# -----------------
@app.get("/api/health")
def health_check(db: Session = Depends(get_db)):
    with _queue_lock:
        workers_alive = sum(1 for t in _worker_threads if t.is_alive())
    return {
        "status": "ok",
        "boards": db.query(Board).filter_by(is_deleted=False).count(),
        "processing": db.query(Board).filter(Board.status.in_(["PROCESSING", "PENDING"])).count(),
        "queue_depth": stt_queue.qsize(),
        "workers": STT_WORKERS,
        "workers_alive": workers_alive,
        "ai_ready": bool(os.getenv("GEMINI_API_KEY") or os.getenv("GEMINI_API_KEY_PAID")),
    }


@app.get("/api/queue/status")
def queue_status(db: Session = Depends(get_db)):
    """'변환 대기 중'에서 안 넘어갈 때 어디가 막혔는지 보는 진단용 엔드포인트."""
    now = time.time()
    with _queue_lock:
        workers_alive = sum(1 for t in _worker_threads if t.is_alive())
        queued_ids = sorted(_queued_board_ids)
        inflight = [
            {
                "board_id": bid,
                "worker": info.get("worker"),
                "running_seconds": int(now - info.get("started", now)),
                "seconds_since_progress": int(now - info.get("beat", now)),
            }
            for bid, info in sorted(_inflight.items())
        ]
    return {
        "queue_depth": stt_queue.qsize(),
        "workers_configured": STT_WORKERS,
        "workers_alive": workers_alive,
        "queued_board_ids": queued_ids,
        "inflight": inflight,
        "pending_boards": db.query(Board).filter(
            Board.status == "PENDING", Board.is_deleted == False  # noqa: E712
        ).count(),
        "processing_boards": db.query(Board).filter(Board.status == "PROCESSING").count(),
        "gemini_timeout_ms": GEMINI_TIMEOUT_MS,
        "ai_ready": bool(os.getenv("GEMINI_API_KEY") or os.getenv("GEMINI_API_KEY_PAID")),
    }


@app.get("/api/debug/dbpath")
def get_dbpath():
    from server.database import DB_PATH
    return {"db_path": DB_PATH, "cwd": os.getcwd()}

@app.get("/api/folders")
def get_folders(db: Session = Depends(get_db)):
    folders = db.query(Folder).order_by(Folder.id).all()
    result = []
    for f in folders:
        count = db.query(Board).filter_by(folder_id=f.id, is_deleted=False).count()
        result.append({
            "id": f.id,
            "name": f.name,
            "board_count": count,
            "created_at": f.created_at.isoformat() if f.created_at else None
        })
    return result

@app.post("/api/folders")
def create_folder(req: FolderCreate, db: Session = Depends(get_db)):
    existing = db.query(Folder).filter_by(name=req.name.strip()).first()
    if existing:
        raise HTTPException(status_code=400, detail="이미 존재하는 폴더 이름입니다.")
    folder = Folder(name=req.name.strip())
    db.add(folder)
    db.commit()
    db.refresh(folder)

    os.makedirs(os.path.join(AUDIO_DIR, folder.name), exist_ok=True)
    os.makedirs(os.path.join(RESULT_DIR, folder.name), exist_ok=True)
    return {"id": folder.id, "name": folder.name, "board_count": 0}

@app.patch("/api/folders/{folder_id}")
def rename_folder(folder_id: int, req: FolderUpdate, db: Session = Depends(get_db)):
    folder = db.query(Folder).filter_by(id=folder_id).first()
    if not folder:
        raise HTTPException(status_code=404, detail="폴더를 찾을 수 없습니다.")
    folder.name = req.name.strip()
    db.commit()
    return {"id": folder.id, "name": folder.name}

@app.delete("/api/folders/{folder_id}")
def delete_folder(folder_id: int, db: Session = Depends(get_db)):
    folder = db.query(Folder).filter_by(id=folder_id).first()
    if not folder:
        raise HTTPException(status_code=404, detail="폴더를 찾을 수 없습니다.")
    if folder.name == "기본 폴더":
        raise HTTPException(status_code=400, detail="기본 폴더는 삭제할 수 없습니다.")
    
    # 소속 보드들을 기본 폴더로 이동
    default_folder = db.query(Folder).filter_by(name="기본 폴더").first()
    if default_folder:
        db.query(Board).filter_by(folder_id=folder.id).update({Board.folder_id: default_folder.id})

    folder_name = folder.name
    db.delete(folder)
    db.commit()

    # 빈 디렉터리를 남겨 두면 감시 스레드가 폴더를 되살리므로 함께 정리한다
    for base in (AUDIO_DIR, RESULT_DIR):
        target = os.path.join(base, folder_name)
        try:
            if os.path.isdir(target) and not os.listdir(target):
                os.rmdir(target)
        except OSError:
            pass

    return {"ok": True}

# -----------------
# 2. Board Endpoints
# -----------------
@app.get("/api/boards")
def get_boards(
    folder_id: Optional[int] = Query(None),
    filter_type: Optional[str] = Query(None),  # starred, trash, processing, all
    search: Optional[str] = Query(None),
    db: Session = Depends(get_db)
):
    query = db.query(Board)

    if filter_type == "trash":
        query = query.filter(Board.is_deleted == True)
    elif filter_type == "starred":
        query = query.filter(Board.is_deleted == False, Board.is_starred == True)
    elif filter_type == "processing":
        query = query.filter(Board.is_deleted == False, Board.status.in_(["PROCESSING", "PENDING"]))
    else:
        query = query.filter(Board.is_deleted == False)
        if folder_id:
            query = query.filter(Board.folder_id == folder_id)

    if search:
        search_kw = f"%{search.strip()}%"
        # 제목 검색뿐만 아니라 자막 본문 내용도 검색
        matched_segment_boards = select(TranscriptSegment.board_id).where(TranscriptSegment.content.ilike(search_kw))
        query = query.filter((Board.title.ilike(search_kw)) | (Board.id.in_(matched_segment_boards)))

    boards = query.order_by(Board.created_at.desc()).all()
    results = []
    for b in boards:
        try:
            keywords = json.loads(b.keywords_json) if b.keywords_json else []
        except Exception:
            keywords = []
        results.append({
            "id": b.id,
            "folder_id": b.folder_id,
            "folder_name": b.folder.name if b.folder else "기본 폴더",
            "title": b.title,
            "duration_seconds": b.duration_seconds,
            "duration_str": format_seconds(b.duration_seconds),
            "status": b.status,
            "progress_percent": b.progress_percent,
            "error_message": b.error_message,
            "is_starred": b.is_starred,
            "is_deleted": b.is_deleted,
            "keywords": keywords,
            "has_audio": bool(b.audio_path and os.path.exists(b.audio_path)),
            "created_at": b.created_at.strftime("%Y. %m. %d. %H:%M") if b.created_at else "",
            "recorded_at": b.recorded_at.strftime("%Y. %m. %d. %H:%M") if b.recorded_at else ""
        })
    return results

@app.get("/api/boards/{board_id}")
def get_board_detail(board_id: int, db: Session = Depends(get_db)):
    b = db.query(Board).filter_by(id=board_id).first()
    if not b:
        raise HTTPException(status_code=404, detail="보드를 찾을 수 없습니다.")

    segments = []
    for s in b.segments:
        segments.append({
            "id": s.id,
            "start_time_ms": s.start_time_ms,
            "end_time_ms": s.end_time_ms,
            "timestamp_str": s.timestamp_str,
            "speaker": s.speaker or "화자 1",
            "content": s.content,
            "sequence": s.sequence
        })

    summaries = []
    for sum_item in b.summaries:
        summaries.append({
            "id": sum_item.id,
            "summary_type": sum_item.summary_type,
            "title": sum_item.title,
            "content": sum_item.content,
            "created_at": sum_item.created_at.isoformat()
        })

    bookmarks = []
    for bm in b.bookmarks:
        bookmarks.append({
            "id": bm.id,
            "timestamp_ms": bm.timestamp_ms,
            "timestamp_str": bm.timestamp_str,
            "note": bm.note
        })

    try:
        keywords = json.loads(b.keywords_json) if b.keywords_json else []
    except Exception:
        keywords = []

    return {
        "id": b.id,
        "folder_id": b.folder_id,
        "folder_name": b.folder.name if b.folder else "기본 폴더",
        "title": b.title,
        "duration_seconds": b.duration_seconds,
        "duration_str": format_seconds(b.duration_seconds),
        "status": b.status,
        "progress_percent": b.progress_percent,
        "error_message": b.error_message,
        "is_starred": b.is_starred,
        "is_deleted": b.is_deleted,
        "keywords": keywords,
        "has_audio": bool(b.audio_path and os.path.exists(b.audio_path)),
        "audio_url": f"/api/audio/{b.id}" if b.audio_path and os.path.exists(b.audio_path) else None,
        "segments": segments,
        "summaries": summaries,
        "bookmarks": bookmarks,
        "created_at": b.created_at.strftime("%Y. %m. %d. %H:%M") if b.created_at else ""
    }

@app.patch("/api/boards/{board_id}")
def update_board(board_id: int, req: BoardUpdate, db: Session = Depends(get_db)):
    b = db.query(Board).filter_by(id=board_id).first()
    if not b:
        raise HTTPException(status_code=404, detail="보드를 찾을 수 없습니다.")
    if req.title is not None:
        b.title = req.title.strip()
    if req.folder_id is not None:
        b.folder_id = req.folder_id
    if req.is_starred is not None:
        b.is_starred = req.is_starred
    db.commit()
    return {"ok": True}

@app.post("/api/boards/{board_id}/star")
def toggle_star_board(board_id: int, db: Session = Depends(get_db)):
    b = db.query(Board).filter_by(id=board_id).first()
    if not b:
        raise HTTPException(status_code=404, detail="보드를 찾을 수 없습니다.")
    b.is_starred = not b.is_starred
    db.commit()
    return {"is_starred": b.is_starred}

@app.delete("/api/boards/{board_id}")
def delete_board(board_id: int, permanent: bool = False, db: Session = Depends(get_db)):
    b = db.query(Board).filter_by(id=board_id).first()
    if not b:
        raise HTTPException(status_code=404, detail="보드를 찾을 수 없습니다.")
    if permanent or b.is_deleted:
        # 완전 삭제: 원본 오디오와 변환 텍스트도 디스크에서 지운다
        purge_board_files(b)
        db.delete(b)
    else:
        # 휴지통으로 이동: 파일을 `휴지통` 폴더로 옮겨 탐색기에서도 사라지게 한다
        move_board_files_to_trash(b)
        b.is_deleted = True
    db.commit()
    return {"ok": True}

@app.post("/api/boards/{board_id}/restore")
def restore_board(board_id: int, db: Session = Depends(get_db)):
    b = db.query(Board).filter_by(id=board_id).first()
    if not b:
        raise HTTPException(status_code=404, detail="보드를 찾을 수 없습니다.")
    # 휴지통으로 옮겨 뒀던 파일을 원래 폴더로 되돌린다
    restore_board_files_from_trash(b)
    b.is_deleted = False
    db.commit()
    return {"ok": True}

@app.post("/api/boards/{board_id}/reprocess")
def reprocess_board(board_id: int, db: Session = Depends(get_db)):
    """보드의 STT 변환을 다시 큐에 넣는다 (실패 재시도 / 완료본 다시 받아쓰기 공통).

    이미 완료된 보드도 원본 오디오만 남아 있으면 처음부터 다시 받아쓸 수 있다.
    기존 스크립트는 새 변환이 끝나 새 세그먼트를 저장하는 시점에 교체되므로,
    변환이 도는 동안에도(그리고 변환이 실패해도) 예전 스크립트는 그대로 남는다.
    """
    b = db.query(Board).filter_by(id=board_id).first()
    if not b:
        raise HTTPException(status_code=404, detail="보드를 찾을 수 없습니다.")

    # 이미 큐/워커가 잡고 있는 보드를 또 넣으면 같은 파일을 두 번 변환하게 된다.
    # (큐에서 유실돼 상태만 남은 보드는 여기서 걸리지 않으므로 수동 재투입이 가능하다)
    if b.status in ("PENDING", "PROCESSING"):
        with _queue_lock:
            active = b.id in _queued_board_ids or b.id in _inflight
        if active:
            raise HTTPException(status_code=409, detail="이미 변환 중이거나 대기 중인 보드입니다.")

    # 휴지통에 있던 보드를 다시 변환하려는 경우이니 파일부터 제자리로 돌려놓는다
    if b.is_deleted:
        restore_board_files_from_trash(b)
    if not b.audio_path or not os.path.exists(b.audio_path):
        raise HTTPException(status_code=400, detail="원본 오디오 파일이 없어 다시 변환할 수 없습니다.")

    had_transcript = db.query(TranscriptSegment).filter_by(board_id=b.id).count() > 0

    b.status = "PENDING"
    b.progress_percent = 0
    b.error_message = None
    b.is_deleted = False
    db.commit()

    # 워커가 죽어 있으면 큐에 넣어도 아무도 꺼내지 않는다. 넣기 전에 확인한다.
    ensure_workers_alive()
    enqueue_board(b.id)
    return {
        "ok": True,
        "board_id": b.id,
        "status": "PENDING",
        "retranscribe": had_transcript,
        "queue_depth": stt_queue.qsize(),
    }


# -----------------
# 2-1. 용어집(단어장) — 받아쓰기 프롬프트에 실어 보내는 고유명사/전문용어
# -----------------
@app.get("/api/glossary")
def get_glossary(folder_id: Optional[int] = Query(None), db: Session = Depends(get_db)):
    """폴더 전용 용어와 공통 용어를 나눠서 돌려준다."""
    folder = db.query(Folder).filter_by(id=folder_id).first() if folder_id else None

    def dump(rows):
        return [{"id": r.id, "term": r.term, "note": r.note or ""} for r in rows]

    common = (
        db.query(GlossaryTerm)
        .filter(GlossaryTerm.folder_id.is_(None))
        .order_by(GlossaryTerm.id)
        .all()
    )
    folder_rows = []
    if folder:
        folder_rows = (
            db.query(GlossaryTerm)
            .filter(GlossaryTerm.folder_id == folder.id)
            .order_by(GlossaryTerm.id)
            .all()
        )
    return {
        "folder_id": folder.id if folder else None,
        "folder_name": folder.name if folder else None,
        "folder_terms": dump(folder_rows),
        "common_terms": dump(common),
        "max_terms": GLOSSARY_MAX_TERMS,
    }


@app.put("/api/glossary")
def replace_glossary(req: GlossaryReplaceRequest, db: Session = Depends(get_db)):
    """한 범위(특정 폴더 또는 공통)의 용어 목록을 통째로 교체한다.

    편집 화면이 여러 줄 텍스트를 통째로 보내는 구조라, 항목별 추가/삭제 대신 교체가 단순하다.
    """
    if req.folder_id is not None and not db.query(Folder).filter_by(id=req.folder_id).first():
        raise HTTPException(status_code=404, detail="폴더를 찾을 수 없습니다.")

    cleaned = []
    seen = set()
    for item in req.terms:
        term = (item.term or "").strip()
        if not term:
            continue
        key = term.lower()
        if key in seen:          # 같은 용어를 두 번 넣어도 프롬프트에는 한 번만 들어가게 한다
            continue
        seen.add(key)
        cleaned.append((term[:200], (item.note or "").strip()[:300]))
        if len(cleaned) >= GLOSSARY_MAX_TERMS:
            break

    scope = db.query(GlossaryTerm)
    if req.folder_id is None:
        scope = scope.filter(GlossaryTerm.folder_id.is_(None))
    else:
        scope = scope.filter(GlossaryTerm.folder_id == req.folder_id)
    scope.delete(synchronize_session=False)

    for term, note in cleaned:
        db.add(GlossaryTerm(folder_id=req.folder_id, term=term, note=note))
    db.commit()
    return {"ok": True, "count": len(cleaned), "dropped": max(0, len(req.terms) - len(cleaned))}


@app.get("/api/boards/{board_id}/glossary-preview")
def preview_board_glossary(board_id: int, db: Session = Depends(get_db)):
    """이 보드를 다시 받아쓸 때 실제로 적용될 용어 목록(폴더 + 공통)을 보여 준다."""
    b = db.query(Board).filter_by(id=board_id).first()
    if not b:
        raise HTTPException(status_code=404, detail="보드를 찾을 수 없습니다.")
    return {"folder_id": b.folder_id, "terms": load_glossary_terms(db, b.folder_id)}


@app.post("/api/boards/batch-delete")
def batch_delete_boards(req: BatchDeleteRequest, db: Session = Depends(get_db)):
    # 파일도 함께 정리해야 하므로 보드를 실제로 읽어서 하나씩 처리한다
    boards = db.query(Board).filter(Board.id.in_(req.board_ids)).all()
    for b in boards:
        if req.permanent or b.is_deleted:
            purge_board_files(b)
            db.delete(b)
        else:
            move_board_files_to_trash(b)
            b.is_deleted = True
    db.commit()
    return {"ok": True, "count": len(boards)}

@app.post("/api/boards/batch-move")
def batch_move_boards(req: BatchMoveRequest, db: Session = Depends(get_db)):
    db.query(Board).filter(Board.id.in_(req.board_ids)).update(
        {Board.folder_id: req.folder_id}, synchronize_session=False
    )
    db.commit()
    return {"ok": True, "count": len(req.board_ids)}

# -----------------
# 3. Transcript, Speaker & Bookmarks
# -----------------
@app.put("/api/boards/{board_id}/transcript")
def update_transcript(board_id: int, req: TranscriptUpdateRequest, db: Session = Depends(get_db)):
    b = db.query(Board).filter_by(id=board_id).first()
    if not b:
        raise HTTPException(status_code=404, detail="보드를 찾을 수 없습니다.")

    if req.segments is not None:
        new_segments = list(req.segments)
    elif req.full_text is not None:
        new_segments = parse_transcript_text(req.full_text)
    else:
        raise HTTPException(status_code=400, detail="segments 또는 full_text 중 하나는 필요합니다.")

    # 편집 화면의 일시적 오류로 기존 스크립트가 통째로 지워지는 사고를 막는다
    if not new_segments and db.query(TranscriptSegment).filter_by(board_id=board_id).count() > 0:
        raise HTTPException(status_code=400, detail="빈 스크립트로는 덮어쓸 수 없습니다.")

    db.query(TranscriptSegment).filter_by(board_id=board_id).delete()
    txt_lines = []
    # 종료 시각은 클라이언트가 보낸 값을 믿지 않고 이웃 문단으로 다시 계산한다.
    # (편집 화면은 '시작+10초'를 그대로 보내기 때문에, 그대로 저장하면 SRT 자막이 겹친다)
    starts = [int(s.get("start_time_ms", 0) or 0) for s in new_segments]
    ends = resolve_end_times(starts, int((b.duration_seconds or 0) * 1000))
    for idx, s in enumerate(new_segments):
        start_ms = starts[idx]
        seg = TranscriptSegment(
            board_id=board_id,
            start_time_ms=start_ms,
            end_time_ms=ends[idx],
            timestamp_str=s.get("timestamp_str") or f"[{ms_to_timestamp(start_ms)}]",
            speaker=s.get("speaker") or "화자 1",
            content=s.get("content", ""),
            sequence=idx
        )
        db.add(seg)
        txt_lines.append(f"{seg.timestamp_str} {seg.content}")
    b.updated_at = datetime.datetime.utcnow()
    db.commit()

    if b.txt_path and os.path.isdir(os.path.dirname(b.txt_path)):
        try:
            with open(b.txt_path, "w", encoding="utf-8") as f:
                f.write("\n\n".join(txt_lines))
        except OSError as e:
            print(f"[TRANSCRIPT-WARN] Board #{board_id} txt save failed: {e}")

    return {"ok": True, "segment_count": len(new_segments)}

@app.post("/api/boards/{board_id}/speakers/rename")
def rename_speaker_in_board(board_id: int, req: SpeakerRenameRequest, db: Session = Depends(get_db)):
    b = db.query(Board).filter_by(id=board_id).first()
    if not b:
        raise HTTPException(status_code=404, detail="보드를 찾을 수 없습니다.")

    db.query(TranscriptSegment).filter_by(board_id=board_id, speaker=req.old_name).update(
        {TranscriptSegment.speaker: req.new_name}
    )
    db.commit()
    return {"ok": True}

@app.post("/api/boards/{board_id}/bookmarks")
def add_bookmark(board_id: int, req: BookmarkCreateRequest, db: Session = Depends(get_db)):
    b = db.query(Board).filter_by(id=board_id).first()
    if not b:
        raise HTTPException(status_code=404, detail="보드를 찾을 수 없습니다.")

    bm = Bookmark(
        board_id=board_id,
        timestamp_ms=req.timestamp_ms,
        timestamp_str=req.timestamp_str,
        note=req.note
    )
    db.add(bm)
    db.commit()
    db.refresh(bm)
    return {"id": bm.id, "timestamp_ms": bm.timestamp_ms, "timestamp_str": bm.timestamp_str, "note": bm.note}

@app.delete("/api/bookmarks/{bookmark_id}")
def delete_bookmark(bookmark_id: int, db: Session = Depends(get_db)):
    bm = db.query(Bookmark).filter_by(id=bookmark_id).first()
    if not bm:
        raise HTTPException(status_code=404, detail="북마크를 찾을 수 없습니다.")
    db.delete(bm)
    db.commit()
    return {"ok": True}

# -----------------
# 4. AI Summary, Keywords & Board Chat
# -----------------
@app.post("/api/boards/{board_id}/keywords/generate")
def generate_keywords(board_id: int, db: Session = Depends(get_db)):
    b = db.query(Board).filter_by(id=board_id).first()
    if not b:
        raise HTTPException(status_code=404, detail="보드를 찾을 수 없습니다.")

    transcript = "\n".join([f"{s.timestamp_str} {s.content}" for s in b.segments])
    ai_keywords = extract_keywords_ai(transcript)
    if not ai_keywords:
        ai_keywords = extract_simple_keywords(transcript, limit=8)

    b.keywords_json = json.dumps(ai_keywords, ensure_ascii=False)
    db.commit()
    return {"keywords": ai_keywords}

@app.post("/api/boards/{board_id}/summary")
def generate_summary(board_id: int, req: SummaryRequest, db: Session = Depends(get_db)):
    b = db.query(Board).filter_by(id=board_id).first()
    if not b:
        raise HTTPException(status_code=404, detail="보드를 찾을 수 없습니다.")

    transcript = "\n".join([f"{s.timestamp_str} {s.content}" for s in b.segments])
    summary_content = generate_summary_ai(transcript, summary_type=req.summary_type)

    title_map = {
        "BASIC": "기본 요약",
        "MEETING": "회의록 정리",
        "ACTION_ITEM": "액션 아이템",
        "QUIZ": "강의 복습 퀴즈",
        "SLIDE": "슬라이드 발표 개요"
    }
    title = title_map.get(req.summary_type, "AI 요약")

    existing_sum = db.query(BoardSummary).filter_by(board_id=board_id, summary_type=req.summary_type).first()
    if existing_sum:
        existing_sum.content = summary_content
        existing_sum.created_at = datetime.datetime.utcnow()
    else:
        new_sum = BoardSummary(
            board_id=board_id,
            summary_type=req.summary_type,
            title=title,
            content=summary_content
        )
        db.add(new_sum)
    db.commit()

    return {"summary_type": req.summary_type, "title": title, "content": summary_content}

@app.get("/api/boards/{board_id}/chats")
def get_board_chats(board_id: int, db: Session = Depends(get_db)):
    chats = db.query(BoardChat).filter_by(board_id=board_id).order_by(BoardChat.created_at).all()
    return [{"id": c.id, "role": c.role, "message": c.message, "created_at": c.created_at.isoformat()} for c in chats]

@app.post("/api/boards/{board_id}/chat")
async def chat_with_board(board_id: int, req: ChatMessageRequest, db: Session = Depends(get_db)):
    b = db.query(Board).filter_by(id=board_id).first()
    if not b:
        raise HTTPException(status_code=404, detail="보드를 찾을 수 없습니다.")

    user_chat = BoardChat(board_id=board_id, role="user", message=req.message)
    db.add(user_chat)
    db.commit()

    transcript = "\n".join([f"{s.timestamp_str} {s.content}" for s in b.segments])
    past_chats = db.query(BoardChat).filter_by(board_id=board_id).order_by(BoardChat.created_at).all()
    history = [{"role": c.role, "message": c.message} for c in past_chats]

    def event_stream():
        full_assistant_reply = []
        for chunk_event in stream_board_chat(transcript, history, req.message):
            yield chunk_event
            if chunk_event.startswith("data: "):
                try:
                    payload = json.loads(chunk_event[6:].strip())
                    if "text" in payload:
                        full_assistant_reply.append(payload["text"])
                except Exception:
                    pass

        reply_text = "".join(full_assistant_reply)
        if reply_text:
            save_db = SessionLocal()
            try:
                ai_chat = BoardChat(board_id=board_id, role="assistant", message=reply_text)
                save_db.add(ai_chat)
                save_db.commit()
            finally:
                save_db.close()

    return StreamingResponse(event_stream(), media_type="text/event-stream")

# -----------------
# 5. Audio Streaming
# -----------------
@app.get("/api/audio/{board_id}")
def stream_audio(board_id: int, request: Request, db: Session = Depends(get_db)):
    b = db.query(Board).filter_by(id=board_id).first()
    if not b or not b.audio_path or not os.path.exists(b.audio_path):
        raise HTTPException(status_code=404, detail="오디오 파일을 찾을 수 없습니다.")

    file_path = b.audio_path
    file_size = os.path.getsize(file_path)
    content_type = mimetypes.guess_type(file_path)[0] or "audio/mpeg"

    range_header = request.headers.get("Range")
    if range_header:
        byte1, byte2 = 0, None
        match = re.search(r"bytes=(\d+)-(\d*)", range_header)
        if match:
            g1, g2 = match.groups()
            byte1 = int(g1)
            if g2:
                byte2 = int(g2)

        length = file_size - byte1 if byte2 is None else byte2 - byte1 + 1
        
        def iterfile():
            with open(file_path, "rb") as f:
                f.seek(byte1)
                remaining = length
                chunk_size = 1024 * 64
                while remaining > 0:
                    read_bytes = min(chunk_size, remaining)
                    data = f.read(read_bytes)
                    if not data:
                        break
                    remaining -= len(data)
                    yield data

        headers = {
            "Content-Range": f"bytes {byte1}-{byte1 + length - 1}/{file_size}",
            "Accept-Ranges": "bytes",
            "Content-Length": str(length),
            "Content-Type": content_type,
        }
        return StreamingResponse(iterfile(), status_code=206, headers=headers)
    else:
        return FileResponse(file_path, media_type=content_type, headers={"Accept-Ranges": "bytes"})

# -----------------
# 6. Export Endpoint (TXT, SRT, VTT, Markdown)
# -----------------
@app.get("/api/boards/{board_id}/export")
def export_board(
    board_id: int,
    format: str = Query("txt"),
    include_timestamps: bool = Query(True),
    include_speakers: bool = Query(True),
    db: Session = Depends(get_db)
):
    b = db.query(Board).filter_by(id=board_id).first()
    if not b:
        raise HTTPException(status_code=404, detail="보드를 찾을 수 없습니다.")

    if format == "srt":
        srt_lines = []
        # 이전 버전이 '시작+10초'로 저장해 둔 보드도 겹치지 않게, 내보낼 때 이웃 기준으로 다시 잡는다
        segs = list(b.segments)
        recalculated = resolve_end_times(
            [s.start_time_ms or 0 for s in segs],
            int((b.duration_seconds or 0) * 1000),
        )
        for i, s in enumerate(segs, start=1):
            start_ms = s.start_time_ms or 0
            end_ms = recalculated[i - 1]
            if end_ms <= start_ms:
                end_ms = start_ms + 1000
            speaker_prefix = f"[{s.speaker}] " if (include_speakers and s.speaker) else ""
            srt_lines.append(
                f"{i}\n{srt_timestamp(start_ms)} --> {srt_timestamp(end_ms)}\n{speaker_prefix}{s.content}\n"
            )
        content = "\n".join(srt_lines)
        media_type = "text/plain; charset=utf-8"
        filename = f"{sanitize_filename(b.title)}.srt"
    elif format == "md":
        lines = [f"# {b.title}\n\n**녹음 일시**: {b.created_at}\n\n---\n"]
        for s in b.segments:
            prefix = ""
            if include_timestamps: prefix += f"`{s.timestamp_str}` "
            if include_speakers and s.speaker: prefix += f"**{s.speaker}**: "
            lines.append(f"{prefix}{s.content}\n")
        content = "\n".join(lines)
        media_type = "text/markdown; charset=utf-8"
        filename = f"{sanitize_filename(b.title)}.md"
    else:
        lines = []
        for s in b.segments:
            prefix = ""
            if include_timestamps: prefix += f"{s.timestamp_str} "
            if include_speakers and s.speaker: prefix += f"[{s.speaker}] "
            lines.append(f"{prefix}{s.content}")
        content = "\n\n".join(lines)
        media_type = "text/plain; charset=utf-8"
        filename = f"{sanitize_filename(b.title)}.txt"

    import urllib.parse
    encoded_filename = urllib.parse.quote(filename)
    return Response(
        content=content.encode("utf-8"),
        media_type=media_type,
        headers={"Content-Disposition": f"attachment; filename*=UTF-8''{encoded_filename}"}
    )

# -----------------
# 7. Upload & Mobile Endpoint
# -----------------
MAX_UPLOAD_BYTES = int(os.getenv("MAX_UPLOAD_MB", "500")) * 1024 * 1024


@app.post("/api/boards/upload")
async def upload_audio_file(
    file: UploadFile = File(...),
    folder_id: Optional[int] = Form(None),
    folder_name: Optional[str] = Form(None),
    db: Session = Depends(get_db)
):
    """녹음 파일을 올려 바로 변환 큐에 넣는다.

    웹은 폴더를 골라 업로드하므로 folder_id 를 보내지만, 안드로이드 앱은 폰에 있는
    폴더 '이름'만 알고 서버의 id 는 모른다. 그래서 folder_name 으로도 받을 수 있게 하고,
    같은 이름의 폴더가 없으면 그 자리에서 만든다 (앱에서 만든 폴더가 서버에도 생긴다).
    """
    # 업로드 파일명은 신뢰할 수 없다: 경로 구분자·상위 경로 참조를 제거한다
    raw_name = unicodedata.normalize("NFC", os.path.basename(file.filename or ""))
    stem, ext = os.path.splitext(raw_name)
    ext = ext.lower()
    if ext not in VALID_AUDIO_EXTS:
        raise HTTPException(
            status_code=400,
            detail=f"지원하지 않는 형식입니다. ({', '.join(VALID_AUDIO_EXTS)}만 업로드할 수 있습니다.)"
        )

    base_name = sanitize_filename(stem, fallback=f"recording_{int(time.time())}")
    filename = base_name + ext

    folder = db.query(Folder).filter_by(id=folder_id).first() if folder_id else None
    if not folder and folder_name and folder_name.strip():
        folder = get_or_create_folder(db, folder_name.strip())
    if not folder:
        folder = get_or_create_folder(db, "기본 폴더")

    target_dir = os.path.join(AUDIO_DIR, sanitize_filename(folder.name))
    os.makedirs(target_dir, exist_ok=True)
    dest_path = os.path.join(target_dir, filename)

    # 같은 이름이 이미 있으면 덮어쓰지 않고 새 이름을 부여한다
    if os.path.exists(dest_path):
        base_name = f"{base_name}_{datetime.datetime.now().strftime('%Y%m%d_%H%M%S')}"
        filename = base_name + ext
        dest_path = os.path.join(target_dir, filename)

    written = 0
    try:
        with open(dest_path, "wb") as f:
            while True:
                chunk = await file.read(1024 * 1024)
                if not chunk:
                    break
                written += len(chunk)
                if written > MAX_UPLOAD_BYTES:
                    raise HTTPException(
                        status_code=413,
                        detail=f"파일이 너무 큽니다. (최대 {MAX_UPLOAD_BYTES // (1024 * 1024)}MB)"
                    )
                f.write(chunk)
    except Exception:
        if os.path.exists(dest_path):
            os.remove(dest_path)
        raise

    board = Board(
        folder_id=folder.id,
        title=base_name,
        audio_path=dest_path,
        audio_filename=filename,
        duration_seconds=0.0,
        status="PENDING",
        progress_percent=0,
        keywords_json="[]",
        recorded_at=datetime.datetime.utcnow()
    )
    db.add(board)
    db.commit()
    db.refresh(board)

    # 워커가 죽어 있으면 큐에 넣어도 아무도 꺼내지 않는다 (앱에서 올린 파일이 대기만 하는 것을 막는다)
    ensure_workers_alive()
    enqueue_board(board.id)
    return {
        "ok": True,
        "board_id": board.id,
        "title": board.title,
        "folder": folder.name,
        "status": "PENDING",
        "queue_depth": stt_queue.qsize(),
    }

# -----------------
# 8. Static Files & Root
# -----------------
os.makedirs(STATIC_DIR, exist_ok=True)
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")

@app.get("/")
def serve_index():
    index_path = os.path.join(STATIC_DIR, "index.html")
    if os.path.exists(index_path):
        return FileResponse(index_path)
    return {"message": "다글로 서버가 준비 중입니다."}


@app.get(LOGIN_PATH)
def serve_login(request: Request, db: Session = Depends(get_db)):
    """이미 로그인한 상태면 곧바로 메인으로 보낸다."""
    if auth.resolve_session_user(db, request.cookies.get(auth.SESSION_COOKIE)):
        return RedirectResponse(url="/", status_code=302)
    login_path = os.path.join(STATIC_DIR, "login.html")
    if os.path.exists(login_path):
        return FileResponse(login_path)
    return {"message": "로그인 페이지를 찾을 수 없습니다."}
