import os
import re
import json
import datetime
from sqlalchemy.orm import Session
from server.models import Folder, Board, TranscriptSegment, BoardSummary

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AUDIO_DIR = os.path.join(BASE_DIR, "녹음파일원본")
RESULT_DIR = os.path.join(BASE_DIR, "강의 녹음 변환")

TIMESTAMP_PATTERN = re.compile(r'\[(\d{2}:\d{2}(?::\d{2})?)\]')

def timestamp_to_ms(ts_str: str) -> int:
    parts = list(map(int, ts_str.split(':')))
    if len(parts) == 2:
        return (parts[0] * 60 + parts[1]) * 1000
    elif len(parts) == 3:
        return (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
    return 0

def ms_to_timestamp(ms: int) -> str:
    total_seconds = max(0, ms // 1000)
    hours = total_seconds // 3600
    minutes = (total_seconds % 3600) // 60
    seconds = total_seconds % 60
    if hours > 0:
        return f"{hours:02d}:{minutes:02d}:{seconds:02d}"
    return f"{minutes:02d}:{seconds:02d}"

def extract_simple_keywords(text: str, limit: int = 8) -> list:
    """간단한 명사/단어 빈도수 기반 기본 키워드 추출 (AI 호출 전 초기 표시용)"""
    # 2글자 이상 한글/영문 단어 추출
    words = re.findall(r'[가-힣a-zA-Z0-9_]{2,}', text)
    stopwords = {
        '그래서', '우리가', '여기서', '이런', '저런', '어떤', '때문에', '그리고', '하지만',
        '이렇게', '저렇게', '그냥', '이제', '있는', '없는', '같은', '통해서', '대해서',
        '겁니다', '합니다', '있습니다', '됩니다', '이라는', '이라고', '하는', '하다', '있다',
        'audio', 'mp3', 'm4a', 'wav'
    }
    freq = {}
    for w in words:
        w_lower = w.lower()
        if w_lower not in stopwords and len(w) >= 2:
            freq[w] = freq.get(w, 0) + 1
    
    sorted_words = sorted(freq.items(), key=lambda x: x[1], reverse=True)
    return [w for w, count in sorted_words[:limit]]

def sync_filesystem_to_db(db: Session):
    """로컬 폴더와 기존 txt/audio 파일들을 SQLite DB로 동기화한다."""
    os.makedirs(AUDIO_DIR, exist_ok=True)
    os.makedirs(RESULT_DIR, exist_ok=True)

    # 1. '기본 폴더' 보장
    default_folder = db.query(Folder).filter_by(name="기본 폴더").first()
    if not default_folder:
        default_folder = Folder(name="기본 폴더")
        db.add(default_folder)
        db.commit()
        db.refresh(default_folder)

    folder_map = {"": default_folder, "Default": default_folder}

    # 2. 결과 폴더 및 오디오 폴더 내의 서브 디렉터리들을 Folder로 생성
    all_subdirs = set()
    for root_dir in [RESULT_DIR, AUDIO_DIR]:
        if os.path.exists(root_dir):
            for entry in os.scandir(root_dir):
                if entry.is_dir() and not entry.name.startswith('.'):
                    all_subdirs.add(entry.name)

    for subdir_name in all_subdirs:
        folder = db.query(Folder).filter_by(name=subdir_name).first()
        if not folder:
            folder = Folder(name=subdir_name)
            db.add(folder)
            db.commit()
            db.refresh(folder)
        folder_map[subdir_name] = folder

    # 3. '강의 녹음 변환' 폴더의 txt 파일들을 Board로 등록
    for root_dir, _, files in os.walk(RESULT_DIR):
        rel_dir = os.path.relpath(root_dir, RESULT_DIR)
        current_folder = default_folder if rel_dir == "." else folder_map.get(rel_dir, default_folder)

        for f in files:
            if f.endswith('.txt') and not f.endswith('_수정본.txt') and not '_chunk_' in f:
                base_name = os.path.splitext(f)[0]
                txt_full_path = os.path.join(root_dir, f)

                # DB에 이미 있는지 확인 (title과 folder_id로 확인)
                existing_board = db.query(Board).filter_by(folder_id=current_folder.id, title=base_name).first()
                if existing_board and len(existing_board.segments) > 0:
                    continue

                # 오디오 파일 찾기
                audio_exts = ['.m4a', '.mp3', '.wav', '.mp4']
                audio_file_path = None
                audio_file_name = None
                target_audio_dir = os.path.join(AUDIO_DIR, rel_dir if rel_dir != "." else "")

                if os.path.exists(target_audio_dir):
                    for ext in audio_exts:
                        candidate = os.path.join(target_audio_dir, base_name + ext)
                        if os.path.exists(candidate):
                            audio_file_path = candidate
                            audio_file_name = base_name + ext
                            break

                # 텍스트 읽고 세그먼트 파싱
                try:
                    with open(txt_full_path, "r", encoding="utf-8") as tf:
                        full_content = tf.read().strip()
                except Exception:
                    continue

                if not full_content:
                    continue

                lines = full_content.split('\n')
                segments = []
                max_time_ms = 0
                seq = 0

                for line in lines:
                    line = line.strip()
                    if not line:
                        continue
                    match = TIMESTAMP_PATTERN.search(line)
                    if match:
                        ts_str = match.group(1)
                        t_ms = timestamp_to_ms(ts_str)
                        if t_ms > max_time_ms:
                            max_time_ms = t_ms
                        clean_text = line.replace(f"[{ts_str}]", "").strip()
                        segments.append({
                            "start_time_ms": t_ms,
                            "end_time_ms": t_ms + 10000,
                            "timestamp_str": f"[{ts_str}]",
                            "speaker": "화자 1",
                            "content": clean_text,
                            "sequence": seq
                        })
                    else:
                        segments.append({
                            "start_time_ms": max_time_ms,
                            "end_time_ms": max_time_ms + 5000,
                            "timestamp_str": f"[{ms_to_timestamp(max_time_ms)}]",
                            "speaker": "화자 1",
                            "content": line,
                            "sequence": seq
                        })
                    seq += 1

                # 오디오 길이가 없으면 마지막 세그먼트 시간 + 15초로 추정
                duration_sec = (max_time_ms // 1000) + 15.0

                # 키워드 추출
                keywords = extract_simple_keywords(full_content, limit=8)

                if existing_board:
                    board = existing_board
                    board.audio_path = audio_file_path
                    board.audio_filename = audio_file_name
                    board.txt_path = txt_full_path
                    board.duration_seconds = duration_sec
                    board.keywords_json = json.dumps(keywords, ensure_ascii=False)
                    board.status = "COMPLETED"
                else:
                    board = Board(
                        folder_id=current_folder.id,
                        title=base_name,
                        audio_path=audio_file_path,
                        audio_filename=audio_file_name,
                        txt_path=txt_full_path,
                        duration_seconds=duration_sec,
                        status="COMPLETED",
                        progress_percent=100,
                        keywords_json=json.dumps(keywords, ensure_ascii=False),
                        recorded_at=datetime.datetime.fromtimestamp(os.path.getmtime(txt_full_path))
                    )
                    db.add(board)
                    db.commit()
                    db.refresh(board)

                # 기존 세그먼트 삭제 후 재등록
                db.query(TranscriptSegment).filter_by(board_id=board.id).delete()
                for seg_data in segments:
                    seg = TranscriptSegment(
                        board_id=board.id,
                        start_time_ms=seg_data["start_time_ms"],
                        end_time_ms=seg_data["end_time_ms"],
                        timestamp_str=seg_data["timestamp_str"],
                        speaker=seg_data["speaker"],
                        content=seg_data["content"],
                        sequence=seg_data["sequence"]
                    )
                    db.add(seg)

                db.commit()

    # 4. 오디오 파일 중 아직 변환되지 않은 파일도 PROCESSING / PENDING 상태로 등록
    for root_dir, _, files in os.walk(AUDIO_DIR):
        rel_dir = os.path.relpath(root_dir, AUDIO_DIR)
        current_folder = default_folder if rel_dir == "." else folder_map.get(rel_dir, default_folder)
        for f in files:
            ext = os.path.splitext(f)[1].lower()
            if ext in ['.m4a', '.mp3', '.wav', '.mp4']:
                base_name = os.path.splitext(f)[0]
                full_audio_path = os.path.join(root_dir, f)
                existing = db.query(Board).filter_by(folder_id=current_folder.id, title=base_name).first()
                if not existing:
                    board = Board(
                        folder_id=current_folder.id,
                        title=base_name,
                        audio_path=full_audio_path,
                        audio_filename=f,
                        duration_seconds=0.0,
                        status="PROCESSING",
                        progress_percent=0,
                        keywords_json="[]",
                        recorded_at=datetime.datetime.fromtimestamp(os.path.getmtime(full_audio_path))
                    )
                    db.add(board)
                    db.commit()
