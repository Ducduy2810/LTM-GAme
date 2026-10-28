package vn.game;

import vn.game.db.DatabaseManager;
import vn.game.db.UserRepository;
import vn.game.games.ArenaGame;
import vn.game.games.SurvivalGame;
import vn.game.games.WheelGame;
import vn.game.model.Leaderboard;
import vn.game.model.User;
import vn.game.server.HttpAndWebSocketServer;
import vn.game.server.SessionManager;
import vn.game.util.JsonUtil;

import java.io.File;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.net.NetworkInterface;

public class Main {

    public static void main(String[] args) {
        int port = 3000;
        File publicDir = new File("public");
        if (!publicDir.exists()) {
            publicDir = new File("../public");
        }

        System.out.println("===============================================================");
        System.out.println("   🎮 HE THONG GAME PORTAL - JAVA PURE NETWORK PROGRAMMING   ");
        System.out.println("   - Game 1: Dau Truong 100 (luot phu -> luot chinh, timer rieng) ");
        System.out.println("   - Game 2: Chiec Non Ky Dieu (4 Nguoi choi theo luot, 10s)   ");
        System.out.println("   - Game 3: Dau Truong Sinh Tu (HP giam dan, Thuong moc loai) ");
        System.out.println("   - Xac thuc & Bao mat: Session Cookie + SHA-256 Hash + Salt  ");
        System.out.println("   - Giao thuc mang: TCP Socket + WebSocket RFC 6455 + HTTP    ");
        System.out.println("===============================================================");

        // ── Khởi tạo SQLite DB ────────────────────────────────────────────────
        DatabaseManager dbManager = DatabaseManager.getInstance();
        dbManager.init();

        // Đăng ký shutdown hook để đóng DB khi tắt server
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("🛑 Server đang tắt, đóng kết nối DB...");
            dbManager.close();
        }));

        UserRepository userRepository = new UserRepository(dbManager);

        // ── Migrate dữ liệu từ JSON (chạy 1 lần duy nhất) ────────────────────
        migrateUsersFromJson(userRepository);
        migrateLeaderboardFromJson();

        // ── Reload leaderboard cache từ DB (sau khi DB đã sẵn sàng) ──────────
        Leaderboard.reload();

        SessionManager sessionManager = new SessionManager();
        ArenaGame arenaGame = new ArenaGame(userRepository);
        WheelGame wheelGame = new WheelGame(userRepository);
        SurvivalGame survivalGame = new SurvivalGame(userRepository);

        HttpAndWebSocketServer server = new HttpAndWebSocketServer(
                port, publicDir, userRepository, sessionManager, arenaGame, wheelGame, survivalGame
        );

        try {
            System.out.println("🌐 Truy cap tren may nay : http://localhost:" + port);
            System.out.println("📱 Cho ban be vao mang LAN, thu cac IP sau:");
            
            boolean foundLanIp = false;
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    String ip = addr.getHostAddress();
                    if (!addr.isLoopbackAddress() && ip.contains(".") && !ip.startsWith("169.254")) {
                        System.out.println("   👉 http://" + ip + ":" + port);
                        foundLanIp = true;
                    }
                }
            }
            
            if (!foundLanIp) {
                String localIp = InetAddress.getLocalHost().getHostAddress();
                System.out.println("   👉 http://" + localIp + ":" + port);
            }
            
            System.out.println("===============================================================");
            System.out.println("⚠️  NEU BAN BE KHONG VAO DUOC:");
            System.out.println("   1. Chay file 'open_firewall.bat' (Run as Administrator)");
            System.out.println("   2. Hoac mo Windows Defender Firewall -> Allow port " + port);
            System.out.println("   3. Kiem tra ca hai may cung mang WiFi/LAN");
            System.out.println("===============================================================");

            server.start();
        } catch (Exception e) {
            System.err.println("❌ Lỗi khởi động máy chủ: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ── Migrate từ JSON sang SQLite (chỉ chạy 1 lần nếu DB trống) ───────────

    @SuppressWarnings("unchecked")
    private static void migrateUsersFromJson(UserRepository userRepository) {
        File jsonFile = new File("data/users.json");
        if (!jsonFile.exists()) return;
        try {
            String json = Files.readString(jsonFile.toPath(), StandardCharsets.UTF_8);
            Object parsed = JsonUtil.parse(json);
            if (!(parsed instanceof List)) return;
            List<User> users = new ArrayList<>();
            for (Object item : (List<?>) parsed) {
                if (item instanceof Map) {
                    users.add(User.fromMap((Map<String, Object>) item));
                }
            }
            userRepository.migrateFromJson(users);
        } catch (Exception e) {
            System.err.println("⚠️  Migrate users.json thất bại: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static void migrateLeaderboardFromJson() {
        File jsonFile = new File("data/leaderboard.json");
        if (!jsonFile.exists()) return;
        try {
            // Chỉ migrate khi bảng leaderboard đang trống
            if (!Leaderboard.getTop(1).isEmpty()) {
                System.out.println("ℹ️  Leaderboard đã có dữ liệu, bỏ qua migrate.");
                return;
            }
            String json = Files.readString(jsonFile.toPath(), StandardCharsets.UTF_8);
            Object parsed = JsonUtil.parse(json);
            if (!(parsed instanceof List)) return;
            int count = 0;
            for (Object item : (List<?>) parsed) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) item;
                String username = (String) m.get("username");
                if (username == null) continue;
                String sql = """
                    INSERT INTO leaderboard
                        (username, total_wins, total_games, total_eliminated,
                         rank_points, best_eliminated)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(username) DO NOTHING
                """;
                try (java.sql.PreparedStatement ps =
                        DatabaseManager.getInstance().getConnection().prepareStatement(sql)) {
                    ps.setString(1, username);
                    ps.setInt   (2, toInt(m.get("totalWins")));
                    ps.setInt   (3, toInt(m.get("totalGames")));
                    ps.setInt   (4, toInt(m.get("totalEliminated")));
                    ps.setInt   (5, toInt(m.get("rankPoints")));
                    ps.setInt   (6, toInt(m.get("bestEliminated")));
                    ps.executeUpdate();
                    count++;
                }
            }
            System.out.println("✅ Đã migrate " + count + " bản ghi leaderboard từ JSON sang SQLite.");
        } catch (Exception e) {
            System.err.println("⚠️  Migrate leaderboard.json thất bại: " + e.getMessage());
        }
    }

    private static int toInt(Object val) {
        if (val instanceof Number) return ((Number) val).intValue();
        try { return Integer.parseInt(String.valueOf(val)); } catch (Exception e) { return 0; }
    }
}
