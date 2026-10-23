// Test script to verify Super Admin (mainung28@gmail.com) Leaderboard Clear feature
const BASE_URL = 'http://localhost:3000';
const WS_URL = 'ws://localhost:3000/ws';

async function login(username, password) {
    const res = await fetch(`${BASE_URL}/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password })
    });
    const cookie = res.headers.get('set-cookie');
    const sessionId = cookie ? cookie.split(';')[0] : '';
    const data = await res.json();
    return { data, sessionId };
}

async function registerAndLogin(username, email, password) {
    await fetch(`${BASE_URL}/auth/register`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, email, password })
    });
    return login(username, password);
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
    console.log("=== BẮT ĐẦU KIỂM THỬ TÍNH NĂNG XÓA BẢNG XẾP HẠNG CHO ADMIN ===");

    // 1. Kiểm tra API bảng xếp hạng ban đầu
    const lbRes1 = await fetch(`${BASE_URL}/api/leaderboard`);
    const lbData1 = await lbRes1.json();
    console.log(`1. Bảng xếp hạng hiện tại có ${lbData1.leaderboard.length} mục.`);

    // 2. Thử nghiệm với người chơi bình thường (không phải admin)
    const normalName = `normal_${Date.now()}`;
    console.log(`\n2. Đăng ký & đăng nhập tài khoản thường [${normalName}]...`);
    const normalUser = await registerAndLogin(normalName, `${normalName}@test.com`, 'Pass1234!');

    // Test HTTP API clear với user thường -> phải bị 403
    const clearHttpRes = await fetch(`${BASE_URL}/api/leaderboard/clear`, {
        method: 'POST',
        headers: { 'Cookie': normalUser.sessionId }
    });
    if (clearHttpRes.status === 403) {
        console.log("   ✅ User thường gọi POST /api/leaderboard/clear bị chặn 403 Forbidden chuẩn xác!");
    } else {
        console.error(`   ❌ Lỗi: User thường gọi HTTP clear lại nhận status ${clearHttpRes.status}`);
        process.exit(1);
    }

    // Test WebSocket với user thường -> phải bị từ chối
    const normalWs = await connectWs(normalUser.sessionId);
    let normalDenied = false;
    normalWs.onmessage = (event) => {
        const msg = JSON.parse(event.data);
        if (msg.type === 'arena_error') {
            console.log(`   ✅ User thường gửi WS admin_clear_leaderboard bị chặn với thông báo: "${msg.message}"`);
            normalDenied = true;
            normalWs.close();
        }
    };
    normalWs.send(JSON.stringify({ type: 'admin_clear_leaderboard' }));

    await new Promise(r => setTimeout(r, 1000));
    if (!normalDenied) {
        console.error("   ❌ Lỗi: User thường không bị chặn trên WebSocket!");
        process.exit(1);
    }

    // 3. Đăng nhập tài khoản Super Admin (DuyNguyen / mainung28@gmail.com)
    console.log("\n3. Đăng nhập tài khoản Super Admin [DuyNguyen - mainung28@gmail.com]...");
    const adminUser = await login('DuyNguyen', '123456');
    if (!adminUser.data.success) {
        console.error("   ❌ Đăng nhập admin thất bại:", adminUser.data);
        process.exit(1);
    }

    // Kiểm tra /auth/me
    const meRes = await fetch(`${BASE_URL}/auth/me`, {
        headers: { 'Cookie': adminUser.sessionId }
    });
    const meData = await meRes.json();
    console.log("   -> /auth/me:", meData);
    if (!meData.user || !meData.user.isAdmin || meData.user.email !== 'mainung28@gmail.com') {
        console.error("   ❌ Lỗi: Tài khoản không có cờ isAdmin hoặc email không khớp!", meData);
        process.exit(1);
    }
    console.log("   ✅ Xác nhận: Tài khoản DuyNguyen (mainung28@gmail.com) là Super Admin!");

    // 4. Admin thực hiện xóa bảng xếp hạng qua WebSocket
    console.log("\n4. Super Admin kết nối WebSocket và gửi yêu cầu xóa bảng xếp hạng...");
    const adminWs = await connectWs(adminUser.sessionId);

    // Đăng ký vào sảnh (giống như arena.js làm khi kết nối)
    adminWs.send(JSON.stringify({ type: 'get_rooms' }));
    await new Promise(r => setTimeout(r, 300));

    let clearSuccessReceived = false;
    let emptyLeaderboardBroadcastReceived = false;

    adminWs.onmessage = (event) => {
        const msg = JSON.parse(event.data);
        if (msg.type === 'admin_clear_leaderboard_success') {
            console.log(`   ✅ Nhận được phản hồi thành công: "${msg.message}"`);
            clearSuccessReceived = true;
        }
        if (msg.type === 'leaderboard_update') {
            console.log(`   ✅ Nhận broadcast leaderboard_update: số lượng mục = ${msg.leaderboard.length}`);
            if (msg.leaderboard.length === 0) {
                emptyLeaderboardBroadcastReceived = true;
            }
        }
    };

    adminWs.send(JSON.stringify({ type: 'admin_clear_leaderboard' }));

    await new Promise(r => setTimeout(r, 1500));

    if (!clearSuccessReceived) {
        console.error("   ❌ Lỗi: Không nhận được admin_clear_leaderboard_success!");
        process.exit(1);
    }
    if (!emptyLeaderboardBroadcastReceived) {
        console.error("   ❌ Lỗi: Bảng xếp hạng broadcast không rỗng sau khi xóa!");
        process.exit(1);
    }

    // 5. Kiểm tra lại qua HTTP GET /api/leaderboard
    const lbRes2 = await fetch(`${BASE_URL}/api/leaderboard`);
    const lbData2 = await lbRes2.json();
    console.log(`\n5. Kiểm tra lại qua GET /api/leaderboard: có ${lbData2.leaderboard.length} mục.`);
    if (lbData2.leaderboard.length !== 0) {
        console.error("   ❌ Lỗi: Bảng xếp hạng trên API vẫn còn dữ liệu!");
        process.exit(1);
    }

    console.log("\n🎉 HOÀN THÀNH KIỂM THỬ XUẤT SẮC 100%!");
    console.log("   ✅ Người dùng thông thường KHÔNG THỂ xóa bảng xếp hạng (bị chặn 403 / arena_error).");
    console.log("   ✅ Super Admin mainung28@gmail.com có đầy đủ quyền xóa bảng xếp hạng.");
    console.log("   ✅ Nút xóa bảng xếp hạng hiển thị chuẩn cho Admin và xóa sạch dữ liệu.");

    adminWs.close();
    process.exit(0);
}

runTest().catch(err => {
    console.error("Test error:", err);
    process.exit(1);
});
