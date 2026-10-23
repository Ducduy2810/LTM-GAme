// ===============================================================
// CHIẾC NÓN KỲ DIỆU - CLIENT CONTROLLER & MULTI-ROOM ENGINE
// ===============================================================

const wsUrl = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws';
const socket = new WebSocket(wsUrl);

let currentUsername = null;
let isCurrentUserAdmin = false;
let currentRoomCode = null;
let isMyHost = false;

// ── VẼ VÒNG QUAY THEO TỈ LỆ ĐÚNG ─────────────────────────────
// Định nghĩa các ô: màu, điểm, tỉ lệ %
const WHEEL_SEGMENTS = [
    { value: 100,  label: '100',  color: '#2563eb', darkColor: '#1d4ed8', weight: 50 }, // Xanh dương
    { value: 200,  label: '200',  color: '#f59e0b', darkColor: '#d97706', weight: 25 }, // Vàng
    { value: 500,  label: '500',  color: '#ef4444', darkColor: '#dc2626', weight: 15 }, // Đỏ
    { value: 1000, label: '1000', color: '#374151', darkColor: '#1f2937', weight: 7  }, // Đen
    { value: 2000, label: '2000', color: '#7c3aed', darkColor: '#6d28d9', weight: 3  }, // Tím
];

// Màu tương ứng để highlight khi quay trúng
const SPIN_COLOR_MAP = {
    100:  '#2563eb',
    200:  '#f59e0b',
    500:  '#ef4444',
    1000: '#374151',
    2000: '#7c3aed',
};

function buildWheelSVG() {
    const svg = document.getElementById('wheel-svg');
    const slices = document.getElementById('wheel-slices');
    if (!slices) return;
    slices.innerHTML = '';

    const cx = 100, cy = 100, r = 94;
    const total = WHEEL_SEGMENTS.reduce((s, seg) => s + seg.weight, 0); // = 100
    let startAngleDeg = -90; // Bắt đầu từ 12 giờ

    // Tách mỗi segment thành nhiều ô nhỏ để vòng quay trông đẹp hơn
    // Số ô tối thiểu mỗi màu = 1, tối đa phụ thuộc tỉ lệ
    const sliceCountPerSeg = [5, 3, 2, 1, 1]; // Xanh 5 ô, Vàng 3, Đỏ 2, Đen 1, Tím 1

    WHEEL_SEGMENTS.forEach((seg, segIdx) => {
        const totalAngle = (seg.weight / total) * 360;
        const numSlices  = sliceCountPerSeg[segIdx];
        const sliceAngle = totalAngle / numSlices;

        for (let i = 0; i < numSlices; i++) {
            const a1 = startAngleDeg + i * sliceAngle;
            const a2 = a1 + sliceAngle;

            const x1 = cx + r * Math.cos(a1 * Math.PI / 180);
            const y1 = cy + r * Math.sin(a1 * Math.PI / 180);
            const x2 = cx + r * Math.cos(a2 * Math.PI / 180);
            const y2 = cy + r * Math.sin(a2 * Math.PI / 180);

            const largeArc = sliceAngle > 180 ? 1 : 0;
            const fill = (i % 2 === 0) ? seg.color : seg.darkColor;

            const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
            path.setAttribute('d', `M${cx},${cy} L${x1.toFixed(2)},${y1.toFixed(2)} A${r},${r} 0 ${largeArc},1 ${x2.toFixed(2)},${y2.toFixed(2)} Z`);
            path.setAttribute('fill', fill);
            path.setAttribute('stroke', '#0f172a');
            path.setAttribute('stroke-width', '1');
            slices.appendChild(path);

            // Thêm nhãn điểm ở giữa slice
            const midAngle = (a1 + a2) / 2;
            const labelR = r * 0.65; // 65% bán kính
            const lx = cx + labelR * Math.cos(midAngle * Math.PI / 180);
            const ly = cy + labelR * Math.sin(midAngle * Math.PI / 180);

            const text = document.createElementNS('http://www.w3.org/2000/svg', 'text');
            text.setAttribute('x', lx.toFixed(2));
            text.setAttribute('y', ly.toFixed(2));
            text.setAttribute('text-anchor', 'middle');
            text.setAttribute('dominant-baseline', 'middle');
            text.setAttribute('fill', seg.value === 1000 ? '#facc15' : '#ffffff');
            text.setAttribute('font-size', sliceAngle >= 30 ? '9' : (sliceAngle >= 15 ? '7' : '6'));
            text.setAttribute('font-weight', '900');
            text.setAttribute('font-family', 'Arial, sans-serif');
            text.setAttribute('transform', `rotate(${midAngle + 90}, ${lx.toFixed(2)}, ${ly.toFixed(2)})`);
            text.textContent = seg.label;
            slices.appendChild(text);
        }

        startAngleDeg += totalAngle;
    });

    // Vẽ lại trục tâm lên trên cùng (sau khi các slice đã vẽ xong)
    const hub1 = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
    hub1.setAttribute('cx', '100'); hub1.setAttribute('cy', '100'); hub1.setAttribute('r', '26');
    hub1.setAttribute('fill', '#1e293b'); hub1.setAttribute('stroke', '#facc15'); hub1.setAttribute('stroke-width', '3');
    svg.appendChild(hub1);
    const hub2 = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
    hub2.setAttribute('cx', '100'); hub2.setAttribute('cy', '100'); hub2.setAttribute('r', '9');
    hub2.setAttribute('fill', '#facc15');
    svg.appendChild(hub2);
}

