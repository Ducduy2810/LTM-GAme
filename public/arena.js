// ================================================================
// ĐẤU TRƯỜNG 100 - CLIENT SCRIPT V3
// Luật gốc: 1 Người Chơi Chính vs N Đối Thủ (người thật + bot)
// ================================================================

const wsUrl = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws';
const socket = new WebSocket(wsUrl);

const screens = {
    lobby:   document.getElementById('lobby-screen'),
    waiting: document.getElementById('waiting-screen'),
    game:    document.getElementById('game-screen'),
    result:  document.getElementById('result-screen'),
    end:     document.getElementById('end-screen')
};

const podiumsContainer = document.getElementById('podiums-container');
const arenaHeader = document.getElementById('arena-header');

function showScreen(screenName) {
    for (let key in screens) {
        if (screens[key]) screens[key].classList.remove('active');
    }
    if (screens[screenName]) screens[screenName].classList.add('active');

    // Khán đài chỉ hiển thị khi đã vào phòng
    if (podiumsContainer) {
        podiumsContainer.style.display = (screenName === 'lobby') ? 'none' : 'block';
    }

    // HUD (arena-header) chỉ hiển thị khi đã vào phòng (waiting, game, result, end)
    if (arenaHeader) {
        arenaHeader.style.display = (screenName === 'lobby') ? 'none' : 'block';
    }
}

// ── Trạng thái cục bộ ────────────────────────────────────────────
let currentUsername     = '';
let isCurrentUserAdmin  = false;
let currentRoomCode     = '';
let currentRoomInfo     = null;
let isMyHost            = false;

// Vai trò trong trận
let amIMainPlayer = false;     // Tôi có là Người Chơi Chính không?
let amIAlive      = true;      // Tôi còn sống (đối thủ) không?

// Đếm và HUD
let totalInitialOpponents = 0;
let aliveOpponents        = 0;
let totalEliminated       = 0;
let myCurrentScore        = 0;

// Quyền trợ giúp
let lifeTrustUsed   = false;
let lifeAskUsed     = false;
let lifeSurveyUsed  = false;

// Danh sách đối thủ thật hiện tại (cho ASK modal)
let realOpponentsList = []; // [{username, isAlive}]

let countdownInterval = null;

const eliminatedSet  = new Set();
const playerSeatMap  = new Map(); // username -> seat number (1..100)

// ── 1. LẤY THÔNG TIN TÀI KHOẢN ──────────────────────────────────
fetch('/auth/me').then(r => r.json()).then(data => {
    if (data.loggedIn) {
        currentUsername    = data.user.username;
        isCurrentUserAdmin = !!data.user.isAdmin
            || (data.user.email === 'mainung28@gmail.com')
            || currentUsername === 'admin'
            || currentUsername === 'DuyNguyen';

        if (isCurrentUserAdmin) {
            const clearLbBtn = document.getElementById('btn-admin-clear-leaderboard');
            if (clearLbBtn) clearLbBtn.style.display = 'inline-block';
        }
    } else {
        alert('Bạn chưa đăng nhập!');
        window.location.href = 'index.html';
    }
}).catch(() => { window.location.href = 'index.html'; });

// ── 2. KHÁN ĐÀI 100 BỤC ─────────────────────────────────────────
function initPodiumsGrid() {
    const grid = document.getElementById('podiums-grid');
    if (!grid || grid.children.length > 0) return;
    for (let i = 1; i <= 100; i++) {
        const seat = document.createElement('div');
        seat.className = 'podium-seat';
        seat.id = `podium-${i}`;
        seat.title = `Bục thi đấu số ${i}`;
        seat.innerHTML = `<span class="seat-idx">${i}</span>`;
        grid.appendChild(seat);
    }
}
initPodiumsGrid();

function renderPodiums(players = []) {
    initPodiumsGrid();
    const activeUsernames = new Set(players.map(p => p.username));
    for (const [username] of playerSeatMap.entries()) {
        if (!activeUsernames.has(username)) playerSeatMap.delete(username);
    }
    const takenSeats = new Set(playerSeatMap.values());
    let nextSeat = 1;
    for (const p of players) {
        if (!playerSeatMap.has(p.username)) {
            while (takenSeats.has(nextSeat) && nextSeat <= 100) nextSeat++;
            if (nextSeat <= 100) {
                playerSeatMap.set(p.username, nextSeat);
                takenSeats.add(nextSeat);
            }
        }
    }
    const seatToUser = new Map();
    for (const [username, seatNum] of playerSeatMap.entries()) seatToUser.set(seatNum, username);

    for (let i = 1; i <= 100; i++) {
        const seat = document.getElementById(`podium-${i}`);
        if (!seat) continue;
        const username = seatToUser.get(i);
        if (username) {
            const p = players.find(x => x.username === username);
            const isMe          = username === currentUsername;
            const isEliminated  = eliminatedSet.has(username);
            const isMainPlayer  = p && p.isMainPlayer;
            const isBot         = p && p.isBot;
            let classes = ['podium-seat', 'active-player'];
            if (isMe)         classes.push('is-me');
            if (isEliminated) classes.push('eliminated');
            if (isMainPlayer) classes.push('main-player');
            if (isBot)        classes.push('is-bot');
            seat.className = classes.join(' ');
            let title = `Bục ${i}: ${username}`;
            if (isMainPlayer) title += ' 👑NCC';
            if (isBot)        title += ' 🤖Bot';
            if (isMe)         title += ' (Bạn)';
            if (isEliminated) title += ' [Loại]';
            seat.title = title;
            const shortName = username.length > 5 ? username.substring(0, 4) + '..' : username;
            const icon = isMainPlayer ? '👑' : (isBot ? '🤖' : '#' + i);
            seat.innerHTML = `<span class="seat-idx">${icon}</span><span class="seat-name">${shortName}</span>`;
        } else {
            seat.className = 'podium-seat';
            seat.title = `Bục thi đấu số ${i}`;
            seat.innerHTML = `<span class="seat-idx">${i}</span>`;
        }
    }

    // Cập nhật HUD
    updateHud();
    const statusText = document.getElementById('player-room-status');
    if (statusText) statusText.innerText = `${players.length} Đấu thủ / 100 Bục`;
}

