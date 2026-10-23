# -*- coding: utf-8 -*-
"""탐색기에서 이름을 바꿨을 때 웹 제목·오디오·txt 세 이름이 함께 따라가는지 검증.

예전에는 txt 이름만 바꾸면 보드의 txt 경로가 끊기고, 서버를 다시 켤 때 그 txt 가 오디오 없는
새 보드로 등록되어 `다시 받아쓰기` 버튼이 사라졌다. 여기서 못 박아 두는 것:

    - txt 이름을 바꾸면 원래 보드가 그 txt 를 다시 잡고, 제목과 오디오 이름도 바뀐다
    - 예전 서버가 만들어 둔 오디오 없는 중복 보드는 원래 보드로 합쳐지고 휴지통으로 간다
    - 본문이 다른 txt 끼리는 이름이 비슷해도 붙지 않는다
    - 오디오 이름을 바꾸면 txt 도 같은 이름이 된다
    - 웹에서 제목을 바꾸면 두 파일 이름도 바뀌고, 감시 스레드가 중복 보드를 만들지 않는다
    - 서버를 다시 켜도(sync_filesystem_to_db) 이미 주인이 있는 파일로 새 보드를 만들지 않는다

실행: `python server/tests/test_rename_sync.py`  (pytest 없이 그냥 돌아간다)
"""
import datetime
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

# 진짜 DB 와 녹음 폴더를 건드리지 않도록, server 를 불러오기 전에 임시 DB 로 돌려 둔다.
_tmp_dir = tempfile.mkdtemp(prefix="yeovyvm-rename-test-")
os.environ["YEOVYVM_DB_PATH"] = os.path.join(_tmp_dir, "test.db")

from server.database import SessionLocal, init_db  # noqa: E402
from server.models import Board, Folder, TranscriptSegment  # noqa: E402
from server import main, migrator  # noqa: E402

AUDIO_DIR = os.path.join(_tmp_dir, "녹음파일원본")
RESULT_DIR = os.path.join(_tmp_dir, "강의 녹음 변환")
for mod in (main, migrator):
    mod.AUDIO_DIR = AUDIO_DIR
    mod.RESULT_DIR = RESULT_DIR
main.TRASH_DIR = os.path.join(_tmp_dir, "휴지통")

passed = 0
failed = []


def check(name, condition, detail=""):
    global passed
    if condition:
        passed += 1
    else:
        failed.append(f"{name}{(': ' + detail) if detail else ''}")


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def make_board(db, folder, stem, lines):
    """오디오·txt·세그먼트를 갖춘 완료 보드를 만든다 (서버가 받아쓰기를 마친 모습)."""
    audio = os.path.join(AUDIO_DIR, folder.name, stem + ".m4a")
    txt = os.path.join(RESULT_DIR, folder.name, stem + ".txt")
    write(audio, "fake audio " + stem)
    write(txt, "\n\n".join(f"[0{i}:00] {line}" for i, line in enumerate(lines)))
    b = Board(
        folder_id=folder.id, title=stem, audio_path=audio, audio_filename=stem + ".m4a",
        txt_path=txt, status="COMPLETED",
        recorded_at=datetime.datetime.fromtimestamp(os.path.getmtime(audio)),
    )
    db.add(b)
    db.commit()
    for i, line in enumerate(lines):
        db.add(TranscriptSegment(board_id=b.id, start_time_ms=i * 60000, end_time_ms=i * 60000 + 5000,
                                 timestamp_str=f"[0{i}:00]", speaker="화자 1", content=line, sequence=i))
    db.commit()
    return b


init_db()
db = SessionLocal()
folder = Folder(name="알고리즘")
db.add(folder)
db.commit()

LECTURE_A = ["오늘은 동적 계획법의 기본 아이디어를 살펴보겠습니다.", "점화식을 세우는 것이 핵심입니다."]
LECTURE_B = ["오늘은 그래프 탐색 중에서 너비 우선 탐색을 봅니다.", "큐를 사용합니다."]
LECTURE_C = ["지난 시간에 이어서 분할 정복 알고리즘을 정리합니다.", "병합 정렬이 대표적입니다."]
LECTURE_D = ["이번 주는 최단 경로 문제와 다익스트라 알고리즘입니다.", "우선순위 큐가 필요합니다."]

a = make_board(db, folder, "음성 260909_132904", LECTURE_A)
b = make_board(db, folder, "음성 260910_100000", LECTURE_B)
c = make_board(db, folder, "알골 260923_133125", LECTURE_C)
d = make_board(db, folder, "알골 260930_151904", LECTURE_D)

# -----------------
# 1. 탐색기에서 txt 이름만 바꾼다
# -----------------
txt_dir = os.path.join(RESULT_DIR, "알고리즘")
os.rename(a.txt_path, os.path.join(txt_dir, "알고리즘_20260909_132904.txt"))
main.sync_renamed_transcripts(db)
db.refresh(a)
check("txt 변경: 제목 따라감", a.title == "알고리즘_20260909_132904", a.title)
check("txt 변경: txt 경로 다시 이어짐", a.txt_path and os.path.exists(a.txt_path), str(a.txt_path))
check("txt 변경: 오디오 이름도 바뀜",
      os.path.basename(a.audio_path) == "알고리즘_20260909_132904.m4a" and os.path.exists(a.audio_path),
      str(a.audio_path))
