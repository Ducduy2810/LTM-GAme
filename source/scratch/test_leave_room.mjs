// Quick test to verify leaving survival room
const BASE_URL = 'http://localhost:3000';
const WS_URL = 'ws://localhost:3000/ws';

async function testLeave() {
    const ts = Date.now();
    const username = `leaveTester_${ts}`;

    // Register & Login
    await fetch(`${BASE_URL}/auth/register`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, email: `${username}@test.com`, password: 'Pass1234!' })
    });
    const loginRes = await fetch(`${BASE_URL}/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password: 'Pass1234!' })
    });
    const cookie = loginRes.headers.get('set-cookie');
    const sessionId = cookie ? cookie.split(';')[0] : '';

    const ws = new WebSocket(WS_URL, { headers: { 'Cookie': sessionId } });

    ws.onopen = () => {
        console.log("1. Connected. Joining DTST room...");
        ws.send(JSON.stringify({ type: 'join_survival_room', roomCode: 'DTST' }));
    };

    let inRoom = false;
    let leftRoom = false;

    ws.onmessage = (event) => {
        const msg = JSON.parse(event.data);
        console.log(`[Message] ${msg.type}`);

        if (msg.type === 'survival_room_info') {
            inRoom = true;
            console.log("2. In room DTST! Now testing leaving room...");
            setTimeout(() => {
                ws.send(JSON.stringify({ type: 'leave_survival_room', roomCode: 'DTST' }));
            }, 300);
        }

        if (msg.type === 'left_survival_room_success' || msg.type === 'survival_left_room_success') {
            leftRoom = true;
            console.log("3. Successfully received left room success message!");
            console.log("✅ RỜI PHÒNG THÀNH CÔNG VÀ CHÍNH XÁC!");
            ws.close();
            process.exit(0);
        }
    };

    setTimeout(() => {
        if (!leftRoom) {
            console.error("❌ Test leave room timeout!");
            process.exit(1);
        }
    }, 5000);
}

testLeave().catch(err => {
    console.error(err);
    process.exit(1);
});
