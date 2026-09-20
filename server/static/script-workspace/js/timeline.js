/**
 * 문장별 시각 계산 (명세 5장).
 *
 * 원본 데이터에는 문단이 시작하는 시각만 들어 있어서, 문장 하나하나의 시각은
 * 아래 규칙으로 계산합니다.
 *   1. 문단이 차지하는 시간은 다음 문단의 시작 시각까지입니다.
 *      마지막 문단은 시작 시각에 endPad(기본 60초)를 더한 값을 끝으로 봅니다.
 *   2. 문단이 차지하는 시간을 각 문장의 글자 수 비율에 따라 나눕니다.
 *   3. 나눈 값을 앞에서부터 누적해서 각 문장의 시작 시각과 끝 시각을 정합니다.
 *
 * 화면을 그리는 코드와 떨어져 있는 순수 함수라서 그대로 단위 테스트할 수 있습니다.
 * (tests/timeline.test.js)
 *
 * @typedef {Object} Sentence
 * @property {string} text
 * @property {number} start        초 단위
 * @property {number} end
 * @property {number} segmentIndex
 * @property {number} index        전체 문장 중 몇 번째인지
 *
 * @typedef {Object} Segment
 * @property {number} start        문단이 시작하는 시각(초)
 * @property {string[]} sentences
 * @property {Sentence[]} items
 */

export const DEFAULT_END_PAD = 60;

/**
 * 원본 문단 목록으로 문장별 시각을 채운 타임라인을 만듭니다.
 * 원본 배열은 건드리지 않고 새 객체를 돌려줍니다.
 *
 * @param {{start:number, sentences:string[]}[]} rawSegments
 * @param {{endPad?: number}} [options]
 * @returns {{segments: Segment[], sentences: Sentence[]}}
 */
export function buildTimeline(rawSegments, options = {}) {
  const endPad = options.endPad ?? DEFAULT_END_PAD;
  /** @type {Sentence[]} */
  const sentences = [];

  const segments = rawSegments.map((raw, segmentIndex) => {
    const isLast = segmentIndex + 1 >= rawSegments.length;
    const next = isLast ? raw.start + endPad : rawSegments[segmentIndex + 1].start;
    const span = next - raw.start;

    const totalChars = raw.sentences.reduce((sum, text) => sum + text.length, 0);
    let cursor = raw.start;

    const items = raw.sentences.map((text, i) => {
      // 글자 수가 없는 이상한 데이터가 들어와도 균등하게 나눕니다.
      const ratio = totalChars > 0 ? text.length / totalChars : 1 / raw.sentences.length;
      const isLastSentence = i + 1 >= raw.sentences.length;
      const start = cursor;
      // 마지막 문장의 끝은 누적 오차 없이 문단의 끝에 정확히 맞춥니다.
      const end = isLastSentence ? next : start + span * ratio;

      /** @type {Sentence} */
      const item = { text, start, end, segmentIndex, index: sentences.length };
      sentences.push(item);
      cursor = end;
      return item;
    });

    return { start: raw.start, sentences: raw.sentences, items };
  });

  return { segments, sentences };
}

/**
 * 지금 재생 중인 문장의 번호를 찾습니다. 해당하는 문장이 없으면 -1.
 * 문장의 끝과 다음 문장의 시작이 맞물려 있어서 문단 경계에서도 끊기지 않습니다.
 *
 * @param {Sentence[]} sentences
 * @param {number} time
 * @returns {number}
 */
export function findSentenceIndex(sentences, time) {
  if (!sentences.length) return -1;
  if (time < sentences[0].start || time >= sentences[sentences.length - 1].end) return -1;

  let lo = 0;
  let hi = sentences.length - 1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (time < sentences[mid].start) hi = mid - 1;
    else if (time >= sentences[mid].end) lo = mid + 1;
    else return mid;
  }
  return -1;
}

/**
 * 초를 mm:ss 로 적습니다. (예: 317 → "05:17")
 * @param {number} sec
 * @returns {string}
 */
export function formatTime(sec) {
  const total = Math.max(0, Math.floor(sec));
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}
