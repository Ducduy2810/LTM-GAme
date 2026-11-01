# Trung tâm quản trị Vũ Môn

Tài liệu được rà soát theo mã nguồn hiện tại ngày **01/11/2026**.

**Bổ sung:** mục **Bảng xếp hạng server** xem thứ hạng toàn server, tìm kiếm và phân trang cả ngoài Top 100. **Đường đua & Rank** quản lý ghép trận, thời gian, RP và thẻ; toàn bộ cấu hình/cấp lại lượt **Thử thách hằng ngày** nằm tại **Cấu hình & bảo trì**. Chi tiết tại [hướng dẫn đấu rank](duong-dua-xep-hang.md).

## Truy cập và tạo tài khoản

- Người chơi: `http://localhost:3000/`.
- Quản trị: `http://localhost:3000/admin`.
- Khi triển khai: cùng đường dẫn `/admin` trên tên miền của server Java, ví dụ `https://game.example.com/admin`.

Trang quản trị chạy trên cùng Java server và database với game, dùng tài khoản có `role='admin'` và phiên đăng nhập riêng. Tài khoản này không đăng nhập vào cổng chơi game. Tài khoản admin cũ có quyền trong database vẫn được giữ lại; đăng nhập tại `/admin` bằng mật khẩu hiện có. Để chơi game, dùng tài khoản người chơi khác.

Không có tên/mật khẩu quản trị mặc định. Không có chức năng đăng ký admin công khai.

Khi báo thông tin quản trị không hợp lệ, kiểm tra đang đăng nhập đúng `/admin`, tài khoản đã tồn tại trong database của server đang chạy và có quyền `admin`. Tên hoặc mật khẩu ghi trong hướng dẫn cũ không tự tạo tài khoản trong database. Nếu chưa có admin, dùng công cụ thiết lập bên dưới; nếu đã có nhưng quên mật khẩu, dùng lệnh khôi phục. Không dùng tài khoản người chơi hoặc đăng nhập Google để vào trang quản trị.

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
| Tổng quan | Số người chơi, người đang online, trận đang diễn ra, trận đã ghi nhận, câu hỏi, ô chữ, tài khoản bị khóa; các trận gần đây |
| Người chơi | Tìm tên/email, xem riêng trạng thái tài khoản và kết nối Online/Offline, xem lịch sử, khóa 1 giờ/24 giờ/7 ngày/đến khi mở thủ công, mở khóa, kết thúc toàn bộ phiên và kết nối |
| Phòng & trận trực tiếp | Xem thành viên và chủ phòng; gửi thông báo; kick thành viên; hủy phòng/trận có lý do |
| Ngân hàng câu hỏi | Thêm/sửa 4 lựa chọn, đáp án A–D, chủ đề, độ khó, lời giải, bộ rank; lọc theo chế độ/độ khó/bậc rank/trạng thái; xem phiên bản; nháp/xuất bản/ẩn |
| Ngân hàng ô chữ | Thêm/sửa đáp án, gợi ý, chủ đề và kiến thức bổ sung; chuẩn hóa đáp án thành A–Z và khoảng trắng |
| Chủ đề | Thêm/sửa tên hiển thị và bật/ẩn chủ đề cho câu hỏi hoặc ô chữ |
| Lịch sử trận | Lọc theo người chơi/game/trạng thái; xem người tham gia, kết quả và bản chụp nội dung đã dùng |
| Nhật ký | Tra cứu ai làm gì, với đối tượng nào, thời điểm và lý do; gồm cả lần kick của chủ phòng |
| Cấu hình | Bật/tắt bảo trì ngay và đặt giờ kết thúc, sửa thông báo, bật/tắt nhận trận mới cho từng game, lưu hoặc bỏ các thay đổi đang nhập |
| Sao lưu | Tạo bản sao SQLite nhất quán, xem danh sách, tải về, so sánh và áp dụng cấu hình từ bản sao lưu có trên server |
| Tài khoản | Đổi mật khẩu quản trị; kết thúc các phiên quản trị sau khi đổi |

Tổng quan, phòng trực tiếp, Đường đua, bảng xếp hạng, cấu hình và thông báo tự cập nhật mỗi 10 giây khi đang mở và không có hộp thao tác. Trang Người chơi cập nhật mỗi 3 giây. Các biểu mẫu có thay đổi chưa lưu được bảo vệ khỏi việc tải lại dữ liệu; gameplay vẫn dùng WebSocket.

