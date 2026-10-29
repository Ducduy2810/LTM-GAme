# Đường đua Kiến thức · Rank, đấu thường và thử thách ngày

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

Ghép theo mức RP gần nhau, mở rộng khoảng ghép theo thời gian. Đủ 4 người thật thì bắt đầu ngay; sau **60 giây** sẽ điền bot vào các chỗ thiếu. Admin có thể đặt mục tiêu 2–4 người. Bot được ghi rõ trong giao diện và tham gia thứ hạng trận rank theo yêu cầu này. Xếp hạng cá nhân và Top 100 chỉ ghi tài khoản thật.

Rank mặc định **10 câu, 15 giây/câu**; admin có thể chọn 10–15 câu. Mọi người nhận cùng câu hỏi và hạn trả lời. Đáp án chỉ được chốt một lần với token riêng của từng câu. Server dùng thứ tự nhận câu trả lời; không nhận thời gian, điểm, kết quả thắng/thua từ client. Đúng nhanh nhất +3 điểm, các đáp án đúng tiếp theo +2, sai/hết giờ 0. Điểm và lời giải công bố cùng lúc khi cả phòng đã trả lời hoặc hết thời gian, tránh để người chưa trả lời suy ra đáp án từ điểm.

| Vị trí | RP mặc định |
| --- | --- |
| Hạng 1 | +25 |
| Hạng 2, trận 3–4 người/bot | +10 |
| Hạng 2, trận 2 người/bot | 0 |
| Hạng 3 | −10 |
| Hạng 4 | −15 |

Hòa điểm: đồng hạng, chia trung bình RP của các vị trí đang hòa; làm tròn số nguyên theo hướng có lợi cho người chơi (17,5 → 18; −12,5 → −12). Không cộng RP dương nếu người chơi rời trận hoặc không đúng câu nào, kể cả khi mọi người cùng 0 điểm. Rời trận xếp sau người ở lại; kết quả vẫn được lưu khi trận kết thúc, tài khoản không thể vào trận Đường đua khác trước lúc đó.

Rank bắt đầu **Đồng 3, 0 RP**. Các bậc: Đồng → Bạc → Vàng → Bạch Kim → Kim Cương, mỗi bậc 3 → 2 → 1. Đủ 100 RP thì tự lên cấp, giữ điểm dư. Khi vượt sang **bậc chính mới** nhận thêm đệm 20 RP: Đồng 1 ở 95 RP thắng +25 → Bạc 3 ở 40 RP (20 dư + 20 đệm). Lên cấp trong cùng bậc không nhận thêm đệm. Kim Cương 1 tiếp tục tích lũy RP, không tự tạo thêm cấp ngoài danh sách.

Thua từ RP dương chỉ giảm đến 0. Chỉ thua tiếp khi đã ở 0 mới tụt một cấp và nhận 70 RP ở cấp đó. Đồng 3 là đáy. Rank cao nhất được giữ khi tụt rank. Danh hiệu hiện tại: đạt ít nhất Kim Cương 1, Top 1–10 là **Huyền Thoại**, Top 11–100 là **Cao Thủ**. Bảng server sắp theo tổng tiến trình `cấp × 100 + RP`, rồi số trận thắng, rồi tên tài khoản; chỉ gồm người đã có trận rank, không bị khóa và không phải admin. Danh hiệu cao nhất kiếm được khi chốt trận được lưu riêng.

## Thử thách ngày và thẻ

Mỗi ngày theo múi giờ **Asia/Ho_Chi_Minh**, một lượt, 10 câu **HARD**, mặc định **7 giây/câu**. Bắt đầu là dùng lượt; không cấp lại chỉ vì tải lại trang hoặc rời trận. Đúng ít nhất 8/10 và hoàn thành trận nhận 1 thẻ bảo vệ + 1 thẻ x2. Không tăng/giảm RP. Mốc ngày mới là 00:00 Việt Nam, không lấy ngày do client gửi.

Trước khi ghép rank, người chơi chủ động bật thẻ đang có. Thẻ bảo vệ chỉ tiêu khi RP gốc âm và đưa thay đổi RP về 0. Thẻ x2 chỉ tiêu khi RP dương và nhân đôi mức nhận. Có thể bật cả hai nhưng một kết quả chỉ tiêu loại phù hợp. Trận hòa 0 RP, đấu thường, luyện máy và trận bị admin hủy không dùng thẻ. Thưởng và tiêu thẻ được ghi cùng giao dịch với kết quả, có khóa duy nhất để không nhận lại do gửi lặp hoặc tải lại trang.

