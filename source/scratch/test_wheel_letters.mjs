// Test script for Chiếc Nón Kỳ Diệu: Guessing individual letters & turn passing
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
    const p1Name = `wL1_${ts}`;
    const p2Name = `wL2_${ts}`;

    console.log("1. Tạo 2 người chơi...");
    const p1 = await registerAndLogin(p1Name, `${p1Name}@test.com`, 'Pass1234!');
    const p2 = await registerAndLogin(p2Name, `${p2Name}@test.com`, 'Pass1234!');

    const ws1 = await connectWs(p1.sessionId);
    const ws2 = await connectWs(p2.sessionId);

    const roomCode = `WL${ts.toString().slice(-4)}`;
    let testDone = false;

    ws1.onmessage = (event) => {
        const msg = JSON.parse(event.data);
        if (msg.type === 'wheel_state') handleState(msg);
        if (msg.type === 'wheel_end_game') {
            console.log("   -> Game kết thúc thành công!", msg.reason);
            testDone = true;
            ws1.close();
            ws2.close();
            process.exit(0);
        }
    };

    ws2.onmessage = (event) => {
        const msg = JSON.parse(event.data);
        if (msg.type === 'wheel_state') handleState(msg);
    };

    console.log(`2. Tạo phòng ${roomCode} và P2 tham gia...`);
    ws1.send(JSON.stringify({
        type: 'create_wheel_room',
        roomName: 'Phòng Thử Đoán Chữ',
        roomCode: roomCode,
        topic: 'IT',
        timeLimit: 10
    }));

    await new Promise(r => setTimeout(r, 400));
    ws2.send(JSON.stringify({ type: 'join_wheel_room', roomCode: roomCode }));

    await new Promise(r => setTimeout(r, 400));
    ws2.send(JSON.stringify({ type: 'toggle_wheel_ready' }));

    await new Promise(r => setTimeout(r, 400));
    console.log("3. Bắt đầu ván đấu...");
    ws1.send(JSON.stringify({ type: 'start_wheel_game' }));

    let hasGuessedLetter = false;

    function handleState(data) {
        if (testDone) return;
        const currentTurnUser = data.currentTurnUsername;
        const currentAction = data.currentAction;
        const ws = (currentTurnUser === p1Name) ? ws1 : ws2;

        if (currentAction === 'SPIN') {
            console.log(`   [${currentTurnUser}] Quay nón...`);
            setTimeout(() => {
                ws.send(JSON.stringify({ type: 'wheel_spin' }));
            }, 400);
        } else if (currentAction === 'GUESS') {
            if (!hasGuessedLetter) {
                hasGuessedLetter = true;
                // Đoán thử 1 chữ cái có trong từ, ví dụ 'C' hoặc 'N'
                const phraseLetters = data.phrase.replace(/\s+/g, '');
                const letterToGuess = phraseLetters.charAt(0);
                console.log(`   [${currentTurnUser}] Thử đoán chữ cái: '${letterToGuess}'...`);
                setTimeout(() => {
                    ws.send(JSON.stringify({ type: 'wheel_guess', letter: letterToGuess }));
                }, 400);
            } else {
                console.log(`   [${currentTurnUser}] Giải luôn ô chữ: "${data.phrase}"...`);
                setTimeout(() => {
                    ws.send(JSON.stringify({ type: 'wheel_solve_phrase', phrase: data.phrase }));
                }, 400);
            }
        }
    }

    setTimeout(() => {
        if (!testDone) {
            console.error("❌ Timeout!");
            process.exit(1);
        }
    }, 15000);
}

runTest();
