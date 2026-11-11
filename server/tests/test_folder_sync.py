# -*- coding: utf-8 -*-
"""웹에서 보드를 옮기거나 폴더 이름을 바꿨을 때 탐색기의 파일·디렉터리가 함께 따라가는지,
그리고 받아쓰기 프롬프트에 폴더 이름이 주제로 실리는지 검증.

예전에는 보드를 다른 폴더로 옮겨도 DB 의 폴더 번호만 바뀌고 파일은 옛 디렉터리에 남았다.
폴더 이름 변경도 DB 의 이름만 바꿔서, 감시 스레드가 옛 디렉터리를 보고 옛 폴더를 다시 만들었다.
여기서 못 박아 두는 것:

    - 보드를 옮기면 오디오·txt·동반 파일이 새 폴더의 디렉터리로 가고 DB 경로도 바뀐다
    - 같은 이름의 파일이 이미 있으면 덮어쓰지 않는다
    - 변환 중인 보드는 폴더만 바꾸고, 변환이 끝난 뒤에 파일을 옮긴다
    - 휴지통의 보드는 복원될 자리가 새 폴더로 바뀐다
    - 없는 폴더로는 옮기지 않는다
    - 폴더 이름을 바꾸면 양쪽 디렉터리 이름과 보드 경로가 함께 바뀐다
    - 못 쓰는 이름(빈 이름, 겹치는 이름, 기본 폴더, 디렉터리로 못 쓰는 문자)은 거절한다
    - 변환 중인 녹음이 있는 폴더는 이름을 바꾸지 않는다
    - 받아쓰기 프롬프트에 폴더 이름이 주제로 들어가고, 뜻 없는 이름은 들어가지 않는다
    - 태블릿 앱의 알림이 기대하는 답이 그대로 온다 (업로드 답의 녹음 번호, 허가 200/409)

실행: `python server/tests/test_folder_sync.py`  (pytest 없이 그냥 돌아간다)
"""
import datetime
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

# 진짜 DB 와 녹음 폴더를 건드리지 않도록, server 를 불러오기 전에 임시 DB 로 돌려 둔다.
_tmp_dir = tempfile.mkdtemp(prefix="yeovyvm-folder-test-")
os.environ["YEOVYVM_DB_PATH"] = os.path.join(_tmp_dir, "test.db")
os.environ["LOGIN_PATH"] = "test-gate"

from fastapi import HTTPException  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from server.database import SessionLocal, init_db  # noqa: E402
from server.models import Board, Folder  # noqa: E402
from server import ai_service, auth, main, migrator  # noqa: E402

AUDIO_DIR = os.path.join(_tmp_dir, "녹음파일원본")
RESULT_DIR = os.path.join(_tmp_dir, "강의 녹음 변환")
for mod in (main, migrator):
    mod.AUDIO_DIR = AUDIO_DIR
    mod.RESULT_DIR = RESULT_DIR
main.TRASH_DIR = os.path.join(_tmp_dir, "휴지통")
os.makedirs(AUDIO_DIR, exist_ok=True)
os.makedirs(RESULT_DIR, exist_ok=True)

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


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def make_folder(db, name, make_dirs=True):
    folder = Folder(name=name)
    db.add(folder)
    db.commit()
    if make_dirs:
        os.makedirs(os.path.join(AUDIO_DIR, name), exist_ok=True)
        os.makedirs(os.path.join(RESULT_DIR, name), exist_ok=True)
    return folder


def make_board(db, folder, stem, status="COMPLETED", audio_dir=None, txt_dir=None, with_txt=True):
    """오디오(와 txt)를 갖춘 보드를 만든다."""
    audio = os.path.join(audio_dir or os.path.join(AUDIO_DIR, folder.name), stem + ".m4a")
    write(audio, "audio of " + stem)
    txt = None
    if with_txt:
        txt = os.path.join(txt_dir or os.path.join(RESULT_DIR, folder.name), stem + ".txt")
        write(txt, "[00:00] text of " + stem)
    b = Board(
        folder_id=folder.id, title=stem, audio_path=audio, audio_filename=stem + ".m4a",
        txt_path=txt, status=status,
        recorded_at=datetime.datetime.fromtimestamp(os.path.getmtime(audio)),
    )
    db.add(b)
    db.commit()
    return b


