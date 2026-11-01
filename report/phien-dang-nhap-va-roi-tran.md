# Trạng thái online, đăng nhập trùng và rời trận

## Quản lý người chơi

- **Trạng thái tài khoản** cho biết tài khoản được phép đăng nhập hay đang bị khóa. Đây là quyền truy cập, không phải trạng thái online.
- **Kết nối người chơi** hiển thị Online khi tài khoản có kết nối đang hoạt động ở cổng game hoặc WebSocket. Một tài khoản mở nhiều tab chỉ tính một người.
- Chỉ đăng nhập rồi đóng trang không được tính là đang online. Phiên quản trị được ghi riêng, không tính là kết nối người chơi.
- Khi đang mở mục Người chơi, admin tự tải lại dữ liệu mỗi 3 giây, giữ nguyên từ khóa và trang đang xem. Tạm dừng khi tab bị ẩn hoặc đang mở hộp thao tác.
- Khi mất mạng đột ngột, trạng thái thay đổi sau khi máy chủ phát hiện kết nối đã mất; WebSocket dùng Ping/Pong để phát hiện kết nối không còn phản hồi.

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

## Kiểm thử

- `scratch/test_player_lifecycle.ps1`: database và cổng riêng; kiểm tra trạng thái online, đăng nhập đồng thời, thay phiên giữa trận, rời/ngắt kết nối trong ba game, chặn vào lại, loại kết quả và hủy trận với bot.
- `node scratch/test_match_leave_guard.mjs`: cảnh báo, hủy thao tác rời, tránh hỏi hai lần và bỏ cảnh báo cũ sau khi phiên bị thay thế.
- `scratch/test_account_features.ps1`, `scratch/test_race.ps1`: hồi quy tài khoản, thông báo trực tiếp, Google OAuth mô phỏng và luật xếp hạng.
