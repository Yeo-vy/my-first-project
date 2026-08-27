import os
import io
import re
import json
import time
import mimetypes
import datetime
import threading
from typing import Optional, List
from fastapi import FastAPI, Depends, HTTPException, Query, Request, Response, UploadFile, File, Form, BackgroundTasks
from fastapi.responses import StreamingResponse, FileResponse
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy.orm import Session
from pydantic import BaseModel

from server.database import init_db, get_db, SessionLocal
from server.models import Folder, Board, TranscriptSegment, BoardSummary, BoardChat, Bookmark
from server.migrator import sync_filesystem_to_db, ms_to_timestamp, timestamp_to_ms, extract_simple_keywords
from server.ai_service import extract_keywords_ai, generate_summary_ai, stream_board_chat, process_audio_file_to_board

app = FastAPI(title="다글로 (daglo) AI 풀스택 서버", version="2.5.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STATIC_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "static")
AUDIO_DIR = os.path.join(BASE_DIR, "녹음파일원본")
RESULT_DIR = os.path.join(BASE_DIR, "강의 녹음 변환")

# -----------------
# Background Auto Folder Watcher (SFTP/RaiDrive 자동 감지 엔진)
# -----------------
def background_audio_watcher():
    """SFTP/RaiDrive 등으로 `녹음파일원본` 폴더에 복사된 새 오디오를 주기적으로 감지하여 자동 STT 실행"""
    time.sleep(3)
    valid_exts = ('.mp3', '.m4a', '.wav', '.mp4')
    processed_sizes = {}

    while True:
        try:
            db = SessionLocal()
            for root_dir, _, files in os.walk(AUDIO_DIR):
                rel_dir = os.path.relpath(root_dir, AUDIO_DIR)
                folder_name = "기본 폴더" if rel_dir == "." else rel_dir
                
                # 폴더 확인
                folder = db.query(Folder).filter_by(name=folder_name).first()
                if not folder:
                    folder = Folder(name=folder_name)
                    db.add(folder)
                    db.commit()
                    db.refresh(folder)

                for f in files:
                    if f.lower().endswith(valid_exts):
                        full_path = os.path.join(root_dir, f)
                        base_name = os.path.splitext(f)[0]
                        current_size = os.path.getsize(full_path)

                        # 파일 복사가 진행 중인지 확인 (5초간 크기 변동이 없는지)
                        last_size, last_time = processed_sizes.get(full_path, (None, None))
                        if last_size != current_size:
                            processed_sizes[full_path] = (current_size, time.time())
                            continue

                        # 크기 변동 없이 최소 4초 경과 = 복사 완료로 판정
                        if time.time() - last_time < 4:
                            continue

                        board = db.query(Board).filter_by(folder_id=folder.id, title=base_name, is_deleted=False).first()
                        if not board:
                            board = Board(
                                folder_id=folder.id,
                                title=base_name,
                                audio_path=full_path,
                                audio_filename=f,
                                status="PROCESSING",
                                progress_percent=0,
                                keywords_json="[]",
                                recorded_at=datetime.datetime.fromtimestamp(os.path.getmtime(full_path))
                            )
                            db.add(board)
                            db.commit()
                            db.refresh(board)
                            print(f"[AUTO-DETECT] New audio found: {f} (Board #{board.id})")
                            threading.Thread(target=process_audio_file_to_board, args=(board.id, full_path, SessionLocal), daemon=True).start()

            db.close()
        except Exception as e:
            pass
        time.sleep(5)

@app.on_event("startup")
def startup_event():
    init_db()
    db = SessionLocal()
    try:
        sync_filesystem_to_db(db)
        print("[INFO] DB initialized and filesystem synced.")
    finally:
        db.close()

    # 백그라운드 폴더 감시 스레드 가동
    watcher_thread = threading.Thread(target=background_audio_watcher, daemon=True)
    watcher_thread.start()

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

class SummaryRequest(BaseModel):
    summary_type: str = "BASIC"  # BASIC, MEETING, ACTION_ITEM, QUIZ, SLIDE

class ChatMessageRequest(BaseModel):
    message: str

def format_seconds(seconds: float) -> str:
    s = int(seconds)
    h = s // 3600
    m = (s % 3600) // 60
    sec = s % 60
    if h > 0:
        return f"{h}:{m:02d}:{sec:02d}"
    return f"{m:02d}:{sec:02d}"

# -----------------
# 1. Folder Endpoints
# -----------------
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

    db.delete(folder)
    db.commit()
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
        matched_segment_boards = db.query(TranscriptSegment.board_id).filter(TranscriptSegment.content.ilike(search_kw)).subquery()
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
        db.delete(b)
    else:
        b.is_deleted = True
    db.commit()
    return {"ok": True}

@app.post("/api/boards/{board_id}/restore")
def restore_board(board_id: int, db: Session = Depends(get_db)):
    b = db.query(Board).filter_by(id=board_id).first()
    if not b:
        raise HTTPException(status_code=404, detail="보드를 찾을 수 없습니다.")
    b.is_deleted = False
    db.commit()
    return {"ok": True}

@app.post("/api/boards/batch-delete")
def batch_delete_boards(req: BatchDeleteRequest, db: Session = Depends(get_db)):
    if req.permanent:
        db.query(Board).filter(Board.id.in_(req.board_ids)).delete(synchronize_session=False)
    else:
        db.query(Board).filter(Board.id.in_(req.board_ids)).update({Board.is_deleted: True}, synchronize_session=False)
    db.commit()
    return {"ok": True, "count": len(req.board_ids)}

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

    if req.segments:
        # 세그먼트 배열 직접 수정
        db.query(TranscriptSegment).filter_by(board_id=board_id).delete()
        txt_lines = []
        for idx, s in enumerate(req.segments):
            seg = TranscriptSegment(
                board_id=board_id,
                start_time_ms=s.get("start_time_ms", 0),
                end_time_ms=s.get("end_time_ms", 0),
                timestamp_str=s.get("timestamp_str", "[00:00]"),
                speaker=s.get("speaker", "화자 1"),
                content=s.get("content", ""),
                sequence=idx
            )
            db.add(seg)
            txt_lines.append(f"{seg.timestamp_str} {seg.content}")
        b.updated_at = datetime.datetime.utcnow()
        db.commit()

        if b.txt_path and os.path.exists(os.path.dirname(b.txt_path)):
            try:
                with open(b.txt_path, "w", encoding="utf-8") as f:
                    f.write("\n\n".join(txt_lines))
            except Exception:
                pass

    return {"ok": True}

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
        for i, s in enumerate(b.segments, start=1):
            start_t = datetime.timedelta(milliseconds=s.start_time_ms)
            end_t = datetime.timedelta(milliseconds=s.end_time_ms)
            speaker_prefix = f"[{s.speaker}] " if (include_speakers and s.speaker) else ""
            srt_lines.append(f"{i}\n{str(start_t)[:11]},000 --> {str(end_t)[:11]},000\n{speaker_prefix}{s.content}\n")
        content = "\n".join(srt_lines)
        media_type = "text/plain; charset=utf-8"
        filename = f"{b.title}.srt"
    elif format == "md":
        lines = [f"# {b.title}\n\n**녹음 일시**: {b.created_at}\n\n---\n"]
        for s in b.segments:
            prefix = ""
            if include_timestamps: prefix += f"`{s.timestamp_str}` "
            if include_speakers and s.speaker: prefix += f"**{s.speaker}**: "
            lines.append(f"{prefix}{s.content}\n")
        content = "\n".join(lines)
        media_type = "text/markdown; charset=utf-8"
        filename = f"{b.title}.md"
    else:
        lines = []
        for s in b.segments:
            prefix = ""
            if include_timestamps: prefix += f"{s.timestamp_str} "
            if include_speakers and s.speaker: prefix += f"[{s.speaker}] "
            lines.append(f"{prefix}{s.content}")
        content = "\n\n".join(lines)
        media_type = "text/plain; charset=utf-8"
        filename = f"{b.title}.txt"

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
@app.post("/api/boards/upload")
async def upload_audio_file(
    file: UploadFile = File(...),
    folder_id: int = Form(...),
    background_tasks: BackgroundTasks = BackgroundTasks(),
    db: Session = Depends(get_db)
):
    folder = db.query(Folder).filter_by(id=folder_id).first()
    if not folder:
        folder = db.query(Folder).filter_by(name="기본 폴더").first()

    folder_name = folder.name if folder else "기본 폴더"
    target_dir = os.path.join(AUDIO_DIR, folder_name)
    os.makedirs(target_dir, exist_ok=True)

    filename = file.filename
    dest_path = os.path.join(target_dir, filename)

    with open(dest_path, "wb") as f:
        content = await file.read()
        f.write(content)

    base_name = os.path.splitext(filename)[0]
    board = db.query(Board).filter_by(folder_id=folder.id, title=base_name).first()
    if not board:
        board = Board(
            folder_id=folder.id,
            title=base_name,
            audio_path=dest_path,
            audio_filename=filename,
            duration_seconds=0.0,
            status="PROCESSING",
            progress_percent=5,
            keywords_json="[]",
            recorded_at=datetime.datetime.utcnow()
        )
        db.add(board)
        db.commit()
        db.refresh(board)

    # 백그라운드 STT 및 AI 요약 실행
    background_tasks.add_task(process_audio_file_to_board, board.id, dest_path, SessionLocal)

    return {"ok": True, "board_id": board.id, "title": board.title, "status": "PROCESSING"}

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
