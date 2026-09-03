/* =========================================
   다글로 (daglo) 완전 대체 프론트엔드 로직
   ========================================= */

let currentFilter = "all"; // all, starred, processing, trash
let currentFolderId = null;
let currentBoard = null;
let folders = [];
let boards = [];
let selectedBoardIds = new Set();
let isUserEditing = false;
let isModified = false;
let currentActiveBlock = null;
let autoPollTimer = null;

const audioPlayer = document.getElementById("global-audio-element");
let currentUser = null;

// -----------------------------------------
// 0. 인증
// 세션이 만료되면 어느 요청이든 401을 받는다. 호출부마다 처리하는 대신
// fetch 를 한 번 감싸서 곧바로 로그인 페이지로 보낸다.
// -----------------------------------------
const rawFetch = window.fetch.bind(window);
let redirectingToLogin = false;

window.fetch = async function (...args) {
    const response = await rawFetch(...args);
    if (response.status === 401 && !redirectingToLogin) {
        redirectingToLogin = true;
        window.location.replace("/login");
    }
    return response;
};

async function loadCurrentUser() {
    try {
        const res = await fetch("/api/auth/me");
        if (!res.ok) return;
        currentUser = await res.json();
        const name = currentUser.display_name || currentUser.username;
        document.getElementById("user-name").textContent = name;
        document.getElementById("user-avatar").textContent = name.slice(0, 2);
        document.getElementById("user-badge").title = `${name} (${currentUser.username})`;
    } catch (e) {
        /* 인터셉터가 401을 처리하므로 여기서는 조용히 넘어간다 */
    }
}

function toggleUserMenu(event) {
    event.stopPropagation();
    document.getElementById("user-menu").classList.toggle("open");
}

function closeUserMenu() {
    document.getElementById("user-menu").classList.remove("open");
}

async function logout() {
    closeUserMenu();
    if (!confirm("로그아웃할까요?")) return;
    await fetch("/api/auth/logout", { method: "POST" });
    window.location.replace("/login");
}

function openPasswordModal() {
    closeUserMenu();
    document.getElementById("current-password-input").value = "";
    document.getElementById("new-password-input").value = "";
    document.getElementById("new-password-confirm-input").value = "";
    document.getElementById("password-modal").style.display = "flex";
    document.getElementById("current-password-input").focus();
}

function closePasswordModal() {
    document.getElementById("password-modal").style.display = "none";
}

async function submitPasswordChange() {
    const currentPassword = document.getElementById("current-password-input").value;
    const newPassword = document.getElementById("new-password-input").value;
    const confirmPassword = document.getElementById("new-password-confirm-input").value;

    if (newPassword !== confirmPassword) {
        showToast("새 비밀번호가 서로 일치하지 않습니다.");
        return;
    }
    if (newPassword.length < 8) {
        showToast("비밀번호는 8자 이상이어야 합니다.");
        return;
    }

    const res = await fetch("/api/auth/password", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ current_password: currentPassword, new_password: newPassword }),
    });

    if (res.ok) {
        closePasswordModal();
        showToast("비밀번호를 변경했습니다.");
    } else {
        const data = await res.json().catch(() => ({}));
        showToast(data.detail || "비밀번호 변경에 실패했습니다.");
    }
}

// -----------------------------------------
// 초기화
// -----------------------------------------
document.addEventListener("DOMContentLoaded", () => {
    loadCurrentUser();
    loadFolders();
    loadBoards();
    setupAudioListeners();
    setupKeyboardShortcuts();
    startProcessingPoller();
    document.addEventListener("click", closeUserMenu);
});

function showToast(msg) {
    const toast = document.getElementById("toast");
    toast.textContent = msg;
    toast.classList.add("show");
    setTimeout(() => { toast.classList.remove("show"); }, 2200);
}

// -----------------------------------------
// 1. 필터 및 폴더 관리
// -----------------------------------------
function changeFilter(filterType) {
    currentFilter = filterType;
    currentFolderId = null;

    document.querySelectorAll(".sidebar-nav .nav-item").forEach(el => el.classList.remove("active"));
    document.querySelectorAll(".folder-item").forEach(el => el.classList.remove("active"));

    const navMap = {
        "all": "nav-all",
        "starred": "nav-starred",
        "processing": "nav-processing",
        "trash": "nav-trash"
    };
    if (navMap[filterType]) {
        document.getElementById(navMap[filterType]).classList.add("active");
    }

    const titleMap = {
        "all": "전체 보드",
        "starred": "중요 보드",
        "processing": "미완료 / 변환 중 녹음",
        "trash": "휴지통"
    };
    document.getElementById("current-folder-title").textContent = titleMap[filterType] || "보드 목록";

    // 휴지통 버튼 텍스트 변경
    const delBtn = document.getElementById("batch-del-btn");
    if (filterType === "trash") {
        delBtn.innerHTML = `<i class="fa-solid fa-trash-can"></i> 완전 삭제`;
    } else {
        delBtn.innerHTML = `<i class="fa-solid fa-trash"></i> 삭제`;
    }

    loadBoards();
}

