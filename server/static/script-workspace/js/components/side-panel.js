import { icons } from '../icons.js';
import { formatTime } from '../timeline.js';
import { CHIPS, ANSWERS, FALLBACK_ANSWER, SUMMARY, TEMPLATES } from '../data.js';

const TABS = [
  { id: 'chat', label: '보드챗' },
  { id: 'summary', label: '기본 요약' },
  { id: 'template', label: '템플릿 요약' },
  { id: 'bookmark', label: '북마크' },
];

/**
 * 우측 AI 패널 (명세 4.4).
 *
 * @param {{
 *   onSeekTime: (seconds:number) => void,
 *   onToast: (msg:string) => void,
 *   onTabChange: (id:string) => void,
 *   onRemoveBookmark: (segmentIndex:number) => void,
 * }} deps
 */
export function createSidePanel({ onSeekTime, onToast, onTabChange, onRemoveBookmark }) {
  const element = document.createElement('aside');
  element.className = 'side-pane';
  element.innerHTML = `
    <nav class="tabs" role="tablist" aria-label="AI 패널">
      ${TABS.map((tab, i) => `
        <button class="tab" type="button" role="tab" id="tab-${tab.id}"
                aria-controls="panel-${tab.id}" data-tab="${tab.id}"
                aria-selected="${i === 0}" tabindex="${i === 0 ? '0' : '-1'}">${tab.label}</button>
      `).join('')}
    </nav>

    <div class="tab-body" id="panel-chat" role="tabpanel" aria-labelledby="tab-chat">
      <div class="empty" data-chat-empty>
        <div class="logo-pair">
          <span class="logo-badge">${icons.slack(24)}</span>
          <span class="logo-badge">${icons.notion(24)}</span>
        </div>
        <h2><span class="thin">연결 한 번으로</span>Slack과 Notion에 바로 공유하세요</h2>
        <p>AI가 분석한 내용을 바로 공유하거나 액션 아이템으로 정리할 수 있어요</p>
        <div class="chips">
          ${CHIPS.map((chip) => `
            <button class="chip" type="button" ${chip.ask ? `data-ask="${chip.ask}"` : ''} ${chip.toast ? `data-toast="${chip.toast}"` : ''}>
              ${chip.logo ? icons[chip.logo](14) : `<span aria-hidden="true">${chip.emoji}</span>`}${chip.label}
            </button>
          `).join('')}
        </div>
      </div>
      <div class="thread" data-thread hidden></div>
    </div>

    <div class="tab-body" id="panel-summary" role="tabpanel" aria-labelledby="tab-summary" hidden>
      <div class="doc">
        <h3>한 문단 요약</h3>
        <p>${SUMMARY.paragraph}</p>
        <h3>핵심 내용</h3>
        <ul>
          ${SUMMARY.points.map(([at, text]) => `
            <li><button class="cite" type="button" data-seek="${at}" aria-label="${formatTime(at)} 위치로 이동">${formatTime(at)}</button>${text}</li>
          `).join('')}
        </ul>
        <h3>다시 확인하면 좋은 용어</h3>
        <ul>${SUMMARY.terms.map((t) => `<li>${t}</li>`).join('')}</ul>
      </div>
    </div>

    <div class="tab-body" id="panel-template" role="tabpanel" aria-labelledby="tab-template" hidden>
      <div class="doc">
        <p class="doc-lead">원하는 형식을 선택하면 스크립트 전체를 해당 형식으로 다시 정리합니다.</p>
        ${TEMPLATES.map((card) => `
          <button class="tpl-card" type="button" data-toast="${card.toast}">
            <span class="tpl-ico">${icons[card.icon]}</span>
            <span><strong>${card.title}</strong><span class="tpl-desc">${card.desc}</span></span>
          </button>
        `).join('')}
      </div>
    </div>

    <div class="tab-body" id="panel-bookmark" role="tabpanel" aria-labelledby="tab-bookmark" hidden>
      <div class="doc" data-bookmark-list></div>
    </div>

    <div class="composer-wrap" data-composer-wrap>
      <div class="composer">
        <button class="round-btn" type="button" aria-label="파일 첨부" data-toast="첨부할 파일을 선택해 주세요.">${icons.plus}</button>
        <textarea rows="1" data-chat-input aria-label="질문 입력" placeholder="궁금한 내용을 질문해 보세요"></textarea>
        <button class="round-btn send-btn" type="button" aria-label="질문 보내기" data-send>${icons.send}</button>
      </div>
    </div>
  `;

  const tabEls = [...element.querySelectorAll('.tab')];
  const panels = Object.fromEntries(TABS.map((t) => [t.id, element.querySelector(`#panel-${t.id}`)]));
  const chatPanel = panels.chat;
  const chatEmpty = element.querySelector('[data-chat-empty]');
  const thread = element.querySelector('[data-thread]');
  const bookmarkList = element.querySelector('[data-bookmark-list]');
  const composerWrap = element.querySelector('[data-composer-wrap]');
  const input = element.querySelector('[data-chat-input]');
  const sendBtn = element.querySelector('[data-send]');

  let activeTab = 'chat';

  function switchTab(id) {
    activeTab = id;
    TABS.forEach((tab) => { panels[tab.id].hidden = tab.id !== id; });
    tabEls.forEach((el) => {
      const selected = el.dataset.tab === id;
      el.setAttribute('aria-selected', String(selected));
      el.tabIndex = selected ? 0 : -1;
    });
    // 입력창은 보드챗 탭에서만 보입니다.
    composerWrap.hidden = id !== 'chat';
    onTabChange(id);
  }

  /* 탭 전환: 마우스와 키보드 양쪽 모두 */
  element.querySelector('.tabs').addEventListener('click', (event) => {
    const tab = event.target.closest('.tab');
    if (tab) switchTab(tab.dataset.tab);
  });
  element.querySelector('.tabs').addEventListener('keydown', (event) => {
    const step = { ArrowRight: 1, ArrowLeft: -1, Home: -Infinity, End: Infinity }[event.key];
    if (step === undefined) return;
    event.preventDefault();
    const at = tabEls.findIndex((el) => el.dataset.tab === activeTab);
    const next = step === -Infinity ? 0
      : step === Infinity ? tabEls.length - 1
      : (at + step + tabEls.length) % tabEls.length;
    switchTab(tabEls[next].dataset.tab);
    tabEls[next].focus();
  });

  /* 시각 표시(인용)를 누르면 그 위치로 이동합니다. */
  element.addEventListener('click', (event) => {
    const seekEl = event.target.closest('[data-seek]');
    if (seekEl) onSeekTime(Number(seekEl.dataset.seek));

    const toastEl = event.target.closest('[data-toast]');
    if (toastEl) onToast(toastEl.dataset.toast);

    const askEl = event.target.closest('[data-ask]');
    if (askEl) ask(askEl.dataset.ask);

    const delEl = event.target.closest('[data-del]');
    if (delEl) onRemoveBookmark(Number(delEl.dataset.del));
  });

  /* ── 보드챗 ─────────────────────────────────────────── */
  function ask(text) {
    chatEmpty.hidden = true;
    thread.hidden = false;

    const user = document.createElement('div');
    user.className = 'msg-user';
    user.textContent = text;
    thread.append(user);

    const answer = ANSWERS[text] || FALLBACK_ANSWER;
    const ai = document.createElement('div');
    ai.className = 'msg-ai';
    ai.innerHTML = `
      <div class="ai-head">${icons.spark}보드챗이 스크립트를 분석했습니다</div>
      <div>${answer.lead}</div>
      <ul>${answer.items.map(([at, line]) => `
        <li><button class="cite" type="button" data-seek="${at}" aria-label="${formatTime(at)} 위치로 이동">${formatTime(at)}</button> ${line}</li>
      `).join('')}</ul>
    `;
    thread.append(ai);
    chatPanel.scrollTop = chatPanel.scrollHeight;
  }

  function refreshSendBtn() {
    sendBtn.classList.toggle('is-ready', input.value.trim().length > 0);
    // 내용에 따라 늘어나되 120px(CSS의 max-height)을 넘지 않습니다.
    input.style.height = 'auto';
    input.style.height = `${Math.min(120, input.scrollHeight)}px`;
  }

  function send() {
    const value = input.value.trim();
    if (!value) return;
    input.value = '';
    refreshSendBtn();
    ask(value);
  }

  input.addEventListener('input', refreshSendBtn);
  input.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      send();
    }
  });
  sendBtn.addEventListener('click', send);

  /* ── 북마크 목록 ────────────────────────────────────── */
  function renderBookmarks(items) {
    if (!items.length) {
      bookmarkList.innerHTML = `
        <div class="empty-small">아직 저장한 북마크가 없습니다.<br>
        스크립트 문단에 마우스를 올린 뒤 북마크 아이콘을 누르면 이곳에 모아 둘 수 있습니다.</div>`;
      return;
    }
    bookmarkList.innerHTML = items.map(({ segmentIndex, start, text }) => `
      <div class="bm-item">
        <button class="bm-time" type="button" data-seek="${start}" aria-label="${formatTime(start)} 위치로 이동">${formatTime(start)}</button>
        <div class="bm-text">${text}</div>
        <button class="bm-del" type="button" data-del="${segmentIndex}" aria-label="북마크 삭제">${icons.close}</button>
      </div>
    `).join('');
  }

  return {
    element,
    switchTab,
    renderBookmarks,

    /** 문단을 보드챗으로 보낼 때: 질문을 입력창에 대신 채워 넣습니다. */
    setDraft(text) {
      input.value = text;
      refreshSendBtn();
      input.focus();
      input.setSelectionRange(input.value.length, input.value.length);
    },
  };
}
