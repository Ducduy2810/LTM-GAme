# 🎮 GAME PORTAL - BÀI TẬP LỚN LẬP TRÌNH MẠNG (JAVA BACKEND THUẦN TÚY)

Dự án triển khai một Cổng Trò Chơi Mạng đa nền tảng với **Backend viết 100% bằng Java thuần túy (Pure Java SE 17)**, không phụ thuộc vào bất kỳ framework nặng nề nào (không cần Maven, Spring Boot, hay thư viện bên ngoài), sử dụng giao tiếp **TCP ServerSocket**, chuẩn **WebSocket RFC 6455**, và **HTTP REST**.

---

## 📌 1. Các Tính Năng Cốt Lõi

### Trò chơi 1: Đấu Trường 100 (Arena 100)
- **Mô hình Đồng thời (Simultaneous Concurrency):** Cho phép tới 100 client kết nối và gửi câu trả lời cùng lúc.
- **Server Timer 10 giây độc lập:** Máy chủ chịu trách nhiệm bấm giờ 10 giây (dùng `ScheduledExecutorService`). Sau 10 giây, máy chủ khóa cổng thu nhận đáp án để chống gian lận/delay.
- **Chấm điểm & Loại trừ (Elimination):** Người trả lời đúng được vào vòng trong; người trả lời sai hoặc không kịp nộp bị loại ngay lập tức.
- **Đồng bộ trạng thái:** Tự động phát kết quả vòng thi và số lượng người còn sống sót cho toàn bộ client.

### Trò chơi 2: Chiếc Nón Kỳ Diệu (Wheel of Fortune)
- **Mô hình Theo lượt (Turn-based Concurrency):** Phòng chơi giới hạn 4 người.
- **Timer 10 giây cho từng lượt:** Người chơi có 10 giây để bấm "Quay Nón" hoặc nhập "Đoán Chữ". Nếu hết 10 giây mà không thao tác, Server tự động phạt "Mất lượt" và chuyển lượt cho người tiếp theo.
- **Ô chữ bí mật:** Bảng chữ cái tương tác thời gian thực, tính điểm theo chữ xuất hiện và xử lý các ô đặc biệt (*Mất lượt*, *Chia đôi điểm*).
- **Khả năng chịu lỗi (Fault Tolerance):** Nếu một người chơi rớt mạng/disconnect giữa chừng lúc đang đến lượt mình, máy chủ tự động thu hồi timer và chuyển lượt mượt mà, không làm crash server.

### Hệ thống Tài khoản & Bảo mật (Authentication & Security)
- **Đăng ký / Đăng nhập:** Quản lý tài khoản người dùng, mã hóa mật khẩu một chiều bằng **SHA-256 kèm Salt ngẫu nhiên** (`SecureRandom`).
- **Quản lý phiên (Session Management):** Cấp `session_id` qua HTTP Cookie (`HttpOnly; SameSite=Lax`). Kết nối WebSocket tự động bóc tách cookie để xác thực danh tính người chơi.
- **Quên mật khẩu (Forgot Password):** Server sinh token 32 ký tự ngẫu nhiên có hạn sử dụng 1 giờ, hiển thị đường dẫn khôi phục trực tiếp lên màn hình Console máy chủ và cho phép đổi mật khẩu mới.

---

## 🏗️ 2. Kiến Trúc Kỹ Thuật (Network Programming Concepts)

- **TCP ServerSocket:** Mở cổng `3000` lắng nghe các kết nối từ client.
- **Unified Protocol Multiplexing:** Trên cùng cổng 3000, Server tự động nhận diện:
  - Nếu có header `Upgrade: websocket`: Thực hiện bắt tay (Handshake) chuyển sang WebSocket RFC 6455 (hashing SHA-1 chuỗi `Sec-WebSocket-Key` + Magic GUID `258EAFA5-E914-47DA-95CA-C5AB0DC85B11` và mã hóa Base64).
  - Nếu là request HTTP: Xử lý các REST API (`/auth/login`, `/auth/register`, `/auth/forgot`,...) hoặc phục vụ file tĩnh giao diện (`index.html`, `arena.html`, `wheel.html`, `style.css`, `*.js`).
- **Xử lý Đa luồng (Multithreading):**
  - Sử dụng `Executors.newCachedThreadPool()` xử lý từng kết nối client độc lập.
  - Sử dụng cấu trúc dữ liệu Thread-Safe: `ConcurrentHashMap`, `CopyOnWriteArrayList`, `AtomicInteger` để chống xung đột race condition khi 100 client gửi đáp án cùng lúc.
- **Tự viết parser JSON thuần túy (`JsonUtil.java`):** Không cần import thư viện Jackson/Gson bên ngoài.

---

## 🚀 3. Hướng Dẫn Chạy Project

### Cách 1: Chạy bằng 1-Click (Khuyên dùng)
Nhấp đúp chuột vào file:
👉 **`run.bat`**

File batch sẽ tự động biên dịch toàn bộ source code Java và khởi chạy máy chủ.

### Cách 2: Chạy bằng dòng lệnh Terminal
Mở PowerShell hoặc Command Prompt tại thư mục `arena_100_java`:

```cmd
:: Bước 1: Biên dịch code Java
javac -encoding UTF-8 -d bin src/vn/game/util/*.java src/vn/game/model/*.java src/vn/game/db/*.java src/vn/game/server/*.java src/vn/game/games/*.java src/vn/game/*.java

:: Bước 2: Chạy Server
java -cp bin vn.game.Main
```

---

## 🌐 4. Trải Nghiệm Game

1. **Mở trình duyệt trên máy của bạn:** Truy cập 👉 `http://localhost:3000`
2. **Cho bạn bè cùng mạng Wi-Fi (LAN) tham gia:** Họ chỉ cần vào trình duyệt và nhập:
   👉 `http://192.168.1.203:3000` (hoặc IP hiển thị trên màn hình Console)
3. Đăng ký tài khoản, đăng nhập và chọn một trong hai game để bắt đầu đấu trường!
