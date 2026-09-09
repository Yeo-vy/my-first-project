/**
 * 문장별 시각 계산에 대한 단위 테스트 (명세 9장 3번).
 * 이 저장소에는 node 도, 자바스크립트 테스트 도구도 없어서
 * 브라우저에서 tests/index.html 을 열어 실행합니다.
 */
import { buildTimeline, findSentenceIndex, formatTime, DEFAULT_END_PAD } from '../js/timeline.js';
import { SEGMENTS, RECORDING } from '../js/data.js';

/** @type {{name:string, fn:Function}[]} */
const cases = [];
const test = (name, fn) => cases.push({ name, fn });

function assert(ok, message) {
  if (!ok) throw new Error(message);
}
function near(actual, expected, message, tolerance = 1e-9) {
  assert(Math.abs(actual - expected) < tolerance,
    `${message} — 기대값 ${expected}, 실제값 ${actual}`);
}

/* ── 규칙 1. 문단이 차지하는 시간 ───────────────────────── */

test('문단의 첫 문장은 문단의 시작 시각에서 출발한다', () => {
  const { segments } = buildTimeline(SEGMENTS);
  segments.forEach((segment, i) => {
    near(segment.items[0].start, segment.start, `${i}번 문단의 첫 문장 시작`);
  });
});

test('문단의 마지막 문장은 다음 문단이 시작하는 시각에 끝난다', () => {
  const { segments } = buildTimeline(SEGMENTS);
  for (let i = 0; i < segments.length - 1; i += 1) {
    const items = segments[i].items;
    near(items[items.length - 1].end, segments[i + 1].start, `${i}번 문단의 끝`);
  }
});

test('마지막 문단은 시작 시각에 60초를 더한 값에서 끝난다', () => {
  const { segments } = buildTimeline(SEGMENTS);
  const last = segments[segments.length - 1];
  const items = last.items;
  near(items[items.length - 1].end, last.start + DEFAULT_END_PAD, '마지막 문단의 끝');
});

test('endPad 를 바꾸면 마지막 문단의 길이가 따라 바뀐다', () => {
  const raw = [{ start: 0, sentences: ['가나다'] }];
  const { sentences } = buildTimeline(raw, { endPad: 10 });
  near(sentences[0].end, 10, 'endPad 10일 때의 끝');
});

/* ── 규칙 2. 글자 수 비율로 나눈다 ──────────────────────── */

test('문장의 길이는 글자 수 비율을 따른다', () => {
  const raw = [
    { start: 0, sentences: ['가', '나나나'] },   // 1 : 3
    { start: 40, sentences: ['끝'] },
  ];
  const { sentences } = buildTimeline(raw);
  near(sentences[0].start, 0, '첫 문장 시작');
  near(sentences[0].end, 10, '첫 문장 끝 (40초의 1/4)');
  near(sentences[1].start, 10, '둘째 문장 시작');
  near(sentences[1].end, 40, '둘째 문장 끝');
});

test('글자 수가 같으면 시간을 똑같이 나눈다', () => {
  const raw = [
    { start: 0, sentences: ['가나', '다라', '마바'] },
    { start: 30, sentences: ['끝'] },
  ];
  const { sentences } = buildTimeline(raw);
  [0, 10, 20].forEach((expected, i) => near(sentences[i].start, expected, `${i}번 문장 시작`));
});

/* ── 규칙 3. 앞에서부터 누적한다 ────────────────────────── */

test('문장의 시각은 앞뒤가 맞물려 이어진다', () => {
  const { sentences } = buildTimeline(SEGMENTS);
  for (let i = 1; i < sentences.length; i += 1) {
    near(sentences[i].start, sentences[i - 1].end, `${i}번 문장이 앞 문장의 끝과 이어짐`);
    assert(sentences[i].end > sentences[i].start, `${i}번 문장의 길이가 0보다 큼`);
  }
});

