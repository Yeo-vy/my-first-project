import datetime
from sqlalchemy import Column, Integer, String, Text, DateTime, ForeignKey, Float, Boolean
from sqlalchemy.orm import declarative_base, relationship

Base = declarative_base()

class Folder(Base):
    __tablename__ = "folders"

    id = Column(Integer, primary_key=True, index=True)
    name = Column(String(255), nullable=False, unique=True, index=True)
    parent_id = Column(Integer, ForeignKey("folders.id"), nullable=True)
    created_at = Column(DateTime, default=datetime.datetime.utcnow)

    boards = relationship("Board", back_populates="folder", cascade="all, delete-orphan")


class Board(Base):
    __tablename__ = "boards"

    id = Column(Integer, primary_key=True, index=True)
    folder_id = Column(Integer, ForeignKey("folders.id"), nullable=True, index=True)
    title = Column(String(500), nullable=False, index=True)
    audio_path = Column(String(1000), nullable=True)
    audio_filename = Column(String(500), nullable=True)
    txt_path = Column(String(1000), nullable=True)
    duration_seconds = Column(Float, default=0.0)
    status = Column(String(50), default="COMPLETED", index=True)  # COMPLETED, PROCESSING, PENDING, FAILED
    progress_percent = Column(Integer, default=100)
    error_message = Column(Text, nullable=True)
    keywords_json = Column(Text, default="[]")  # e.g. '["객체", "바이트"]'
    speaker_map_json = Column(Text, default="{}")  # e.g. '{"화자 1": "교수님"}'
    is_starred = Column(Boolean, default=False, index=True)
    is_deleted = Column(Boolean, default=False, index=True)
    recorded_at = Column(DateTime, nullable=True)
    created_at = Column(DateTime, default=datetime.datetime.utcnow, index=True)
    updated_at = Column(DateTime, default=datetime.datetime.utcnow, onupdate=datetime.datetime.utcnow)

    folder = relationship("Folder", back_populates="boards")
    segments = relationship("TranscriptSegment", back_populates="board", cascade="all, delete-orphan", order_by="TranscriptSegment.sequence")
    summaries = relationship("BoardSummary", back_populates="board", cascade="all, delete-orphan")
    chats = relationship("BoardChat", back_populates="board", cascade="all, delete-orphan", order_by="BoardChat.created_at")
    bookmarks = relationship("Bookmark", back_populates="board", cascade="all, delete-orphan", order_by="Bookmark.timestamp_ms")
    # 보드를 지우면 공유 링크도 같이 죽어야 한다 (남아 있으면 삭제한 보드가 계속 열린다)
    share = relationship("BoardShare", back_populates="board", cascade="all, delete-orphan", uselist=False)


class TranscriptSegment(Base):
    __tablename__ = "transcript_segments"

    id = Column(Integer, primary_key=True, index=True)
    board_id = Column(Integer, ForeignKey("boards.id"), nullable=False, index=True)
    start_time_ms = Column(Integer, default=0, index=True)
    end_time_ms = Column(Integer, default=0)
    timestamp_str = Column(String(20), default="[00:00]")
    speaker = Column(String(100), default="화자 1", index=True)
    content = Column(Text, nullable=False)
    sequence = Column(Integer, default=0, index=True)

    board = relationship("Board", back_populates="segments")


class BoardSummary(Base):
    __tablename__ = "board_summaries"

    id = Column(Integer, primary_key=True, index=True)
    board_id = Column(Integer, ForeignKey("boards.id"), nullable=False, index=True)
    summary_type = Column(String(50), default="BASIC", index=True)  # BASIC, MEETING, ACTION_ITEM, QUIZ, SLIDE
    title = Column(String(255), default="기본 요약")
    content = Column(Text, nullable=False)
    created_at = Column(DateTime, default=datetime.datetime.utcnow)

    board = relationship("Board", back_populates="summaries")


