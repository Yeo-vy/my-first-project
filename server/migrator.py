import os
import re
import json
import datetime
from sqlalchemy.orm import Session
from server.models import Folder, Board, TranscriptSegment, BoardSummary

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AUDIO_DIR = os.path.join(BASE_DIR, "녹음파일원본")
RESULT_DIR = os.path.join(BASE_DIR, "강의 녹음 변환")

TIMESTAMP_PATTERN = re.compile(r'\[(\d{1,2}:\d{2}(?::\d{2})?)\]')

# 받아쓰기는 사람이 허가해야 시작한다.
# 새로 들어온 녹음은 WAITING(허가 대기)으로만 쌓이고, 웹에서 `받아쓰기 시작`을 눌러야
# PENDING(큐 대기)으로 바뀌어 STT 워커가 집어간다. 예전처럼 발견 즉시 자동으로 돌리고
# 싶으면 .env 에 AUTO_TRANSCRIBE=on 을 둔다.
AUTO_TRANSCRIBE = os.getenv("AUTO_TRANSCRIBE", "off").strip().lower() in ("on", "1", "true", "yes")
# 새로 발견/업로드된 녹음이 처음 갖는 상태
NEW_BOARD_STATUS = "PENDING" if AUTO_TRANSCRIBE else "WAITING"

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

def split_timestamped_line(line: str, last_ms: int = 0) -> list:
    """`[MM:SS] 본문` 한 줄을 (시각ms, 본문) 조각들로 끊는다.

    AI 는 말이 없는 구간을 만나면 `[00:01] [00:02] [00:03] 본문` 처럼 한 줄에 타임스탬프를
    여러 개 붙여 준다. 첫 개만 떼어내면 나머지가 본문에 남아 스크립트와 자막에 [00:02] 가
    그대로 찍힌다. 그래서 줄 안의 타임스탬프를 전부 떼어내고 조각마다 시각을 붙인다.

    본문이 빈 조각(타임스탬프만 있는 구간)도 마지막에 그대로 돌려준다. 부르는 쪽에서
    시각만 이어받고 건너뛰면 된다.
    """
    pieces = []
    pos = 0
    current_ms = last_ms
    for m in TIMESTAMP_PATTERN.finditer(line):
        text = line[pos:m.start()].strip()
        if text:
            pieces.append((current_ms, text))
        current_ms = timestamp_to_ms(m.group(1))
        pos = m.end()
    pieces.append((current_ms, line[pos:].strip()))
    return pieces


def strip_timestamps(text: str) -> str:
    """본문에 섞여 들어간 타임스탬프를 걷어낸다 (이미 그렇게 저장된 예전 스크립트용)."""
    cleaned = TIMESTAMP_PATTERN.sub(" ", text or "")
    return re.sub(r"[ \t]{2,}", " ", cleaned).strip()


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

def get_folder_name(root_dir: str, base_dir: str) -> str:
    rel_dir = os.path.relpath(root_dir, base_dir)
    if rel_dir == "." or rel_dir == "":
        return "기본 폴더"
    # 중첩된 폴더일 경우 가장 하위 폴더명만 사용 (예: Default/Dreampath -> Dreampath)
    return os.path.basename(os.path.normpath(root_dir))

def get_or_create_folder(db: Session, name: str) -> Folder:
    folder = db.query(Folder).filter_by(name=name).first()
    if not folder:
        folder = Folder(name=name)
        db.add(folder)
        db.commit()
        db.refresh(folder)
    return folder

