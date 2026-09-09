import { icons } from '../icons.js';
import { formatTime } from '../timeline.js';

const BAR_COUNT = 220;
const WAVE_SEED = 20260604;

/**
 * 파형 막대의 높이를 만듭니다.
 * 새로 고칠 때마다 모양이 달라지지 않도록 고정된 시드의 의사 난수를 씁니다.
 * @returns {number[]} 20 ~ 100 사이의 높이(%)
 */
export function makeWaveHeights(count = BAR_COUNT, seed = WAVE_SEED) {
  let value = seed;
  const random = () => {
    value = (value * 1103515245 + 12345) % 2147483648;
    return value / 2147483648;
  };
  return Array.from({ length: count }, (_, i) =>
    Math.min(100, 20 + Math.abs(Math.sin(i / 7)) * 34 + random() * 46));
}

/**
 * 하단 재생 바 (명세 4.5).
 *
 * @param {{
 *   duration: number,
 *   onSeek: (seconds:number) => void,
 *   onSeekRatio: (ratio:number) => void,
 *   onToggle: () => void,
 *   onNudge: (delta:number) => void,
 *   onSpeed: () => void,
 * }} deps
 */
export function createPlayerBar({ duration, onSeek, onSeekRatio, onToggle, onNudge, onSpeed }) {
  const element = document.createElement('footer');
  element.className = 'player';
  element.innerHTML = `
    <div class="wave" role="slider" tabindex="0" aria-label="재생 위치"
         aria-valuemin="0" aria-valuemax="${duration}" aria-valuenow="0"></div>
    <div class="player-row">
      <span class="t-now" data-now>00:00</span>
      <div class="transport">
        <button class="icon-btn" type="button" aria-label="3초 뒤로" data-back>${icons.seek3(true)}</button>
        <button class="icon-btn play" type="button" aria-label="재생" data-play>
          <svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor" data-play-icon>${icons.play}</svg>
        </button>
        <button class="icon-btn" type="button" aria-label="3초 앞으로" data-forward>${icons.seek3(false)}</button>
      </div>
      <div class="player-right">
        <span class="t-total">${formatTime(duration)}</span>
        <button class="speed" type="button" data-speed aria-label="재생 속도 바꾸기">1x</button>
      </div>
    </div>
  `;

  const wave = element.querySelector('.wave');
  const nowEl = element.querySelector('[data-now]');
  const playBtn = element.querySelector('[data-play]');
  const playIcon = element.querySelector('[data-play-icon]');
  const speedBtn = element.querySelector('[data-speed]');

  const bars = makeWaveHeights().map((height) => {
    const bar = document.createElement('span');
    bar.style.height = `${height}%`;
    wave.append(bar);
    return bar;
  });

  /* 파형을 누르면 그 가로 위치의 비율만큼 재생 위치를 옮깁니다. */
  wave.addEventListener('click', (event) => {
    const rect = wave.getBoundingClientRect();
    onSeekRatio((event.clientX - rect.left) / rect.width);
  });
  wave.addEventListener('keydown', (event) => {
    if (event.key === 'ArrowRight') { event.preventDefault(); onNudge(5); }
    if (event.key === 'ArrowLeft') { event.preventDefault(); onNudge(-5); }
  });

  playBtn.addEventListener('click', onToggle);
  element.querySelector('[data-back]').addEventListener('click', () => onNudge(-3));
  element.querySelector('[data-forward]').addEventListener('click', () => onNudge(3));
  speedBtn.addEventListener('click', onSpeed);

  let playedCount = 0;
  let wasPlaying = null;
  let shownTime = null;
  let shownSpeed = null;

  return {
    element,

    /** 재생 상태를 화면에 반영합니다. */
    render({ time, playing, speed }) {
      const label = formatTime(time);
      if (label !== shownTime) {
        shownTime = label;
        nowEl.textContent = label;
      }
      wave.setAttribute('aria-valuenow', String(Math.round(time)));
      wave.setAttribute('aria-valuetext', label);

      // 달라진 막대만 손봅니다.
      const next = Math.round((time / duration) * bars.length);
      if (next !== playedCount) {
        const [from, to] = next > playedCount ? [playedCount, next] : [next, playedCount];
        for (let i = from; i < to; i += 1) bars[i]?.classList.toggle('is-played', i < next);
        playedCount = next;
      }

      if (playing !== wasPlaying) {
        wasPlaying = playing;
        playIcon.innerHTML = playing ? icons.pause : icons.play;
        playBtn.setAttribute('aria-label', playing ? '일시정지' : '재생');
      }

      if (speed !== shownSpeed) {
        shownSpeed = speed;
        speedBtn.textContent = `${speed}x`;
      }
    },
  };
}
