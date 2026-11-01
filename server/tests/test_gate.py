# -*- coding: utf-8 -*-
"""인증 게이트 전수 검사: 로그인하지 않은 요청이 '없는 경로' 와 구분되지 않는지 본다.

등록된 모든 경로 × 7개 메서드 × 슬래시 유무, 정적 파일, 아무 경로, CORS preflight 를
쿠키 없이 / 가짜 쿠키로 / 가짜 API 토큰으로 보내서, 상태·본문·주요 헤더가 맨 FastAPI 앱이
없는 경로에 주는 404 와 한 글자라도 다르면 표시한다. 마지막에 진짜 로그인으로 주요 기능이
도는지 확인한다.

실행: `python server/tests/test_gate.py`  (pytest 없이 그냥 돌아간다)
"""
import os
import re
import secrets
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

_tmp_dir = tempfile.mkdtemp(prefix="yeovyvm-gate-test-")
os.environ["YEOVYVM_DB_PATH"] = os.path.join(_tmp_dir, "test.db")
os.environ["LOGIN_PATH"] = "test-gate"
os.environ["LOGIN_REDIRECT"] = "off"
os.environ["PING_PUBLIC"] = "off"
# 가짜 토큰이 '틀린 토큰' 으로 검사되도록 진짜 토큰을 하나 걸어 둔다.
os.environ["YEOVYVM_API_TOKEN"] = "real-api-token-" + secrets.token_hex(16)
# CORS 미들웨어가 있어도 게이트가 먼저 도는지 보려고 켠다.
os.environ["ALLOWED_ORIGINS"] = "https://allowed.example.com"

from fastapi import FastAPI  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402
from starlette.routing import Mount  # noqa: E402

from server.database import SessionLocal, init_db  # noqa: E402
from server.models import Board, Folder  # noqa: E402
from server import auth, main  # noqa: E402

METHODS = ["GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"]
# 매 요청마다 달라지는 헤더는 비교하지 않는다.
IGNORED_HEADERS = {"date", "server"}

passed = 0
failed = []


def check(name, condition, detail=""):
    global passed
    if condition:
        passed += 1
    else:
        failed.append(f"{name}{(': ' + detail) if detail else ''}")


def fingerprint(resp):
    headers = tuple(sorted((k.lower(), v) for k, v in resp.headers.items() if k.lower() not in IGNORED_HEADERS))
    return resp.status_code, resp.content, headers


# -----------------
# 기준: 아무 경로도 없는 맨 FastAPI 앱이 '없는 경로' 에 주는 응답 (메서드별로)
# -----------------
bare = TestClient(FastAPI(docs_url=None, redoc_url=None, openapi_url=None))
BASELINE = {m: fingerprint(bare.request(m, "/no-such-path-" + m.lower())) for m in METHODS}
check("기준 404 본문", BASELINE["GET"][1] == b'{"detail":"Not Found"}', repr(BASELINE["GET"]))


# -----------------
# 준비
# -----------------
init_db()
db = SessionLocal()
folder = Folder(name="폴더")
db.add(folder)
db.commit()
board = Board(title="보드", folder_id=folder.id, status="COMPLETED")
db.add(board)
db.commit()
auth.create_user(db, "owner", "test-password-1234", is_admin=True)
board_id = board.id
db.close()


# -----------------
# 1. 등록된 경로 전부 뽑기
# -----------------
def sample_path(template):
    return re.sub(r"\{[^}]+\}", "1", template)


route_paths = set()
inventory = []
for r in main.app.routes:
    if isinstance(r, Mount):
        inventory.append(("MOUNT", r.path))
        route_paths.update({r.path + "/app.js", r.path + "/style.css", r.path + "/index.html",
                            r.path + "/", r.path, r.path + "/no-such-file.js"})
    else:
        inventory.append((",".join(sorted(getattr(r, "methods", None) or [])), r.path))
        route_paths.add(sample_path(r.path))

variants = set()
for p in route_paths:
    variants.add(p)
    variants.add(p.rstrip("/") or "/")
    if not p.endswith("/"):
        variants.add(p + "/")
variants.update({
    "/docs", "/redoc", "/openapi.json", "/docs/oauth2-redirect",
    "/admin", "/wp-login.php", "/.env", "/login", "/api", "/api/",
    "/random-" + secrets.token_hex(4), "/api/random-" + secrets.token_hex(4),
    "/static/../server/main.py", "/share/", "/api/share/",
    f"/api/boards/{board_id}", f"/api/audio/{board_id}",
    "/share/fake-" + secrets.token_hex(8), "/api/share/fake-" + secrets.token_hex(8),
    "/api/boards?folder_id=abc", "/api/boards/not-an-int",
})

# 로그인하지 않아도 열리는 것은 비밀 로그인 주소 밑 정상 요청뿐이다.
LOGIN_OPEN = {
    ("GET", main.LOGIN_PATH), ("HEAD", main.LOGIN_PATH),
    ("GET", main.LOGIN_STATUS_PATH), ("HEAD", main.LOGIN_STATUS_PATH),
    ("POST", main.LOGIN_SUBMIT_PATH), ("POST", main.LOGIN_SETUP_PATH),
}


