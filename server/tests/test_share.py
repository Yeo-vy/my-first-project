# -*- coding: utf-8 -*-
"""공유 링크가 '읽기 전용 + 이 보드만' 을 지키는지 검증.

공유 링크는 로그인하지 않은 사람이 여는 유일한 통로라서, 여기서 한 칸만 새도 서버 전체가
열린다. 그래서 '되는 것' 보다 '안 되는 것' 을 더 많이 확인한다.

    - 공유 안 한 보드는 토큰이 없으니 아무것도 열리지 않는다
    - 공유한 보드는 스크립트와 오디오만 열린다
    - 같은 토큰으로 수정(PUT/PATCH/POST/DELETE)은 안 된다
    - 토큰이 있어도 목록·다른 보드·로그인 API 는 여전히 잠겨 있다
    - 응답에 다른 보드로 갈 실마리(보드 번호·폴더 이름)가 들어 있지 않다
    - 공유를 해제하면 그 즉시 죽는다

실행: `python server/tests/test_share.py`  (pytest 없이 그냥 돌아간다)
"""
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

# 진짜 DB 를 건드리지 않도록, server 를 불러오기 전에 임시 DB 로 돌려 둔다.
_tmp_dir = tempfile.mkdtemp(prefix="yeovyvm-share-test-")
os.environ["YEOVYVM_DB_PATH"] = os.path.join(_tmp_dir, "test.db")
os.environ["LOGIN_PATH"] = "test-gate"
# 감시 스레드와 STT 워커까지 띄울 필요는 없다. startup 이벤트를 쓰지 않고 직접 테이블만 만든다.

from fastapi.testclient import TestClient  # noqa: E402

from server.database import SessionLocal, init_db  # noqa: E402
from server.models import Board, BoardShareView, Folder, TranscriptSegment  # noqa: E402
from server import auth, main  # noqa: E402

passed = 0
failed = []


def check(name, condition, detail=""):
    global passed
    if condition:
        passed += 1
    else:
        failed.append(f"{name}{(': ' + detail) if detail else ''}")


# -----------------
# 준비: 보드 두 개와 로그인한 주인
# -----------------
init_db()
db = SessionLocal()

folder = Folder(name="비밀 폴더")
db.add(folder)
db.commit()

shared_board = Board(title="공유할 강의", folder_id=folder.id, status="COMPLETED", duration_seconds=125.0)
other_board = Board(title="남에게 보이면 안 되는 강의", folder_id=folder.id, status="COMPLETED")
db.add_all([shared_board, other_board])
db.commit()

db.add(TranscriptSegment(
    board_id=shared_board.id, sequence=0, start_time_ms=0, end_time_ms=3000,
    timestamp_str="[00:00]", speaker="화자 1", content="공유된 첫 문장입니다.",
))
db.add(TranscriptSegment(
    board_id=other_board.id, sequence=0, start_time_ms=0, end_time_ms=3000,
    timestamp_str="[00:00]", speaker="화자 1", content="이건 새면 안 되는 문장입니다.",
))
db.commit()

owner = auth.create_user(db, "owner", "test-password-1234", is_admin=True)
shared_id, other_id = shared_board.id, other_board.id
db.close()

client = TestClient(main.app)

# 주인으로 로그인 (쿠키가 client 에 남는다)
login = client.post(main.LOGIN_SUBMIT_PATH, json={"username": "owner", "password": "test-password-1234"})
check("주인 로그인", login.status_code == 200, f"status={login.status_code}")

anon = TestClient(main.app)  # 로그인하지 않은 브라우저


# -----------------
# 1. 공유하기 전
# -----------------
state = client.get(f"/api/boards/{shared_id}/share").json()
check("공유 전에는 꺼져 있다", state["shared"] is False and state["url"] is None, str(state))

check("로그인 없이 보드 상세는 막힌다", anon.get(f"/api/boards/{shared_id}").status_code == 401)
check("로그인 없이 목록은 막힌다", anon.get("/api/boards").status_code == 401)
check("아무 토큰이나 넣으면 404", anon.get("/api/share/아무거나-틀린토큰").status_code == 404)
check("아무 토큰이나 넣은 공유 화면도 404", anon.get("/share/아무거나-틀린토큰").status_code == 404)


# -----------------
# 2. 공유 켜기
# -----------------
created = client.post(f"/api/boards/{shared_id}/share")
check("공유 생성 성공", created.status_code == 200, f"status={created.status_code}")
state = created.json()
token = (state.get("url") or "").rstrip("/").split("/")[-1]
check("공유 주소에 토큰이 들어 있다", bool(token) and len(token) >= 16, str(state))

again = client.post(f"/api/boards/{shared_id}/share").json()
check("다시 눌러도 같은 링크", again["url"] == state["url"], f"{again['url']} != {state['url']}")


# -----------------
# 3. 공유받은 사람이 볼 수 있는 것
# -----------------
page = anon.get(f"/share/{token}")
check("공유 화면이 로그인 없이 열린다", page.status_code == 200, f"status={page.status_code}")
check("공유 화면은 검색엔진에 안 걸리게 한다", "noindex" in page.headers.get("x-robots-tag", ""))

