# Giao diện người chơi: Quần đảo Vũ Môn

Mở http://localhost:3000/ và đăng nhập. Trang chủ dùng ảnh quần đảo bạn cung cấp làm nền toàn màn hình; các đảo và bảng tên là điểm bấm để chọn chế độ. Các mục mở trong cửa sổ trên bản đồ, sau đó dẫn vào phòng chơi hiện có.

| Ảnh | Vị trí / chế độ |
| --- | --- |
| 1 · Đảo xúc xắc | Thử thách hằng ngày · phía trên phải |
| 2 · Đảo tượng vàng | Đấu xếp hạng · phía dưới phải |
| 3 · Đảo cúp có cánh | Bảng xếp hạng · phía dưới giữa |
| 4 · Đảo mái vòm / hành tinh | Đấu thường · chính giữa |
| 5 · Đảo cửa hàng / sách | Phòng học, ôn luyện · phía trên trái |
| 6 · Đảo máy móc | Đấu với máy · phía dưới trái |
| 7 · Toàn cảnh quần đảo | Nền trang chủ sau đăng nhập |

Ảnh được sao chép vào `public/assets/world/`, giữ nguyên file gốc. Sáu ảnh đảo riêng được dùng trong cửa sổ chế độ và thanh chuyển nhanh trên điện thoại. Bản đồ dùng vùng bấm SVG theo từng đảo để giữ vị trí tương tác khi thay đổi kích thước màn hình.

- **Góc trên phải:** tên người chơi, rank hiện tại, menu hồ sơ, lịch sử, quản trị nếu có quyền và đăng xuất.
- **Thanh dưới:** hồ sơ, lịch sử, Góc giải trí chứa đủ ba game cũ và bật/tắt toàn màn hình nếu trình duyệt hỗ trợ.
- **Trong cửa sổ:** bấm “Bản đồ”, nút ×, vùng ngoài cửa sổ hoặc phím Escape để quay lại. Các đảo hỗ trợ phím Tab và Enter.
- **Điện thoại:** vuốt ngang để xem các đảo; sáu biểu tượng phía trên thanh cá nhân mở nhanh đúng chế độ. Cửa sổ có thể cuộn nội dung trên màn hình nhỏ.
- **Thông báo:** dòng chữ chạy của admin nằm trên cùng; thông báo lý do khóa/ngắt phiên tiếp tục hiển thị khi đang mở cửa sổ chế độ.

Trang chủ có chuyển động nổi nhẹ của toàn quần đảo, mây trôi phía trước và hiệu ứng chiều sâu nhẹ theo vị trí chuột. Rê chuột vào một trong sáu đảo hoặc bảng tên sẽ phóng to phần hình của đảo, đồng thời làm sáng bảng tên; rời chuột sẽ thu về. Vùng bấm và bảng tên đi cùng chuyển động bản đồ. Dùng Tab để chọn đảo cũng hiện hiệu ứng tương ứng.

Nút **Dừng chuyển động** (biểu tượng 〰 trên điện thoại) tắt chuyển động nền; bấm lại để bật. Lựa chọn được lưu trên trình duyệt. Khi mở cửa sổ chế độ/thông báo, đăng xuất hoặc chuyển sang tab khác, chuyển động nền tự tạm dừng. Thiết lập giảm chuyển động của hệ điều hành được ưu tiên. Trên điện thoại vẫn vuốt ngang và chạm để mở chế độ, không cần rê chuột.

Hiệu ứng dùng CSS/SVG trên ảnh nền hiện có, giữ nguyên các file ảnh gốc; không cần tải thêm video hoặc GIF cho trang chủ. Video nền đăng nhập của client/admin tiếp tục là phần riêng.

Nút, bảng tên và cửa sổ dùng màu xanh trời, xanh đậm, kem và viền vàng. Không thay đổi dữ liệu, luật rank, phân quyền admin hoặc luồng chơi. Đường đua thường vẫn có tìm trận và phòng riêng; luyện bot và thử thách ngày có lối vào riêng. Ba game cũ tiếp tục không tính RP.

Các file giao diện chính: `public/index.html`, `public/portal.js`, `public/world_portal.css`; hiệu ứng trang chủ nằm ở `public/world_motion.js` và `public/world_motion.css`.

## Kiểm tra

- `node --check public/portal.js`
- `node scratch/test_registration_ui.mjs`
- `powershell -NoProfile -ExecutionPolicy Bypass -File scratch/test_portal.ps1 -Preview`: dùng database kiểm thử riêng; ghi lại `PREVIEW_URL` được in ra.
- `node scratch/test_world_portal_browser.mjs http://localhost:<cổng-kiểm-thử>`: Chrome kiểm tra sáu vùng bấm thật, đúng ảnh từng mục, phòng học, hồ sơ, lịch sử, ba game cũ, bàn phím, bố cục điện thoại/ngang, thông báo admin và lý do ngắt phiên.

Sau khi cập nhật source, tải lại bằng **Ctrl + F5**. Giao diện tĩnh được server nạp từ thư mục `public`; không cần đổi database.