def status_of(call):
    """엔드포인트 함수를 불러 보고, HTTPException 이 나면 그 상태 코드를 돌려준다 (정상이면 200)."""
    try:
        call()
        return 200
    except HTTPException as e:
        return e.status_code


def same(a, b):
    return os.path.normcase(os.path.abspath(a)) == os.path.normcase(os.path.abspath(b))


init_db()
db = SessionLocal()
default = make_folder(db, "기본 폴더", make_dirs=False)
algo = make_folder(db, "알고리즘")
os_folder = make_folder(db, "운영체제")

# -----------------
# 1. 보드 한 개를 다른 폴더로 옮긴다
# -----------------
a = make_board(db, algo, "1주차 강의")
write(os.path.splitext(a.txt_path)[0] + "_수정본.txt", "edited")
old_audio, old_txt = a.audio_path, a.txt_path

res = main.update_board(a.id, main.BoardUpdate(folder_id=os_folder.id), db)
db.refresh(a)
check("이동: 결과가 moved", res.get("move") == "moved", str(res))
check("이동: 폴더 번호가 바뀜", a.folder_id == os_folder.id)
check("이동: 오디오가 새 폴더 디렉터리에 있음",
      same(a.audio_path, os.path.join(AUDIO_DIR, "운영체제", "1주차 강의.m4a")) and os.path.isfile(a.audio_path),
      str(a.audio_path))
check("이동: txt 가 새 폴더 디렉터리에 있음",
      same(a.txt_path, os.path.join(RESULT_DIR, "운영체제", "1주차 강의.txt")) and os.path.isfile(a.txt_path),
      str(a.txt_path))
check("이동: 옛 자리에는 남지 않음", not os.path.exists(old_audio) and not os.path.exists(old_txt))
check("이동: 동반 파일도 따라감",
      os.path.isfile(os.path.join(RESULT_DIR, "운영체제", "1주차 강의_수정본.txt"))
      and not os.path.exists(os.path.join(RESULT_DIR, "알고리즘", "1주차 강의_수정본.txt")))
check("이동: audio_filename 유지", a.audio_filename == "1주차 강의.m4a", str(a.audio_filename))
check("이동: 내용이 그대로", read(a.audio_path) == "audio of 1주차 강의")

# 같은 폴더로 다시 옮기면 아무 일도 없다
res = main.update_board(a.id, main.BoardUpdate(folder_id=os_folder.id), db)
check("같은 폴더로 이동: 아무것도 안 함", res.get("move") is None and os.path.isfile(a.audio_path), str(res))

# 감시 스레드가 새 자리의 파일을 이 보드의 것으로 알아본다 (새 보드를 만들지 않는다)
check("이동: 새 경로로 보드를 찾을 수 있음",
      db.query(Board).filter(Board.audio_path == a.audio_path).count() == 1)

# -----------------
# 2. 같은 이름의 파일이 이미 있으면 덮어쓰지 않는다
# -----------------
b = make_board(db, algo, "겹치는 이름")
write(os.path.join(AUDIO_DIR, "운영체제", "겹치는 이름.m4a"), "someone else")
main.update_board(b.id, main.BoardUpdate(folder_id=os_folder.id), db)
db.refresh(b)
check("이름 충돌: 남의 파일을 덮지 않음",
      read(os.path.join(AUDIO_DIR, "운영체제", "겹치는 이름.m4a")) == "someone else")
check("이름 충돌: 내 파일은 다른 이름으로 들어감",
      os.path.isfile(b.audio_path) and read(b.audio_path) == "audio of 겹치는 이름"
      and os.path.basename(b.audio_path) == "겹치는 이름_1.m4a", str(b.audio_path))