async function loadFolders() {
    try {
        const res = await fetch("/api/folders");
        folders = await res.json();
        renderFolderList();
    } catch (e) {
        console.error("폴더 로드 실패:", e);
    }
}

function renderFolderList() {
    const container = document.getElementById("folder-list");
    container.innerHTML = "";

    folders.forEach(f => {
        const item = document.createElement("div");
        item.className = `folder-item ${currentFolderId === f.id ? "active" : ""}`;
        item.onclick = () => selectFolder(f.id, f.name);
        item.innerHTML = `
            <div class="folder-info">
                <i class="fa-solid fa-folder"></i>
                <span>${escapeHtml(f.name)}</span>
            </div>
            <span class="badge">${f.board_count || 0}</span>
        `;
        container.appendChild(item);
    });

    const uploadSelect = document.getElementById("upload-folder-select");
    if (uploadSelect) {
        uploadSelect.innerHTML = folders.map(f => `<option value="${f.id}">${escapeHtml(f.name)}</option>`).join("");
    }
}

function selectFolder(folderId, folderName) {
    currentFolderId = folderId;
    currentFilter = "folder";

    document.querySelectorAll(".sidebar-nav .nav-item").forEach(el => el.classList.remove("active"));
    document.getElementById("current-folder-title").textContent = folderName;
    renderFolderList();
    loadBoards();
}

function showDashboard() {
    document.getElementById("dashboard-view").style.display = "flex";
    document.getElementById("board-detail-view").style.display = "none";
    loadBoards();
    loadFolders();
}

// -----------------------------------------
// 2. 보드 목록 로드 및 렌더링
// -----------------------------------------
async function loadBoards(searchQuery = "") {
    try {
        let url = "/api/boards";
        const params = new URLSearchParams();

        if (currentFolderId) {
            params.append("folder_id", currentFolderId);
        } else if (currentFilter) {
            params.append("filter_type", currentFilter);
        }

        if (searchQuery.trim()) {
            params.append("search", searchQuery.trim());
        }

        if ([...params].length > 0) url += "?" + params.toString();

        const res = await fetch(url);
        boards = await res.json();
        renderBoardsTable();
    } catch (e) {
        console.error("보드 목록 로드 실패:", e);
    }
}

function renderBoardsTable() {
    const tbody = document.getElementById("boards-table-body");
    const emptyMsg = document.getElementById("no-boards-msg");
    const countBadge = document.getElementById("boards-total-count");
    tbody.innerHTML = "";

    countBadge.textContent = boards.length;

    if (boards.length === 0) {
        emptyMsg.style.display = "block";
        return;
    }
    emptyMsg.style.display = "none";

    boards.forEach(b => {
        const tr = document.createElement("tr");
        const isChecked = selectedBoardIds.has(b.id);
        const tagsHtml = (b.keywords || []).slice(0, 5).map(kw => `<span class="tag-pill"># ${escapeHtml(kw)}</span>`).join("");

        let statusBadge = "";
        if (b.status === "PROCESSING") {
            statusBadge = `<span class="status-tag processing"><i class="fa-solid fa-spinner fa-spin"></i> 변환 중 (${b.progress_percent || 0}%)</span>`;
        } else if (b.status === "PENDING") {
            statusBadge = `<span class="status-tag pending"><i class="fa-regular fa-hourglass-half"></i> 변환 대기 중</span>`;
        } else if (b.status === "FAILED") {
            const reason = b.error_message ? escapeHtml(b.error_message) : "알 수 없는 오류";
            statusBadge = `<span class="status-tag failed" title="${reason}"><i class="fa-solid fa-circle-exclamation"></i> 실패</span>`;
        }

        let actionButtons = "";
        if (currentFilter === "trash") {
            actionButtons = `
                <button class="icon-btn-small" onclick="restoreBoard(${b.id})" title="복원"><i class="fa-solid fa-rotate-left"></i></button>
                <button class="icon-btn-small" onclick="deleteBoardPermanent(${b.id})" title="완전 삭제"><i class="fa-solid fa-xmark"></i></button>
            `;
        } else {
            const retryBtn = (b.status === "FAILED" && b.has_audio)
                ? `<button class="icon-btn-small" onclick="reprocessBoard(event, ${b.id})" title="변환 다시 시도"><i class="fa-solid fa-rotate-right"></i></button>`
                : "";
            actionButtons = `
                ${retryBtn}
                <button class="icon-btn-small" onclick="deleteBoard(${b.id})" title="삭제"><i class="fa-regular fa-trash-can"></i></button>
            `;
        }

        tr.innerHTML = `
            <td onclick="event.stopPropagation()">
                <input type="checkbox" ${isChecked ? "checked" : ""} onchange="toggleSelectBoard(${b.id}, this.checked)">
            </td>
            <td class="star-cell ${b.is_starred ? 'starred' : ''}" onclick="toggleStarRow(event, ${b.id})">
                <i class="${b.is_starred ? 'fa-solid' : 'fa-regular'} fa-star"></i>
            </td>
            <td>
                <div class="board-title-cell">
                    <span class="board-title-text">
                        ${escapeHtml(b.title)}
                        ${statusBadge}
                    </span>
                    <div class="board-tags-row">${tagsHtml}</div>
                </div>
            </td>
            <td><span style="color: var(--text-muted); font-size:13px;">${b.duration_str}</span></td>
            <td><span style="color: var(--text-muted); font-size:13px;"><i class="fa-solid fa-folder" style="color: #f59e0b; margin-right:4px;"></i>${escapeHtml(b.folder_name)}</span></td>
            <td><span style="color: var(--text-subtle); font-size:12.5px;">${b.created_at}</span></td>
            <td onclick="event.stopPropagation()">
                ${actionButtons}
            </td>
        `;
        tr.onclick = () => openBoardDetail(b.id);
        tbody.appendChild(tr);
    });

    updateBatchActionBar();
}

