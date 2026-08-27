"""다글로 (daglo) AI 음성 기록 및 보드챗 서버 실행 스크립트"""

import uvicorn
import os

if __name__ == "__main__":
    port = int(os.getenv("PORT", 8000))
    host = os.getenv("HOST", "0.0.0.0")
    print(f"[START] Daglo Web Server started at http://localhost:{port}")
    uvicorn.run("server.main:app", host=host, port=port, reload=True)