check("txt 변경: audio_filename 갱신", a.audio_filename == "알고리즘_20260909_132904.m4a", str(a.audio_filename))
check("txt 변경: 다른 보드는 그대로", db.get(Board, b.id).title == "음성 260910_100000")

# -----------------
# 2. 예전 서버가 이미 만들어 둔 오디오 없는 중복 보드가 있는 경우
# -----------------
new_txt = os.path.join(txt_dir, "알고리즘_20260923_133125.txt")
os.rename(c.txt_path, new_txt)
dup = Board(folder_id=folder.id, title="알고리즘_20260923_133125", txt_path=new_txt, status="COMPLETED")
db.add(dup)
db.commit()
main.sync_renamed_transcripts(db)
db.refresh(c)
db.refresh(dup)
check("중복 보드: 원래 보드가 txt 를 되찾음", c.txt_path == new_txt, str(c.txt_path))
check("중복 보드: 원래 보드 오디오 이름도 바뀜",
      os.path.basename(c.audio_path) == "알고리즘_20260923_133125.m4a" and os.path.exists(c.audio_path))
check("중복 보드: 휴지통으로", dup.is_deleted is True)
check("중복 보드: txt 를 놓아 줌 (완전 삭제해도 txt 가 안 지워진다)", dup.txt_path is None)

# 본문이 다른 txt 는 이름이 그럴듯해도 붙지 않는다
stray = os.path.join(txt_dir, "음성 260910_100000_메모.txt")
write(stray, "[00:00] 전혀 다른 내용의 메모 파일입니다. 강의와는 상관없어요.")
os.rename(b.txt_path, os.path.join(_tmp_dir, "b_moved_out.txt"))  # b 의 txt 를 감시 폴더 밖으로 치운다
main.sync_renamed_transcripts(db)
db.refresh(b)
check("본문 다른 txt: 붙지 않음", b.title == "음성 260910_100000" and not os.path.exists(b.txt_path))
os.remove(stray)

# -----------------
# 3. 탐색기에서 오디오 이름을 바꾼다 (감시 스레드 경로: find_moved_board → rename_board_files)
# -----------------
old_audio = d.audio_path
new_audio = os.path.join(os.path.dirname(old_audio), "알고리즘_20260930_151904.m4a")
os.rename(old_audio, new_audio)
mtime = datetime.datetime.fromtimestamp(os.path.getmtime(new_audio))
moved = main.find_moved_board(db, new_audio, mtime)
check("오디오 변경: 보드 찾음", moved is not None and moved.id == d.id)
moved.title = "알고리즘_20260930_151904"
moved.audio_path = new_audio
main.rename_board_files(moved, moved.title)
db.commit()
db.refresh(d)
check("오디오 변경: txt 도 같은 이름",
      os.path.basename(d.txt_path) == "알고리즘_20260930_151904.txt" and os.path.exists(d.txt_path),
      str(d.txt_path))

# -----------------
# 4. 웹에서 제목을 바꾼다
# -----------------
main.update_board(a.id, main.BoardUpdate(title="알고리즘 3주차"), db)
db.refresh(a)
check("웹 제목 변경: 오디오 이름", os.path.basename(a.audio_path) == "알고리즘 3주차.m4a" and os.path.exists(a.audio_path))
check("웹 제목 변경: txt 이름", os.path.basename(a.txt_path) == "알고리즘 3주차.txt" and os.path.exists(a.txt_path))

# 같은 이름 파일이 이미 있으면 덮어쓰지 않는다
write(os.path.join(AUDIO_DIR, "알고리즘", "겹치는 이름.m4a"), "someone else")
main.update_board(d.id, main.BoardUpdate(title="겹치는 이름"), db)
db.refresh(d)
check("이름 충돌: 남의 파일을 덮지 않음",
      open(os.path.join(AUDIO_DIR, "알고리즘", "겹치는 이름.m4a"), encoding="utf-8").read() == "someone else")
check("이름 충돌: 내 오디오는 그대로", os.path.exists(d.audio_path) and d.audio_path.endswith("알고리즘_20260930_151904.m4a"))
os.remove(os.path.join(AUDIO_DIR, "알고리즘", "겹치는 이름.m4a"))

# -----------------
# 5. 서버 재시작 동기화가 주인 있는 파일로 새 보드를 만들지 않는다
# -----------------
before = db.query(Board).count()
migrator.sync_filesystem_to_db(db)
after = db.query(Board).count()
check("재시작 동기화: 중복 보드 없음", before == after, f"{before} -> {after}")

db.close()

print(f"통과 {passed}개, 실패 {len(failed)}개")
for f in failed:
    print("  - " + f)
sys.exit(1 if failed else 0)
