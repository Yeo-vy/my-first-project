import os
from sqlalchemy import create_engine, event
from sqlalchemy.orm import sessionmaker
from server.models import Base

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# 테스트나 별도 인스턴스에서 다른 DB를 쓰고 싶으면 DAGLO_DB_PATH 로 지정한다
DB_PATH = os.getenv("DAGLO_DB_PATH") or os.path.join(BASE_DIR, "daglo.db")
DATABASE_URL = f"sqlite:///{DB_PATH}"

# 이 DB 는 여러 스레드가 동시에 두드린다: 웹 요청 + STT 워커(기본 2개) + 5초 주기 감시 스레드.
# 기본 설정(rollback journal + timeout 5초)이면 변환 중 진행률 커밋과 웹 요청이 겹치는 순간
# `database is locked` 로 요청이 터지거나 워커가 보드를 실패 처리해 버린다.
# 아래 두 가지로 막는다.
#   - WAL: 읽기(웹 목록 조회)와 쓰기(워커 진행률)가 서로를 막지 않는다
#   - busy_timeout: 그래도 겹치면 즉시 실패하지 말고 그 시간만큼 기다렸다 다시 잡는다
SQLITE_BUSY_TIMEOUT_SEC = max(5, int(os.getenv("SQLITE_BUSY_TIMEOUT", "30")))

engine = create_engine(
    DATABASE_URL,
    connect_args={"check_same_thread": False, "timeout": SQLITE_BUSY_TIMEOUT_SEC},
    echo=False
)


@event.listens_for(engine, "connect")
def _set_sqlite_pragmas(dbapi_conn, _record):
    """새 연결마다 PRAGMA 를 건다 (PRAGMA 는 연결 단위 설정이라 한 번만 걸면 안 된다)."""
    cur = dbapi_conn.cursor()
    try:
        cur.execute("PRAGMA journal_mode=WAL")
        cur.execute(f"PRAGMA busy_timeout={SQLITE_BUSY_TIMEOUT_SEC * 1000}")
        # WAL 에서는 NORMAL 로도 크래시 시 커밋 내용이 남는다 (FULL 대비 쓰기가 훨씬 빠르다)
        cur.execute("PRAGMA synchronous=NORMAL")
        cur.execute("PRAGMA foreign_keys=ON")
    finally:
        cur.close()

SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

def init_db():
    Base.metadata.create_all(bind=engine)

def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
