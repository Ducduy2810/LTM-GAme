package vn.game.model;

import vn.game.util.JsonUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lưu thống kê & xếp hạng người chơi.
 * Dùng ConcurrentHashMap để thread-safe vì nhiều ArenaRoom cùng ghi đồng thời.
 */
public class Leaderboard {
    public static class PlayerStats {
        public final String username;
        public volatile int totalWins = 0;          // Số trận thắng (loại hết đối thủ)
        public volatile int totalGames = 0;         // Tổng số trận đã chơi
        public volatile int totalEliminated = 0;    // Tổng đối thủ đã loại được
        public volatile int rankPoints = 0;         // Điểm xếp hạng tích lũy
        public volatile int bestEliminated = 0;     // Kỷ lục: loại nhiều nhất trong 1 trận

        public PlayerStats(String username) {
            this.username = username;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new HashMap<>();
            m.put("username", username);
            m.put("totalWins", totalWins);
            m.put("totalGames", totalGames);
            m.put("totalEliminated", totalEliminated);
            m.put("rankPoints", rankPoints);
            m.put("bestEliminated", bestEliminated);
            return m;
        }

        public static PlayerStats fromMap(Map<String, Object> m) {
            String username = (String) m.get("username");
            if (username == null) return null;
            PlayerStats s = new PlayerStats(username);
            s.totalWins = toInt(m.get("totalWins"));
            s.totalGames = toInt(m.get("totalGames"));
            s.totalEliminated = toInt(m.get("totalEliminated"));
            s.rankPoints = toInt(m.get("rankPoints"));
            s.bestEliminated = toInt(m.get("bestEliminated"));
            return s;
        }

        private static int toInt(Object val) {
            if (val instanceof Number) return ((Number) val).intValue();
            if (val instanceof String) {
                try { return Integer.parseInt((String) val); } catch (Exception e) { return 0; }
            }
            return 0;
        }
    }

    private static final Map<String, PlayerStats> stats = new ConcurrentHashMap<>();
    private static final String DATA_FILE = "data/leaderboard.json";

    // Tự động tải dữ liệu từ file khi class được nạp
    static {
        loadFromFile();
    }

    /**
     * Cập nhật thống kê sau khi kết thúc trận đấu.
     * @param username     Tên người chơi chính (mainPlayer)
     * @param eliminated   Số đối thủ đã loại được trong trận này
     * @param totalOpponents Tổng số đối thủ ban đầu (thật + Bot)
     * @param won          Có thắng hoàn toàn (loại hết đối thủ) không?
     */
    public static void recordMatch(String username, int eliminated, int totalOpponents, boolean won) {
        if (username == null) return;
        PlayerStats s = stats.computeIfAbsent(username, PlayerStats::new);
        s.totalGames++;
        s.totalEliminated += eliminated;
        if (eliminated > s.bestEliminated) s.bestEliminated = eliminated;
        if (won) s.totalWins++;
        int points = calcMilestonePoints(eliminated, totalOpponents);
        s.rankPoints += points;
        saveToFile();
    }

    /**
     * Tính điểm theo mốc số đối thủ đã loại, quy về % tổng đối thủ.
     * YÊU CẦU 6: Cải thiện cơ chế tính điểm theo từng mốc 5 người
     */
    public static int calcMilestonePoints(int eliminated, int totalOpponents) {
        if (totalOpponents <= 0) return 0;
        
        // Tính điểm dựa trên số người đã loại (mỗi 5 người = 1 mốc)
        // Mốc 1-5: 100 điểm/người
        // Mốc 6-10: 150 điểm/người
        // Mốc 11-20: 200 điểm/người
        // Mốc 21-40: 250 điểm/người
        // Mốc 41-60: 300 điểm/người
        // Mốc 61-80: 400 điểm/người
        // Mốc 81-100: 500 điểm/người
        
        int totalPoints = 0;
        
        for (int i = 1; i <= eliminated; i++) {
            if (i <= 5) {
                totalPoints += 100;
            } else if (i <= 10) {
                totalPoints += 150;
            } else if (i <= 20) {
                totalPoints += 200;
            } else if (i <= 40) {
                totalPoints += 250;
            } else if (i <= 60) {
                totalPoints += 300;
            } else if (i <= 80) {
                totalPoints += 400;
            } else {
                totalPoints += 500;
            }
        }
        
        return totalPoints;
    }
    
