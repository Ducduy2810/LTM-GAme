# Cập nhật tài khoản và thông báo

**Cập nhật mới:** đấu rank đã mở trong **Đường đua Kiến thức**. Phần mô tả “chưa mở/Chưa xếp hạng” bên dưới ghi lại trạng thái bản trước; tài khoản mới hiện bắt đầu ở Đồng 3. Xem [luật và quản trị Đường đua](duong-dua-xep-hang.md).

## Sử dụng

Chạy `run.bat`; người chơi vào `http://localhost:3000`, quản trị viên vào `/admin`. Thông tin admin trên máy hiện tại được ghi ở đầu README theo yêu cầu chủ dự án.

Trong **Thông báo toàn server**, nhập tối đa 500 ký tự và thời hạn 0–10080 phút. Số 0 giữ đến khi tắt. Phát lại thay nội dung trước; nút Tắt gỡ dòng chữ chạy trên các trang. Nội dung được lưu trong database, vẫn còn sau khi khởi động lại nếu chưa hết hạn. Client nhận thông báo bằng SSE `/api/events`, độc lập với phòng/game; máy chủ kiểm tra hết hạn mỗi 5 giây. Proxy khi deploy cần cho phép kết nối SSE dài, tắt buffering, bên cạnh hỗ trợ WebSocket hiện có.

Trong **Người chơi**, admin nhập lý do khi khóa/ngắt phiên. Người đang online nhận hộp thoại ngay cả ở trang chủ. Lý do còn được lưu trong `account_notices` để hiển thị ở lần đăng nhập tiếp theo; người dùng xác nhận đã đọc bằng nút Đã hiểu. Tài khoản đang bị khóa nhận lý do sau khi nhập đúng mật khẩu hoặc đăng nhập Google thành công. Sai mật khẩu không tiết lộ lý do xử lý tài khoản. Chỉ tài khoản sở hữu thông báo được xác nhận thông báo của mình.

**Hồ sơ của tôi** hiển thị rank hiện tại và cao nhất từ `player_ranks`; dữ liệu mới mặc định Chưa xếp hạng, 0 điểm. Chế độ đấu rank và công thức tăng/giảm điểm riêng chưa được triển khai. Điểm lịch sử ba game giải trí không được chuyển sang bảng rank này. Hai bảng bổ sung tự tạo khi khởi động, giữ database SQLite và dữ liệu hiện có.

Đổi mật khẩu cần mật khẩu hiện tại, mật khẩu mới 8–128 ký tự và xác nhận lại. Server kiểm tra phiên và CSRF, băm PBKDF2, xóa mã khôi phục cũ, kết thúc các phiên HTTP/WebSocket. Tài khoản tạo bằng Google chưa có mật khẩu có thể dùng nút Nhận mã đặt mật khẩu hoặc Quên mật khẩu để đặt qua email. Không nhập mật khẩu Google vào game.

## Google OAuth

Trong `.env` hoặc biến môi trường hosting:

```dotenv
APP_BASE_URL=http://localhost:3000
GOOGLE_CLIENT_ID=your-web-client-id.apps.googleusercontent.com
GOOGLE_CLIENT_SECRET=your-client-secret
GOOGLE_REDIRECT_URI=http://localhost:3000/auth/google/callback
```

Mở Google Cloud Console → Google Auth Platform / Credentials → OAuth client dạng **Web application**. Thêm chính xác giá trị `GOOGLE_REDIRECT_URI` vào **Authorized redirect URIs**, gồm giao thức, tên miền, cổng và đường dẫn. Xem giá trị server đang dùng tại admin → Google & Email. Khi deploy, thay `APP_BASE_URL` và `GOOGLE_REDIRECT_URI` bằng tên miền HTTPS thật. Nếu consent screen còn Testing, thêm các email thử vào Test users. `localhost` trên máy người chơi là máy của họ, vì vậy server công khai không được để callback localhost.

Nếu chưa đặt `GOOGLE_REDIRECT_URI`, server dùng `APP_BASE_URL + /auth/google/callback`; mặc định localhost theo cổng server. Khi mở local qua 127.0.0.1, nút Google đưa về tên miền cấu hình trước khi tạo state để tránh mất cookie. Không tạo callback từ header tùy ý. Luồng mới có state ràng buộc với cookie, hạn 10 phút, dùng một lần, PKCE S256 và kiểm tra email đã được Google xác minh. Không cho dùng Google để vào tài khoản admin. Giao diện giải thích lỗi state/cấu hình/kết nối thay vì trả về trang lỗi trống.