function handleSearch(query) {
    loadBoards(query);
}

// -----------------------------------------
// 3. 다중 선택 및 보드 작업
// -----------------------------------------
function toggleSelectBoard(boardId, checked) {
    if (checked) selectedBoardIds.add(boardId);
    else selectedBoardIds.delete(boardId);
    updateBatchActionBar();
}

function toggleSelectAll(checkbox) {
    if (checkbox.checked) {
        boards.forEach(b => selectedBoardIds.add(b.id));
    } else {
        selectedBoardIds.clear();
    }
    renderBoardsTable();
}

function updateBatchActionBar() {
    const bar = document.getElementById("batch-actions");
    const countSpan = document.getElementById("selected-count");
    if (selectedBoardIds.size > 0) {
        bar.style.display = "flex";
        countSpan.textContent = `${selectedBoardIds.size}개 선택됨`;
    } else {
        bar.style.display = "none";
    }
}

async function toggleStarRow(event, boardId) {
    event.stopPropagation();
    try {
        await fetch(`/api/boards/${boardId}/star`, { method: "POST" });
        loadBoards();
    } catch (e) {}
}

async function deleteBoard(boardId) {
    if (!confirm("이 보드를 휴지통으로 이동하시겠습니까?")) return;
    await fetch(`/api/boards/${boardId}`, { method: "DELETE" });
    showToast("휴지통으로 이동되었습니다.");
    loadBoards();
    loadFolders();
}

async function reprocessBoard(event, boardId) {
    if (event) event.stopPropagation();
    try {
        const res = await fetch(`/api/boards/${boardId}/reprocess`, { method: "POST" });
        const data = await res.json();
        if (!res.ok) {
            alert(data.detail || "다시 변환할 수 없습니다.");
            return;
        }
        showToast("변환 대기열에 다시 넣었습니다.");
        loadBoards();
    } catch (e) {
        alert("재시도 요청에 실패했습니다.");
    }
}

async function restoreBoard(boardId) {
    await fetch(`/api/boards/${boardId}/restore`, { method: "POST" });
    showToast("보드가 복원되었습니다.");
    loadBoards();
    loadFolders();
}

async function deleteBoardPermanent(boardId) {
    if (!confirm("이 보드를 영구적으로 삭제하시겠습니까? 되돌릴 수 없습니다.")) return;
    await fetch(`/api/boards/${boardId}?permanent=true`, { method: "DELETE" });
    showToast("영구 삭제되었습니다.");
    loadBoards();
}

async function batchDeleteBoards() {
    const isTrash = currentFilter === "trash";
    const msg = isTrash ? `선택한 ${selectedBoardIds.size}개의 보드를 영구 삭제하시겠습니까?` : `선택한 ${selectedBoardIds.size}개의 보드를 휴지통으로 이동하시겠습니까?`;
    if (!confirm(msg)) return;

    await fetch("/api/boards/batch-delete", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ board_ids: Array.from(selectedBoardIds), permanent: isTrash })
    });
    selectedBoardIds.clear();
    showToast(isTrash ? "영구 삭제되었습니다." : "휴지통으로 이동되었습니다.");
    loadBoards();
    loadFolders();
}

async function batchMoveBoards() {
    const targetFolderId = prompt("이동할 대상 폴더의 번호(ID)를 입력하세요:\n" + folders.map(f => `${f.id}: ${f.name}`).join("\n"));
    if (!targetFolderId) return;

    await fetch("/api/boards/batch-move", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ board_ids: Array.from(selectedBoardIds), folder_id: parseInt(targetFolderId) })
    });
    selectedBoardIds.clear();
    showToast("폴더가 이동되었습니다.");
    loadBoards();
    loadFolders();
}

