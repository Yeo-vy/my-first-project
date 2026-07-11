import os
import re
import shutil #파일들을 생성된 폴더 안으로 일괄 이동
from google import genai

# 1. API 클라이언트 초기화
client = genai.Client(api_key="AQ.Ab8RN6Kqrr-di4lzHs5OIexGf4uGDfUw9sypL-yNvk28AxPx3A")

# 2. 바탕화면 '강의 녹음 변환' 폴더 경로 자동 설정 (윈도우/맥 모두 호환)
path = os.path.dirname(os.path.abspath(__file__))   #os.path.join(os.path.expanduser("~"), "Desktop") // C:\Users\pij19\Desktop
target_folder = os.path.join(path, "강의 녹음 변환")

# 폴더가 없다면 자동으로 생성하고 안내 후 종료
if not os.path.exists(target_folder):
    os.makedirs(target_folder)
    print(f"📁 바탕화면에 [{target_folder}] 폴더를 방금 생성했습니다!")
    print("이 폴더 안에 변환할 오디오 파일(.m4a, .mp3 등)을 넣고 프로그램을 다시 실행해주세요.")
    input("종료하려면 엔터를 누르세요...")
    exit()

# 폴더 안의 오디오 파일 찾기
valid_extensions = ('.mp3', '.m4a', '.wav', '.mp4')
audio_files = [f for f in os.listdir(target_folder) if f.lower().endswith(valid_extensions)]

if not audio_files:
    print(f"❌ 폴더 안에 처리할 오디오 파일이 없습니다.\n경로: {target_folder}")
    input("종료하려면 엔터를 누르세요...")
    exit()

print(f"🔍 총 {len(audio_files)}개의 오디오 파일을 발견했습니다. 일괄 변환을 시작합니다!\n" + "="*50)





# 타임스탬프 변환 함수
def timestamp_to_seconds(ts_str):
    parts = list(map(int, ts_str.split(':')))
    if len(parts) == 2:
        return parts[0] * 60 + parts[1]
    elif len(parts) == 3:
        return parts[0] * 3600 + parts[1] * 60 + parts[2]
    return 0





def move_files():
    # 1. 원본 파일 이름을 기준으로 폴더명 설정 (확장자 제외한 순수 이름)
    # 예: "2026. 04. 06. Fuzz_logic.mp4" -> "2026. 04. 06. Fuzz_logic" 폴더 생성
    move_target_folder = os.path.join(target_folder, file_base_name)

    # 2. 해당 이름을 가진 폴더가 없다면 새로 생성
    if not os.path.exists(move_target_folder):
        os.makedirs(move_target_folder)
        print(f"  📂 폴더 생성 완료: {move_target_folder}/")

    # 3. 새 폴더로 이동시킬 파일 목록 정의
    # (TXT 파일도 함께 생성하도록 세팅하셨다면 아래 목록에 그대로 포함됩니다)
    files_to_move = [
        file_path,                 # 원본 영상/오디오 파일 (.mp4, .m4a 등)
        html_output_path,              # 생성된 HTML 뷰어 파일 (_viewer.html)
        txt_output_path             # 생성된 TXT 파일 (있을 경우에만 이동)
    ]

    # 4. 파일들을 생성된 폴더 안으로 일괄 이동
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
    
    # 이미 텍스트 파일과 HTML 파일이 존재하면 건너뛰기 (중복 변환 방지)
    if os.path.exists(txt_output_path) and os.path.exists(html_output_path):
        print(f"⏭️ [{audio_file}] 파일은 이미 txt, html 파일이 존재하여 건너뜁니다.")
        move_files()
        continue

    print(f"\n▶️ [{audio_file}] 작업 시작...")
    
    try:
        # 파일 업로드 및 제미나이 처리
        print("   - Google AI Studio 서버로 업로드 중...")
        uploaded_file = client.files.upload(file=file_path)
        
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

        # 4. 순수 텍스트 파일(.txt) 저장
        with open(txt_output_path, "w", encoding="utf-8") as f:
            f.write(transcript_text)
        print(f"   ✔️ 텍스트 파일 저장 완료: {file_base_name}.txt")

        # 5. 인터랙티브 HTML 문서 생성 및 저장
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
                replaced_line = line.replace(f"[{ts}]", f'<span class="timestamp">[{ts}]</span>')
                html_lines.append(f'<div class="script-block text-content" onclick="playAt({last_seconds})">{replaced_line}</div>')
            else:
                html_lines.append(f'<div class="script-block text-content" onclick="playAt({last_seconds})">{line}</div>')
        
        content_html = "\n".join(html_lines)
        
        html_template = f"""<!DOCTYPE html>
<html lang="ko">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>🎬 강의 스크립트 - {file_base_name}</title>
    <style>
        body {{ font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; line-height: 1.8; margin: 0; padding: 0; background-color: #fafafa; color: #333; display: flex; flex-direction: column; height: 100vh; }}
        .main-container {{ flex: 1; overflow-y: auto; padding: 40px 20px; max-width: 800px; margin: 0 auto; width: 100%; box-sizing: border-box; padding-bottom: 120px; }}
        .script-block {{ margin-bottom: 35px; border-bottom: 1px solid #f0f0f0; padding-bottom: 20px; }}
        .timestamp {{ display: inline-block; color: #888; font-size: 13px; font-weight: 500; margin-bottom: 8px; cursor: pointer; transition: color 0.2s; }}
        .timestamp:hover {{ color: #0056b3; text-decoration: underline; }}
        .text-content {{ font-size: 16px; color: #222; margin: 0; cursor: pointer; padding: 4px 8px; border-radius: 4px; transition: background-color 0.2s; }}
        .text-content:hover {{ background-color: #edf2f9; color: #0056b3; }}
        .bottom-player-container {{ position: fixed; bottom: 0; left: 0; right: 0; background: white; padding: 20px 40px; border-top: 1px solid #e1e4e8; box-shadow: 0 -4px 20px rgba(0,0,0,0.06); display: flex; justify-content: center; align-items: center; z-index: 1000; }}
        audio {{ width: 100%; max-width: 900px; }}
    </style>
</head>
<body>
<div class="main-container">
    <h2 style="font-size: 22px; margin-bottom: 30px; border-bottom: 2px solid #333; padding-bottom: 10px;">📜 강의 스크립트: {file_base_name}</h2>
    <div class="transcript-box">
        {content_html}
    </div>
</div>
<div class="bottom-player-container">
    <audio id="audioPlayer" controls>
        <source src="{audio_file}" type="audio/mp4">
        브라우저가 오디오 태그를 지원하지 않습니다.
    </audio>
</div>
<script>
    function playAt(seconds) {{
        var player = document.getElementById('audioPlayer');
        player.currentTime = seconds;
        player.play();
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

print("\n✨ 모든 작업이 완료되었습니다!")
