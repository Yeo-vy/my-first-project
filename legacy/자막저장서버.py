"""HTML 자막 편집 저장 서버.

강의 스크립트 HTML에서 편집한 내용이 서버의 txt에도 저장되도록 돕는 작은 FastAPI 앱.
- GET /view/{경로}  : "강의 녹음 변환/" 아래 파일(주로 _강의스크립트.html)을 브라우저로 열어준다.
                       같은 오리진이라 HTML 안의 fetch(PUT /save/...)가 CORS 문제 없이 동작한다.
- PUT /save/{경로}   : 편집된 받아쓰기 텍스트를 대응 .txt에 원자적으로 저장하고,
                       같은 이름의 _강의스크립트.html이 있으면 transcript-box 내용을 재조립해 갱신한다.

인증: .env의 SAVE_TOKEN 이 설정되어 있으면
      - /save 는 X-Save-Token 헤더로, /view 는 ?token= 쿼리 파라미터로 일치해야 한다.
      - 비워두면 인증 없이 동작하니 공개 서버에서는 반드시 설정할 것.

실행: uvicorn 자막저장서버:app --host 0.0.0.0 --port 8900
"""

import html as html_lib
import mimetypes
import os
import re
import tempfile
from typing import Optional

from dotenv import load_dotenv
from fastapi import FastAPI, Header, HTTPException, Query, Request, Response
from fastapi.middleware.cors import CORSMiddleware

load_dotenv()

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
RESULT_FOLDER = os.path.join(BASE_DIR, "강의 녹음 변환")
SAVE_TOKEN = os.getenv("SAVE_TOKEN", "")

TIMESTAMP_PATTERN = re.compile(r'\[(\d{2}:\d{2}(?::\d{2})?)\]')

app = FastAPI(title="자막 저장 서버", docs_url=None, redoc_url=None)

# Filebrowser(8080 포트) 등 다른 오리진에서 열린 HTML에서도 저장할 수 있도록 CORS를 연다.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["GET", "PUT"],
    allow_headers=["X-Save-Token", "Content-Type"],
)


def timestamp_to_seconds(ts_str):
    parts = list(map(int, ts_str.split(':')))
    if len(parts) == 2:
        return parts[0] * 60 + parts[1]
    elif len(parts) == 3:
        return parts[0] * 3600 + parts[1] * 60 + parts[2]
    return 0


def resolve_result_path(rel_path: str, must_exist: bool = True) -> str:
    """결과 폴더 기준 상대 경로를 절대 경로로 바꾸고, 폴더 밖으로 나가는지 검사한다."""
    target = os.path.realpath(os.path.join(RESULT_FOLDER, rel_path))
    base = os.path.realpath(RESULT_FOLDER)
    if target != base and not target.startswith(base + os.sep):
        raise HTTPException(status_code=400, detail="잘못된 경로입니다")
    if must_exist and not os.path.isfile(target):
        raise HTTPException(status_code=404, detail="파일을 찾을 수 없습니다")
    return target


def check_view_token(token: str) -> None:
    if SAVE_TOKEN and token != SAVE_TOKEN:
        raise HTTPException(status_code=403, detail="토큰이 올바르지 않습니다")


def check_save_token(x_save_token: Optional[str]) -> None:
    if SAVE_TOKEN and x_save_token != SAVE_TOKEN:
        raise HTTPException(status_code=403, detail="토큰이 올바르지 않습니다")


def build_script_blocks(transcript_text: str) -> str:
    """받아쓰기 텍스트를 받아쓰기py.py와 동일한 script-block HTML로 조립한다."""
    html_lines = []
    last_seconds = 0
    for line in transcript_text.strip().split('\n'):
        line = line.strip()
        if not line:
            continue

        match = TIMESTAMP_PATTERN.search(line)
        if match:
            ts = match.group(1)
            last_seconds = timestamp_to_seconds(ts)
            text_only = html_lib.escape(line.replace(f"[{ts}]", "").strip())
            html_lines.append(
                f'<div class="script-block" data-ts="[{ts}]" data-seconds="{last_seconds}">'
                f'<span class="timestamp" onclick="playAt({last_seconds})" title="오디오 재생">[{ts}] 🔊</span> '
                f'<p class="text-content" contenteditable="true">{text_only}</p></div>'
            )
        else:
            html_lines.append(
                f'<div class="script-block" data-seconds="{last_seconds}">'
                f'<p class="text-content" contenteditable="true">{html_lib.escape(line)}</p></div>'
            )
    return "\n".join(html_lines)


def refresh_html_transcript(html_path: str, transcript_text: str) -> None:
    """기존 스크립트 HTML의 transcript-box 내용만 새 텍스트로 바꾼다.

    템플릿 구조(받아쓰기py.py가 생성)가 아니면 구조 손상을 피하기 위해 txt만 저장되고 건너뛴다.
    """
    try:
        with open(html_path, "r", encoding="utf-8") as f:
            page = f.read()

        start_marker = '<div class="transcript-box">'
        end_marker = '<button class="save-btn"'
        start = page.index(start_marker) + len(start_marker)
        end = page.index(end_marker)

        segment = page[start:end]
        close_idx = segment.rfind('</div>')  # main-container 닫기 태그 — 이 뒷부분은 그대로 보존
        new_segment = '\n        ' + build_script_blocks(transcript_text) + '\n    </div>\n' + segment[close_idx:]

        with open(html_path, "w", encoding="utf-8") as f:
            f.write(page[:start] + new_segment + page[end:])
    except (ValueError, OSError):
        pass


def atomic_write(path: str, content: str) -> None:
    """같은 폴더에 임시 파일을 만든 뒤 교체한다 — 저장 도중 죽어도 반쪽 파일이 남지 않게."""
    directory = os.path.dirname(path) or "."
    fd, tmp_path = tempfile.mkstemp(dir=directory, suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(content)
        os.replace(tmp_path, path)
    except Exception:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)
        raise


@app.get("/")
def root():
    return {"ok": True, "usage": "GET /view/{경로}, PUT /save/{txt경로}"}


@app.get("/view/{rel_path:path}")
def view_file(
    rel_path: str,
    token: Optional[str] = Query(default=None),
):
    check_view_token(token or "")
    target = resolve_result_path(rel_path)
    media_type = mimetypes.guess_type(target)[0] or "application/octet-stream"
    with open(target, "rb") as f:
        data = f.read()
    return Response(content=data, media_type=media_type)


@app.put("/save/{rel_path:path}")
async def save_transcript(
    rel_path: str,
    request: Request,
    x_save_token: Optional[str] = Header(default=None),
):
    check_save_token(x_save_token)

    # Starlette가 경로를 이미 퍼센트 디코딩하므로 여기서 또 unquote 하지 않는다.
    if not rel_path.endswith(".txt"):
        raise HTTPException(status_code=400, detail=".txt 파일 경로만 저장할 수 있습니다")

    body = (await request.body()).decode("utf-8")
    transcript_text = body.strip()

    txt_path = resolve_result_path(rel_path, must_exist=False)
    atomic_write(txt_path, transcript_text)

    # 대응하는 스크립트 HTML이 있으면 화면 내용도 함께 갱신한다.
    base_name = os.path.splitext(txt_path)[0]
    html_path = base_name + "_강의스크립트.html"
    if os.path.isfile(html_path):
        refresh_html_transcript(html_path, transcript_text)

    return {"ok": True}
