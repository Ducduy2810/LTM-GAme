import java.sql.*;

/** Chỉ đọc tên đăng nhập và email, không truy xuất mật khẩu hoặc mã khôi phục. */
public class LookupAccountEmail {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Provide username");
        Class.forName("org.sqlite.JDBC");
        try (Connection db = DriverManager.getConnection("jdbc:sqlite:file:data/game.db?mode=ro");
             PreparedStatement query = db.prepareStatement(
                     "SELECT username, email FROM users WHERE username = ? COLLATE NOCASE LIMIT 1")) {
            query.setString(1, args[0]);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) {
                    System.out.println("Account not found");
                    return;
                }
                System.out.println("Username: " + row.getString("username"));
                System.out.println("Registered email: " + row.getString("email"));
            }
        }
    }
}