check("이름 충돌: audio_filename 이 실제 이름과 같음", b.audio_filename == os.path.basename(b.audio_path))

# -----------------
# 3. 없는 폴더로는 옮기지 않는다
# -----------------
c = make_board(db, algo, "어디로도 안 감")
code = status_of(lambda: main.update_board(c.id, main.BoardUpdate(folder_id=99999), db))
db.rollback()
db.refresh(c)
check("없는 폴더: 404", code == 404, str(code))
check("없는 폴더: 보드와 파일은 그대로", c.folder_id == algo.id and os.path.isfile(c.audio_path))
check("없는 폴더(일괄): 404",
      status_of(lambda: main.batch_move_boards(main.BatchMoveRequest(board_ids=[c.id], folder_id=99999), db)) == 404)
db.rollback()

# -----------------
# 4. 변환 중인 보드는 폴더만 바꾸고, 변환이 끝나면 파일을 옮긴다
# -----------------
p = make_board(db, algo, "변환 중인 녹음", status="PROCESSING", with_txt=False)
res = main.update_board(p.id, main.BoardUpdate(folder_id=os_folder.id), db)
db.refresh(p)
check("변환 중: 결과가 deferred", res.get("move") == "deferred", str(res))
check("변환 중: 폴더 번호는 바뀜", p.folder_id == os_folder.id)
check("변환 중: 파일은 건드리지 않음",
      same(p.audio_path, os.path.join(AUDIO_DIR, "알고리즘", "변환 중인 녹음.m4a")) and os.path.isfile(p.audio_path))
check("변환 중: 아직 끝나지 않았으면 정리하지 않음", main.settle_board_files(p.id) is False)

p.status = "COMPLETED"
db.commit()
main._settle_if_folder_changed(p.id, algo.id)      # 워커가 변환을 마친 뒤 부르는 자리
db.refresh(p)
check("변환 끝: 파일이 새 폴더로 감",
      same(p.audio_path, os.path.join(AUDIO_DIR, "운영체제", "변환 중인 녹음.m4a")) and os.path.isfile(p.audio_path),
      str(p.audio_path))

# 변환하는 동안 폴더가 그대로였다면 파일을 건드리지 않는다
q = make_board(db, algo, "그대로 둔 녹음", audio_dir=os.path.join(AUDIO_DIR, "알고리즘", "2학기"), with_txt=False)
main._settle_if_folder_changed(q.id, algo.id)
db.refresh(q)
check("폴더가 그대로: 파일을 옮기지 않음", os.path.isfile(q.audio_path) and "2학기" in q.audio_path, str(q.audio_path))

# -----------------
# 5. 여러 개를 한꺼번에 옮긴다
# -----------------
m1 = make_board(db, algo, "묶음 1")
m2 = make_board(db, algo, "묶음 2")
m3 = make_board(db, os_folder, "이미 거기 있음")
res = main.batch_move_boards(main.BatchMoveRequest(board_ids=[m1.id, m2.id, m3.id], folder_id=os_folder.id), db)
for x in (m1, m2, m3):
    db.refresh(x)
check("일괄 이동: 옮긴 수", res.get("moved_files") == 2 and res.get("count") == 3, str(res))
check("일괄 이동: 파일이 모두 새 폴더에 있음",
      all(os.path.isfile(x.audio_path) and same(os.path.dirname(x.audio_path), os.path.join(AUDIO_DIR, "운영체제"))
          for x in (m1, m2, m3)))
check("일괄 이동: txt 도 모두 새 폴더에 있음",
      all(os.path.isfile(x.txt_path) and same(os.path.dirname(x.txt_path), os.path.join(RESULT_DIR, "운영체제"))
          for x in (m1, m2, m3)))

