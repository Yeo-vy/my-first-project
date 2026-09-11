# -*- coding: utf-8 -*-
"""조각 시각 처리 검증.

자막 하이라이트가 맞으려면 세그먼트 시각이 맞아야 하고, 세그먼트 시각은 이 함수들이 정한다.
시각은 받아쓰기(Gemini)에게 묻지 않고, 녹음을 쉬는 자리에서 잘라 우리가 잰다. 받아쓰기는
조각 번호만 지키면 되는데, 그 번호를 어길 때 무슨 일이 일어나야 하는지를 여기에 못 박아 둔다.

실행: `python server/tests/test_chunk_timing.py`  (pytest 없이 그냥 돌아간다)
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from server.ai_service import (  # noqa: E402
    LEVEL_WINDOW_MS,
    SEGMENT_MAX_MS,
    SEGMENT_MIN_MS,
    find_crowded_segment,
    group_segments,
    parse_segment_transcript,
    plan_segments,
    spread_pieces,
)

FIVE_MIN = 5 * 60 * 1000
SPEECH_DB = -25.0
PAUSE_DB = -55.0

passed = 0
failed = []


def check(name, condition, detail=""):
    global passed
    if condition:
        passed += 1
    else:
        failed.append(f"{name}{(': ' + detail) if detail else ''}")


def speech_levels(total_ms, pauses_ms=(), pause_windows=3):
    """말소리 음량이 이어지다가 pauses_ms 자리마다 pause_windows 창만큼 조용해지는 음량 목록."""
    levels = [SPEECH_DB] * (total_ms // LEVEL_WINDOW_MS)
    for at in pauses_ms:
        center = at // LEVEL_WINDOW_MS
        for i in range(center - pause_windows // 2, center + pause_windows // 2 + 1):
            if 0 <= i < len(levels):
                levels[i] = PAUSE_DB
    return levels


# ---- plan_segments --------------------------------------------------------------

def test_segments_cover_recording_without_gaps():
    total = 7 * 60 * 1000 + 1234
    segments = plan_segments(speech_levels(total, range(10_000, total, 7_000)), total)
    check("처음부터 시작한다", segments[0][0] == 0, str(segments[:2]))
    check("녹음 끝에서 닫는다", segments[-1][1] == total, str(segments[-2:]))
    check("빈틈도 겹침도 없다", all(a[1] == b[0] for a, b in zip(segments, segments[1:])))
    check("최대 길이를 넘지 않는다", all(e - s <= SEGMENT_MAX_MS for s, e in segments),
          str([e - s for s, e in segments]))
    check("마지막 말고는 최소 길이를 지킨다",
          all(e - s >= SEGMENT_MIN_MS - LEVEL_WINDOW_MS for s, e in segments[:-1]),
          str([e - s for s, e in segments]))


def test_cuts_at_the_pause():
    """말이 쉬는 자리가 하나뿐이면 거기서 자른다 (낱말 한가운데를 자르지 않는다)."""
    total = 50_000
    segments = plan_segments(speech_levels(total, [22_000]), total)
    check("쉬는 자리에서 자른다", abs(segments[0][1] - 22_000) <= LEVEL_WINDOW_MS, str(segments))


def test_prefers_real_pause_over_blip():
    """말 사이의 100ms 틈 하나보다 300ms 넘게 조용한 자리를 고른다."""
    total = 50_000
    levels = speech_levels(total, [26_000])                # 진짜 쉼
    levels[18_000 // LEVEL_WINDOW_MS] = -90.0              # 더 조용하지만 한 창뿐인 틈
    segments = plan_segments(levels, total)
    check("짧은 틈에 속지 않는다", abs(segments[0][1] - 26_000) <= LEVEL_WINDOW_MS, str(segments))


def test_without_levels_cuts_at_max_length():
    """음량을 못 쟀어도 변환은 계속된다. 조각 시각은 여전히 우리가 자른 값이다."""
    total = 100_000
    segments = plan_segments([], total)
    check("최대 길이로 자른다", [s for s, _ in segments] == [0, 30_000, 60_000, 90_000], str(segments))
    check("끝은 녹음 길이", segments[-1][1] == total, str(segments))


def test_short_recording_is_one_segment():
    segments = plan_segments(speech_levels(12_000), 12_000)
    check("짧은 녹음은 한 조각", segments == [(0, 12_000)], str(segments))
    check("길이 0 이면 조각 없음", plan_segments([], 0) == [])


# ---- group_segments -------------------------------------------------------------

def test_batches_stay_within_limit_and_order():
    segments = [(i * 25_000, (i + 1) * 25_000) for i in range(30)]    # 12분 30초
    batches = group_segments(segments, FIVE_MIN)
    check("묶음 하나가 5분을 넘지 않는다",
          all(b[-1][1] - b[0][0] <= FIVE_MIN for b in batches), str([len(b) for b in batches]))
    check("조각을 빠뜨리거나 섞지 않는다", [s for b in batches for s in b] == segments)
    check("5분이면 12조각씩", [len(b) for b in batches] == [12, 12, 6], str([len(b) for b in batches]))


# ---- parse_segment_transcript ---------------------------------------------------

def test_parse_reads_each_label():
    text = "<<1>>안녕하세요.\n<<2>> 오늘은 정렬을\n보겠습니다.\n<<3>>"
    texts = parse_segment_transcript(text, 3)
    check("번호마다 본문", texts == ["안녕하세요.", "오늘은 정렬을 보겠습니다.", ""], str(texts))


def test_parse_allows_skipped_silent_segment():
    """말이 없는 조각은 번호째로 건너뛰기도 한다. 그 조각만 비우고 나머지는 믿는다."""
    texts = parse_segment_transcript("<<1>> 하나\n<<3>> 셋", 3)
    check("건너뛴 번호는 빈 본문", texts == ["하나", "", "셋"], str(texts))


def test_parse_accepts_spaced_label_and_strips_timestamps():
    texts = parse_segment_transcript("<< 1 >> [00:03] 첫 문장\n<<2>>둘째 [ 00:20] 문장", 2)
    check("번호 안 공백도 번호", texts is not None and len(texts) == 2, str(texts))
    check("본문에 섞인 시각은 걷어낸다", texts == ["첫 문장", "둘째 문장"], str(texts))


def test_parse_rejects_broken_labels():
    check("번호가 없으면 못 믿는다", parse_segment_transcript("그냥 받아쓴 글", 3) is None)
    check("번호가 되감기면 못 믿는다", parse_segment_transcript("<<2>> 가 <<1>> 나", 3) is None)
    check("번호가 겹치면 못 믿는다", parse_segment_transcript("<<1>> 가 <<1>> 나", 3) is None)
    check("조각 수를 넘으면 못 믿는다", parse_segment_transcript("<<1>> 가 <<4>> 나", 3) is None)
    check("0 번은 없다", parse_segment_transcript("<<0>> 가", 3) is None)
    check("첫 번호 앞에 글이 있으면 못 믿는다",
          parse_segment_transcript("네, 받아쓰겠습니다.\n<<1>> 가", 3) is None)


# ---- find_crowded_segment -------------------------------------------------------

def test_normal_pace_is_not_crowded():
    """실제 강의 받아쓰기: 조각마다 초당 5.6~7.4자."""
    segments = [(0, 23_200), (23_200, 52_100), (52_100, 81_200)]
    texts = ["가" * 151, "나" * 206, "다" * 180]
    check("평소 속도는 믿는다", find_crowded_segment(texts, segments) is None)


def test_skipped_labels_are_crowded():
    """번호 두 개를 건너뛰고 세 조각 분량을 한 번호에 몰아 적은 경우."""
    segments = [(0, 25_000), (25_000, 50_000), (50_000, 75_000)]
    texts = ["가" * 520, "", ""]
    check("몰린 조각을 짚는다", find_crowded_segment(texts, segments) == 0)


def test_short_last_segment_is_not_false_alarm():
    """녹음 끝의 2초짜리 조각에 한마디가 들어가도 헛경보를 내지 않는다."""
    segments = [(0, 20_000), (20_000, 22_000)]
    texts = ["가" * 120, "네 수고하셨습니다."]
    check("짧은 조각은 봐준다", find_crowded_segment(texts, segments) is None)


# ---- spread_pieces (번호를 못 믿는 요청) ----------------------------------------

def test_spread_covers_whole_batch():
    pieces = [(0, "가" * 100), (0, "나" * 100), (0, "다" * 100)]
    spread = spread_pieces(pieces, FIVE_MIN)
    times = [ms for ms, _ in spread]
    check("첫 조각은 구간 시작", times[0] == 0, str(times))
    check("시각이 늘어난다", times == sorted(times) and len(set(times)) == 3, str(times))
    check("구간 안에 머문다", max(times) < FIVE_MIN, str(times))
    check("글자 수에 비례한다", abs(times[1] - FIVE_MIN / 3) < 1000, str(times))


def test_spread_keeps_text_order():
    pieces = [(0, "첫째"), (0, "둘째"), (0, "셋째")]
    spread = spread_pieces(pieces, FIVE_MIN)
    check("본문 순서는 그대로", [t for _, t in spread] == ["첫째", "둘째", "셋째"])


def test_spread_handles_empty():
    check("빈 입력은 그대로", spread_pieces([], FIVE_MIN) == [])


for fn in list(globals().values()):
    if callable(fn) and getattr(fn, "__name__", "").startswith("test_"):
        fn()

print(f"통과 {passed}개, 실패 {len(failed)}개")
for f in failed:
    print("  실패:", f)
sys.exit(1 if failed else 0)
