# Phòng học, Bảng danh vọng và video đăng nhập

## Phòng học theo ảnh mẫu 1

Bấm đảo **Phòng học** trên bản đồ (hoặc mở `http://localhost:3000/#practice` sau khi đăng nhập). Giao diện toàn màn hình gồm thanh nâu ở trên, bảng thiết lập bên trái và khung câu hỏi màu kem bên phải.

1. Chọn chủ đề và một trong ba độ khó Dễ / Trung bình / Khó.
2. Bấm **Lưu và lấy câu hỏi mới** để áp dụng thiết lập cho câu tiếp theo.
3. Chọn một đáp án. Server chấm và trả về đáp án đúng, cùng lời giải nếu câu hỏi có lời giải.
4. Thống kê đã trả lời, số câu đúng, tỷ lệ chính xác và chuỗi đúng cập nhật theo kết quả được server xác nhận. Các thống kê này thuộc phiên đăng nhập, không cộng RP.

Chủ đề phía trên khung câu hỏi mô tả câu đã lấy; đổi lựa chọn ở bảng thiết lập chỉ áp dụng từ lần lấy câu hỏi kế tiếp. Trên điện thoại, hai khung xếp dọc và có thể cuộn để đọc/trả lời. Bấm **Bản đồ**, × hoặc Escape để quay lại.

## Bảng danh vọng theo ảnh mẫu 2

Bấm đảo **Bảng xếp hạng** hoặc mở `http://localhost:3000/#leaderboard`.

- Bục vinh danh sắp vị trí **2 – 1 – 3**, hiển thị tên, rank/danh hiệu và RP của người chơi thật.
- Danh sách bên phải tiếp tục từ hạng 4 đến hạng 100, có thể cuộn; trên điện thoại nằm dưới bục.
- Tổng RP dùng để thể hiện tiến trình qua các cấp, tương ứng `current_step × 100 + current_points`; RP trong cấp vẫn hiển thị riêng cạnh tên rank. Thứ tự hoàn toàn theo API server.
- Tự làm mới mỗi 30 giây khi đang xem và trang còn hiển thị; nút **Cập nhật bảng** cho phép tải ngay sau điều chỉnh của admin.
- Khi chưa đủ ba người, các vị trí trống được ghi rõ. Không thêm tên, điểm, xu hướng hay mùa giải giả.
- Thành tích Đấu Trường 100 cũ được giữ trong phần mở rộng phía dưới, tách biệt với rank mới.

## Video đăng nhập client và admin

Video nguồn do người dùng chỉ định: `giao dien dap nhap.mp4`, bản sao trong `public/assets/login/login-background.mp4` (2.392.651 byte). Dùng chung cho `http://localhost:3000/` và `http://localhost:3000/admin` trước khi đăng nhập.

Video tự phát lặp **không tiếng**, có lớp phủ để biểu mẫu dễ đọc, nút dừng/phát ở góc dưới. Video dừng khi đăng nhập xong hoặc chuyển trang sang nền. Với thiết lập giảm chuyển động của hệ điều hành, mặc định dùng ảnh tĩnh; có thể bấm phát nếu muốn. Khi tải video lỗi, ảnh dự phòng và biểu mẫu tiếp tục hoạt động.

Server phục vụ MP4 đúng `video/mp4`, hỗ trợ `HEAD` và tải từng phần (`Range` / `206`), không đọc toàn bộ video vào bộ nhớ cho mỗi yêu cầu. Không cần sửa database hoặc cấu hình tài khoản.

Các file chính: `public/study_rank.css`, `public/portal.js`, `public/index.html`, `public/login_scene.css`, `public/login_scene.js`, `public/admin.html`, `src/vn/game/server/HttpAndWebSocketServer.java`.

## Kiểm thử

Chạy `powershell -NoProfile -ExecutionPolicy Bypass -File scratch/test_portal.ps1 -StudyPreview` để có server kiểm thử với 12 người chơi xếp hạng giả **trong database riêng tại scratch**, không thêm vào dữ liệu thật. Dùng địa chỉ `PREVIEW_URL` được in ra cho:

```text
node scratch/test_study_rank_browser.mjs http://localhost:<cổng-kiểm-thử>
node scratch/test_login_video_http.mjs http://localhost:<cổng-kiểm-thử>
```

Bài trình duyệt kiểm tra cấu hình/chấm câu hỏi/thống kê, Top 3 và danh sách, cập nhật sau khi admin đổi rank, desktop/mobile, video chạy trên cả hai trang, dừng/phát, giảm chuyển động và video tải lỗi. Bài HTTP đối chiếu đúng byte video với file gốc trong dự án, kiểm tra MIME, các dạng Range, yêu cầu không hợp lệ và HEAD.

Sau khi cập nhật, chạy lại `run.bat` để biên dịch phần server và tải lại trang bằng **Ctrl + F5**.
