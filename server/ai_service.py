import os
import re
import json
import tempfile
import uuid
import datetime
from typing import AsyncGenerator, List, Dict, Optional, Callable
from dotenv import load_dotenv
from google import genai
from sqlalchemy.orm import Session

try:
    from pydub import AudioSegment
except ImportError:
    AudioSegment = None

load_dotenv()

api_keys = [k for k in [os.getenv("GEMINI_API_KEY"), os.getenv("GEMINI_API_KEY_PAID")] if k]
active_key_index = 0

CHUNK_LENGTH_MS = 20 * 60 * 1000   # 20분 청크
OVERLAP_MS = 30 * 1000             # 30초 오버랩
CHUNK_STEP_MS = CHUNK_LENGTH_MS - OVERLAP_MS
TIMESTAMP_PATTERN = re.compile(r'\[(\d{2}:\d{2}(?::\d{2})?)\]')

def get_client() -> Optional[genai.Client]:
    global active_key_index
    if not api_keys:
        return None
    return genai.Client(api_key=api_keys[active_key_index])

def is_quota_error(err: Exception) -> bool:
    msg = str(err).upper()
    return "429" in msg or "RESOURCE_EXHAUSTED" in msg or "QUOTA" in msg

def timestamp_to_seconds(ts_str: str) -> int:
    parts = list(map(int, ts_str.split(':')))
    if len(parts) == 2:
        return parts[0] * 60 + parts[1]
    elif len(parts) == 3:
        return parts[0] * 3600 + parts[1] * 60 + parts[2]
    return 0