class BoardChat(Base):
    __tablename__ = "board_chats"

    id = Column(Integer, primary_key=True, index=True)
    board_id = Column(Integer, ForeignKey("boards.id"), nullable=False, index=True)
    role = Column(String(50), nullable=False)  # user, assistant
    message = Column(Text, nullable=False)
    created_at = Column(DateTime, default=datetime.datetime.utcnow)

    board = relationship("Board", back_populates="chats")


class Bookmark(Base):
    __tablename__ = "bookmarks"

    id = Column(Integer, primary_key=True, index=True)
    board_id = Column(Integer, ForeignKey("boards.id"), nullable=False, index=True)
    timestamp_ms = Column(Integer, nullable=False, index=True)
    timestamp_str = Column(String(20), nullable=False)
    note = Column(String(500), default="")
    created_at = Column(DateTime, default=datetime.datetime.utcnow)

    board = relationship("Board", back_populates="bookmarks")


class GlossaryTerm(Base):
    """받아쓰기에 힌트로 넣어 주는 단어장 항목.

    강의 고유명사·전문용어는 발음만으로는 자주 틀리게 적힌다. 이 목록을 STT 프롬프트에
    같이 넣어 표기를 고정한다. folder_id 가 비어 있으면 모든 폴더에 적용되는 공통 용어다.
    """
    __tablename__ = "glossary_terms"

    id = Column(Integer, primary_key=True, index=True)
    folder_id = Column(Integer, ForeignKey("folders.id"), nullable=True, index=True)
    term = Column(String(200), nullable=False)
    note = Column(String(300), default="")  # 설명 또는 자주 잘못 인식되는 표기
    created_at = Column(DateTime, default=datetime.datetime.utcnow)


class BoardShare(Base):
    """보드 하나를 '읽기 전용'으로 바깥에 여는 공유 링크.

    로그인 세션(UserSession)과 달리 토큰을 해시가 아니라 그대로 보관한다. 공유 링크는
    사람이 나중에 다시 복사해 갈 수 있어야 하기 때문이다. 대신 이 토큰으로 열리는 것은
    이 보드의 스크립트와 오디오뿐이고, 목록·다른 보드·수정 API 는 열리지 않는다.

    보드당 한 개만 둔다(board_id unique). 공유를 끄면 행을 지우므로 링크는 그 즉시 죽는다.
    """
    __tablename__ = "board_shares"

    id = Column(Integer, primary_key=True, index=True)
    board_id = Column(Integer, ForeignKey("boards.id"), nullable=False, unique=True, index=True)
    token = Column(String(64), nullable=False, unique=True, index=True)
    created_at = Column(DateTime, default=datetime.datetime.utcnow)
    # 마지막으로 열람된 시각. 공유 화면에서 '누가 보고 갔는지' 대신 '열린 적이 있는지'만 남긴다.
    last_viewed_at = Column(DateTime, nullable=True)

    board = relationship("Board", back_populates="share")


class User(Base):
    __tablename__ = "users"

    id = Column(Integer, primary_key=True, index=True)
    username = Column(String(64), nullable=False, unique=True, index=True)
    display_name = Column(String(100), nullable=False, default="")
    password_hash = Column(String(255), nullable=False)
    is_admin = Column(Boolean, default=False)
    is_active = Column(Boolean, default=True, index=True)
    created_at = Column(DateTime, default=datetime.datetime.utcnow)
    last_login_at = Column(DateTime, nullable=True)

    sessions = relationship("UserSession", back_populates="user", cascade="all, delete-orphan")


class UserSession(Base):
    __tablename__ = "user_sessions"

    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    # 원본 토큰은 저장하지 않는다. DB가 유출돼도 세션을 위조할 수 없도록 SHA-256 해시만 보관.
    token_hash = Column(String(64), nullable=False, unique=True, index=True)
    user_agent = Column(String(300), default="")
    ip_address = Column(String(64), default="")
    created_at = Column(DateTime, default=datetime.datetime.utcnow)
    expires_at = Column(DateTime, nullable=False, index=True)

    user = relationship("User", back_populates="sessions")
