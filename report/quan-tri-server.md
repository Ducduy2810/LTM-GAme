# Trung tâm quản trị Vũ Môn

**Bổ sung:** mục **Bảng xếp hạng server** xem thứ hạng toàn server, tìm kiếm và phân trang cả ngoài Top 100. **Đường đua & Rank** quản lý ghép trận, thời gian, RP và thẻ; toàn bộ cấu hình/cấp lại lượt **Thử thách hằng ngày** nằm tại **Cấu hình & bảo trì**. Chi tiết tại [hướng dẫn đấu rank](duong-dua-xep-hang.md).

## Truy cập và tạo tài khoản

- Người chơi: `http://localhost:3000/`.
- Quản trị: `http://localhost:3000/admin`.
- Khi triển khai: cùng đường dẫn `/admin` trên tên miền của server Java, ví dụ `https://game.example.com/admin`.

Trang quản trị chạy trên cùng Java server và database với game, dùng tài khoản có `role='admin'` và phiên đăng nhập riêng. Tài khoản này không đăng nhập vào cổng chơi game. Tài khoản admin cũ có quyền trong database vẫn được giữ lại; đăng nhập tại `/admin` bằng mật khẩu hiện có. Để chơi game, dùng tài khoản người chơi khác.

Không có tên/mật khẩu quản trị mặc định. Không có chức năng đăng ký admin công khai.

Tại máy server, chạy **`setup-admin.bat`** một lần. Công cụ biên dịch dự án, hỏi tên tài khoản và mật khẩu 12–128 ký tự, xác nhận lại mật khẩu. Mật khẩu không hiện khi nhập. Phải chọn tên tài khoản chưa tồn tại. Công cụ tạo tài khoản riêng, không tự chuyển tài khoản người chơi thành admin.

Có thể tạo tự động bằng `java -cp "bin;lib/*" vn.game.db.AdminAccount --generate`. Công cụ chọn tên chưa tồn tại và mật khẩu ngẫu nhiên, hiển thị thông tin một lần trên terminal; database chỉ lưu mật khẩu đã băm, không ghi mật khẩu vào `.env` hoặc mã nguồn.

Để khôi phục mật khẩu admin từ terminal tương tác ở thư mục dự án:

```powershell
& '<JDK>\bin\java.exe' -cp 'bin;lib/*' vn.game.db.AdminAccount --reset '<ten-admin>'
```

Công cụ cấp/thu hồi quyền cục bộ từ bản trước vẫn có thể dùng cho người vận hành. Chỉ cấp `admin` cho tài khoản dành riêng để quản trị.

## Các chức năng

| Mục | Khả năng |
| --- | --- |
| Tổng quan | Số người chơi, kết nối WebSocket, trận đã ghi nhận, câu hỏi, ô chữ, tài khoản bị khóa; các trận gần đây |
| Người chơi | Tìm tên/email, xem lịch sử, khóa 1 giờ/24 giờ/7 ngày/đến khi mở thủ công, mở khóa, kết thúc toàn bộ phiên và kết nối |
| Phòng & trận trực tiếp | Xem thành viên và chủ phòng; gửi thông báo; kick thành viên; hủy phòng/trận có lý do |
| Ngân hàng câu hỏi | Thêm/sửa 4 lựa chọn, đáp án A–D, chủ đề, độ khó, lời giải; xem phiên bản; nháp/xuất bản/ẩn |
| Ngân hàng ô chữ | Thêm/sửa đáp án, gợi ý, chủ đề và kiến thức bổ sung; chuẩn hóa đáp án thành A–Z và khoảng trắng |
| Chủ đề | Thêm/sửa tên hiển thị và bật/ẩn chủ đề cho câu hỏi hoặc ô chữ |
| Lịch sử trận | Lọc theo người chơi/game/trạng thái; xem người tham gia, kết quả và bản chụp nội dung đã dùng |
| Nhật ký | Tra cứu ai làm gì, với đối tượng nào, thời điểm và lý do; gồm cả lần kick của chủ phòng |
| Cấu hình | Bật bảo trì, sửa thông báo, bật/tắt nhận trận mới cho từng game, cấu hình mặc định khi tạo phòng |
| Sao lưu | Tạo bản sao SQLite nhất quán, xem danh sách và tải về |
| Tài khoản | Đổi mật khẩu quản trị; kết thúc các phiên quản trị sau khi đổi |

Tổng quan và phòng trực tiếp tự cập nhật mỗi 10 giây khi đang mở và không có hộp thao tác. Đây là giám sát gần thời gian thực; gameplay vẫn dùng WebSocket.

Không xóa vật lý tài khoản/câu hỏi/trận từ giao diện. Dùng khóa tài khoản hoặc ẩn nội dung để bảo toàn lịch sử. Bảng thành tích cũ là dữ liệu tham khảo, không còn API HTTP xóa. Ba game vẫn chỉ thuộc giải trí/đấu thường và không cộng rank; chưa có chức năng vận hành mùa giải rank.

## Quyền của chủ phòng

