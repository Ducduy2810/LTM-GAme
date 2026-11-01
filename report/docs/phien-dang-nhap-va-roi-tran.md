# Trạng thái online, đăng nhập trùng và rời trận

Rà soát ngày **01/11/2026**. Quy tắc mất kết nối của đấu thường và rank được tách rõ ở các phần dưới.

## Quản lý người chơi

- **Trạng thái tài khoản** cho biết tài khoản được phép đăng nhập hay đang bị khóa. Đây là quyền truy cập, không phải trạng thái online.
- **Kết nối người chơi** hiển thị Online khi tài khoản có kết nối đang hoạt động ở cổng game hoặc WebSocket. Một tài khoản mở nhiều tab chỉ tính một người.
- Chỉ đăng nhập rồi đóng trang không được tính là đang online. Phiên quản trị được ghi riêng, không tính là kết nối người chơi.
- Khi đang mở mục Người chơi, admin tự tải lại dữ liệu mỗi 3 giây, giữ nguyên từ khóa và trang đang xem. Tạm dừng khi tab bị ẩn hoặc đang mở hộp thao tác.
- Khi mất mạng đột ngột, trạng thái thay đổi sau khi máy chủ phát hiện kết nối đã mất; WebSocket dùng Ping/Pong để phát hiện kết nối không còn phản hồi.

Ô **Kết nối trực tiếp** ở Tổng quan dùng cùng nguồn trạng thái, tổng hợp SSE ở trang chủ/phòng học và WebSocket trong game, gộp theo tài khoản. Ô **Trận đang diễn ra** đếm trận đã bắt đầu có trạng thái ACTIVE của mọi game, không tính phòng chờ. Xem [hướng dẫn quản trị](quan-tri-server.md).

## Đăng nhập một tài khoản ở nhiều nơi

- Lần đăng nhập thành công mới nhất thay thế các phiên người chơi trước của cùng tài khoản. Áp dụng cho mật khẩu và Google.
- Phiên cũ nhận thông báo tài khoản được đăng nhập ở nơi khác qua thông báo trực tiếp và WebSocket. Cookie/CSRF của phiên cũ không còn quyền thực hiện thao tác.
- Khi tải lại bằng cookie cũ trong 10 phút, API vẫn giải thích lý do phiên kết thúc.
- Phiên mới được thông báo đã chuyển phiên. Nhập sai mật khẩu không ngắt phiên đang hoạt động.
- Hai yêu cầu đăng nhập đồng thời được xử lý tuần tự để chỉ còn một phiên hợp lệ. Nút đăng nhập chặn bấm lặp trong khi yêu cầu đang xử lý.
- Việc mở nhiều tab với cùng cookie không tự tạo thêm phiên đăng nhập. Khi bị thay phiên trong một trận đấu thường, sự tham gia ở kết nối cũ được xử lý như rời trận. Luật tái kết nối của rank vẫn được giữ.

## Rời trận đấu thường

Áp dụng cho Đấu Trường 100, Chiếc Nón Kỳ Diệu, Đấu Trường Sinh Tử và đấu thường/đấu bot của Đường đua Kiến thức.

- Rời trong khi trận đang diễn ra: ghi nhận **Bỏ cuộc – Không tính thành tích**, không nhận điểm/chiến thắng của trận đó và không được quay lại trận đang diễn ra để khôi phục kết quả.
- Người còn lại tiếp tục thi đấu; nếu không còn đối thủ thật hoặc máy, trận được kết thúc phù hợp với luật từng trò.
- Khi người chơi duy nhất rời trận với bot, hủy trận ngay. Đường đua ở chế độ BOT cũng hủy khi người chủ trì rời trận.
- Rời phòng chờ hoặc rời sau khi trận kết thúc không bị tính bỏ cuộc.
- Không xóa kết quả các trận trước, không khóa tài khoản và không trừ RP cho các game đấu thường.
- Bị chủ phòng/admin mời ra được ghi riêng là **Bị mời rời phòng**, không hiển thị thành tự bỏ cuộc.
- Máy chủ xử lý cả yêu cầu rời hợp lệ lẫn ngắt kết nối khi đang đấu thường. Không suy đoán được người chơi cố ý tắt mạng hay mất mạng thật, nên cả hai đều mất quyền tính thành tích của trận đang chơi.
- Hộp xác nhận xuất hiện khi bấm rời hoặc chuyển trang trong trận. Đóng tab/tải lại dùng cảnh báo của trình duyệt khi trình duyệt cho phép. Chọn ở lại không đóng WebSocket.
- Kết quả bỏ cuộc được giữ trong database và không thể bị thao tác chốt kết quả hoặc hủy trận ghi đè thành chiến thắng.

## Rời trận rank và kết nối lại

- Khi bấm rời hoặc về trang chủ trong trận rank, hỏi **“Bạn đang trong trận xếp hạng quan trọng, bạn vẫn rời phòng chứ ?”**. Đồng ý gửi yêu cầu rời, liên kết về trang chủ đợi server xác nhận đúng trận trước khi chuyển trang.
- Chủ động rời hoặc bị kick bị ghi bỏ cuộc/tính thua và không được quay lại trận đó. RP được chốt khi trận cũ kết thúc, không nhận RP dương từ việc bỏ cuộc.
- Người đã bỏ cuộc được tìm rank mới ngay. Đã sửa liên kết phòng cũ để việc chốt/hủy trận cũ không xóa chỗ hay gửi trạng thái đè lên trận mới; thẻ bảo vệ chờ chốt ở trận cũ được giữ riêng.
- Mất kết nối đột ngột trong rank giữ quyền tiếp tục nếu trận chưa kết thúc. Thời gian server vẫn chạy, câu hết hạn không được trả lời lại. Đây là khác biệt với xử lý mất kết nối ở đấu thường nêu trên.
- Bị kick nhận hộp thoại kèm lý do; admin hủy trận hoặc lượt tìm trận cũng có thông báo riêng với lý do. Hủy trận và bảo trì không cộng/trừ RP hoặc tiêu thẻ.
- Bật bảo trì áp dụng ngay cho cả người đang ở sảnh: hiện giờ kết thúc, thu hồi phiên, hủy hàng chờ/trận và chặn người chơi đăng nhập đến hạn. Admin dùng phiên riêng nên vẫn truy cập được.

Chi tiết luật, ghép cùng bậc, câu hỏi và giao diện sau trận nằm trong [hướng dẫn Đường đua](duong-dua-xep-hang.md); cấu hình thời hạn bảo trì nằm trong [hướng dẫn quản trị](quan-tri-server.md).

## Kiểm thử liên quan

- `scratch/test_player_lifecycle.ps1`: database và cổng riêng; kiểm tra trạng thái online, đăng nhập đồng thời, thay phiên giữa trận, rời/ngắt kết nối trong ba game, chặn vào lại, loại kết quả và hủy trận với bot.
- `node scratch/test_match_leave_guard.mjs`: cảnh báo, hủy thao tác rời, tránh hỏi hai lần và bỏ cảnh báo cũ sau khi phiên bị thay thế.
- `scratch/test_account_features.ps1`, `scratch/test_race.ps1`: hồi quy tài khoản, thông báo trực tiếp, Google OAuth mô phỏng và luật xếp hạng.