function updateHud() {
    const el = n => document.getElementById(n);
    if (el('survivor-hud'))  el('survivor-hud').innerText  = aliveOpponents;
    if (el('eliminated-hud'))el('eliminated-hud').innerText = totalEliminated;
    if (el('score-hud'))     el('score-hud').innerText      = myCurrentScore;
}

// ── 3. KẾT NỐI WEBSOCKET ─────────────────────────────────────────
socket.onopen = () => {
    const params     = new URLSearchParams(window.location.search);
    const targetRoom = params.get('room');
    if (targetRoom) {
        socket.send(JSON.stringify({ type: 'join_room', roomCode: targetRoom }));
    } else {
        showScreen('lobby');
        socket.send(JSON.stringify({ type: 'get_rooms' }));
    }
};

socket.onclose = () => console.log('WebSocket đóng kết nối');

socket.onmessage = (event) => {
    const data = JSON.parse(event.data);

    if (data.type === 'auth_error' || data.type === 'arena_error') {
        alert(data.message);
        if (data.type === 'auth_error') window.location.href = 'index.html';
        return;
    }

    switch (data.type) {

        // ── SẢNH ─────────────────────────────────────────────────
        case 'room_list':
            renderRoomList(data.rooms);
            break;

        case 'leaderboard_update':
            renderLeaderboard(data.leaderboard, 'leaderboard-body');
            break;

        // ── THÔNG TIN PHÒNG (khi vào phòng) ─────────────────────
        case 'room_info': {
            currentRoomInfo = data;
            currentRoomCode = data.roomCode;

            document.getElementById('room-code-badge').innerText = data.roomCode;
            document.getElementById('room-name-display').innerText = data.roomName;
            document.getElementById('room-settings-summary').innerText =
                `[${data.topicName || data.topic}] • [${data.difficultyName || data.difficulty}] • ${data.questionCount} câu • ⏱️ Phụ 5s / Chính 15s • Bot: ${data.botCount}`;

            showScreen('waiting');
            break;
        }

        // ── RỜI PHÒNG THÀNH CÔNG ─────────────────────────────────
        case 'left_room_success':
            currentRoomCode = '';
            currentRoomInfo = null;
            resetGameState();
            showScreen('lobby');
            socket.send(JSON.stringify({ type: 'get_rooms' }));
            break;

        // ── CẬP NHẬT DANH SÁCH NGƯỜI CHƠI PHÒNG CHỜ ─────────────
        case 'update_arena_players':
            handleUpdatePlayers(data);
            break;

        // ── BẮT ĐẦU TRẬN ─────────────────────────────────────────
        case 'arena_game_started':
            handleGameStarted(data);
            break;

        // ── CÂU HỎI MỚI ──────────────────────────────────────────
        case 'arena_new_question':
            handleNewQuestion(data);
            break;

        // ── KẾT QUẢ VÒNG ─────────────────────────────────────────
        case 'arena_round_result':
            handleRoundResult(data);
            break;

        // ── KẾT THÚC TRẬN ────────────────────────────────────────
        case 'arena_end_game':
            handleEndGame(data);
            break;

        // ── KẾT QUẢ QUYỀN TRỢ GIÚP ──────────────────────────────
        case 'arena_life_trust':
        case 'arena_life_ask':
            showLifelineResult(`👁 ${data.opponentUsername} đã chọn đáp án: <strong>${'ABCD'[data.opponentAnswer]}</strong>`);
            break;

        case 'arena_life_survey':
            showLifelineSurvey(data.distribution, data.totalRespondents);
            break;
            
        // YÊU CẦU 2: Lượt người chơi chính trả lời
        case 'arena_main_player_turn':
            handleMainPlayerTurn(data);
            break;
            
        // YÊU CẦU 5: Tạm dừng và tiếp tục
        case 'arena_game_paused':
            handleGamePaused(data);
            break;
            
        case 'arena_game_resumed':
            handleGameResumed(data);
            break;

        case 'admin_clear_leaderboard_success':
            alert('✅ ' + (data.message || 'Đã xóa toàn bộ Bảng Xếp Hạng thành công!'));
            break;
    }
};