// -----------------------------------------
// 4. 보드 상세 뷰 (2-패널)
// -----------------------------------------
async function openBoardDetail(boardId) {
    try {
        const res = await fetch(`/api/boards/${boardId}`);
        currentBoard = await res.json();

        document.getElementById("dashboard-view").style.display = "none";
        document.getElementById("board-detail-view").style.display = "flex";

        document.getElementById("detail-folder-name").textContent = currentBoard.folder_name;
        document.getElementById("detail-title").textContent = currentBoard.title;

        // 스타 버튼 상태
        const starBtn = document.getElementById("detail-star-btn");
        if (currentBoard.is_starred) {
            starBtn.classList.add("starred");
            starBtn.innerHTML = `<i class="fa-solid fa-star"></i>`;
        } else {
            starBtn.classList.remove("starred");
            starBtn.innerHTML = `<i class="fa-regular fa-star"></i>`;
        }

        renderDetailStatus(currentBoard);
        renderKeywords(currentBoard.keywords || []);
        renderTranscript(currentBoard.segments || []);

        if (currentBoard.audio_url) {
            audioPlayer.src = currentBoard.audio_url;
            audioPlayer.load();
        } else {
            audioPlayer.src = "";
        }

        loadSummariesTab();
        loadChatHistory();
        renderBookmarks(currentBoard.bookmarks || []);

        document.getElementById("transcript-container").scrollTop = 0;
    } catch (e) {
        console.error("보드 상세 로드 실패:", e);
    }
}

async function toggleDetailStar() {
    if (!currentBoard) return;
    const res = await fetch(`/api/boards/${currentBoard.id}/star`, { method: "POST" });
    const data = await res.json();
    currentBoard.is_starred = data.is_starred;
    const starBtn = document.getElementById("detail-star-btn");
    if (data.is_starred) {
        starBtn.classList.add("starred");
        starBtn.innerHTML = `<i class="fa-solid fa-star"></i>`;
        showToast("중요 보드로 설정되었습니다.");
    } else {
        starBtn.classList.remove("starred");
        starBtn.innerHTML = `<i class="fa-regular fa-star"></i>`;
        showToast("중요 보드가 해제되었습니다.");
    }
}

function renderKeywords(keywords) {
    const container = document.getElementById("keywords-container");
    container.innerHTML = "";
    if (!keywords || keywords.length === 0) {
        container.innerHTML = `<span style="font-size:12px; color:var(--text-subtle);">추출된 키워드가 없습니다.</span>`;
        return;
    }
    keywords.forEach(kw => {
        const pill = document.createElement("span");
        pill.className = "keyword-pill-interactive";
        pill.textContent = "# " + kw;
        pill.onclick = () => highlightKeywordInTranscript(kw);
        container.appendChild(pill);
    });
}

function renderTranscript(segments) {
    const container = document.getElementById("transcript-container");
    container.innerHTML = "";

    if (segments.length === 0) {
        container.innerHTML = `<p style="color:var(--text-subtle); padding:40px; text-align:center;">스크립트 내용이 없습니다.</p>`;
        return;
    }

    segments.forEach((seg, idx) => {
        const block = document.createElement("div");
        block.className = "script-block";
        block.dataset.ms = seg.start_time_ms;
        block.dataset.ts = seg.timestamp_str;
        block.dataset.speaker = seg.speaker || "화자 1";
        block.id = `seg-${seg.id || idx}`;

        block.innerHTML = `
            <div class="block-meta">
                <span class="ts-badge" onclick="playAtMs(${seg.start_time_ms})">${seg.timestamp_str}</span>
                <span class="speaker-badge" onclick="openSpeakerModalFor('${escapeHtml(seg.speaker || "화자 1")}')">${escapeHtml(seg.speaker || "화자 1")}</span>
            </div>
            <div class="text-content" contenteditable="true" spellcheck="false">${escapeHtml(seg.content)}</div>
            <div class="block-actions">
                <button class="icon-btn-small" onclick="addBookmarkAtMs(${seg.start_time_ms}, '${seg.timestamp_str}')" title="이 위치 북마크"><i class="fa-regular fa-bookmark"></i></button>
            </div>
        `;

        const textElem = block.querySelector(".text-content");
        textElem.addEventListener("focus", () => { isUserEditing = true; });
        textElem.addEventListener("input", () => { isModified = true; });
        textElem.addEventListener("blur", () => {
            isUserEditing = false;
            if (isModified) {
                saveTranscriptChanges();
                isModified = false;
            }
        });

        container.appendChild(block);
    });
}

function highlightKeywordInTranscript(kw) {
    document.getElementById("script-find-input").value = kw;
    findInTranscript(kw);
}

function findInTranscript(query) {
    const q = query.trim().toLowerCase();
    const blocks = document.querySelectorAll(".script-block");
    if (!q) {
        blocks.forEach(b => b.style.opacity = "1");
        return;
    }

    let firstFound = null;
    blocks.forEach(b => {
        const text = b.querySelector(".text-content").innerText.toLowerCase();
        if (text.includes(q)) {
            b.style.opacity = "1";
            b.classList.add("active");
            if (!firstFound) firstFound = b;
        } else {
            b.style.opacity = "0.4";
            b.classList.remove("active");
        }
    });

    if (firstFound) {
        firstFound.scrollIntoView({ behavior: "smooth", block: "center" });
    }
}

