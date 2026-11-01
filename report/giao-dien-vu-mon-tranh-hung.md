# Giao diện Vũ Môn Tranh Hùng

Tên hiển thị chung của dự án là **Vũ Môn Tranh Hùng**. Biểu tượng khiên vàng, cổng Vũ Môn và cá chép vươn lên dùng chung tại trang chủ, đăng nhập, quản trị và các trò chơi. File SVG dùng được cả làm logo và favicon, không phụ thuộc dịch vụ bên ngoài.

## Bộ giao diện dùng chung

- `public/assets/brand/vu-mon-crest.svg`: biểu tượng vector.
- `public/vm_theme.js`: gắn biểu tượng, tên thương hiệu và thanh điều hướng.
- `public/vm_theme.css`: bảng màu và kiểu hiển thị chung; được nạp sau CSS riêng của từng trang.

Giữ nguyên màu gốc của trang chủ, phòng ôn luyện và bảng xếp hạng trong `world_portal.css`, `study_rank.css`, `login_scene.css`. Các quy tắc màu chung trong `vm_theme.css` loại trừ `.vm-home`, nên không phủ bộ màu mới lên ba màn hình này. Tên và biểu tượng mới vẫn dùng chung.

Các trang còn lại dùng nền trời xanh nhạt `#edf8ff`, thẻ kem sáng `#fff9e9`, ô phụ xanh nhạt `#eaf8ff`, nút vàng `#f7dc86`–`#e7b74b`. Thanh điều hướng và sidebar admin dùng nền trắng xanh thay cho nâu tối. Trạng thái đúng/online dùng xanh, sai/cảnh báo dùng đỏ để giữ ý nghĩa của thông tin. Vòng quay dùng các mảng màu sáng với chữ đậm để đọc rõ.

Trang đăng nhập admin chỉ giữ một form căn giữa, không còn cột giới thiệu hay khung hướng dẫn chạy `setup-admin.bat`. Nền chuyển động vẫn hiển thị quanh form. Chữ mục quản lý admin là 15 px, nội dung bảng/biểu mẫu chủ yếu 14 px; sidebar rộng hơn để tên mục đọc rõ. Trên điện thoại, sidebar thu về các biểu tượng như trước.

Đường đua dùng chữ mô tả/nhãn 13–15 px, đáp án 16–17 px, câu hỏi 22–28 px và điểm rank 21 px. Chữ chính xanh đậm `#263f50`, chữ phụ `#4d6574`; vàng chủ yếu dùng làm nền nút và điểm nhấn thay vì chữ nhạt trên nền sáng. Màu chữ đúng/sai và trạng thái HP vẫn phân biệt riêng.

Áp dụng cho cả tám trang HTML: trang chủ, admin, Đường đua Kiến thức, Đấu trường 100, Chiếc nón kỳ diệu, Đấu trường sinh tử, đặt lại mật khẩu và xác nhận email. Các chế độ dùng chung trang, gồm đấu thường và đấu với máy, nhận cùng giao diện. Bảng biểu, bộ lọc, biểu mẫu, hộp thoại, câu hỏi, đáp án, ô chữ, thanh rank và màn hình kết quả dùng chung bộ màu.

Khi cần đổi bộ màu, sửa các biến `--vm-*` ở đầu `vm_theme.css`. Giữ các quy tắc trạng thái đúng/sai, máu thấp và đồng hồ sắp hết giờ. Không dùng CSS để thay đổi nội dung câu hỏi hay luật chơi.

## Kiểm tra

`node scratch/test_vm_theme_browser.mjs` kiểm tra trên Chrome desktop và điện thoại: đăng nhập, trang chủ, ôn luyện, bảng xếp hạng, câu hỏi và kết quả ba trò chơi thường, quản lý người chơi, form câu hỏi admin và hai trang tài khoản. Test so sánh trực tiếp màu nền, màu chữ, viền và bộ lọc của trang chủ/ôn luyện/bảng xếp hạng với CSS gốc khi tắt stylesheet chung, kiểm tra sidebar admin sáng, màu đáp án đúng/sai, cảnh báo HP thấp và tràn ngang trên điện thoại. Test mô phỏng API/WebSocket, không thay đổi cơ sở dữ liệu. Ảnh kiểm tra được lưu trong thư mục `scratch/portal-browser-vm-theme-*`.

`node scratch/test_rank_backup_browser.mjs` kiểm tra giao diện thanh rank và sao lưu quản trị.

Hai bài kiểm tra trình duyệt dùng `scratch/contrast_audit.mjs` để rà chữ trên nền đặc/gradient ở các màn hình ngoài trang chủ. Kết quả lưu trong `contrast-audit.json` cùng thư mục ảnh. Nội dung chữ thông thường được kiểm tra với tỷ lệ 4.5:1, chữ lớn với 3:1. Kiểm tra này không thay cho việc xem ảnh nền, trạng thái focus hay kiểm thử khả năng truy cập toàn diện; ảnh chụp desktop/mobile cũng được xem trực tiếp.

Đây là thay đổi tài nguyên giao diện tĩnh. Máy chủ đang chạy phục vụ file mới mà không cần khởi động lại. Nếu trình duyệt còn giữ giao diện cũ, nhấn **Ctrl + F5**.