// Vẽ vòng quay khi trang load xong
document.addEventListener('DOMContentLoaded', buildWheelSVG);
let currentTurnUsername = null;
let countdownInterval = null;
let currentWheelRotation = 0;

// 1. KIỂM TRA ĐĂNG NHẬP & PHÂN QUYỀN
fetch('/auth/me')
    .then(r => r.json())
    .then(data => {
        if (data.loggedIn && data.user) {
            currentUsername = data.user.username;
            isCurrentUserAdmin = !!data.user.isAdmin || 
                                (data.user.email === 'mainung28@gmail.com') || 
                                (currentUsername === 'admin') || 
                                (currentUsername === 'DuyNguyen');

            const badge = document.getElementById('current-username');
            if (badge) {
                badge.innerText = currentUsername + (isCurrentUserAdmin ? ' (Admin)' : '');
            }
        } else {
            alert('Bạn chưa đăng nhập! Vui lòng đăng nhập trước khi vào game.');
            window.location.href = 'index.html';
        }
    })
    .catch(() => {
        window.location.href = 'index.html';
    });

// 2. CHUYỂN ĐỔI MÀN HÌNH (SCREEN NAVIGATION)
function showScreen(screenName) {
    document.querySelectorAll('.screen').forEach(s => s.classList.remove('active'));
    const target = document.getElementById(`${screenName}-screen`);
    if (target) {
        target.classList.add('active');
    }
}

// 3. KẾT NỐI VÀ LẮNG NGHE WEBSOCKET
socket.onopen = () => {
    // Khi vừa vào trang, đăng ký lắng nghe danh sách phòng ở Sảnh
    socket.send(JSON.stringify({ type: 'get_wheel_rooms' }));

    // Tự động vào phòng nếu có tham số ?room=CODE trên URL
    const urlParams = new URLSearchParams(window.location.search);
    const targetRoom = urlParams.get('room');
    if (targetRoom) {
        socket.send(JSON.stringify({ type: 'join_wheel_room', roomCode: targetRoom }));
    }
};

