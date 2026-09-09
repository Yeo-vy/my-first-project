import { icons } from '../icons.js';
import { formatTime } from '../timeline.js';

const TOOLBAR_HIDE_DELAY = 220;   // 마우스가 도구 모음으로 건너가는 동안 사라지지 않게
const USER_SCROLL_PAUSE = 3500;   // 직접 스크롤한 뒤 자동 스크롤을 멈추는 시간
const AUTO_SCROLL_SETTLE = 700;   // 우리가 부드럽게 옮기는 동안의 scroll 이벤트는 무시합니다

/**
 * 스크립트 패널 (명세 4.2 · 4.3).
 *
 * @param {{
 *   segments: import('../timeline.js').Segment[],
 *   onSeekSentence: (index:number) => void,
 *   onSeekTime: (seconds:number) => void,
 *   onToggleBookmark: (segmentIndex:number) => void,
 *   onCopy: (segmentIndex:number) => void,
 *   onQuote: (segmentIndex:number) => void,
 *   isBookmarked: (segmentIndex:number) => boolean,
 * }} deps
 */
export function createScriptPanel({
  segments, onSeekSentence, onSeekTime, onToggleBookmark, onCopy, onQuote, isBookmarked,
}) {
  const element = document.createElement('section');
  element.className = 'script-pane';
  element.innerHTML = `
    <div class="pane-head">
      <h1>스크립트</h1>
      <div class="pane-tools">
        <button class="icon-btn" type="button" aria-label="스크립트 검색" data-toast="검색어를 입력해 주세요.">${icons.search}</button>
        <button class="icon-btn" type="button" aria-label="스크립트 표시 설정" data-toast="표시 설정을 열었습니다.">${icons.settings}</button>
      </div>
    </div>
    <div class="script-scroll">
      <div class="script-inner"></div>
    </div>
  `;

  const scroll = element.querySelector('.script-scroll');
  const inner = element.querySelector('.script-inner');

  /* ── 문단과 문장을 그립니다 ─────────────────────────────
     문장 하나하나가 별도의 인라인 요소여야 재생 위치에 따라
     문장 단위로 배경색을 입힐 수 있습니다. */
  const sentenceEls = [];
  const segmentEls = segments.map((segment, segmentIndex) => {
    const seg = document.createElement('div');
    seg.className = 'seg';
    seg.dataset.seg = String(segmentIndex);

    const time = document.createElement('button');
    time.type = 'button';
    time.className = 'seg-time';
    time.textContent = formatTime(segment.start);
    time.setAttribute('aria-label', `${formatTime(segment.start)} 위치부터 재생`);
    time.addEventListener('click', () => onSeekTime(segment.start));

    const body = document.createElement('p');
    body.className = 'seg-body';
    segment.items.forEach((item, i) => {
      if (i > 0) body.append(document.createTextNode(' '));
      const span = document.createElement('span');
      span.className = 'sent';
      span.textContent = item.text;
      span.dataset.index = String(item.index);
      // 클릭한 문장이 반드시 그 문장으로 잡히도록 반올림하지 않은 시각을 씁니다.
      span.addEventListener('click', () => onSeekSentence(item.index));
      body.append(span);
      sentenceEls[item.index] = span;
    });

    seg.append(time, body);
    inner.append(seg);
    return seg;
  });

  /* ── 문단 도구 모음 ───────────────────────────────────── */
  const toolbar = document.createElement('div');
  toolbar.className = 'seg-toolbar';
  toolbar.innerHTML = `
    <button type="button" data-act="bookmark" aria-label="북마크에 저장">${icons.bookmark}</button>
    <button type="button" data-act="copy" aria-label="문단 복사">${icons.copy}</button>
    <button type="button" data-act="quote" aria-label="보드챗으로 보내기">${icons.quote}</button>
  `;
  scroll.append(toolbar);

  const bookmarkBtn = toolbar.querySelector('[data-act="bookmark"]');
  let hoverSeg = null;
  let hideTimer = null;

  function showToolbar(seg) {
    hoverSeg = seg;
    clearTimeout(hideTimer);
    toolbar.classList.add('is-open');   // 폭을 재려면 먼저 보여야 합니다
    toolbar.style.top = `${seg.offsetTop - 6}px`;
    toolbar.style.left = `${Math.max(0, seg.offsetLeft + seg.offsetWidth - toolbar.offsetWidth - 6)}px`;
    bookmarkBtn.classList.toggle('is-on', isBookmarked(Number(seg.dataset.seg)));
  }

  function scheduleHide() {
    clearTimeout(hideTimer);
    hideTimer = setTimeout(() => {
      toolbar.classList.remove('is-open');
      hoverSeg = null;
    }, TOOLBAR_HIDE_DELAY);
  }

  inner.addEventListener('mouseover', (event) => {
    const seg = event.target.closest('.seg');
    if (seg && seg !== hoverSeg) showToolbar(seg);
  });
  inner.addEventListener('mouseleave', scheduleHide);
  toolbar.addEventListener('mouseenter', () => clearTimeout(hideTimer));
  toolbar.addEventListener('mouseleave', scheduleHide);

  toolbar.addEventListener('click', (event) => {
    const btn = event.target.closest('button');
    if (!btn || !hoverSeg) return;
    const segmentIndex = Number(hoverSeg.dataset.seg);
    if (btn.dataset.act === 'bookmark') onToggleBookmark(segmentIndex);
    else if (btn.dataset.act === 'copy') onCopy(segmentIndex);
    else onQuote(segmentIndex);
  });

  /* ── 자동 스크롤 ──────────────────────────────────────
     읽고 있는 위치가 갑자기 끌려가지 않도록, 사용자가 직접 스크롤하면
     그 뒤 3.5초 동안은 따라가지 않습니다. */
  let lastUserScroll = 0;
  let autoScrollUntil = 0;
  scroll.addEventListener('scroll', () => {
    // 우리가 옮기는 중에 생긴 scroll 이벤트는 사용자의 스크롤로 세지 않습니다.
    if (Date.now() < autoScrollUntil) return;
    lastUserScroll = Date.now();
  }, { passive: true });

  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  let activeIndex = -1;

  return {
    element,

    /** 지금 재생 중인 문장을 표시하고, 필요하면 화면 가운데로 옮깁니다. */
    setActiveSentence(index) {
      if (index === activeIndex) return;
      if (activeIndex >= 0) sentenceEls[activeIndex]?.classList.remove('is-active');
      activeIndex = index;
      if (index < 0) return;

      const el = sentenceEls[index];
      el.classList.add('is-active');
      if (Date.now() - lastUserScroll <= USER_SCROLL_PAUSE) return;

      const behavior = reduceMotion.matches ? 'auto' : 'smooth';
      autoScrollUntil = Date.now() + (behavior === 'smooth' ? AUTO_SCROLL_SETTLE : 0);
      el.scrollIntoView({ block: 'center', behavior });
    },

    /** 위치를 옮긴 직후에는 곧바로 따라가게 합니다. */
    resumeAutoScroll() {
      lastUserScroll = 0;
    },

    /** 북마크한 문단의 문장 아래에 강조색 밑줄을 켜고 끕니다. */
    setBookmarked(segmentIndex, on) {
      segments[segmentIndex].items.forEach((item) => {
        sentenceEls[item.index].classList.toggle('is-bookmarked', on);
      });
      if (hoverSeg && Number(hoverSeg.dataset.seg) === segmentIndex) {
        bookmarkBtn.classList.toggle('is-on', on);
      }
    },

    segmentEls,
  };
}
