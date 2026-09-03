"""다글로 서버 인증 모듈.

세션 쿠키 기반 로그인을 담당한다. 외부 의존성 없이 표준 라이브러리(hashlib/secrets)만
사용하며, 비밀번호는 PBKDF2-HMAC-SHA256으로, 세션 토큰은 SHA-256 해시로 저장한다.
"""

import os
import base64
import hashlib
import hmac
import secrets
import datetime
import threading
from typing import Optional, Dict, List

from fastapi import Depends, HTTPException, Request
from sqlalchemy.orm import Session

from server.database import get_db
from server.models import User, UserSession

# -----------------
# 설정
# -----------------
SESSION_COOKIE = "daglo_session"
SESSION_DAYS = max(1, int(os.getenv("SESSION_DAYS", "14")))
# 리버스 프록시 뒤 HTTPS로 서비스한다면 COOKIE_SECURE=1 로 켜는 것을 권장.
COOKIE_SECURE = os.getenv("COOKIE_SECURE", "0").lower() in ("1", "true", "yes")

PBKDF2_ITERATIONS = 260_000
MIN_PASSWORD_LENGTH = 8

# 로그인 시도 제한 (무차별 대입 방어)
MAX_LOGIN_FAILURES = 8
LOCKOUT_SECONDS = 15 * 60

_failures: Dict[str, List[float]] = {}
_failure_lock = threading.Lock()


# -----------------
# 비밀번호 해싱
# -----------------
def hash_password(password: str) -> str:
    """`pbkdf2_sha256$<iterations>$<salt>$<hash>` 형식 문자열을 만든다."""
    salt = secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, PBKDF2_ITERATIONS)
    return "pbkdf2_sha256${}${}${}".format(
        PBKDF2_ITERATIONS,
        base64.b64encode(salt).decode(),
        base64.b64encode(digest).decode(),
    )


def verify_password(password: str, stored: str) -> bool:
    """저장된 해시와 비교한다. 형식이 깨졌더라도 예외 대신 False를 돌려준다."""
    try:
        algorithm, iterations, salt_b64, hash_b64 = (stored or "").split("$")
        if algorithm != "pbkdf2_sha256":
            return False
        digest = hashlib.pbkdf2_hmac(
            "sha256",
            password.encode("utf-8"),
            base64.b64decode(salt_b64),
            int(iterations),
        )
        return hmac.compare_digest(digest, base64.b64decode(hash_b64))
    except Exception:
        return False


def validate_password(password: str) -> None:
    """정책에 어긋나면 400을 던진다."""
    if len(password or "") < MIN_PASSWORD_LENGTH:
        raise HTTPException(
            status_code=400,
            detail=f"비밀번호는 {MIN_PASSWORD_LENGTH}자 이상이어야 합니다.",
        )


# -----------------
# 로그인 시도 제한
# -----------------
def _now() -> float:
    return datetime.datetime.utcnow().timestamp()


def lockout_remaining(key: str) -> int:
    """남은 잠금 시간(초). 0이면 잠기지 않은 상태."""
    with _failure_lock:
        cutoff = _now() - LOCKOUT_SECONDS
        recent = [t for t in _failures.get(key, []) if t > cutoff]
        if recent:
            _failures[key] = recent
        else:
            _failures.pop(key, None)
        if len(recent) < MAX_LOGIN_FAILURES:
            return 0
        return max(1, int(recent[0] + LOCKOUT_SECONDS - _now()))


def record_failure(key: str) -> None:
    with _failure_lock:
        cutoff = _now() - LOCKOUT_SECONDS
        recent = [t for t in _failures.get(key, []) if t > cutoff]
        recent.append(_now())
        _failures[key] = recent


def clear_failures(key: str) -> None:
    with _failure_lock:
        _failures.pop(key, None)


def client_ip(request: Request) -> str:
    forwarded = request.headers.get("x-forwarded-for", "")
    if forwarded:
        return forwarded.split(",")[0].strip()[:64]
    return (request.client.host if request.client else "unknown")[:64]


# -----------------
# 세션 관리
# -----------------
def _token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def purge_expired_sessions(db: Session) -> None:
    db.query(UserSession).filter(
        UserSession.expires_at < datetime.datetime.utcnow()
    ).delete(synchronize_session=False)
    db.commit()


def create_session(db: Session, user: User, request: Request) -> str:
    """새 세션을 만들고 클라이언트에 내려줄 원본 토큰을 반환한다."""
    purge_expired_sessions(db)
    token = secrets.token_urlsafe(48)
    session = UserSession(
        user_id=user.id,
        token_hash=_token_hash(token),
        user_agent=(request.headers.get("user-agent") or "")[:300],
        ip_address=client_ip(request),
        expires_at=datetime.datetime.utcnow() + datetime.timedelta(days=SESSION_DAYS),
    )
    db.add(session)
    user.last_login_at = datetime.datetime.utcnow()
    db.commit()
    return token