def sync_filesystem_to_db(db: Session):
    """로컬 폴더와 기존 txt/audio 파일들을 SQLite DB로 동기화한다."""
    os.makedirs(AUDIO_DIR, exist_ok=True)
    os.makedirs(RESULT_DIR, exist_ok=True)

    # 0. 기존 DB에 잘못 생성된 중첩 폴더명(Default\Dreampath 등) 정리 및 보드 병합
    all_folders = db.query(Folder).all()
    for f in all_folders:
        if '\\' in f.name or '/' in f.name:
            leaf_name = os.path.basename(f.name.replace('\\', '/'))
            
            leaf_folder = get_or_create_folder(db, leaf_name)
            
            boards = db.query(Board).filter_by(folder_id=f.id).all()
            for b in boards:
                existing = db.query(Board).filter_by(folder_id=leaf_folder.id, title=b.title).first()
                if existing and existing.id != b.id:
                    if existing.status == "COMPLETED" and b.status != "COMPLETED":
                        db.delete(b)
                    elif b.status == "COMPLETED" and existing.status != "COMPLETED":
                        db.delete(existing)
                        b.folder_id = leaf_folder.id
                    else:
                        db.delete(b)
                else:
                    b.folder_id = leaf_folder.id
            db.commit()
            try:
                db.delete(f)
                db.commit()
            except Exception:
                pass

    # 1. '기본 폴더' 보장
    default_folder = get_or_create_folder(db, "기본 폴더")

    # 2. 오디오 파일 전체 스캔하여 title -> full_path 매핑 구축 (폴더 구조가 달라도 매칭되도록)
    audio_map = {}
    for root_dir, _, files in os.walk(AUDIO_DIR):
        for f in files:
            ext = os.path.splitext(f)[1].lower()
            if ext in ['.m4a', '.mp3', '.wav', '.mp4']:
                base = os.path.splitext(f)[0]
                audio_map[base] = os.path.join(root_dir, f)

    # 3. '강의 녹음 변환' 폴더의 txt 파일들을 Board로 등록
    for root_dir, _, files in os.walk(RESULT_DIR):
        txt_files = [
            f for f in files
            if f.endswith('.txt') and not f.endswith('_수정본.txt') and '_chunk_' not in f
        ]
        if not txt_files:
            continue

        folder_name = get_folder_name(root_dir, RESULT_DIR)
        current_folder = get_or_create_folder(db, folder_name)

        for f in txt_files:
            base_name = os.path.splitext(f)[0]
            txt_full_path = os.path.join(root_dir, f)

            # DB에 이미 있는지 확인 (title과 folder_id로 확인)
            existing_board = db.query(Board).filter_by(folder_id=current_folder.id, title=base_name).first()
            if existing_board and len(existing_board.segments) > 0:
                continue

            # 오디오 맵에서 오디오 파일 찾기
            audio_file_path = audio_map.get(base_name)
            audio_file_name = os.path.basename(audio_file_path) if audio_file_path else None

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
                # 한 줄에 타임스탬프가 여러 개 붙어 있어도 조각마다 끊어 본문에 남기지 않는다
                for t_ms, clean_text in split_timestamped_line(line, max_time_ms):
                    if t_ms > max_time_ms:
                        max_time_ms = t_ms
                    if not clean_text:
                        continue
                    segments.append({
                        "start_time_ms": t_ms,
                        "end_time_ms": t_ms + 10000,
                        "timestamp_str": f"[{ms_to_timestamp(t_ms)}]",
                        "speaker": "화자 1",
                        "content": clean_text,
                        "sequence": seq
                    })
                    seq += 1

            # 오디오 길이가 없으면 마지막 세그먼트 시간 + 15초로 추정
            duration_sec = (max_time_ms // 1000) + 15.0
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
        audio_files = [f for f in files if os.path.splitext(f)[1].lower() in ['.m4a', '.mp3', '.wav', '.mp4']]
        if not audio_files:
            continue

        folder_name = get_folder_name(root_dir, AUDIO_DIR)
        current_folder = get_or_create_folder(db, folder_name)

        for f in audio_files:
            base_name = os.path.splitext(f)[0]
            full_audio_path = os.path.join(root_dir, f)
            existing = db.query(Board).filter_by(folder_id=current_folder.id, title=base_name).first()
            if not existing:
                # 허가 대기(WAITING)로 등록한다. 사람이 `받아쓰기 시작`을 누르면 PENDING 이 되고
                # 그때 워커가 집어간다 (PROCESSING 으로 두면 영구 정체된다)
                board = Board(
                    folder_id=current_folder.id,
                    title=base_name,
                    audio_path=full_audio_path,
                    audio_filename=f,
                    duration_seconds=0.0,
                    status=NEW_BOARD_STATUS,
                    progress_percent=0,
                    keywords_json="[]",
                    recorded_at=datetime.datetime.fromtimestamp(os.path.getmtime(full_audio_path))
                )
                db.add(board)
                db.commit()
            elif not existing.audio_path:
                existing.audio_path = full_audio_path
                existing.audio_filename = f
                db.commit()
