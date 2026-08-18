import os
import re
import shutil
import tempfile
import uuid
import urllib.parse
from google import genai
from dotenv import load_dotenv

try:
    from pydub import AudioSegment
except ImportError:
    print("❌ 오류: pydub 라이브러리가 설치되어 있지 않습니다.")
    exit()

# --- [API 키 설정] ---
load_dotenv()
api_key = os.getenv("GEMINI_API_KEY")

if not api_key:
    print("❌ 오류: .env 파일에서 GEMINI_API_KEY를 찾을 수 없습니다.")
    exit()

client = genai.Client(api_key=api_key)
# ---------------------

path = os.path.dirname(os.path.abspath(__file__))
audio_folder = os.path.join(path, "녹음파일원본")
result_folder = os.path.join(path, "강의 녹음 변환")

for folder in [audio_folder, result_folder]:
    if not os.path.exists(folder):
        os.makedirs(folder)

valid_extensions = ('.mp3', '.m4a', '.wav', '.mp4')
audio_files = []

for root_dir, _, files in os.walk(audio_folder):
    for f in files:
        if f.lower().endswith(valid_extensions):
            full_path = os.path.join(root_dir, f)
            rel_path = os.path.relpath(full_path, audio_folder)
            audio_files.append(rel_path)

if not audio_files:
    print(f"📁 [{audio_folder}] 폴더가 준비되었습니다!")
    exit()

def timestamp_to_seconds(ts_str):
    parts = list(map(int, ts_str.split(':')))
    if len(parts) == 2:
        return parts[0] * 60 + parts[1]
    elif len(parts) == 3:
        return parts[0] * 3600 + parts[1] * 60 + parts[2]
    return 0

def offset_timestamps(text, offset_seconds):
    if offset_seconds == 0:
        return text
        
    pattern = r'\[(\d{2}:\d{2}(?::\d{2})?)\]'
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
            
    return re.sub(pattern, repl, text)

CHUNK_LENGTH_MS = 60 * 60 * 1000
pattern = r'\[(\d{2}:\d{2}(?::\d{2})?)\]'