function clearScriptFind() {
    document.getElementById("script-find-input").value = "";
    findInTranscript("");
}

async function saveBoardTitle(newTitle) {
    if (!currentBoard || !newTitle.trim()) return;
    await fetch(`/api/boards/${currentBoard.id}`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ title: newTitle.trim() })
    });
    showToast("제목이 변경되었습니다.");
}

async function saveTranscriptChanges() {
    if (!currentBoard) return;
    const segments = [];
    document.querySelectorAll(".script-block").forEach((block, idx) => {
        const ms = parseInt(block.dataset.ms) || 0;
        const ts = block.dataset.ts || "[00:00]";
        const spk = block.dataset.speaker || "화자 1";
        const content = block.querySelector(".text-content").innerText.trim();
        segments.push({
            start_time_ms: ms,
            end_time_ms: ms + 10000,
            timestamp_str: ts,
            speaker: spk,
            content: content,
            sequence: idx
        });
    });

    try {
        await fetch(`/api/boards/${currentBoard.id}/transcript`, {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ segments })
        });
        showToast("저장되었습니다.");
    } catch (e) {
        console.error("저장 실패:", e);
    }
}

async function regenerateKeywords() {
    if (!currentBoard) return;
    showToast("AI가 키워드를 새로 추출하는 중...");
    try {
        const res = await fetch(`/api/boards/${currentBoard.id}/keywords/generate`, { method: "POST" });
        const data = await res.json();
        renderKeywords(data.keywords);
        showToast("키워드가 갱신되었습니다.");
    } catch (e) {
        alert("키워드 추출 실패");
    }
}

async function deleteCurrentBoard() {
    if (!currentBoard) return;
    if (!confirm("이 보드를 휴지통으로 이동하시겠습니까?")) return;
    await fetch(`/api/boards/${currentBoard.id}`, { method: "DELETE" });
    showDashboard();
    showToast("삭제되었습니다.");
}

// -----------------------------------------
// 5. 오디오 플레이어 컨트롤
// -----------------------------------------
function setupAudioListeners() {
    audioPlayer.addEventListener("timeupdate", () => {
        const currSec = audioPlayer.currentTime;
        const durSec = audioPlayer.duration || currentBoard?.duration_seconds || 0;

        document.getElementById("curr-time").textContent = formatTime(currSec);
        document.getElementById("total-time").textContent = formatTime(durSec);

        if (durSec > 0) {
            document.getElementById("seek-bar").value = (currSec / durSec) * 100;
        }

        const currMs = currSec * 1000;
        const blocks = document.querySelectorAll(".script-block[data-ms]");
        let active = null;
        for (let i = 0; i < blocks.length; i++) {
            if (parseFloat(blocks[i].dataset.ms) <= currMs) {
                active = blocks[i];
            }
        }

        if (active && active !== currentActiveBlock) {
            if (currentActiveBlock) currentActiveBlock.classList.remove("active");
            active.classList.add("active");
            currentActiveBlock = active;
            if (!isUserEditing) {
                active.scrollIntoView({ behavior: "smooth", block: "center" });
            }
        }
    });

    audioPlayer.addEventListener("play", () => {
        document.getElementById("play-icon").className = "fa-solid fa-pause";
    });

    audioPlayer.addEventListener("pause", () => {
        document.getElementById("play-icon").className = "fa-solid fa-play";
    });
}

function togglePlay() {
    if (audioPlayer.paused) audioPlayer.play();
    else audioPlayer.pause();
}

function playAtMs(ms) {
    audioPlayer.currentTime = ms / 1000;
    audioPlayer.play();
}

function seekRelative(seconds) {
    audioPlayer.currentTime = Math.max(0, Math.min(audioPlayer.duration || 999999, audioPlayer.currentTime + seconds));
}

function onSeekInput(percent) {
    const durSec = audioPlayer.duration || currentBoard?.duration_seconds || 0;
    const targetSec = (percent / 100) * durSec;
    document.getElementById("curr-time").textContent = formatTime(targetSec);
}

function onSeekChange(percent) {
    const durSec = audioPlayer.duration || currentBoard?.duration_seconds || 0;
    audioPlayer.currentTime = (percent / 100) * durSec;
}

function changeSpeed(val) {
    audioPlayer.playbackRate = parseFloat(val);
}

function formatTime(secs) {
    const s = Math.floor(secs || 0);
    const m = Math.floor(s / 60);
    const remS = s % 60;
    return `${String(m).padStart(2, '0')}:${String(remS).padStart(2, '0')}`;
}

function setupKeyboardShortcuts() {
    document.addEventListener("keydown", (e) => {
        if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "s") {
            e.preventDefault();
            saveTranscriptChanges();
        }
        if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "b") {
            e.preventDefault();
            addCurrentBookmark();
        }
        if (e.key === "F1") { e.preventDefault(); seekRelative(-5); }
        if (e.key === "F2") { e.preventDefault(); togglePlay(); }
        if (e.key === "F3") { e.preventDefault(); seekRelative(5); }
        if (e.key === "Escape" && document.getElementById("board-detail-view").style.display === "flex") {
            showDashboard();
        }
    });
}