// ── 4. HANDLER: CẬP NHẬT NGƯỜI CHƠI PHÒNG CHỜ ───────────────────
function handleUpdatePlayers(data) {
    // Phân tách người thật và bot
    const realPlayers = data.players.filter(p => !p.isBot);
    const botPlayers = data.players.filter(p => p.isBot);

    document.getElementById('player-count').innerText = realPlayers.length;
    const list = document.getElementById('player-list');
    list.innerHTML = '';

    let meInRoom = null;
    realOpponentsList = [];

    // Hiển thị người thật trong danh sách
    realPlayers.forEach(p => {
        if (p.username === currentUsername) meInRoom = p;
        else if (!p.isHost && !p.isSuperAdmin) realOpponentsList.push(p);

        const chip = document.createElement('div');
        chip.className = `player-chip ${p.isHost ? 'is-host' : ''}`;

        let roleTag = '';
        if (p.isSuperAdmin) roleTag = '<span style="color:#fde047; margin-left:4px; font-weight:800;">🛡️ (Admin)</span>';
        else if (p.isHost)  roleTag = '<span style="color:#fde047; margin-left:4px; font-weight:800;">👑 (Chủ phòng)</span>';

        let readyTag = '';
        if (p.isHost || p.isSuperAdmin) readyTag = '<span class="ready-badge is-ready" style="margin-left:6px;">Chủ Trì</span>';
        else if (p.isReady)             readyTag = '<span class="ready-badge is-ready" style="margin-left:6px;">✅ Sẵn sàng</span>';
        else                            readyTag = '<span class="ready-badge not-ready" style="margin-left:6px;">⏳ Chưa sẵn sàng</span>';

        chip.innerHTML = `👤 ${p.username} ${roleTag} ${readyTag}`;
        list.appendChild(chip);
    });

    // Hiển thị bot trong danh sách (gọn gàng)
    if (botPlayers.length > 0) {
        const botChip = document.createElement('div');
        botChip.className = 'player-chip';
        botChip.style.background = 'rgba(100, 116, 139, 0.2)';
        botChip.style.border = '1px solid rgba(100, 116, 139, 0.4)';
        botChip.innerHTML = `🤖 ${botPlayers.length} Bot (Đối thủ máy)`;
        list.appendChild(botChip);
    }

    // Truyền tất cả (người thật + bot) lên khán đài
    renderPodiums(data.players);

    isMyHost = (data.hostUsername && data.hostUsername === currentUsername);
    const canIControl = isMyHost || isCurrentUserAdmin;

    const btnReady   = document.getElementById('btn-toggle-ready');
    const btnStart   = document.getElementById('btn-start');
    const waitingMsg = document.getElementById('waiting-msg');
    const roleBadge  = document.getElementById('role-badge-waiting');

    if (roleBadge) {
        roleBadge.style.display = 'block';
        if (isCurrentUserAdmin) roleBadge.innerText = '🛡️ Bạn là Super Admin – Có toàn quyền kiểm soát';
        else if (isMyHost)      roleBadge.innerText = '👑 Bạn là Chủ Phòng – Có thể bắt đầu trận đấu';
        else                    roleBadge.style.display = 'none';
    }

    // Nút Sẵn Sàng
    if (canIControl) {
        btnReady.style.display = 'none';
    } else {
        btnReady.style.display = 'inline-block';
        if (meInRoom && meInRoom.isReady) {
            btnReady.innerText   = '❌ HỦY SẴN SÀNG';
            btnReady.className   = 'btn-ready-toggle ready-active';
        } else {
            btnReady.innerText   = '✅ SẴN SÀNG';
            btnReady.className   = 'btn-ready-toggle';
        }
    }

    // Nút Bắt Đầu
    if (canIControl) {
        btnStart.style.display = 'inline-block';
        if (isCurrentUserAdmin) {
            btnStart.disabled       = false;
            btnStart.style.opacity  = '1';
            btnStart.innerText      = '👑 Bắt đầu Trận Đấu (Super Admin)';
            waitingMsg.innerText    = '🛡️ Bạn là Super Admin – Có toàn quyền bắt đầu bất kỳ lúc nào!';
            waitingMsg.style.color  = '#10b981';
        } else if (data.allReady || data.players.length === 1) {
            btnStart.disabled       = false;
            btnStart.style.opacity  = '1';
            btnStart.innerText      = '👑 Bắt đầu Trận Đấu';
            waitingMsg.innerText    = '👑 Mọi người đã sẵn sàng! Bạn có thể bắt đầu trận đấu.';
            waitingMsg.style.color  = '#10b981';
        } else {
            btnStart.disabled       = true;
            btnStart.style.opacity  = '0.6';
            btnStart.innerText      = '⏳ Đang chờ người chơi Sẵn sàng...';
            waitingMsg.innerText    = '⏳ Vẫn còn người chơi chưa bấm Sẵn sàng.';
            waitingMsg.style.color  = '#facc15';
        }
    } else {
        btnStart.style.display = 'none';
        if (meInRoom && meInRoom.isReady) {
            waitingMsg.innerText    = `✅ Bạn đã sẵn sàng! Chờ Chủ phòng [${data.hostUsername}] bắt đầu...`;
            waitingMsg.style.color  = '#10b981';
        } else {
            waitingMsg.innerText    = '👉 Hãy bấm [SẴN SÀNG] để Chủ phòng có thể bắt đầu trận đấu!';
            waitingMsg.style.color  = '#facc15';
        }
    }
}

// ── 5. HANDLER: BẮT ĐẦU TRẬN ────────────────────────────────────
function handleGameStarted(data) {
    resetGameState();
    totalInitialOpponents = data.totalOpponents || 0;
    aliveOpponents        = totalInitialOpponents;
    amIMainPlayer         = (data.mainPlayer === currentUsername);
    amIAlive              = true;

    updateHud();

    const myStatus = document.getElementById('my-status');
    if (myStatus) {
        if (amIMainPlayer) {
            myStatus.innerText  = '👑 NGƯỜI CHƠI CHÍNH';
            myStatus.className  = 'player-status-badge main-player-badge';
        } else {
            myStatus.innerText  = 'Trạng thái: ĐỐI THỦ – CÒN SỐNG';
            myStatus.className  = 'player-status-badge alive';
        }
    }

    const roleBadgeGame = document.getElementById('role-badge-game');
    if (roleBadgeGame) {
        if (amIMainPlayer) {
            roleBadgeGame.innerText   = `👑 NGƯỜI CHƠI CHÍNH – Bạn đối đầu với ${totalInitialOpponents} đối thủ!`;
            roleBadgeGame.style.color = '#fde047';
            roleBadgeGame.style.borderColor = 'rgba(253,224,71,0.5)';
        } else {
            roleBadgeGame.innerText   = `⚔️ ĐỐI THỦ của ${data.mainPlayer}`;
            roleBadgeGame.style.color = '#93c5fd';
            roleBadgeGame.style.borderColor = 'rgba(147,197,253,0.3)';
        }
    }

    // Reset quyền trợ giúp
    lifeTrustUsed  = false;
    lifeAskUsed    = false;
    lifeSurveyUsed = false;
    updateLifelineButtons();

    if (window.arenaSound) window.arenaSound.init();
}

