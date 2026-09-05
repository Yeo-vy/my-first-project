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
let isRefreshing = false;

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
        window.location.replace("/");
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
        document.getElementById("user-menu-btn").title = `${name} (${currentUser.username})`;
    } catch (e) {
        /* 인터셉터가 401을 처리하므로 여기서는 조용히 넘어간다 */
    }
}

// -----------------------------------------
// 0-1. 브라우저 뒤로/앞으로 (History API)
// -----------------------------------------
// 화면 전환을 주소창 기록으로 남겨서 크롬의 뒤로가기 버튼이 앱 안에서도 동작하게 한다.
// 경로 대신 쿼리스트링(`/?board=3`)을 쓰는 이유: 서버 라우팅을 건드리지 않아도
// 새로고침·즐겨찾기·링크 공유가 그대로 동작한다.

function dashboardUrl(filter, folderId) {
    if (folderId) return `?folder=${folderId}`;
    if (filter && filter !== "all") return `?filter=${filter}`;
    return location.pathname;   // 전체 보드는 쿼리 없이 깔끔하게 둔다
}

function pushHistory(state, url) {
    const cur = history.state;
    // 같은 화면을 다시 그린 것뿐이면(화자 이름 변경 후 재로드 등) 기록을 늘리지 않는다.
    // 안 그러면 뒤로가기를 여러 번 눌러야 실제로 이전 화면이 나온다.
    if (cur && cur.view === state.view && cur.boardId === state.boardId
        && cur.filter === state.filter && cur.folderId === state.folderId) {
        return;
    }
    // depth: 우리가 이 세션에서 직접 쌓은 항목 수. 0이면 뒤로 갈 곳이 앱 밖이라는 뜻이라
    // 앱 안 뒤로가기 버튼이 history.back() 대신 목록을 직접 그려야 한다.
    state.depth = ((cur && cur.depth) || 0) + 1;
    history.pushState(state, "", url);
}

function onPopState(event) {
    const state = event.state;
    // record=false: 복원하는 중이므로 새 기록을 쌓지 않는다 (안 그러면 뒤로가기가 제자리걸음)
    if (state && state.view === "board") {
        openBoardDetail(state.boardId, false);
    } else if (state && state.folderId) {
        selectFolder(state.folderId, state.folderName || "폴더", false);
    } else {
        changeFilter((state && state.filter) || "all", false);
    }
}

async function applyInitialRoute() {
    // 주소창에 이미 들어 있는 상태(새로고침·공유 링크)를 첫 화면에 반영한다.
    const params = new URLSearchParams(location.search);
    const boardId = parseInt(params.get("board"), 10);
    const folderId = parseInt(params.get("folder"), 10);
    const filter = params.get("filter");

    if (Number.isInteger(boardId)) {
        history.replaceState({ view: "board", boardId, depth: 0 }, "", location.search);
        await openBoardDetail(boardId, false);
        return;
    }
    if (Number.isInteger(folderId)) {
        const found = folders.find(f => f.id === folderId);
        const name = found ? found.name : "폴더";
        history.replaceState(
            { view: "dashboard", filter: "folder", folderId, folderName: name, depth: 0 },
            "", location.search
        );
        selectFolder(folderId, name, false);
        return;
    }
    const initial = filter || "all";
    history.replaceState(
        { view: "dashboard", filter: initial, folderId: null, depth: 0 },
        "", dashboardUrl(initial, null)
    );
    changeFilter(initial, false);
}

