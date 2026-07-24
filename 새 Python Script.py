import os
import re
import shutil
import tempfile
import uuid
import urllib.parse
from google import genai
from dotenv import load_dotenv  # 추가된 모듈

# --- [API 키 설정] ---
# .env 파일에서 환경 변수 불러오기
load_dotenv()
api_key = os.getenv("GEMINI_API_KEY")

if not api_key:
    print("❌ 오류: .env 파일에서 GEMINI_API_KEY를 찾을 수 없습니다.")
    print("스크립트와 같은 폴더에 .env 파일을 만들고 API 키를 입력해주세요.")
    input("종료하려면 엔터를 누르세요...")
    exit()

# 1. API 클라이언트 초기화 (환경 변수 적용)
client = genai.Client(api_key=api_key)
# ---------------------

# 2. 바탕화면 '강의 녹음 변환' 폴더 경로 자동 설정
path = os.path.dirname(os.path.abspath(__file__))
target_folder = os.path.join(path, "강의 녹음 변환")

if not os.path.exists(target_folder):
    os.makedirs(target_folder)
    print(f"📁 바탕화면에 [{target_folder}] 폴더를 방금 생성했습니다!")
    print("이 폴더 안에 변환할 오디오 파일(.m4a, .mp3 등)을 넣고 프로그램을 다시 실행해주세요.")
    input("종료하려면 엔터를 누르세요...")
    exit()

valid_extensions = ('.mp3', '.m4a', '.wav', '.mp4')
audio_files = [f for f in os.listdir(target_folder) if f.lower().endswith(valid_extensions)]

if not audio_files:
    print(f"❌ 폴더 안에 처리할 오디오 파일이 없습니다.\n경로: {target_folder}")
    input("종료하려면 엔터를 누르세요...")
    exit()

print(f"🔍 총 {len(audio_files)}개의 오디오 파일을 발견했습니다. 일괄 변환을 시작합니다!\n" + "="*50)

def timestamp_to_seconds(ts_str):
    parts = list(map(int, ts_str.split(':')))
    if len(parts) == 2:
        return parts[0] * 60 + parts[1]
    elif len(parts) == 3:
        return parts[0] * 3600 + parts[1] * 60 + parts[2]
    return 0

def move_files():
    move_target_folder = os.path.join(target_folder, file_base_name)
    if not os.path.exists(move_target_folder):
        os.makedirs(move_target_folder)
        print(f"  📂 폴더 생성 완료: {move_target_folder}/")

    files_to_move = [file_path, html_output_path, txt_output_path]
    for target_file in files_to_move:
        if os.path.exists(target_file):
            try:
                shutil.move(target_file, move_target_folder)
                print(f"  🚚 이동 완료: {target_file} -> {move_target_folder}/")
            except Exception as move_error:
                print(f"  ⚠️ 파일 이동 중 오류 발생 ({target_file}): {move_error}")

pattern = r'\[(\d{2}:\d{2}(?::\d{2})?)\]'