## Admin quản lý

Đăng nhập `/admin`. Thông tin admin trên máy này nằm ở đầu README. Các phần quản trị được tách như sau:

**Bảng xếp hạng server:** xem toàn bộ người chơi đã hoàn thành trận rank, không giới hạn ở Top 100. Phân trang 50 người, tìm theo tên tài khoản, lọc người đang xếp hạng/tài khoản khóa/tất cả. Có thứ hạng chính thức toàn server, rank và RP hiện tại, danh hiệu, rank cao nhất, số trận/thắng và trạng thái. Tìm kiếm giữ nguyên thứ hạng toàn server. Tài khoản khóa có thể tra cứu nhưng không có thứ hạng chính thức; tài khoản admin và người chưa có trận rank không nằm trong bảng. Bảng người chơi công khai vẫn giữ Top 100 theo cùng cách sắp xếp.

**Cấu hình & bảo trì:** công tắc chung cho Đường đua và thử thách, cấu hình bảo trì/game cũ, cùng toàn bộ cấu hình **Thử thách hằng ngày**. Tại đây admin bật/tắt daily, chỉnh giây/câu, mốc câu đúng, số thẻ bảo vệ/x2 được thưởng, hoặc cấp lại lượt hôm nay cho một tài khoản có lý do. Không còn các trường daily hoặc lựa chọn cấp lại lượt trong phần rank. Các biểu mẫu lưu riêng phần được chỉnh, tránh lưu rank ghi đè daily và ngược lại. Cấu hình daily có nhật ký riêng `DAILY_SETTINGS`.

**Đường đua & Rank:**

- Bật/tắt rank; cấu hình số câu, giây/câu rank/ghép thường, thời gian chờ bot và số người mục tiêu.
- Chỉnh RP từng vị trí (kể cả hạng 2 của trận 2 người), điểm đệm và RP sau tụt cấp.
- Xem hàng chờ, đưa người chơi ra khỏi hàng chờ có lý do.
- Xem phòng/trận đang chạy, gửi thông báo phòng, kick người chơi (kể cả chủ phòng) hoặc hủy trận có lý do. Hủy không thay đổi RP/thẻ. Khóa/ngắt phiên ở mục Người chơi cũng được áp dụng vào Đường đua.
- Đặt rank/RP hiện tại và số thẻ. Những chỉnh sửa này cần lý do và lưu nhật ký. Không sửa RP/thẻ/lượt khi người chơi còn trong hàng chờ hoặc phòng; hủy trận hoặc đưa ra trước. Hạ rank hiện tại không xóa thành tích cao nhất. Đặt rank không tự tạo số trận thắng giả.
- Xem 50 kết quả gần nhất cùng câu hỏi, đáp án đã chọn và lời giải của người chơi; nhật ký chung lưu các thao tác admin.

Cấu hình được chụp khi tạo trận. Chỉnh giữa trận chỉ ảnh hưởng trận mới; không đổi deadline hoặc mức thưởng của trận đang chạy. Bảo trì chung chặn trận mới; trận đã chạy tiếp tục theo cấu hình đã chụp. Chủ phòng đấu thường được kick, xóa **phòng chờ**, không có nút tạm dừng và không được xóa trận đang thi đấu. Phòng rank ghép tự động không có chủ phòng được can thiệp kết quả.

Ngân hàng câu hỏi → **Phạm vi sử dụng**:

- `LEGACY`: ba game ban đầu và phòng học.
- `RACE`: chỉ Đường đua.
- `BOTH`: dùng chung.

Đường đua chỉ chọn câu NETWORKING đang PUBLISHED, chủ đề còn bật và có lời giải. CSV nhận thêm cột tùy chọn `usage` với các giá trị trên; thiếu cột mặc định LEGACY. Bản cập nhật bổ sung **32 câu Lập trình mạng** (10 Dễ, 10 Trung bình, 12 Khó) có giải thích, chỉ cho Đường đua. Admin có thể sửa/ẩn hoặc bổ sung; trận đang chạy giữ câu hỏi đã chụp. Nếu không đủ câu phù hợp thì từ chối bắt đầu và báo rõ, không lấy câu sai chủ đề/độ khó hay trừ lượt trước.

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