// -----------------------------------------
// 6. AI 어시스턴트 패널
// -----------------------------------------
function switchAiTab(tabName) {
    document.querySelectorAll(".ai-tab").forEach(t => {
        t.classList.toggle("active", t.dataset.tab === tabName);
    });
    document.querySelectorAll(".ai-tab-content").forEach(c => {
        c.classList.toggle("active", c.id === `ai-tab-${tabName}`);
    });
}

async function loadChatHistory() {
    if (!currentBoard) return;
    const container = document.getElementById("chat-messages");
    container.innerHTML = `
        <div class="chat-bubble assistant welcome">
            <div class="chat-avatar"><i class="fa-solid fa-robot"></i></div>
            <div class="chat-text">
                안녕하세요! <strong>${escapeHtml(currentBoard.title)}</strong> 녹음 내용을 모두 학습했습니다.<br>
                궁금한 점이 있거나 요약이 필요하시면 무엇이든 물어보세요!
            </div>
        </div>
    `;

    try {
        const res = await fetch(`/api/boards/${currentBoard.id}/chats`);
        const history = await res.json();
        history.forEach(c => appendChatMessage(c.role, c.message));
    } catch (e) {
        console.error("채팅 히스토리 로드 실패:", e);
    }
}

function handleChatKeyDown(e) {
    if (e.key === "Enter" && !e.shiftKey) {
        e.preventDefault();
        sendChatMessage();
    }
}

function sendQuickPrompt(promptText) {
    document.getElementById("chat-input").value = promptText;
    sendChatMessage();
}

function parseTimestampsInText(rawText) {
    // [MM:SS] 링크로 감싸서 클릭 시 playAtMs() 연동
    return rawText.replace(/\[(\d{2}:\d{2}(?::\d{2})?)\]/g, (match, ts) => {
        const parts = ts.split(':').map(Number);
        const sec = parts.length === 2 ? parts[0] * 60 + parts[1] : parts[0] * 3600 + parts[1] * 60 + parts[2];
        return `<a class="chat-ts-link" onclick="playAtMs(${sec * 1000})">${match}</a>`;
    });
}

async function sendChatMessage() {
    const input = document.getElementById("chat-input");
    const msg = input.value.trim();
    if (!msg || !currentBoard) return;

    input.value = "";
    appendChatMessage("user", msg);

    const assistantBubble = appendChatMessage("assistant", "");
    const textContainer = assistantBubble.querySelector(".chat-text");
    textContainer.innerHTML = `<i class="fa-solid fa-spinner fa-spin"></i> 답변 생성 중...`;

    try {
        const response = await fetch(`/api/boards/${currentBoard.id}/chat`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ message: msg })
        });

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let fullReply = "";
        textContainer.innerHTML = "";

        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            const chunk = decoder.decode(value);
            const lines = chunk.split("\n\n");

            for (const line of lines) {
                if (line.startsWith("data: ")) {
                    try {
                        const data = JSON.parse(line.substring(6));
                        if (data.text) {
                            fullReply += data.text;
                            const parsedHtml = marked.parse(fullReply);
                            textContainer.innerHTML = parseTimestampsInText(parsedHtml);
                            document.getElementById("chat-messages").scrollTop = document.getElementById("chat-messages").scrollHeight;
                        }
                    } catch (err) {}
                }
            }
        }
    } catch (e) {
        textContainer.innerHTML = `<span style="color:red;">답변을 불러오는 중 오류가 발생했습니다.</span>`;
    }
}

function appendChatMessage(role, text) {
    const container = document.getElementById("chat-messages");
    const bubble = document.createElement("div");
    bubble.className = `chat-bubble ${role}`;

    const icon = role === "user" ? "fa-user" : "fa-robot";
    bubble.innerHTML = `
        <div class="chat-avatar"><i class="fa-solid ${icon}"></i></div>
        <div class="chat-text">${text ? parseTimestampsInText(marked.parse(text)) : ""}</div>
    `;

    container.appendChild(bubble);
    container.scrollTop = container.scrollHeight;
    return bubble;
}

async function loadSummariesTab() {
    if (!currentBoard) return;
    const summaryContainer = document.getElementById("summary-content");
    const existing = (currentBoard.summaries || []).find(s => s.summary_type === "BASIC");
    if (existing && existing.content) {
        summaryContainer.innerHTML = marked.parse(existing.content);
    } else {
        summaryContainer.innerHTML = `<p class="placeholder-text">상단의 [AI 요약 생성] 버튼을 누르면 본문 전체를 3단 구조로 분석하여 요약합니다.</p>`;
    }
}