socket.onmessage = (event) => {
    const data = JSON.parse(event.data);

    if (data.type === 'auth_error') {
        alert(data.message);
        window.location.href = 'index.html';
        return;
    }

    if (data.type === 'wheel_error') {
        alert(data.message);
        return;
    }

    // DANH SÁCH PHÒNG Ở SẢNH
    if (data.type === 'wheel_room_list') {
        renderRoomList(data.rooms || []);
    }

    // THÔNG TIN PHÒNG HIỆN TẠI
    if (data.type === 'wheel_room_info') {
        currentRoomCode = data.roomCode;
        document.getElementById('wait-room-code').innerText = data.roomCode;
        document.getElementById('wait-room-name').innerText = data.roomName;
        document.getElementById('wait-host-name').innerText = data.hostUsername;
        document.getElementById('wait-topic-name').innerText = data.topicName || data.topic;
        document.getElementById('wait-time-limit').innerText = `${data.timeLimit}s`;
        showScreen('waiting');
    }

    // RỜI PHÒNG THÀNH CÔNG VỀ SẢNH
    if (data.type === 'left_wheel_room') {
        currentRoomCode = null;
        clearInterval(countdownInterval);
        showScreen('lobby');
        socket.send(JSON.stringify({ type: 'get_wheel_rooms' }));
    }

    // CẬP NHẬT DANH SÁCH 4 NGƯỜI CHƠI TRONG PHÒNG CHỜ
    if (data.type === 'update_wheel_players') {
        renderWaitingPodiums(data.players || [], data.hostUsername, data.allReady);
    }

    // BẮT ĐẦU VÁN ĐẤU
    if (data.type === 'wheel_game_started') {
        showScreen('game');
        document.getElementById('hint-banner').innerText = `💡 Chủ đề: [${data.topicName || data.topic}] — Gợi ý: ${data.hint}`;
    }

    // HIỆU ỨNG QUAY NÓN
    if (data.type === 'wheel_spin_result') {
        animateWheelSpin(data.result);
    }

    // CẬP NHẬT TRẠNG THÁI VÁN ĐẤU LIVE
    if (data.type === 'wheel_state') {
        handleWheelState(data);
    }

    // NHẬT KÝ CHAT / THAO TÁC
    if (data.type === 'wheel_chat') {
        const box = document.getElementById('wheel-chat-box');
        if (box) {
            box.innerHTML += `<div>> ${data.message}</div>`;
            box.scrollTop = box.scrollHeight;
        }
    }

    // KẾT THÚC VÁN ĐẤU
    if (data.type === 'wheel_end_game') {
        clearInterval(countdownInterval);
        showScreen('end');
        document.getElementById('end-reason').innerText = data.reason || 'Ván đấu đã kết thúc!';
        document.getElementById('end-full-phrase').innerText = data.fullPhrase || '';

        const listContainer = document.getElementById('end-winners-list');
        listContainer.innerHTML = '';
        if (data.winners && data.winners.length > 0) {
            data.winners.forEach(w => {
                const item = document.createElement('div');
                item.style.padding = '8px 14px';
                item.style.background = 'rgba(250, 204, 21, 0.15)';
                item.style.border = '1px solid #facc15';
                item.style.borderRadius = '8px';
                item.style.fontWeight = '800';
                item.style.color = '#fde047';
                item.innerHTML = `🎖️ Quán Quân: <strong>${w}</strong>`;
                listContainer.appendChild(item);
            });
        }
    }
};

// 4. VẼ DANH SÁCH PHÒNG Ở SẢNH (RENDER ROOM LIST)
function renderRoomList(rooms) {
    const container = document.getElementById('wheel-rooms-container');
    if (!container) return;

    if (!rooms || rooms.length === 0) {
        container.innerHTML = '<p style="color:#94a3b8; grid-column:1/-1; text-align:center; padding:30px;">Chưa có phòng nào. Hãy là người đầu tiên tạo phòng!</p>';
        return;
    }

    container.innerHTML = '';
    rooms.forEach(r => {
        const card = document.createElement('div');
        card.className = 'room-card';

        const isPlaying = (r.gameState === 'PLAYING');
        const statusClass = isPlaying ? 'playing' : 'waiting';
        const statusText = isPlaying ? 'Đang Đấu' : 'Đang Chờ';

        let adminDeleteBtn = '';
        if (isCurrentUserAdmin && r.roomCode !== 'CNKD') {
            adminDeleteBtn = `<button class="btn-join-card" style="background:#ef4444; color:#fff; margin-left:6px;" onclick="closeRoomByAdmin('${r.roomCode}')">Đóng</button>`;
        }

        card.innerHTML = `
            <div>
                <div class="room-card-header">
                    <h4 class="room-card-title">${r.roomName}</h4>
                    <span class="room-status-badge ${statusClass}">${statusText}</span>
                </div>
                <div class="room-card-details">
                    <div>🔑 Mã phòng: <strong style="color:#facc15;">${r.roomCode}</strong></div>
                    <div>👑 Chủ phòng: <strong>${r.hostUsername}</strong></div>
                    <div>💡 Chủ đề: <strong>${r.topicName || r.topic}</strong></div>
                    <div>⏱️ Lượt đoán: <strong>${r.timeLimit}s</strong></div>
                    <div>👥 Người chơi: <strong style="color:#38bdf8;">${r.playerCount} / ${r.maxPlayers || 4}</strong></div>
                </div>
            </div>
            <div style="display:flex;">
                <button class="btn-join-card" onclick="joinRoom('${r.roomCode}')" ${r.playerCount >= 4 ? 'disabled style="opacity:0.5;"' : ''}>
                    ${r.playerCount >= 4 ? 'Phòng Đã Đầy' : 'Vào Sàn Đấu ➡'}
                </button>
                ${adminDeleteBtn}
            </div>
        `;
        container.appendChild(card);
    });
}