function goHome() {
    // 로고를 누르면 상세 화면·검색어·필터를 모두 처음 상태로 되돌린다
    closeUserMenu();
    const search = document.getElementById("board-search-input");
    if (search) search.value = "";
    loadFolders();
    changeFilter("all");   // 전체 보드로 리셋하면서 목록도 다시 읽는다
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
    window.location.replace("/");
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
document.addEventListener("DOMContentLoaded", async () => {
    loadCurrentUser();
    setupAudioListeners();
    setupKeyboardShortcuts();
    startProcessingPoller();
    document.addEventListener("click", closeUserMenu);
    window.addEventListener("popstate", onPopState);
    // 폴더를 먼저 받아야 `?folder=3` 으로 들어왔을 때 폴더 이름을 제목에 띄울 수 있다.
    // 목록 로드는 applyInitialRoute 안의 changeFilter/selectFolder 가 맡는다 (중복 호출 방지).
    await loadFolders();
    applyInitialRoute();
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
function changeFilter(filterType, record = true) {
    exitDetailView();          // 상세 화면에서 눌렀다면 목록으로 먼저 나온다
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
    if (record) {
        pushHistory({ view: "dashboard", filter: filterType, folderId: null },
                    dashboardUrl(filterType, null));
    }
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
        // 기본 폴더는 서버에서도 삭제를 막으므로 버튼을 아예 만들지 않는다
        const canDelete = f.name !== "기본 폴더";
        item.innerHTML = `
            <div class="folder-info">
                <i class="fa-solid fa-folder"></i>
                <span>${escapeHtml(f.name)}</span>
            </div>
            <div class="folder-meta">
                <span class="badge">${f.board_count || 0}</span>
                ${canDelete
                    ? `<button class="folder-del-btn" title="폴더 삭제"><i class="fa-solid fa-trash"></i></button>`
                    : `<span class="folder-del-spacer"></span>`}
            </div>
        `;
        if (canDelete) {
            item.querySelector(".folder-del-btn").onclick = (e) => {
                e.stopPropagation();   // 폴더 선택으로 번지지 않게 한다
                openDeleteFolderModal(f);
            };
        }
        container.appendChild(item);
    });

    const uploadSelect = document.getElementById("upload-folder-select");
    if (uploadSelect) {
        uploadSelect.innerHTML = folders.map(f => `<option value="${f.id}">${escapeHtml(f.name)}</option>`).join("");
    }
}

function selectFolder(folderId, folderName, record = true) {
    exitDetailView();          // 상세 화면에서 눌렀다면 목록으로 먼저 나온다
    currentFolderId = folderId;
    currentFilter = "folder";

    document.querySelectorAll(".sidebar-nav .nav-item").forEach(el => el.classList.remove("active"));
    document.getElementById("current-folder-title").textContent = folderName;
    renderFolderList();
    loadBoards();
    if (record) {
        pushHistory({ view: "dashboard", filter: "folder", folderId, folderName },
                    dashboardUrl("folder", folderId));
    }
}

function stopPlayback() {
    // 목록으로 나가면서 재생을 놔두면, 다른 보드를 열었을 때 이전 녹음이 계속 들린다
    try {
        audioPlayer.pause();
        audioPlayer.currentTime = 0;
    } catch (e) {
        /* src 가 비어 있을 때는 무시한다 */
    }
}

function exitDetailView() {
    // 상세 화면에서 목록으로 빠져나오는 유일한 통로.
    // 사이드바(전체/중요/미완료/휴지통/폴더)에서 불러도 화면이 같이 전환되도록 여기 한 곳에 모은다.
    const detail = document.getElementById("board-detail-view");
    if (detail.style.display === "none") return;   // 이미 목록이면 할 일이 없다
    stopPlayback();
    currentBoard = null;
    detail.style.display = "none";
    document.getElementById("dashboard-view").style.display = "flex";
}

function showDashboard() {
    // 우리가 쌓아 둔 기록이 있으면 진짜 '뒤로'를 눌러 준다.
    // 앱 안 뒤로가기 버튼과 크롬 뒤로가기가 같은 자리로 가고, 기록도 한 겹만 쌓인다.
    const state = history.state;
    if (state && state.view === "board" && (state.depth || 0) > 0) {
        history.back();        // popstate 가 목록 복원을 맡는다
        return;
    }
    // 링크로 상세 화면에 바로 들어온 경우(뒤로 갈 곳이 앱 밖) 전체 보드를 직접 그린다
    changeFilter("all");
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
            // 실패한 보드는 곧바로 재시도, 이미 끝난 보드는 확인을 거쳐 다시 받아쓰기
            let redoBtn = "";
            if (b.has_audio && b.status === "FAILED") {
                redoBtn = `<button class="icon-btn-small" onclick="reprocessBoard(event, ${b.id})" title="변환 다시 시도"><i class="fa-solid fa-rotate-right"></i></button>`;
            } else if (b.has_audio && !IN_FLIGHT_STATUSES.includes(b.status)) {
                redoBtn = `<button class="icon-btn-small" onclick="retranscribeBoard(event, ${b.id})" title="다시 받아쓰기"><i class="fa-solid fa-rotate-right"></i></button>`;
            }
            actionButtons = `
                ${redoBtn}
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

// 목록을 서버와 다시 맞춘다.
// 서버는 이 요청을 받으면 녹음 폴더를 그 자리에서 한 번 훑기 때문에, 방금 탐색기에 넣은 파일도
// 감시 주기(5초)를 기다리지 않고 바로 보인다. 옮기거나 지운 파일, 폴더 개수도 함께 반영된다.
async function refreshBoards() {
    if (isRefreshing) return;
    isRefreshing = true;

    const btn = document.getElementById("refresh-boards-btn");
    const icon = document.getElementById("refresh-boards-icon");
    if (btn) btn.disabled = true;
    if (icon) icon.classList.add("fa-spin");

    let result = null;
    try {
        const res = await fetch("/api/boards/refresh", { method: "POST" });
        if (res.ok) result = await res.json();
    } catch (e) {
        console.error("보드 새로고침 실패:", e);
    }

    try {
        // 폴더 검사가 실패해도 목록만이라도 다시 읽는다
        await Promise.all([
            loadFolders(),
            loadBoards(document.getElementById("board-search-input").value || ""),
        ]);
    } finally {
        isRefreshing = false;
        if (btn) btn.disabled = false;
        if (icon) icon.classList.remove("fa-spin");
    }

    if (!result) {
        showToast("목록만 다시 읽었습니다. (폴더 검사에 실패했습니다)");
    } else if (result.added > 0) {
        showToast(`새 녹음 ${result.added}개를 찾았습니다. 변환을 시작합니다.`);
    } else if (!result.scanned) {
        showToast("폴더를 검사하는 중입니다. 잠시 뒤 목록에 반영됩니다.");
    } else {
        showToast("목록을 최신 상태로 맞췄습니다.");
    }
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
    if (!confirm("이 보드를 휴지통으로 이동하시겠습니까? 원본 녹음 파일도 휴지통 폴더로 함께 옮겨져 탐색기에서 사라집니다. (복원하면 원래 자리로 되돌아옵니다)")) return;
    await fetch(`/api/boards/${boardId}`, { method: "DELETE" });
    showToast("휴지통으로 이동되었습니다. (원본 파일도 함께 이동)");
    loadBoards();
    loadFolders();
}

// 실패/중단된 보드의 변환을 다시 시도한다 (남아 있는 결과가 없으니 확인 없이 바로)
async function reprocessBoard(event, boardId) {
    if (event) event.stopPropagation();
    await requestReprocess(boardId, "변환 대기열에 다시 넣었습니다.");
}

// 이미 스크립트가 있는 보드를 원본 녹음으로 처음부터 다시 받아쓴다
async function retranscribeBoard(event, boardId) {
    if (event) event.stopPropagation();
    // 템플릿 리터럴이라 줄바꿈이 그대로 확인 창에 들어간다
    const ok = confirm(
`원본 녹음으로 스크립트를 처음부터 다시 만듭니다.

· 지금 스크립트는 새 변환이 끝나는 순간 통째로 교체됩니다 (직접 고친 내용도 사라집니다)
· 키워드와 기본 요약도 새로 만들어집니다
· 북마크와 AI 대화 기록은 그대로 남습니다
· 변환이 끝나기 전까지는 기존 스크립트를 그대로 볼 수 있습니다

계속할까요?`
    );
    if (!ok) return;
    await requestReprocess(boardId, "다시 받아쓰기를 시작했습니다. 변환이 끝나면 스크립트가 교체됩니다.");
}

// 상세 화면 헤더의 `다시 받아쓰기` 버튼
function retranscribeCurrentBoard() {
    if (!currentBoard) return;
    if (!currentBoard.has_audio) {
        alert("원본 녹음 파일이 없어 다시 받아쓸 수 없습니다.");
        return;
    }
    if (IN_FLIGHT_STATUSES.includes(currentBoard.status)) {
        alert("이미 변환 중입니다. 변환이 끝난 뒤에 다시 시도해주세요.");
        return;
    }
    retranscribeBoard(null, currentBoard.id);
}

async function requestReprocess(boardId, successMessage) {
    try {
        const res = await fetch(`/api/boards/${boardId}/reprocess`, { method: "POST" });
        const data = await res.json();
        if (!res.ok) {
            alert(data.detail || "다시 변환할 수 없습니다.");
            return null;
        }
        showToast(successMessage);
        // 상세 화면에서 눌렀다면 폴러가 완료를 감지하도록 상태를 바로 반영한다
        if (currentBoard && currentBoard.id === boardId) {
            currentBoard.status = "PENDING";
            currentBoard.progress_percent = 0;
            currentBoard.error_message = null;
            renderDetailStatus(currentBoard);
        }
        loadBoards();
        return data;
    } catch (e) {
        alert("재시도 요청에 실패했습니다.");
        return null;
    }
}

async function restoreBoard(boardId) {
    // 서버가 휴지통 폴더의 원본 파일도 원래 자리로 되돌린다
    await fetch(`/api/boards/${boardId}/restore`, { method: "POST" });
    showToast("보드가 복원되었습니다.");
    loadBoards();
    loadFolders();
}

async function deleteBoardPermanent(boardId) {
    if (!confirm("이 보드를 영구적으로 삭제하시겠습니까? 원본 녹음 파일과 변환 텍스트가 디스크에서 완전히 지워집니다. 되돌릴 수 없습니다.")) return;
    await fetch(`/api/boards/${boardId}?permanent=true`, { method: "DELETE" });
    showToast("원본 파일까지 영구 삭제되었습니다.");
    loadBoards();
}

async function batchDeleteBoards() {
    const isTrash = currentFilter === "trash";
    const msg = isTrash
        ? `선택한 ${selectedBoardIds.size}개의 보드를 영구 삭제하시겠습니까? 원본 녹음 파일과 변환 텍스트가 디스크에서 완전히 지워집니다. 되돌릴 수 없습니다.`
        : `선택한 ${selectedBoardIds.size}개의 보드를 휴지통으로 이동하시겠습니까? 원본 녹음 파일도 휴지통 폴더로 함께 옮겨져 탐색기에서 사라집니다.`;
    if (!confirm(msg)) return;

    await fetch("/api/boards/batch-delete", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ board_ids: Array.from(selectedBoardIds), permanent: isTrash })
    });
    selectedBoardIds.clear();
    showToast(isTrash ? "원본 파일까지 영구 삭제되었습니다." : "휴지통으로 이동되었습니다. (원본 파일도 함께 이동)");
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
async function openBoardDetail(boardId, record = true) {
    try {
        const res = await fetch(`/api/boards/${boardId}`);
        if (!res.ok) {
            // 지운 보드로 '앞으로 가기' 를 눌렀을 때 빈 상세 화면이 뜨지 않게 막는다
            showToast("보드를 찾을 수 없습니다.");
            changeFilter("all");
            return;
        }
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

        if (record) {
            pushHistory({ view: "board", boardId }, `?board=${boardId}`);
        }
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

    // 새로 변환한 보드는 서버가 이미 1분 내외 문단으로 묶어 주지만,
    // 예전에 촘촘하게 저장된 보드는 문단마다 타임스탬프가 붙어 읽기 힘들다.
    // `마지막으로 띄운 배지에서 1분이 지났을 때만` 다시 띄워 둘 다 자연스럽게 만든다.
    // (분 단위로 자르면 01:10 / 01:55 처럼 같은 분에 걸친 문단의 배지가 잘못 사라진다)
    let lastShownMs = null;

    segments.forEach((seg, idx) => {
        const block = document.createElement("div");
        block.className = "script-block";
        block.dataset.ms = seg.start_time_ms;
        block.dataset.ts = seg.timestamp_str;
        block.dataset.speaker = seg.speaker || "화자 1";
        block.id = `seg-${seg.id || idx}`;

        const startMs = seg.start_time_ms || 0;
        const showTs = lastShownMs === null || startMs - lastShownMs >= 60000;
        if (showTs) lastShownMs = startMs;

        block.innerHTML = `
            <div class="block-meta">
                ${showTs
                    ? `<span class="ts-badge" onclick="playAtMs(${seg.start_time_ms})">${seg.timestamp_str}</span>`
                    : ""}
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
            // 종료 시각은 서버가 이웃 문단을 보고 다시 계산한다 (자막 겹침 방지)
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
    if (!confirm("이 보드를 휴지통으로 이동하시겠습니까? 원본 녹음 파일도 휴지통 폴더로 함께 옮겨져 탐색기에서 사라집니다. (복원하면 원래 자리로 되돌아옵니다)")) return;
    await fetch(`/api/boards/${currentBoard.id}`, { method: "DELETE" });
    showDashboard();
    showToast("휴지통으로 이동되었습니다. (원본 파일도 함께 이동)");
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
            // 재생 중에는 밑줄로 위치만 표시한다.
            // 여기서 스크롤까지 하면 읽고 있던 자리가 계속 밀려나서, 화면을 따라가는 게 아니라
            // 화면에 끌려다니게 된다. 스크롤은 사용자가 재생 바로 구간을 옮겼을 때만 한다.
            if (currentActiveBlock) currentActiveBlock.classList.remove("active");
            active.classList.add("active");
            currentActiveBlock = active;
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
    const targetSec = (percent / 100) * durSec;
    audioPlayer.currentTime = targetSec;
    scrollToBlockAt(targetSec * 1000);   // 구간을 건너뛴 것이므로 자막도 그 자리로 데려간다
}

function scrollToBlockAt(ms) {
    // 해당 시각을 포함하는(= 시작 시각이 ms 를 넘지 않는 마지막) 자막 문단으로 스크롤한다.
    // timeupdate 의 밑줄 갱신과 같은 규칙을 쓰므로 밑줄과 스크롤 위치가 어긋나지 않는다.
    const blocks = document.querySelectorAll(".script-block[data-ms]");
    let target = null;
    for (let i = 0; i < blocks.length; i++) {
        if (parseFloat(blocks[i].dataset.ms) <= ms) target = blocks[i];
    }
    if (target) target.scrollIntoView({ behavior: "smooth", block: "center" });
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

// 삭제 확인 모달이 떠 있는 동안 어떤 폴더를 지우려는지 들고 있는다
let folderPendingDelete = null;

function openDeleteFolderModal(folder) {
    folderPendingDelete = folder;
    const count = folder.board_count || 0;
    const keepBtn = document.getElementById("folder-delete-keep-btn");
    const trashBtn = document.getElementById("folder-delete-trash-btn");

    document.getElementById("folder-delete-title").textContent = `'${folder.name}' 폴더 삭제`;
    if (count > 0) {
        // 안에 보드가 있으면 살릴지 함께 버릴지 고르게 한다
        document.getElementById("folder-delete-desc").textContent =
            `이 폴더에 보드 ${count}개가 있습니다. 보드를 어떻게 할지 고르세요. 원본 녹음 파일과 변환 텍스트도 보드를 따라 함께 옮겨집니다. ` +
            `휴지통으로 보낸 보드는 휴지통에서 되돌릴 수 있습니다.`;
        keepBtn.textContent = "보드는 기본 폴더로";
        trashBtn.style.display = "";
    } else {
        document.getElementById("folder-delete-desc").textContent =
            "빈 폴더입니다. 삭제하면 탐색기의 폴더도 함께 정리됩니다.";
        keepBtn.textContent = "삭제";
        trashBtn.style.display = "none";
    }
    document.getElementById("folder-delete-modal").style.display = "flex";
}

function closeDeleteFolderModal() {
    document.getElementById("folder-delete-modal").style.display = "none";
    folderPendingDelete = null;
}

async function submitDeleteFolder(withBoards) {
    // 확인 절차는 이 모달 자체다 (무엇이 어떻게 되는지 위에 적어 두고 버튼으로 고르게 한다)
    const folder = folderPendingDelete;
    if (!folder) return;

    try {
        const res = await fetch(`/api/folders/${folder.id}?with_boards=${withBoards}`, { method: "DELETE" });
        const data = await res.json().catch(() => ({}));
        if (!res.ok) {
            showToast(data.detail || "폴더를 삭제하지 못했습니다.");
            return;
        }
        closeDeleteFolderModal();

        let msg = "폴더를 삭제했습니다.";
        if (data.trashed_boards) {
            msg = `폴더를 삭제하고 보드 ${data.trashed_boards}개를 휴지통으로 옮겼습니다.`;
        } else if (data.moved_boards) {
            msg = `폴더를 삭제하고 보드 ${data.moved_boards}개를 기본 폴더로 옮겼습니다.`;
        }
        if (data.leftover_files) {
            msg += ` (서버가 모르는 파일 ${data.leftover_files}개가 남아 있어 탐색기 폴더는 두었습니다)`;
        }
        showToast(msg);

        // 지운 폴더를 보고 있었다면 전체 보드로 돌아간다 (changeFilter 가 목록도 다시 읽는다)
        if (currentFolderId === folder.id) {
            changeFilter("all");
        } else {
            loadBoards();
        }
        loadFolders();
    } catch (e) {
        showToast("폴더 삭제에 실패했습니다.");
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
// 8-1. 용어집(단어장)
// -----------------------------------------
// 받아쓰기 프롬프트에 실어 보낼 고유명사/전문용어. 저장만으로는 기존 스크립트가 바뀌지 않고,
// `다시 받아쓰기`를 돌려야 반영된다. 그래서 모달에서 바로 재변환까지 이어갈 수 있게 했다.
let glossaryFolderId = null;
let glossaryMaxTerms = 200;

async function openGlossaryModal() {
    if (!currentBoard) return;
    glossaryFolderId = currentBoard.folder_id || null;

    try {
        const url = glossaryFolderId ? `/api/glossary?folder_id=${glossaryFolderId}` : "/api/glossary";
        const res = await fetch(url);
        if (!res.ok) throw new Error("load failed");
        const data = await res.json();

        glossaryMaxTerms = data.max_terms || 200;
        const folderBox = document.getElementById("glossary-folder-terms");
        const label = document.getElementById("glossary-folder-label");

        label.textContent = data.folder_name
            ? `'${data.folder_name}' 폴더 전용`
            : "폴더 전용 (이 보드는 폴더에 속해 있지 않습니다)";
        folderBox.value = termsToText(data.folder_terms || []);
        folderBox.disabled = !glossaryFolderId;
        document.getElementById("glossary-common-terms").value = termsToText(data.common_terms || []);

        folderBox.oninput = updateGlossaryHint;
        document.getElementById("glossary-common-terms").oninput = updateGlossaryHint;
        updateGlossaryHint();

        // 재변환은 원본 녹음이 있어야 가능하다
        const retryBtn = document.getElementById("glossary-save-retry-btn");
        retryBtn.style.display = currentBoard.has_audio ? "" : "none";

        document.getElementById("glossary-modal").style.display = "flex";
    } catch (e) {
        alert("용어집을 불러오지 못했습니다.");
    }
}

function closeGlossaryModal() {
    document.getElementById("glossary-modal").style.display = "none";
}

function termsToText(terms) {
    return terms.map(t => (t.note ? `${t.term} | ${t.note}` : t.term)).join("\n");
}

// 한 줄에 용어 하나. `용어 | 메모` 형식으로 메모를 덧붙일 수 있다.
function textToTerms(text) {
    return (text || "")
        .split("\n")
        .map(line => {
            const parts = line.split("|");
            return { term: (parts.shift() || "").trim(), note: parts.join("|").trim() };
        })
        .filter(t => t.term);
}

function updateGlossaryHint() {
    const total =
        textToTerms(document.getElementById("glossary-folder-terms").value).length +
        textToTerms(document.getElementById("glossary-common-terms").value).length;
    document.getElementById("glossary-count-hint").textContent =
        total > glossaryMaxTerms
            ? `용어 ${total}개 — 프롬프트에는 앞에서부터 ${glossaryMaxTerms}개까지만 들어갑니다.`
            : `용어 ${total}개`;
}

async function saveGlossary(thenRetranscribe) {
    const buttons = Array.from(document.querySelectorAll("#glossary-modal button"));
    buttons.forEach(b => { b.disabled = true; });
    try {
        if (glossaryFolderId) {
            await putGlossary(glossaryFolderId, textToTerms(document.getElementById("glossary-folder-terms").value));
        }
        await putGlossary(null, textToTerms(document.getElementById("glossary-common-terms").value));
        closeGlossaryModal();
        showToast("용어집을 저장했습니다.");
        if (thenRetranscribe && currentBoard) {
            retranscribeBoard(null, currentBoard.id);
        }
    } catch (e) {
        alert("용어집 저장에 실패했습니다.");
    } finally {
        buttons.forEach(b => { b.disabled = false; });
    }
}

async function putGlossary(folderId, terms) {
    const res = await fetch("/api/glossary", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ folder_id: folderId, terms })
    });
    if (!res.ok) throw new Error("save failed");
    return res.json();
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
    updateRetranscribeBtn(board);

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

function updateRetranscribeBtn(board) {
    const btn = document.getElementById("detail-retranscribe-btn");
    if (!btn) return;

    // 원본 녹음이 없으면 다시 받아쓸 방법이 없고, 변환 중에는 중복 투입을 막는다
    if (!board || !board.has_audio) {
        btn.style.display = "none";
        return;
    }
    const inFlight = IN_FLIGHT_STATUSES.includes(board.status);
    btn.style.display = "";
    btn.disabled = inFlight;
    btn.title = inFlight
        ? "변환이 끝난 뒤에 다시 받아쓸 수 있습니다"
        : "원본 녹음으로 스크립트를 처음부터 다시 만듭니다";
}

function escapeHtml(str) {
    if (!str) return "";
    return str.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#039;");
}