def ms_to_timestamp_str(ms: int) -> str:
    total_seconds = max(0, ms // 1000)
    hours = total_seconds // 3600
    minutes = (total_seconds % 3600) // 60
    seconds = total_seconds % 60
    if hours > 0:
        return f"[{hours:02d}:{minutes:02d}:{seconds:02d}]"
    return f"[{minutes:02d}:{seconds:02d}]"

def offset_timestamps(text: str, offset_seconds: int) -> str:
    if offset_seconds == 0:
        return text

    def repl(match):
        secs = timestamp_to_seconds(match.group(1))
        total_secs = secs + offset_seconds
        h = total_secs // 3600
        m = (total_secs % 3600) // 60
        s = total_secs % 60
        if h > 0:
            return f"[{h:02d}:{m:02d}:{s:02d}]"
        else:
            return f"[{m:02d}:{s:02d}]"

    return re.sub(TIMESTAMP_PATTERN, repl, text)

def strip_overlap(text: str, boundary_seconds: int) -> str:
    kept_lines = []
    keep = False
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line:
            continue
        match = TIMESTAMP_PATTERN.search(line)
        if match:
            keep = timestamp_to_seconds(match.group(1)) >= boundary_seconds
        if keep:
            kept_lines.append(line)
    return "\n".join(kept_lines)

def transcribe_chunk_with_fallback(temp_chunk_path: str, display_name: str, prompt_text: str) -> str:
    global active_key_index
    client = get_client()
    if not client:
        raise RuntimeError("GEMINI_API_KEY가 설정되지 않았습니다.")

    last_error = None
    for key_idx in range(active_key_index, len(api_keys)):
        if key_idx != active_key_index:
            active_key_index = key_idx
            client = genai.Client(api_key=api_keys[key_idx])
            print(f"[AI-KEY] Switching to API key ({key_idx + 1}/{len(api_keys)})...")

        uploaded_file = None
        try:
            uploaded_file = client.files.upload(
                file=temp_chunk_path,
                config={"display_name": display_name}
            )

            response = client.models.generate_content(
                model="gemini-2.5-flash",
                contents=[uploaded_file, prompt_text]
            )

            candidate = response.candidates[0] if response.candidates else None
            finish_reason = candidate.finish_reason if candidate else None

            parts = []
            if candidate and candidate.content and candidate.content.parts:
                parts = [p.text for p in candidate.content.parts if p.text]
            transcript_text = "".join(parts).strip()

            if not transcript_text or finish_reason not in (None, genai.types.FinishReason.STOP):
                raise RuntimeError(f"응답이 비정상 종료되었습니다 (finish_reason={finish_reason})")

            return transcript_text
        except Exception as err:
            last_error = err
            if not is_quota_error(err):
                raise
        finally:
            if uploaded_file is not None:
                try:
                    client.files.delete(name=uploaded_file.name)
                except Exception:
                    pass

    raise last_error

def process_audio_file_to_board(board_id: int, audio_path: str, db_session_factory, progress_callback: Optional[Callable[[int], None]] = None):
    """오디오 파일을 청크 단위로 나누고 Gemini STT를 실행하여 Board에 저장하는 완전 자동화 파이프라인"""
    from server.models import Board, TranscriptSegment, BoardSummary

    if AudioSegment is None:
        raise RuntimeError("pydub 라이브러리가 필요합니다.")

    prompt = """
이 오디오 파일을 처음부터 끝까지 빠짐없이 텍스트로 받아쓰기(Transcription) 해줘.
작성할 때 아래 규칙을 엄격하게 지켜:
1. 문단이 바뀔 때마다 맨 앞에 [MM:SS] 타임스탬프를 적어줘.
2. 동일한 타임스탬프 연속 출력 금지, 시간은 증가해야 해.
3. 인사말이나 부연 설명 없이 타임스탬프와 본문 텍스트만 출력해.
"""
    db = db_session_factory()
    board = db.query(Board).filter_by(id=board_id).first()
    if not board:
        db.close()
        return

    try:
        board.status = "PROCESSING"
        board.progress_percent = 5
        db.commit()

        audio = AudioSegment.from_file(audio_path)
        total_duration_sec = len(audio) / 1000.0
        board.duration_seconds = total_duration_sec
        db.commit()

        chunk_starts = list(range(0, max(len(audio) - OVERLAP_MS, 1), CHUNK_STEP_MS))
        chunks = [audio[start:start + CHUNK_LENGTH_MS] for start in chunk_starts]
        total_chunks = len(chunks)

        full_transcript = ""
        for i, chunk in enumerate(chunks):
            temp_chunk_path = os.path.join(tempfile.gettempdir(), f"chunk_{uuid.uuid4().hex}.mp3")
            # STT 최적화 (16kHz 모노 32k)
            chunk.set_frame_rate(16000).set_channels(1).export(temp_chunk_path, format="mp3", bitrate="32k")

            try:
                transcript_text = transcribe_chunk_with_fallback(
                    temp_chunk_path, f"board_{board_id}_chunk_{i+1}", prompt
                )

                chunk_start_seconds = chunk_starts[i] // 1000
                adjusted = offset_timestamps(transcript_text, chunk_start_seconds)
                if i > 0:
                    adjusted = strip_overlap(adjusted, chunk_start_seconds)

                full_transcript += adjusted + "\n\n"

                # 진행률 업데이트
                progress = int(10 + (i + 1) / total_chunks * 70)
                board.progress_percent = min(85, progress)
                db.commit()
                if progress_callback:
                    progress_callback(board.progress_percent)

            finally:
                if os.path.exists(temp_chunk_path):
                    os.remove(temp_chunk_path)

        # 세그먼트 파싱 & 저장
        lines = full_transcript.strip().split('\n')
        db.query(TranscriptSegment).filter_by(board_id=board.id).delete()

        seq = 0
        last_ms = 0
        txt_lines = []
        for line in lines:
            line = line.strip()
            if not line:
                continue
            match = TIMESTAMP_PATTERN.search(line)
            if match:
                ts = match.group(1)
                t_ms = timestamp_to_seconds(ts) * 1000
                last_ms = t_ms
                clean = line.replace(f"[{ts}]", "").strip()
                seg = TranscriptSegment(
                    board_id=board.id,
                    start_time_ms=t_ms,
                    end_time_ms=t_ms + 10000,
                    timestamp_str=f"[{ts}]",
                    speaker="화자 1",
                    content=clean,
                    sequence=seq
                )
                txt_lines.append(f"[{ts}] {clean}")
            else:
                seg = TranscriptSegment(
                    board_id=board.id,
                    start_time_ms=last_ms,
                    end_time_ms=last_ms + 5000,
                    timestamp_str=ms_to_timestamp_str(last_ms),
                    speaker="화자 1",
                    content=line,
                    sequence=seq
                )
                txt_lines.append(line)
            db.add(seg)
            seq += 1

        # 텍스트 파일 저장
        if not board.txt_path:
            import os
            RESULT_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "강의 녹음 변환")
            folder_name = db.query(Folder).filter_by(id=board.folder_id).first().name
            if folder_name == "기본 폴더":
                target_dir = RESULT_DIR
            else:
                target_dir = os.path.join(RESULT_DIR, folder_name)
            os.makedirs(target_dir, exist_ok=True)
            board.txt_path = os.path.join(target_dir, f"{board.title}.txt")
            db.commit()

        if board.txt_path:
            with open(board.txt_path, "w", encoding="utf-8") as tf:
                tf.write("\n\n".join(txt_lines))

        # 후처리 1: AI 키워드 자동 추출
        board.progress_percent = 90
        db.commit()
        keywords = extract_keywords_ai(full_transcript)
        board.keywords_json = json.dumps(keywords, ensure_ascii=False)

        # 후처리 2: 기본 3단 요약 자동 생성
        board.progress_percent = 95
        db.commit()
        basic_summary = generate_summary_ai(full_transcript, "BASIC")
        summary_obj = BoardSummary(
            board_id=board.id,
            summary_type="BASIC",
            title="기본 요약",
            content=basic_summary
        )
        db.add(summary_obj)

        board.status = "COMPLETED"
        board.progress_percent = 100
        board.error_message = None
        board.updated_at = datetime.datetime.utcnow()
        db.commit()
        print(f"[AI-COMPLETE] Board #{board.id} ({board.title}) STT & Analysis done.")

    except Exception as e:
        print(f"[AI-ERROR] Board #{board.id} STT failed: {e}")
        board.status = "FAILED"
        board.error_message = str(e)
        db.commit()
    finally:
        db.close()