// 5. VẼ 4 BỤC NGỒI TRONG PHÒNG CHỜ (WAITING PODIUMS)
function renderWaitingPodiums(players, hostUsername, allReady) {
    const container = document.getElementById('waiting-podiums');
    if (!container) return;
    container.innerHTML = '';

    let meInRoom = null;

    // Luôn hiển thị đủ 4 bục ngồi
    for (let i = 0; i < 4; i++) {
        const slot = document.createElement('div');
        const p = players[i];

        if (p) {
            if (p.username === currentUsername) meInRoom = p;
            slot.className = 'podium-slot occupied';

            let roleTag = '';
            if (p.isSuperAdmin) {
                roleTag = '<span style="color:#fde047; font-weight:800; font-size:12px;">🛡️ Admin</span>';
            } else if (p.isHost) {
                roleTag = '<span style="color:#fde047; font-weight:800; font-size:12px;">👑 Chủ phòng</span>';
            }

            let readyTag = '';
            if (p.isHost || p.isSuperAdmin) {
                readyTag = '<span class="ready-badge is-ready">Chủ Trì</span>';
            } else if (p.isReady) {
                readyTag = '<span class="ready-badge is-ready">✅ Sẵn sàng</span>';
            } else {
                readyTag = '<span class="ready-badge not-ready">⏳ Chưa sẵn sàng</span>';
            }

            slot.innerHTML = `
                <div class="podium-avatar">👤</div>
                <div class="podium-username">${p.username}</div>
                <div>${roleTag}</div>
                <div>${readyTag}</div>
            `;
        } else {
            slot.className = 'podium-slot';
            slot.innerHTML = `
                <div style="font-size:28px; opacity:0.4;">🪑</div>
                <div style="color:#64748b; font-size:13px; margin-top:6px;">Ghế ${i + 1}</div>
                <div style="color:#475569; font-size:11px;">Chờ người chơi...</div>
            `;
        }
        container.appendChild(slot);
    }

    // Phân quyền Chủ phòng / Người chơi
    isMyHost = (hostUsername && hostUsername === currentUsername);
    const canIControl = isMyHost || isCurrentUserAdmin;

    const btnReady = document.getElementById('btn-toggle-ready');
    const btnStart = document.getElementById('btn-start-game');
    const waitingMsg = document.getElementById('waiting-status-msg');

    if (canIControl) {
        btnReady.style.display = 'none';
        btnStart.style.display = 'inline-block';

        if (isCurrentUserAdmin) {
            btnStart.disabled = false;
            btnStart.style.opacity = '1';
            btnStart.innerText = '👑 BẮT ĐẦU VÁN ĐẤU (Super Admin)';
            waitingMsg.innerText = '🛡️ Bạn là Super Admin. Có toàn quyền bắt đầu bất kỳ lúc nào!';
            waitingMsg.style.color = '#10b981';
        } else if (allReady || players.length === 1) {
            btnStart.disabled = false;
            btnStart.style.opacity = '1';
            btnStart.innerText = '👑 BẮT ĐẦU VÁN ĐẤU';
            waitingMsg.innerText = '👑 Mọi người đã sẵn sàng! Bạn có thể bấm bắt đầu ván đấu.';
            waitingMsg.style.color = '#10b981';
        } else {
            btnStart.disabled = true;
            btnStart.style.opacity = '0.6';
            btnStart.innerText = '⏳ Đang chờ người chơi Sẵn sàng...';
            waitingMsg.innerText = '⏳ Vẫn còn người chơi chưa bấm Sẵn sàng. Chờ mọi người sẵn sàng để bắt đầu!';
            waitingMsg.style.color = '#facc15';
        }
    } else {
        btnStart.style.display = 'none';
        btnReady.style.display = 'inline-block';

        if (meInRoom && meInRoom.isReady) {
            btnReady.innerText = '❌ HỦY SẴN SÀNG';
            btnReady.className = 'btn-ready-toggle ready-active';
            waitingMsg.innerText = `✅ Bạn đã sẵn sàng! Đang chờ Chủ phòng [${hostUsername}] bấm bắt đầu...`;
            waitingMsg.style.color = '#10b981';
        } else {
            btnReady.innerText = '✅ SẴN SÀNG';
            btnReady.className = 'btn-ready-toggle';
            waitingMsg.innerText = '👉 Hãy bấm nút [SẴN SÀNG] bên dưới để Chủ phòng có thể bắt đầu ván đấu!';
            waitingMsg.style.color = '#facc15';
        }
    }
}