// ── 6. HANDLER: CÂU HỎI MỚI ─────────────────────────────────────
function handleNewQuestion(data) {
    showScreen('game');

    aliveOpponents  = data.aliveOpponents  !== undefined ? data.aliveOpponents  : aliveOpponents;
    totalEliminated = data.totalEliminated !== undefined ? data.totalEliminated : totalEliminated;
    updateHud();

    const badge = document.getElementById('question-badge');
    if (badge) {
        badge.innerText = `CÂU ${String(data.questionId).padStart(2, '0')} / ${data.totalQuestions || 10}`;
        badge.style.background = 'linear-gradient(90deg, #f59e0b, #d97706)';
        badge.style.color      = '#000';
    }

    document.getElementById('question-text').innerText = data.text;
    document.getElementById('feedback-msg').innerText  = '';

    const container = document.getElementById('options-container');
    container.innerHTML = '';
    const letters = ['A', 'B', 'C', 'D'];

    const canAnswer = amIAlive; // cả main player lẫn đối thủ còn sống đều trả lời

    data.options.forEach((opt, index) => {
        const btn = document.createElement('button');
        btn.className = 'answer-btn option-btn';
        btn.innerHTML = `
            <span class="answer-letter">${letters[index] || ''}</span>
            <span class="answer-text">${opt}</span>
        `;
        if (!canAnswer) btn.disabled = true;
        btn.onclick = () => selectAnswer(index, btn);
        container.appendChild(btn);
    });

    if (!amIAlive) {
        document.getElementById('feedback-msg').innerText =
            amIMainPlayer ? '❌ Người chơi chính đã thua – Bạn đang theo dõi.'
                          : 'Bạn đã bị loại, đang theo dõi với vai trò khán giả.';
    }

    // Quyền trợ giúp
    const lifePanel = document.getElementById('lifelines-panel');
    if (lifePanel) {
        lifePanel.style.display = amIMainPlayer ? 'block' : 'none';
    }
    hideLifelineResult();
    
    // YÊU CẦU 5: Hiển thị nút pause/resume cho host/admin
    const pauseControls = document.getElementById('pause-controls');
    if (pauseControls) {
        pauseControls.style.display = (isMyHost || isCurrentUserAdmin) ? 'flex' : 'none';
    }

    startTimer(data.timeLimit || 10);
}

// ── 7. HANDLER: KẾT QUẢ VÒNG ────────────────────────────────────
function handleRoundResult(data) {
    clearInterval(countdownInterval);
    const dial = document.getElementById('timer-dial');
    if (dial) dial.classList.remove('danger');

    // Highlight đáp án đúng
    const btns = document.querySelectorAll('.answer-btn');
    if (btns[data.correctAnswerIndex]) btns[data.correctAnswerIndex].classList.add('correct');
    btns.forEach(b => {
        if (b.classList.contains('selected') && !b.classList.contains('correct'))
            b.classList.add('wrong');
    });

    // Cập nhật HUD từ dữ liệu server
    aliveOpponents  = data.aliveOpponents  !== undefined ? data.aliveOpponents  : aliveOpponents;
    totalEliminated = data.totalEliminated !== undefined ? data.totalEliminated : totalEliminated;
    myCurrentScore  = data.currentScore    !== undefined ? data.currentScore    : myCurrentScore;
    updateHud();

    // Đánh dấu đối thủ thật bị loại
    if (data.eliminatedRealOpponents && Array.isArray(data.eliminatedRealOpponents)) {
        data.eliminatedRealOpponents.forEach(u => eliminatedSet.add(u));
    }

    // Cập nhật trạng thái sống/chết của tôi nếu là đối thủ
    if (!amIMainPlayer && amIAlive && data.eliminatedRealOpponents &&
        data.eliminatedRealOpponents.includes(currentUsername)) {
        amIAlive = false;
        const myStatus = document.getElementById('my-status');
        if (myStatus) {
            myStatus.innerText  = 'Trạng thái: BỊ LOẠI';
            myStatus.className  = 'player-status-badge eliminated';
        }
        if (window.arenaSound) window.arenaSound.playEliminated();
    } else if (amIAlive && window.arenaSound) {
        window.arenaSound.playCorrect();
    }

    // Render kết quả sau 2 giây
    setTimeout(() => {
        showScreen('result');

        document.getElementById('survivor-count').innerText       = aliveOpponents;
        document.getElementById('eliminated-round-count').innerText =
            (data.eliminatedRealOpponents ? data.eliminatedRealOpponents.length : 0) +
            (data.eliminatedBotCount || 0);
        document.getElementById('current-score-result').innerText = myCurrentScore;

        const elimNames = data.eliminatedRealOpponents || [];
        const botCount  = data.eliminatedBotCount || 0;
        let elimText = '';
        if (elimNames.length) elimText += elimNames.join(', ');
        if (botCount) elimText += (elimText ? ' + ' : '') + `${botCount} Bot`;
        document.getElementById('eliminated-list').innerText = elimText || 'Không có ai';

        const resultInfo   = document.getElementById('result-info');
        const mainBadge    = document.getElementById('main-player-result-badge');

        if (amIMainPlayer) {
            mainBadge.style.display = 'block';
            if (data.mainPlayerCorrect) {
                resultInfo.innerText    = `✅ Người Chơi Chính trả lời ĐÚNG! ${data.eliminatedBotCount + (data.eliminatedRealOpponents ? data.eliminatedRealOpponents.length : 0)} đối thủ bị loại.`;
                resultInfo.style.color  = '#10b981';
                mainBadge.innerText     = `👑 Bạn là Người Chơi Chính – Đáp án đúng!`;
                mainBadge.style.background = 'rgba(16,185,129,0.2)';
                mainBadge.style.color      = '#10b981';
                mainBadge.style.border     = '1px solid rgba(16,185,129,0.5)';
            } else {
                resultInfo.innerText    = `❌ Người Chơi Chính trả lời SAI – Trận đấu kết thúc!`;
                resultInfo.style.color  = '#ef4444';
                mainBadge.innerText     = `👑 Bạn là Người Chơi Chính – Đáp án SAI!`;
                mainBadge.style.background = 'rgba(239,68,68,0.2)';
                mainBadge.style.color      = '#ef4444';
                mainBadge.style.border     = '1px solid rgba(239,68,68,0.5)';
            }
        } else {
            mainBadge.style.display = 'none';
            if (!amIAlive && data.eliminatedRealOpponents && data.eliminatedRealOpponents.includes(currentUsername)) {
                resultInfo.innerText   = '❌ Rất tiếc! Bạn đã trả lời sai và bị loại khỏi trận đấu!';
                resultInfo.style.color = '#ef4444';
            } else if (amIAlive) {
                if (data.mainPlayerCorrect) {
                    resultInfo.innerText   = '✅ Người Chơi Chính đúng! Bạn sống sót vòng này.';
                    resultInfo.style.color = '#10b981';
                } else {
                    resultInfo.innerText   = '❌ Người Chơi Chính trả lời sai – Trận đấu kết thúc!';
                    resultInfo.style.color = '#f59e0b';
                }
            } else {
                resultInfo.innerText   = 'Bạn đang theo dõi trận đấu ở chế độ khán giả.';
                resultInfo.style.color = '#94a3b8';
            }
        }
    }, 2000);
}