| Thao tác | Chủ phòng | Thành viên | Quản trị qua `/admin` |
| --- | --- | --- | --- |
| Bắt đầu trận theo luật sẵn sàng | Có | Không | Không cần tham gia game |
| Chọn người chính Đấu Trường 100 | Có | Không | — |
| Kick người chơi thật trong phòng | Có, trừ bản thân/admin | Không | Có |
| Xóa phòng chờ hoặc phòng đã kết thúc | Có, với phòng riêng của mình | Không | Có |
| Hủy phòng khi trận đang diễn ra | Không | Không | Có |
| Tạm dừng/tiếp tục từ giao diện game | Không | Không | Bản này dùng hủy trận khi cần; không có nút pause |

Trong cả ba game, chủ phòng có bảng **“Quản lý thành viên · Chủ phòng”** ở góc dưới màn hình. Mở bảng và chọn Kick. Chỉ kick được người đang ở chính phòng đó. Người bị kick được đưa về sảnh; tài khoản đó không thể vào lại phòng đang tồn tại, kể cả tải trang hoặc mở kết nối khác. Đây là chặn theo tài khoản trong vòng đời phòng, không phải khóa toàn hệ thống. Phòng được hủy và tạo lại là một phòng mới.

Khi ở phòng chờ hoặc sau khi trận đã kết thúc, bảng này có nút **Xóa phòng chờ**. Xóa phòng sẽ đưa mọi thành viên về sảnh. Chủ phòng không được xóa phòng ở các trạng thái đang chuẩn bị, trả lời, xử lý kết quả hoặc đang chơi; server kiểm tra trạng thái đồng thời với việc đóng phòng. Phòng mặc định chỉ do admin quản lý.

Kick khi trận đang chạy dùng cùng cơ chế rời phòng để cập nhật lượt, số người và quyền chủ phòng. Người bị kick có trạng thái `KICKED` trong lịch sử trận đang chạy. Khi cả phòng bị admin hủy, kết quả của trận là `CANCELLED`, không ghi thắng/thua. Phòng mặc định được khởi tạo lại rỗng sau khi admin hủy, để luôn có phòng mặc định trong sảnh.

Ẩn nút ở client là một phần giao diện; server cũng từ chối lệnh xóa khi đang thi đấu, lệnh tạm dừng của chủ phòng và lệnh kick không đúng quyền. Xóa phòng chờ được ghi nhật ký, không tạo kết quả thắng/thua.

## Nội dung và CSV

Lần nâng cấp đầu tiên, câu hỏi và ô chữ Java được chép vào database một lần. Sau đó database là nguồn nội dung chính; khởi động lại không ghi đè nội dung đã sửa. Dữ liệu Java giữ vai trò dữ liệu khởi tạo.

- `DRAFT`: quản trị được xem/sửa, chưa dùng trong game.
- `PUBLISHED`: được dùng cho lượt mới nếu chủ đề đang bật.
- `ARCHIVED`: ẩn khỏi lượt mới, vẫn giữ trong quản trị và lịch sử.

Lưu nội dung tạo một phiên bản mới. Nếu hai cửa sổ cùng sửa một phiên bản, thao tác lưu sau bị từ chối và cần tải lại. Mỗi trận giữ các đối tượng câu hỏi/ô chữ đã chọn; sửa nội dung không đổi đáp án của trận đang chơi. Trận mới lưu bản chụp câu hỏi/đáp án/lời giải trong cấu hình lịch sử. Trận từ các bản cũ không có bản chụp thì không thể bổ sung ngược chính xác.

Trang ngân hàng có nút tải CSV mẫu, chọn tệp hoặc dán CSV, **Kiểm tra & xem trước**, rồi **Nhập dữ liệu**. Hỗ trợ CSV UTF-8/BOM, dấu phẩy, dấu ngoặc kép và xuống dòng trong ô đã trích dẫn; tối đa 500 dòng mỗi lần. Toàn bộ dữ liệu được kiểm tra trước khi ghi, ghi trong cùng transaction; một dòng sai sẽ không nhập bất kỳ dòng nào. Import tạo nội dung mới, không tự ghi đè theo tên.

CSV câu hỏi:

```csv
text,option_a,option_b,option_c,option_d,correct,topic,difficulty,explanation,status
Cổng HTTP mặc định là bao nhiêu?,21,443,80,8080,C,NETWORKING,EASY,HTTP mặc định dùng cổng 80.,DRAFT
```

CSV ô chữ:

```csv
phrase,hint,topic,explanation,status
LAP TRINH MANG,Một môn học chuyên ngành,IT,Kiến thức về kết nối và trao đổi dữ liệu.,DRAFT
```

`correct` dùng A/B/C/D; `difficulty` dùng EASY/MEDIUM/HARD; `topic` phải tồn tại đúng loại. Bỏ cột `status` sẽ mặc định DRAFT. Excel `.xlsx` chưa được hỗ trợ trực tiếp; xuất thành CSV UTF-8 trước khi nhập.

## Bảo trì, sao lưu và dữ liệu

Trong **Cấu hình & bảo trì**, bật **Bảo trì**, chọn **Theo số giờ** (có thể nhập số lẻ) hoặc **Đến ngày giờ cụ thể** theo giờ Việt Nam, nhập thông báo rồi lưu. Server lưu thời điểm kết thúc vào database và tự mở lại khi đến hạn, kể cả sau khi khởi động lại. Admin có thể tắt bảo trì sớm.