// 6. XỬ LÝ TRẠNG THÁI TRẬN ĐẤU (HANDLE WHEEL STATE)
function handleWheelState(data) {
    currentTurnUsername = data.currentTurnUsername;
    const isPlaying = (data.gameState === 'PLAYING');

    if (!isPlaying) return;

    showScreen('game');

    // 1. Cập nhật gợi ý ô chữ
    if (data.hint) {
        document.getElementById('hint-banner').innerText = `💡 Chủ đề: [${data.topicName || data.topic}] — Gợi ý: ${data.hint}`;
    }

    // 2. Cập nhật 4 bục ngồi trong trận đấu
    const gamePodiums = document.getElementById('game-podiums');
    gamePodiums.innerHTML = '';
    const activePlayer = data.players[data.turnIndex];

    for (let i = 0; i < 4; i++) {
        const slot = document.createElement('div');
        const p = data.players[i];

        if (p) {
            const isTurn = (i === data.turnIndex);
            slot.className = `podium-slot occupied ${isTurn ? 'active-turn' : ''}`;
            slot.innerHTML = `
                <div class="podium-avatar">${isTurn ? '⭐' : '👤'}</div>
                <div class="podium-username">${p.username}</div>
                <div class="podium-score">${p.score || 0} đ</div>
                ${isTurn ? '<span class="ready-badge is-ready" style="margin-top:4px;">LƯỢT QUAY</span>' : ''}
            `;
        } else {
            slot.className = 'podium-slot';
            slot.innerHTML = `
                <div style="font-size:24px; opacity:0.3;">🪑</div>
                <div style="color:#64748b; font-size:12px;">Trống</div>
            `;
        }
        gamePodiums.appendChild(slot);
    }

    // 3. Hiển thị bảng ô chữ
    const phraseContainer = document.getElementById('phrase-board');
    phraseContainer.innerHTML = '';
    for (let char of data.phrase) {
        if (char === ' ') {
            const space = document.createElement('div');
            space.className = 'letter-space';
            phraseContainer.appendChild(space);
        } else {
            const box = document.createElement('div');
            box.className = 'letter-box';
            if (data.revealedLetters && data.revealedLetters.includes(char)) {
                box.classList.add('revealed');
                box.innerText = char;
            } else {
                box.innerText = '';
            }
            phraseContainer.appendChild(box);
        }
    }

    // 4. Cập nhật lượt chơi và bảng điều khiển
    const myTurn = (activePlayer && activePlayer.username === currentUsername);
    const turnIndicator = document.getElementById('turn-indicator');
    const spinBox = document.getElementById('spin-control-box');
    const guessBox = document.getElementById('guess-control-box');
    const waitBox = document.getElementById('wait-control-box');
    const waitText = document.getElementById('wait-action-text');

    if (myTurn) {
        turnIndicator.innerHTML = '🟢 ĐẾN LƯỢT BẠN THAO TÁC!';
        turnIndicator.style.color = '#10b981';

        if (data.currentAction === 'SPIN') {
            spinBox.style.display = 'block';
            guessBox.style.display = 'none';
            waitBox.style.display = 'none';
        } else if (data.currentAction === 'GUESS') {
            spinBox.style.display = 'none';
            guessBox.style.display = 'flex';
            waitBox.style.display = 'none';
            const spinColor = SPIN_COLOR_MAP[data.currentSpinValue] || '#facc15';
            document.getElementById('spin-result-banner').innerHTML =
                `🎉 Bạn vừa quay vào ô: <span style="color:${spinColor}; font-size:1.3em; font-weight:900;">■</span> <strong style="color:${spinColor};">${data.currentSpinValue} ĐIỂM!</strong>`;
            const guessInput = document.getElementById('guess-input');
            guessInput.value = '';
            guessInput.focus();
        } else {
            spinBox.style.display = 'none';
            guessBox.style.display = 'none';
            waitBox.style.display = 'block';
            waitText.innerText = '⏳ Đang chờ hệ thống xử lý...';
        }
    } else {
        turnIndicator.innerHTML = `🔴 ĐANG LÀ LƯỢT CỦA [${activePlayer ? activePlayer.username : '...'}]`;
        turnIndicator.style.color = '#facc15';

        spinBox.style.display = 'none';
        guessBox.style.display = 'none';
        waitBox.style.display = 'block';
        waitText.innerText = `⏳ Đang đợi [${activePlayer ? activePlayer.username : 'người chơi'}] thực hiện lượt...`;
    }

    // 5. Khởi động đồng hồ đếm ngược lượt
    startTurnTimer(data.timeLimit || 10);
}