// ── 8. HANDLER: KẾT THÚC TRẬN ────────────────────────────────────
function handleEndGame(data) {
    showScreen('end');

    const mainWon = !!data.mainWon;

    document.getElementById('end-reason').innerText      = data.reason || 'Trận đấu đã khép lại!';
    document.getElementById('end-main-player').innerText = data.mainPlayer || '—';
    document.getElementById('end-eliminated').innerText  = data.totalEliminated  || 0;
    document.getElementById('end-total').innerText       = data.totalInitialOpponents || 0;
    document.getElementById('end-score').innerText       = data.finalScore || 0;

    const trophy = document.getElementById('end-trophy');
    const title  = document.getElementById('end-title');
    if (amIMainPlayer) {
        if (mainWon) {
            trophy.innerText       = '🏆';
            title.style.color      = '#f59e0b';
            title.innerText        = '🎉 Chúc Mừng! Bạn Chinh Phục Đấu Trường 100!';
            if (window.arenaSound) window.arenaSound.playVictory();
        } else {
            trophy.innerText       = '💔';
            title.style.color      = '#ef4444';
            title.innerText        = '😞 Bạn Đã Thua – Đấu Trường Vẫn Còn Đối Thủ!';
        }
    } else {
        if (mainWon) {
            trophy.innerText       = '🏅';
            title.style.color      = '#f59e0b';
            title.innerText        = `🏆 ${data.mainPlayer} Đã Chinh Phục Đấu Trường 100!`;
            if (window.arenaSound) window.arenaSound.playVictory();
        } else {
            trophy.innerText       = '🎉';
            title.style.color      = '#10b981';
            title.innerText        = `🎉 ${data.mainPlayer} Thua – Các Đối Thủ Chiến Thắng!`;
        }
    }

    // Mini bảng xếp hạng
    if (data.leaderboard && data.leaderboard.length > 0) {
        const panel = document.getElementById('end-leaderboard-panel');
        const body  = document.getElementById('end-leaderboard-body');
        panel.style.display = 'block';
        renderLeaderboard(data.leaderboard, null, body);
    }
}

// ── 9. QUYỀN TRỢ GIÚP ────────────────────────────────────────────
function updateLifelineButtons() {
    const btnTrust   = document.getElementById('btn-life-trust');
    const btnAsk     = document.getElementById('btn-life-ask');
    const btnSurvey  = document.getElementById('btn-life-survey');
    if (!btnTrust) return;

    btnTrust.disabled  = lifeTrustUsed;
    btnAsk.disabled    = lifeAskUsed;
    btnSurvey.disabled = lifeSurveyUsed;
    if (lifeTrustUsed)  { btnTrust.innerText  = '👁 Tin Người Chơi (Đã dùng)'; btnTrust.style.opacity  = '0.4'; }
    if (lifeAskUsed)    { btnAsk.innerText    = '🙋 Hỏi Người Chơi (Đã dùng)'; btnAsk.style.opacity    = '0.4'; }
    if (lifeSurveyUsed) { btnSurvey.innerText = '📊 Khảo Sát (Đã dùng)';       btnSurvey.style.opacity = '0.4'; }
}

function showLifelineResult(html) {
    const el = document.getElementById('lifeline-result');
    if (!el) return;
    el.style.display = 'block';
    el.innerHTML = html;
}

function showLifelineSurvey(dist, total) {
    const letters = ['A', 'B', 'C', 'D'];
    let html = `<div style="color:#fde047; font-weight:700; margin-bottom:6px;">📊 Khảo sát (${total} người đã trả lời):</div>`;
    dist.forEach((pct, i) => {
        html += `<div style="margin:3px 0; display:flex; align-items:center; gap:8px;">
            <span style="width:16px; font-weight:700;">${letters[i]}</span>
            <div style="flex:1; background:rgba(255,255,255,0.08); border-radius:4px; overflow:hidden; height:14px;">
                <div style="width:${pct}%; background:${i===0?'#ef4444':i===1?'#3b82f6':i===2?'#10b981':'#f59e0b'}; height:100%;"></div>
            </div>
            <span style="width:36px; text-align:right; font-size:12px;">${pct}%</span>
        </div>`;
    });
    showLifelineResult(html);
}

function hideLifelineResult() {
    const el = document.getElementById('lifeline-result');
    if (el) el.style.display = 'none';
}

