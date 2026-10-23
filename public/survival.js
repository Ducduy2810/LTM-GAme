// ================================================================
// ĐẤU TRƯỜNG SINH TỬ (SURVIVAL ARENA) - CLIENT ENGINE V1
// Luật chơi: Trả lời cùng câu hỏi, sai trừ Máu (HP), HP=0 bị loại!
// ================================================================

(function () {
    'use strict';

    // ── WebSocket Endpoint ──────────────────────────────────────────
    const wsUrl = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws';
    let socket = null;

    // ── Màn hình ứng dụng ───────────────────────────────────────────
    const screens = {
        lobby:   document.getElementById('lobby-screen'),
        waiting: document.getElementById('waiting-screen'),
        game:    document.getElementById('game-screen'),
        result:  document.getElementById('result-screen'),
        end:     document.getElementById('end-screen')
    };

    function showScreen(screenName) {
        for (let key in screens) {
            if (screens[key]) screens[key].classList.remove('active');
        }
        if (screens[screenName]) {
            screens[screenName].classList.add('active');
        }
        window.scrollTo({ top: 0, behavior: 'smooth' });
    }

    // ── Trạng thái người chơi & Trận đấu ────────────────────────────
    let currentUsername     = '';
    let isCurrentUserAdmin  = false;
    let currentRoomCode     = '';
    let currentRoomInfo     = null;
    let isMyHost            = false;

    // Cơ chế Sinh tồn (HP & Điểm)
    let myHp                = 100;
    let maxHp               = 100;
    let myScore             = 0;
    let myIsAlive           = true;
    let myAnswerLocked      = false;
    let totalRounds         = 15;
    let currentRoundNum     = 1;

    // Giai đoạn hiện tại (Phase)
    let currentPhase = {
        name: 'Giai Đoạn 1: Khởi Động',
        penalty: 15,
        timeLimit: 15
    };

    // Danh sách đấu thủ
    let allPlayersList      = [];
    let latestPlayerStates  = [];

    // Đồng hồ đếm ngược
    let countdownInterval   = null;
    let soundEnabled        = true;

    // ── Sound Synthesizer Fallback (Web Audio API) ───────────────────
    class SurvivalSoundFallback {
        constructor() {
            this.ctx = null;
        }
        init() {
            if (!this.ctx) {
                const AudioCtx = window.AudioContext || window.webkitAudioContext;
                if (AudioCtx) this.ctx = new AudioCtx();
            }
            if (this.ctx && this.ctx.state === 'suspended') {
                this.ctx.resume();
            }
        }
        playTick(isDanger = false) {
            if (!soundEnabled) return;
            this.init();
            if (!this.ctx) return;
            const osc = this.ctx.createOscillator();
            const gain = this.ctx.createGain();
            osc.type = isDanger ? 'sawtooth' : 'sine';
            osc.frequency.setValueAtTime(isDanger ? 900 : 540, this.ctx.currentTime);
            gain.gain.setValueAtTime(0.18, this.ctx.currentTime);
            gain.gain.exponentialRampToValueAtTime(0.001, this.ctx.currentTime + 0.08);
            osc.connect(gain);
            gain.connect(this.ctx.destination);
            osc.start();
            osc.stop(this.ctx.currentTime + 0.08);
        }
        playLockAnswer() {
            if (!soundEnabled) return;
            this.init();
            if (!this.ctx) return;
            const osc = this.ctx.createOscillator();
            const gain = this.ctx.createGain();
            osc.type = 'triangle';
            osc.frequency.setValueAtTime(500, this.ctx.currentTime);
            osc.frequency.exponentialRampToValueAtTime(1100, this.ctx.currentTime + 0.16);
            gain.gain.setValueAtTime(0.25, this.ctx.currentTime);
            gain.gain.exponentialRampToValueAtTime(0.01, this.ctx.currentTime + 0.18);
            osc.connect(gain);
            gain.connect(this.ctx.destination);
            osc.start();
            osc.stop(this.ctx.currentTime + 0.18);
        }
        playCorrect() {
            if (!soundEnabled) return;
            this.init();
            if (!this.ctx) return;
            const notes = [523.25, 659.25, 783.99, 1046.50];
            notes.forEach((freq, idx) => {
                const osc = this.ctx.createOscillator();
                const gain = this.ctx.createGain();
                osc.type = 'sine';
                osc.frequency.setValueAtTime(freq, this.ctx.currentTime + idx * 0.07);
                gain.gain.setValueAtTime(0.25, this.ctx.currentTime + idx * 0.07);
                gain.gain.exponentialRampToValueAtTime(0.001, this.ctx.currentTime + idx * 0.07 + 0.5);
                osc.connect(gain);
                gain.connect(this.ctx.destination);
                osc.start(this.ctx.currentTime + idx * 0.07);
                osc.stop(this.ctx.currentTime + idx * 0.07 + 0.5);
            });
        }
        playDamage() {
            if (!soundEnabled) return;
            this.init();
            if (!this.ctx) return;
            const osc = this.ctx.createOscillator();
            const gain = this.ctx.createGain();
            osc.type = 'sawtooth';
            osc.frequency.setValueAtTime(320, this.ctx.currentTime);
            osc.frequency.linearRampToValueAtTime(90, this.ctx.currentTime + 0.35);
            gain.gain.setValueAtTime(0.35, this.ctx.currentTime);
            gain.gain.exponentialRampToValueAtTime(0.01, this.ctx.currentTime + 0.4);
            osc.connect(gain);
            gain.connect(this.ctx.destination);
            osc.start();
            osc.stop(this.ctx.currentTime + 0.4);
        }
        playEliminated() {
            if (!soundEnabled) return;
            this.init();
            if (!this.ctx) return;
            // Tiếng hồi chuông tử thần
            const freqs = [180, 150, 120];
            freqs.forEach((f, idx) => {
                const osc = this.ctx.createOscillator();
                const gain = this.ctx.createGain();
                osc.type = 'sawtooth';
                osc.frequency.setValueAtTime(f, this.ctx.currentTime + idx * 0.18);
                gain.gain.setValueAtTime(0.3, this.ctx.currentTime + idx * 0.18);
                gain.gain.exponentialRampToValueAtTime(0.001, this.ctx.currentTime + idx * 0.18 + 0.6);
                osc.connect(gain);
                gain.connect(this.ctx.destination);
                osc.start(this.ctx.currentTime + idx * 0.18);
                osc.stop(this.ctx.currentTime + idx * 0.18 + 0.6);
            });
        }
        playVictory() {
            if (!soundEnabled) return;
            this.init();
            if (!this.ctx) return;
            const chords = [523.25, 659.25, 783.99, 1046.50, 1318.51];
            chords.forEach((freq, idx) => {
                const osc = this.ctx.createOscillator();
                const gain = this.ctx.createGain();
                osc.type = 'triangle';
                osc.frequency.setValueAtTime(freq, this.ctx.currentTime + idx * 0.09);
                gain.gain.setValueAtTime(0.28, this.ctx.currentTime + idx * 0.09);
                gain.gain.exponentialRampToValueAtTime(0.001, this.ctx.currentTime + idx * 0.09 + 1.2);
                osc.connect(gain);
                gain.connect(this.ctx.destination);
                osc.start(this.ctx.currentTime + idx * 0.09);
                osc.stop(this.ctx.currentTime + idx * 0.09 + 1.2);
            });
        }
    }

    const sound = window.arenaSound || new SurvivalSoundFallback();

    // ── 1. KIỂM TRA ĐĂNG NHẬP & PHÂN QUYỀN ───────────────────────────
    fetch('/auth/me')
        .then(r => r.json())
        .then(data => {
            if (data.loggedIn && data.user) {
                currentUsername    = data.user.username;
                isCurrentUserAdmin = !!data.user.isAdmin
                    || (data.user.email === 'mainung28@gmail.com')
                    || currentUsername === 'admin'
                    || currentUsername === 'DuyNguyen';

                initWebSocket();
            } else {
                alert('Bạn chưa đăng nhập! Vui lòng đăng nhập trước khi tham gia Đấu Trường Sinh Tử.');
                window.location.href = 'index.html';
            }
        })
        .catch(() => {
            window.location.href = 'index.html';
        });

    // ── 2. KHỞI TẠO WEBSOCKET & XỬ LÝ SỰ KIỆN ────────────────────────
    function initWebSocket() {
        socket = new WebSocket(wsUrl);

        socket.onopen = () => {
            console.log('✅ Kết nối WebSocket Survival thành công');

            // Kiểm tra xem có mã phòng từ URL param (?room=CODE) không
            const params = new URLSearchParams(window.location.search);
            const directRoom = params.get('room');
            if (directRoom) {
                joinRoomByCode(directRoom.trim().toUpperCase());
            } else {
                // Đăng ký lấy danh sách phòng ở Sảnh
                socket.send(JSON.stringify({ type: 'get_survival_rooms' }));
            }
        };

        socket.onmessage = (event) => {
            try {
                const data = JSON.parse(event.data);
                handleSocketMessage(data);
            } catch (err) {
                console.error('Lỗi phân tích JSON WebSocket:', err);
            }
        };

        socket.onerror = (err) => {
            console.error('Lỗi WebSocket Survival:', err);
        };

        socket.onclose = () => {
            console.warn('WebSocket Survival đã ngắt kết nối.');
        };
    }

    // ── 3. ROUTER XỬ LÝ MESSAGE WEBSOCKET ────────────────────────────
    function handleSocketMessage(data) {
        if (!data || !data.type) return;

        switch (data.type) {
            case 'auth_error':
                alert(data.message || 'Lỗi xác thực người dùng!');
                window.location.href = 'index.html';
                break;

            case 'survival_error':
                alert(data.message || 'Đã xảy ra lỗi trong Đấu Trường Sinh Tử!');
                break;

            // ── SẢNH: DANH SÁCH PHÒNG ────────────────────────────────
            case 'survival_room_list':
                renderRoomList(data.rooms || []);
                break;

            // ── THÔNG TIN PHÒNG ĐÃ THAM GIA ──────────────────────────
            case 'survival_room_info':
                currentRoomInfo = data;
                currentRoomCode = data.roomCode;
                maxHp           = data.startingHp || 100;
                myHp            = maxHp;
                totalRounds     = data.totalRounds || 15;

                updateRoomInfoStrip(data);
                showScreen('waiting');
                break;

            // ── RỜI PHÒNG THÀNH CÔNG ─────────────────────────────────
            case 'left_survival_room_success':
            case 'survival_left_room_success':
                currentRoomCode = '';
                currentRoomInfo = null;
                clearInterval(countdownInterval);
                resetSurvivalState();
                showScreen('lobby');
                socket.send(JSON.stringify({ type: 'get_survival_rooms' }));
                break;

            // ── CẬP NHẬT NGƯỜI CHƠI TRONG PHÒNG CHỜ ──────────────────
            case 'update_survival_players':
            case 'survival_update_players':
                handleUpdatePlayers(data);
                break;

            // ── BẮT ĐẦU TRẬN ĐẤU SINH TỬ ─────────────────────────────
            case 'survival_game_started':
                handleGameStarted(data);
                break;

            // ── CÂU HỎI MỚI ──────────────────────────────────────────
            case 'survival_new_question':
                handleNewQuestion(data);
                break;

            // ── KẾT QUẢ VÒNG ĐẤU ─────────────────────────────────────
            case 'survival_round_result':
                handleRoundResult(data);
                break;

            // ── KẾT THÚC TOÀN BỘ TRẬN ĐẤU ───────────────────────────
            case 'survival_end_game':
                handleEndGame(data);
                break;

            default:
                console.log('Thông điệp chưa hỗ trợ:', data.type, data);
        }
    }

    // ── 4. THANH MÁU (HP BAR RENDERING) ──────────────────────────────
    function renderHpBar(hp, max) {
        const safeMax = (max && max > 0) ? max : 100;
        const current = Math.max(0, hp !== undefined ? hp : 0);
        const pct = Math.max(0, Math.min(100, (current / safeMax) * 100));
        const color = pct > 60 ? '#10b981' : pct > 30 ? '#f59e0b' : '#ef4444';
        return `<div class='hp-bar'><div class='hp-bar-fill' style='width:${pct}%;background:${color};'></div></div>`;
    }

    function updateHpBar(hp, max) {
        const safeMax = (max && max > 0) ? max : 100;
        const safeHp  = Math.max(0, Math.min(safeMax, hp !== undefined ? hp : 0));
        const pct     = Math.max(0, Math.min(100, (safeHp / safeMax) * 100));
        const color   = pct > 60 ? '#10b981' : pct > 30 ? '#f59e0b' : '#ef4444';

        // Cập nhật HUD Header
        const hudHpVal  = document.getElementById('hud-hp-val');
        const hudHpFill = document.getElementById('hud-hp-fill');
        const hudHpBar  = document.getElementById('hud-hp-bar');
        if (hudHpVal)  hudHpVal.innerText = `${safeHp} / ${safeMax}`;
        if (hudHpFill) {
            hudHpFill.style.width = `${pct}%`;
            hudHpFill.style.background = color;
        }
        if (hudHpBar) {
            if (pct <= 30) hudHpBar.classList.add('danger');
            else hudHpBar.classList.remove('danger');
        }

        // Cập nhật Game HUD
        const gameMyHp  = document.getElementById('game-my-hp');
        const gameHpFill = document.getElementById('game-hp-fill');
        const gameHpBar = document.getElementById('game-hp-bar');
        if (gameMyHp)  gameMyHp.innerText = `${safeHp} / ${safeMax}`;
        if (gameHpFill) {
            gameHpFill.style.width = `${pct}%`;
            gameHpFill.style.background = color;
        }
        if (gameHpBar) {
            if (pct <= 30) gameHpBar.classList.add('danger');
            else gameHpBar.classList.remove('danger');
        }
    }

    function updateScoreHud(score) {
        myScore = score !== undefined ? score : myScore;
        const hudScore = document.getElementById('hud-score-val');
        const gameScore = document.getElementById('game-my-score');
        if (hudScore)  hudScore.innerText = myScore;
        if (gameScore) gameScore.innerText = myScore;
    }

    function updateAliveHud(aliveCount) {
        const hudAlive = document.getElementById('hud-alive-val');
        if (hudAlive) hudAlive.innerText = aliveCount !== undefined ? aliveCount : 0;
    }

    // ── 5. CẬP NHẬT THÔNG TIN DẢI PHÒNG (ROOM INFO STRIP) ─────────────
    function updateRoomInfoStrip(info) {
        const codeBadge    = document.getElementById('room-code-badge');
        const nameDisplay  = document.getElementById('room-name-display');
        const summaryText  = document.getElementById('room-settings-summary');

        if (codeBadge)   codeBadge.innerText   = info.roomCode || 'SINHTU';
        if (nameDisplay) nameDisplay.innerText = info.roomName || 'Phòng Đấu Sinh Tử';
        if (summaryText) {
            const topicName = formatTopicName(info.topic || info.topicName);
            summaryText.innerText = `[${topicName}] • HP: ${info.startingHp || 100} • ${info.totalRounds || 15} Vòng`;
        }
    }

    function formatTopicName(topic) {
        switch (topic) {
            case 'NETWORKING': return 'Mạng Máy Tính & Lập Trình';
            case 'SCIENCE':    return 'Khoa Học & Tự Nhiên';
            case 'HISTORY':    return 'Lịch Sử & Văn Hóa';
            default:           return 'Tổng Hợp Đấu Trường';
        }
    }

    // ── 6. RENDER DANH SÁCH PHÒNG Ở SẢNH ──────────────────────────────
    function renderRoomList(rooms) {
        const container = document.getElementById('rooms-list-container');
        if (!container) return;

        if (!rooms || rooms.length === 0) {
            container.innerHTML = `
                <div style="grid-column: 1/-1; text-align: center; padding: 40px 20px; color: #94a3b8;">
                    <div style="font-size: 32px; margin-bottom: 8px;">⚔️</div>
                    <p style="font-size: 15px; margin: 0 0 10px 0;">Chưa có phòng đấu sinh tử nào đang mở.</p>
                    <button class="btn-red-action" onclick="document.getElementById('btn-open-create-modal').click()">
                        Tạo Phòng Ngay Bây Giờ
                    </button>
                </div>
            `;
            return;
        }

        container.innerHTML = '';
        rooms.forEach(r => {
            const card = document.createElement('div');
            card.className = 'room-card';

            const isPlaying   = (r.gameState === 'QUESTION' || r.gameState === 'RESULT' || r.isPlaying);
            const statusClass = isPlaying ? 'playing' : 'waiting';
            const statusText  = isPlaying ? 'Đang Diễn Ra' : 'Đang Chờ';

            let adminCloseBtn = '';
            if (isCurrentUserAdmin) {
                adminCloseBtn = `
                    <button class="btn-join-card" style="background:#7f1d1d; border-color:#dc2626; margin-left:6px;" 
                            onclick="window.survivalAdminCloseRoom('${r.roomCode}')">
                        Đóng
                    </button>
                `;
            }

            card.innerHTML = `
                <div>
                    <div class="room-card-header">
                        <span class="room-code-badge">${r.roomCode}</span>
                        <span class="room-status-badge ${statusClass}">${statusText}</span>
                    </div>
                    <div class="room-card-title" title="${escapeHtml(r.roomName)}">${escapeHtml(r.roomName)}</div>
                    <div class="room-card-tags">
                        <span class="room-tag">📚 ${formatTopicName(r.topic || r.topicName)}</span>
                        <span class="room-tag hp-tag">🩸 HP: ${r.startingHp || 100}</span>
                        <span class="room-tag round-tag">🎯 ${r.totalRounds || 15} Vòng</span>
                    </div>
                </div>
                <div class="room-card-footer">
                    <span class="room-player-count">👥 ${r.playerCount || 1} Đấu thủ</span>
                    <div>
                        <button class="btn-join-card" onclick="window.joinSurvivalRoom('${r.roomCode}')">
                            Vào Tham Chiến
                        </button>
                        ${adminCloseBtn}
                    </div>
                </div>
            `;
            container.appendChild(card);
        });
    }

    // ── 7. HANDLER: CẬP NHẬT PHÒNG CHỜ ───────────────────────────────
    function handleUpdatePlayers(data) {
        const players = data.players || [];
        allPlayersList = players;

        // Số lượng đấu thủ
        const countEl = document.getElementById('player-count');
        if (countEl) countEl.innerText = players.length;

        // Host xác định
        const hostUsername = data.hostUsername;
        isMyHost = (currentUsername === hostUsername);

        // Nút Bắt đầu cho Host
        const btnStart = document.getElementById('btn-start');
        if (btnStart) {
            btnStart.style.display = isMyHost ? 'inline-block' : 'none';
        }

        // Render danh sách chip người chơi
        const listContainer = document.getElementById('player-list');
        if (listContainer) {
            listContainer.innerHTML = '';
            players.forEach(p => {
                const chip = document.createElement('div');
                const isHost = (p.username === hostUsername);
                const isMe   = (p.username === currentUsername);

                chip.className = `player-chip ${isHost ? 'is-host' : ''} ${isMe ? 'is-me' : ''}`;

                const readyTag = isHost
                    ? `<span class="ready-badge is-ready">👑 CHỦ PHÒNG</span>`
                    : (p.isReady
                        ? `<span class="ready-badge is-ready">✓ SẴN SÀNG</span>`
                        : `<span class="ready-badge not-ready">⌛ ĐANG CHỜ</span>`);

                chip.innerHTML = `
                    <span>${isHost ? '👑' : '👤'}</span>
                    <span>${escapeHtml(p.username)}${isMe ? ' (Bạn)' : ''}</span>
                    ${readyTag}
                `;
                listContainer.appendChild(chip);
            });
        }

        // Nút sẵn sàng của bản thân
        const me = players.find(p => p.username === currentUsername);
        const btnToggleReady = document.getElementById('btn-toggle-ready');
        if (btnToggleReady && me) {
            if (isMyHost) {
                btnToggleReady.style.display = 'none';
            } else {
                btnToggleReady.style.display = 'inline-block';
                if (me.isReady) {
                    btnToggleReady.classList.add('ready-active');
                    btnToggleReady.innerText = '❌ HỦY SẴN SÀNG';
                } else {
                    btnToggleReady.classList.remove('ready-active');
                    btnToggleReady.innerText = '✅ SẴN SÀNG';
                }
            }
        }

        // Thông điệp trạng thái chờ
        const waitingMsg = document.getElementById('waiting-msg');
        if (waitingMsg) {
            if (data.allReady) {
                waitingMsg.innerText = isMyHost
                    ? '⚡ Tất cả đấu thủ đã sẵn sàng! Bạn có thể bấm [Khởi Tranh Sinh Tử]!'
                    : '⚡ Tất cả đã sẵn sàng! Đang chờ Chủ phòng bắt đầu cuộc chiến...';
                waitingMsg.style.color = '#34d399';
            } else {
                waitingMsg.innerText = 'Đang chờ các đấu thủ sẵn sàng...';
                waitingMsg.style.color = '#fde047';
            }
        }
    }

    // ── 8. HANDLER: GAME BẮT ĐẦU ─────────────────────────────────────
    function handleGameStarted(data) {
        clearInterval(countdownInterval);
        resetSurvivalState();

        if (data.startingHp) maxHp = data.startingHp;
        if (data.totalRounds) totalRounds = data.totalRounds;
        myHp = maxHp;
        myScore = 0;
        myIsAlive = true;

        updateHpBar(myHp, maxHp);
        updateScoreHud(0);
        updateAliveHud(data.aliveCount || (data.players ? data.players.length : 1));

        const myStatus = document.getElementById('my-status');
        if (myStatus) {
            myStatus.className = 'player-status-badge alive';
            document.getElementById('my-status-icon').innerText = '💚';
            document.getElementById('my-status-text').innerText = 'CÒN SỐNG';
        }

        showScreen('game');
    }

    // ── 9. HANDLER: CÂU HỎI MỚI (NEW QUESTION) ────────────────────────
    function handleNewQuestion(data) {
        showScreen('game');
        myAnswerLocked = false;

        // Cập nhật số vòng và giai đoạn
        currentRoundNum = data.round || data.questionId || 1;
        totalRounds     = data.totalRounds || totalRounds || 15;

        // Cập nhật trạng thái người chơi nếu server gửi (để sync HP sau khi trả lời sai)
        if (data.playerStates) {
            latestPlayerStates = data.playerStates;
            const meInStates = data.playerStates.find(p => p.username === currentUsername);
            if (meInStates) {
                myHp = meInStates.hp !== undefined ? meInStates.hp : myHp;
                myScore = meInStates.score !== undefined ? meInStates.score : myScore;
                myIsAlive = (meInStates.isAlive !== false) && (myHp > 0);
                updateHpBar(myHp, maxHp);
                updateScoreHud(myScore);
            }
        }

        // Cập nhật Phase (Giai đoạn)
        if (data.phase || data.phaseName) {
            currentPhase.name = data.phaseName || data.phase || currentPhase.name;
        }
        if (data.penalty !== undefined) {
            currentPhase.penalty = data.penalty;
        }
        if (data.timeLimit !== undefined) {
            currentPhase.timeLimit = data.timeLimit;
        }

        // Cập nhật Phase Indicator badge
        const phaseIndicator = document.getElementById('phase-indicator');
        const phaseNameText  = document.getElementById('phase-name-text');
        if (phaseNameText) {
            phaseNameText.innerText = currentPhase.name.toUpperCase();
        }
        if (phaseIndicator) {
            phaseIndicator.className = 'phase-indicator';
            const phaseLower = currentPhase.name.toLowerCase();
            if (phaseLower.includes('3') || phaseLower.includes('tử') || phaseLower.includes('sinh tử')) {
                phaseIndicator.classList.add('phase-3');
            } else if (phaseLower.includes('2') || phaseLower.includes('tăng tốc')) {
                phaseIndicator.classList.add('phase-2');
            } else {
                phaseIndicator.classList.add('phase-1');
            }
        }

        // Question badge
        const badge = document.getElementById('question-badge');
        if (badge) {
            badge.innerText = `VÒNG ${String(currentRoundNum).padStart(2, '0')} / ${totalRounds} • ${currentPhase.name.toUpperCase()}`;
        }

        // Penalty warning (đã xóa khỏi giao diện)
        // const penaltyWarning = document.getElementById('penalty-warning');
        // if (penaltyWarning) { ... }

        // Nội dung câu hỏi
        const qText = document.getElementById('question-text');
        if (qText) qText.innerText = data.text || 'Đang tải nội dung câu hỏi...';

        // Phản hồi
        const feedbackMsg = document.getElementById('feedback-msg');
        if (feedbackMsg) {
            if (!myIsAlive) {
                feedbackMsg.innerText = '💀 Bạn đã bị loại và đang theo dõi trận đấu ở chế độ Khán Giả.';
                feedbackMsg.style.color = '#ef4444';
            } else {
                feedbackMsg.innerText = '';
            }
        }

        // Cập nhật số đấu thủ còn sống nếu server gửi
        if (data.aliveCount !== undefined) {
            updateAliveHud(data.aliveCount);
        }

        // Render 4 đáp án A, B, C, D
        const container = document.getElementById('options-container');
        if (container) {
            container.innerHTML = '';
            const letters = ['A', 'B', 'C', 'D'];
            const options = data.options || data.choices || [];

            options.forEach((opt, index) => {
                const btn = document.createElement('button');
                btn.className = 'answer-btn';
                btn.innerHTML = `
                    <span class="answer-letter">${letters[index] || ''}</span>
                    <span class="answer-text">${escapeHtml(opt)}</span>
                `;

                // Nếu đã bị loại, vô hiệu hóa nút bấm
                if (!myIsAlive) {
                    btn.disabled = true;
                }

                btn.onclick = () => selectSurvivalAnswer(index, btn);
                container.appendChild(btn);
            });
        }

        // Bắt đầu đồng hồ đếm ngược với thời gian riêng biệt của Phase
        const phaseTime = currentPhase.timeLimit || data.timeLimit || 15;
        startTimer(phaseTime);
    }

    // ── 10. CHỌN ĐÁP ÁN (SUBMIT ANSWER) ──────────────────────────────
    function selectSurvivalAnswer(index, btn) {
        if (!myIsAlive || myAnswerLocked) return;
        myAnswerLocked = true;

        if (sound) sound.playLockAnswer();

        const btns = document.querySelectorAll('.answer-btn');
        btns.forEach(b => {
            b.classList.remove('selected');
            b.disabled = true;
        });

        btn.classList.add('selected');
        btn.disabled = false;

        socket.send(JSON.stringify({
            type: 'submit_survival_answer',
            answer: index
        }));

        const feedbackMsg = document.getElementById('feedback-msg');
        if (feedbackMsg) {
            feedbackMsg.innerText = '🔒 Đã chốt phương án sinh tồn! Hồi hộp chờ phán quyết...';
            feedbackMsg.style.color = '#fde047';
        }
    }

    // ── 11. ĐỒNG HỒ ĐẾM NGƯỢC CÔNG NGHỆ CAO ───────────────────────────
    function startTimer(seconds) {
        clearInterval(countdownInterval);
        const timeDisplay    = document.getElementById('time-left');
        const timerDial      = document.getElementById('timer-dial');
        const timerProgress  = document.getElementById('timer-progress');
        const totalCircumference = 219.91; // 2 * PI * 35
        const maxSeconds     = seconds > 0 ? seconds : 15;

        if (timeDisplay) timeDisplay.innerText = seconds;
        if (timerDial) timerDial.classList.remove('danger');
        if (timerProgress) {
            timerProgress.style.strokeDasharray  = `${totalCircumference}`;
            timerProgress.style.strokeDashoffset = '0';
        }

        if (sound) sound.playTick(false);

        countdownInterval = setInterval(() => {
            seconds--;
            if (timeDisplay) timeDisplay.innerText = Math.max(0, seconds);

            if (timerProgress) {
                const fraction = Math.min(1, Math.max(0, (maxSeconds - seconds) / maxSeconds));
                timerProgress.style.strokeDashoffset = `${totalCircumference * fraction}`;
            }

            const isDanger = (seconds <= 3 && seconds > 0);
            if (isDanger && timerDial) timerDial.classList.add('danger');
            if (seconds > 0 && sound) sound.playTick(isDanger);

            if (seconds <= 0) {
                clearInterval(countdownInterval);
                document.querySelectorAll('.answer-btn').forEach(b => b.disabled = true);

                if (myIsAlive && !myAnswerLocked) {
                    const feedbackMsg = document.getElementById('feedback-msg');
                    if (feedbackMsg) {
                        feedbackMsg.innerText = '⏰ Hết giờ! Bạn không kịp nộp đáp án và sẽ chịu phạt trừ máu!';
                        feedbackMsg.style.color = '#ef4444';
                    }
                }
            }
        }, 1000);
    }

    // ── 12. HANDLER: KẾT QUẢ VÒNG ĐẤU (ROUND RESULT) ─────────────────
    function handleRoundResult(data) {
        clearInterval(countdownInterval);
        showScreen('result');

        const correctIndex = data.correctAnswerIndex !== undefined ? data.correctAnswerIndex : (data.correctAnswer !== undefined ? data.correctAnswer : data.correctIndex);
        const letters = ['A', 'B', 'C', 'D'];
        const correctLetter = (correctIndex >= 0 && correctIndex < 4) ? letters[correctIndex] : '?';

        // YÊU CẦU 1: Lấy thông tin đáp án của bản thân từ playerAnswerResults
        const playerAnswerResults = data.playerAnswerResults || {};
        const myAnswerResult = playerAnswerResults[currentUsername];
        const myAnswer = myAnswerResult ? myAnswerResult.answer : null;
        const myIsCorrect = myAnswerResult ? myAnswerResult.isCorrect : false;

        // Tìm trạng thái của bản thân trong vòng này
        const playerStates = data.playerStates || data.standings || [];
        latestPlayerStates = playerStates;

        const meInResult = playerStates.find(p => p.username === currentUsername);

        if (meInResult) {
            myHp      = meInResult.hp !== undefined ? meInResult.hp : myHp;
            myScore   = meInResult.score !== undefined ? meInResult.score : myScore;
            myIsAlive = (meInResult.isAlive !== false) && (myHp > 0);
        }

        // Cập nhật HUDs
        updateHpBar(myHp, maxHp);
        updateScoreHud(myScore);
        if (data.survivorsCount !== undefined) {
            updateAliveHud(data.survivorsCount);
        }

        // Cập nhật trạng thái sống còn
        const myStatus = document.getElementById('my-status');
        if (myStatus) {
            if (myIsAlive) {
                myStatus.className = 'player-status-badge alive';
                document.getElementById('my-status-icon').innerText = '💚';
                document.getElementById('my-status-text').innerText = 'CÒN SỐNG';
            } else {
                myStatus.className = 'player-status-badge eliminated';
                document.getElementById('my-status-icon').innerText = '💀';
                document.getElementById('my-status-text').innerText = 'ĐÃ BỊ LOẠI';
            }
        }

        // YÊU CẦU 1: Hiển thị đáp án người chơi chọn và kết quả đúng/sai
        const resultInfo = document.getElementById('result-info');
        if (resultInfo) {
            let statusText = '';
            const myAnswerLetter = (myAnswer >= 0 && myAnswer < 4) ? letters[myAnswer] : 'Không trả lời';

            if (myIsCorrect) {
                statusText = `<span style="color:#10b981;">✅ Bạn chọn <strong>[${myAnswerLetter}]</strong> - CHÍNH XÁC! Bảo toàn Máu và nhận điểm thưởng.</span>`;
                if (sound) sound.playCorrect();
            } else {
                const penaltyNum = data.penalty || currentPhase.penalty || 15;
                if (!myIsAlive) {
                    statusText = `<span style="color:#ef4444;">❌ Bạn chọn <strong>[${myAnswerLetter}]</strong> - SAI! 💀 BẠN ĐÃ CẠN KIỆT HP VÀ CHÍNH THỨC BỊ LOẠI KHỎI ĐẤU TRƯỜNG!</span>`;
                    if (sound) sound.playEliminated();
                } else {
                    statusText = `<span style="color:#ef4444;">❌ Bạn chọn <strong>[${myAnswerLetter}]</strong> - SAI! Bị trừ -${penaltyNum} HP. Hãy cẩn trọng ở vòng kế!</span>`;
                    if (sound) sound.playDamage();
                }
            }

            resultInfo.innerHTML = `
                <div>Đáp án chính xác: <strong style="color:#38bdf8; font-size:1.3rem;">[${correctLetter}]</strong></div>
                <div style="margin-top:6px;">${statusText}</div>
            `;
        }

        // Thống kê 4 ô
        const survivorCount       = document.getElementById('survivor-count');
        const eliminatedRoundCount = document.getElementById('eliminated-round-count');
        const penaltyAppliedCount = document.getElementById('penalty-applied-count');
        const resultMyHp          = document.getElementById('result-my-hp');

        if (survivorCount) {
            survivorCount.innerText = data.survivorsCount !== undefined ? data.survivorsCount : '0';
        }
        if (eliminatedRoundCount) {
            const elimThisRound = data.eliminatedThisRound || [];
            eliminatedRoundCount.innerText = elimThisRound.length;
        }
        if (penaltyAppliedCount) {
            const penaltyNum = data.penaltyApplied || currentPhase.penalty || 15;
            penaltyAppliedCount.innerText = `-${penaltyNum} HP`;
        }
        if (resultMyHp) {
            resultMyHp.innerText = `${myHp} HP`;
            resultMyHp.style.color = myHp > 50 ? '#10b981' : (myHp > 20 ? '#fde047' : '#ef4444');
        }

        // Danh sách đấu thủ vừa bị loại vòng này
        const eliminatedListEl = document.getElementById('eliminated-list');
        if (eliminatedListEl) {
            const elimThisRound = data.eliminatedThisRound || [];
            if (elimThisRound.length > 0) {
                eliminatedListEl.innerText = elimThisRound.map(u => typeof u === 'string' ? u : u.username).join(', ');
            } else {
                eliminatedListEl.innerText = 'Không có đấu thủ nào bị loại vòng này.';
            }
        }

        // Khung thưởng mốc sinh tồn (Milestone bonus)
        const milestoneBox = document.getElementById('milestone-bonus-box');
        const milestoneText = document.getElementById('milestone-bonus-text');
        if (milestoneBox && milestoneText) {
            if (data.milestoneBonus) {
                milestoneBox.style.display = 'flex';
                milestoneText.innerText = typeof data.milestoneBonus === 'string'
                    ? data.milestoneBonus
                    : `⭐ Cột mốc sinh tồn đạt được! Thưởng +${data.milestoneBonus.hp || 0} HP & +${data.milestoneBonus.score || 0} Điểm!`;
            } else {
                milestoneBox.style.display = 'none';
            }
        }

        // Render bảng xếp hạng sinh tồn vòng hiện tại
        renderPlayerStandings(playerStates, 'round-standings-body');
    }

    // ── 13. RENDER BẢNG XẾP HẠNG SINH TỒN (STANDINGS TABLE) ──────────
    function renderPlayerStandings(playerStates, targetBodyId) {
        const tbody = document.getElementById(targetBodyId);
        if (!tbody) return;

        if (!playerStates || playerStates.length === 0) {
            tbody.innerHTML = `<tr><td colspan="5" style="text-align:center; color:#64748b; padding:16px;">Chưa có dữ liệu đấu thủ</td></tr>`;
            return;
        }

        // Sắp xếp: Còn sống lên trước, theo HP giảm dần, rồi theo Score giảm dần
        const sorted = [...playerStates].sort((a, b) => {
            const aAlive = (a.isAlive !== false) && ((a.hp || 0) > 0);
            const bAlive = (b.isAlive !== false) && ((b.hp || 0) > 0);
            if (aAlive !== bAlive) return bAlive ? 1 : -1;
            if ((b.hp || 0) !== (a.hp || 0)) return (b.hp || 0) - (a.hp || 0);
            return (b.score || 0) - (a.score || 0);
        });

        const medals = ['🥇', '🥈', '🥉'];
        let html = '';

        sorted.forEach((p, index) => {
            const isMe = (p.username === currentUsername);
            const isAlive = (p.isAlive !== false) && ((p.hp || 0) > 0);
            const rankLabel = medals[index] || (index + 1);
            const playerHp = Math.max(0, p.hp !== undefined ? p.hp : 0);
            const pMaxHp = p.maxHp || maxHp || 100;

            const rowClass = `${isMe ? 'is-me-row' : ''} ${!isAlive ? 'eliminated-row' : ''}`;
            const statusBadge = isAlive
                ? `<span style="color:#10b981; font-weight:800;">💚 SỐNG</span>`
                : `<span style="color:#ef4444; font-weight:800;">💀 ĐÃ LOẠI</span>`;

            html += `
                <tr class="${rowClass}">
                    <td style="font-weight:900;">${rankLabel}</td>
                    <td>
                        <strong>${escapeHtml(p.username)}</strong>
                        ${isMe ? ' <span style="color:#fde047; font-size:11px;">(Bạn)</span>' : ''}
                    </td>
                    <td>
                        <div style="display:flex; align-items:center; gap:8px;">
                            <div style="flex:1;">${renderHpBar(playerHp, pMaxHp)}</div>
                            <span style="font-size:11px; font-weight:800; min-width:38px; text-align:right;">${playerHp}</span>
                        </div>
                    </td>
                    <td style="text-align:center; color:#fde047; font-weight:800;">${p.score || 0}</td>
                    <td style="text-align:right;">${statusBadge}</td>
                </tr>
            `;
        });

        tbody.innerHTML = html;
    }

    // ── 14. HANDLER: KẾT THÚC TRẬN ĐẤU (END GAME) ─────────────────────
    function handleEndGame(data) {
        clearInterval(countdownInterval);
        showScreen('end');

        const trophyEl    = document.getElementById('end-trophy');
        const endTitle    = document.getElementById('end-title');
        const endReason   = document.getElementById('end-reason');
        const winnerName  = document.getElementById('winner-name');
        const endMyRank   = document.getElementById('end-my-rank');
        const endMyHp     = document.getElementById('end-my-hp');
        const endMyScore  = document.getElementById('end-my-score');

        const standings = data.finalStandings || data.playerStates || latestPlayerStates || [];
        const winner = data.winner || (standings.length > 0 ? standings[0].username : 'Không có');

        if (winnerName) {
            winnerName.innerText = `👑 ${winner}`;
        }
        if (endReason) {
            endReason.innerText = data.reason || 'Cuộc chiến sinh tồn đã hoàn thành tất cả các vòng!';
        }

        // Kiểm tra xem bản thân có thắng hay không
        const isWinner = (winner === currentUsername) || (data.isWin === true);

        if (isWinner) {
            if (trophyEl) trophyEl.innerText = '🏆';
            if (endTitle) {
                endTitle.innerText = 'CHIẾN THẮNG TUYỆT ĐỐI!';
                endTitle.style.color = '#fde047';
            }
            if (sound) sound.playVictory();
        } else if (myIsAlive) {
            if (trophyEl) trophyEl.innerText = '🏅';
            if (endTitle) {
                endTitle.innerText = 'BẠN ĐÃ SỐNG SÓT ĐẾN CÙNG!';
                endTitle.style.color = '#38bdf8';
            }
        } else {
            if (trophyEl) trophyEl.innerText = '💀';
            if (endTitle) {
                endTitle.innerText = 'BẠN ĐÃ BỊ LOẠI TRƯỚC VẠCH ĐÍCH';
                endTitle.style.color = '#ef4444';
            }
        }

        // Tìm vị trí xếp hạng của mình
        let myRank = '-';
        const sorted = [...standings].sort((a, b) => {
            const aAlive = (a.isAlive !== false) && ((a.hp || 0) > 0);
            const bAlive = (b.isAlive !== false) && ((b.hp || 0) > 0);
            if (aAlive !== bAlive) return bAlive ? 1 : -1;
            if ((b.hp || 0) !== (a.hp || 0)) return (b.hp || 0) - (a.hp || 0);
            return (b.score || 0) - (a.score || 0);
        });

        const myIdx = sorted.findIndex(p => p.username === currentUsername);
        if (myIdx >= 0) myRank = `#${myIdx + 1}`;

        if (endMyRank)  endMyRank.innerText  = myRank;
        if (endMyHp)    endMyHp.innerText    = `${Math.max(0, myHp)} HP`;
        if (endMyScore) endMyScore.innerText = `${myScore}`;

        // Render bảng xếp hạng chung cuộc
        renderPlayerStandings(sorted, 'end-standings-body');
    }

    // ── 15. RESET STATE KHI RỜI TRẬN ─────────────────────────────────
    function resetSurvivalState() {
        myHp            = maxHp;
        myScore         = 0;
        myIsAlive       = true;
        myAnswerLocked  = false;
        currentRoundNum = 1;
        currentPhase    = {
            name: 'Giai Đoạn 1: Khởi Động',
            penalty: 15,
            timeLimit: 15
        };
        updateHpBar(myHp, maxHp);
        updateScoreHud(0);
    }

    // ── 16. TOÀN BỘ SỰ KIỆN GIAO DIỆN (UI EVENT LISTENERS) ───────────

    // Vào phòng bằng mã code
    function joinRoomByCode(code) {
        if (!code) {
            alert('Vui lòng nhập mã phòng!');
            return;
        }
        if (!socket || socket.readyState !== WebSocket.OPEN) {
            alert('Chưa kết nối đến máy chủ! Vui lòng thử lại sau giây lát.');
            return;
        }
        socket.send(JSON.stringify({
            type: 'join_survival_room',
            roomCode: code.toUpperCase()
        }));
    }
    window.joinSurvivalRoom = joinRoomByCode;

    const btnJoinCode = document.getElementById('btn-join-code');
    const inputJoinCode = document.getElementById('input-join-code');
    if (btnJoinCode && inputJoinCode) {
        btnJoinCode.addEventListener('click', () => {
            const code = inputJoinCode.value.trim();
            joinRoomByCode(code);
        });
        inputJoinCode.addEventListener('keypress', (e) => {
            if (e.key === 'Enter') {
                const code = inputJoinCode.value.trim();
                joinRoomByCode(code);
            }
        });
    }

    // Làm mới danh sách phòng
    const btnRefreshRooms = document.getElementById('btn-refresh-rooms');
    if (btnRefreshRooms) {
        btnRefreshRooms.addEventListener('click', () => {
            if (socket && socket.readyState === WebSocket.OPEN) {
                socket.send(JSON.stringify({ type: 'get_survival_rooms' }));
            }
        });
    }

    // Admin đóng phòng
    window.survivalAdminCloseRoom = function (code) {
        if (!isCurrentUserAdmin) return;
        if (confirm(`Bạn có chắc chắn muốn đóng phòng [${code}]?`)) {
            socket.send(JSON.stringify({
                type: 'admin_close_survival_room',
                roomCode: code
            }));
        }
    };

    // Modal tạo phòng
    const modalCreateRoom   = document.getElementById('modal-create-room');
    const btnOpenCreateModal = document.getElementById('btn-open-create-modal');
    const btnCloseModal     = document.getElementById('btn-close-modal');
    const btnCancelModal    = document.getElementById('btn-cancel-modal');
    const formCreateRoom    = document.getElementById('form-create-room');

    if (btnOpenCreateModal && modalCreateRoom) {
        btnOpenCreateModal.addEventListener('click', () => {
            modalCreateRoom.classList.add('active');
            const nameInput = document.getElementById('create-room-name');
            if (nameInput) {
                nameInput.value = `Phòng Sinh Tử của ${currentUsername}`;
                nameInput.focus();
            }
        });
    }

    function closeModal() {
        if (modalCreateRoom) modalCreateRoom.classList.remove('active');
    }
    if (btnCloseModal)  btnCloseModal.addEventListener('click', closeModal);
    if (btnCancelModal) btnCancelModal.addEventListener('click', closeModal);

    // Xử lý gửi form tạo phòng
    const btnSubmitCreate = document.getElementById('btn-submit-create-room');
    if (btnSubmitCreate) {
        btnSubmitCreate.addEventListener('click', () => {
            const roomNameEl = document.getElementById('create-room-name');
            const roomCodeEl = document.getElementById('create-room-code');
            const topicEl    = document.getElementById('create-topic');
            const hpEl       = document.getElementById('create-starting-hp');
            const roundsEl   = document.getElementById('create-total-rounds');

            const roomName = roomNameEl ? roomNameEl.value.trim() : '';
            if (!roomName) {
                alert('Vui lòng nhập tên phòng!');
                return;
            }

            const roomCode    = roomCodeEl ? roomCodeEl.value.trim().toUpperCase() : '';
            const topic       = topicEl ? topicEl.value : 'ALL';
            const startingHp  = hpEl ? parseInt(hpEl.value, 10) : 100;
            const totalRounds = roundsEl ? parseInt(roundsEl.value, 10) : 15;

            socket.send(JSON.stringify({
                type: 'create_survival_room',
                roomName: roomName,
                roomCode: roomCode,
                topic: topic,
                startingHp: startingHp,
                totalRounds: totalRounds
            }));

            closeModal();
        });
    }

    // Sao chép mã phòng
    const btnCopyCode = document.getElementById('btn-copy-code');
    if (btnCopyCode) {
        btnCopyCode.addEventListener('click', () => {
            if (!currentRoomCode) return;
            navigator.clipboard.writeText(currentRoomCode).then(() => {
                const oldText = btnCopyCode.innerText;
                btnCopyCode.innerText = '✓ Đã sao chép!';
                setTimeout(() => { btnCopyCode.innerText = oldText; }, 2000);
            }).catch(() => {
                alert(`Mã phòng của bạn: ${currentRoomCode}`);
            });
        });
    }

    // Nút Sẵn sàng
    const btnToggleReady = document.getElementById('btn-toggle-ready');
    if (btnToggleReady) {
        btnToggleReady.addEventListener('click', () => {
            socket.send(JSON.stringify({ type: 'toggle_survival_ready' }));
        });
    }

    // Nút Bắt đầu trận đấu (Chủ phòng)
    const btnStart = document.getElementById('btn-start');
    if (btnStart) {
        btnStart.addEventListener('click', () => {
            if (confirm('Bắt đầu cuộc chiến sinh tử ngay bây giờ?')) {
                socket.send(JSON.stringify({ type: 'start_survival_game' }));
            }
        });
    }

    // ── HÀM RỜI PHÒNG DÙNG CHUNG ─────────────────────────────────────
    function leaveCurrentRoom() {
        if (socket && socket.readyState === WebSocket.OPEN) {
            socket.send(JSON.stringify({
                type: 'leave_survival_room',
                roomCode: currentRoomCode
            }));
        }
        currentRoomCode = '';
        currentRoomInfo = null;
        clearInterval(countdownInterval);
        resetSurvivalState();
        showScreen('lobby');
        if (socket && socket.readyState === WebSocket.OPEN) {
            socket.send(JSON.stringify({ type: 'get_survival_rooms' }));
        }
    }
    window.leaveSurvivalRoom = leaveCurrentRoom;

    // Rời phòng từ phòng chờ
    const btnLeaveRoom = document.getElementById('btn-leave-room');
    if (btnLeaveRoom) {
        btnLeaveRoom.addEventListener('click', () => {
            leaveCurrentRoom();
        });
    }

    // Rời phòng trong trận (game-screen)
    const btnGameLeave = document.getElementById('btn-game-leave');
    if (btnGameLeave) {
        btnGameLeave.addEventListener('click', () => {
            if (confirm('Bạn có chắc chắn muốn bỏ cuộc và rời trận đấu về sảnh?')) {
                leaveCurrentRoom();
            }
        });
    }

    // Rời phòng từ màn hình kết quả vòng (result-screen)
    const btnResultLeave = document.getElementById('btn-result-leave');
    if (btnResultLeave) {
        btnResultLeave.addEventListener('click', () => {
            leaveCurrentRoom();
        });
    }

    // Rời phòng từ màn hình kết thúc (end-screen)
    const btnEndLeave = document.getElementById('btn-end-leave');
    if (btnEndLeave) {
        btnEndLeave.addEventListener('click', () => {
            leaveCurrentRoom();
        });
    }

    // Về phòng chờ từ màn hình kết thúc
    const btnBackToRoom = document.getElementById('btn-back-to-room');
    if (btnBackToRoom) {
        btnBackToRoom.addEventListener('click', () => {
            showScreen('waiting');
        });
    }

    // Bật/Tắt âm thanh
    const btnSound = document.getElementById('btn-sound');
    if (btnSound) {
        btnSound.addEventListener('click', () => {
            soundEnabled = !soundEnabled;
            if (window.arenaSound && typeof window.arenaSound.toggle === 'function') {
                window.arenaSound.enabled = soundEnabled;
            }
            btnSound.innerText = soundEnabled ? '🔊' : '🔇';
        });
    }

    // Đóng kết nối khi rời trang
    window.addEventListener('beforeunload', () => {
        if (socket && socket.readyState === WebSocket.OPEN) {
            if (currentRoomCode) {
                socket.send(JSON.stringify({
                    type: 'leave_survival_room',
                    roomCode: currentRoomCode
                }));
            }
            socket.close();
        }
    });

    // ── Tiện ích hỗ trợ ──────────────────────────────────────────────
    function escapeHtml(str) {
        if (!str) return '';
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

})();