def extract_keywords_ai(transcript: str) -> List[str]:
    client = get_client()
    if not client or not transcript.strip():
        # 간단한 빈도 기반 fallback
        words = re.findall(r'[가-힣a-zA-Z0-9_]{2,}', transcript)
        stopwords = {'그래서', '우리가', '여기서', '이런', '저런', '어떤', '때문에', '그리고', '하지만', '이렇게', '그냥', '이제', '있는', '없는'}
        freq = {}
        for w in words:
            if w not in stopwords:
                freq[w] = freq.get(w, 0) + 1
        return [k for k, _ in sorted(freq.items(), key=lambda x: x[1], reverse=True)[:8]]

    prompt = f"""
아래 스크립트에서 가장 중요한 핵심 주제 및 개념 키워드 8개를 추출해줘.
반드시 JSON 문자열 배열 형식으로만 응답해. (예: ["객체지향", "메모리버퍼", "소켓통신", "클래스", "스트림", "스레드", "패킷", "인터페이스"])

스크립트:
{transcript[:5000]}
"""
    try:
        response = client.models.generate_content(
            model="gemini-2.5-flash",
            contents=prompt,
            config={"response_mime_type": "application/json"}
        )
        data = json.loads(response.text.strip())
        if isinstance(data, list):
            return [str(w) for w in data[:10]]
        elif isinstance(data, dict) and "keywords" in data:
            return [str(w) for w in data["keywords"][:10]]
        return []
    except Exception as e:
        print(f"[AI-KEYWORD-ERROR] {e}")
        return []

