package vn.game.model;

import java.util.*;

public class QuestionBank {

    private static final List<Question> ALL_QUESTIONS = new ArrayList<>();

    static {
        // ========================================================
        // 1. CHỦ ĐỀ: LẬP TRÌNH & MẠNG MÁY TÍNH (NETWORKING)
        // ========================================================
        // Dễ
        ALL_QUESTIONS.add(new Question(1, "Khái niệm 'Concurrency' trong lập trình mạng có nghĩa là gì?",
                Arrays.asList("Xử lý tuần tự", "Xử lý đồng thời", "Lỗi mất mạng", "Bảo mật mạng"), 1, "NETWORKING", "EASY"));
        ALL_QUESTIONS.add(new Question(2, "Cổng (Port) mặc định của giao thức HTTP không mã hóa là bao nhiêu?",
                Arrays.asList("21", "443", "80", "8080"), 2, "NETWORKING", "EASY"));
        ALL_QUESTIONS.add(new Question(3, "Giao thức nào truyền thông tin bảo mật và mã hóa trên Web?",
                Arrays.asList("FTP", "HTTPS", "SMTP", "Telnet"), 1, "NETWORKING", "EASY"));
        ALL_QUESTIONS.add(new Question(4, "Trong kiến trúc Client-Server, máy nào luôn chủ động gửi yêu cầu trước?",
                Arrays.asList("Client", "Server", "Database", "Firewall"), 0, "NETWORKING", "EASY"));

        // Trung bình
        ALL_QUESTIONS.add(new Question(5, "Giao thức nào đảm bảo truyền tin cậy, không mất gói tin và đúng thứ tự?",
                Arrays.asList("UDP", "TCP", "ICMP", "ARP"), 1, "NETWORKING", "MEDIUM"));
        ALL_QUESTIONS.add(new Question(6, "Trong Java Socket, lớp nào được sử dụng để lắng nghe kết nối TCP ở Server?",
                Arrays.asList("ServerSocket", "DatagramSocket", "SocketChannel", "HttpURLConnection"), 0, "NETWORKING", "MEDIUM"));
        ALL_QUESTIONS.add(new Question(7, "WebSocket nâng cấp kết nối (Upgrade) từ giao thức nào ban đầu?",
                Arrays.asList("HTTP/1.1", "FTP", "DNS", "SSH"), 0, "NETWORKING", "MEDIUM"));
        ALL_QUESTIONS.add(new Question(8, "Cơ chế nào ở Server giúp bảo vệ tài nguyên chia sẻ khi 100 client cùng truy cập?",
                Arrays.asList("Synchronization / Thread-Safe", "Tắt máy chủ", "Dừng luồng vĩnh viễn", "Xóa bộ nhớ đệm"), 0, "NETWORKING", "MEDIUM"));

        // Khó
        ALL_QUESTIONS.add(new Question(9, "Kỹ thuật nào giúp Server xử lý hàng nghìn kết nối mà không tạo 1 luồng (Thread) cho mỗi kết nối?",
                Arrays.asList("Non-blocking I/O (NIO / Epoll)", "Blocking IO truyền thống", "Tăng CPU", "Polling liên tục"), 0, "NETWORKING", "HARD"));
        ALL_QUESTIONS.add(new Question(10, "Chuẩn RFC nào đặc tả giao thức WebSocket chính thức?",
                Arrays.asList("RFC 2616", "RFC 6455", "RFC 793", "RFC 1035"), 1, "NETWORKING", "HARD"));
        ALL_QUESTIONS.add(new Question(11, "Thuật toán băm nào thường được dùng kết hợp Salt để lưu trữ mật khẩu an toàn?",
                Arrays.asList("MD5 đơn giản", "SHA-256 + Salt", "Base64", "ROT13"), 1, "NETWORKING", "HARD"));

        // ========================================================
        // 2. CHỦ ĐỀ: KHOA HỌC & CÔNG NGHỆ (SCIENCE)
        // ========================================================
        // Dễ
        ALL_QUESTIONS.add(new Question(12, "Hành tinh nào trong Hệ Mặt Trời được mệnh danh là Hành Tinh Đỏ?",
                Arrays.asList("Sao Kim", "Sao Hỏa", "Sao Mộc", "Sao Thổ"), 1, "SCIENCE", "EASY"));
        ALL_QUESTIONS.add(new Question(13, "Nguyên tố hóa học nào chiếm tỷ lệ lớn nhất trong không khí Trái Đất?",
                Arrays.asList("Oxy", "Nito (Nitrogen)", "Cacbonic", "Hydro"), 1, "SCIENCE", "EASY"));
        ALL_QUESTIONS.add(new Question(14, "Đơn vị đo lường điện trở trong vật lý là gì?",
                Arrays.asList("Volt", "Ampere", "Ohm", "Watt"), 2, "SCIENCE", "EASY"));

        // Trung bình
        ALL_QUESTIONS.add(new Question(15, "Ai là người phát minh ra định luật vạn vật hấp dẫn?",
                Arrays.asList("Albert Einstein", "Isaac Newton", "Galileo Galilei", "Nikola Tesla"), 1, "SCIENCE", "MEDIUM"));
        ALL_QUESTIONS.add(new Question(16, "Bộ nhớ RAM trong máy tính là viết tắt của cụm từ nào?",
                Arrays.asList("Read Access Memory", "Random Access Memory", "Rapid Action Module", "Real Application Memory"), 1, "SCIENCE", "MEDIUM"));
        ALL_QUESTIONS.add(new Question(17, "Vận tốc ánh sáng trong chân không xấp xỉ khoảng bao nhiêu?",
                Arrays.asList("300.000 km/s", "150.000 km/s", "30.000 km/s", "3.000 km/s"), 0, "SCIENCE", "MEDIUM"));

        // Khó
        ALL_QUESTIONS.add(new Question(18, "Hạt hạ nguyên tử nào mang điện tích âm trong nguyên tử?",
                Arrays.asList("Proton", "Neutron", "Electron", "Positron"), 2, "SCIENCE", "HARD"));
        ALL_QUESTIONS.add(new Question(19, "Ngôn ngữ lập trình đầu tiên được thiết kế bởi Ada Lovelace vào thế kỷ 19 dành cho cỗ máy nào?",
                Arrays.asList("ENIAC", "Cỗ máy phân tích (Analytical Engine)", "Turing Machine", "Mark I"), 1, "SCIENCE", "HARD"));

        // ========================================================
        // 3. CHỦ ĐỀ: LỊCH SỬ, VĂN HÓA & ĐỜI SỐNG (HISTORY)
        // ========================================================
        // Dễ
        ALL_QUESTIONS.add(new Question(20, "Gameshow 'Đấu Trường 100' phát sóng lần đầu tiên tại Việt Nam trên kênh truyền hình nào?",
                Arrays.asList("VTV1", "VTV3", "HTV7", "VTC9"), 1, "HISTORY", "EASY"));
        ALL_QUESTIONS.add(new Question(21, "Thủ đô của nước Cộng hòa Xã hội Chủ nghĩa Việt Nam là thành phố nào?",
                Arrays.asList("Đà Nẵng", "Hà Nội", "TP. Hồ Chí Minh", "Cần Thơ"), 1, "HISTORY", "EASY"));
        ALL_QUESTIONS.add(new Question(22, "Quốc hoa chính thức của Việt Nam là loài hoa nào?",
                Arrays.asList("Hoa Mai", "Hoa Sen", "Hoa Đào", "Hoa Lan"), 1, "HISTORY", "EASY"));

        // Trung bình
        ALL_QUESTIONS.add(new Question(23, "Vị vua nào đã ban 'Chiếu dời đô' từ Hoa Lư về Thăng Long vào năm 1010?",
                Arrays.asList("Lý Thái Tổ", "Lý Thường Kiệt", "Trần Hưng Đạo", "Lê Lợi"), 0, "HISTORY", "MEDIUM"));
        ALL_QUESTIONS.add(new Question(24, "Di sản thiên nhiên thế giới nào của Việt Nam nổi tiếng với hàng nghìn hòn đảo đá vôi kỳ vĩ?",
                Arrays.asList("Vịnh Hạ Long", "Phong Nha - Kẻ Bàng", "Côn Đảo", "Tràng An"), 0, "HISTORY", "MEDIUM"));
        ALL_QUESTIONS.add(new Question(25, "Chiến thắng Điện Biên Phủ 'lừng lẫy năm châu, chấn động địa cầu' diễn ra vào năm nào?",
                Arrays.asList("1945", "1954", "1968", "1975"), 1, "HISTORY", "MEDIUM"));

        // Khó
        ALL_QUESTIONS.add(new Question(26, "Tác phẩm văn học bất hủ 'Truyện Kiều' gồm bao nhiêu câu thơ lục bát?",
                Arrays.asList("3.254 câu", "3.000 câu", "2.854 câu", "3.500 câu"), 0, "HISTORY", "HARD"));
        ALL_QUESTIONS.add(new Question(27, "Cầu Long Biên tại Hà Nội được khánh thành và đưa vào sử dụng dưới thời toàn quyền nào?",
                Arrays.asList("Paul Doumer", "Jean Decoux", "Albert Sarraut", "Alexandre Varenne"), 0, "HISTORY", "HARD"));
        ALL_QUESTIONS.add(new Question(28, "Hiệp định Genève về chấm dứt chiến tranh ở Đông Dương được ký kết vào năm nào?",
                Arrays.asList("1954", "1945", "1973", "1975"), 0, "HISTORY", "HARD"));
        ALL_QUESTIONS.add(new Question(29, "Thành Cổ Loa thời Âu Lạc có tổng cộng bao nhiêu vòng thành?",
                Arrays.asList("3 vòng", "2 vòng", "4 vòng", "5 vòng"), 0, "HISTORY", "HARD"));
        ALL_QUESTIONS.add(new Question(30, "Ai là vị Trạng nguyên trẻ tuổi nhất trong lịch sử khoa bảng Việt Nam?",
                Arrays.asList("Nguyễn Hiền", "Lê Văn Thịnh", "Mạc Đĩnh Chi", "Lương Thế Vinh"), 0, "HISTORY", "HARD"));

        // Thêm câu hỏi Mạng máy tính & Lập trình (HARD / MEDIUM)
        ALL_QUESTIONS.add(new Question(31, "Cơ chế 'Heartbeat / Ping-Pong' trong giao thức WebSocket nhằm mục đích gì?",
                Arrays.asList("Kiểm tra kết nối sống và chống timeout", "Gửi dữ liệu lớn", "Mã hóa SSL", "Đổi Session ID"), 0, "NETWORKING", "HARD"));
        ALL_QUESTIONS.add(new Question(32, "Trong mô hình OSI, giao thức TCP và UDP thuộc tầng nào?",
                Arrays.asList("Tầng Giao vận (Transport)", "Tầng Mạng (Network)", "Tầng Ứng dụng (Application)", "Tầng Liên kết dữ liệu (Data Link)"), 0, "NETWORKING", "MEDIUM"));
        ALL_QUESTIONS.add(new Question(33, "Khái niệm 'Race Condition' xảy ra khi nào trong hệ thống đa luồng?",
                Arrays.asList("Nhiều luồng truy cập và sửa đổi dữ liệu chung không đồng bộ", "Mạng bị nghẽn", "CPU quá nhiệt", "Bộ nhớ bị rò rỉ"), 0, "NETWORKING", "HARD"));
        ALL_QUESTIONS.add(new Question(34, "Mã trạng thái HTTP nào biểu thị yêu cầu nâng cấp giao thức WebSocket thành công?",
                Arrays.asList("101 Switching Protocols", "200 OK", "302 Found", "400 Bad Request"), 0, "NETWORKING", "HARD"));
        ALL_QUESTIONS.add(new Question(35, "Giao thức DNS sử dụng cổng (Port) mặc định là bao nhiêu?",
                Arrays.asList("53", "25", "110", "143"), 0, "NETWORKING", "MEDIUM"));
        ALL_QUESTIONS.add(new Question(36, "Trong Java, cấu trúc dữ liệu nào vừa an toàn đa luồng (Thread-safe) vừa cho hiệu năng cao hơn Hashtable?",
                Arrays.asList("ConcurrentHashMap", "HashMap", "TreeMap", "ArrayList"), 0, "NETWORKING", "HARD"));

        // Thêm câu hỏi Khoa học (HARD / MEDIUM / EASY)
        ALL_QUESTIONS.add(new Question(37, "Thuyết tương đối hẹp được Albert Einstein công bố vào năm nào?",
                Arrays.asList("1905", "1915", "1895", "1921"), 0, "SCIENCE", "HARD"));
        ALL_QUESTIONS.add(new Question(38, "Kim loại nào có tính dẫn điện tốt nhất ở điều kiện tiêu chuẩn?",
                Arrays.asList("Bạc (Ag)", "Đồng (Cu)", "Vàng (Au)", "Nhôm (Al)"), 0, "SCIENCE", "MEDIUM"));
        ALL_QUESTIONS.add(new Question(39, "Hành tinh nào có số lượng vệ tinh tự nhiên nhiều nhất trong Hệ Mặt Trời hiện nay?",
                Arrays.asList("Sao Thổ", "Sao Mộc", "Sao Thiên Vương", "Sao Hải Vương"), 0, "SCIENCE", "HARD"));
        ALL_QUESTIONS.add(new Question(40, "Khí nào sau đây gây ra hiệu ứng nhà kính mạnh nhất?",
                Arrays.asList("Hơi nước và CO2", "Oxy", "Nito", "Argon"), 0, "SCIENCE", "MEDIUM"));
        ALL_QUESTIONS.add(new Question(41, "Cơ quan nào trong cơ thể người sản sinh ra hoóc-môn Insulin?",
                Arrays.asList("Tuyến tụy", "Gan", "Thận", "Tuyến giáp"), 0, "SCIENCE", "MEDIUM"));
        ALL_QUESTIONS.add(new Question(42, "Hệ số ma sát trượt thông thường có đơn vị là gì?",
                Arrays.asList("Không có đơn vị (đại lượng không thứ nguyên)", "Newton (N)", "Joule (J)", "m/s^2"), 0, "SCIENCE", "HARD"));
    }

