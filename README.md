# 🎮 GAME PORTAL - BÀI TẬP LỚN LẬP TRÌNH MẠNG (JAVA BACKEND THUẦN TÚY)

Dự án triển khai một Cổng Trò Chơi Mạng đa nền tảng với **Backend viết 100% bằng Java thuần túy (Pure Java SE 17)**, không phụ thuộc vào bất kỳ framework nặng nề nào (không cần Maven, Spring Boot, hay thư viện bên ngoài), sử dụng giao tiếp **TCP ServerSocket**, chuẩn **WebSocket RFC 6455**, và **HTTP REST**.

---

## 📌 1. Các Tính Năng Cốt Lõi

### Trò chơi 1: Đấu Trường 100 (Arena 100)
- **Hai lượt trả lời mỗi câu:** Người chơi phụ trả lời trước (mặc định 5 giây); hết lượt phụ mới bắt đầu lượt người chơi chính (mặc định 15 giây). Thời gian mỗi lượt cấu hình riêng từ 1–120 giây khi tạo phòng. Server kiểm tra lượt và hạn chót; không nhận đáp án sớm, muộn hoặc khi đang tạm dừng.
- **Chọn người chơi chính:** Chọn thủ công (mặc định chủ phòng, đổi trong phòng chờ) hoặc chọn ngẫu nhiên trong số người chơi thật khi bắt đầu. Quyền quản lý phòng thuộc chủ phòng, độc lập với vai trò người chơi chính.
- **Quyền trợ giúp:** Chỉ người chơi chính dùng trong lượt chính, trước khi chốt đáp án. Mỗi loại dùng một lần mỗi trận; yêu cầu sai thời điểm hoặc không có người phù hợp để hỏi sẽ không tiêu tốn quyền.
- **Chấm điểm & Loại trừ (Elimination):** Người chơi chính trả lời đúng thì loại các đối thủ trả lời sai/không trả lời. Người chính trả lời sai hoặc hết giờ thì thua; loại hết đối thủ thì thắng.
- **Độ khó và chủ đề:** Dễ/Trung bình/Khó chỉ lấy câu hỏi đúng cấu hình. Tăng dần phân bổ Dễ → Trung bình → Khó, xáo trộn trong mỗi nhóm. Thiếu câu sẽ dùng số lượng khả dụng và thông báo rõ, không lấy bù sai độ khó/chủ đề. Bot trả lời theo độ khó của từng câu.
- **Xóa phòng:** Chủ phòng hoặc admin có thể xác nhận xóa phòng đang chờ hoặc đang chơi. Hệ thống hủy timer, đưa thành viên về sảnh và không ghi thắng/thua cho trận bị hủy. Phòng mặc định `DT100` được bảo vệ.
- **Đồng bộ trạng thái:** Tự động phát kết quả vòng thi và số lượng người còn sống sót cho toàn bộ client.

### Trò chơi 2: Chiếc Nón Kỳ Diệu (Wheel of Fortune)
- **Mô hình Theo lượt (Turn-based Concurrency):** Phòng chơi giới hạn 4 người.
- **Timer 10 giây cho từng lượt:** Người chơi có 10 giây để bấm "Quay Nón" hoặc nhập "Đoán Chữ". Nếu hết 10 giây mà không thao tác, Server tự động phạt "Mất lượt" và chuyển lượt cho người tiếp theo.
- **Ô chữ bí mật:** Bảng chữ cái tương tác thời gian thực, tính điểm theo chữ xuất hiện và xử lý các ô đặc biệt (*Mất lượt*, *Chia đôi điểm*).
- **Khả năng chịu lỗi (Fault Tolerance):** Nếu một người chơi rớt mạng/disconnect giữa chừng lúc đang đến lượt mình, máy chủ tự động thu hồi timer và chuyển lượt mượt mà, không làm crash server.

### Trò chơi 3: Đấu Trường Sinh Tử (Survival Arena)
- **Tạm dừng / Tiếp tục:** Chủ phòng hoặc admin được dừng khi chuẩn bị câu hỏi, đang trả lời hoặc đang xem kết quả. Thời gian còn lại được giữ nguyên; server không nhận đáp án trong lúc tạm dừng.
- **Công bố sớm:** Khi mọi người chơi còn sống đang tham gia vòng đã chốt đáp án, server công bố ngay. Người đã bị loại/đang xem không làm vòng đấu phải chờ. Nếu còn người chưa trả lời, vòng đấu chờ hết thời gian; mỗi người chỉ chốt được một đáp án hợp lệ.
- **Thông báo kết quả cá nhân:** Hiện cả chữ cái và nội dung đáp án đúng, lựa chọn của người chơi, kết luận ĐÚNG/SAI hoặc không trả lời và HP thực tế bị trừ. Người đã bị loại ở vòng trước được ghi nhận là đang xem, không bị báo sai hay trừ HP thêm. Điểm thưởng mốc sinh tồn được trình bày riêng.
- **Xóa phòng:** Chủ phòng hoặc admin có thể xóa phòng ở sảnh hoặc bằng nút trên thanh điều khiển trong phòng. Hệ thống hủy các timer, thông báo hủy trận và đưa thành viên về sảnh. Phòng mặc định `DTST` được bảo vệ.

