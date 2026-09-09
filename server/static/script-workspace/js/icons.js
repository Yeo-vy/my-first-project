/**
 * 인라인 SVG 아이콘 모음.
 * 외부 아이콘 패키지를 새로 들이지 않고 여기에서만 관리합니다.
 */

const stroke = (d, size = 17, extra = '') =>
  `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" ${extra}>${d}</svg>`;

export const icons = {
  back: stroke('<path d="M15 5l-7 7 7 7"/>', 18, 'stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"'),

  slide: stroke('<rect x="3" y="4" width="18" height="13" rx="2"/><path d="M8 21h8"/>', 15),

  quiz: stroke(
    '<rect x="3" y="3" width="18" height="18" rx="3"/>' +
    '<path d="M9.2 9.3a2.8 2.8 0 1 1 3.1 3.9v1.1" stroke-linecap="round"/>' +
    '<circle cx="12.3" cy="17.3" r=".9" fill="currentColor" stroke="none"/>', 15),

  share: stroke(
    '<circle cx="18" cy="5.5" r="2.6"/><circle cx="6" cy="12" r="2.6"/><circle cx="18" cy="18.5" r="2.6"/>' +
    '<path d="M8.4 10.8 15.6 6.8M8.4 13.2l7.2 4"/>'),

  download: stroke('<path d="M12 4v10m0 0 4-4m-4 4-4-4M5 18.5h14"/>', 17, 'stroke-linecap="round"'),

  more: '<svg width="17" height="17" viewBox="0 0 24 24" fill="currentColor">' +
        '<circle cx="12" cy="5.5" r="1.6"/><circle cx="12" cy="12" r="1.6"/><circle cx="12" cy="18.5" r="1.6"/></svg>',

  search: stroke('<circle cx="11" cy="11" r="6.2"/><path d="m15.6 15.6 3.6 3.6"/>', 17, 'stroke-linecap="round"'),

  settings: stroke(
    '<circle cx="12" cy="12" r="3"/>' +
    '<path d="M19.2 14.4a1.6 1.6 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-2.7 1.1v.2a2 2 0 1 1-4 0v-.1a1.6 1.6 0 0 0-2.8-1.1l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.6 1.6 0 0 0-1.1-2.7h-.2a2 2 0 1 1 0-4h.1a1.6 1.6 0 0 0 1.1-2.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.6 1.6 0 0 0 1.8.3h.1A1.6 1.6 0 0 0 10 4.8v-.2a2 2 0 1 1 4 0v.1a1.6 1.6 0 0 0 2.7 1.1l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.6 1.6 0 0 0 1.1 2.7h.2a2 2 0 1 1 0 4h-.1a1.6 1.6 0 0 0-1.5 1.1z"/>'),

  /* 문단 도구 모음 — 왼쪽부터 북마크 저장, 문단 복사, 보드챗으로 보내기 */
  bookmark: stroke(
    '<path d="M6.5 4.5h11a1 1 0 0 1 1 1v14l-6.5-4-6.5 4v-14a1 1 0 0 1 1-1z"/><path d="M12 8.6v4M10 10.6h4"/>',
    17, 'stroke-width="1.6" stroke-linecap="round"'),

  copy: stroke(
    '<rect x="8.5" y="3.5" width="12" height="12" rx="2.4"/><rect x="3.5" y="8.5" width="12" height="12" rx="2.4"/>',
    17, 'stroke-width="1.6"'),

  quote:
    '<svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">' +
    '<rect x="3.5" y="7.5" width="12" height="13" rx="2.4"/>' +
    '<rect x="9.5" y="3.5" width="11" height="11" rx="2.4" fill="currentColor" stroke="none"/>' +
    '<text x="15" y="12.4" font-size="8.4" font-weight="700" text-anchor="middle" fill="#1a1c1e" stroke="none">A</text></svg>',

  close: stroke('<path d="M6 6l12 12M18 6 6 18"/>', 16, 'stroke-linecap="round"'),

  plus: stroke('<path d="M12 6v12M6 12h12"/>', 18, 'stroke-linecap="round"'),

  send: stroke('<path d="M12 19V6m0 0-5.5 5.5M12 6l5.5 5.5"/>', 18,
    'stroke-width="2" stroke-linecap="round" stroke-linejoin="round"'),

  spark: '<svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">' +
         '<path d="m12 3 1.9 5.4L19.5 10l-5.6 1.6L12 17l-1.9-5.4L4.5 10l5.6-1.6z"/></svg>',

  /* 재생 바 */
  pause: '<rect x="7" y="5" width="3.6" height="14" rx="1.2"/><rect x="13.4" y="5" width="3.6" height="14" rx="1.2"/>',
  play: '<path d="M8 5.2 18.4 12 8 18.8z"/>',

  /* 원형 화살표 안에 숫자 3. 되감기는 화살표만 좌우로 뒤집고 숫자는 그대로 둡니다. */
  seek3(mirrored) {
    const arrow =
      '<path d="M12 5.4a6.9 6.9 0 1 0 6.5 4.6" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/>' +
      '<path d="M11.7 2.4v6.2l-4.4-3.1z" fill="currentColor"/>';
    const body = mirrored ? `<g transform="translate(24 0) scale(-1 1)">${arrow}</g>` : arrow;
    return `<svg width="21" height="21" viewBox="0 0 24 24">${body}` +
      '<text x="12" y="15.8" text-anchor="middle" font-size="8.4" font-weight="700" fill="currentColor">3</text></svg>';
  },

  /* 템플릿 요약 카드 */
  note: stroke('<path d="M4 5.5h16v14H4z"/><path d="M8 9.5h8M8 13.5h8M8 17h5"/>', 17, 'stroke-width="1.6"'),
  people: stroke('<circle cx="9" cy="8" r="3"/><path d="M3.5 19c.6-3.2 2.8-4.8 5.5-4.8s4.9 1.6 5.5 4.8M16 6.5h5M16 10.5h5"/>', 17, 'stroke-width="1.6"'),
  checklist: stroke('<path d="M4 7.5h11M4 12h11M4 16.5h7"/><path d="m17.5 15.5 1.8 1.8 3.2-3.6"/>', 17, 'stroke-width="1.6" stroke-linecap="round"'),
  bubble: stroke('<path d="M20.5 15.5a2 2 0 0 1-2 2H8l-4.5 3.2V6a2 2 0 0 1 2-2h13a2 2 0 0 1 2 2z"/>', 17, 'stroke-width="1.6"'),

  /* 서비스 로고 */
  slack: (size = 24) =>
    `<svg width="${size}" height="${size}" viewBox="0 0 122 122">` +
    '<path fill="#E01E5A" d="M25.8 77.6a12.9 12.9 0 1 1-12.9-12.9h12.9zm6.5 0a12.9 12.9 0 0 1 25.8 0v32.3a12.9 12.9 0 0 1-25.8 0z"/>' +
    '<path fill="#36C5F0" d="M45.2 25.8a12.9 12.9 0 1 1 12.9-12.9v12.9zm0 6.5a12.9 12.9 0 0 1 0 25.8H12.9a12.9 12.9 0 0 1 0-25.8z"/>' +
    '<path fill="#2EB67D" d="M96.2 45.2a12.9 12.9 0 1 1 12.9 12.9H96.2zm-6.5 0a12.9 12.9 0 0 1-25.8 0V12.9a12.9 12.9 0 0 1 25.8 0z"/>' +
    '<path fill="#ECB22E" d="M76.8 96.2a12.9 12.9 0 1 1-12.9 12.9V96.2zm0-6.5a12.9 12.9 0 0 1 0-25.8h32.3a12.9 12.9 0 0 1 0 25.8z"/></svg>',

  notion: (size = 24) =>
    `<svg width="${size}" height="${size}" viewBox="0 0 24 24">` +
    '<rect x="2.5" y="2.5" width="19" height="19" rx="2.6" fill="#fff"/>' +
    '<path d="M7 7.6v9h1.9v-5.4l3.9 5.4h1.8v-9h-1.9v5.3L8.9 7.6z" fill="#111"/></svg>',
};
