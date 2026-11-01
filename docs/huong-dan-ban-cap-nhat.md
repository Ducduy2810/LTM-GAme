# Bản cập nhật cổng game học tập Vũ Môn

> **Rà soát ngày 01/11/2026:** Đường đua Kiến thức đã mở đấu rank, đấu thường, chơi máy và thử thách ngày. Báo cáo này giữ phần nền tảng cổng game và cập nhật lại các mô tả liên quan; xem [luật Đường đua và rank](duong-dua-xep-hang.md), [quản trị, cấu hình và bảo trì](quan-tri-server.md), [tài khoản và thông báo](tai-khoan-va-thong-bao.md).

> Đã có trang quản trị riêng `/admin`: xem [hướng dẫn quản trị, tạo tài khoản, CSV và triển khai](quan-tri-server.md). Chủ phòng được kick và xóa phòng riêng khi đang chờ/đã kết thúc; không được xóa lúc thi đấu hoặc tạm dừng trận, áp dụng cả ba game.

## Phạm vi đã triển khai

- Trang chủ dạng bản đồ đảo: Phòng học, Chơi với máy, Giải trí, PvP, Đấu hạng, Lịch sử, Bảng thành tích và Hồ sơ.
- Giữ ba game cũ dưới dạng giải trí/đấu thường. Không game nào được cộng điểm vào bảng rank.
- Chơi máy dùng bot có sẵn của Đấu Trường 100 và Chiếc Nón. Liên kết từ trang chủ điền cấu hình bot khi mở hộp tạo phòng. Đây là gợi ý cấu hình; người tạo phòng vẫn có thể điều chỉnh trước khi bắt đầu.
- PvP ba game cũ dùng các phòng nhiều người hiện có; cấu hình tạo phòng được điền 0 bot. Đường đua có hàng chờ ghép tự động riêng cho đấu thường và rank.
- Phòng học lấy câu hỏi đã xuất bản từ database, chấm trên server và hiển thị lời giải nếu có. Kết quả luyện tập chưa lưu lâu dài; chưa có EXP hoặc nhiệm vụ.
- Lịch sử 30 trận gần nhất theo tài khoản, lưu trên SQLite. Tài khoản và dữ liệu bảng thành tích cũ được giữ nguyên.
- Đường đua rank đã mở, bắt đầu từ Đồng 3; năm bậc từ Đồng đến Kim Cương, mỗi bậc ba cấp, tiếp theo là Cao Thủ không chia cấp. Chỉ ghép người cùng bậc lớn, dùng ngân hàng theo bậc và bốc từng câu trên server khi mở lượt; Cao Thủ dùng bộ Khó của Kim Cương nhưng ghép hàng chờ riêng. Có RP, lịch sử, ôn tập sau trận và Top 100; chưa có mùa giải/reset mùa hoặc EXP.
- Admin có trang đăng nhập riêng, quản lý tài khoản, phòng/trận, câu hỏi/ô chữ, chủ đề, nhật ký, cấu hình, bảo trì theo thời hạn và sao lưu. Có vận hành rank và bảng xếp hạng toàn server; chưa có quản lý mùa giải.

## Các thay đổi quản trị và rank đã bổ sung

- **Tổng quan:** đếm tài khoản đã đăng nhập và đang online trên toàn trang, gộp các tab/kết nối của cùng tài khoản; thêm số trận đang diễn ra, không tính phòng chờ.
- **Rời trận rank:** xác nhận trước khi về trang chủ; chủ động rời hoặc bị kick bị ghi bỏ cuộc và không vào lại trận cũ. Đã sửa lỗi người bỏ cuộc bị chặn tìm trận mới do còn liên kết phòng cũ. Mất kết nối rank đột ngột vẫn được nối lại nếu trận chưa kết thúc.
- **Thông báo:** kick, hủy trận hoặc admin hủy lượt tìm trận hiển thị hộp thoại kèm lý do. Khi bắt đầu lượt rank mới, dọn ôn tập cũ và ẩn lịch sử/Top 100 trong giao diện thi đấu; lịch sử lưu trong database được giữ nguyên.
- **Ngân hàng câu hỏi:** lọc theo chế độ, độ khó, trạng thái và bậc rank; admin chọn bộ rank khi thêm/sửa hoặc nhập CSV. Có 75 câu khởi tạo riêng cho năm bậc, độ khó tăng dần; các lượt rank không lặp ID câu trong cùng trận.
- **Bảo trì:** chọn số giờ hoặc ngày giờ kết thúc theo giờ Việt Nam. Tích bật/tắt áp dụng ngay, đăng xuất người chơi, hủy hàng chờ/trận và chặn đăng nhập đến hạn; admin vẫn hoạt động.
- **Sao lưu/cấu hình:** xem trước và áp dụng cấu hình từ bản sao lưu trên server, tạo bản an toàn trước khi ghi, giữ nguyên tài khoản/câu hỏi/lịch sử. Việc tạo bản sao không tự áp dụng cấu hình. Chức năng tải file từ máy lên từng được bổ sung nhưng đã được gỡ trong mã nguồn hiện tại.
- **Biểu mẫu admin:** tách bản nhập khỏi dữ liệu trực tiếp cho cấu hình game, Đường đua, dịch vụ, thử thách ngày và thông báo. Hiện thay đổi chưa lưu, lưu riêng từng biểu mẫu, có Bỏ thay đổi, giữ dữ liệu khi lưu lỗi và chặn phản hồi cũ ghi đè; phòng/người chơi/hàng chờ vẫn cập nhật định kỳ. Rank vẫn cho phép 10–15 câu/trận.