Ô **Kết nối trực tiếp** đếm các tài khoản người chơi đã đăng nhập và đang có kết nối hoạt động, kể cả ở trang chủ hoặc phòng học. Server tổng hợp kết nối sự kiện toàn trang (SSE) và kết nối game (WebSocket), gộp theo tên tài khoản để mở nhiều tab không bị đếm nhiều lần. Phiên đăng nhập còn lưu nhưng không có kết nối hoạt động không được coi là đang online. Phiên admin tách riêng khỏi số người chơi.

Ô **Trận đang diễn ra** cộng các trận có trạng thái `ACTIVE` trong ba game cũ và Đường đua; phòng đang chờ chưa bắt đầu không được tính vào số này.

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

Người bị kick nhận hộp thoại **Bạn đã bị kick khỏi phòng**, kèm lý do và nút **Đã hiểu**. Admin hủy trận hoặc đưa người đang tìm trận ra khỏi hàng chờ cũng tạo thông báo rõ lý do: **Trận đấu đã bị hủy** hoặc **Lượt tìm trận đã bị hủy**. Thông báo không chỉ xuất hiện thoáng qua. Quy tắc bỏ cuộc, nối lại và ghép trận mới của Đường đua rank được mô tả riêng trong [hướng dẫn Đường đua](duong-dua-xep-hang.md).

Ẩn nút ở client là một phần giao diện; server cũng từ chối lệnh xóa khi đang thi đấu, lệnh tạm dừng của chủ phòng và lệnh kick không đúng quyền. Xóa phòng chờ được ghi nhật ký, không tạo kết quả thắng/thua.

## Nội dung và CSV

Lần nâng cấp đầu tiên, câu hỏi và ô chữ Java được chép vào database một lần. Sau đó database là nguồn nội dung chính; khởi động lại không ghi đè nội dung đã sửa. Dữ liệu Java giữ vai trò dữ liệu khởi tạo.

- `DRAFT`: quản trị được xem/sửa, chưa dùng trong game.
- `PUBLISHED`: được dùng cho lượt mới nếu chủ đề đang bật.
- `ARCHIVED`: ẩn khỏi lượt mới, vẫn giữ trong quản trị và lịch sử.

Lưu nội dung tạo một phiên bản mới. Nếu hai cửa sổ cùng sửa một phiên bản, thao tác lưu sau bị từ chối và cần tải lại. Câu hỏi/ô chữ đã được chọn cho lượt chơi được giữ bằng bản chụp; sửa ngân hàng không đổi đáp án của câu đã gửi. Riêng rank bốc từng câu khi mở lượt, nên thay đổi ngân hàng có thể tác động các lượt chưa mở. Ba game cũ lưu bản chụp trong cấu hình lịch sử; Đường đua lưu từng lượt trong `race_rounds`. Trận từ các bản cũ không có bản chụp thì không thể bổ sung ngược chính xác.

Ngân hàng câu hỏi kết hợp được các bộ lọc **Chế độ**, **Độ khó**, **Bậc rank**, **Trạng thái** và từ khóa. Các chế độ gồm Đấu Trường 100, Sinh tử, Phòng học, Đấu rank, Đường đua thường, chơi máy và thử thách ngày; Chiếc Nón dùng ngân hàng ô chữ riêng. Bộ lọc được thực hiện tại server trước giới hạn tối đa 500 kết quả. Lọc theo chế độ xác định phạm vi câu có thể dùng, không tự thay đổi trạng thái xuất bản của câu hỏi.

Trang ngân hàng có nút tải CSV mẫu, chọn tệp hoặc dán CSV, **Kiểm tra & xem trước**, rồi **Nhập dữ liệu**. Hỗ trợ CSV UTF-8/BOM, dấu phẩy, dấu ngoặc kép và xuống dòng trong ô đã trích dẫn; tối đa 500 dòng mỗi lần. Toàn bộ dữ liệu được kiểm tra trước khi ghi, ghi trong cùng transaction; một dòng sai sẽ không nhập bất kỳ dòng nào. Import tạo nội dung mới, không tự ghi đè theo tên.

CSV câu hỏi:

```csv
text,option_a,option_b,option_c,option_d,correct,topic,difficulty,explanation,status,usage,rank_tier
Cổng HTTP mặc định là bao nhiêu?,21,443,80,8080,C,NETWORKING,EASY,HTTP mặc định dùng cổng 80.,DRAFT,RACE,BRONZE
```

CSV ô chữ:

```csv
phrase,hint,topic,explanation,status
LAP TRINH MANG,Một môn học chuyên ngành,IT,Kiến thức về kết nối và trao đổi dữ liệu.,DRAFT
```

`correct` dùng A/B/C/D; `difficulty` dùng EASY/MEDIUM/HARD; `topic` phải tồn tại đúng loại. Bỏ cột `status` sẽ mặc định DRAFT. `usage` nhận LEGACY/RACE/BOTH, bỏ cột mặc định LEGACY; `rank_tier` để trống cho câu Chung hoặc nhận BRONZE/SILVER/GOLD/PLATINUM/DIAMOND. Câu dành cho rank phải dùng RACE, chủ đề NETWORKING, có lời giải và độ khó phù hợp với bậc. Excel `.xlsx` chưa được hỗ trợ trực tiếp; xuất thành CSV UTF-8 trước khi nhập.

## Bảo trì, sao lưu và dữ liệu

Trong **Cấu hình & bảo trì**, chọn **Theo số giờ** (có thể nhập số lẻ) hoặc **Đến ngày giờ cụ thể** theo giờ Việt Nam, nhập thông báo, rồi tích **Bật bảo trì ngay**. Thao tác bật/tắt được lưu và áp dụng ngay, có trạng thái đang xử lý/thành công/lỗi; không cần bấm Lưu cấu hình để bật/tắt. Khi đang bảo trì, sửa thời hạn/thông báo rồi bấm **Lưu cấu hình** để cập nhật. Server lưu thời điểm kết thúc vào database và tự mở lại khi đến hạn, kể cả sau khi khởi động lại. Admin có thể tắt bảo trì sớm.

Khi bảo trì bắt đầu, mọi phiên người chơi bị đăng xuất, hàng chờ và các trận đang chạy bị hủy; trận rank bị hủy không cộng/trừ RP hoặc tiêu thụ thẻ. Người chơi ở sảnh và trong game nhận thông báo kèm thời gian kết thúc. Đăng nhập bằng mật khẩu, Google, truy cập API người chơi hoặc kết nối game đều bị chặn trong thời gian này, với thông báo server đang bảo trì đến mốc admin đã đặt. Trang admin và đăng nhập admin vẫn hoạt động. Hết bảo trì, người chơi đăng nhập lại bằng phiên mới. Bảo trì đóng quyền truy cập người chơi trong khi tiến trình Java tiếp tục chạy để phục vụ admin và tự hết hạn.

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

## Áp dụng cấu hình từ file sao lưu

Trong **Sao lưu dữ liệu**, chọn một bản SQLite đang có trong danh sách rồi bấm **Áp dụng cấu hình**. Hộp thoại so sánh các thiết lập đang dùng và trong file: máy chủ/các game, Đường đua/rank/thử thách ngày, thông báo toàn server. Cột giá trị tại thời điểm sao lưu giữ nguyên để đối chiếu, không thay đổi theo cấu hình hiện tại. Chỉ các nhóm có trong file được cập nhật. Kiểm tra bảng so sánh rồi bấm **Áp dụng cấu hình** để lưu. Nếu đang có bản nhập chưa lưu ở một biểu mẫu, dùng **Bỏ thay đổi** tại biểu mẫu đó để lấy cấu hình vừa áp dụng.

Việc tạo hoặc lưu trữ một bản sao chưa áp dụng cấu hình vào server. Trong chuỗi cập nhật đã có bước bổ sung tải file từ máy lên; **mã nguồn hiện tại đã gỡ chức năng tải lên trên web**, endpoint `/api/admin/backup-upload` trả 404. Các bản sao đã có trên server vẫn có thể xem, tải về và áp dụng cấu hình. Khôi phục toàn bộ database vẫn thực hiện tại máy chủ theo hướng dẫn vận hành.

Hệ thống kiểm tra toàn bộ cấu hình và dấu kiểm tra của file để tránh áp dụng file đã đổi sau bước xem trước, tự tạo bản sao lưu dữ liệu trước khi áp dụng và cập nhật các nhóm trong một lần ghi; gặp lỗi sẽ hoàn tác toàn bộ thay đổi. Tài khoản, câu hỏi, lịch sử trận và dữ liệu rank cá nhân được giữ nguyên. Cấu hình Google/SMTP trong `.env` không nằm trong file SQLite. Thời hạn bảo trì và thông báo được giữ theo mốc thời gian gốc; những mục đã hết hạn được tắt. Nếu file bật bảo trì còn hiệu lực, phần xem trước sẽ báo rõ việc đăng xuất người chơi và hủy trận.

