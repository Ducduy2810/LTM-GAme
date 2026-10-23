// Test script to verify timing in Đấu Trường 100
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
    const hostUser = `arMain_${ts}`;
    const oppUser = `arOpp_${ts}`;

    console.log("1. Đăng ký & Đăng nhập 2 người chơi...");
    const host = await registerAndLogin(hostUser, `${hostUser}@test.com`, 'Pass1234!');
    const opp = await registerAndLogin(oppUser, `${oppUser}@test.com`, 'Pass1234!');

    const hostWs = await connectWs(host.sessionId);
    const oppWs = await connectWs(opp.sessionId);

    const roomCode = `AR${ts.toString().slice(-4)}`;
    console.log(`2. Host tạo phòng [${roomCode}] không cài đặt thời gian...`);

    let questionReceivedTime = 0;
    let mainAnswerSubmittedTime = 0;
    let resultReceivedTime = 0;
    let mainReceivedTimeLimit = 0;
    let oppReceivedTimeLimit = 0;
    let isGameStarted = false;

    hostWs.onmessage = (event) => {
        const msg = JSON.parse(event.data);

        if (msg.type === 'create_room_success') {
            console.log(`   -> Tạo phòng ${roomCode} thành công!`);
            oppWs.send(JSON.stringify({ type: 'join_room', roomCode }));
        }

        if (msg.type === 'update_arena_players') {
            const allReady = msg.players && msg.players.length >= 2;
            if (allReady && !isGameStarted) {
                isGameStarted = true;
                console.log("   -> Cả 2 đã vào phòng, host bắt đầu trận...");
                hostWs.send(JSON.stringify({ type: 'start_arena_game' }));
            }
        }

        if (msg.type === 'arena_error') {
            console.error("   ❌ [LỖI TỪ SERVER]:", msg.message);
            process.exit(1);
        }

        if (msg.type === 'arena_new_question') {
            questionReceivedTime = Date.now();
            mainReceivedTimeLimit = msg.timeLimit;
            console.log(`   -> [Người chơi chính] Nhận câu hỏi! timeLimit = ${msg.timeLimit}s, isMainPlayer = ${msg.isMainPlayer}`);

            // Người chơi chính trả lời NGAY LẬP TỨC sau 500ms (không cần đợi đối thủ!)
            setTimeout(() => {
                mainAnswerSubmittedTime = Date.now();
                console.log(`   -> [Người chơi chính] Nộp đáp án A (0) sau 500ms...`);
                hostWs.send(JSON.stringify({ type: 'submit_arena_answer', answer: 0 }));
            }, 500);
        }

        if (msg.type === 'arena_round_result') {
            resultReceivedTime = Date.now();
            const elapsedSinceQuestion = (resultReceivedTime - questionReceivedTime) / 1000;
            const elapsedSinceAnswer = (resultReceivedTime - mainAnswerSubmittedTime) / 1000;
            console.log(`   -> [KẾT QUẢ VÒNG NHẬN ĐƯỢC!]`);
            console.log(`      * Thời gian từ lúc nhận câu hỏi đến khi có kết quả: ${elapsedSinceQuestion.toFixed(2)}s`);
            console.log(`      * Thời gian từ lúc Người chơi chính nộp đến khi có kết quả: ${elapsedSinceAnswer.toFixed(2)}s`);

            // Kiểm tra:
            // 1. Người chơi chính nhận timeLimit = 15
            if (mainReceivedTimeLimit !== 15) {
                console.error(`❌ THẤT BẠI: Người chơi chính phải nhận timeLimit = 15, thực tế: ${mainReceivedTimeLimit}`);
                process.exit(1);
            }
            // 2. Đối thủ nhận timeLimit = 5
            if (oppReceivedTimeLimit !== 5) {
                console.error(`❌ THẤT BẠI: Đối thủ phải nhận timeLimit = 5, thực tế: ${oppReceivedTimeLimit}`);
                process.exit(1);
            }
            // 3. Thời gian không bị skip sau khi người chơi chính nộp
            // Vì không skip, elapsedSinceQuestion phải xấp xỉ 15s (>= 14s).
            if (elapsedSinceQuestion < 14) {
                console.error(`❌ THẤT BẠI: Server đã skip thời gian thừa! (Chỉ mất ${elapsedSinceQuestion.toFixed(2)}s thay vì đủ 15s)`);
                process.exit(1);
            }

            console.log(`\n🎉 TẤT CẢ CÁC ĐIỀU KIỆN ĐỀU ĐẠT CHUẨN 100%!`);
            console.log(`   ✅ Đối thủ nhận thời gian 5s.`);
            console.log(`   ✅ Người chơi chính nhận thời gian 15s.`);
            console.log(`   ✅ Người chơi chính được trả lời ngay mà không phải chờ đối thủ.`);
            console.log(`   ✅ Người chơi chính trả lời xong KHÔNG bị skip thời gian thừa (chạy đủ 15s)!`);

            hostWs.close();
            oppWs.close();
            process.exit(0);
        }
    };

    oppWs.onmessage = (event) => {
        const msg = JSON.parse(event.data);

        if (msg.type === 'room_info') {
            console.log(`   -> [Đối thủ] Đã vào phòng. Bấm sẵn sàng...`);
            oppWs.send(JSON.stringify({ type: 'toggle_ready' }));
        }

        if (msg.type === 'arena_new_question') {
            oppReceivedTimeLimit = msg.timeLimit;
            console.log(`   -> [Đối thủ] Nhận câu hỏi! timeLimit = ${msg.timeLimit}s, isMainPlayer = ${msg.isMainPlayer}`);

            // Đối thủ trả lời sau 1.5s
            setTimeout(() => {
                console.log(`   -> [Đối thủ] Nộp đáp án A (0) sau 1.5s...`);
                oppWs.send(JSON.stringify({ type: 'submit_arena_answer', answer: 0 }));
            }, 1500);
        }
    };

    // Tạo phòng
    hostWs.send(JSON.stringify({
        type: 'create_room',
        roomName: `Test Timing ${roomCode}`,
        roomCode: roomCode,
        topic: 'ALL',
        difficulty: 'EASY',
        questionCount: 5,
        botCount: 0
    }));
}

runTest().catch((err) => {
    console.error("Test error:", err);
    process.exit(1);
});