// YÊU CẦU 2: Xử lý lượt người chơi chính
function handleMainPlayerTurn(data) {
    playSound('questionBeep');
    const feedbackEl = document.getElementById('feedback-msg');
    if (feedbackEl) {
        feedbackEl.style.color = '#fde047';
        feedbackEl.innerText = '⏰ ' + data.message;
    }
    // Cập nhật timer với thời gian mới
    if (data.timeLimit) {
        startCountdown(data.timeLimit);
    }
}

// YÊU CẦU 5: Xử lý tạm dừng trận đấu
function handleGamePaused(data) {
    playSound('notificationSound');
    if (countdownInterval) {
        clearInterval(countdownInterval);
        countdownInterval = null;
    }
    const feedbackEl = document.getElementById('feedback-msg');
    if (feedbackEl) {
        feedbackEl.style.color = '#ef4444';
        feedbackEl.innerText = data.message || '⏸️ Trận đấu đã tạm dừng';
    }
    // Hiển thị nút resume, ẩn nút pause
    const btnPause = document.getElementById('btn-pause-game');
    const btnResume = document.getElementById('btn-resume-game');
    if (btnPause) btnPause.style.display = 'none';
    if (btnResume) btnResume.style.display = 'inline-block';
}

// YÊU CẦU 5: Xử lý tiếp tục trận đấu
function handleGameResumed(data) {
    playSound('notificationSound');
    const feedbackEl = document.getElementById('feedback-msg');
    if (feedbackEl) {
        feedbackEl.style.color = '#10b981';
        feedbackEl.innerText = data.message || '▶️ Trận đấu tiếp tục!';
    }
    // Khởi động lại đếm ngược với thời gian còn lại
    if (data.remainingTime) {
        startCountdown(data.remainingTime);
    }
    // Hiển thị nút pause, ẩn nút resume
    const btnPause = document.getElementById('btn-pause-game');
    const btnResume = document.getElementById('btn-resume-game');
    if (btnPause) btnPause.style.display = 'inline-block';
    if (btnResume) btnResume.style.display = 'none';
}

// ── 10. BẢNG XẾP HẠNG ────────────────────────────────────────────
function renderLeaderboard(entries = [], tbodyId, directEl) {
    const medals = ['🥇', '🥈', '🥉'];
    const tbody  = directEl || document.getElementById(tbodyId);
    if (!tbody) return;

    if (!entries || entries.length === 0) {
        tbody.innerHTML = '<tr><td colspan="6" style="text-align:center; color:#64748b; padding:12px;">Chưa có dữ liệu</td></tr>';
        return;
    }

    if (directEl) {
        // mini table
        let html = '';
        entries.slice(0, 10).forEach((e, i) => {
            const isMe = e.username === currentUsername;
            html += `<div style="display:flex; align-items:center; gap:10px; padding:4px 0; border-bottom:1px solid rgba(255,255,255,0.06); ${isMe ? 'color:#fde047;' : 'color:#e2e8f0;'}">
                <span style="width:22px; text-align:center;">${medals[i] || (i+1)}</span>
                <span style="flex:1; font-weight:${isMe?'800':'400'};">${e.username}</span>
                <span style="color:#10b981; font-size:12px;">${e.rankPoints || 0} pts</span>
                <span style="color:#94a3b8; font-size:11px;">${e.totalWins || 0}W</span>
            </div>`;
        });
        directEl.innerHTML = html;
    } else {
        // full table body
        let html = '';
        entries.forEach((e, i) => {
            const isMe = e.username === currentUsername;
            html += `<tr style="${isMe ? 'color:#fde047; font-weight:700; background:rgba(253,224,71,0.05);' : ''}">
                <td style="padding:6px 8px;">${medals[i] || (i+1)}</td>
                <td style="padding:6px 8px;">${e.username}</td>
                <td style="padding:6px 8px; text-align:center; color:#10b981; font-weight:700;">${e.rankPoints || 0}</td>
                <td style="padding:6px 8px; text-align:center;">${e.totalWins || 0}</td>
                <td style="padding:6px 8px; text-align:center;">${e.totalGames || 0}</td>
                <td style="padding:6px 8px; text-align:center; color:#ef4444;">${e.totalEliminated || 0}</td>
            </tr>`;
        });
        tbody.innerHTML = html;
    }
}

// ── 11. DANH SÁCH PHÒNG ───────────────────────────────────────────
function renderRoomList(rooms = []) {
    const container = document.getElementById('rooms-list-container');
    if (!container) return;

    if (!rooms || rooms.length === 0) {
        container.innerHTML = '<p style="color:#94a3b8; grid-column:1/-1; text-align:center; padding:30px;">Chưa có phòng nào. Hãy là người đầu tiên tạo phòng!</p>';
        return;
    }

    container.innerHTML = '';
    rooms.forEach(r => {
        const card = document.createElement('div');
        card.className = 'room-card';

        const isPlaying   = (r.gameState === 'QUESTION' || r.gameState === 'RESULT');
        const statusClass = isPlaying ? 'playing' : 'waiting';
        const statusText  = isPlaying ? 'Đang Đấu'  : 'Đang Chờ';

        let adminDeleteBtn = '';
        if (isCurrentUserAdmin && r.roomCode !== 'DT100') {
            adminDeleteBtn = `<button class="btn-join-card" style="background:#ef4444; border-color:#dc2626; margin-left:6px;" onclick="closeRoomByAdmin('${r.roomCode}')">Đóng</button>`;
        }

        card.innerHTML = `
            <div>
                <div class="room-card-header">
                    <span class="room-code-badge">${r.roomCode}</span>
                    <span class="room-status-badge ${statusClass}">${statusText}</span>
                </div>
                <div class="room-card-title">${r.roomName}</div>
                <div style="font-size: 12px; color: #94a3b8; margin-bottom: 8px;">👑 Chủ phòng: <strong>${r.hostUsername}</strong></div>
                <div class="room-card-tags">
                    <span class="room-tag">📚 ${r.topicName}</span>
                    <span class="room-tag">⚡ ${r.difficultyName}</span>
                    <span class="room-tag">🤖 Bot: ${r.botCount}</span>
                    <span class="room-tag">⏱️ 5s / 15s</span>
                </div>
            </div>
            <div class="room-card-footer">
                <span class="room-player-count">👥 ${r.playerCount} Đấu thủ</span>
                <div>
                    <button class="btn-join-card" onclick="joinRoomByCode('${r.roomCode}')">Vào Sàn Đấu</button>
                    ${adminDeleteBtn}
                </div>
            </div>
        `;
        container.appendChild(card);
    });
}

