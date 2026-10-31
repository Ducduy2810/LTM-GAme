# Tài nguyên bản đồ người chơi

Các ảnh do chủ dự án cung cấp, được sao chép nguyên bản vào thư mục này; không thay đổi ảnh gốc.

| File trong dự án | Chức năng | Tên ảnh nguồn |
| --- | --- | --- |
| `sky-world.png` | Bản đồ nền tương tác sau đăng nhập | `2d_game_environment_background_art_vibrant_anime_casual_mobile_game_art_style..png` |
| `daily.png` | Thử thách hằng ngày | `Gemini_Generated_Image_pl5pytpl5pytpl5p.png` |
| `rank.png` | Đấu xếp hạng | `Golden Trophy Island, Bridge Removed-1.png` |
| `leaderboard.png` | Bảng xếp hạng | `Floating island cutout-3.png` |
| `pvp.png` | Đấu thường PvP | `Skyborne study island and floating planet-3.png` |
| `practice.png` | Phòng học / ôn luyện | `Floating shop island with waterfall.png` |
| `bot.png` | Chơi với máy | `Machinery Island, Pebble Path Removed-2.png` |

Nền sử dụng nguyên ảnh cuối, các vùng bấm SVG và bảng tên được đặt theo cùng hệ tọa độ 1376 × 768. Sáu ảnh đảo được dùng ở cửa sổ chức năng và thanh lối tắt trên điện thoại. File `daily.png` giữ nền trắng gốc; giao diện xử lý cách hòa màu bằng CSS, không chỉnh file ảnh.

Hiệu ứng phóng to đảo dùng lớp SVG hiển thị lại đúng vùng hình trên `sky-world.png`, với mặt nạ làm mềm viền. Chuyển động nổi/mây trôi được dựng bằng CSS. Không chỉnh sửa bitmap gốc hoặc tạo bản sao ảnh lớn mới; các lớp ảnh dùng chung tài nguyên đã được trình duyệt tải.