// 7. ĐỒNG HỒ ĐẾM NGƯỢC LƯỢT
function startTurnTimer(seconds) {
    clearInterval(countdownInterval);
    const timeDisplay = document.getElementById('time-left');
    timeDisplay.innerText = seconds;

    countdownInterval = setInterval(() => {
        seconds--;
        timeDisplay.innerText = Math.max(0, seconds);
        if (seconds <= 0) {
            clearInterval(countdownInterval);
        }
    }, 1000);
}

// 8. HIỆU ỨNG QUAY NÓN (WHEEL ANIMATION)
function animateWheelSpin(spinValue) {
    const wheel = document.getElementById('wheel-svg');
    if (!wheel) return;

    // Quay thêm từ 4 đến 6 vòng ngẫu nhiên
    const extraRot = 1440 + Math.floor(Math.random() * 360);
    currentWheelRotation += extraRot;
    wheel.style.transform = `rotate(${currentWheelRotation}deg)`;

    // Hiển thị màu kết quả ô quay trúng
    if (spinValue && SPIN_COLOR_MAP[spinValue]) {
        const color = SPIN_COLOR_MAP[spinValue];
        const pointer = document.querySelector('.wheel-pointer');
        if (pointer) {
            pointer.style.borderBottomColor = color;
            setTimeout(() => { pointer.style.borderBottomColor = ''; }, 3000);
        }
    }
}

// 9. GẮN CÁC SỰ KIỆN TƯƠNG TÁC (BUTTON EVENTS)
// Vào phòng
window.joinRoom = function(code) {
    socket.send(JSON.stringify({ type: 'join_wheel_room', roomCode: code }));
};

// Super Admin đóng phòng
window.closeRoomByAdmin = function(code) {
    if (confirm(`Bạn có chắc chắn muốn đóng phòng [${code}]?`)) {
        socket.send(JSON.stringify({ type: 'admin_close_wheel_room', roomCode: code }));
    }
};

// Nút Sẵn Sàng
document.getElementById('btn-toggle-ready').addEventListener('click', () => {
    socket.send(JSON.stringify({ type: 'toggle_wheel_ready' }));
});

// Nút Bắt Đầu Ván Đấu
document.getElementById('btn-start-game').addEventListener('click', () => {
    socket.send(JSON.stringify({ type: 'start_wheel_game' }));
});

