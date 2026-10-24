package vn.game.db;

import vn.game.model.User;

import java.sql.*;
import java.util.UUID;

/**
 * UserRepository — thao tác CRUD với bảng `users` trong SQLite.
 * Giữ nguyên toàn bộ public interface để không cần sửa code gọi.
 */
public class UserRepository {

    private final DatabaseManager db;

    public UserRepository(DatabaseManager db) {
        this.db = db;
    }

    /** Constructor tương thích ngược — dùng Singleton DatabaseManager */
    public UserRepository() {
        this(DatabaseManager.getInstance());
    }

    // ── Đọc ──────────────────────────────────────────────────────────────────

    public User findByUsername(String username) {
        if (username == null) return null;
        String sql = "SELECT * FROM users WHERE username = ? COLLATE NOCASE LIMIT 1";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, username);
            return mapRow(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("findByUsername: " + e.getMessage(), e);
        }
    }

    public User findByEmail(String email) {
        if (email == null) return null;
        String sql = "SELECT * FROM users WHERE email = ? COLLATE NOCASE LIMIT 1";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, email);
            return mapRow(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("findByEmail: " + e.getMessage(), e);
        }
    }

    public User findByGoogleId(String googleId) {
        if (googleId == null) return null;
        String sql = "SELECT * FROM users WHERE google_id = ? LIMIT 1";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, googleId);
            return mapRow(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("findByGoogleId: " + e.getMessage(), e);
        }
    }

    public User findByResetToken(String token) {
        if (token == null) return null;
        String sql = "SELECT * FROM users WHERE reset_token = ? LIMIT 1";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, token);
            return mapRow(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("findByResetToken: " + e.getMessage(), e);
        }
    }

    // ── Ghi ──────────────────────────────────────────────────────────────────

    /**
     * Đăng ký user mới. Throws Exception nếu username/email đã tồn tại.
     */
    public synchronized User register(String username, String email, String password) throws Exception {
        if (findByUsername(username) != null) {
            throw new Exception("Tên tài khoản này đã có người sử dụng!");
        }
        if (email != null && !email.isBlank() && findByEmail(email) != null) {
            throw new Exception("Email này đã được sử dụng!");
        }

        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setSalt(User.generateSalt());
        user.setPasswordHash(User.hashPassword(password, user.getSalt()));
        user.setRole(
            "admin".equalsIgnoreCase(username) ||
            (email != null && "mainung28@gmail.com".equalsIgnoreCase(email))
            ? "admin" : "user"
        );

        String sql = """
            INSERT INTO users (username, email, password_hash, salt, google_id,
                               reset_token, reset_expires, role)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql,
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, user.getUsername());
            ps.setString(2, user.getEmail());
            ps.setString(3, user.getPasswordHash());
            ps.setString(4, user.getSalt());
            ps.setNull(5, Types.VARCHAR);    // google_id
            ps.setNull(6, Types.VARCHAR);    // reset_token
            ps.setLong(7, 0L);              // reset_expires
            ps.setString(8, user.getRole());
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) user.setId(keys.getInt(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("register: " + e.getMessage(), e);
        }
        return user;
    }

    /**
     * Cập nhật toàn bộ thông tin user (dùng sau khi thay đổi password, token...).
     */
    public synchronized void update(User user) {
        String sql = """
            UPDATE users SET
                email         = ?,
                password_hash = ?,
                salt          = ?,
                google_id     = ?,
                reset_token   = ?,
                reset_expires = ?,
                role          = ?
            WHERE username = ? COLLATE NOCASE
        """;
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, user.getEmail());
            ps.setString(2, user.getPasswordHash());
            ps.setString(3, user.getSalt());
            ps.setString(4, user.getGoogleId());
            ps.setString(5, user.getResetToken());
            ps.setLong(6, user.getResetExpires());
            ps.setString(7, user.getRole());
            ps.setString(8, user.getUsername());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("update: " + e.getMessage(), e);
        }
    }

    /**
     * Đăng nhập Google: tìm theo googleId → email → tạo mới.
     */
    public synchronized User registerOrGetGoogleUser(String googleId, String email, String name) {
        // 1. Tìm theo googleId
        User existing = findByGoogleId(googleId);
        if (existing != null) return existing;

        // 2. Tìm theo email, gán googleId
        if (email != null) {
            existing = findByEmail(email);
            if (existing != null) {
                existing.setGoogleId(googleId);
                update(existing);
                return existing;
            }
        }

        // 3. Tạo tài khoản mới
        String base = (name != null && !name.isBlank())
                ? name.replaceAll("[^a-zA-Z0-9_]", "")
                : (email != null ? email.split("@")[0] : "google_user");
        if (base.isEmpty()) base = "google_user";

        String finalUsername = base;
        int count = 1;
        while (findByUsername(finalUsername) != null) {
            finalUsername = base + "_" + count++;
        }

        User newUser = new User();
        newUser.setUsername(finalUsername);
        newUser.setEmail(email);
        newUser.setGoogleId(googleId);
        newUser.setSalt(User.generateSalt());
        newUser.setPasswordHash(User.hashPassword(UUID.randomUUID().toString(), newUser.getSalt()));
        newUser.setRole("user");

        String sql = """
            INSERT INTO users (username, email, password_hash, salt, google_id,
                               reset_token, reset_expires, role)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql,
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, newUser.getUsername());
            ps.setString(2, newUser.getEmail());
            ps.setString(3, newUser.getPasswordHash());
            ps.setString(4, newUser.getSalt());
            ps.setString(5, newUser.getGoogleId());
            ps.setNull(6, Types.VARCHAR);
            ps.setLong(7, 0L);
            ps.setString(8, newUser.getRole());
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) newUser.setId(keys.getInt(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("registerOrGetGoogleUser: " + e.getMessage(), e);
        }
        return newUser;
    }

    // ── Phân quyền ───────────────────────────────────────────────────────────

    public boolean isAdmin(String username) {
        if (username == null) return false;
        if ("admin".equalsIgnoreCase(username) || "DuyNguyen".equalsIgnoreCase(username)) return true;
        User u = findByUsername(username);
        return u != null && u.isAdmin();
    }

    public boolean isAdmin(User user) {
        if (user == null) return false;
        if ("admin".equalsIgnoreCase(user.getUsername()) || "DuyNguyen".equalsIgnoreCase(user.getUsername())) return true;
        if (user.getEmail() != null && "mainung28@gmail.com".equalsIgnoreCase(user.getEmail())) return true;
        return user.isAdmin();
    }

    // ── Migrate từ JSON (chạy 1 lần khi setup) ───────────────────────────────

    /**
     * Nhập dữ liệu từ users.json vào SQLite nếu bảng đang trống.
     * Tự động bỏ qua nếu đã có dữ liệu (idempotent).
     */
    public synchronized void migrateFromJson(java.util.List<User> users) {
        // Chỉ migrate khi bảng chưa có dữ liệu
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM users")) {
            if (rs.next() && rs.getInt(1) > 0) {
                System.out.println("ℹ️  Bảng users đã có dữ liệu, bỏ qua migrate.");
                return;
            }
        } catch (SQLException e) {
            throw new RuntimeException("migrateFromJson count: " + e.getMessage(), e);
        }

        String sql = """
            INSERT OR IGNORE INTO users
                (username, email, password_hash, salt, google_id,
                 reset_token, reset_expires, role)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;
        int count = 0;
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            for (User u : users) {
                ps.setString(1, u.getUsername());
                ps.setString(2, u.getEmail());
                ps.setString(3, u.getPasswordHash());
                ps.setString(4, u.getSalt());
                ps.setString(5, u.getGoogleId());
                ps.setString(6, u.getResetToken());
                ps.setLong  (7, u.getResetExpires());
                ps.setString(8, u.getRole() != null ? u.getRole() : "user");
                ps.addBatch();
                count++;
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("migrateFromJson insert: " + e.getMessage(), e);
        }
        System.out.println("✅ Đã migrate " + count + " users từ JSON sang SQLite.");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /** Map 1 ResultSet row → User object */
    private User mapRow(ResultSet rs) throws SQLException {
        if (!rs.next()) return null;
        User u = new User();
        u.setId(rs.getInt("id"));
        u.setUsername(rs.getString("username"));
        u.setEmail(rs.getString("email"));
        u.setPasswordHash(rs.getString("password_hash"));
        u.setSalt(rs.getString("salt"));
        u.setGoogleId(rs.getString("google_id"));
        u.setResetToken(rs.getString("reset_token"));
        u.setResetExpires(rs.getLong("reset_expires"));
        u.setRole(rs.getString("role") != null ? rs.getString("role") : "user");
        return u;
    }
}
