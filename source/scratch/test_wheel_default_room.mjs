// Test script for default room CNKD in Chiếc Nón Kỳ Diệu
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
    const user = `normalPlayer_${ts}`;

    console.log("1. Tạo tài khoản thường (không phải admin, không phải DuyNguyen)...");
    const u = await registerAndLogin(user, `${user}@test.com`, 'Pass1234!');
    const ws = await connectWs(u.sessionId);

    console.log("2. Vào phòng mặc định [CNKD]...");
    ws.send(JSON.stringify({ type: 'join_wheel_room', roomCode: 'CNKD' }));

    ws.onmessage = (event) => {
        const msg = JSON.parse(event.data);

        if (msg.type === 'wheel_room_info') {
            console.log(`   -> Thông tin phòng CNKD: Tên="${msg.roomName}", Chủ phòng hiệu lực="${msg.hostUsername}"`);
            if (msg.hostUsername === user) {
                console.log("   ✅ Tuyệt vời: Người chơi thường đã trở thành Chủ phòng hiệu lực vì DuyNguyen vắng mặt!");
            }
        }

        if (msg.type === 'update_wheel_players') {
            console.log(`   -> Danh sách người chơi: Số lượng=${msg.players.length}, Host="${msg.hostUsername}"`);
            // Thử bấm bắt đầu ván đấu
            console.log("3. Bấm bắt đầu ván đấu với tư cách Chủ phòng hiệu lực...");
            ws.send(JSON.stringify({ type: 'start_wheel_game' }));
        }

        if (msg.type === 'wheel_game_started') {
            console.log(`   🎉 VÁN ĐẤU BẮT ĐẦU THÀNH CÔNG! Chủ đề="${msg.topicName}", Gợi ý="${msg.hint}"`);
            console.log("\n✅ XÁC MINH HOÀN TOÀN: PHÒNG MẶC ĐỊNH CNKD KHÔNG CÒN BỊ LỖI TREO KHI DUYNGUYEN VẮNG MẶT!");
            ws.close();
            process.exit(0);
        }

        if (msg.type === 'wheel_error') {
            console.error("   ❌ Gặp lỗi:", msg.message);
            ws.close();
            process.exit(1);
        }
    };

    setTimeout(() => {
        console.error("❌ Test bị timeout sau 10s!");
        process.exit(1);
    }, 10000);
}

runTest().catch(err => {
    console.error("Test error:", err);
    process.exit(1);
});