    /**
     * YÊU CẦU 6: Tính điểm từng bước để hiển thị tăng dần
     * Trả về danh sách các mốc điểm từ 0 đến eliminated
     */
    public static List<MilestoneInfo> getMilestones(int eliminated, int totalOpponents) {
        List<MilestoneInfo> milestones = new ArrayList<>();
        int cumulativePoints = 0;
        
        for (int i = 0; i <= eliminated; i++) {
            if (i > 0) {
                if (i <= 5) {
                    cumulativePoints += 100;
                } else if (i <= 10) {
                    cumulativePoints += 150;
                } else if (i <= 20) {
                    cumulativePoints += 200;
                } else if (i <= 40) {
                    cumulativePoints += 250;
                } else if (i <= 60) {
                    cumulativePoints += 300;
                } else if (i <= 80) {
                    cumulativePoints += 400;
                } else {
                    cumulativePoints += 500;
                }
            }
            
            // Kiểm tra có phải mốc quan trọng không (chia hết cho 5)
            boolean isImportantMilestone = (i > 0 && i % 5 == 0);
            milestones.add(new MilestoneInfo(i, cumulativePoints, isImportantMilestone));
        }
        
        return milestones;
    }
    
    public static class MilestoneInfo {
        public final int eliminated;
        public final int points;
        public final boolean isImportantMilestone;
        
        public MilestoneInfo(int eliminated, int points, boolean isImportantMilestone) {
            this.eliminated = eliminated;
            this.points = points;
            this.isImportantMilestone = isImportantMilestone;
        }
        
        public Map<String, Object> toMap() {
            Map<String, Object> m = new HashMap<>();
            m.put("eliminated", eliminated);
            m.put("points", points);
            m.put("isImportantMilestone", isImportantMilestone);
            return m;
        }
    }

    public static List<Map<String, Object>> getTop(int n) {
        List<PlayerStats> list = new ArrayList<>(stats.values());
        list.sort((a, b) -> Integer.compare(b.rankPoints, a.rankPoints));
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < Math.min(n, list.size()); i++) {
            result.add(list.get(i).toMap());
        }
        return result;
    }

    public static PlayerStats get(String username) {
        return stats.get(username);
    }

    // ── PERSISTENCE: Lưu/đọc dữ liệu ra file ──────────────────────

    public static synchronized void saveToFile() {
        try {
            File dir = new File("data");
            if (!dir.exists()) dir.mkdirs();

            List<Map<String, Object>> list = new ArrayList<>();
            for (PlayerStats s : stats.values()) {
                list.add(s.toMap());
            }

            String json = JsonUtil.toJson(list);
            Files.writeString(new File(DATA_FILE).toPath(), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Lỗi lưu file leaderboard: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public static synchronized void loadFromFile() {
        File file = new File(DATA_FILE);
        if (!file.exists()) {
            // Thử đường dẫn tương đối từ thư mục cha (nếu chạy từ bin/)
            File alt = new File("../" + DATA_FILE);
            if (alt.exists()) file = alt;
            else return;
        }

        try {
            String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            Object obj = JsonUtil.parse(json);
            if (obj instanceof List) {
                List<?> list = (List<?>) obj;
                for (Object item : list) {
                    if (item instanceof Map) {
                        PlayerStats s = PlayerStats.fromMap((Map<String, Object>) item);
                        if (s != null) stats.put(s.username, s);
                    }
                }
            }
            System.out.println("📊 Đã tải leaderboard: " + stats.size() + " người chơi từ " + file.getPath());
        } catch (Exception e) {
            System.err.println("Lỗi đọc file leaderboard: " + e.getMessage());
        }
    }
}
