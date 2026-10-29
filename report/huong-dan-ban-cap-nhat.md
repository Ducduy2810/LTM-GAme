# Bản cập nhật cổng game học tập Vũ Môn

> **Bản mới hơn:** Đường đua Kiến thức đã mở đấu rank và đấu thường. Những mô tả “rank chưa mở” trong báo cáo này thuộc phiên bản trước. Xem [hướng dẫn Đường đua, RP và admin](duong-dua-xep-hang.md).

> Đã có trang quản trị riêng `/admin`: xem [hướng dẫn quản trị, tạo tài khoản, CSV và triển khai](quan-tri-server.md). Chủ phòng được kick và xóa phòng riêng khi đang chờ/đã kết thúc; không được xóa lúc thi đấu hoặc tạm dừng trận, áp dụng cả ba game.

## Phạm vi đã triển khai

- Trang chủ dạng bản đồ đảo: Phòng học, Chơi với máy, Giải trí, PvP, Đấu hạng, Lịch sử, Bảng thành tích và Hồ sơ.
- Giữ ba game cũ dưới dạng giải trí/đấu thường. Không game nào được cộng điểm vào bảng rank.
- Chơi máy dùng bot có sẵn của Đấu Trường 100 và Chiếc Nón. Liên kết từ trang chủ điền cấu hình bot khi mở hộp tạo phòng. Đây là gợi ý cấu hình; người tạo phòng vẫn có thể điều chỉnh trước khi bắt đầu.
- PvP dùng các phòng nhiều người hiện có; cấu hình tạo phòng được điền 0 bot. Đây chưa phải hệ thống tự động ghép trận.
- Phòng học lấy câu hỏi đã xuất bản từ database, chấm trên server và hiển thị lời giải nếu có. Kết quả luyện tập chưa lưu lâu dài; chưa có EXP hoặc nhiệm vụ.
- Lịch sử 30 trận gần nhất theo tài khoản, lưu trên SQLite. Tài khoản và dữ liệu bảng thành tích cũ được giữ nguyên.
- Đấu hạng riêng và bảng xếp hạng mùa giải hiển thị rõ chưa mở. Không tạo rank/EXP hoặc bảng người chơi giả.
- Admin có trang đăng nhập riêng, quản lý tài khoản, phòng/trận, câu hỏi/ô chữ, chủ đề, nhật ký, cấu hình và sao lưu. Quản lý mùa giải chưa được triển khai vì chế độ rank riêng chưa mở.

## Cách chạy

Chạy `run.bat`, sau đó mở `http://localhost:3000`. Nếu server cũ vẫn chạy, dừng nó trước rồi chạy lại để nạp mã Java mới. Có thể cần Ctrl+F5 trong trình duyệt sau cập nhật.

Tiếp tục dùng JDK 17 trở lên và các JAR SQLite/SLF4J hiện có. Chưa chuyển MySQL: một server Java sở hữu database SQLite cục bộ vẫn đáp ứng mô hình client–server; đổi hệ quản trị không tự giải quyết phân quyền hoặc gian lận.

## Database và sao lưu

Lần đầu nâng cấp database có bảng `users` nhưng chưa có bảng `matches`, server dùng SQLite `VACUUM INTO` tạo bản sao nhất quán tại `data/backups/before-history-<timestamp>.db`, bao gồm dữ liệu đã commit trong WAL. Nếu sao lưu thất bại, quá trình khởi tạo dừng để không nâng cấp tiếp.

Schema được bổ sung theo cách chạy lại an toàn:

- `matches`: UUID, game, mode `CASUAL`/`BOT`, phòng, cấu hình JSON, thời gian bắt đầu/kết thúc, trạng thái và lý do.
- `match_players`: liên kết `users.id`, tên tại thời điểm thi đấu, điểm, thứ hạng nếu có và kết quả. Bot không tạo tài khoản/thành tích cá nhân.
- Các chỉ mục cho tra cứu lịch sử theo tài khoản và thời gian.

Các thao tác ghi trận dùng kết nối JDBC riêng, bật foreign key và busy timeout. Kết thúc trận dùng transaction và điều kiện `status='ACTIVE'` để chỉ ghi kết quả một lần. Khi đóng phòng, trận được hủy; khi khởi động tiến trình server mới, các trận còn active chuyển thành `INTERRUPTED`. Không khôi phục trận đang chạy sau khi server khởi động lại. Người đã rời trận không còn trong danh sách cuối được giữ với kết quả `LEFT` và điểm mặc định; chưa lưu từng câu trả lời của game.

