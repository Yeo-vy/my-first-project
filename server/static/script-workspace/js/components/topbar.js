import { icons } from '../icons.js';

/**
 * 상단 바 (명세 4.1).
 * 왼쪽부터 뒤로 가기, 출처 링크, 슬래시, 녹음 제목.
 * 오른쪽에는 슬라이드/퀴즈 만들기와 아이콘 버튼 세 개를 둡니다.
 *
 * @param {{recording:{source:string,title:string}, onToast:(msg:string)=>void, onPane:(name:string)=>void}} deps
 */
export function createTopbar({ recording, onToast, onPane }) {
  const element = document.createElement('header');
  element.className = 'topbar';
  element.innerHTML = `
    <button class="icon-btn" type="button" aria-label="이전 화면으로 이동" data-act="back">${icons.back}</button>

    <div class="crumb">
      <span class="crumb-src" title="${escapeAttr(recording.source)}">${escapeHtml(recording.source)}</span>
      <span class="crumb-sep" aria-hidden="true">/</span>
      <span class="crumb-title">${escapeHtml(recording.title)}</span>
    </div>

    <div class="pane-switch" role="group" aria-label="화면 전환">
      <button type="button" data-pane-btn="script" aria-pressed="true">스크립트</button>
      <button type="button" data-pane-btn="ai" aria-pressed="false">AI 패널</button>
    </div>

    <div class="top-actions">
      <button class="pill pill-gradient" type="button" data-toast="슬라이드를 만들고 있습니다.">
        ${icons.slide}<span>슬라이드 만들기</span>
      </button>
      <button class="pill pill-quiz" type="button" data-toast="퀴즈를 만들고 있습니다.">
        ${icons.quiz}<span>퀴즈 만들기</span>
      </button>
      <button class="icon-btn" type="button" aria-label="공유하기" data-toast="공유 링크를 복사했습니다.">${icons.share}</button>
      <button class="icon-btn" type="button" aria-label="내려받기" data-toast="스크립트를 내려받는 중입니다.">${icons.download}</button>
      <button class="icon-btn" type="button" aria-label="더 보기" data-toast="추가 메뉴를 열었습니다.">${icons.more}</button>
    </div>
  `;

  element.addEventListener('click', (event) => {
    const paneBtn = event.target.closest('[data-pane-btn]');
    if (paneBtn) onPane(paneBtn.dataset.paneBtn);

    const back = event.target.closest('[data-act="back"]');
    if (back) onToast('이전 화면으로 돌아갑니다.');

    const toastBtn = event.target.closest('[data-toast]');
    if (toastBtn) onToast(toastBtn.dataset.toast);
  });

  return {
    element,
    /** 좁은 화면에서 어느 패널을 보고 있는지 버튼에 표시합니다. */
    setPane(name) {
      element.querySelectorAll('[data-pane-btn]').forEach((btn) => {
        btn.setAttribute('aria-pressed', String(btn.dataset.paneBtn === name));
      });
    },
  };
}

function escapeHtml(text) {
  return String(text).replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));
}

function escapeAttr(text) {
  return escapeHtml(text).replace(/"/g, '&quot;');
}
