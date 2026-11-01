# Đường đua Kiến thức · Rank, đấu thường và thử thách ngày

Tài liệu được rà soát theo mã nguồn hiện tại ngày **01/11/2026**. Hành vi ghép rank, rời trận, bảo trì và chọn câu bên dưới là quy tắc hiện hành; các kết quả kiểm thử có ngày cũ được giữ như ghi nhận của từng đợt.

## Mở chế độ

Chạy `run.bat`, đăng nhập tài khoản người chơi tại `http://localhost:3000`.

- **Đấu xếp hạng** → Đường đua rank (`/race.html?mode=ranked`).
- **Đấu thường PvP** → chọn thẻ **Đường đua Kiến thức** (`/race.html?mode=casual`), có **Tìm trận đấu thường** để ghép tự động với người chơi khác, bên cạnh biểu mẫu tạo trận/tham gia bằng mã. Không có lựa chọn rank hoặc luyện máy trong trang này. Phòng riêng đấu thường mới không có bot; trận ghép tự động điền bot sau thời gian chờ cấu hình (mặc định 60 giây), giống rank, nhưng không tính RP hoặc dùng thẻ. Hàng chờ đấu thường và rank tách riêng.
- **Chơi với máy** → chọn thẻ **Đường đua Kiến thức** bên cạnh các game khác (`/race.html?mode=bot`), mở biểu mẫu riêng, mặc định 3 bot, chọn 1–3 bot và bấm bắt đầu trong phòng chờ.
- **Thử thách hằng ngày** là một mục riêng trong thanh **Khám phá thế giới** (`/#daily`), có trang chơi riêng (`/race.html?mode=daily`), lượt riêng, không tính RP. Đã bỏ lối vào daily trong mục đấu rank.

Mỗi địa chỉ mở đúng giao diện của chế độ được chọn, không còn thanh tab chuyển qua rank/đấu thường/bot/daily trong trang Đường đua. Rank và Top 100 chỉ xuất hiện trong trang rank; lịch sử tại mỗi trang được lọc theo chế độ. Để đổi chế độ, quay lại đúng mục ở trang chủ. Nếu tài khoản còn hàng chờ/trận của chế độ khác, trang hiển thị liên kết tiếp tục trận đó và không tự chiếm phiên của tab đang chơi. Trận đã hoàn thành ở chế độ khác không làm thay thế biểu mẫu tạo trận vừa mở.

Đợt tách giao diện ngày 30/10/2026 đã đạt 169 kiểm tra Đường đua và 35 kiểm tra portal, cùng Chrome desktop/mobile. Có kiểm tra trang đấu thường tạo phòng không bot, trang luyện máy tạo đúng số bot đã chọn, daily không xuất hiện trong rank và mở chế độ khác không chiếm phiên đang chơi.

Ba game Arena 100, Chiếc Nón và Sinh tử vẫn giữ nguyên, không đóng góp RP. Đường đua dùng luật và lịch sử riêng, xuất hiện thêm trong mục lịch sử ở trang chủ. Bảng xếp hạng cũ vẫn chỉ để tham khảo.

## Luật mặc định đã triển khai

Chỉ ghép người chơi **cùng bậc rank lớn**: Đồng với Đồng, Bạc với Bạc, Vàng với Vàng, Bạch Kim với Bạch Kim, Kim Cương với Kim Cương và Cao Thủ với Cao Thủ. Ba cấp nhỏ trong cùng bậc được ghép với nhau. Trong bậc đó, ưu tiên RP gần nhau và mở rộng khoảng ghép theo thời gian; không mở rộng sang bậc khác. Đủ 4 người thật thì bắt đầu ngay; sau **60 giây** sẽ điền bot vào các chỗ thiếu. Admin có thể đặt mục tiêu 2–4 người. Bot được ghi rõ trong giao diện và tham gia thứ hạng trận rank theo yêu cầu này. Xếp hạng cá nhân và Top 100 chỉ ghi tài khoản thật.