# -----------------
# 6. 휴지통의 보드를 옮기면 복원될 자리가 바뀐다
# -----------------
t = make_board(db, algo, "지웠다 살릴 녹음")
main.move_board_files_to_trash(t)
t.is_deleted = True
db.commit()
main.update_board(t.id, main.BoardUpdate(folder_id=os_folder.id), db)
db.refresh(t)
check("휴지통 보드: 파일은 휴지통에 그대로", main.is_inside(t.audio_path, main.TRASH_DIR) and os.path.isfile(t.audio_path))
main.restore_board_files_from_trash(t)
t.is_deleted = False
db.commit()
check("휴지통 보드: 복원하면 새 폴더로 돌아옴",
      same(t.audio_path, os.path.join(AUDIO_DIR, "운영체제", "지웠다 살릴 녹음.m4a")) and os.path.isfile(t.audio_path),
      str(t.audio_path))
check("휴지통 보드: txt 도 새 폴더로 돌아옴",
      same(t.txt_path, os.path.join(RESULT_DIR, "운영체제", "지웠다 살릴 녹음.txt")) and os.path.isfile(t.txt_path),
      str(t.txt_path))

# -----------------
# 7. 안쪽에 들어 있는 폴더 디렉터리는 그 자리를 그대로 쓴다 / 기본 폴더로도 옮긴다
# -----------------
nested = make_folder(db, "Dreampath", make_dirs=False)
os.makedirs(os.path.join(AUDIO_DIR, "Default", "Dreampath"))
n = make_board(db, algo, "안쪽으로 갈 녹음", with_txt=False)
main.update_board(n.id, main.BoardUpdate(folder_id=nested.id), db)
db.refresh(n)
check("중첩 폴더: 안쪽 디렉터리로 들어감",
      same(n.audio_path, os.path.join(AUDIO_DIR, "Default", "Dreampath", "안쪽으로 갈 녹음.m4a"))
      and os.path.isfile(n.audio_path), str(n.audio_path))
check("중첩 폴더: 바로 아래에 같은 이름을 또 만들지 않음", not os.path.exists(os.path.join(AUDIO_DIR, "Dreampath")))

main.update_board(n.id, main.BoardUpdate(folder_id=default.id), db)
db.refresh(n)
check("기본 폴더로: 파일이 기본 폴더 자리로 감",
      os.path.isfile(n.audio_path)
      and migrator.get_folder_name(os.path.dirname(n.audio_path), AUDIO_DIR) == "기본 폴더", str(n.audio_path))

# 녹음파일원본 바로 아래의 파일은 이미 기본 폴더 것이다. 옮길 이유가 없다.
r = make_board(db, default, "바로 아래 녹음", audio_dir=AUDIO_DIR, with_txt=False)
r.folder_id = algo.id
db.commit()
main.update_board(r.id, main.BoardUpdate(folder_id=default.id), db)
db.refresh(r)
check("기본 폴더로: 바로 아래에 있던 파일은 그대로", same(r.audio_path, os.path.join(AUDIO_DIR, "바로 아래 녹음.m4a")))

# -----------------
# 8. 폴더 이름을 바꾼다
# -----------------
algol = make_folder(db, "알골")
g1 = make_board(db, algol, "알골 1주차")
g2 = make_board(db, algol, "알골 2주차", audio_dir=os.path.join(AUDIO_DIR, "알골", "보강"))
g3 = make_board(db, algol, "알골 휴지통")
main.move_board_files_to_trash(g3)
g3.is_deleted = True
db.commit()

res = main.rename_folder(algol.id, main.FolderUpdate(name="  알고리즘 설계  "), db)
for x in (algol, g1, g2, g3):
    db.refresh(x)
check("이름 변경: 폴더 이름이 바뀜 (앞뒤 공백 제거)", algol.name == "알고리즘 설계" and res["name"] == "알고리즘 설계", algol.name)
check("이름 변경: 양쪽 디렉터리 이름이 바뀜",
      os.path.isdir(os.path.join(AUDIO_DIR, "알고리즘 설계")) and os.path.isdir(os.path.join(RESULT_DIR, "알고리즘 설계"))
      and not os.path.exists(os.path.join(AUDIO_DIR, "알골")) and not os.path.exists(os.path.join(RESULT_DIR, "알골")),
      str(res))
