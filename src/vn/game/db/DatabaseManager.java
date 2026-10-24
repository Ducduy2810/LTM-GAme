package vn.game.db;

import java.io.File;
import java.sql.*;
import java.util.logging.Logger;

/**
 * Quản lý kết nối SQLite duy nhất cho toàn bộ ứng dụng.
 * Dùng pattern Singleton + Connection pool đơn giản (1 connection, WAL mode).
 */
public class DatabaseManager {

    private static final Logger LOG = Logger.getLogger(DatabaseManager.class.getName());
    private static final String DB_FILE = "data/game.db";

    private static DatabaseManager instance;
    private Connection connection;

    // ── Singleton ────────────────────────────────────────────────────────────

    private DatabaseManager() {}

    public static synchronized DatabaseManager getInstance() {
        if (instance == null) {
            instance = new DatabaseManager();
        }
        return instance;
    }

    // ── Khởi tạo ─────────────────────────────────────────────────────────────

    /**
     * Mở kết nối SQLite, bật WAL mode (hiệu năng tốt hơn với concurrent reads),
     * rồi tạo tất cả bảng nếu chưa tồn tại.
     */
    public synchronized void init() {
        try {
            // Đảm bảo thư mục data/ tồn tại
            File dir = new File("data");
            if (!dir.exists()) dir.mkdirs();

            // Load driver (cần thiết với Java 17 + module path)
            Class.forName("org.sqlite.JDBC");

            connection = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);

            // Tối ưu hiệu năng
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");   // Write-Ahead Logging
                st.execute("PRAGMA synchronous=NORMAL"); // Cân bằng an toàn/tốc độ
                st.execute("PRAGMA foreign_keys=ON");    // Bật foreign key
            }

            createTables();
            System.out.println("✅ SQLite đã kết nối: " + new File(DB_FILE).getAbsolutePath());

        } catch (Exception e) {
            throw new RuntimeException("Không thể khởi tạo SQLite: " + e.getMessage(), e);
        }
    }

    // ── Tạo bảng ─────────────────────────────────────────────────────────────

    private void createTables() throws SQLException {
        try (Statement st = connection.createStatement()) {

            // Bảng users
            st.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    username      TEXT    NOT NULL UNIQUE COLLATE NOCASE,
                    email         TEXT    UNIQUE COLLATE NOCASE,
                    password_hash TEXT,
                    salt          TEXT,
                    google_id     TEXT    UNIQUE,
                    reset_token   TEXT,
                    reset_expires INTEGER NOT NULL DEFAULT 0,
                    role          TEXT    NOT NULL DEFAULT 'user'
                )
            """);

            // Index tìm kiếm nhanh
            st.execute("CREATE INDEX IF NOT EXISTS idx_users_email     ON users(email)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_users_google_id ON users(google_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_users_reset_token ON users(reset_token)");

            // Bảng leaderboard
            st.execute("""
                CREATE TABLE IF NOT EXISTS leaderboard (
                    username          TEXT    PRIMARY KEY COLLATE NOCASE,
                    total_wins        INTEGER NOT NULL DEFAULT 0,
                    total_games       INTEGER NOT NULL DEFAULT 0,
                    total_eliminated  INTEGER NOT NULL DEFAULT 0,
                    rank_points       INTEGER NOT NULL DEFAULT 0,
                    best_eliminated   INTEGER NOT NULL DEFAULT 0
                )
            """);

            st.execute("CREATE INDEX IF NOT EXISTS idx_lb_rank ON leaderboard(rank_points DESC)");
        }
        System.out.println("📋 Bảng SQLite đã sẵn sàng (users, leaderboard)");
    }

    // ── Truy cập connection ───────────────────────────────────────────────────

    /**
     * Trả về Connection dùng chung. Thread-safe vì SQLite WAL mode hỗ trợ
     * concurrent reads, còn writes đã được synchronized ở tầng Repository.
     */
    public Connection getConnection() {
        try {
            // Tự reconnect nếu connection bị đóng
            if (connection == null || connection.isClosed()) {
                System.out.println("⚠️  SQLite connection bị đóng, đang reconnect...");
                init();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Lỗi kiểm tra SQLite connection: " + e.getMessage(), e);
        }
        return connection;
    }

    /**
     * Đóng connection khi shutdown (gọi từ shutdown hook).
     */
    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                System.out.println("🔒 SQLite connection đã đóng.");
            }
        } catch (SQLException e) {
            System.err.println("Lỗi đóng SQLite: " + e.getMessage());
        }
    }
}
