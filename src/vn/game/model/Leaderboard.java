package vn.game.model;

import vn.game.db.DatabaseManager;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Leaderboard — lưu thống kê & xếp hạng người chơi vào SQLite.
 * Vẫn giữ cache in-memory (ConcurrentHashMap) để đọc nhanh,
 * mỗi thay đổi được ghi xuống DB ngay lập tức.
 */
public class Leaderboard {

    public static class PlayerStats {
        public final String username;
        public volatile int totalWins       = 0;
        public volatile int totalGames      = 0;
        public volatile int totalEliminated = 0;
        public volatile int rankPoints      = 0;
        public volatile int bestEliminated  = 0;

        public PlayerStats(String username) {
            this.username = username;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new HashMap<>();
            m.put("username",        username);
            m.put("totalWins",       totalWins);
            m.put("totalGames",      totalGames);
            m.put("totalEliminated", totalEliminated);
            m.put("rankPoints",      rankPoints);
            m.put("bestEliminated",  bestEliminated);
            return m;
        }
    }

    // ── Cache in-memory ───────────────────────────────────────────────────────
    private static final Map<String, PlayerStats> cache = new ConcurrentHashMap<>();

    // Tải dữ liệu từ DB khi class được nạp lần đầu
    static {
        loadAll();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Ghi nhận kết quả trận đấu, cập nhật cache và DB.
     */
    public static void recordMatch(String username, int eliminated, int totalOpponents, boolean won) {
        if (username == null) return;
        PlayerStats s = cache.computeIfAbsent(username, PlayerStats::new);
        s.totalGames++;
        s.totalEliminated += eliminated;
        if (eliminated > s.bestEliminated) s.bestEliminated = eliminated;
        if (won) s.totalWins++;
        s.rankPoints += calcMilestonePoints(eliminated, totalOpponents);
        upsert(s);
    }

    /** Tính điểm lũy tiến cho các mốc 5 người đã hoàn thành. */
    public static int calcMilestonePoints(int eliminated, int totalOpponents) {
        if (totalOpponents <= 0) return 0;
        int pts = 0;
        int completed = Math.min(Math.max(0, eliminated), totalOpponents) / 5;
        for (int step = 1; step <= completed; step++) {
            int i = step * 5;
            int rate = i <= 5 ? 100 : i <= 10 ? 150 : i <= 20 ? 200 : i <= 40 ? 250 : i <= 60 ? 300 : i <= 80 ? 400 : 500;
            pts += rate * 5;
        }
        return pts;
    }

    /** Danh sách checkpoint điểm để animate tăng dần trên client. */
    public static List<MilestoneInfo> getMilestones(int eliminated, int totalOpponents) {
        List<MilestoneInfo> list = new ArrayList<>();
        for (int i = 1; i <= Math.min(eliminated, totalOpponents); i++) {
            list.add(new MilestoneInfo(i, calcMilestonePoints(i, totalOpponents), i % 5 == 0));
        }
        return list;
    }

    /** Top N người chơi theo rankPoints. */
    public static List<Map<String, Object>> getTop(int n) {
        List<PlayerStats> list = new ArrayList<>(cache.values());
        list.sort((a, b) -> Integer.compare(b.rankPoints, a.rankPoints));
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < Math.min(n, list.size()); i++) result.add(list.get(i).toMap());
        return result;
    }

    public static PlayerStats get(String username) {
        return cache.get(username);
    }

    /** Xóa toàn bộ bảng xếp hạng (Admin only). */
    public static synchronized void clear() {
        cache.clear();
        String sql = "DELETE FROM leaderboard";
        try (Statement st = DatabaseManager.getInstance().getConnection().createStatement()) {
            st.execute(sql);
            System.out.println("🗑️  Đã xóa toàn bộ bảng xếp hạng.");
        } catch (SQLException e) {
            System.err.println("Leaderboard.clear: " + e.getMessage());
        }
    }

    // ── SQLite helpers ────────────────────────────────────────────────────────

    /** Tải toàn bộ dữ liệu từ DB vào cache. */
    private static synchronized void loadAll() {
        String sql = "SELECT * FROM leaderboard";
        try (Statement st  = DatabaseManager.getInstance().getConnection().createStatement();
             ResultSet rs  = st.executeQuery(sql)) {
            int count = 0;
            while (rs.next()) {
                PlayerStats s = mapRow(rs);
                cache.put(s.username, s);
                count++;
            }
            System.out.println("📊 Đã tải leaderboard: " + count + " người chơi từ SQLite.");
        } catch (Exception e) {
            // DB có thể chưa init lúc class load lần đầu → im lặng, sẽ load sau
            System.err.println("Leaderboard.loadAll (bỏ qua nếu DB chưa sẵn sàng): " + e.getMessage());
        }
    }

    /** INSERT hoặc UPDATE 1 dòng (UPSERT). */
    private static synchronized void upsert(PlayerStats s) {
        String sql = """
            INSERT INTO leaderboard
                (username, total_wins, total_games, total_eliminated, rank_points, best_eliminated)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(username) DO UPDATE SET
                total_wins       = excluded.total_wins,
                total_games      = excluded.total_games,
                total_eliminated = excluded.total_eliminated,
                rank_points      = excluded.rank_points,
                best_eliminated  = excluded.best_eliminated
        """;
        try (PreparedStatement ps = DatabaseManager.getInstance().getConnection().prepareStatement(sql)) {
            ps.setString(1, s.username);
            ps.setInt   (2, s.totalWins);
            ps.setInt   (3, s.totalGames);
            ps.setInt   (4, s.totalEliminated);
            ps.setInt   (5, s.rankPoints);
            ps.setInt   (6, s.bestEliminated);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Leaderboard.upsert: " + e.getMessage());
        }
    }

    private static PlayerStats mapRow(ResultSet rs) throws SQLException {
        PlayerStats s = new PlayerStats(rs.getString("username"));
        s.totalWins       = rs.getInt("total_wins");
        s.totalGames      = rs.getInt("total_games");
        s.totalEliminated = rs.getInt("total_eliminated");
        s.rankPoints      = rs.getInt("rank_points");
        s.bestEliminated  = rs.getInt("best_eliminated");
        return s;
    }

    /**
     * Reload cache từ DB (dùng sau khi clear() hoặc thay đổi trực tiếp DB).
     */
    public static synchronized void reload() {
        cache.clear();
        loadAll();
    }

    // ── MilestoneInfo ─────────────────────────────────────────────────────────

    public static class MilestoneInfo {
        public final int     eliminated;
        public final int     points;
        public final boolean isImportantMilestone;

        public MilestoneInfo(int eliminated, int points, boolean isImportantMilestone) {
            this.eliminated           = eliminated;
            this.points               = points;
            this.isImportantMilestone = isImportantMilestone;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new HashMap<>();
            m.put("eliminated",           eliminated);
            m.put("points",               points);
            m.put("isImportantMilestone", isImportantMilestone);
            return m;
        }
    }
}