# 3. 발견된 오디오 파일을 하나씩 순회하며 처리
for audio_file in audio_files:
    file_path = os.path.join(target_folder, audio_file)
    file_base_name = os.path.splitext(audio_file)[0]

    txt_output_path = os.path.join(target_folder, f"{file_base_name}.txt")
    html_output_path = os.path.join(target_folder, f"{file_base_name}_강의스크립트.html")
    
    if os.path.exists(txt_output_path) and os.path.exists(html_output_path):
        print(f"⏭️ [{audio_file}] 파일은 이미 txt, html 파일이 존재하여 건너뜁니다.")
        move_files()
        continue

    print(f"\n▶️ [{audio_file}] 작업 시작...")

    upload_source_path = file_path
    temp_upload_path = None
    try:
        file_path.encode('ascii')
    except UnicodeEncodeError:
        ext = os.path.splitext(file_path)[1]
        temp_upload_path = os.path.join(tempfile.gettempdir(), f"upload_{uuid.uuid4().hex}{ext}")
        shutil.copy2(file_path, temp_upload_path)
        upload_source_path = temp_upload_path
        print("   - 한글 파일명 감지: 임시 ASCII 파일명으로 업로드합니다.")

    try:
        print("   - Google AI Studio 서버로 업로드 중...")
        uploaded_file = client.files.upload(
            file=upload_source_path,
            config={"display_name": "lecture_audio"}
        )

        print("   - 제미나이 받아쓰기 진행 중 (시간이 소요됩니다)...")
        prompt = """
        이 오디오 파일을 처음부터 끝까지 빠짐없이 텍스트로 받아쓰기(Transcription) 해줘.
        작성할 때 아래의 규칙을 아주 엄격하게 지켜야 해:
        1. 문단이 바뀌거나 내용이 전환될 때마다 반드시 문장 맨 앞에 [MM:SS] 형식으로 정확한 타임스탬프를 적어줘.
        2. 절대 동일한 타임스탬프(예: [00:00])를 연속해서 여러 번 출력하지 마! 시간이 흐름에 따라 시간이 반드시 증가해야 해.
        3. 가급적 30초~40초 분량마다 문단을 나누고 새로운 타임스탬프를 갱신해서 찍어줘.
        4. 인사말이나 다른 설명 없이 오직 타임스탬프와 받아쓰기 내용만 출력해.
        """

        response = client.models.generate_content(
            model="gemini-2.5-flash",
            contents=[uploaded_file, prompt]
        )
        transcript_text = response.text

        with open(txt_output_path, "w", encoding="utf-8") as f:
            f.write(transcript_text)
        print(f"   ✔️ 텍스트 파일 저장 완료: {file_base_name}.txt")

        # ==========================================
        # 5. 수정된 인터랙티브 HTML 문서 생성 (수정 기능 추가)
        # ==========================================
        html_lines = []
        last_seconds = 0

        for line in transcript_text.split('\n'):
            line = line.strip()
            if not line:
                continue

            match = re.search(pattern, line)
            if match:
                ts = match.group(1)
                last_seconds = timestamp_to_seconds(ts)
                # 타임스탬프와 순수 텍스트 분리
                text_only = line.replace(f"[{ts}]", "").strip()
                
                # HTML 구성: 타임스탬프(재생 버튼) + 텍스트(수정 가능 영역)
                html_lines.append(
                    f'<div class="script-block" data-ts="[{ts}]">'
                    f'<span class="timestamp" onclick="playAt({last_seconds})" title="클릭하여 오디오 재생">[{ts}] 🔊</span> '
                    f'<p class="text-content" contenteditable="true" title="클릭하여 자막 수정">{text_only}</p>'
                    f'</div>'
                )
            else:
                html_lines.append(
                    f'<div class="script-block">'
                    f'<p class="text-content" contenteditable="true" title="클릭하여 자막 수정">{line}</p>'
                    f'</div>'
                )

        content_html = "\n".join(html_lines)
        audio_src = urllib.parse.quote(audio_file)

        html_template = f"""<!DOCTYPE html>
<html lang="ko">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>🎬 강의 스크립트 - {file_base_name}</title>
    <style>
        body {{ font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; line-height: 1.8; margin: 0; padding: 0; background-color: #f8f9fa; color: #333; display: flex; flex-direction: column; height: 100vh; }}
        .main-container {{ flex: 1; overflow-y: auto; padding: 40px 20px; max-width: 800px; margin: 0 auto; width: 100%; box-sizing: border-box; padding-bottom: 120px; }}
        .script-block {{ display: flex; align-items: flex-start; gap: 12px; margin-bottom: 20px; border-bottom: 1px dashed #e0e0e0; padding-bottom: 15px; }}
        
        /* 타임스탬프 (재생 버튼 역할) 디자인 */
        .timestamp {{ flex-shrink: 0; display: inline-block; color: #fff; background-color: #4a90e2; font-size: 13px; font-weight: bold; margin-top: 4px; padding: 4px 10px; border-radius: 6px; cursor: pointer; transition: background 0.2s; user-select: none; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }}
        .timestamp:hover {{ background-color: #357abd; }}
        
        /* 텍스트 (수정 영역) 디자인 */
        .text-content {{ flex-grow: 1; font-size: 16px; color: #222; margin: 0; padding: 8px 12px; border: 2px solid transparent; border-radius: 6px; transition: all 0.2s; outline: none; background-color: transparent; }}
        .text-content:hover {{ background-color: #fff; border-color: #d1d5db; }}
        .text-content:focus {{ background-color: #fff; border-color: #4a90e2; box-shadow: 0 0 0 3px rgba(74, 144, 226, 0.2); }}
        
        .bottom-player-container {{ position: fixed; bottom: 0; left: 0; right: 0; background: white; padding: 15px 40px; border-top: 1px solid #e1e4e8; box-shadow: 0 -4px 20px rgba(0,0,0,0.06); display: flex; justify-content: center; align-items: center; z-index: 1000; }}
        audio {{ width: 100%; max-width: 800px; }}
        
        /* 저장 버튼 디자인 */
        .save-btn {{ position: fixed; bottom: 90px; right: 30px; background-color: #28a745; color: white; border: none; padding: 12px 24px; border-radius: 50px; font-size: 15px; font-weight: bold; cursor: pointer; box-shadow: 0 4px 12px rgba(0,0,0,0.2); z-index: 1001; transition: transform 0.2s, background-color 0.2s; }}
        .save-btn:hover {{ transform: translateY(-2px); background-color: #218838; }}
        .save-btn:active {{ transform: translateY(0); }}
    </style>
</head>
<body>
<div class="main-container">
    <h2 style="font-size: 22px; margin-bottom: 30px; border-bottom: 2px solid #333; padding-bottom: 10px;">📝 강의 스크립트 (클릭하여 텍스트 수정 가능)</h2>
    <div class="transcript-box">
        {content_html}
    </div>
</div>

<button class="save-btn" onclick="saveTranscript()">💾 수정된 스크립트 저장</button>

<div class="bottom-player-container">
    <audio id="audioPlayer" controls>
        <source src="{audio_src}" type="audio/mp4">
        브라우저가 오디오 태그를 지원하지 않습니다.
    </audio>
</div>

<script>
    // 오디오 재생 함수
    function playAt(seconds) {{
        var player = document.getElementById('audioPlayer');
        player.currentTime = seconds;
        player.play();
    }}

    // 수정한 텍스트를 다시 txt 파일로 다운로드하는 함수
    function saveTranscript() {{
        let newContent = "";
        const blocks = document.querySelectorAll('.script-block');
        
        blocks.forEach(block => {{
            const ts = block.getAttribute('data-ts'); // 기존 타임스탬프
            const textElement = block.querySelector('.text-content');
            
            if (textElement) {{
                const text = textElement.innerText.trim();
                if (ts) {{
                    newContent += ts + " " + text + "\\n\\n";
                }} else {{
                    newContent += text + "\\n\\n";
                }}
            }}
        }});

        // 파일 생성 및 다운로드 트리거
        const blob = new Blob([newContent], {{type: "text/plain;charset=utf-8"}});
        const url = URL.createObjectURL(blob);
        const link = document.createElement("a");
        link.href = url;
        link.download = "{file_base_name}_수정본.txt"; // 다운로드될 파일명
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        URL.revokeObjectURL(url);
        
        alert("수정된 스크립트가 다운로드 폴더에 저장되었습니다!");
    }}
</script>
</body>
</html>
"""
        with open(html_output_path, "w", encoding="utf-8") as f:
            f.write(html_template)
        print(f"   ✔️ 인터랙티브 문서 저장 완료: {file_base_name}_강의스크립트.html")

        move_files()

    except Exception as e:
        print(f"   ❌ 오류 발생: {e}")
    finally:
        if temp_upload_path and os.path.exists(temp_upload_path):
            try:
                os.remove(temp_upload_path)
            except Exception:
                pass

print("\n✨ 모든 작업이 완료되었습니다!")