## Quản lý bộ câu hỏi từng bậc rank

Ngân hàng câu hỏi có bộ lọc **Bậc rank** và trường **Bộ câu hỏi bậc rank** khi thêm/sửa. Chọn Đồng/Bạc/Vàng/Bạch Kim/Kim Cương để dành câu cho đấu rank; hệ thống đặt phạm vi Chỉ Đường đua và chủ đề Lập trình mạng. Để Chung cho ngân hàng theo phạm vi sử dụng thông thường. File CSV mẫu có cột `usage` và `rank_tier`. Mỗi bậc được cấp 15 câu khởi tạo; admin có thể bổ sung, sửa hoặc ẩn câu. Chi tiết cơ cấu độ khó và ghép trận theo bậc xem [hướng dẫn Đường đua](duong-dua-xep-hang.md).

Cao Thủ dùng bộ Khó của Kim Cương theo luật mở rộng rank hiện tại, không có bộ câu thứ sáu; việc ghép Cao Thủ vẫn tách riêng khỏi Kim Cương.

Đấu rank bốc câu theo từng lượt từ ngân hàng hiện tại, không chọn sẵn cả bộ lúc tạo trận. Câu đã gửi được giữ nguyên để chấm điểm/nối lại; các lượt chưa mở có thể lấy câu admin vừa thêm hoặc chỉnh sửa. Nếu ẩn quá nhiều câu làm thiếu số lượng/độ khó còn lại, trận rank được hủy không tính RP hoặc tiêu thẻ.

## Giữ cấu hình đang nhập khi cập nhật trực tiếp

Các biểu mẫu Cấu hình máy chủ/các game, Đường đua & Rank, trạng thái dịch vụ Đường đua, thử thách ngày và thông báo có bản nhập riêng. Khi sửa sẽ hiện **Có thay đổi chưa lưu** và nút **Bỏ thay đổi**. Dữ liệu phòng/hàng chờ/người chơi tiếp tục cập nhật mỗi 10 giây; các ô đang sửa được giữ nguyên, kể cả khi chuyển sang ô hoặc mục admin khác.

Bấm nút lưu của từng biểu mẫu để áp dụng. Lưu lỗi giữ nguyên nội dung; nếu tiếp tục gõ trong lúc đang lưu, phần chỉnh sửa mới cũng được giữ và vẫn được đánh dấu chưa lưu. **Bỏ thay đổi** tải cấu hình mới nhất từ server cho đúng biểu mẫu đó; các bản nhập ở biểu mẫu khác được giữ. Phản hồi tải dữ liệu cũ hoặc phản hồi đến sau khi có chỉnh sửa mới không ghi đè bản nhập. Nút Bật/tắt bảo trì vẫn áp dụng ngay theo quy tắc riêng, đồng thời giữ các thay đổi mặc định game chưa lưu. Số câu đấu rank cho phép 10–15; giá trị ngoài giới hạn được báo khi lưu, không tự đổi về giá trị cũ.

Nguyên nhân lỗi trước đây là chu kỳ cập nhật trực tiếp dựng lại biểu mẫu từ dữ liệu đã lưu, làm ô vừa nhập trở về giá trị cũ. Hiện dữ liệu đã lưu và bản đang nhập được quản lý riêng cho năm biểu mẫu, kèm phiên bản chỉnh sửa để bỏ qua phản hồi đến trễ. Chuyển từ ô số câu sang ô giây/câu không lưu tự động và không làm mất nội dung. **Bỏ thay đổi** thất bại hoặc có chỉnh sửa mới trong lúc đang tải cũng giữ nguyên bản nhập. Dữ liệu này được giữ khi chuyển mục trong cùng trang admin; tải lại toàn bộ trang không phải cơ chế lưu bản nháp lâu dài.

Các bài kiểm tra giao diện liên quan nằm tại `scratch/test_admin_drafts_ui.mjs`, `scratch/test_question_filters_ui.mjs`, `scratch/test_rank_question_editor_ui.mjs` và `scratch/test_backup_config_ui.mjs`. Bộ bản nhập cấu hình đã kiểm tra 39 trường hợp, gồm đổi ô, cập nhật định kỳ, phản hồi trễ, lưu lỗi, nhập tiếp trong lúc lưu, bỏ thay đổi và tính độc lập giữa các biểu mẫu.