// Nút Rời Phòng
document.getElementById('btn-leave-room').addEventListener('click', () => {
    socket.send(JSON.stringify({ type: 'leave_wheel_room' }));
});
document.getElementById('btn-game-leave').addEventListener('click', () => {
    socket.send(JSON.stringify({ type: 'leave_wheel_room' }));
});
document.getElementById('btn-end-leave').addEventListener('click', () => {
    socket.send(JSON.stringify({ type: 'leave_wheel_room' }));
});
document.getElementById('btn-play-again').addEventListener('click', () => {
    showScreen('waiting');
});

// Nút Sao Chép Mã Phòng
document.getElementById('btn-copy-code').addEventListener('click', () => {
    if (currentRoomCode) {
        navigator.clipboard.writeText(currentRoomCode).then(() => {
            alert(`Đã sao chép mã phòng: ${currentRoomCode}`);
        }).catch(() => {
            prompt('Sao chép mã phòng:', currentRoomCode);
        });
    }
});

// Vào phòng bằng mã nhập tay
document.getElementById('btn-join-code').addEventListener('click', () => {
    const input = document.getElementById('input-join-code');
    const code = input.value.trim().toUpperCase();
    if (!code) {
        alert('Vui lòng nhập mã phòng!');
        return;
    }
    joinRoom(code);
});
document.getElementById('input-join-code').addEventListener('keypress', (e) => {
    if (e.key === 'Enter') document.getElementById('btn-join-code').click();
});

// Làm mới danh sách phòng
document.getElementById('btn-refresh-rooms').addEventListener('click', () => {
    socket.send(JSON.stringify({ type: 'get_wheel_rooms' }));
});

// Nút Quay Nón
document.getElementById('btn-spin').addEventListener('click', () => {
    socket.send(JSON.stringify({ type: 'wheel_spin' }));
});

// Nút Đoán Chữ Cái
document.getElementById('btn-guess').addEventListener('click', () => {
    const input = document.getElementById('guess-input');
    const letter = input.value.trim().toUpperCase();
    if (letter.length === 1 && letter >= 'A' && letter <= 'Z') {
        socket.send(JSON.stringify({ type: 'wheel_guess', letter: letter }));
        input.value = '';
    } else {
        alert('Vui lòng nhập 1 chữ cái từ A đến Z!');
    }
});
document.getElementById('guess-input').addEventListener('keypress', (e) => {
    if (e.key === 'Enter') document.getElementById('btn-guess').click();
});

// Nút Đoán Cả Ô Chữ
document.getElementById('btn-solve').addEventListener('click', () => {
    const input = document.getElementById('solve-input');
    const phrase = input.value.trim().toUpperCase();
    if (phrase.length > 0) {
        socket.send(JSON.stringify({ type: 'wheel_solve_phrase', phrase: phrase }));
        input.value = '';
    }
});
document.getElementById('solve-input').addEventListener('keypress', (e) => {
    if (e.key === 'Enter') document.getElementById('btn-solve').click();
});

// MODAL TẠO PHÒNG MỚI
const modalCreate = document.getElementById('modal-create-wheel-room');
document.getElementById('btn-open-create-modal').addEventListener('click', () => {
    modalCreate.classList.add('active');
    document.getElementById('create-room-name').value = `Sàn Đấu của ${currentUsername || 'Tôi'}`;
    document.getElementById('create-room-code').value = '';
});

document.getElementById('btn-close-modal').addEventListener('click', () => {
    modalCreate.classList.remove('active');
});
document.getElementById('btn-cancel-modal').addEventListener('click', () => {
    modalCreate.classList.remove('active');
});

document.getElementById('btn-submit-create-room').addEventListener('click', () => {
    const roomName = document.getElementById('create-room-name').value.trim();
    if (!roomName) {
        alert('Vui lòng nhập tên phòng!');
        return;
    }

    const roomCode = document.getElementById('create-room-code').value.trim();
    const topic = document.getElementById('create-topic').value;
    const timeLimit = parseInt(document.getElementById('create-time-limit').value) || 10;

    socket.send(JSON.stringify({
        type: 'create_wheel_room',
        roomName: roomName,
        roomCode: roomCode,
        topic: topic,
        timeLimit: timeLimit
    }));

    modalCreate.classList.remove('active');
});

// ĐÓNG WEBSOCKET KHI RỜI TRANG
window.addEventListener('beforeunload', () => {
    socket.close();
});
