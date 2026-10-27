# 🎮 GAME PORTAL - BÀI TẬP LỚN LẬP TRÌNH MẠNG (JAVA BACKEND THUẦN TÚY)

Dự án triển khai một Cổng Trò Chơi Mạng đa nền tảng với **Backend viết 100% bằng Java thuần túy (Pure Java SE 17)**, không phụ thuộc vào bất kỳ framework nặng nề nào (không cần Maven, Spring Boot, hay thư viện bên ngoài), sử dụng giao tiếp **TCP ServerSocket**, chuẩn **WebSocket RFC 6455**, và **HTTP REST**.

---

## 📌 1. Các Tính Năng Cốt Lõi

### Trò chơi 1: Đấu Trường 100 (Arena 100)
- **Một người chơi chính và tối đa 100 đối thủ:** Người thật và bot cùng chiếm số chỗ đối thủ; bot tự giảm nếu đủ chỗ.
- **Hai lượt trả lời:** Người chơi phụ có 5 giây trước, sau đó người chơi chính có 60 giây riêng để trả lời và dùng trợ giúp. Máy chủ kiểm soát lượt và thời hạn.
- **Chấm điểm & Loại trừ:** Khi người chơi chính trả lời đúng, các đối thủ sai bị loại. Điểm tăng tại mỗi mốc 5 người bị loại, kèm hiệu ứng thông báo từng mốc; loại 13 người chỉ đạt mốc 5 và 10.
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

### Thiết lập phòng và quản lý câu hỏi

- Trong sảnh game, chọn **Tạo phòng mới** để đặt giới hạn người chơi và chủ đề. Đấu trường 100 có thêm số bot, số câu và độ khó cố định hoặc tăng dần. Chọn **Xáo trộn tất cả chủ đề** để trộn câu hỏi giữa các chủ đề.
- Sinh tử dùng các vòng 1–5 Dễ, 6–10 Trung bình và từ vòng 11 Khó. Các giai đoạn cuối tăng phạt HP và giảm thời gian.
- Admin mở **Quản lý kho câu hỏi** trong sảnh người chơi (`/questions.html`) để thêm, sửa, xóa và tìm câu hỏi. Kho được lưu ở `data/questions.json`, áp dụng từ trận mới; đáp án đúng chỉ được trả qua API cho admin.
- Nếu một nhóm chủ đề/độ khó chưa đủ câu, trò chơi dùng hết nhóm rồi mới lặp lại, không lấy bù câu sai độ khó. Không được xóa câu cuối cùng của mỗi nhóm chủ đề/độ khó chính.
- Chủ phòng/admin có thể tạm dừng Đấu trường 100 trong lượt hỏi. Mất kết nối của người đang thi đấu tự tạm dừng; vào lại cùng tài khoản và mã phòng, rồi chủ phòng/admin bấm tiếp tục. Vai trò, đáp án đã khóa và trợ giúp được giữ nguyên. Rời phòng chủ động không chặn trận như mất mạng.
- Trợ giúp được khôi phục khi bắt đầu trận mới.

### Kiểm thử thay đổi luật chơi

Sau khi biên dịch mã Java, biên dịch `scratch/GameRulesTest.java` và `scratch/QuestionBankTest.java` với classpath `bin;lib/*`. Chạy `GameRulesTest` để kiểm tra luật và `node scratch/test_arena_ui.mjs` để kiểm tra trạng thái giao diện. `QuestionBankTest` phải chạy từ một thư mục tạm trống, sau đó chạy lại với tham số `reload` trong cùng thư mục để kiểm tra lưu/nạp kho câu hỏi mà không sửa dữ liệu thật.