window.joinRoomByCode = function(code) {
    if (!code) return;
    socket.send(JSON.stringify({ type: 'join_room', roomCode: code }));
};

window.closeRoomByAdmin = function(code) {
    if (confirm(`Bạn có chắc chắn muốn đóng phòng [${code}]?`)) {
        socket.send(JSON.stringify({ type: 'admin_close_room', roomCode: code }));
    }
};

// ── 12. CHỐT ĐÁP ÁN ─────────────────────────────────────────────
function selectAnswer(index, btn) {
    if (!amIAlive) return;
    if (window.arenaSound) window.arenaSound.playLockAnswer();

    const btns = document.querySelectorAll('.answer-btn');
    btns.forEach(b => { b.classList.remove('selected'); b.disabled = true; });
    btn.classList.add('selected');
    btn.disabled = false;

    socket.send(JSON.stringify({ type: 'submit_arena_answer', answer: index }));
    document.getElementById('feedback-msg').innerText = '🔒 Đã chốt đáp án! Hồi hộp chờ kết quả từ trường quay...';
}

// ── 13. ĐỒNG HỒ ĐẾM NGƯỢC ───────────────────────────────────────
function startTimer(seconds) {
    clearInterval(countdownInterval);
    const timeDisplay    = document.getElementById('time-left');
    const timerDial      = document.getElementById('timer-dial');
    const timerProgress  = document.getElementById('timer-progress');
    const totalCircumference = 219.91;
    const maxSeconds     = seconds;

    timeDisplay.innerText = seconds;
    if (timerDial) timerDial.classList.remove('danger');
    if (timerProgress) {
        timerProgress.style.strokeDasharray  = `${totalCircumference}`;
        timerProgress.style.strokeDashoffset = '0';
    }
    if (window.arenaSound) window.arenaSound.playTick(false);

    countdownInterval = setInterval(() => {
        seconds--;
        timeDisplay.innerText = Math.max(0, seconds);

        if (timerProgress) {
            const offset = totalCircumference * Math.min(1, Math.max(0, (maxSeconds - seconds) / maxSeconds));
            timerProgress.style.strokeDashoffset = `${offset}`;
        }
        const isDanger = seconds <= 3 && seconds > 0;
        if (isDanger && timerDial) timerDial.classList.add('danger');
        if (seconds > 0 && window.arenaSound) window.arenaSound.playTick(isDanger);
        if (seconds <= 0) {
            clearInterval(countdownInterval);
            document.querySelectorAll('.answer-btn').forEach(b => b.disabled = true);
            if (amIAlive && !document.getElementById('feedback-msg').innerText) {
                document.getElementById('feedback-msg').innerText = '⏰ Đã hết thời gian! Bạn không kịp nộp đáp án.';
            }
        }
    }, 1000);
}

// YÊU CẦU 2 & 5: Hàm startCountdown cho các trường hợp đặc biệt (resume, main player turn)
function startCountdown(seconds) {
    startTimer(seconds);
}

// ── 14. RESET GAME STATE ─────────────────────────────────────────
function resetGameState() {
    amIMainPlayer         = false;
    amIAlive              = true;
    totalInitialOpponents = 0;
    aliveOpponents        = 0;
    totalEliminated       = 0;
    myCurrentScore        = 0;
    lifeTrustUsed         = false;
    lifeAskUsed           = false;
    lifeSurveyUsed        = false;
    eliminatedSet.clear();
    playerSeatMap.clear();
    clearInterval(countdownInterval);
    updateHud();
}

// ── 15. BUTTON EVENTS ────────────────────────────────────────────

// Sẵn Sàng
document.getElementById('btn-toggle-ready').addEventListener('click', () => {
    socket.send(JSON.stringify({ type: 'toggle_ready' }));
});

// Bắt Đầu
document.getElementById('btn-start').addEventListener('click', () => {
    socket.send(JSON.stringify({ type: 'start_arena_game' }));
});

// Rời Phòng
document.getElementById('btn-leave-room').addEventListener('click', () => {
    socket.send(JSON.stringify({ type: 'leave_room' }));
});
document.getElementById('btn-end-leave').addEventListener('click', () => {
    socket.send(JSON.stringify({ type: 'leave_room' }));
});
document.getElementById('btn-back-to-room').addEventListener('click', () => {
    showScreen('waiting');
});

// Sao Chép Mã Phòng
document.getElementById('btn-copy-code').addEventListener('click', () => {
    if (currentRoomCode) {
        navigator.clipboard.writeText(currentRoomCode).then(() => {
            alert(`Đã sao chép mã phòng: ${currentRoomCode}`);
        }).catch(() => {
            prompt('Sao chép mã phòng:', currentRoomCode);
        });
    }
});

// Vào phòng bằng mã
document.getElementById('btn-join-code').addEventListener('click', () => {
    const code = document.getElementById('input-join-code').value.trim().toUpperCase();
    if (!code) { alert('Vui lòng nhập mã phòng!'); return; }
    socket.send(JSON.stringify({ type: 'join_room', roomCode: code }));
});
document.getElementById('input-join-code').addEventListener('keypress', (e) => {
    if (e.key === 'Enter') document.getElementById('btn-join-code').click();
});