Rank mặc định **10 câu, 15 giây/câu**; admin có thể chọn 10–15 câu. Mọi người nhận cùng câu hỏi và hạn trả lời. Đáp án chỉ được chốt một lần với token riêng của từng câu. Server dùng thứ tự nhận câu trả lời; không nhận thời gian, điểm, kết quả thắng/thua từ client. Đúng nhanh nhất +3 điểm, các đáp án đúng tiếp theo +2, sai/hết giờ 0. Điểm và lời giải công bố cùng lúc khi cả phòng đã trả lời hoặc hết thời gian, tránh để người chưa trả lời suy ra đáp án từ điểm.

| Vị trí | RP mặc định |
| --- | --- |
| Hạng 1 | +25 |
| Hạng 2, trận 3–4 người/bot | +10 |
| Hạng 2, trận 2 người/bot | 0 |
| Hạng 3 | −10 |
| Hạng 4 | −15 |

Hòa điểm: đồng hạng, chia trung bình RP của các vị trí đang hòa; làm tròn số nguyên theo hướng có lợi cho người chơi (17,5 → 18; −12,5 → −12). Không cộng RP dương nếu người chơi rời trận hoặc không đúng câu nào, kể cả khi mọi người cùng 0 điểm. Rời trận hoặc bị kick sẽ được ghi nhận bỏ cuộc, xếp sau người ở lại và chặn quay lại trận cũ. Tài khoản có thể ghép trận mới ngay; kết quả/RP của trận cũ vẫn được chốt riêng khi trận đó kết thúc, không xóa chỗ hoặc gửi trạng thái đè lên trận mới. Mất kết nối đột ngột vẫn giữ chỗ để nối lại trận chưa kết thúc; không tạo thêm trận song song khi chưa rời trận hiện tại.

### Về trang chủ, bỏ cuộc và kết nối lại

Khi đang trả lời hoặc xem kết quả một câu trong trận rank, bấm logo/liên kết về trang chủ hoặc nút rời trận sẽ hỏi: **“Bạn đang trong trận xếp hạng quan trọng, bạn vẫn rời phòng chứ ?”**. Hủy xác nhận giữ nguyên trận. Đồng ý gửi yêu cầu rời phòng; liên kết về trang chủ chỉ chuyển trang sau khi server xác nhận đã rời đúng trận. Trình duyệt cũng cảnh báo khi đóng hoặc tải lại trang trong trận theo khả năng hỗ trợ của trình duyệt.

| Tình huống trong trận rank | Cách xử lý |
| --- | --- |
| Chủ động bấm rời trận hoặc xác nhận về trang chủ | Ghi bỏ cuộc/tính thua, không được vào lại trận cũ; RP được chốt khi trận cũ kết thúc |
| Bị kick | Hiện thông báo kèm lý do, ghi bỏ cuộc và chặn quay lại trận cũ |
| Mất mạng hoặc đứt kết nối đột ngột | Giữ tư cách tham gia, cho nối lại nếu trận chưa kết thúc; đồng hồ và câu hỏi trên server vẫn tiếp tục |
| Admin hủy trận hoặc server bật bảo trì | Hủy trận, không cộng/trừ RP hoặc tiêu thẻ |

Lỗi đã sửa: người đã bỏ cuộc vẫn bị báo đang ở phòng khác khi tìm rank mới. Dữ liệu tham gia được giải phóng theo đúng trận đã rời; thao tác chốt/hủy trận cũ không xóa tư cách tham gia trận mới. Thẻ bảo vệ đã chọn nhưng đang chờ chốt ở trận cũ được giữ riêng, tránh dùng trùng khi ghép trận mới. Quy tắc giữ chỗ khi mất mạng ở bảng này áp dụng cho rank; đấu thường và luyện máy có cơ chế rời trận riêng.

### Thông báo và giao diện sau trận

Người bị kick nhận hộp thoại **Bạn đã bị kick khỏi phòng** và lý do. Khi admin hủy trận hoặc hủy lượt tìm trận của một tài khoản, người chơi nhận **Trận đấu đã bị hủy** hoặc **Lượt tìm trận đã bị hủy**, kèm lý do và nút **Đã hiểu**. Một sự kiện hủy trận không mở lặp nhiều thông báo giống nhau.