`leaderboard` cũ chỉ dùng xem thành tích trước cập nhật. Điểm trong từng ván vẫn hoạt động, nhưng không cộng vào bảng này nữa. API HTTP xóa bảng thành tích cũ đã bị vô hiệu hóa.

## Quyền quản trị

Chạy `setup-admin.bat` để tạo tài khoản quản trị riêng, sau đó đăng nhập tại `/admin`. Tài khoản admin không dùng để chơi game. Chi tiết về phiên quản trị, thời hạn đăng nhập và deploy được trình bày trong [hướng dẫn quản trị](quan-tri-server.md).

Đã bỏ toàn bộ cơ chế cấp/kiểm tra admin theo tên `admin`, `DuyNguyen` hoặc email đặc biệt. Tài khoản mới luôn là `user`; tài khoản cũ có `role='admin'` vẫn giữ quyền. Phòng mặc định trao quyền chủ phòng cho người tham gia đầu tiên thay vì gán cho một tên tài khoản cố định.

Để cấp quyền cho tài khoản đã tồn tại, người vận hành chạy lệnh sau tại thư mục dự án, bằng `java.exe` của JDK 17+ mà `run.bat` sử dụng:

```powershell
& '<duong-dan-JDK>\bin\java.exe' -cp 'bin;lib/*' vn.game.db.AdminAccount '<ten-tai-khoan>' admin
```

Đổi đối số cuối thành `user` để thu hồi quyền. Đây là công cụ vận hành cục bộ, không phải API công khai. Chưa tự cấp quyền cho bất kỳ tài khoản thật nào trong lần sửa này.

Ô chữ `wheel_state` chỉ gửi các chữ đã mở và ký tự `_` ở ô còn bí mật. Đáp án đầy đủ chỉ được gửi khi kết thúc ván. Quyền điều khiển phòng được server kiểm tra độc lập với giao diện.

## API bổ sung

| API | Quyền | Công dụng |
| --- | --- | --- |
| `GET /api/portal/overview` | Đăng nhập | Lịch sử cá nhân, danh sách phòng và `rankEnabled=false` |
| `/api/admin/*` | Phiên admin riêng | Các chức năng quản trị; thay thế `/api/portal/admin` cũ |
| `POST /api/practice/next` | Đăng nhập | Nhận câu hỏi theo `topic`, `difficulty`, không có đáp án |
| `POST /api/practice/answer` | Đăng nhập | Chốt đáp án theo `questionId`, `answer` và nhận kết quả |

Mỗi tài khoản có một câu luyện tập chờ chốt, hết hạn sau 10 phút. Lấy câu mới thay câu cũ; gửi lại câu đã chốt hoặc gửi đáp án cho câu của tài khoản khác bị từ chối.

## Kiểm tra

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scratch/test_portal.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scratch/test_portal_regressions.ps1
```

Các script tạo database riêng trong `scratch/`, không sử dụng tài khoản thật và không gửi email. `-Preview` trên script đầu giữ server thử tối đa 5 phút; URL xuất hiện ở dòng `PREVIEW_URL`. Có thể dùng URL này với `node scratch/test_portal_browser.mjs <URL>` nếu máy có Chrome để kiểm tra giao diện và lưu ảnh desktop/mobile trong `scratch/`.

## Phần nên làm tiếp

1. Xây chế độ đấu hạng mới: câu hỏi đồng thời, luật cố định, hàng chờ, ghép trận, mùa giải và sổ thay đổi điểm.
2. Lưu từng câu trả lời và tiến bộ học tập; bổ sung nhiệm vụ/EXP sau khi chốt quy tắc.
3. Chuẩn hóa kết nối lại, thời gian chờ tái kết nối, giới hạn một tài khoản/một trận và mã câu/lượt trong bản tin game.
4. Kiểm tra tải và cấu hình HTTPS/WSS cho môi trường triển khai thực tế. Chọn MySQL/PostgreSQL nếu nhu cầu ghi đồng thời hoặc số server thực tế yêu cầu.