check("이름 변경: 보드의 오디오 경로가 따라감",
      same(g1.audio_path, os.path.join(AUDIO_DIR, "알고리즘 설계", "알골 1주차.m4a")) and os.path.isfile(g1.audio_path),
      str(g1.audio_path))
check("이름 변경: 보드의 txt 경로가 따라감",
      same(g1.txt_path, os.path.join(RESULT_DIR, "알고리즘 설계", "알골 1주차.txt")) and os.path.isfile(g1.txt_path),
      str(g1.txt_path))
check("이름 변경: 하위 디렉터리의 파일도 따라감",
      same(g2.audio_path, os.path.join(AUDIO_DIR, "알고리즘 설계", "보강", "알골 2주차.m4a")) and os.path.isfile(g2.audio_path),
      str(g2.audio_path))
check("이름 변경: 보드는 같은 폴더에 그대로", g1.folder_id == algol.id and g2.folder_id == algol.id)
main.restore_board_files_from_trash(g3)
g3.is_deleted = False
db.commit()
check("이름 변경: 휴지통 보드는 새 이름의 디렉터리로 복원됨",
      same(g3.audio_path, os.path.join(AUDIO_DIR, "알고리즘 설계", "알골 휴지통.m4a")) and os.path.isfile(g3.audio_path),
      str(g3.audio_path))

# 감시 스레드가 옛 이름의 폴더를 다시 만들 디렉터리가 남아 있지 않다
leaf_names = {migrator.get_folder_name(root, AUDIO_DIR) for root, _, files in os.walk(AUDIO_DIR) if files}
check("이름 변경: 옛 이름으로 보이는 디렉터리가 없음", "알골" not in leaf_names, str(leaf_names))

# 거절해야 하는 이름들
check("이름 변경: 빈 이름 400", status_of(lambda: main.rename_folder(algol.id, main.FolderUpdate(name="   "), db)) == 400)
check("이름 변경: 겹치는 이름 400",
      status_of(lambda: main.rename_folder(algol.id, main.FolderUpdate(name="운영체제"), db)) == 400)
check("이름 변경: '기본 폴더' 로는 못 바꿈 400",
      status_of(lambda: main.rename_folder(algol.id, main.FolderUpdate(name="기본 폴더"), db)) == 400)
check("이름 변경: 기본 폴더는 못 바꿈 400",
      status_of(lambda: main.rename_folder(default.id, main.FolderUpdate(name="아무거나"), db)) == 400)
check("이름 변경: 디렉터리로 못 쓰는 문자 400",
      status_of(lambda: main.rename_folder(algol.id, main.FolderUpdate(name="알고리즘/설계"), db)) == 400)
check("이름 변경: 마침표로 끝나는 이름 400",
      status_of(lambda: main.rename_folder(algol.id, main.FolderUpdate(name="알고리즘."), db)) == 400)
check("이름 변경: 없는 폴더 404",
      status_of(lambda: main.rename_folder(99999, main.FolderUpdate(name="아무거나"), db)) == 404)
db.rollback()
db.refresh(algol)
check("이름 변경: 거절된 뒤에도 이름과 디렉터리는 그대로",
      algol.name == "알고리즘 설계" and os.path.isdir(os.path.join(AUDIO_DIR, "알고리즘 설계")))
res = main.rename_folder(algol.id, main.FolderUpdate(name="알고리즘 설계"), db)
check("이름 변경: 같은 이름이면 아무것도 안 함", res.get("renamed_dirs") == 0)

# 변환 중인 녹음이 있으면 바꾸지 않는다
g1.status = "PROCESSING"
db.commit()
code = status_of(lambda: main.rename_folder(algol.id, main.FolderUpdate(name="바뀌면 안 됨"), db))
db.rollback()
db.refresh(algol)
check("이름 변경: 변환 중이면 409", code == 409, str(code))
check("이름 변경: 변환 중이면 디렉터리도 그대로",
      algol.name == "알고리즘 설계" and os.path.isfile(g1.audio_path) and not os.path.exists(os.path.join(AUDIO_DIR, "바뀌면 안 됨")))