# -----------------
# 2. 인증 없이 전수 요청
# -----------------
CASES = {
    "쿠키 없음": ({}, {}),
    "가짜 세션 쿠키": ({auth.SESSION_COOKIE: secrets.token_urlsafe(48)}, {}),
    "가짜 옛 쿠키": ({"daglo_session": secrets.token_urlsafe(48)}, {}),
    "빈 쿠키": ({auth.SESSION_COOKIE: ""}, {}),
    "가짜 Bearer": ({}, {"Authorization": "Bearer " + secrets.token_hex(16)}),
    "가짜 X-API-Key": ({}, {"X-API-Key": secrets.token_hex(16)}),
}

mismatches = []
total = 0
for case, (cookies, headers) in CASES.items():
    c = TestClient(main.app, raise_server_exceptions=False, follow_redirects=False)
    for k, v in cookies.items():
        c.cookies.set(k, v)
    for path in sorted(variants):
        bare_path = path.split("?")[0]
        for m in METHODS:
            if (m, bare_path) in LOGIN_OPEN:
                continue
            total += 1
            got = fingerprint(c.request(m, path, headers=headers))
            if got != BASELINE[m]:
                mismatches.append((case, m, path, got[0], got[1][:80], dict(got[2])))

    # CORS preflight (허용 오리진으로 보내도 게이트가 먼저 막아야 한다)
    for path in ["/api/boards", "/api/folders", "/no-such-" + secrets.token_hex(3)]:
        total += 1
        got = fingerprint(c.options(path, headers={
            **headers, "Origin": "https://allowed.example.com",
            "Access-Control-Request-Method": "POST",
        }))
        if got != BASELINE["OPTIONS"]:
            mismatches.append((case, "PREFLIGHT", path, got[0], got[1][:80], dict(got[2])))

print(f"인증 없는 요청 {total}건 보냄, 기본 404 와 다른 응답 {len(mismatches)}건")
for mm in mismatches[:40]:
    print("[MISMATCH]", *mm)
check(f"인증 없는 {total}건이 모두 기본 404 와 같다", not mismatches, f"{len(mismatches)}건 다름")


# -----------------
# 3. 로그인 주소 자체는 열려 있어야 한다 (안 열리면 아무도 못 들어온다)
# -----------------
anon = TestClient(main.app, follow_redirects=False)
check("로그인 화면 GET", anon.get(main.LOGIN_PATH).status_code == 200)
check("로그인 상태 GET", anon.get(main.LOGIN_STATUS_PATH).status_code == 200)
bad = anon.post(main.LOGIN_SUBMIT_PATH, json={"username": "owner", "password": "wrong-password"})
check("틀린 비밀번호는 401 (로그인 화면이 메시지를 보여줘야 한다)", bad.status_code == 401, str(bad.status_code))


# -----------------
# 4. 진짜 로그인하면 그대로 동작한다
# -----------------
user = TestClient(main.app, follow_redirects=False)
login = user.post(main.LOGIN_SUBMIT_PATH, json={"username": "owner", "password": "test-password-1234"})
check("로그인", login.status_code == 200, str(login.status_code))
check("세션 쿠키 발급", bool(user.cookies.get(auth.SESSION_COOKIE)))
check("메인 화면", user.get("/").status_code == 200)
check("정적 파일", user.get("/static/app.js").status_code == 200)
check("내 정보", user.get("/api/auth/me").status_code == 200)
check("폴더 목록", user.get("/api/folders").status_code == 200)
check("보드 목록", user.get("/api/boards").status_code == 200)
check("보드 상세", user.get(f"/api/boards/{board_id}").status_code == 200)
created = user.post("/api/folders", json={"name": "새 폴더"})
check("폴더 만들기", created.status_code in (200, 201), f"{created.status_code} {created.text[:100]}")
check("/docs 는 로그인해도 없다", user.get("/docs").status_code == 404)
check("/openapi.json 은 로그인해도 없다", user.get("/openapi.json").status_code == 404)
real_api = TestClient(main.app)
check("진짜 API 토큰은 통과", real_api.get("/api/folders", headers={
    "Authorization": "Bearer " + os.environ["YEOVYVM_API_TOKEN"]}).status_code == 200)

# 로그아웃하면 그 쿠키는 즉시 죽는다
stolen = user.cookies.get(auth.SESSION_COOKIE)
user.post("/api/auth/logout")
replay = TestClient(main.app)
replay.cookies.set(auth.SESSION_COOKIE, stolen)
check("로그아웃한 쿠키는 기본 404", fingerprint(replay.get("/api/folders")) == BASELINE["GET"])


print("\n등록된 경로:")
for methods, path in inventory:
    print(f"  {methods:<22} {path}")
print(f"\n통과 {passed}건, 실패 {len(failed)}건")
for f in failed:
    print("  [FAIL]", f)
sys.exit(1 if failed else 0)