Kiểm thử tự động mô phỏng endpoint Google để kiểm tra logic ứng dụng. Việc đồng ý cấp quyền bằng tài khoản Google thật và cấu hình Cloud Console cần thực hiện trên tài khoản của chủ dự án; kiểm thử mô phỏng không xác nhận các cấu hình bên Google.

Kiểm tra trực tiếp ngày 30/10/2026 trên cấu hình hiện tại: URL bắt đầu OAuth đã đi đến trang nhập tài khoản Google (`/v3/signin/identifier`, HTTP 200), không phát hiện `redirect_uri_mismatch` hoặc `invalid_client` trước bước người dùng cấp quyền. Callback đang dùng là `http://localhost:3000/auth/google/callback`. Chưa thực hiện đăng nhập/cấp quyền bằng tài khoản Google thật.

Tài liệu chính thức: [OAuth cho web server](https://developers.google.com/identity/protocols/oauth2/web-server), [OpenID Connect và email_verified](https://developers.google.com/identity/openid-connect/reference).

## Email đăng ký và quên mật khẩu

```dotenv
GMAIL_USER=your-address@gmail.com
GMAIL_APP_PASSWORD=your-app-password
SMTP_HOST=smtp.gmail.com
SMTP_PORT=465
SMTP_SECURITY=auto
```

Cổng 465 dùng TLS trực tiếp; cổng 587 dùng STARTTLS khi để auto. Mật khẩu ứng dụng có thể chứa khoảng trắng phân nhóm, server loại bỏ trước khi xác thực. Không dùng mật khẩu đăng nhập Gmail. [Hướng dẫn mật khẩu ứng dụng](https://support.google.com/mail/answer/185833), [Thông số SMTP Gmail](https://developers.google.com/workspace/gmail/imap/imap-smtp).

Đăng ký gửi **liên kết kích hoạt** có hạn 30 phút, quên mật khẩu gửi **mã 6 số** có hạn 10 phút. Chỉ báo thành công sau khi SMTP tiếp nhận thư. Kiểm tra cả Spam; SMTP tiếp nhận chưa bảo đảm thư đến Inbox. Nếu gửi thất bại, yêu cầu chờ được hủy để thử lại, giao diện trả mã lỗi có thể phân biệt cấu hình, mạng, TLS, xác thực và máy chủ từ chối. Không in mật khẩu, mã hay token ra log.

Trong admin → **Google & Email → Kiểm tra kết nối email**, server kết nối TLS và xác thực nhưng không gửi thư. Chức năng này kiểm tra kết nối server, không kiểm tra hộp thư nhận. Lần chẩn đoán trên máy hiện tại: TLS và SMTP AUTH thành công khi tiến trình được truy cập mạng; môi trường chạy hạn chế mạng trả lỗi `Permission denied: getsockopt`. Nếu gặp lại, chạy `run.bat` trong terminal bình thường hoặc kiểm tra mạng/firewall/giới hạn SMTP của hosting. Không cần quyền Administrator của Windows chỉ để chạy game.

`.env` được đọc lại khi bắt đầu OAuth hoặc gửi/kiểm tra email. Khi deploy, đồng đội phải cấu hình biến môi trường và giữ volume database; không copy bí mật vào source.

## Kiểm thử

Các script tạo database thử riêng trong `scratch`, không sửa tài khoản thật và không gửi thư đến email thật. `scratch/test_account_features.ps1` kiểm tra thông báo SSE, quyền truy cập, lý do khóa/ngắt phiên, profile/rank, đổi mật khẩu và Google OAuth bằng dữ liệu giả. `scratch/test_password_reset.ps1` kiểm tra đăng ký/kích hoạt/khôi phục qua SMTP TLS giả lập cục bộ. Các bộ `test_admin.ps1`, `test_portal.ps1`, `test_portal_regressions.ps1` kiểm tra các tính năng hiện có.

Kết quả 30/10/2026: 101 kiểm tra tài khoản/SSE/OAuth; 100 kiểm tra đăng ký/khôi phục gồm TLS trực tiếp, STARTTLS và kiểm tra kết nối không gửi thư; 140 quản trị; 35 portal; 574 Arena; 46 Sinh tử; 36 WebSocket/heartbeat. Các kiểm tra JS hiện có đều đạt. Chrome headless xác nhận phát/tắt thông báo từ admin, hiển thị an toàn ở cả ba game, profile desktop/mobile, đổi mật khẩu và lý do khóa/ngắt phiên tại game, trang chủ và lần đăng nhập. `scratch/test_account_browser.mjs` nhận URL server thử từ `test_account_features.ps1 -Preview`. Server thật được chạy lại và nút kiểm tra Email trả TLS/xác thực thành công, không gửi thư thật.