Sau trận rank có thể xem ôn tập, lịch sử đấu và Top 100. Khi bắt đầu tìm trận rank mới, vào phòng chờ hoặc đang thi đấu, các phần này được ẩn; nội dung ôn tập của trận trước và bộ lọc ôn tập được xóa khỏi giao diện. Phản hồi ôn tập cũ đến trễ cũng không mở lại bảng giữa trận. Khi kết thúc hoặc về sảnh, lịch sử và Top 100 được hiển thị lại. Đây là dọn giao diện, không xóa lịch sử hoặc kết quả trong database.

Rank bắt đầu **Đồng 3, 0 RP**. Các bậc: Đồng → Bạc → Vàng → Bạch Kim → Kim Cương, mỗi bậc 3 → 2 → 1. Đủ 100 RP thì tự lên cấp, giữ điểm dư. Khi vượt sang **bậc chính mới** nhận thêm đệm 20 RP: Đồng 1 ở 95 RP thắng +25 → Bạc 3 ở 40 RP (20 dư + 20 đệm). Lên cấp trong cùng bậc không nhận thêm đệm. Đủ 100 RP ở Kim Cương 1 sẽ lên **Cao Thủ**, không chia cấp và không áp trần RP theo luật chơi. Điểm được lưu bằng số nguyên 64 bit. Ví dụ Kim Cương 1 có 99 RP thắng +25 sẽ thành Cao Thủ 44 RP, gồm 20 RP đệm. Cao Thủ dùng bộ câu hỏi Khó của Kim Cương, nhưng ghép cùng nhóm Cao Thủ theo mức RP.

Thua từ RP dương chỉ giảm đến 0. Chỉ thua tiếp khi đã ở 0 mới tụt một cấp và nhận 70 RP ở cấp đó; Cao Thủ ở 0 RP thua sẽ xuống Kim Cương 1, 70 RP. Đồng 3 là đáy. Rank cao nhất được giữ khi tụt rank. **Top 10 Cao Thủ có điểm cao nhất là Huyền Thoại**; những Cao Thủ còn lại giữ bậc Cao Thủ, kể cả ngoài Top 100. Danh hiệu hiện tại được tính lại theo thứ tự điểm, không được cấp cho Kim Cương 1. Bảng server sắp theo tổng tiến trình `cấp × 100 + RP`, rồi số trận thắng, rồi tên tài khoản; chỉ gồm người đã có trận rank, không bị khóa và không phải admin. Danh hiệu cao nhất kiếm được khi chốt trận được lưu riêng. Điểm dư Kim Cương 1 trong dữ liệu cũ được chuyển sang Cao Thủ và giữ nguyên tổng tiến trình.

Thanh RP ở trang đấu rank, kết quả trận và hồ sơ hiển thị số điểm cùng hiệu ứng tăng/giảm. Với Cao Thủ, thanh biểu diễn tiến độ đến mốc 100 RP tiếp theo, không phải giới hạn điểm của bậc. Tùy chọn giảm chuyển động của thiết bị được tôn trọng. Một kết quả trận chỉ phát hiệu ứng một lần khi được hiển thị.

## Thử thách ngày và thẻ

Mỗi ngày theo múi giờ **Asia/Ho_Chi_Minh**, một lượt, 10 câu **HARD**, mặc định **7 giây/câu**. Bắt đầu là dùng lượt; không cấp lại chỉ vì tải lại trang hoặc rời trận. Đúng ít nhất 8/10 và hoàn thành trận nhận 1 thẻ bảo vệ + 1 thẻ x2. Không tăng/giảm RP. Mốc ngày mới là 00:00 Việt Nam, không lấy ngày do client gửi.

Trước khi ghép rank, người chơi chủ động bật thẻ đang có. Thẻ bảo vệ chỉ tiêu khi RP gốc âm và đưa thay đổi RP về 0. Thẻ x2 chỉ tiêu khi RP dương và nhân đôi mức nhận. Có thể bật cả hai nhưng một kết quả chỉ tiêu loại phù hợp. Trận hòa 0 RP, đấu thường, luyện máy và trận bị admin hủy không dùng thẻ. Thẻ bảo vệ đã chọn ở trận bỏ cuộc chưa chốt được giữ cho trận cũ; số thẻ khả dụng để ghép trận mới đã trừ phần này. Nếu trận cũ không cần dùng thẻ hoặc bị hủy, thẻ được mở lại khi chốt/hủy trận. Thưởng và tiêu thẻ được ghi cùng giao dịch với kết quả, có khóa duy nhất để không nhận lại do gửi lặp hoặc tải lại trang.

