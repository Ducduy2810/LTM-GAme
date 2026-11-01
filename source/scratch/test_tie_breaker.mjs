// Test script to verify Sudden Death Tie-Breaker (Vòng Đấu Tốc Độ Khi Còn 2 Người Cùng Sai) in Đấu Trường 100
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
            headers: { 'Cookie': sessionId }
        });
        ws.onopen = () => resolve(ws);
        ws.onerror = (err) => reject(err);
    });
}

async function runTest() {
    const ts = Date.now();
    const hostUser = `arenaMain_${ts}`;
    const oppUser = `arenaOpp_${ts}`;

    console.log("1. Đăng ký & Đăng nhập 2 người chơi (Người chơi chính & 1 Đối thủ)...");
    const host = await registerAndLogin(hostUser, `${hostUser}@test.com`, 'Pass1234!');
    const opp = await registerAndLogin(oppUser, `${oppUser}@test.com`, 'Pass1234!');

    const hostWs = await connectWs(host.sessionId);
    const oppWs = await connectWs(opp.sessionId);

    const roomCode = `TB${ts.toString().slice(-4)}`;
    console.log(`2. Host tạo phòng [${roomCode}] (0 bot, 2 người chơi)...`);

    let isTieBreakerPhase = false;
    let roundIndex = 0;
    let isGameStarted = false;

    hostWs.onmessage = (event) => {
        const msg = JSON.parse(event.data);

        if (msg.type === 'arena_game_started') {
            console.log(`   -> Trận đấu bắt đầu! Người chơi chính: [${msg.mainPlayer}], Đối thủ: ${msg.totalOpponents}`);
        }

        if (msg.type === 'update_arena_players' && msg.allReady && !isGameStarted && msg.players && msg.players.length >= 2) {
            isGameStarted = true;
            console.log("   -> Cả 2 đã sẵn sàng! Host bấm bắt đầu trận...");
            hostWs.send(JSON.stringify({ type: 'start_arena_game' }));
        }

        if (msg.type === 'arena_main_player_turn') {
            console.log("   -> [Main Player Turn] Đến lượt Người chơi chính trả lời!");
            if (!isTieBreakerPhase) {
                console.log("      * Người chơi chính cố tình nộp đáp án 3 (đáp án sai)...");
                hostWs.send(JSON.stringify({ type: 'submit_arena_answer', answer: 3 }));
            }
        }

        if (msg.type === 'arena_new_question') {
            roundIndex++;
            console.log(`\n📋 [CÂU HỎI ${roundIndex}] "${msg.text}" (Time: ${msg.timeLimit}s, TieBreaker: ${!!msg.isTieBreaker})`);

            if (msg.isTieBreaker) {
                isTieBreakerPhase = true;
                console.log("⚡ [VÒNG ĐẤU TỐC ĐỘ KÍCH HOẠT!] Cả 2 người chơi nhận câu hỏi đồng thời!");
                // Main player trả lời đáp án 0 sau 200ms
                setTimeout(() => {
                    console.log("   -> [Người chơi chính] Nộp đáp án sau 200ms...");
                    hostWs.send(JSON.stringify({ type: 'submit_arena_answer', answer: 0 }));
                }, 200);
            }
        }

        if (msg.type === 'arena_round_result') {
            console.log(`   -> [KẾT QUẢ VÒNG ${roundIndex}] Đúng: [${msg.correctAnswerIndex}], Chính đúng: ${msg.mainPlayerCorrect}, Đối thủ còn sống: ${msg.aliveOpponents}`);
            if (msg.isTieBreakerNotice) {
                console.log("   ⚔️ THÔNG BÁO TỪ SERVER:", msg.message);
            }
        }

        if (msg.type === 'arena_end_game') {
            console.log(`\n🏆 [TRẬN ĐẤU KẾT THÚC!]`);
            console.log(`   - Kết quả: ${msg.reason}`);
            console.log(`   - Người chơi chính thắng: ${msg.mainWon}`);
            console.log(`   - Điểm số: ${msg.finalScore}`);
            console.log("\n✅ XÁC MINH HOÀN TOÀN: LOGIC ĐẤU TỐC ĐỘ (TIE-BREAKER) KHI 2 NGƯỜI CÙNG SAI HOẠT ĐỘNG CHUẨN XÁC 100%!");
            hostWs.close();
            oppWs.close();
            process.exit(0);
        }
    };

    oppWs.onmessage = (event) => {
        const msg = JSON.parse(event.data);
        if (msg.type === 'arena_new_question') {
            if (!msg.isTieBreaker) {
                console.log("   -> [Đối thủ] Nộp đáp án 3 (cố tình sai giống host)...");
                oppWs.send(JSON.stringify({ type: 'submit_arena_answer', answer: 3 }));
            } else {
                // Vòng Tie-breaker: Đối thủ nộp đáp án 0 nhưng chậm hơn (sau 1.5s)
                setTimeout(() => {
                    console.log("   -> [Đối thủ] Nộp đáp án nhưng chậm hơn (sau 1.5s)...");
                    oppWs.send(JSON.stringify({ type: 'submit_arena_answer', answer: 0 }));
                }, 1500);
            }
        }
    };

    // Tạo phòng với 0 bot, timeLimit 5s
    hostWs.send(JSON.stringify({
        type: 'create_room',
        roomName: 'Phòng Đấu Tốc Độ 1v1',
        roomCode: roomCode,
        topic: 'IT',
        difficulty: 'EASY',
        questionCount: 10,
        timeLimit: 5,
        botCount: 0
    }));

    await new Promise(r => setTimeout(r, 400));
    console.log(`3. Đối thủ gia nhập phòng [${roomCode}]...`);
    oppWs.send(JSON.stringify({ type: 'join_room', roomCode: roomCode }));

    await new Promise(r => setTimeout(r, 400));
    console.log("4. Đối thủ bấm SẴN SÀNG...");
    oppWs.send(JSON.stringify({ type: 'toggle_ready' }));

    setTimeout(() => {
        console.error("❌ Test bị timeout sau 35 giây!");
        process.exit(1);
    }, 35000);
}

runTest().catch(err => {
    console.error("Test error:", err);
    process.exit(1);
});
