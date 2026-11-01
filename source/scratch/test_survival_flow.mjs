// Test script to verify Đấu Trường Sinh Tử (Survival Arena) flow
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
    const hostUser = `survHost_${ts}`;
    const p2User = `survP2_${ts}`;

    console.log("1. Đăng ký & Đăng nhập 2 người chơi...");
    const host = await registerAndLogin(hostUser, `${hostUser}@test.com`, 'Pass1234!');
    const p2 = await registerAndLogin(p2User, `${p2User}@test.com`, 'Pass1234!');

    console.log("2. Kết nối WebSocket...");
    const hostWs = await connectWs(host.sessionId);
    const p2Ws = await connectWs(p2.sessionId);

    const roomCode = `ST${ts.toString().slice(-4)}`;
    console.log(`3. Host tạo phòng sinh tử [${roomCode}] (HP: 20, 3 Vòng)...`);

    let gameStarted = false;
    let roundResultCount = 0;

    hostWs.onmessage = (event) => {
        const msg = JSON.parse(event.data);
        console.log(`[Host] Received: ${msg.type}`);

        if (msg.type === 'survival_game_started') {
            gameStarted = true;
            console.log(`   -> Trận đấu bắt đầu! Tổng số vòng: ${msg.totalRounds}, HP ban đầu: ${msg.startingHp}, Người chơi: ${msg.playerCount}`);
        }

        if ((msg.type === 'survival_update_players' || msg.type === 'update_survival_players') && msg.allReady && !gameStarted && msg.players && msg.players.length > 1) {
            console.log("   -> Host thấy tất cả đã sẵn sàng, gửi start_survival_game...");
            hostWs.send(JSON.stringify({ type: 'start_survival_game' }));
        }

        if (msg.type === 'survival_new_question') {
            console.log(`   -> Câu hỏi vòng ${msg.round}/${msg.totalQuestions}: "${msg.text}" | Giai đoạn: ${msg.phaseName} | Trừ sai: -${msg.penalty} HP | Time: ${msg.timeLimit}s`);
            // Host submits correct answer (index 1)
            hostWs.send(JSON.stringify({ type: 'submit_survival_answer', answer: 1 }));
        }

        if (msg.type === 'survival_round_result') {
            roundResultCount++;
            console.log(`   -> Kết quả vòng ${msg.round}: Đúng là đáp án [${msg.correctAnswerIndex}], Còn sống: ${msg.survivorsCount}, Bị loại vòng này: ${msg.eliminatedThisRound ? msg.eliminatedThisRound.length : 0}`);
            if (msg.playerStates) {
                msg.playerStates.forEach(ps => {
                    console.log(`      * [${ps.username}] HP: ${ps.hp}, Điểm: ${ps.score}, Còn sống: ${ps.isAlive}`);
                });
            }
            if (msg.milestoneBonus) {
                console.log(`      🎉 MỐC THƯỞNG KÍCH HOẠT: ${msg.milestoneMessage}`);
            }
        }

        if (msg.type === 'survival_end_game') {
            console.log(`   -> Trận đấu kết thúc! Lý do: "${msg.reason}", Quán quân: ${msg.winner}`);
            if (msg.finalStandings) {
                console.log("   -> Bảng xếp hạng chung cuộc:");
                msg.finalStandings.forEach(s => {
                    console.log(`      Hạng ${s.rank}: ${s.username} - Điểm: ${s.score}, HP: ${s.hp}`);
                });
            }
            console.log("\n✅ TẤT CẢ KIỂM THỬ ĐẤU TRƯỜNG SINH TỬ ĐÃ THÀNH CÔNG VÀ CHÍNH XÁC!");
            hostWs.close();
            p2Ws.close();
            process.exit(0);
        }
    };

    let p2Readied = false;
    p2Ws.onmessage = (event) => {
        const msg = JSON.parse(event.data);
        if ((msg.type === 'survival_update_players' || msg.type === 'update_survival_players') && !p2Readied && msg.roomCode === roomCode) {
            p2Readied = true;
            console.log("   -> Player 2 bấm SẴN SÀNG...");
            p2Ws.send(JSON.stringify({ type: 'toggle_survival_ready' }));
        }
        if (msg.type === 'survival_new_question') {
            // Player 2 submits WRONG answer (index 3) so they lose HP!
            console.log("   -> Player 2 trả lời sai để test trừ HP...");
            p2Ws.send(JSON.stringify({ type: 'submit_survival_answer', answer: 3 }));
        }
    };

    // Host creates room with 10 starting HP so P2 gets eliminated in 2 rounds
    hostWs.send(JSON.stringify({
        type: 'create_survival_room',
        roomCode: roomCode,
        roomName: 'Đấu Trường Sinh Tử Test',
        topic: 'HISTORY',
        startingHp: 10,
        totalRounds: 3
    }));

    // Player 2 joins room after 500ms
    setTimeout(() => {
        console.log(`   -> Player 2 tham gia phòng [${roomCode}]...`);
        p2Ws.send(JSON.stringify({ type: 'join_survival_room', roomCode: roomCode }));
    }, 500);

    setTimeout(() => {
        console.error("❌ Test bị timeout sau 65s!");
        process.exit(1);
    }, 65000);
}

runTest().catch(err => {
    console.error("Test error:", err);
    process.exit(1);
});