Một tiến trình Java chịu trách nhiệm hàng chờ và nhịp trận. Khi deploy cần giữ volume `data/` và kết nối WebSocket/SSE qua proxy. Bản này chưa hỗ trợ chia một hàng chờ qua nhiều tiến trình/server. Mất kết nối cho phép tải lại trang để tiếp tục trận hiện tại; thời gian server vẫn chạy, các câu đã hết hạn tính 0. Hàng chờ giữ chỗ mất mạng 10 giây, phòng chờ giữ thành viên mất mạng 30 giây. Khi server dừng/khởi động lại, trận chưa hoàn thành bị hủy/ghi INTERRUPTED, không tính RP/thẻ; daily đã bắt đầu vẫn dùng lượt. Khôi phục trận chỉ chạy sau khi bind cổng thành công để chạy nhầm server thứ hai không làm hỏng trận của tiến trình đang phục vụ.

## Kiểm thử

`powershell -NoProfile -ExecutionPolicy Bypass -File scratch/test_race.ps1` tạo database riêng và chạy các kiểm tra RP, điểm đệm/tụt cấp, quyền phòng, bot sau 60 giây, thời gian rank, daily, thẻ, kết nối lại, gửi lặp và giao thức WebSocket thật. Có kiểm tra thay đổi cấu hình giữa trận, rời trận không nhận RP dương, và không chạy khôi phục khi cổng đang bị chiếm.

Thêm `-Preview` để giữ server thử và lấy `PREVIEW_URL`, sau đó chạy `node scratch/test_race_browser.mjs <PREVIEW_URL>`. Script Chrome dùng tài khoản giả, kiểm tra admin và player, desktop/mobile, bot và một lượt daily đầy đủ, ôn câu sai. Để rút ngắn riêng bước QA trình duyệt, script tạm đổi chờ bot của database thử thành 10 giây và khôi phục lại 60 giây khi kết thúc; server thật vẫn giữ mặc định 60 giây. Không gửi thư hoặc thay dữ liệu người chơi thật.

Các bộ kiểm thử ba game cũ, admin, portal, tài khoản và email vẫn được giữ trong `scratch/`.

Phần ghép tự động đấu thường đã được bổ sung kiểm tra: hàng chờ CASUAL không trộn với RANKED, đủ người thật bắt đầu ngay, điền bot đúng sau 60 giây, hoàn tất trận không thay đổi rank/thành tích rank/thẻ. Bộ `test_race.ps1` hiện đạt 187 kiểm tra. Chạy `node scratch/test_race_browser.mjs <PREVIEW_URL> --casual-only` để kiểm tra riêng nút tìm/hủy trận, bot, bố cục desktop/mobile và tạo phòng riêng; script vẫn dùng dữ liệu thử và khôi phục cấu hình sau khi chạy.

Bổ sung quản trị bảng rank và tách daily: `scratch/test_admin_ranking.ps1` đạt 156 kiểm tra (dữ liệu thử hơn 100 người, phân trang, tìm kiếm giữ hạng toàn server, quyền truy cập, cấu hình độc lập và cấp lại lượt). Chạy thêm `-Preview` rồi `node scratch/test_admin_ranking_browser.mjs <PREVIEW_URL>` để kiểm tra giao diện admin và lưu từ hai mục. Bộ quản trị hiện có 140 kiểm tra và bộ Đường đua 187 kiểm tra vẫn đạt sau thay đổi này.

Kết quả 30/10/2026: **164** kiểm tra Đường đua/rank, **574** luật Arena, **46** Sinh tử, **36** WebSocket/heartbeat, **140** quản trị, **35** portal, **101** tài khoản/OAuth/SSE và **100** đăng ký/khôi phục qua SMTP cục bộ đều đạt (tổng 1.196). Chrome đã kiểm tra giao diện Đường đua/admin và trang chủ trên desktop/mobile, cùng một lượt daily đầy đủ. Database thật sau nâng cấp giữ nguyên số tài khoản, trận ba game cũ, người tham gia và thành tích cũ; `PRAGMA quick_check` trả `ok`. Server thật giữ mặc định 15/60/7 như trên.
