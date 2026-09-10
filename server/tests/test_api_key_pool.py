# -*- coding: utf-8 -*-
"""API 키 여러 개를 돌려 쓰는 동작 검증.

한도(429)에 걸린 키에서 다음 키로 자동으로 넘어가는지, 걸린 키를 쉬게 했다가 다시 앞 칸부터
쓰는지를 가짜 클라이언트로 확인한다. 실제 Gemini 는 부르지 않는다.

실행: `python server/tests/test_api_key_pool.py`  (pytest 없이 그냥 돌아간다)
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

import server.ai_service as ai  # noqa: E402

passed = 0
failed = []


def check(name, condition, detail=""):
    global passed
    if condition:
        passed += 1
    else:
        failed.append(f"{name}{(': ' + detail) if detail else ''}")


MINUTE_429 = "429 RESOURCE_EXHAUSTED. {'error': {'details': [{'retryDelay': '37s'}]}}"
DAILY_429 = ("429 RESOURCE_EXHAUSTED. {'quotaId': 'GenerateRequestsPerDayPerProjectPerModel-FreeTier', "
             "'retryDelay': '12s'}")


class FakeResponse:
    def __init__(self, text):
        self.text = text


class FakeModels:
    def __init__(self, owner):
        self.owner = owner

    def generate_content(self, model, contents, config=None):
        self.owner.calls.append("generate")
        if self.owner.error:
            raise RuntimeError(self.owner.error)
        return FakeResponse(f"answer from {self.owner.key}")

    def generate_content_stream(self, model, contents):
        self.owner.calls.append("stream")
        if self.owner.error:
            raise RuntimeError(self.owner.error)
        return iter([FakeResponse("hello "), FakeResponse(self.owner.key)])


class FakeClient:
    def __init__(self, key, error):
        self.key = key
        self.error = error
        self.calls = []
        self.models = FakeModels(self)


def use_keys(keys, errors=None):
    """키 목록과 키별 오류를 정해 두고, 만들어진 클라이언트를 기록한다."""
    errors = errors or {}
    made = []

    def fake_make_client(key):
        client = FakeClient(key, errors.get(key))
        made.append(client)
        return client

    ai.api_keys = list(keys)
    ai.api_key_pool = ai.ApiKeyPool(ai.api_keys)
    ai.make_client = fake_make_client
    return made


def generate(label="테스트"):
    return ai.run_with_api_keys(label, lambda c: c.models.generate_content(model="m", contents="x")).text


# ---- load_api_keys ---------------------------------------------------------------

def test_load_reads_twelve_slots_then_paid():
    names = ai.API_KEY_ENV_NAMES
    saved = {n: os.environ.get(n) for n in names}
    try:
        for n in names:
            os.environ.pop(n, None)
        os.environ["GEMINI_API_KEY"] = "k1"
        os.environ["GEMINI_API_KEY_12"] = "k12"
        os.environ["GEMINI_API_KEY_5"] = " k5 "
        os.environ["GEMINI_API_KEY_7"] = "k1"  # 같은 키를 두 칸에 넣어도 한 번만 쓴다
        os.environ["GEMINI_API_KEY_PAID"] = "paid"
        keys = ai.load_api_keys()
        check("칸 순서대로, 빈 칸과 중복은 빼고, 유료는 맨 뒤", keys == ["k1", "k5", "k12", "paid"], str(keys))
    finally:
        for n, v in saved.items():
            if v is None:
                os.environ.pop(n, None)
            else:
                os.environ[n] = v
    check("최대 12칸", len([n for n in names if n != "GEMINI_API_KEY_PAID"]) == 12)


# ---- quota_rest_seconds -----------------------------------------------------------

def test_rest_seconds():
    check("retryDelay 를 따른다", ai.quota_rest_seconds(RuntimeError(MINUTE_429)) == 37.0)
    check("하루 한도는 길게 쉰다", ai.quota_rest_seconds(RuntimeError(DAILY_429)) == ai.KEY_REST_DAILY_SEC)
    check("아무 정보 없으면 기본값", ai.quota_rest_seconds(RuntimeError("429")) == ai.KEY_REST_SEC)
    check("'retry in 5.5s' 도 읽되 너무 짧게는 안 쉰다",
          ai.quota_rest_seconds(RuntimeError("Please retry in 5.5s")) == 10.0)


# ---- run_with_api_keys ------------------------------------------------------------

def test_switches_to_next_key_on_quota():
    use_keys(["k1", "k2", "k3"], {"k1": MINUTE_429})
    check("한도 걸린 k1 에서 k2 로 넘어간다", generate() == "answer from k2")
    order = ai.api_key_pool.order()
    check("k1 은 쉬는 동안 뒤로 밀린다", order == [1, 2, 0], str(order))


def test_returns_to_first_key_after_rest():
    made = use_keys(["k1", "k2"], {"k1": MINUTE_429})
    generate()
    made[0].error = None
    ai.api_key_pool._resting_until[0] = 0.0  # 쉬는 시간이 지났다
    # 새 클라이언트는 errors 표를 다시 보므로 표에서도 지운다
    ai.make_client = lambda key: FakeClient(key, None)
    check("풀리면 다시 앞 칸 키부터 쓴다", generate() == "answer from k1")


def test_resting_key_is_skipped_first():
    made = use_keys(["k1", "k2"])
    ai.api_key_pool.rest(0, RuntimeError(DAILY_429))
    check("쉬는 키보다 쉬지 않는 키를 먼저 쓴다", generate() == "answer from k2")
    check("쉬는 k1 은 두드리지도 않는다", [c.key for c in made] == ["k2"], str([c.key for c in made]))


def test_paid_key_used_only_after_free_keys():
    use_keys(["free1", "free2", "paid"], {"free1": DAILY_429, "free2": MINUTE_429})
    check("무료 키가 다 막히면 유료 키", generate() == "answer from paid")


def test_all_keys_exhausted():
    use_keys(["k1", "k2"], {"k1": MINUTE_429, "k2": DAILY_429})
    try:
        generate()
        check("모두 막히면 오류", False)
    except RuntimeError as err:
        check("모두 막혔다고 알려 준다", "2개가 모두 한도" in str(err), str(err))


def test_each_key_tried_once_per_call():
    made = use_keys(["k1", "k2", "k3"], {"k1": MINUTE_429, "k2": MINUTE_429, "k3": MINUTE_429})
    try:
        generate()
    except RuntimeError:
        pass
    check("한 번 부를 때 키마다 한 번씩만", [c.key for c in made] == ["k1", "k2", "k3"], str([c.key for c in made]))


def test_other_errors_do_not_switch_keys():
    made = use_keys(["k1", "k2"], {"k1": "400 INVALID_ARGUMENT"})
    try:
        generate()
        check("다른 오류는 올려 보낸다", False)
    except RuntimeError as err:
        check("다른 오류는 그대로 올린다", "INVALID_ARGUMENT" in str(err))
    check("다른 오류로는 키를 바꾸지 않는다", [c.key for c in made] == ["k1"])


def test_no_keys():
    use_keys([])
    try:
        generate()
        check("키가 없으면 오류", False)
    except RuntimeError as err:
        check("키가 없다고 알려 준다", "GEMINI_API_KEY" in str(err))


# ---- 받아쓰기 / 챗봇 ---------------------------------------------------------------

def test_transcribe_reuploads_on_new_key():
    uploads, deletes = [], []

    class Uploaded:
        def __init__(self, key):
            self.name = f"files/{key}"

    class Files:
        def __init__(self, key):
            self.key = key

        def upload(self, file, config):
            uploads.append(self.key)
            return Uploaded(self.key)

        def delete(self, name):
            deletes.append(name)

    class Candidate:
        finish_reason = None

        class content:
            parts = [type("P", (), {"text": "[00:00] 안녕하세요"})()]

    class TranscribeModels:
        def __init__(self, key):
            self.key = key

        def generate_content(self, model, contents):
            if self.key == "k1":
                raise RuntimeError(MINUTE_429)
            return type("R", (), {"candidates": [Candidate()]})()

    def fake_make_client(key):
        return type("C", (), {"files": Files(key), "models": TranscribeModels(key)})()

    ai.api_keys = ["k1", "k2"]
    ai.api_key_pool = ai.ApiKeyPool(ai.api_keys)
    ai.make_client = fake_make_client
    text = ai.transcribe_chunk_with_fallback("chunk.mp3", "board_1_chunk_1", "prompt")
    check("받아쓰기도 다음 키로 넘어간다", text == "[00:00] 안녕하세요", text)
    check("키를 바꾸면 업로드부터 다시", uploads == ["k1", "k2"], str(uploads))
    check("올린 파일은 키마다 지운다", deletes == ["files/k1", "files/k2"], str(deletes))


def test_chat_stream_switches_before_first_text():
    use_keys(["k1", "k2"], {"k1": MINUTE_429})
    events = [json.loads(line[len("data: "):]) for line in ai.stream_board_chat("스크립트", [], "질문")]
    text = "".join(e.get("text", "") for e in events)
    check("챗봇도 다음 키로 넘어간다", text == "hello k2", str(events))


def test_chat_stream_all_exhausted():
    use_keys(["k1"], {"k1": MINUTE_429})
    events = [json.loads(line[len("data: "):]) for line in ai.stream_board_chat("스크립트", [], "질문")]
    check("챗봇은 모두 막혔다고 알려 준다", len(events) == 1 and "모두 한도" in events[0].get("error", ""), str(events))


def test_summary_uses_fallback():
    use_keys(["k1", "k2"], {"k1": DAILY_429})
    check("요약도 다음 키로 넘어간다", ai.generate_summary_ai("스크립트") == "answer from k2")


original_make_client = ai.make_client
for fn in list(globals().values()):
    if callable(fn) and getattr(fn, "__name__", "").startswith("test_"):
        fn()
ai.make_client = original_make_client

print(f"통과 {passed}개, 실패 {len(failed)}개")
for f in failed:
    print("  실패:", f)
sys.exit(1 if failed else 0)