Kiểm thử Sinh tử: biên dịch project rồi chạy `scratch/test_survival_rules.ps1` và `node scratch/test_survival_ui.mjs`. Kiểm tra giao diện nạp đúng `arena_sound.js` như trang thực tế, thử các vòng đúng → sai → đúng và mô phỏng thiết bị âm thanh lỗi để bảo đảm thông báo luôn cập nhật đúng câu, đáp án vẫn được gửi. Các bài kiểm thử dùng dữ liệu riêng, không gửi email hoặc thay đổi tài khoản thật.

### Hệ thống Tài khoản & Bảo mật (Authentication & Security)
- **Đăng ký / Đăng nhập:** Báo lỗi ngay khi nhập email sai định dạng, kiểm tra email/tên đăng nhập bị trùng, gửi liên kết xác nhận email để kích hoạt tài khoản mới. Không cần nhập mã khi đăng ký. Mật khẩu được mã hóa một chiều bằng **SHA-256 kèm Salt ngẫu nhiên** (`SecureRandom`).
- **Quản lý phiên (Session Management):** Cấp `session_id` qua HTTP Cookie (`HttpOnly; SameSite=Lax`). Kết nối WebSocket tự động bóc tách cookie để xác thực danh tính người chơi.
- **Quên mật khẩu (Forgot Password):** Gửi mã 6 số qua email đã đăng ký, có hiệu lực 10 phút và chỉ dùng một lần. Mã được băm trong SQLite, giới hạn 5 lần nhập sai và chờ 60 giây trước khi gửi lại. Đổi mật khẩu sẽ đăng xuất các phiên đăng nhập cũ.

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
javac --release 17 -encoding UTF-8 -cp "lib/*" -d bin src/vn/game/config/*.java src/vn/game/util/*.java src/vn/game/model/*.java src/vn/game/db/*.java src/vn/game/server/*.java src/vn/game/games/*.java src/vn/game/*.java

:: Bước 2: Chạy Server
java -cp "bin;lib/*" vn.game.Main
```

---

## 🌐 4. Trải Nghiệm Game

1. **Mở trình duyệt trên máy của bạn:** Truy cập 👉 `http://localhost:3000`
2. **Cho bạn bè cùng mạng Wi-Fi (LAN) tham gia:** Họ chỉ cần vào trình duyệt và nhập:
   👉 `http://192.168.1.203:3000` (hoặc IP hiển thị trên màn hình Console)
3. Đăng ký tài khoản, đăng nhập và chọn một trong hai game để bắt đầu đấu trường!

Trong Đấu trường 100, chọn **Tạo phòng mới** để cài thời gian lượt phụ/lượt chính và chế độ chọn người chính. Trong phòng chờ, chủ phòng có thể chọn một người đang có mặt hoặc chuyển sang chế độ ngẫu nhiên. Người được chọn rời phòng trước khi bắt đầu thì cần chọn lại. Nút **Xóa phòng** xuất hiện cho chủ phòng/admin trong phòng chờ, khi đang chơi và trên danh sách phòng. Khi chơi, các nút trả lời/trợ giúp được mở đúng lượt; tạm dừng giữ nguyên thời gian còn lại của lượt hiện tại.

Trong trận Đấu trường 100, bục của người chơi chính có viền vàng, biểu tượng 👑 và nhãn **CHÍNH** trên màn hình của mọi người. Tên đầy đủ luôn hiện phía trên khán đài; tên và vai trò được đồng bộ lại khi tải lại/kết nối thay thế. Bục của bản thân nếu chỉ là người chơi phụ dùng màu xanh để phân biệt với người chơi chính.

Cả ba trò đều hiển thị thông báo **“Người chơi [tên] đã mất kết nối”** đến các thành viên còn lại trong cùng phòng. Thông báo hiện khoảng 12 giây và không chặn trả lời, đếm giờ hoặc quay nón. Mất kết nối TCP/đóng trang được xử lý khi server phát hiện; mất mạng đột ngột được kiểm tra bằng Ping mỗi 10 giây, chờ Pong tối đa 20 giây, nên thường phát hiện trong khoảng 20–30 giây. Browser tự trả lời frame Ping ở tầng WebSocket theo [RFC 6455](https://www.rfc-editor.org/rfc/rfc6455.html#section-5.5.2). Chủ động bấm rời phòng không bị báo là mất mạng; không gửi thông báo của phòng này sang phòng khác. Chiếc nón vẫn lưu sự kiện vào nhật ký phòng.

Kiểm thử luật Đấu trường 100 (database thử riêng, không dùng tài khoản thật): chạy `scratch/test_arena_rules.ps1` sau khi biên dịch. Kiểm tra trạng thái các nút trên giao diện bằng `node scratch/test_arena_ui.mjs`.

Kiểm thử kết nối thật cho cả ba trò: `scratch/test_connection_notices.ps1` (mô phỏng cả kết nối đóng đột ngột, không trả Pong, trả Pong bình thường, chủ động rời và thay kết nối). Script tạo database riêng và cổng cục bộ riêng. Kiểm tra thông báo Chiếc nón: `node scratch/test_wheel_presence_ui.mjs`.

## Cấu hình gửi mã đặt lại mật khẩu

Tạo `.env` ở thư mục gốc (tham khảo `.env.example`). Nếu đã có `.env`, chỉ cập nhật các biến cần thiết:

```dotenv
GMAIL_USER=your-address@gmail.com
GMAIL_APP_PASSWORD=your-google-app-password
SMTP_HOST=smtp.gmail.com
SMTP_PORT=465
APP_BASE_URL=http://localhost:3000
```

`GMAIL_USER` là hộp thư gửi mã của hệ thống; người chơi nhập email đã đăng ký của họ để nhận mã. Dùng mật khẩu ứng dụng Google, không dùng mật khẩu đăng nhập Gmail. Cần bật Xác minh 2 bước trước khi tạo mật khẩu ứng dụng: https://support.google.com/accounts/answer/185833?hl=vi . SMTP sử dụng SSL/TLS trực tiếp; không đặt cổng 587 cho cấu hình này. Không đưa mật khẩu thật vào `.env.example` hoặc commit `.env`.

Chọn **Quên mật khẩu → Gửi mã xác nhận**, rồi nhập email, mã 6 số, mật khẩu mới (8–128 ký tự) và xác nhận mật khẩu. Có thể gửi lại mã sau 60 giây; mã cũ sẽ mất hiệu lực khi mã mới được cấp. Nếu hết hạn hoặc nhập sai 5 lần, cần yêu cầu mã mới. Email chưa đăng ký sẽ nhận lỗi rõ ràng và không được chuyển tới màn hình nhập mã. Trong thời gian chờ gửi lại, API trả lỗi 429 thay vì báo đã gửi mã mới. Chỉ báo gửi thành công sau khi SMTP tiếp nhận thư; cần kiểm tra cả mục thư rác trong hộp thư nhận. Lỗi cấu hình hoặc lỗi SMTP sẽ được báo rõ và không in mã ra terminal. Cấu hình gửi mail trong `.env` được đọc lại ở mỗi yêu cầu gửi mã; không cần khởi động lại chỉ để thay mật khẩu ứng dụng Gmail.

Cơ sở dữ liệu cũ được tự động bổ sung cột khi khởi động, giữ nguyên tài khoản và bảng xếp hạng. Luồng cũ `/reset.html?token=...` không còn được sử dụng.

## Kiểm tra email khi đăng ký tài khoản mới

Nhập tên đăng nhập, email và mật khẩu (8–128 ký tự) → **Tạo Tài Khoản** → mở email → bấm liên kết để kích hoạt tài khoản → đăng nhập. Không cần nhập mã xác nhận khi đăng ký. Email sai định dạng vẫn hiện thông báo đỏ ngay dưới ô nhập. Tên đăng nhập/email bị trùng sẽ bị từ chối.

API `POST /auth/register` nhận `{username, email, password}` và chỉ lưu thông tin chờ xác nhận trong bảng `pending_registrations` (mật khẩu và token đều được băm). API `POST /auth/verify-email` nhận token từ liên kết và tạo tài khoản hoạt động khi token đúng. Liên kết có hạn 30 phút và dùng một lần; gửi lại sau 60 giây bằng cách đăng ký lại với cùng tên, email và mật khẩu. Liên kết mới thay thế liên kết cũ. Nếu đăng ký nhầm thông tin đang chờ, có thể đăng ký lại sau khi yêu cầu cũ hết hạn. Gửi email thất bại sẽ báo lỗi và hủy yêu cầu chờ để có thể thử lại ngay.

Đặt `APP_BASE_URL` trong `.env` thành địa chỉ truy cập thật của server (ví dụ `http://localhost:3000` khi chạy trên máy này hoặc `http://192.168.1.10:3000` khi chạy LAN). Khi triển khai, dùng tên miền HTTPS của bạn. Mặc định là `http://localhost:3000`; không tự lấy địa chỉ từ header của client. Khi bấm liên kết trên điện thoại/máy khác, cần cấu hình địa chỉ LAN hoặc tên miền mà thiết bị đó truy cập được và giữ server đang chạy. Token nằm trong fragment của URL và được xóa khỏi thanh địa chỉ trước khi gửi yêu cầu xác nhận, không đưa vào log HTTP.

Tài khoản cũ vẫn đăng nhập bình thường. Chức năng lấy lại mật khẩu vẫn kiểm tra email của tài khoản đã hoạt động và yêu cầu mã 6 số gửi qua email trước khi đổi mật khẩu. Hộp thư không tồn tại hoặc không truy cập được sẽ không thể hoàn tất kích hoạt tài khoản mới. Việc SMTP tiếp nhận thư không có nghĩa thư chắc chắn đã đến hộp thư; cần kiểm tra cả thư rác.
