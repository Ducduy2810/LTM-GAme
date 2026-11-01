package vn.game.db;

import vn.game.model.User;
import vn.game.util.PasswordResetCode;
import vn.game.util.EmailVerification;

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

    /** Giữ thông tin đăng ký dưới dạng băm; chưa tạo tài khoản hoạt động. */
    public synchronized boolean issueEmailVerification(String username, String email, String password, String tokenHash, long now) throws Exception {
        if (findByUsername(username) != null) throw new Exception("Tên tài khoản này đã có người sử dụng!");
        if (findByEmail(email) != null) throw new Exception("Email này đã được đăng ký. Hãy đăng nhập hoặc lấy lại mật khẩu.");
        try (PreparedStatement ps = db.getConnection().prepareStatement("DELETE FROM pending_registrations WHERE expires <= ?")) {
            ps.setLong(1, now);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = db.getConnection().prepareStatement("SELECT * FROM pending_registrations WHERE email = ? COLLATE NOCASE")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    if (!username.equalsIgnoreCase(rs.getString("username"))
                            || !User.hashPassword(password, rs.getString("salt")).equals(rs.getString("password_hash"))) {
                        throw new Exception("Email này đang chờ xác nhận. Để gửi lại liên kết, hãy nhập đúng tên đăng nhập và mật khẩu của yêu cầu trước, hoặc đợi liên kết hết hạn sau 30 phút.");
                    }
                    if (rs.getLong("sent_at") > now - EmailVerification.RESEND_AFTER_MS) return false;
                }
            }
        }
        try (PreparedStatement ps = db.getConnection().prepareStatement("SELECT 1 FROM pending_registrations WHERE username = ? COLLATE NOCASE AND email <> ? COLLATE NOCASE")) {
            ps.setString(1, username);
            ps.setString(2, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) throw new Exception("Tên đăng nhập này đang chờ xác nhận email. Hãy chọn tên khác hoặc xác nhận yêu cầu trước.");
            }
        }
        String salt = User.generateSalt();
        try (PreparedStatement ps = db.getConnection().prepareStatement("""
            INSERT INTO pending_registrations (email, username, password_hash, salt, token_hash, expires, sent_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(email) DO UPDATE SET token_hash = excluded.token_hash, expires = excluded.expires, sent_at = excluded.sent_at
        """)) {
            ps.setString(1, email);
            ps.setString(2, username);
            ps.setString(3, User.hashPassword(password, salt));
            ps.setString(4, salt);
            ps.setString(5, tokenHash);
            ps.setLong(6, now + EmailVerification.VALID_FOR_MS);
            ps.setLong(7, now);
            ps.executeUpdate();
            return true;
        }
    }

    public synchronized void cancelEmailVerification(String email, String tokenHash) {
        try (PreparedStatement ps = db.getConnection().prepareStatement("DELETE FROM pending_registrations WHERE email = ? COLLATE NOCASE AND token_hash = ?")) {
            ps.setString(1, email);
            ps.setString(2, tokenHash);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("cancelEmailVerification: " + e.getMessage(), e); }
    }

    public boolean hasPendingRegistration(String username) {
        try (PreparedStatement ps = db.getConnection().prepareStatement("SELECT 1 FROM pending_registrations WHERE username = ? COLLATE NOCASE AND expires > ?")) {
            ps.setString(1, username);
            ps.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { throw new RuntimeException("hasPendingRegistration: " + e.getMessage(), e); }
    }

    /** Xác nhận và tạo tài khoản trong cùng khóa ghi, không thể dùng liên kết hai lần. */
    public synchronized User confirmEmailVerification(String tokenHash, long now) throws Exception {
        String username, email, passwordHash, salt;
        try (PreparedStatement ps = db.getConnection().prepareStatement("SELECT * FROM pending_registrations WHERE token_hash = ? AND expires > ?")) {
            ps.setString(1, tokenHash);
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                username = rs.getString("username");
                email = rs.getString("email");
                passwordHash = rs.getString("password_hash");
                salt = rs.getString("salt");
            }
        }
        if (findByUsername(username) != null || findByEmail(email) != null) {
            throw new Exception("Tên đăng nhập hoặc email đã được sử dụng. Hãy đăng nhập hoặc đăng ký lại với thông tin khác.");
        }
        User user = insertUser(username, email, passwordHash, salt);
        cancelEmailVerification(email, tokenHash);
        return user;
    }


    /** Giữ chỗ gửi mã nguyên tử, tránh nhiều request cùng gửi cho một tài khoản. */
    public synchronized User issuePasswordReset(String email, String hash, long now) {
        User user = findByEmail(email);
        if (user == null) return null;
        String sql = """
            UPDATE users SET reset_token = ?, reset_expires = ?, reset_attempts = 0, reset_sent_at = ?
            WHERE id = ? AND reset_sent_at <= ?
        """;
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, hash);
            ps.setLong(2, now + PasswordResetCode.VALID_FOR_MS);
            ps.setLong(3, now);
            ps.setInt(4, user.getId());
            ps.setLong(5, now - PasswordResetCode.RESEND_AFTER_MS);
            return ps.executeUpdate() == 1 ? user : null;
        } catch (SQLException e) {
            throw new RuntimeException("issuePasswordReset: " + e.getMessage(), e);
        }
    }

    /** Chỉ hủy đúng mã gửi thất bại, không xóa mã mới hơn. */
    public synchronized void cancelPasswordReset(int userId, String hash) {
        try (PreparedStatement ps = db.getConnection().prepareStatement("""
            UPDATE users SET reset_token = NULL, reset_expires = 0, reset_attempts = 0, reset_sent_at = 0
            WHERE id = ? AND reset_token = ?
        """)) {
            ps.setInt(1, userId);
            ps.setString(2, hash);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("cancelPasswordReset: " + e.getMessage(), e);
        }
    }

    /** Kiểm tra, đếm lần sai và tiêu thụ mã trong cùng khóa ghi của repository. */
    public synchronized String resetPasswordWithCode(String email, String code, String password, long now) {
        User user = findByEmail(email);
        if (user == null) return null;
        try (PreparedStatement ps = db.getConnection().prepareStatement(
                "SELECT reset_attempts FROM users WHERE id = ?")) {
            ps.setInt(1, user.getId());
            int attempts;
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                attempts = rs.getInt("reset_attempts");
            }
            if (user.getResetExpires() <= now || attempts >= PasswordResetCode.MAX_ATTEMPTS
                    || user.getResetToken() == null || !user.getResetToken().startsWith("otp:")) return null;
            if (!PasswordResetCode.matches(email, code, user.getResetToken())) {
                try (PreparedStatement update = db.getConnection().prepareStatement(
                        "UPDATE users SET reset_attempts = reset_attempts + 1 WHERE id = ?")) {
                    update.setInt(1, user.getId());
                    update.executeUpdate();
                }
                return null;
            }
            user.updatePassword(password);
            update(user);
            return user.getUsername();
        } catch (SQLException e) {
            throw new RuntimeException("resetPasswordWithCode: " + e.getMessage(), e);
        }
    }

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

        String salt = User.generateSalt();
        return insertUser(username, email, User.hashPassword(password, salt), salt);
    }

    private User insertUser(String username, String email, String passwordHash, String salt) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setSalt(salt);
        user.setPasswordHash(passwordHash);
        user.setRole("user");

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
        if(googleId==null||googleId.isBlank()||!vn.game.util.PasswordResetMailer.isEmail(email))throw new IllegalArgumentException("Thông tin Google chưa hợp lệ.");
        // 1. Tìm theo googleId
        User existing = findByGoogleId(googleId);
        if (existing != null) {if(existing.isAdmin())throw new IllegalArgumentException("Tài khoản quản trị dùng trang riêng.");return existing;}

        // 2. Tìm theo email, gán googleId
        if (email != null) {
            existing = findByEmail(email);
            if (existing != null) {
                if(existing.isAdmin())throw new IllegalArgumentException("Tài khoản quản trị dùng trang riêng.");
                if(existing.getGoogleId()!=null&&!existing.getGoogleId().equals(googleId))throw new IllegalArgumentException("Email đã gắn với tài khoản Google khác.");
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
        if(base.length()>48)base=base.substring(0,48);

        String finalUsername = base;
        int count = 1;
        while (findByUsername(finalUsername) != null) {
            finalUsername = base + "_" + count++;
        }

        User newUser = new User();
        newUser.setUsername(finalUsername);
        newUser.setEmail(email);
        newUser.setGoogleId(googleId);
        newUser.setSalt(null);
        newUser.setPasswordHash(null);
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
        User u = findByUsername(username);
        return u != null && u.isAdmin();
    }

    public boolean isAdmin(User user) {
        if (user == null) return false;
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
