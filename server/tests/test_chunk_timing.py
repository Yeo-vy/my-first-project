# -*- coding: utf-8 -*-
"""청크 시각 처리 검증.

자막 하이라이트가 맞으려면 문단 시각이 맞아야 하고, 문단 시각은 이 함수들이 정한다.
받아쓰기(Gemini)가 시각을 엉뚱하게 주거나 아예 안 주는 경우가 잦아서, 그때 무슨 일이
일어나야 하는지를 여기에 못 박아 둔다.

실행: `python server/tests/test_chunk_timing.py`  (pytest 없이 그냥 돌아간다)
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from server.ai_service import (  # noqa: E402
    chunk_timestamps_are_usable,
    drop_overlap_pieces,
    parse_chunk_pieces,
    spread_pieces,
)

FIVE_MIN = 5 * 60 * 1000
OVERLAP = 20 * 1000

passed = 0
failed = []


def check(name, condition, detail=""):
    global passed
    if condition:
        passed += 1
    else:
        failed.append(f"{name}{(': ' + detail) if detail else ''}")


# ---- parse_chunk_pieces --------------------------------------------------------

def test_parse_reads_chunk_relative_times():
    text = "[00:05] 안녕하세요\n[01:30] 오늘은 정렬을 보겠습니다"
    pieces = parse_chunk_pieces(text, FIVE_MIN)
    check("조각 두 개를 읽는다", len(pieces) == 2, str(pieces))
    check("첫 조각은 5초", pieces[0][0] == 5000, str(pieces[0]))
    check("둘째 조각은 90초", pieces[1][0] == 90000, str(pieces[1]))


def test_parse_clamps_beyond_chunk():
    """청크는 5분인데 받아쓰기가 [12:00] 을 찍어 주는 일이 있다. 청크 밖은 지어낸 값이다."""
    text = "[00:10] 처음\n[12:00] 있을 수 없는 시각"
    pieces = parse_chunk_pieces(text, FIVE_MIN)
    check("청크 끝을 넘지 않는다", max(ms for ms, _ in pieces) <= FIVE_MIN, str(pieces))


def test_parse_fixes_backward_time():
    """시간이 되감기면 문단 묶기와 종료 시각 계산이 통째로 어긋난다."""
    text = "[02:00] 먼저\n[00:30] 되감김\n[02:30] 그다음"
    pieces = parse_chunk_pieces(text, FIVE_MIN)
    times = [ms for ms, _ in pieces]
    check("시각이 뒤로 가지 않는다", times == sorted(times), str(times))


def test_parse_drops_empty_text():
    text = "[00:01]\n[00:02]\n[00:03] 진짜 본문"
    pieces = parse_chunk_pieces(text, FIVE_MIN)
    check("본문 없는 타임스탬프는 버린다", len(pieces) == 1, str(pieces))
    check("시각은 마지막 것을 잇는다", pieces[0][0] == 3000, str(pieces))


# ---- chunk_timestamps_are_usable -----------------------------------------------

def test_single_stamp_chunk_is_unusable():
    """실제로 board 55 에서 벌어진 일: 한 청크 전체가 [00:00] 하나로 왔다."""
    text = "[00:00] " + ("이러저러한 설명이 계속 이어집니다. " * 200)
    pieces = parse_chunk_pieces(text, FIVE_MIN)
    check("시각 하나뿐인 청크는 못 믿는다",
          not chunk_timestamps_are_usable(pieces, FIVE_MIN))


def test_front_loaded_chunk_is_unusable():
    """앞 30초만 찍고 나머지 4분 30초를 통째로 붙여 주는 경우도 못 쓴다."""
    text = "\n".join(f"[00:{s:02d}] 짧은 문장" for s in (0, 10, 20, 30))
    pieces = parse_chunk_pieces(text, FIVE_MIN)
    check("청크 앞부분만 덮으면 못 믿는다",
          not chunk_timestamps_are_usable(pieces, FIVE_MIN))


def test_healthy_chunk_is_usable():
    text = "\n".join(f"[{m:02d}:{s:02d}] 문장" for m, s in
                     [(0, 5), (0, 40), (1, 20), (2, 10), (3, 5), (4, 30)])
    pieces = parse_chunk_pieces(text, FIVE_MIN)
    check("청크를 고루 덮으면 믿는다", chunk_timestamps_are_usable(pieces, FIVE_MIN))


# ---- spread_pieces --------------------------------------------------------------

def test_spread_covers_whole_chunk():
    pieces = [(0, "가" * 100), (0, "나" * 100), (0, "다" * 100)]
    spread = spread_pieces(pieces, FIVE_MIN)
    times = [ms for ms, _ in spread]
    check("첫 조각은 청크 시작", times[0] == 0, str(times))
    check("시각이 늘어난다", times == sorted(times) and len(set(times)) == 3, str(times))
    check("청크 안에 머문다", max(times) < FIVE_MIN, str(times))
    check("글자 수에 비례한다", abs(times[1] - FIVE_MIN / 3) < 1000, str(times))


def test_spread_keeps_text_order():
    pieces = [(0, "첫째"), (0, "둘째"), (0, "셋째")]
    spread = spread_pieces(pieces, FIVE_MIN)
    check("본문 순서는 그대로", [t for _, t in spread] == ["첫째", "둘째", "셋째"])


def test_spread_handles_empty():
    check("빈 입력은 그대로", spread_pieces([], FIVE_MIN) == [])


# ---- drop_overlap_pieces --------------------------------------------------------

def test_drop_overlap_removes_duplicated_head():
    """겹쳐 자른 앞부분은 이전 청크가 이미 받아쓴 내용이라 버려야 한다.

    예전 코드는 절대 시각으로 바꾼 뒤에 걸러서 한 줄도 못 버렸다 (모든 줄이 청크 시작보다
    크다). 그래서 경계마다 같은 말이 두 번씩 들어갔다.
    """
    pieces = [(0, "겹친 앞부분"), (10000, "아직 겹침"), (25000, "여기부터 새 내용")]
    kept = drop_overlap_pieces(pieces, OVERLAP)
    check("겹친 구간은 버린다", [t for _, t in kept] == ["여기부터 새 내용"], str(kept))


# ---- 두 청크를 이어 붙였을 때 ------------------------------------------------------

def test_chunks_stay_inside_their_window():
    """청크 경계 시각만은 우리가 직접 잘라서 아는 값이다. 그 밖으로 새면 안 된다."""
    step = FIVE_MIN - OVERLAP
    absolute = []
    for i, start_ms in enumerate([0, step]):
        # 첫 청크는 정상, 둘째 청크는 받아쓰기가 시각을 하나만 준 경우
        if i == 0:
            text = "\n".join(f"[{m:02d}:{s:02d}] 문장" for m, s in
                             [(0, 0), (1, 0), (2, 0), (3, 0), (4, 0)])
        else:
            text = "[00:00] " + ("이어지는 설명입니다. " * 100)
        pieces = parse_chunk_pieces(text, FIVE_MIN)
        if chunk_timestamps_are_usable(pieces, FIVE_MIN):
            if i > 0:
                pieces = drop_overlap_pieces(pieces, OVERLAP)
        else:
            pieces = spread_pieces(pieces, FIVE_MIN)
        absolute.extend((start_ms + ms, text) for ms, text in pieces)

    times = [ms for ms, _ in absolute]
    check("전체 시각이 늘어난다", times == sorted(times), str(times[:8]))
    check("둘째 청크가 자기 창 안에 있다",
          all(step <= t <= step + FIVE_MIN for t in times if t >= step))
    second = [t for t in times if t >= step]
    check("못 믿는 청크도 한 점에 뭉치지 않는다", len(set(second)) > 1, str(second[:5]))


for fn in list(globals().values()):
    if callable(fn) and getattr(fn, "__name__", "").startswith("test_"):
        fn()

print(f"통과 {passed}개, 실패 {len(failed)}개")
for f in failed:
    print("  실패:", f)
sys.exit(1 if failed else 0)