def generate_summary_ai(transcript: str, summary_type: str = "BASIC") -> str:
    client = get_client()
    if not client or not transcript.strip():
        return "Gemini API 키가 설정되지 않았거나 스크립트가 비어 있습니다."

    prompts = {
        "BASIC": """
당신은 최고의 강의/회의록 분석 전문가입니다.
제공된 스크립트를 바탕으로 직관적이고 깔끔한 마크다운(Markdown) 요약본을 작성하세요.

[출력 양식]:
### 📌 3줄 핵심 요약
1. 핵심 요약 1
2. 핵심 요약 2
3. 핵심 요약 3

### 📖 주요 내용 정리
- **[주제 1]**: 상세 설명 및 핵심 개념
- **[주제 2]**: 상세 설명 및 핵심 개념
- **[주제 3]**: 상세 설명 및 핵심 개념

### 💡 핵심 키포인트 & 결론
- 요약 및 기억해야 할 핵심 결론
""",
        "MEETING": """
당신은 프로페셔널한 비즈니스 회의록 작성자입니다.
제공된 대화/회의 스크립트를 분석하여 체계적인 회의록을 작성하세요.

[출력 양식]:
### 🏢 회의 개요 & 목적
- **주요 의제**: ...
- **진행 상황**: ...

### 💬 주요 논의 및 의사결정 사항
- **[안건 1]**: 논의 내용 및 결정된 사항
- **[안건 2]**: 논의 내용 및 결정된 사항

### 🎯 다음 단계 및 결정사항
- 실행 목표 및 후속 일정
""",
        "ACTION_ITEM": """
제공된 스크립트에서 결정된 실행 과제(Action Items)와 할 일 목록(To-Do)을 명확하게 추출하세요.

[출력 양식]:
### 📋 실행 과제 (Action Items)
- [ ] **과제 1**: 구체적 실행 내용 및 목표
- [ ] **과제 2**: 구체적 실행 내용 및 목표
- [ ] **과제 3**: 구체적 실행 내용 및 목표

### ⚠️ 주의사항 및 고려할 제약사항
- 실행 시 유의할 점
""",
        "QUIZ": """
제공된 강의 스크립트 내용을 복습할 수 있는 핵심 퀴즈 3문제와 정답 및 상세 해설을 작성하세요.

[출력 양식]:
### ✍️ 강의 복습 퀴즈
1. **문제 1**: ...
2. **문제 2**: ...
3. **문제 3**: ...

---
### 🔍 정답 및 상세 해설
- **1번 정답**: ... (해설: ...)
- **2번 정답**: ... (해설: ...)
- **3번 정답**: ... (해설: ...)
""",
        "SLIDE": """
제공된 강의/발표 스크립트를 파워포인트/슬라이드 발표 자료 개요 형태로 작성하세요.

[출력 양식]:
### 📊 슬라이드 발표 개요

#### Slide 1: 표지 & 도입
- 제목: ...
- 핵심 메시지: ...

#### Slide 2: 핵심 개념 및 원리
- 주요 포인트: ...
- 상세 설명: ...

#### Slide 3: 적용 사례 및 결론
- 결론: ...
"""
    }

    selected_prompt = prompts.get(summary_type, prompts["BASIC"])
    full_prompt = f"{selected_prompt}\n\n[스크립트 원본]:\n{transcript[:18000]}"

    try:
        response = client.models.generate_content(
            model="gemini-2.5-flash",
            contents=full_prompt
        )
        return response.text or "요약을 생성하지 못했습니다."
    except Exception as e:
        return f"요약 생성 중 오류가 발생했습니다: {str(e)}"

def stream_board_chat(transcript: str, chat_history: List[Dict[str, str]], user_message: str):
    client = get_client()
    if not client:
        yield "data: " + json.dumps({"text": "⚠️ Gemini API 키가 설정되지 않았습니다. .env 파일을 확인해주세요."}) + "\n\n"
        return

    system_instruction = f"""
당신은 이 녹음/강의 보드의 전담 AI 비서 '다글로 챗봇'입니다.
아래 제공된 [전체 스크립트] 내용을 바탕으로 사용자의 질문에 정확하고 친절하게 답변하세요.

규칙:
1. 스크립트에 언급된 내용을 근거로 명확하게 답변하세요.
2. 관련된 타임스탬프([MM:SS])가 있다면 함께 언급하여 사용자가 오디오를 찾아 들을 수 있게 도와주세요.
3. 스크립트에 없는 내용은 지어내지 말고 솔직하게 "본 녹음 내용에는 해당 내용이 언급되지 않았습니다"라고 답변하세요.
4. 마크다운 문법(굵게, 불릿 기호 등)을 활용해 읽기 쉽게 답변하세요.

[전체 스크립트]:
{transcript[:30000]}
"""

    contents = []
    for c in chat_history[-5:]:
        contents.append({"role": "user" if c["role"] == "user" else "model", "parts": [{"text": c["message"]}]})

    contents.append({"role": "user", "parts": [{"text": f"{system_instruction}\n\n[사용자 질문]:\n{user_message}"}]})

    try:
        stream = client.models.generate_content_stream(
            model="gemini-2.5-flash",
            contents=contents
        )
        for chunk in stream:
            if chunk.text:
                yield f"data: {json.dumps({'text': chunk.text})}\n\n"
    except Exception as e:
        yield f"data: {json.dumps({'error': str(e)})}\n\n"