## Admin quản lý

Đăng nhập `/admin` bằng tài khoản có quyền quản trị trong database của server đang chạy. Không có thông tin đăng nhập mặc định; xem [tạo/khôi phục tài khoản admin](quan-tri-server.md). Các phần quản trị được tách như sau:

**Bảng xếp hạng server:** xem toàn bộ người chơi đã hoàn thành trận rank, không giới hạn ở Top 100. Phân trang 50 người, tìm theo tên tài khoản, lọc người đang xếp hạng/tài khoản khóa/tất cả. Có thứ hạng chính thức toàn server, rank và RP hiện tại, danh hiệu, rank cao nhất, số trận/thắng và trạng thái. Tìm kiếm giữ nguyên thứ hạng toàn server. Tài khoản khóa có thể tra cứu nhưng không có thứ hạng chính thức; tài khoản admin và người chưa có trận rank không nằm trong bảng. Bảng người chơi công khai vẫn giữ Top 100 theo cùng cách sắp xếp.

**Cấu hình & bảo trì:** công tắc chung cho Đường đua và thử thách, cấu hình bảo trì/game cũ, cùng toàn bộ cấu hình **Thử thách hằng ngày**. Tại đây admin bật/tắt daily, chỉnh giây/câu, mốc câu đúng, số thẻ bảo vệ/x2 được thưởng, hoặc cấp lại lượt hôm nay cho một tài khoản có lý do. Không còn các trường daily hoặc lựa chọn cấp lại lượt trong phần rank. Các biểu mẫu lưu riêng phần được chỉnh, tránh lưu rank ghi đè daily và ngược lại. Cấu hình daily có nhật ký riêng `DAILY_SETTINGS`.

**Đường đua & Rank:**

- Bật/tắt rank; cấu hình số câu, giây/câu rank/ghép thường, thời gian chờ bot và số người mục tiêu.
- Chỉnh RP từng vị trí (kể cả hạng 2 của trận 2 người), điểm đệm và RP sau tụt cấp.
- Xem hàng chờ, đưa người chơi ra khỏi hàng chờ có lý do.
- Xem phòng/trận đang chạy, gửi thông báo phòng, kick người chơi (kể cả chủ phòng) hoặc hủy trận có lý do. Hủy không thay đổi RP/thẻ. Khóa/ngắt phiên ở mục Người chơi cũng được áp dụng vào Đường đua.
- Đặt rank/RP hiện tại và số thẻ. Những chỉnh sửa này cần lý do và lưu nhật ký. Không sửa RP/thẻ/lượt khi người chơi còn trong hàng chờ hoặc phòng; hủy trận hoặc đưa ra trước. Hạ rank hiện tại không xóa thành tích cao nhất. Đặt rank không tự tạo số trận thắng giả.
- Xem 50 kết quả gần nhất cùng câu hỏi, đáp án đã chọn và lời giải của người chơi; nhật ký chung lưu các thao tác admin.

Luật trận như số câu, thời gian, RP và bậc đề được chụp khi tạo trận. Chỉnh các luật này chỉ ảnh hưởng trận mới; không đổi deadline hoặc mức thưởng của trận đang chạy. Nội dung câu hỏi rank được bốc riêng khi mở từng lượt theo ngân hàng hiện tại, như mô tả bên dưới. **Bảo trì chung đăng xuất người chơi, hủy hàng chờ và các trận đang chạy**, không tính RP hoặc tiêu thẻ; admin vẫn truy cập được. Công tắc nhận trận mới của từng dịch vụ chỉ chặn trận mới. Chủ phòng đấu thường được kick, xóa **phòng chờ**, không có nút tạm dừng và không được xóa trận đang thi đấu. Phòng rank ghép tự động không có chủ phòng được can thiệp kết quả.