async function requestSummary(type) {
    if (!currentBoard) return;
    const targetElem = type === "BASIC" ? document.getElementById("summary-content") : document.getElementById("template-summary-content");
    targetElem.innerHTML = `<div style="text-align:center; padding:30px;"><i class="fa-solid fa-spinner fa-spin" style="font-size:24px; color:var(--primary-color);"></i><p style="margin-top:10px;">Gemini가 내용을 분석하여 요약을 작성 중입니다...</p></div>`;

    if (type !== "BASIC") switchAiTab("template");
    else switchAiTab("summary");

    try {
        const res = await fetch(`/api/boards/${currentBoard.id}/summary`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ summary_type: type })
        });
        const data = await res.json();
        targetElem.innerHTML = parseTimestampsInText(marked.parse(data.content));
    } catch (e) {
        targetElem.innerHTML = `<p style="color:red;">요약 생성 실패: ${e}</p>`;
    }
}

// -----------------------------------------
// 7. 북마크 & 화자 변경
// -----------------------------------------
function renderBookmarks(bookmarks) {
    const list = document.getElementById("bookmark-list");
    list.innerHTML = "";
    if (!bookmarks || bookmarks.length === 0) {
        list.innerHTML = `<p style="font-size:12.5px; color:var(--text-subtle); text-align:center; padding:20px;">저장된 북마크가 없습니다.</p>`;
        return;
    }

    bookmarks.forEach(bm => {
        const item = document.createElement("div");
        item.className = "bookmark-item";
        item.onclick = () => playAtMs(bm.timestamp_ms);
        item.innerHTML = `
            <span class="bookmark-time">${bm.timestamp_str}</span>
            <span class="bookmark-note">${escapeHtml(bm.note || "북마크")}</span>
            <button class="icon-btn-small" onclick="event.stopPropagation(); deleteBookmark(${bm.id})"><i class="fa-regular fa-trash-can"></i></button>
        `;
        list.appendChild(item);
    });
}

async function addCurrentBookmark() {
    if (!currentBoard) return;
    const ms = Math.floor((audioPlayer.currentTime || 0) * 1000);
    const tsStr = `[${formatTime(audioPlayer.currentTime || 0)}]`;
    const noteInput = document.getElementById("new-bookmark-note");
    const note = noteInput ? noteInput.value.trim() : "";

    await addBookmarkAtMs(ms, tsStr, note);
    if (noteInput) noteInput.value = "";
}

async function addBookmarkAtMs(ms, tsStr, note = "") {
    if (!currentBoard) return;
    try {
        const res = await fetch(`/api/boards/${currentBoard.id}/bookmarks`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ timestamp_ms: ms, timestamp_str: tsStr, note: note || "메모 없음" })
        });
        const newBm = await res.json();
        if (!currentBoard.bookmarks) currentBoard.bookmarks = [];
        currentBoard.bookmarks.push(newBm);
        renderBookmarks(currentBoard.bookmarks);
        showToast("북마크가 추가되었습니다.");
    } catch (e) {
        console.error("북마크 추가 실패:", e);
    }
}

async function deleteBookmark(bookmarkId) {
    await fetch(`/api/bookmarks/${bookmarkId}`, { method: "DELETE" });
    if (currentBoard && currentBoard.bookmarks) {
        currentBoard.bookmarks = currentBoard.bookmarks.filter(b => b.id !== bookmarkId);
        renderBookmarks(currentBoard.bookmarks);
    }
    showToast("북마크가 삭제되었습니다.");
}

function openSpeakerModalFor(speakerName) {
    document.getElementById("speaker-old-input").value = speakerName;
    document.getElementById("speaker-new-input").value = "";
    document.getElementById("speaker-modal").style.display = "flex";
    document.getElementById("speaker-new-input").focus();
}

function openSpeakerModal() {
    openSpeakerModalFor("화자 1");
}

function closeSpeakerModal() {
    document.getElementById("speaker-modal").style.display = "none";
}

async function submitRenameSpeaker() {
    if (!currentBoard) return;
    const oldName = document.getElementById("speaker-old-input").value.trim();
    const newName = document.getElementById("speaker-new-input").value.trim();
    if (!oldName || !newName) return;

    await fetch(`/api/boards/${currentBoard.id}/speakers/rename`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ old_name: oldName, new_name: newName })
    });

    closeSpeakerModal();
    showToast(`화자 이름이 '${newName}'(으)로 변경되었습니다.`);
    openBoardDetail(currentBoard.id);
}

// -----------------------------------------
// 8. 내보내기 & 업로드 모달
// -----------------------------------------
function openExportModal() {
    document.getElementById("export-modal").style.display = "flex";
}

function closeExportModal() {
    document.getElementById("export-modal").style.display = "none";
}

function exportFile(format) {
    if (!currentBoard) return;
    window.open(`/api/boards/${currentBoard.id}/export?format=${format}`, "_blank");
    closeExportModal();
}

function openNewFolderModal() {
    document.getElementById("folder-modal").style.display = "flex";
    document.getElementById("new-folder-input").focus();
}

function closeFolderModal() {
    document.getElementById("folder-modal").style.display = "none";
    document.getElementById("new-folder-input").value = "";
}