g1.status = "COMPLETED"
db.commit()

# 새 이름의 디렉터리가 이미 있으면 덮어쓰지 않고 합친다
write(os.path.join(AUDIO_DIR, "자료구조", "알골 1주차.m4a"), "already here")
res = main.rename_folder(algol.id, main.FolderUpdate(name="자료구조"), db)
for x in (algol, g1, g2):
    db.refresh(x)
check("합치기: 폴더 이름이 바뀜", algol.name == "자료구조")
check("합치기: 원래 있던 파일을 덮지 않음", read(os.path.join(AUDIO_DIR, "자료구조", "알골 1주차.m4a")) == "already here")
check("합치기: 내 파일은 다른 이름으로 들어가고 경로가 맞음",
      os.path.isfile(g1.audio_path) and read(g1.audio_path) == "audio of 알골 1주차"
      and same(os.path.dirname(g1.audio_path), os.path.join(AUDIO_DIR, "자료구조"))
      and g1.audio_filename == os.path.basename(g1.audio_path), str(g1.audio_path))
check("합치기: 하위 디렉터리의 파일도 경로가 맞음", os.path.isfile(g2.audio_path), str(g2.audio_path))
check("합치기: 옛 디렉터리는 사라짐", not os.path.exists(os.path.join(AUDIO_DIR, "알고리즘 설계")))

# 디렉터리가 없는 폴더도 이름은 바뀐다
bare = make_folder(db, "디렉터리 없는 폴더", make_dirs=False)
res = main.rename_folder(bare.id, main.FolderUpdate(name="이름만 있는 폴더"), db)
db.refresh(bare)
check("디렉터리 없는 폴더: 이름만 바뀜", bare.name == "이름만 있는 폴더" and res.get("renamed_dirs") == 0, str(res))

# 안쪽에 들어 있는 디렉터리는 그 자리에서 이름이 바뀐다
inner = db.query(Folder).filter_by(name="Dreampath").first()
i1 = make_board(db, inner, "드림패스 회의", audio_dir=os.path.join(AUDIO_DIR, "Default", "Dreampath"), with_txt=False)
main.rename_folder(inner.id, main.FolderUpdate(name="드림패스"), db)
db.refresh(i1)
check("중첩 폴더 이름 변경: 제자리에서 바뀜",
      same(i1.audio_path, os.path.join(AUDIO_DIR, "Default", "드림패스", "드림패스 회의.m4a")) and os.path.isfile(i1.audio_path),
      str(i1.audio_path))

# -----------------
# 9. 받아쓰기 프롬프트에 폴더 이름이 주제로 실린다
# -----------------
hint = ai_service.build_subject_prompt("알고리즘", "음성 260909_132904")
check("주제: 폴더 이름이 들어감", "'알고리즘' 폴더" in hint and hint.startswith("5. "), hint)
check("주제: 자동으로 붙은 제목은 넣지 않음", "260909" not in hint and "제목" not in hint, hint)
check("주제: 뜻 있는 제목은 함께 넣음",
      "제목은 '다익스트라와 최단 경로'" in ai_service.build_subject_prompt("알고리즘", "다익스트라와 최단 경로"))
check("주제: 기본 폴더 + 자동 제목이면 넣지 않음", ai_service.build_subject_prompt("기본 폴더", "음성 260909_132904") == "")
check("주제: 폴더가 뜻 없어도 제목이 있으면 넣음",
      "제목은 '운영체제 3주차'" in ai_service.build_subject_prompt("기본 폴더", "운영체제 3주차")
      and "폴더" not in ai_service.build_subject_prompt("기본 폴더", "운영체제 3주차").split("이야.")[0])
