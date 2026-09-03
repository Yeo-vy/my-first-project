import os
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from server.models import Base

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# 테스트나 별도 인스턴스에서 다른 DB를 쓰고 싶으면 DAGLO_DB_PATH 로 지정한다
DB_PATH = os.getenv("DAGLO_DB_PATH") or os.path.join(BASE_DIR, "daglo.db")
DATABASE_URL = f"sqlite:///{DB_PATH}"

engine = create_engine(
    DATABASE_URL,
    connect_args={"check_same_thread": False},
    echo=False
)

SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

def init_db():
    Base.metadata.create_all(bind=engine)

def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