// Làm mới danh sách phòng
document.getElementById('btn-refresh-rooms').addEventListener('click', () => {
    socket.send(JSON.stringify({ type: 'get_rooms' }));
});

// Modal Tạo Phòng
const modalCreateRoom = document.getElementById('modal-create-room');
document.getElementById('btn-open-create-modal').addEventListener('click', () => {
    modalCreateRoom.classList.add('active');
    document.getElementById('create-room-name').value = `Sàn Đấu của ${currentUsername || 'Tôi'}`;
    document.getElementById('create-room-code').value = '';
});
document.getElementById('btn-close-modal').addEventListener('click', () => modalCreateRoom.classList.remove('active'));
document.getElementById('btn-cancel-modal').addEventListener('click', () => modalCreateRoom.classList.remove('active'));

// Submit Tạo Phòng
document.getElementById('btn-submit-create-room').addEventListener('click', () => {
    const roomName = document.getElementById('create-room-name').value.trim();
    if (!roomName) { alert('Vui lòng nhập tên phòng!'); return; }

    const roomCode     = document.getElementById('create-room-code').value.trim();
    const topic        = document.getElementById('create-topic').value;
    const difficulty   = document.getElementById('create-difficulty').value;
    const questionCount = parseInt(document.getElementById('create-question-count').value) || 10;
    const botCount     = parseInt(document.getElementById('create-bot-count').value) || 0;

    socket.send(JSON.stringify({
        type: 'create_room',
        roomName, roomCode, topic, difficulty, questionCount, botCount
    }));
    modalCreateRoom.classList.remove('active');
});

// Quyền Trợ Giúp – Tin Người Chơi
document.getElementById('btn-life-trust').addEventListener('click', () => {
    if (lifeTrustUsed) return;
    lifeTrustUsed = true;
    updateLifelineButtons();
    socket.send(JSON.stringify({ type: 'arena_life_trust' }));
});

// Quyền Trợ Giúp – Hỏi Người Chơi (mở modal chọn)
const modalAsk = document.getElementById('modal-ask-player');
document.getElementById('btn-life-ask').addEventListener('click', () => {
    if (lifeAskUsed) return;
    // Hiển thị modal chọn đối thủ thật còn sống
    const listEl = document.getElementById('ask-player-list');
    listEl.innerHTML = '';
    const aliveOpponents_real = realOpponentsList.filter(p => p.isAlive !== false);
    if (aliveOpponents_real.length === 0) {
        alert('Không có đối thủ thật nào để hỏi.');
        return;
    }
    aliveOpponents_real.forEach(p => {
        const btn = document.createElement('button');
        btn.className   = 'btn-primary-action';
        btn.style.width = '100%';
        btn.innerText   = `👤 ${p.username}`;
        btn.onclick     = () => {
            lifeAskUsed = true;
            updateLifelineButtons();
            socket.send(JSON.stringify({ type: 'arena_life_ask', targetUsername: p.username }));
            modalAsk.classList.remove('active');
        };
        listEl.appendChild(btn);
    });
    modalAsk.classList.add('active');
});
document.getElementById('btn-close-ask-modal').addEventListener('click', () => {
    modalAsk.classList.remove('active');
});

// Quyền Trợ Giúp – Khảo Sát
document.getElementById('btn-life-survey').addEventListener('click', () => {
    if (lifeSurveyUsed) return;
    lifeSurveyUsed = true;
    updateLifelineButtons();
    socket.send(JSON.stringify({ type: 'arena_life_survey' }));
});

// Âm Thanh
const btnSound = document.getElementById('btn-sound');
if (btnSound) {
    btnSound.addEventListener('click', () => {
        if (window.arenaSound) {
            const enabled = window.arenaSound.toggle();
            btnSound.innerText = enabled ? '🔊' : '🔇';
        }
    });
}

// Đóng WebSocket khi rời trang
window.addEventListener('beforeunload', () => socket.close());

// YÊU CẦU 5: Tạm dừng và tiếp tục trận đấu
document.getElementById('btn-pause-game').addEventListener('click', () => {
    socket.send(JSON.stringify({ type: 'arena_pause_game' }));
});
document.getElementById('btn-resume-game').addEventListener('click', () => {
    socket.send(JSON.stringify({ type: 'arena_resume_game' }));
});

// Nút rời phòng trong lúc chơi game
document.getElementById('btn-leave-game-ingame').addEventListener('click', () => {
    if (confirm('Bạn có chắc muốn rời phòng? (Tiến trình trò chơi của bạn sẽ bị mất)')) {
        socket.send(JSON.stringify({ type: 'leave_room' }));
    }
});

// Đặc quyền Super Admin: Xóa toàn bộ dữ liệu Bảng Xếp Hạng
const btnClearLeaderboard = document.getElementById('btn-admin-clear-leaderboard');
if (btnClearLeaderboard) {
    btnClearLeaderboard.addEventListener('click', () => {
        if (!isCurrentUserAdmin) {
            alert('Bạn không có quyền quản trị viên!');
            return;
        }
        if (confirm('⚠️ [CẢNH BÁO SUPER ADMIN]\nBạn có chắc chắn muốn xóa toàn bộ dữ liệu Bảng Xếp Hạng Toàn Cầu không?\nHành động này sẽ xóa sạch dữ liệu xếp hạng và không thể hoàn tác!')) {
            socket.send(JSON.stringify({ type: 'admin_clear_leaderboard' }));
        }
    });
}
document.getElementById('btn-leave-result-ingame').addEventListener('click', () => {
    if (confirm('Bạn có chắc muốn rời phòng?')) {
        socket.send(JSON.stringify({ type: 'leave_room' }));
    }
});