    /**
     * Lấy danh sách câu hỏi phù hợp theo Chủ đề, Độ khó và Số lượng.
     * Hỗ trợ:
     * - Độ khó "ALL" hoặc "MIX" hoặc "RANDOM": Xáo trộn câu hỏi từ EASY → MEDIUM → HARD (tăng dần)
     * - Độ khó cụ thể: Lọc theo EASY, MEDIUM, HARD
     * - Chủ đề "ALL" hoặc "MIX" hoặc "RANDOM": Xáo trộn câu hỏi từ tất cả chủ đề
     */
    public static List<Question> getQuestions(String topic, String difficulty, int count) {
        String t = (topic != null) ? topic.toUpperCase().trim() : "ALL";
        String d = (difficulty != null) ? difficulty.toUpperCase().trim() : "ALL";

        // YÊU CẦU 3: Hỗ trợ xáo trộn chủ đề ngẫu nhiên
        boolean mixTopic = "ALL".equals(t) || "MIX".equals(t) || "RANDOM".equals(t);
        
        // YÊU CẦU 1: Hỗ trợ câu hỏi tăng dần độ khó
        boolean mixDifficulty = "ALL".equals(d) || "MIX".equals(d) || "RANDOM".equals(d);

        List<Question> filtered = new ArrayList<>();

        if (mixTopic && mixDifficulty) {
            // Xáo trộn cả chủ đề và độ khó → sắp xếp theo độ khó tăng dần
            filtered.addAll(ALL_QUESTIONS);
            // Sắp xếp theo độ khó: EASY → MEDIUM → HARD
            filtered.sort((a, b) -> {
                int orderA = getDifficultyOrder(a.getDifficulty());
                int orderB = getDifficultyOrder(b.getDifficulty());
                return Integer.compare(orderA, orderB);
            });
        } else if (mixTopic) {
            // Xáo trộn chủ đề, giữ độ khó cố định
            for (Question q : ALL_QUESTIONS) {
                if (q.getDifficulty().equals(d)) {
                    filtered.add(q);
                }
            }
        } else if (mixDifficulty) {
            // Giữ chủ đề, tăng dần độ khó
            for (Question q : ALL_QUESTIONS) {
                if (q.getTopic().equals(t)) {
                    filtered.add(q);
                }
            }
            filtered.sort((a, b) -> {
                int orderA = getDifficultyOrder(a.getDifficulty());
                int orderB = getDifficultyOrder(b.getDifficulty());
                return Integer.compare(orderA, orderB);
            });
        } else {
            // Lọc chính xác theo chủ đề và độ khó
            for (Question q : ALL_QUESTIONS) {
                boolean topicMatch = q.getTopic().equals(t);
                boolean diffMatch = q.getDifficulty().equals(d);
                if (topicMatch && diffMatch) {
                    filtered.add(q);
                }
            }
        }

        // Nếu số câu chưa đủ, lấy thêm từ kho chung (giữ nguyên logic cũ)
        if (filtered.size() < count) {
            for (Question q : ALL_QUESTIONS) {
                if (!filtered.contains(q)) {
                    filtered.add(q);
                    if (filtered.size() >= count) break;
                }
            }
        }

        // Nếu không phải tăng dần độ khó, trộn ngẫu nhiên
        if (!mixDifficulty) {
            Collections.shuffle(filtered);
        }

        int resultCount = Math.min(count, filtered.size());
        List<Question> result = new ArrayList<>();
        for (int i = 0; i < resultCount; i++) {
            Question src = filtered.get(i);
            result.add(new Question(i + 1, src.getText(), src.getOptions(), src.getCorrect(), src.getTopic(), src.getDifficulty()));
        }
        return result;
    }

    private static int getDifficultyOrder(String difficulty) {
        if ("EASY".equals(difficulty)) return 1;
        if ("MEDIUM".equals(difficulty)) return 2;
        if ("HARD".equals(difficulty)) return 3;
        return 0;
    }
}
