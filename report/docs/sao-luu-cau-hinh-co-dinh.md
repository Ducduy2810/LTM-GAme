# Sao lưu cấu hình tại thời điểm tạo

Rà soát ngày **01/11/2026**. Xem thêm [quản trị và áp dụng cấu hình](quan-tri-server.md).

- Admin chỉ tạo và tải xuống bản sao lưu từ server. Form và API tải file dữ liệu từ máy lên đã được gỡ bỏ. Các file cũ trên server vẫn có thể xem và tải xuống.
- Mỗi lần tạo bản sao dùng SQLite `VACUUM INTO` để lấy ảnh chụp dữ liệu nhất quán, gồm dữ liệu đã commit trong WAL.
- Bản mới ghi đầy đủ cấu hình hiệu lực của máy chủ, Đường đua/rank, thử thách ngày và thông báo, kể cả những thiết lập trước đó chỉ dùng mặc định. Việc bổ sung mặc định diễn ra trên bản sao tạm và đọc dữ liệu từ chính bản sao, không đọc lại cấu hình hiện tại rồi gắn nhầm vào bản cũ.
- Sau khi hoàn tất và đóng SQLite, bản sao tạm được chuyển thành tên file mới. Không ghi đè các bản đã có.
- Màn hình xem đặt **Tại thời điểm sao lưu · Cố định** trước **Máy chủ hiện tại · Đối chiếu**. Thay đổi máy chủ chỉ thay đổi cột đối chiếu.
- Ví dụ: tạo bản A lúc 10 câu, đổi lên 15 câu rồi tạo bản B. A giữ 10, B giữ 15. Đổi tiếp lên 12 cũng không sửa A hoặc B. Áp dụng A đưa cấu hình máy chủ về 10 và tự tạo bản an toàn giữ cấu hình 12 trước khi áp dụng.
- File được đọc ở chế độ chỉ đọc; dấu vân tay SHA-256 kiểm tra nguồn trước khi áp dụng. Xem hoặc áp dụng không chỉnh sửa file nguồn.
- Với bản cũ đầy đủ dữ liệu ứng dụng nhưng thiếu cấu hình mặc định đã ghi, giao diện ghi rõ phần được suy ra từ mặc định (rank là 10 câu), không lấy từ máy chủ hiện tại. Không thể suy ra một giá trị tùy chỉnh đã không được lưu trong file. File chỉ chứa một phần cấu hình vẫn chỉ áp dụng các nhóm được ghi trong đó.
- Bảo trì/thông báo đã hết hạn vẫn được hiển thị theo ảnh chụp gốc, kèm cảnh báo rằng khi áp dụng sẽ không bật lại thời hạn đã qua.
- Áp dụng cấu hình không khôi phục đè tài khoản, câu hỏi hoặc lịch sử trận. Phục hồi toàn bộ database cần dừng server và khôi phục tại máy vận hành.

## Áp dụng và hiển thị trên biểu mẫu

Tạo bản sao chỉ lưu lại dữ liệu tại thời điểm đó; muốn đưa cấu hình trở lại phải chọn **Áp dụng cấu hình**, xem bảng đối chiếu rồi xác nhận. Các nhóm máy chủ/game, Đường đua/thử thách ngày và thông báo được kiểm tra và ghi cùng một giao dịch, có bản an toàn trước khi ghi; nếu lỗi thì hoàn tác toàn bộ. Google/SMTP trong `.env` không thuộc cấu hình SQLite.

Biểu mẫu admin hiện có bản nhập riêng để không mất nội dung khi dữ liệu trực tiếp cập nhật mỗi 10 giây. Vì vậy, nếu một biểu mẫu đang hiện **Có thay đổi chưa lưu**, nó vẫn giữ những gì admin đang nhập sau khi áp dụng bản sao. Bấm **Bỏ thay đổi** tại đúng biểu mẫu đó để tải cấu hình mới nhất từ server. Biểu mẫu chưa chỉnh sẽ nhận cấu hình khi được tải lại. Đây là bảo vệ bản nhập, không phải thao tác áp dụng thất bại.

Chức năng tải file từ máy lên đã được gỡ khỏi giao diện/API hiện tại; tải xuống và áp dụng các bản đã có trên server vẫn được giữ. File nguồn được đọc, không bị sửa để chạy theo cấu hình mới của web admin.

Kiểm thử hồi quy: `scratch/test_rank_backup.ps1` xác nhận file 10 câu giữ nguyên byte sau khi cấu hình hiện tại đổi thành 15/12, kiểm tra bản an toàn và áp dụng ngược; `node scratch/test_backup_config_ui.mjs` kiểm tra hiển thị và yêu cầu áp dụng có CSRF.