Biểu mẫu Đường đua, trạng thái dịch vụ và thử thách ngày có bản nhập độc lập: hiện **Có thay đổi chưa lưu**, chỉ áp dụng khi bấm lưu, hỗ trợ **Bỏ thay đổi**, giữ nội dung nếu lưu lỗi và chặn phản hồi tải cũ ghi đè. Hàng chờ/phòng/người chơi vẫn được cập nhật mỗi 10 giây. Số câu rank vẫn giới hạn **10–15**; sửa lỗi mất nội dung không mở rộng giới hạn thành 20 câu.

Ngân hàng câu hỏi → **Phạm vi sử dụng**:

- `LEGACY`: ba game ban đầu và phòng học.
- `RACE`: chỉ Đường đua.
- `BOTH`: dùng chung.

Đường đua chỉ chọn câu NETWORKING đang PUBLISHED, chủ đề còn bật và có lời giải. CSV nhận thêm cột tùy chọn `usage` với các giá trị trên; thiếu cột mặc định LEGACY. Bản cập nhật bổ sung **32 câu Lập trình mạng** (10 Dễ, 10 Trung bình, 12 Khó) có giải thích, chỉ cho Đường đua. Admin có thể sửa/ẩn hoặc bổ sung; các câu đã mở lượt trong trận được giữ bằng bản chụp để chấm điểm và ôn tập. Nếu không đủ câu phù hợp thì từ chối bắt đầu và báo rõ, không lấy câu sai chủ đề/độ khó hay trừ lượt trước.

Đây là cùng hệ thống lưu câu hỏi nhưng có phân vùng sử dụng: câu Chung thuộc RACE/BOTH dùng cho Đường đua thường/chơi máy/thử thách ngày; rank chỉ dùng bộ riêng có `rank_tier` đúng bậc. Câu LEGACY/BOTH thuộc phần Chung phục vụ game cũ và phòng học. Chiếc Nón sử dụng bảng ô chữ riêng. Daily lấy câu HARD; việc chọn bộ rank còn phải đáp ứng cơ cấu độ khó của từng bậc.