## Cách chạy

Chạy `run.bat`, sau đó mở `http://localhost:3000`. Nếu server cũ vẫn chạy, dừng nó trước rồi chạy lại để nạp mã Java mới. Có thể cần Ctrl+F5 trong trình duyệt sau cập nhật.

Tiếp tục dùng JDK 17 trở lên và các JAR SQLite/SLF4J hiện có. Chưa chuyển MySQL: một server Java sở hữu database SQLite cục bộ vẫn đáp ứng mô hình client–server; đổi hệ quản trị không tự giải quyết phân quyền hoặc gian lận.

## Database và sao lưu

Lần đầu nâng cấp database có bảng `users` nhưng chưa có bảng `matches`, server dùng SQLite `VACUUM INTO` tạo bản sao nhất quán tại `data/backups/before-history-<timestamp>.db`, bao gồm dữ liệu đã commit trong WAL. Nếu sao lưu thất bại, quá trình khởi tạo dừng để không nâng cấp tiếp.

Schema được bổ sung theo cách chạy lại an toàn:

- `matches`: UUID, game, mode `CASUAL`/`BOT`, phòng, cấu hình JSON, thời gian bắt đầu/kết thúc, trạng thái và lý do.
- `match_players`: liên kết `users.id`, tên tại thời điểm thi đấu, điểm, thứ hạng nếu có và kết quả. Bot không tạo tài khoản/thành tích cá nhân.
- Các chỉ mục cho tra cứu lịch sử theo tài khoản và thời gian.

Các thao tác ghi trận dùng kết nối JDBC riêng, bật foreign key và busy timeout. Kết thúc trận dùng transaction và điều kiện `status='ACTIVE'` để chỉ ghi kết quả một lần. Khi đóng phòng, trận được hủy; khi khởi động tiến trình server mới, các trận còn active chuyển thành `INTERRUPTED`. Không khôi phục trận đang chạy sau khi server khởi động lại. Với ba game cũ, người đã rời trận không còn trong danh sách cuối được giữ với kết quả `LEFT` và điểm mặc định; chưa lưu từng câu trả lời của các game này. Đường đua có bảng trận, người tham gia, từng câu, đáp án và sổ RP riêng; xem [schema và vận hành Đường đua](duong-dua-xep-hang.md).

`leaderboard` cũ chỉ dùng xem thành tích trước cập nhật. Điểm trong từng ván vẫn hoạt động, nhưng không cộng vào bảng này nữa. API HTTP xóa bảng thành tích cũ đã bị vô hiệu hóa.

### Cách tính điểm Đấu Trường 100

Điểm người chơi chính là tổng điểm theo thứ tự các đối thủ đã bị loại; không phải lấy số bị loại nhân với mức của mốc cuối:

| Đối thủ bị loại thứ | Điểm cộng cho mỗi đối thủ |
| --- | --- |
| 1–5 | +100 |
| 6–10 | +150 |
| 11–20 | +200 |
| 21–40 | +250 |
| 41–60 | +300 |
| 61–80 | +400 |
| Từ 81 | +500 |

Ví dụ loại 10 đối thủ được `5 × 100 + 5 × 150 = 1.250` điểm. Công thức nằm trong `Leaderboard.calcMilestonePoints`, được dùng để cập nhật điểm ván của người chơi chính. Đây là điểm Đấu Trường 100, không phải RP Đường đua và không cộng vào bảng rank mới.

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
| `GET /api/portal/overview` | Đăng nhập | Lịch sử cá nhân, danh sách phòng và `rankEnabled=true`; khả năng nhận trận còn phụ thuộc cấu hình dịch vụ/bảo trì |
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

1. Bổ sung mùa giải/reset mùa nếu chốt được quy tắc; đấu rank, hàng chờ, ghép cùng bậc và sổ thay đổi RP đã triển khai.
2. Mở rộng lưu từng câu trả lời cho ba game cũ và tiến bộ học tập; bổ sung nhiệm vụ/EXP sau khi chốt quy tắc. Đường đua đã lưu từng lượt và đáp án.
3. Mở rộng chuẩn hóa giao thức/kết nối lại cho ba game cũ. Đường đua rank đã có nối lại khi mất mạng, giới hạn tham gia, token theo câu và xử lý riêng trường hợp bỏ cuộc.
4. Kiểm tra tải và cấu hình HTTPS/WSS cho môi trường triển khai thực tế. Chọn MySQL/PostgreSQL nếu nhu cầu ghi đồng thời hoặc số server thực tế yêu cầu.