check("주제: 폴더가 없어도 터지지 않음", ai_service.build_subject_prompt(None, None) == "")
check("주제: 숫자뿐인 이름은 넣지 않음", ai_service.build_subject_prompt("2026-1", "recording_001") == "")
check("주제: 따옴표·줄바꿈을 걷어 냄",
      "\n" not in ai_service.build_subject_prompt("알고\n리즘 '설계'", None).rstrip("\n")
      and "''" not in ai_service.build_subject_prompt("알고\n리즘 '설계'", None))
check("주제: 한 글자 폴더 이름도 참고함 (C 언어 등)", "'C' 폴더" in ai_service.build_subject_prompt("C", None))

# 실제 변환 경로에서 프롬프트가 어떻게 조립되는지 본다 (AI·ffmpeg 호출만 가짜로 바꾼다)
prompts = []
_real = {name: getattr(ai_service, name) for name in (
    "transcribe_segments", "probe_duration_seconds", "measure_levels_db", "extract_keywords_ai", "generate_summary_ai")}


def fake_transcribe(_audio_path, segments, _label, prompt_text):
    prompts.append(prompt_text)
    return " ".join(f"<<{i + 1}>> 다익스트라 알고리즘입니다." for i in range(len(segments)))


ai_service.transcribe_segments = fake_transcribe
ai_service.probe_duration_seconds = lambda _path: 20.0
ai_service.measure_levels_db = lambda _path: [-25.0] * 200
ai_service.extract_keywords_ai = lambda _text: []
ai_service.generate_summary_ai = lambda _text, _kind="BASIC": "요약"
try:
    s = make_board(db, algol, "받아쓸 녹음", status="PENDING")
    ai_service.process_audio_file_to_board(s.id, s.audio_path, SessionLocal)
    db.refresh(s)
    check("변환: 끝까지 돌았음", s.status == "COMPLETED", f"{s.status} {s.error_message}")
    check("변환: 프롬프트에 폴더 이름이 주제로 들어감",
          bool(prompts) and all("'자료구조' 폴더" in p_ for p_ in prompts), prompts[0][-400:] if prompts else "")
    check("변환: 기본 규칙 4개는 그대로", bool(prompts) and "\n4. 인사말" in prompts[0] and "\n5. 이 녹음은" in prompts[0])

    prompts.clear()
    d0 = make_board(db, default, "음성 261110_090000", status="PENDING",
                    audio_dir=os.path.join(AUDIO_DIR, "기본 폴더"), txt_dir=os.path.join(RESULT_DIR, "기본 폴더"))
    ai_service.process_audio_file_to_board(d0.id, d0.audio_path, SessionLocal)
    check("변환: 뜻 없는 이름이면 주제 항목이 없음",
          bool(prompts) and "이 녹음은" not in prompts[0] and "이 녹음의 제목" not in prompts[0],
          prompts[0][-300:] if prompts else "")
finally:
    for name, fn in _real.items():
        setattr(ai_service, name, fn)

# -----------------
# 10. HTTP 로 불러도 같은 답이 온다 (웹 화면과 태블릿 앱이 기대하는 모양)
# -----------------
# 감시 스레드와 변환 워커는 띄우지 않는다 (startup 이벤트를 쓰지 않는다). 큐에 넣는 자리만 지나가게 한다.
queued = []
main.ensure_workers_alive = lambda: None
main.enqueue_board = lambda board_id: queued.append(board_id)
main.AUTO_TRANSCRIBE = False
main.NEW_BOARD_STATUS = "WAITING"

auth.create_user(db, "owner", "test-password-1234", is_admin=True)
client = TestClient(main.app)
login = client.post(main.LOGIN_SUBMIT_PATH, json={"username": "owner", "password": "test-password-1234"})
check("HTTP: 로그인", login.status_code == 200, f"status={login.status_code}")

http_folder = make_folder(db, "HTTP 폴더")
h = make_board(db, http_folder, "HTTP 녹음")

