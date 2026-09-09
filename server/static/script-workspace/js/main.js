import { RECORDING, SEGMENTS } from './data.js';
import { buildTimeline, findSentenceIndex } from './timeline.js';
import { createPlayer } from './player.js';
import { createToast } from './toast.js';
import { createTopbar } from './components/topbar.js';
import { createScriptPanel } from './components/script-panel.js';
import { createSidePanel } from './components/side-panel.js';
import { createPlayerBar } from './components/player-bar.js';

const NARROW = window.matchMedia('(max-width: 860px)');

const { segments, sentences } = buildTimeline(SEGMENTS);

/* ── 재생 상태 ─────────────────────────────────────────── */
const player = createPlayer({
  duration: RECORDING.duration,
  time: RECORDING.initialTime,
  playing: true,
});

/* ── 북마크 ────────────────────────────────────────────── */
const bookmarks = new Set();

function bookmarkItems() {
  return [...bookmarks].sort((a, b) => a - b).map((segmentIndex) => ({
    segmentIndex,
    start: segments[segmentIndex].start,
    text: segments[segmentIndex].sentences[0],
  }));
}

function toggleBookmark(segmentIndex) {
  const on = !bookmarks.has(segmentIndex);
  if (on) bookmarks.add(segmentIndex);
  else bookmarks.delete(segmentIndex);
  scriptPanel.setBookmarked(segmentIndex, on);
  sidePanel.renderBookmarks(bookmarkItems());
  toast.show(on ? '북마크에 저장했습니다.' : '북마크에서 삭제했습니다.');
}

/* ── 위치 이동 ─────────────────────────────────────────── */
function seekTo(seconds, { fromScript = true } = {}) {
  player.seek(seconds);
  player.play();
  scriptPanel.resumeAutoScroll();
  if (fromScript && NARROW.matches) setPane('script');
}

/* ── 화면 조각 ─────────────────────────────────────────── */
const toast = createToast();

const topbar = createTopbar({
  recording: RECORDING,
  onToast: (msg) => toast.show(msg),
  onPane: setPane,
});

const scriptPanel = createScriptPanel({
  segments,
  isBookmarked: (i) => bookmarks.has(i),
  onSeekSentence: (index) => seekTo(sentences[index].start),
  onSeekTime: (seconds) => seekTo(seconds),
  onToggleBookmark: toggleBookmark,
  onCopy: async (segmentIndex) => {
    const text = segments[segmentIndex].sentences.join(' ');
    try {
      await navigator.clipboard.writeText(text);
      toast.show('문단을 복사했습니다.');
    } catch {
      toast.show('복사에 실패했습니다.');
    }
  },
  onQuote: (segmentIndex) => {
    // 보드챗 탭으로 전환하면서 질문을 입력창에 채워 둡니다.
    sidePanel.switchTab('chat');
    sidePanel.setDraft(`이 부분을 쉽게 풀어서 설명해 주세요: ${segments[segmentIndex].sentences[0]}`);
  },
});

const sidePanel = createSidePanel({
  onSeekTime: (seconds) => seekTo(seconds),
  onToast: (msg) => toast.show(msg),
  onTabChange: () => { if (NARROW.matches) setPane('ai'); },
  onRemoveBookmark: toggleBookmark,
});
sidePanel.renderBookmarks(bookmarkItems());

const playerBar = createPlayerBar({
  duration: RECORDING.duration,
  onSeek: (seconds) => seekTo(seconds, { fromScript: false }),
  onSeekRatio: (ratio) => seekTo(ratio * RECORDING.duration, { fromScript: false }),
  onToggle: () => player.toggle(),
  onNudge: (delta) => {
    player.nudge(delta);
    scriptPanel.resumeAutoScroll();
  },
  onSpeed: () => player.cycleSpeed(),
});

/* ── 조립 ──────────────────────────────────────────────── */
const app = document.createElement('div');
app.className = 'app';

const workspace = document.createElement('main');
workspace.className = 'workspace';
workspace.append(scriptPanel.element, sidePanel.element);

app.append(topbar.element, workspace, playerBar.element);
document.body.append(app, toast.element);

/* ── 재생 상태를 화면에 잇습니다 ───────────────────────── */
player.subscribe((state) => {
  playerBar.render(state);
  scriptPanel.setActiveSentence(findSentenceIndex(sentences, state.time));
});

/* ── 좁은 화면에서 한 번에 한 패널만 보여 줍니다 ───────── */
function setPane(name) {
  document.body.dataset.pane = name;
  topbar.setPane(name);
}
setPane('script');

/* ── 스페이스바로 재생과 정지를 전환합니다 ─────────────── */
document.addEventListener('keydown', (event) => {
  if (event.code !== 'Space') return;
  // 입력창에 초점이 있을 때는 동작하지 않습니다.
  // 버튼에 초점이 있을 때도 비켜서, 스페이스바가 그 버튼을 누르도록 둡니다.
  const tag = event.target.tagName?.toLowerCase();
  if (tag === 'textarea' || tag === 'input' || tag === 'button' || tag === 'a') return;
  if (event.target.isContentEditable) return;
  event.preventDefault();
  player.toggle();
});
