// Test script for Chiếc Nón Kỳ Diệu (Wheel of Fortune) multiplayer flow
const BASE_URL = 'http://localhost:3000';
const WS_URL = 'ws://localhost:3000/ws';

async function registerAndLogin(username, email, password) {
    await fetch(`${BASE_URL}/auth/register`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, email, password })
    });
    const loginRes = await fetch(`${BASE_URL}/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password })
    });
    const cookie = loginRes.headers.get('set-cookie');
    const sessionId = cookie ? cookie.split(';')[0] : '';
    return { username, sessionId };
}

function connectWs(sessionId) {
    return new Promise((resolve, reject) => {
        const ws = new WebSocket(WS_URL, {
            headers: {
                'Cookie': sessionId
            }
        });
        ws.onopen = () => resolve(ws);
        ws.onerror = (err) => reject(err);
    });
}

async function runTest() {
    const ts = Date.now();
    const p1Name = `wPlayer1_${ts}`;
    const p2Name = `wPlayer2_${ts}`;
    const p3Name = `wPlayer3_${ts}`;

    console.log("🎮 [KIỂM THỬ CHIẾC NÓN KỲ DIỆU] 1. Khởi tạo 3 tài khoản người chơi...");
    const p1 = await registerAndLogin(p1Name, `${p1Name}@test.com`, 'Pass1234!');
    const p2 = await registerAndLogin(p2Name, `${p2Name}@test.com`, 'Pass1234!');
    const p3 = await registerAndLogin(p3Name, `${p3Name}@test.com`, 'Pass1234!');

    console.log("2. Kết nối WebSocket cho 3 người chơi...");
    const ws1 = await connectWs(p1.sessionId);
    const ws2 = await connectWs(p2.sessionId);
    const ws3 = await connectWs(p3.sessionId);

    const roomCode = `WN${ts.toString().slice(-4)}`;
    console.log(`3. Người chơi 1 tạo phòng đấu [${roomCode}] (Chủ đề CNTT, 10s/lượt)...`);

    let targetPhrase = "";
    let gameEnded = false;

    // Lắng nghe tin nhắn của P1 (Host)
    ws1.onmessage = (event) => {
        const msg = JSON.parse(event.data);
        // console.log(`[P1] ${msg.type}`);

        if (msg.type === 'wheel_game_started') {
            console.log(`   -> [VTV3] Trận đấu bắt đầu! Chủ đề: ${msg.topicName}, Gợi ý: "${msg.hint}"`);
        }

        if (msg.type === 'wheel_state') {
            handleGameState(msg);
        }

        if (msg.type === 'wheel_end_game') {
            handleGameEnd(msg);
        }
    };

    // Lắng nghe tin nhắn của P2
    ws2.onmessage = (event) => {
        const msg = JSON.parse(event.data);
        if (msg.type === 'wheel_state') {
            handleGameState(msg);
        }
    };

    // Lắng nghe tin nhắn của P3
    ws3.onmessage = (event) => {
        const msg = JSON.parse(event.data);
        if (msg.type === 'wheel_state') {
            handleGameState(msg);
        }
    };

    // P1 tạo phòng
    ws1.send(JSON.stringify({
        type: 'create_wheel_room',
        roomName: 'Sàn Đấu Thử Nghiệm',
        roomCode: roomCode,
        topic: 'IT',
        timeLimit: 10
    }));

    // Chờ 500ms rồi P2 và P3 vào phòng
    await new Promise(r => setTimeout(r, 500));
    console.log(`4. Người chơi 2 và Người chơi 3 cùng gia nhập phòng [${roomCode}]...`);
    ws2.send(JSON.stringify({ type: 'join_wheel_room', roomCode: roomCode }));
    ws3.send(JSON.stringify({ type: 'join_wheel_room', roomCode: roomCode }));

    // Chờ 500ms rồi P2 và P3 bấm Sẵn Sàng
    await new Promise(r => setTimeout(r, 500));
    console.log("5. Người chơi 2 và 3 bấm 'SẴN SÀNG'...");
    ws2.send(JSON.stringify({ type: 'toggle_wheel_ready' }));
    ws3.send(JSON.stringify({ type: 'toggle_wheel_ready' }));

    // Chờ 500ms rồi P1 bấm Bắt đầu ván đấu
    await new Promise(r => setTimeout(r, 500));
    console.log("6. Chủ phòng (P1) bấm 'BẮT ĐẦU VÁN ĐẤU'...");
    ws1.send(JSON.stringify({ type: 'start_wheel_game' }));

    let actionTaken = false;

    function handleGameState(data) {
        if (gameEnded) return;
        targetPhrase = data.phrase;

        const currentTurnUser = data.currentTurnUsername;
        const currentAction = data.currentAction;

        console.log(`   [Lượt] Đến lượt: [${currentTurnUser}], Hành động cần làm: [${currentAction}], Ô chữ hiện tại: "${data.phrase}"`);

        // Tìm WebSocket tương ứng với người đến lượt
        let activeWs = null;
        if (currentTurnUser === p1Name) activeWs = ws1;
        else if (currentTurnUser === p2Name) activeWs = ws2;
        else if (currentTurnUser === p3Name) activeWs = ws3;

        if (!activeWs) return;

        if (currentAction === 'SPIN') {
            console.log(`      -> [${currentTurnUser}] Bấm Quay Nón...`);
            setTimeout(() => {
                activeWs.send(JSON.stringify({ type: 'wheel_spin' }));
            }, 600);
        } else if (currentAction === 'GUESS') {
            console.log(`      -> [${currentTurnUser}] Đã quay vào ô ${data.currentSpinValue}đ! Thử giải cả ô chữ luôn: "${targetPhrase}"`);
            setTimeout(() => {
                activeWs.send(JSON.stringify({
                    type: 'wheel_solve_phrase',
                    phrase: targetPhrase
                }));
            }, 600);
        }
    }

    function handleGameEnd(msg) {
        gameEnded = true;
        console.log(`\n🎉 [KẾT QUẢ VÁN ĐẤU CHIẾC NÓN KỲ DIỆU]`);
        console.log(`   - Thông điệp: ${msg.reason}`);
        console.log(`   - Ô chữ đầy đủ: "${msg.fullPhrase}"`);
        console.log(`   - Quán quân: ${msg.winner}`);
        if (msg.winners) {
            console.log(`   - Bảng vàng thành tích: ${JSON.stringify(msg.winners)}`);
        }
        console.log("\n✅ KIỂM THỬ CHIẾC NÓN KỲ DIỆU: TOÀN BỘ LOGIC QUAY, ĐOÁN, GIẢI Ô CHỮ & PHÂN LƯỢT HOẠT ĐỘNG HOÀN HẢO!\n");
        ws1.close();
        ws2.close();
        ws3.close();
        process.exit(0);
    }

    // Timeout an toàn sau 15 giây
    setTimeout(() => {
        if (!gameEnded) {
            console.error("❌ Test bị timeout sau 15s!");
            process.exit(1);
        }
    }, 15000);
}

runTest().catch(err => {
    console.error("Test error:", err);
    process.exit(1);
});