Khi bảo trì bắt đầu, mọi phiên người chơi bị đăng xuất, hàng chờ và các trận đang chạy bị hủy; trận rank bị hủy không cộng/trừ RP hoặc tiêu thụ thẻ. Người chơi ở sảnh và trong game nhận thông báo kèm thời gian kết thúc. Đăng nhập bằng mật khẩu, Google, truy cập API người chơi hoặc kết nối game đều bị chặn trong thời gian này. Trang admin và đăng nhập admin vẫn hoạt động. Hết bảo trì, người chơi đăng nhập lại bằng phiên mới.

Công tắc **Nhận trận mới** của từng game chỉ chặn tạo/bắt đầu trận mới cho game đó. Cấu hình mặc định áp dụng cho phòng mới; người tạo phòng giải trí vẫn có thể chọn cấu hình trong giới hạn luật hiện có.

Vẫn dùng SQLite, không yêu cầu MySQL. Trước nâng cấp schema quản trị trên database có người dùng, server tạo `data/backups/before-admin-<timestamp>.db`. Sao lưu thủ công dùng SQLite `VACUUM INTO`, gồm dữ liệu đã commit cả trong WAL. Tệp được lưu ở `data/backups/`; chỉ phiên quản trị hợp lệ mới tải được.

Các bảng bổ sung: `admin_audit_logs`, `app_settings`, `education_topics`, `game_questions`, `wheel_phrases`, `content_revisions`. Bảng `users` thêm `blocked_until` và `blocked_reason`. Tài khoản, trận và thành tích trước đây được giữ lại.

Không có nút restart Java hoặc khôi phục database trực tiếp trên web. Người vận hành dừng server, giữ bản sao database hiện tại rồi thực hiện khôi phục tại máy chủ. Không thay tệp `.db` khi ứng dụng còn chạy.

## Khi đồng đội lấy code và triển khai

1. Dừng tiến trình phiên bản cũ, cập nhật code và biên dịch bằng JDK 17 trở lên. Giữ các JAR trong `lib/`.
2. Giữ thư mục `data/` trên bộ nhớ bền vững của môi trường triển khai. Không dùng database trong Git hoặc sao chép database đang chạy qua Git.
3. Đặt `APP_BASE_URL=https://<ten-mien-thuc>` trong môi trường triển khai hoặc `.env` của server. Giá trị này dùng cho liên kết xác nhận, kiểm tra nguồn yêu cầu admin và cờ Secure của cookie admin.
4. Nếu đã có tài khoản admin, dùng tài khoản đó. Nếu chưa có và không chạy được công cụ console, đặt `ADMIN_BOOTSTRAP_USERNAME` và `ADMIN_BOOTSTRAP_PASSWORD` bằng secret của nền tảng. Server chỉ tạo khi chưa có admin; không ghi đè admin đã tồn tại. Gỡ hai secret khởi tạo sau khi tạo xong, quản lý mật khẩu bằng trang đổi mật khẩu hoặc công cụ cục bộ.
5. Chạy Java backend, cấu hình HTTPS và chuyển tiếp WebSocket cho `/ws`. Chỉ triển khai file tĩnh sẽ không chạy được game hay quản trị.
6. Truy cập `https://<ten-mien>/admin`. Database của bản triển khai và máy cá nhân là hai hệ thống riêng, không tự đồng bộ khi pull code.

Không tin cậy `X-Forwarded-For` để xác định người đăng nhập; giới hạn đăng nhập sai dựa trên địa chỉ kết nối tới Java. Nếu dùng reverse proxy, địa chỉ này có thể là địa chỉ proxy, vì vậy cần cấu hình thêm giới hạn đăng nhập ở proxy phù hợp với môi trường thực tế. Không đưa `.env`, mật khẩu, database hoặc tệp sao lưu vào Git.

Phiên quản trị dùng cookie riêng HttpOnly/SameSite=Strict, hết hạn sau 30 phút không hoạt động hoặc tối đa 8 giờ. Các lần lấy tổng quan/phòng tự động không gia hạn thời gian không hoạt động. Mọi POST sau đăng nhập cần CSRF token. Mật khẩu admin được lưu bằng PBKDF2; mật khẩu admin cũ được nâng cấp khi đăng nhập thành công. Quyền và khóa tài khoản được kiểm tra ở server.

## Kiểm tra

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scratch/test_admin.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scratch/test_portal.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scratch/test_portal_regressions.ps1
```

Các bài kiểm tra tạo database và tài khoản thử riêng trong `scratch/`, không gửi email và không thay tài khoản thật. Có thể chạy script admin với `-Preview`, lấy URL `PREVIEW_URL` rồi chạy `node scratch/test_admin_browser.mjs <URL>` trên máy có Chrome để kiểm tra giao diện và kick thật qua WebSocket ở cả ba game. Script preview chờ tối đa 10 phút; tạo tệp `stop-preview` trong thư mục test để kết thúc sớm.
