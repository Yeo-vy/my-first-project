# legacy

지금은 쓰지 않는, 웹 서버(`server/`) 이전 구조의 코드와 문서입니다. 기록용으로만 남겨 둡니다.

예전 구조: Azure VM 의 `my_files` 에 녹음을 올리면 `inotifywait` 가 `받아쓰기py.py` 를 실행해
`_강의스크립트.html` 을 만들고, 그 HTML 에서 고친 내용은 `자막저장서버.py`(8900 포트)가 txt 에 저장했습니다.

지금은 `server/migrator.py` 가 녹음을 보드로 등록하고, 받아쓰기는 `server/ai_service.py` 가 합니다.

| 파일 | 내용 |
|---|---|
| `받아쓰기py.py` | 폴더를 통째로 훑어 받아쓰는 독립 스크립트 |
| `자막저장서버.py` | 위 스크립트가 만든 HTML 의 편집 내용을 저장하던 서버 |
| `서버만들기.txt` | Azure VM 구축 기록 |
| `서버업데이트가이드.txt` | Azure VM 에 적용하던 작업 순서 |
| `claude 분석.txt`, `implementation_plan_opus4.6.md`, `project_analysis_gemini3.6flash.md` | 예전 구조를 두고 한 분석과 개선 계획 |

`받아쓰기py.py` 는 자기 폴더 옆의 `녹음파일원본/` 을 찾으므로, 이 폴더로 옮긴 채로는 실행해도 동작하지 않습니다.