Nội dung mạng tham chiếu các tiêu chuẩn gốc: [TCP RFC 9293](https://www.rfc-editor.org/rfc/rfc9293), [UDP RFC 768](https://www.rfc-editor.org/rfc/rfc768), [HTTP RFC 9110](https://www.rfc-editor.org/rfc/rfc9110), [WebSocket RFC 6455](https://www.rfc-editor.org/rfc/rfc6455).

## Database và vận hành

Giữ SQLite, tài khoản và dữ liệu cũ. Tự tạo bản sao nhất quán `data/backups/before-race-*.db` trước khi thêm bảng rank/trận mới; xem/tải được trong Sao lưu admin. Bổ sung:

- `race_matches`, `race_players`: chế độ, cấu hình đã chụp, trạng thái và kết quả.
- `race_rounds`, `race_answers`: câu hỏi/lời giải tại thời điểm thi đấu, deadline, đáp án, thứ tự nhận và điểm.
- `race_participation`: chặn một tài khoản tham gia đồng thời nhiều phòng Đường đua.
- `rank_ledger`: RP trước/sau, thưởng gốc, loại thẻ; duy nhất theo trận/người chơi.
- `player_cards`, `daily_attempts`: tồn thẻ, lượt và thưởng theo ngày.
- `player_ranks`: mở rộng cấp hiện tại/cao nhất, số trận/thắng và danh hiệu cao nhất.
- `game_questions.usage`: phạm vi sử dụng câu hỏi; các câu cũ mặc định LEGACY.

Không chuyển điểm Arena/Chiếc Nón/Sinh tử sang RP. Hồ sơ trước đây “Chưa xếp hạng” chuyển thành Đồng 3. Không có cơ chế mùa giải/reset toàn bảng trong bản này.

Một tiến trình Java chịu trách nhiệm hàng chờ và nhịp trận. Khi deploy cần giữ volume `data/` và kết nối WebSocket/SSE qua proxy. Bản này chưa hỗ trợ chia một hàng chờ qua nhiều tiến trình/server. Mất kết nối trong rank cho phép tải lại trang để tiếp tục trận hiện tại; thời gian server vẫn chạy, các câu đã hết hạn tính 0. Hàng chờ giữ chỗ mất mạng 10 giây, phòng chờ giữ thành viên mất mạng 30 giây. Khi server dừng/khởi động lại, trận chưa hoàn thành bị hủy/ghi INTERRUPTED, không tính RP/thẻ; daily đã bắt đầu vẫn dùng lượt. Khôi phục trận chỉ chạy sau khi bind cổng thành công để chạy nhầm server thứ hai không làm hỏng trận của tiến trình đang phục vụ.

## Kiểm thử

Các số lượng kiểm tra ghi ở các đoạn theo đợt bên dưới là kết quả lịch sử, không phải tổng của phiên bản mới nhất. Các bài giao diện bổ sung cho chuỗi thay đổi này gồm `scratch/test_rank_post_match_ui.mjs` (ẩn/dọn nội dung sau trận), `scratch/test_rank_question_editor_ui.mjs` (gán bộ rank), `scratch/test_question_filters_ui.mjs` (bộ lọc) và `scratch/test_match_leave_guard.mjs` (xác nhận rời trận). Bộ `test_race.ps1` còn kiểm tra bỏ cuộc rồi ghép lại, xử lý trận cũ độc lập, ghép cùng bậc và bốc câu theo từng lượt.

`powershell -NoProfile -ExecutionPolicy Bypass -File scratch/test_race.ps1` tạo database riêng và chạy các kiểm tra RP, điểm đệm/tụt cấp, quyền phòng, bot sau 60 giây, thời gian rank, daily, thẻ, kết nối lại, gửi lặp và giao thức WebSocket thật. Có kiểm tra thay đổi cấu hình giữa trận, rời trận không nhận RP dương, và không chạy khôi phục khi cổng đang bị chiếm.

Thêm `-Preview` để giữ server thử và lấy `PREVIEW_URL`, sau đó chạy `node scratch/test_race_browser.mjs <PREVIEW_URL>`. Script Chrome dùng tài khoản giả, kiểm tra admin và player, desktop/mobile, bot và một lượt daily đầy đủ, ôn câu sai. Để rút ngắn riêng bước QA trình duyệt, script tạm đổi chờ bot của database thử thành 10 giây và khôi phục lại 60 giây khi kết thúc; server thật vẫn giữ mặc định 60 giây. Không gửi thư hoặc thay dữ liệu người chơi thật.

Các bộ kiểm thử ba game cũ, admin, portal, tài khoản và email vẫn được giữ trong `scratch/`.

Trong đợt bổ sung ghép tự động đấu thường, đã kiểm tra: hàng chờ CASUAL không trộn với RANKED, đủ người thật bắt đầu ngay, điền bot đúng sau 60 giây, hoàn tất trận không thay đổi rank/thành tích rank/thẻ. Bộ `test_race.ps1` tại đợt đó đạt 187 kiểm tra. Chạy `node scratch/test_race_browser.mjs <PREVIEW_URL> --casual-only` để kiểm tra riêng nút tìm/hủy trận, bot, bố cục desktop/mobile và tạo phòng riêng; script vẫn dùng dữ liệu thử và khôi phục cấu hình sau khi chạy.

Trong đợt bổ sung quản trị bảng rank và tách daily, `scratch/test_admin_ranking.ps1` đạt 156 kiểm tra (dữ liệu thử hơn 100 người, phân trang, tìm kiếm giữ hạng toàn server, quyền truy cập, cấu hình độc lập và cấp lại lượt). Chạy thêm `-Preview` rồi `node scratch/test_admin_ranking_browser.mjs <PREVIEW_URL>` để kiểm tra giao diện admin và lưu từ hai mục. Bộ quản trị 140 kiểm tra và bộ Đường đua 187 kiểm tra của đợt đó cũng đạt.

Kết quả 30/10/2026: **164** kiểm tra Đường đua/rank, **574** luật Arena, **46** Sinh tử, **36** WebSocket/heartbeat, **140** quản trị, **35** portal, **101** tài khoản/OAuth/SSE và **100** đăng ký/khôi phục qua SMTP cục bộ đều đạt (tổng 1.196). Chrome đã kiểm tra giao diện Đường đua/admin và trang chủ trên desktop/mobile, cùng một lượt daily đầy đủ. Database thật sau nâng cấp giữ nguyên số tài khoản, trận ba game cũ, người tham gia và thành tích cũ; `PRAGMA quick_check` trả `ok`. Server thật giữ mặc định 15/60/7 như trên.

## Bộ câu hỏi riêng theo bậc rank lớn

Đấu rank dùng 5 bộ độc lập: **Đồng, Bạc, Vàng, Bạch Kim, Kim Cương**; ba cấp nhỏ trong cùng bậc dùng cùng bộ. Chỉ ghép người trong cùng bậc lớn, rồi ưu tiên RP gần nhau. Thiếu người vẫn điền bot theo thời gian ghép hiện có. Trước lúc ghép, server đọc lại rank để xử lý trường hợp kết quả trận trước vừa làm người chơi lên/tụt bậc.

Theo phần mở rộng rank hiện tại, **Cao Thủ dùng bộ Kim Cương** với toàn bộ câu Khó, nhưng hàng chờ vẫn chỉ ghép Cao Thủ với Cao Thủ; dùng chung bộ câu không có nghĩa là ghép chung với Kim Cương.

Hệ thống khởi tạo một lần **15 câu riêng mỗi bậc (75 câu)**. Câu hỏi Chung hiện có tiếp tục phục vụ đấu thường, chơi máy và thử thách ngày; mỗi bộ rank có ID/nội dung câu hỏi riêng. Cách chia trong một trận 10–15 câu:

| Bậc | Tỷ lệ chọn | Ví dụ 10 câu |
|---|---|---|
| Đồng | Tất cả Dễ | 10 Dễ |
| Bạc | Một phần ba Dễ (làm tròn xuống), còn lại Trung bình | 3 Dễ + 7 Trung bình |
| Vàng | Một phần ba Khó (làm tròn xuống), còn lại Trung bình | 7 Trung bình + 3 Khó |
| Bạch Kim | Một phần ba Trung bình (làm tròn xuống), còn lại Khó | 3 Trung bình + 7 Khó |
| Kim Cương | Tất cả Khó | 10 Khó |

Trong admin, **Ngân hàng câu hỏi** có bộ lọc **Bậc rank** và cột **Bộ rank**. Khi thêm/sửa, chọn **Bộ câu hỏi bậc rank**; câu đó thuộc chủ đề NETWORKING, phạm vi RACE, có lời giải và độ khó phù hợp với bậc. CSV hỗ trợ cột `rank_tier` (`BRONZE`, `SILVER`, `GOLD`, `PLATINUM`, `DIAMOND`; để trống cho câu Chung).

Chỉ câu đã xuất bản và chủ đề đang bật được đưa vào trận. Nếu thiếu câu theo số lượng/độ khó đã yêu cầu, server báo rõ để admin bổ sung, không lấy bù từ bậc khác. Đấu rank chỉ kiểm tra số câu khả dụng khi ghép/tạo trận, không chọn sẵn cả bộ. Khi mở mỗi lượt, server bốc độ khó theo phần cơ cấu còn lại rồi chọn ngẫu nhiên đúng một câu từ ngân hàng hiện tại, loại các ID đã dùng trong trận. Mọi người trong phòng nhận cùng câu và token. Bậc đề, tổng số câu và cơ cấu độ khó được lưu cùng cấu hình trận; câu vừa chọn được chụp/lưu trước khi gửi. Nối lại không bốc lại câu đang chơi. Admin thêm/sửa/ẩn câu có thể ảnh hưởng những lượt chưa mở; nếu ngân hàng thiếu câu giữa trận, server hủy trận rõ lý do, không tính RP hoặc tiêu thẻ, không lấy bù từ bậc khác.

Client chỉ nhận câu đang mở, không tải toàn bộ đề rank lúc vào trận. Server kiểm tra đáp án theo bản chụp đã lưu; sửa nội dung ngân hàng sau khi câu được gửi không thay đổi việc chấm câu đó. Cơ chế bốc từng lượt này dành cho rank; các chế độ khác giữ cơ chế chọn câu hiện có.

Nâng cấp database tự thêm `game_questions.rank_tier`, tạo bản sao `before-rank-pools-*` trước khi đổi database có nội dung, giữ nguyên câu hỏi đã sửa và chỉ khởi tạo các bộ rank một lần. Khởi động lại không xuất bản lại câu đã ẩn hoặc ghi đè nội dung admin đã chỉnh.