r = client.patch(f"/api/folders/{http_folder.id}", json={"name": "선형대수"})
check("HTTP 이름 변경: 200 과 바뀐 이름",
      r.status_code == 200 and r.json().get("name") == "선형대수" and r.json().get("renamed_dirs") == 2, r.text)
r = client.patch(f"/api/folders/{http_folder.id}", json={"name": "운영체제"})
check("HTTP 이름 변경: 겹치면 400 과 이유",
      r.status_code == 400 and "이미 존재" in str(r.json().get("detail")), r.text)

r = client.patch(f"/api/boards/{h.id}", json={"folder_id": os_folder.id})
db.expire_all()
db.refresh(h)
check("HTTP 보드 이동: 파일이 따라감",
      r.status_code == 200 and r.json().get("move") == "moved"
      and same(h.audio_path, os.path.join(AUDIO_DIR, "운영체제", "HTTP 녹음.m4a")) and os.path.isfile(h.audio_path),
      r.text + " " + str(h.audio_path))
r = client.patch(f"/api/boards/{h.id}", json={"is_starred": True})
check("HTTP 보드 수정: 폴더를 안 바꾸면 이동 없음", r.status_code == 200 and r.json().get("move") is None, r.text)

r = client.post("/api/boards/batch-move", json={"board_ids": [h.id], "folder_id": http_folder.id})
db.expire_all()
db.refresh(h)
check("HTTP 일괄 이동: 파일이 따라감",
      r.status_code == 200 and r.json().get("moved_files") == 1
      and same(h.audio_path, os.path.join(AUDIO_DIR, "선형대수", "HTTP 녹음.m4a")) and os.path.isfile(h.audio_path),
      r.text + " " + str(h.audio_path))
r = client.post("/api/boards/batch-move", json={"board_ids": [h.id], "folder_id": 99999})
check("HTTP 일괄 이동: 없는 폴더는 404 와 이유", r.status_code == 404 and r.json().get("detail"), r.text)

# 태블릿 앱: 녹음을 올리고 → 알림의 `받아쓰기 시작` 으로 허가하고 → 알림을 눌러 그 녹음을 연다
r = client.post(
    "/api/boards/upload",
    data={"folder_name": "선형대수"},
    files={"file": ("선형대수_20261111_090000.m4a", b"fake audio bytes", "audio/mp4")},
)
answer = r.json() if r.status_code == 200 else {}
new_id = answer.get("board_id")
check("업로드 답: 녹음 번호와 허가가 필요한지를 알려 줌",
      r.status_code == 200 and isinstance(new_id, int) and answer.get("needs_approval") is True
      and answer.get("folder") == "선형대수", r.text)
r = client.post(f"/api/boards/{new_id}/approve")      # 앱이 보내는 모양: 본문 없는 POST
check("허가: 200 이고 변환 큐에 들어감", r.status_code == 200 and queued == [new_id], r.text + " " + str(queued))
r = client.post(f"/api/boards/{new_id}/approve")
check("허가: 이미 시작했으면 409 (앱은 실패가 아니라 `이미 시작함`으로 알린다)",
      r.status_code == 409 and bool(r.json().get("detail")) and queued == [new_id], r.text)
r = client.get(f"/?board={new_id}")
check("알림에서 여는 주소: 로그인돼 있으면 웹 화면이 열림", r.status_code == 200 and "<html" in r.text.lower(), str(r.status_code))
anon = TestClient(main.app)
r = anon.get(f"/?board={new_id}", follow_redirects=False)
# LOGIN_REDIRECT 를 켠 서버는 404 대신 로그인 주소로 보낸다. 어느 쪽이든 앱은 로그인 화면으로 간다.
check("알림에서 여는 주소: 로그인이 풀려 있으면 화면을 주지 않음", r.status_code in (404, 302), str(r.status_code))
check("허가: 로그인이 풀려 있으면 404", anon.post(f"/api/boards/{new_id}/approve").status_code == 404)

db.close()

print(f"통과 {passed}개, 실패 {len(failed)}개")
for f in failed:
    print("  - " + f)
sys.exit(1 if failed else 0)