def destroy_session(db: Session, token: Optional[str]) -> None:
    if not token:
        return
    db.query(UserSession).filter(
        UserSession.token_hash == _token_hash(token)
    ).delete(synchronize_session=False)
    db.commit()


def destroy_all_sessions_for_user(db: Session, user_id: int, keep_token: Optional[str] = None) -> None:
    query = db.query(UserSession).filter(UserSession.user_id == user_id)
    if keep_token:
        query = query.filter(UserSession.token_hash != _token_hash(keep_token))
    query.delete(synchronize_session=False)
    db.commit()


def resolve_session_user(db: Session, token: Optional[str]) -> Optional[User]:
    """쿠키 토큰으로 사용자를 찾는다. 만료·비활성 계정이면 None."""
    if not token:
        return None
    session = (
        db.query(UserSession)
        .filter(UserSession.token_hash == _token_hash(token))
        .one_or_none()
    )
    if session is None:
        return None
    if session.expires_at < datetime.datetime.utcnow():
        db.delete(session)
        db.commit()
        return None
    user = db.query(User).filter(User.id == session.user_id).one_or_none()
    if user is None or not user.is_active:
        return None
    return user


def set_session_cookie(response, token: str) -> None:
    response.set_cookie(
        key=SESSION_COOKIE,
        value=token,
        max_age=SESSION_DAYS * 24 * 3600,
        httponly=True,
        samesite="lax",
        secure=COOKIE_SECURE,
        path="/",
    )


def clear_session_cookie(response) -> None:
    response.delete_cookie(key=SESSION_COOKIE, path="/")


# -----------------
# 기계 클라이언트용 API 토큰
# -----------------
def api_token_ok(request: Request) -> bool:
    """DAGLO_API_TOKEN 이 설정된 경우에만 헤더 토큰 인증을 허용한다."""
    expected = os.getenv("DAGLO_API_TOKEN", "")
    if not expected:
        return False
    provided = request.headers.get("x-api-key", "")
    if not provided:
        auth_header = request.headers.get("authorization", "")
        if auth_header.lower().startswith("bearer "):
            provided = auth_header[7:].strip()
    return bool(provided) and hmac.compare_digest(provided, expected)


# -----------------
# 사용자 생성 / 부트스트랩
# -----------------
def has_any_user(db: Session) -> bool:
    return db.query(User).count() > 0


def normalize_username(username: str) -> str:
    username = (username or "").strip().lower()
    if not (3 <= len(username) <= 64):
        raise HTTPException(status_code=400, detail="아이디는 3~64자여야 합니다.")
    if not all(ch.isalnum() or ch in "_-." for ch in username):
        raise HTTPException(status_code=400, detail="아이디는 영문/숫자/._- 만 사용할 수 있습니다.")
    return username


def create_user(db: Session, username: str, password: str,
                display_name: str = "", is_admin: bool = False) -> User:
    username = normalize_username(username)
    validate_password(password)
    if db.query(User).filter(User.username == username).first():
        raise HTTPException(status_code=409, detail="이미 존재하는 아이디입니다.")
    user = User(
        username=username,
        display_name=(display_name or username).strip()[:100],
        password_hash=hash_password(password),
        is_admin=is_admin,
        is_active=True,
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    return user


def bootstrap_admin_from_env(db: Session) -> None:
    """ADMIN_USERNAME/ADMIN_PASSWORD 가 있고 계정이 하나도 없으면 관리자 계정을 만든다."""
    username = os.getenv("ADMIN_USERNAME", "").strip()
    password = os.getenv("ADMIN_PASSWORD", "")
    if not username or not password or has_any_user(db):
        return
    try:
        create_user(db, username, password, display_name=username, is_admin=True)
        print(f"[AUTH] .env 설정으로 관리자 계정을 생성했습니다: {username}")
    except HTTPException as e:
        print(f"[AUTH-ERROR] 관리자 계정 자동 생성 실패: {e.detail}")


def user_to_dict(user: User) -> dict:
    return {
        "id": user.id,
        "username": user.username,
        "display_name": user.display_name or user.username,
        "is_admin": bool(user.is_admin),
        "is_active": bool(user.is_active),
        "created_at": user.created_at.isoformat() if user.created_at else None,
        "last_login_at": user.last_login_at.isoformat() if user.last_login_at else None,
    }


# -----------------
# FastAPI 의존성
# -----------------
def get_current_user(request: Request, db: Session = Depends(get_db)) -> User:
    """미들웨어를 통과한 요청에서 로그인 사용자 객체를 꺼낸다."""
    user = resolve_session_user(db, request.cookies.get(SESSION_COOKIE))
    if user is None:
        raise HTTPException(status_code=401, detail="로그인이 필요합니다.")
    return user


def get_current_admin(user: User = Depends(get_current_user)) -> User:
    if not user.is_admin:
        raise HTTPException(status_code=403, detail="관리자만 사용할 수 있습니다.")
    return user