test('문장에는 자기가 속한 문단 번호가 붙는다', () => {
  const { segments, sentences } = buildTimeline(SEGMENTS);
  segments.forEach((segment, i) => {
    segment.items.forEach((item) => {
      assert(item.segmentIndex === i, `${item.index}번 문장의 문단 번호`);
      assert(sentences[item.index] === item, '전체 목록과 문단 목록이 같은 객체를 가리킴');
    });
  });
  assert(sentences.length === SEGMENTS.reduce((n, s) => n + s.sentences.length, 0), '문장 개수');
});

test('원본 데이터를 건드리지 않는다', () => {
  const raw = [{ start: 0, sentences: ['가'] }];
  buildTimeline(raw);
  assert(raw[0].items === undefined, '원본에 items 가 생기지 않음');
});

test('빈 문장만 있어도 시간을 균등하게 나눈다', () => {
  const raw = [{ start: 0, sentences: ['', ''] }];
  const { sentences } = buildTimeline(raw, { endPad: 20 });
  near(sentences[0].end, 10, '빈 문장도 절반씩');
  assert(Number.isFinite(sentences[1].end), '0으로 나눈 값이 생기지 않음');
});

/* ── 지금 재생 중인 문장 찾기 ───────────────────────────── */

test('문단 경계에서도 강조가 끊기지 않는다', () => {
  const { segments, sentences } = buildTimeline(SEGMENTS);
  for (let i = 0; i < segments.length - 1; i += 1) {
    const boundary = segments[i + 1].start;
    const before = findSentenceIndex(sentences, boundary - 0.01);
    const at = findSentenceIndex(sentences, boundary);
    assert(before >= 0 && at >= 0, `${i}번 문단 경계에서 문장을 찾음`);
    assert(at === before + 1, `${i}번 문단 경계에서 다음 문장으로 이어짐`);
    assert(sentences[at].segmentIndex === i + 1, '경계 뒤의 문장은 다음 문단에 속함');
  }
});

test('문장의 시작 시각으로 옮기면 그 문장이 잡힌다', () => {
  const { sentences } = buildTimeline(SEGMENTS);
  sentences.forEach((item, i) => {
    assert(findSentenceIndex(sentences, item.start) === i, `${i}번 문장을 시작 시각으로 찾음`);
  });
});

test('스크립트 바깥의 시각에서는 강조할 문장이 없다', () => {
  const { sentences } = buildTimeline(SEGMENTS);
  assert(findSentenceIndex(sentences, 0) === -1, '맨 앞');
  assert(findSentenceIndex(sentences, RECORDING.duration) === -1, '맨 뒤');
  assert(findSentenceIndex([], 10) === -1, '문장이 하나도 없을 때');
});

test('처음 진입했을 때의 재생 위치(317초)에 강조할 문장이 있다', () => {
  const { sentences } = buildTimeline(SEGMENTS);
  const at = findSentenceIndex(sentences, RECORDING.initialTime);
  assert(at >= 0, '317초에 해당하는 문장을 찾음');
  assert(sentences[at].segmentIndex === 1, '05:12 문단 안에 있음');
});

/* ── 시각 표기 ──────────────────────────────────────────── */

test('초를 mm:ss 로 적는다', () => {
  const pairs = [[0, '00:00'], [9, '00:09'], [317, '05:17'], [3256, '54:16'], [-5, '00:00'], [59.9, '00:59']];
  pairs.forEach(([input, expected]) => {
    assert(formatTime(input) === expected, `formatTime(${input}) → ${formatTime(input)}, 기대값 ${expected}`);
  });
});

test('mm:ss 는 항상 다섯 글자라 숫자가 흔들리지 않는다', () => {
  for (let s = 0; s <= 3256; s += 7) {
    assert(formatTime(s).length === 5, `${s}초의 표기 길이`);
  }
});

/** 테스트를 모두 돌리고 결과를 돌려줍니다. */
export function run() {
  const results = cases.map(({ name, fn }) => {
    try {
      fn();
      return { name, ok: true };
    } catch (error) {
      return { name, ok: false, message: error.message };
    }
  });
  return { results, passed: results.filter((r) => r.ok).length, total: results.length };
}