async function submitCreateFolder() {
    const name = document.getElementById("new-folder-input").value.trim();
    if (!name) return;
    try {
        const res = await fetch("/api/folders", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ name })
        });
        if (!res.ok) {
            const err = await res.json();
            alert(err.detail || "폴더 생성 실패");
            return;
        }
        closeFolderModal();
        showToast("새 폴더가 생성되었습니다.");
        loadFolders();
    } catch (e) {
        alert("폴더 생성 실패");
    }
}

function openUploadModal() {
    document.getElementById("upload-modal").style.display = "flex";
}

function closeUploadModal() {
    document.getElementById("upload-modal").style.display = "none";
    document.getElementById("audio-file-input").value = "";
    document.getElementById("file-chosen-text").textContent = "클릭하여 파일을 선택하세요";
}

function onFileSelected(input) {
    if (input.files && input.files[0]) {
        document.getElementById("file-chosen-text").textContent = input.files[0].name;
    }
}

async function submitAudioUpload() {
    const fileInput = document.getElementById("audio-file-input");
    const folderSelect = document.getElementById("upload-folder-select");
    if (!fileInput.files || !fileInput.files[0]) {
        alert("업로드할 음성 파일을 선택해주세요.");
        return;
    }

    const file = fileInput.files[0];
    const folderId = folderSelect.value;
    const formData = new FormData();
    formData.append("file", file);
    formData.append("folder_id", folderId);

    const btn = document.getElementById("start-upload-btn");
    btn.disabled = true;
    btn.innerHTML = `<i class="fa-solid fa-spinner fa-spin"></i> 업로드 중...`;

    try {
        await fetch("/api/boards/upload", {
            method: "POST",
            body: formData
        });
        closeUploadModal();
        showToast("파일이 업로드되었습니다. 백그라운드 AI 변환이 시작됩니다.");
        loadBoards();
        loadFolders();
    } catch (e) {
        alert("업로드 중 오류 발생");
    } finally {
        btn.disabled = false;
        btn.innerHTML = "업로드 및 변환 시작";
    }
}

// -----------------------------------------
// 9. 변환 중 보드 자동 폴링
// -----------------------------------------
const IN_FLIGHT_STATUSES = ["PROCESSING", "PENDING"];

function startProcessingPoller() {
    setInterval(async () => {
        const onDashboard = document.getElementById("dashboard-view").style.display !== "none";

        if (onDashboard) {
            // 변환 중/대기 중인 보드가 있으면 목록 상태를 주기적으로 새로 고친다
            if (boards.some(b => IN_FLIGHT_STATUSES.includes(b.status))) {
                loadBoards(document.getElementById("board-search-input").value || "");
            }
            return;
        }

        // 상세 화면에서 변환이 끝나면 스크립트/요약이 채워지므로 완료 시점에 한 번 다시 읽는다
        if (!currentBoard || !IN_FLIGHT_STATUSES.includes(currentBoard.status)) return;
        if (isUserEditing) return;
        try {
            const res = await fetch(`/api/boards/${currentBoard.id}`);
            const fresh = await res.json();
            const finished = !IN_FLIGHT_STATUSES.includes(fresh.status);
            currentBoard = fresh;
            renderDetailStatus(fresh);
            if (finished) {
                renderKeywords(fresh.keywords || []);
                renderTranscript(fresh.segments || []);
                loadSummariesTab();
                showToast(fresh.status === "COMPLETED" ? "변환이 완료되었습니다." : "변환에 실패했습니다.");
            }
        } catch (e) {
            console.error("상태 갱신 실패:", e);
        }
    }, 4000);
}

function renderDetailStatus(board) {
    const bar = document.getElementById("detail-status-bar");
    if (!bar) return;

    if (board.status === "PROCESSING" || board.status === "PENDING") {
        const pct = board.status === "PENDING" ? 0 : (board.progress_percent || 0);
        const label = board.status === "PENDING" ? "변환 대기 중" : `AI 변환 중 ${pct}%`;
        bar.style.display = "flex";
        bar.className = "detail-status-bar processing";
        bar.innerHTML = `
            <i class="fa-solid fa-spinner fa-spin"></i>
            <span>${label}</span>
            <div class="mini-progress"><div class="mini-progress-fill" style="width:${pct}%"></div></div>
        `;
    } else if (board.status === "FAILED") {
        bar.style.display = "flex";
        bar.className = "detail-status-bar failed";
        bar.innerHTML = `
            <i class="fa-solid fa-circle-exclamation"></i>
            <span>변환 실패: ${escapeHtml(board.error_message || "알 수 없는 오류")}</span>
            <button class="btn-sm" onclick="reprocessBoard(null, ${board.id})">
                <i class="fa-solid fa-rotate-right"></i> 다시 시도
            </button>
        `;
    } else {
        bar.style.display = "none";
        bar.innerHTML = "";
    }
}

function escapeHtml(str) {
    if (!str) return "";
    return str.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#039;");
}