for audio_file in audio_files:
    file_path = os.path.join(audio_folder, audio_file)
    rel_dir = os.path.dirname(audio_file)
    pure_file_name = os.path.basename(audio_file)
    file_base_name = os.path.splitext(pure_file_name)[0]
    ext = os.path.splitext(pure_file_name)[1].lower()

    current_result_dir = os.path.join(result_folder, rel_dir)
    os.makedirs(current_result_dir, exist_ok=True)

    txt_output_path = os.path.join(current_result_dir, f"{file_base_name}.txt")
    html_output_path = os.path.join(current_result_dir, f"{file_base_name}_강의스크립트.html")
    
    # 1. 최종 완성본이 이미 있으면 패스
    if os.path.exists(txt_output_path) and os.path.exists(html_output_path):
        print(f"⏭️ [{pure_file_name}] 완료된 파일입니다. 건너뜁니다.")
        continue

    print(f"\n▶️ [{audio_file}] 작업 시작...")
    
    try:
        audio = AudioSegment.from_file(file_path)
        chunks = [audio[i:i + CHUNK_LENGTH_MS] for i in range(0, len(audio), CHUNK_LENGTH_MS)]
        print(f"   - 총 {len(chunks)}개의 조각으로 나누어 처리를 시작합니다.")
        
        full_transcript = ""
        is_all_success = True # 🌟 모든 조각이 성공했는지 체크하는 변수

        for i, chunk in enumerate(chunks):
            # 🌟 조각별 임시 저장 파일 경로 (예: 강의1_chunk_0.txt)
            chunk_cache_path = os.path.join(current_result_dir, f"{file_base_name}_chunk_{i}.txt")
            
            # 🌟 이어하기 핵심: 이미 처리된 조각 텍스트가 있다면 API 호출 없이 바로 읽어옴
            if os.path.exists(chunk_cache_path):
                print(f"   - [{i+1}/{len(chunks)}] 번째 조각은 이미 완료되어 불러옵니다 (API 절약).")
                with open(chunk_cache_path, "r", encoding="utf-8") as f:
                    full_transcript += f.read() + "\n\n"
                continue

            print(f"   - [{i+1}/{len(chunks)}] 번째 조각 API 변환 중...")
            
            temp_chunk_path = os.path.join(tempfile.gettempdir(), f"chunk_{uuid.uuid4().hex}.mp3")
            chunk.export(temp_chunk_path, format="mp3")
            
            try:
                uploaded_file = client.files.upload(
                    file=temp_chunk_path,
                    config={"display_name": f"lecture_chunk_{i+1}"}
                )

                prompt = """
                이 오디오 파일을 처음부터 끝까지 빠짐없이 텍스트로 받아쓰기(Transcription) 해줘.
                작성할 때 아래 규칙을 엄격하게 지켜:
                1. 문단이 바뀔 때마다 맨 앞에 [MM:SS] 타임스탬프를 적어줘.
                2. 동일한 타임스탬프 연속 출력 금지, 시간은 증가해야 해.
                3. 인사말 없이 타임스탬프와 텍스트만 출력해.
                """

                response = client.models.generate_content(
                    model="gemini-2.5-flash",
                    contents=[uploaded_file, prompt]
                )
                
                offset_seconds = i * (CHUNK_LENGTH_MS // 1000)
                adjusted_text = offset_timestamps(response.text, offset_seconds)
                
                # 🌟 API 변환 성공 시: 해당 조각만 따로 텍스트로 저장 (세이브 포인트)
                with open(chunk_cache_path, "w", encoding="utf-8") as f:
                    f.write(adjusted_text)# type: ignore #VSCODE에서 static type checking을 켜면 오류가 나서 ignore 처리함
                    
                full_transcript += adjusted_text + "\n\n"# type: ignore #VSCODE에서 static type checking을 켜면 오류가 나서 ignore 처리함
                
            except Exception as api_err:
                print(f"   ❌ [{i+1}/{len(chunks)}] 번째 조각에서 API 한도 초과 등으로 실패했습니다.")
                print(f"   ⚠️ 에러 내용: {api_err}")
                print(f"   ⏸️ 내일 다시 실행하면 실패한 [{i+1}] 번째 조각부터 이어하기가 진행됩니다.")
                is_all_success = False
                break # 더 이상 진행하지 않고 중단
                
            finally:
                if os.path.exists(temp_chunk_path):
                    os.remove(temp_chunk_path)

        # ==========================================
        # 🌟 모든 조각이 100% 성공했을 때만 최종 HTML/TXT 생성
        # ==========================================
        if is_all_success:
            with open(txt_output_path, "w", encoding="utf-8") as f:
                f.write(full_transcript.strip())
            
            html_lines = []
            last_seconds = 0
            for line in full_transcript.strip().split('\n'):
                line = line.strip()
                if not line: continue

                match = re.search(pattern, line)
                if match:
                    ts = match.group(1)
                    last_seconds = timestamp_to_seconds(ts)
                    text_only = line.replace(f"[{ts}]", "").strip()
                    html_lines.append(
                        f'<div class="script-block" data-ts="[{ts}]" data-seconds="{last_seconds}">'
                        f'<span class="timestamp" onclick="playAt({last_seconds})" title="오디오 재생">[{ts}] 🔊</span> '
                        f'<p class="text-content" contenteditable="true">{text_only}</p></div>'
                    )
                else:
                    html_lines.append(
                        f'<div class="script-block" data-seconds="{last_seconds}">'
                        f'<p class="text-content" contenteditable="true">{line}</p></div>'
                    )

            content_html = "\n".join(html_lines)
            
            rel_audio_path = os.path.relpath(file_path, current_result_dir)
            safe_parts = [urllib.parse.quote(p) for p in rel_audio_path.replace('\\', '/').split('/')]
            audio_src = "/".join(safe_parts)
            mime_type = "audio/mpeg" if ext == ".mp3" else "audio/wav" if ext == ".wav" else "audio/mp4"

            # (HTML 템플릿 부분은 이전과 완전히 동일하므로 간략화하여 결합)
            html_template = f"""<!DOCTYPE html>
<html lang="ko">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>🎬 강의 스크립트 - {file_base_name}</title>
    <style>
        body {{ font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; line-height: 1.8; margin: 0; padding: 0; background-color: #f8f9fa; color: #333; display: flex; flex-direction: column; height: 100vh; }}
        .main-container {{ flex: 1; overflow-y: auto; padding: 40px 20px; max-width: 800px; margin: 0 auto; width: 100%; box-sizing: border-box; padding-bottom: 150px; }}
        .script-block {{ display: flex; align-items: flex-start; gap: 12px; margin-bottom: 20px; border-bottom: 1px dashed #e0e0e0; padding-bottom: 15px; padding-left: 10px; border-left: 4px solid transparent; transition: all 0.3s ease; }}
        .script-block.active {{ background-color: #eaf3ff; border-left: 4px solid #4a90e2; border-radius: 6px; padding-right: 10px; }}
        .timestamp {{ flex-shrink: 0; display: inline-block; color: #fff; background-color: #4a90e2; font-size: 13px; font-weight: bold; margin-top: 4px; padding: 4px 10px; border-radius: 6px; cursor: pointer; transition: background 0.2s; user-select: none; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }}
        .timestamp:hover {{ background-color: #357abd; }}
        .text-content {{ flex-grow: 1; font-size: 16px; color: #222; margin: 0; padding: 8px 12px; border: 2px solid transparent; border-radius: 6px; transition: all 0.2s; outline: none; background-color: transparent; }}
        .text-content:hover {{ background-color: #fff; border-color: #d1d5db; }}
        .text-content:focus {{ background-color: #fff; border-color: #4a90e2; box-shadow: 0 0 0 3px rgba(74, 144, 226, 0.2); }}
        .bottom-player-container {{ position: fixed; bottom: 0; left: 0; right: 0; background: white; padding: 15px 40px; border-top: 1px solid #e1e4e8; box-shadow: 0 -4px 20px rgba(0,0,0,0.06); display: flex; flex-direction: column; justify-content: center; align-items: center; z-index: 1000; }}
        .shortcut-info {{ font-size: 13px; color: #666; margin-bottom: 8px; font-weight: bold; }}
        audio {{ width: 100%; max-width: 800px; }}
        .save-btn {{ position: fixed; bottom: 110px; right: 30px; background-color: #28a745; color: white; border: none; padding: 12px 24px; border-radius: 50px; font-size: 15px; font-weight: bold; cursor: pointer; box-shadow: 0 4px 12px rgba(0,0,0,0.2); z-index: 1001; transition: transform 0.2s, background-color 0.2s; }}
        .save-btn:hover {{ transform: translateY(-2px); background-color: #218838; }}
        .save-btn:active {{ transform: translateY(0); }}
        #toast {{ visibility: hidden; min-width: 200px; background-color: #333; color: #fff; text-align: center; border-radius: 30px; padding: 12px 20px; position: fixed; z-index: 1002; left: 50%; bottom: 30px; font-size: 15px; transform: translateX(-50%); opacity: 0; box-shadow: 0 4px 12px rgba(0,0,0,0.2); transition: opacity 0.3s, bottom 0.3s, visibility 0.3s; }}
        #toast.show {{ visibility: visible; opacity: 1; bottom: 130px; }}
    </style>
</head>
<body>
<div class="main-container">
    <h2 style="font-size: 22px; margin-bottom: 30px; border-bottom: 2px solid #333; padding-bottom: 10px;">📝 강의 스크립트</h2>
    <div class="transcript-box">
        {content_html}
    </div>
</div>
<button class="save-btn" onclick="exportToFile()">💾 .txt 파일로 다운로드</button>
<div id="toast">저장되었습니다</div>
<div class="bottom-player-container">
    <div class="shortcut-info">⌨️ 단축키 : [F2] 재생/일시정지 | [F1] 5초 이전 | [F3] 5초 이후 | [Ctrl+S] 임시 저장</div>
    <audio id="audioPlayer" controls>
        <source src="{audio_src}" type="{mime_type}">
        브라우저가 오디오 태그를 지원하지 않습니다.
    </audio>
</div>
<script>
    const STORAGE_KEY = "transcript_v2_{file_base_name}";
    let isModified = false;
    let isUserEditing = false; 

    function playAt(seconds) {{
        var player = document.getElementById('audioPlayer');
        player.currentTime = seconds;
        player.play();
    }}
    function showToast() {{
        var toast = document.getElementById("toast");
        toast.classList.add("show");
        setTimeout(function() {{ toast.classList.remove("show"); }}, 2000);
    }}
    function saveToLocal() {{
        const containerClone = document.querySelector('.transcript-box').cloneNode(true);
        containerClone.querySelectorAll('.script-block.active').forEach(el => el.classList.remove('active'));
        localStorage.setItem(STORAGE_KEY, containerClone.innerHTML);
        showToast();
    }}
    function exportToFile() {{
        let newContent = "";
        document.querySelectorAll('.script-block').forEach(block => {{
            const ts = block.getAttribute('data-ts');
            const textElement = block.querySelector('.text-content');
            if (textElement) {{
                const text = textElement.innerText.trim();
                newContent += (ts ? ts + " " : "") + text + "\\n\\n";
            }}
        }});
        const blob = new Blob([newContent], {{type: "text/plain;charset=utf-8"}});
        const url = URL.createObjectURL(blob);
        const link = document.createElement("a");
        link.href = url;
        link.download = "{file_base_name}_수정본.txt";
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        URL.revokeObjectURL(url);
    }}
    window.addEventListener('DOMContentLoaded', () => {{
        const savedData = localStorage.getItem(STORAGE_KEY);
        if (savedData) document.querySelector('.transcript-box').innerHTML = savedData;
    }});
    document.querySelector('.transcript-box').addEventListener('focusin', e => {{
        if (e.target.classList.contains('text-content')) isUserEditing = true;
    }});
    document.querySelector('.transcript-box').addEventListener('input', e => {{
        if (e.target.classList.contains('text-content')) isModified = true;
    }});
    document.querySelector('.transcript-box').addEventListener('focusout', e => {{
        if (e.target.classList.contains('text-content')) {{
            isUserEditing = false; 
            if (isModified) {{ saveToLocal(); isModified = false; }}
        }}
    }});
    const audio = document.getElementById('audioPlayer');
    let currentActiveBlock = null;
    audio.addEventListener('timeupdate', () => {{
        const currentTime = audio.currentTime;
        const blocks = document.querySelectorAll('.script-block[data-seconds]');
        let activeBlock = null;
        for (let i = 0; i < blocks.length; i++) {{
            if (parseFloat(blocks[i].dataset.seconds) <= currentTime) activeBlock = blocks[i];
        }}
        if (activeBlock && activeBlock !== currentActiveBlock) {{
            if (currentActiveBlock) currentActiveBlock.classList.remove('active');
            activeBlock.classList.add('active');
            currentActiveBlock = activeBlock;
            if (!isUserEditing) activeBlock.scrollIntoView({{ behavior: 'smooth', block: 'center' }});
        }}
    }});
    document.addEventListener('keydown', e => {{
        var player = document.getElementById('audioPlayer');
        if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {{
            e.preventDefault(); saveToLocal(); isModified = false; 
        }}
        if (e.key === 'F2') {{ e.preventDefault(); player.paused ? player.play() : player.pause(); }}
        if (e.key === 'F1') {{ e.preventDefault(); player.currentTime = Math.max(0, player.currentTime - 5); }}
        if (e.key === 'F3') {{ e.preventDefault(); player.currentTime = Math.min(player.duration || 0, player.currentTime + 5); }}
    }});
</script>
</body>
</html>"""
            with open(html_output_path, "w", encoding="utf-8") as f:
                f.write(html_template)
            
            # (선택 사항) 최종 완성되었으므로 지저분한 임시 chunk.txt 파일들 삭제
            for i in range(len(chunks)):
                chunk_cache_path = os.path.join(current_result_dir, f"{file_base_name}_chunk_{i}.txt")
                if os.path.exists(chunk_cache_path):
                    os.remove(chunk_cache_path)
                    
            print(f"   ✔️ 모든 조각 결합 완료! 인터랙티브 문서 저장 완료: {file_base_name}_강의스크립트.html")

    except Exception as e:
        print(f"   ❌ 오류 발생: {e}")

print("\n✨ 프로그램 실행이 종료되었습니다.")