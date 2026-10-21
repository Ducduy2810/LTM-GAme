package vn.game.model;

import java.util.*;

public class WheelPhraseBank {

    private static final List<WheelPhrase> ALL_PHRASES = new ArrayList<>();
    private static final Random RANDOM = new Random();

    static {
        // 1. CÔNG NGHỆ THÔNG TIN & MẠNG MÁY TÍNH (IT)
        ALL_PHRASES.add(new WheelPhrase("IT", "Một môn học chuyên ngành công nghệ thông tin", "LAP TRINH MANG"));
        ALL_PHRASES.add(new WheelPhrase("IT", "Phần mềm nền tảng quản lý tài nguyên máy tính", "HE DIEU HANH"));
        ALL_PHRASES.add(new WheelPhrase("IT", "Công nghệ phát triển mạnh mẽ mô phỏng trí tuệ con người", "TRI TUE NHAN TAO"));
        ALL_PHRASES.add(new WheelPhrase("IT", "Lĩnh vực bảo vệ hệ thống khỏi các cuộc tấn công mạng", "AN TOAN THONG TIN"));
        ALL_PHRASES.add(new WheelPhrase("IT", "Hệ thống lưu trữ và quản lý dữ liệu có tổ chức", "CO SO DU LIEU"));
        ALL_PHRASES.add(new WheelPhrase("IT", "Mô hình cung cấp dịch vụ máy chủ và tài nguyên qua mạng", "DIEN TOAN DAM MAY"));
        ALL_PHRASES.add(new WheelPhrase("IT", "Giao thức truyền tải siêu văn bản an toàn trên Internet", "GIAO THUC MANG"));
        ALL_PHRASES.add(new WheelPhrase("IT", "Mô hình phân tán lưu trữ chuỗi khối bất biến", "CONG NGHE BLOCKCHAIN"));

        // 2. DANH LAM THẮNG CẢNH & DU LỊCH (GEOGRAPHY)
        ALL_PHRASES.add(new WheelPhrase("GEOGRAPHY", "Kỳ quan thiên nhiên thế giới tại tỉnh Quảng Ninh", "VINH HA LONG"));
        ALL_PHRASES.add(new WheelPhrase("GEOGRAPHY", "Di sản thiên nhiên thế giới nổi tiếng với hệ thống hang động", "PHONG NHA KE BANG"));
        ALL_PHRASES.add(new WheelPhrase("GEOGRAPHY", "Địa danh lịch sử gắn liền tháp Rùa giữa lòng thủ đô", "HO GUOM HA NOI"));
        ALL_PHRASES.add(new WheelPhrase("GEOGRAPHY", "Ngôi chùa cổ kính có kiến trúc hình bông sen", "CHUA MOT COT"));
        ALL_PHRASES.add(new WheelPhrase("GEOGRAPHY", "Nóc nhà Đông Dương trên dãy Hoàng Liên Sơn", "DINH PHAN XI PANG"));
        ALL_PHRASES.add(new WheelPhrase("GEOGRAPHY", "Hòn đảo ngọc du lịch lớn nhất Việt Nam", "DAO PHU QUOC"));
        ALL_PHRASES.add(new WheelPhrase("GEOGRAPHY", "Hang động tự nhiên lớn nhất thế giới tại Quảng Bình", "HANG SON DOONG"));

        // 3. CA DAO - TỤC NGỮ (PROVERBS)
        ALL_PHRASES.add(new WheelPhrase("PROVERBS", "Câu tục ngữ khuyên răn sự kiên trì nhẫn nại", "CO CONG MAI SAT CO NGAY NEN KIM"));
        ALL_PHRASES.add(new WheelPhrase("PROVERBS", "Truyền thống đạo lý tri ân cội nguồn của dân tộc", "UONG NUOC NHO NGUON"));
        ALL_PHRASES.add(new WheelPhrase("PROVERBS", "Lời nhắc nhở nhớ ơn những người đi trước", "AN QUA NHO KE TRONG CAY"));
        ALL_PHRASES.add(new WheelPhrase("PROVERBS", "Đề cao tinh thần đoàn kết anh em một nhà", "LA LANH DUM LA RACH"));
        ALL_PHRASES.add(new WheelPhrase("PROVERBS", "Khuyên nhủ về môi trường và bạn bè xung quanh", "GAN MUC THI DEN GAN DEN THI RANG"));

        // 4. CHỦ ĐỀ CHUNG & GAMESHOW (GENERAL)
        ALL_PHRASES.add(new WheelPhrase("GENERAL", "Trò chơi truyền hình quen thuộc phát sóng trưa thứ 7 trên VTV3", "CHIEC NON KY DIEU"));
        ALL_PHRASES.add(new WheelPhrase("GENERAL", "Gameshow đọ trí giữa 1 người chơi chính và 100 đối thủ", "DAU TRUONG MOT TRAM"));
        ALL_PHRASES.add(new WheelPhrase("GENERAL", "Gameshow kiến thức dành cho học sinh trung học phổ thông", "DUONG LEN DINH OLYMPIA"));
        ALL_PHRASES.add(new WheelPhrase("GENERAL", "Chương trình truyền hình trắc nghiệm kiến thức dẫn dắt bởi nhà báo Lại Văn Sâm", "AI LA TRIEU PHU"));
    }

    public static WheelPhrase getRandomPhrase(String topic) {
        List<WheelPhrase> filtered = new ArrayList<>();
        if (topic != null && !"ALL".equalsIgnoreCase(topic)) {
            for (WheelPhrase p : ALL_PHRASES) {
                if (p.getTopic().equalsIgnoreCase(topic)) {
                    filtered.add(p);
                }
            }
        }
        if (filtered.isEmpty()) {
            filtered.addAll(ALL_PHRASES);
        }
        return filtered.get(RANDOM.nextInt(filtered.size()));
    }

    public static String getTopicDisplayName(String topic) {
        if (topic == null) return "Tất cả chủ đề";
        switch (topic.toUpperCase()) {
            case "IT": return "Công nghệ thông tin & Mạng";
            case "GEOGRAPHY": return "Danh lam thắng cảnh & Du lịch";
            case "PROVERBS": return "Ca dao & Tục ngữ Việt Nam";
            case "GENERAL": return "Gameshow & Văn hóa đại chúng";
            default: return "Tất cả chủ đề (Ngẫu nhiên)";
        }
    }
}