doc = anon.get(f"/api/share/{token}")
check("공유 내용이 로그인 없이 열린다", doc.status_code == 200, f"status={doc.status_code}")
data = doc.json() if doc.status_code == 200 else {}
check("제목이 보인다", data.get("title") == "공유할 강의", str(data.get("title")))
check("스크립트가 보인다",
      len(data.get("segments", [])) == 1 and "공유된 첫 문장" in data["segments"][0]["content"],
      str(data.get("segments")))


# -----------------
# 4. 새면 안 되는 것
# -----------------
body = doc.text
check("폴더 이름이 새지 않는다", "비밀 폴더" not in body)
check("다른 보드 제목이 새지 않는다", "보이면 안 되는" not in body)
check("보드 번호가 새지 않는다", "folder_id" not in data and "id" not in data, str(sorted(data.keys())))

check("토큰이 있어도 목록은 막힌다", anon.get("/api/boards").status_code == 401)
check("토큰이 있어도 다른 보드는 막힌다", anon.get(f"/api/boards/{other_id}").status_code == 401)
check("토큰이 있어도 다른 보드 오디오는 막힌다", anon.get(f"/api/audio/{other_id}").status_code == 401)
check("토큰이 있어도 폴더 목록은 막힌다", anon.get("/api/folders").status_code == 401)
check("토큰이 있어도 사용자 목록은 막힌다", anon.get("/api/auth/users").status_code == 401)
check("공유 화면에서 메인은 안 열린다", anon.get("/").status_code == 404)


# -----------------
# 5. 고칠 수 없어야 한다
# -----------------
check("공유 주소로 스크립트 수정 불가",
      anon.put(f"/api/boards/{shared_id}/transcript", json={"segments": []}).status_code == 401)
check("공유 주소로 제목 수정 불가",
      anon.patch(f"/api/boards/{shared_id}", json={"title": "바꿔치기"}).status_code == 401)
check("공유 주소로 보드 삭제 불가",
      anon.delete(f"/api/boards/{shared_id}").status_code == 401)
check("공유 주소로 공유 해제 불가",
      anon.delete(f"/api/boards/{shared_id}/share").status_code == 401)
# /api/share 밑으로 쓰기 요청이 와도 게이트가 먼저 막는다 (405 가 아니라 401 이어야 한다)
check("공유 접두사 밑 POST 는 게이트가 막는다",
      anon.post(f"/api/share/{token}", json={"title": "바꿔치기"}).status_code == 401)
check("공유 접두사 밑 DELETE 도 게이트가 막는다",
      anon.delete(f"/api/share/{token}").status_code == 401)

unchanged = client.get(f"/api/boards/{shared_id}").json()
check("내용이 그대로다", unchanged["title"] == "공유할 강의" and len(unchanged["segments"]) == 1)


# -----------------
# 6. 열람 기록과 해제
# -----------------
state = client.get(f"/api/boards/{shared_id}/share").json()
check("열람 시각이 남는다", state.get("last_viewed_at") is not None, str(state))
viewers = state.get("viewers") or []
check("접속자 IP 가 남는다", len(viewers) >= 1 and bool(viewers[0].get("ip")), str(state))

# 같은 IP 가 다시 열면 줄이 늘지 않고 횟수만 오른다
before = {v["ip"]: v["view_count"] for v in viewers}
anon.get(f"/api/share/{token}")
after = client.get(f"/api/boards/{shared_id}/share").json()["viewers"]
check("같은 IP 는 한 줄로 합쳐진다", len(after) == len(viewers), str(after))
check("다시 열면 횟수가 오른다",
      any(v["view_count"] == before.get(v["ip"], 0) + 1 for v in after), str(after))

# 프록시 뒤에서 온 요청은 X-Forwarded-For 의 첫 주소로 남는다
anon.get(f"/api/share/{token}", headers={"X-Forwarded-For": "203.0.113.7, 10.0.0.1"})
after = client.get(f"/api/boards/{shared_id}/share").json()["viewers"]
check("새 IP 가 맨 위에 보인다", after and after[0]["ip"] == "203.0.113.7", str(after))

revoked = client.delete(f"/api/boards/{shared_id}/share")
check("공유 해제 성공", revoked.status_code == 200 and revoked.json()["shared"] is False)
check("해제하면 접속 기록도 지워진다", SessionLocal().query(BoardShareView).count() == 0)
check("해제하면 내용이 바로 죽는다", anon.get(f"/api/share/{token}").status_code == 404)
check("해제하면 화면도 바로 죽는다", anon.get(f"/share/{token}").status_code == 404)


# -----------------
# 7. 휴지통에 들어간 보드는 열리지 않는다
# -----------------
token2 = (client.post(f"/api/boards/{shared_id}/share").json()["url"] or "").rstrip("/").split("/")[-1]
check("다시 공유하면 새 토큰", bool(token2) and token2 != token)
check("새 토큰으로 열린다", anon.get(f"/api/share/{token2}").status_code == 200)

db = SessionLocal()
db.query(Board).filter_by(id=shared_id).first().is_deleted = True
db.commit()
db.close()
check("휴지통에 넣으면 공유가 닫힌다", anon.get(f"/api/share/{token2}").status_code == 404)


# -----------------
# 결과
# -----------------
print(f"\n통과 {passed}개")
if failed:
    print(f"실패 {len(failed)}개")
    for f in failed:
        print(f"  - {f}")
    sys.exit(1)
print("모두 통과했습니다.")
