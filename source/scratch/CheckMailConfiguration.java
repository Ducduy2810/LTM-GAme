import vn.game.config.Env;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.sql.*;

/** Kiểm tra xác thực SMTP, không gửi email hay in thông tin bí mật. */
public class CheckMailConfiguration {
    public static void main(String[] args) throws Exception {
        String sender = Env.get("GMAIL_USER").trim();
        String password = Env.get("GMAIL_APP_PASSWORD").replaceAll("\\s", "");
        System.out.println("Gmail sender configured: " + !sender.isEmpty());
        System.out.println("App password configured: " + !password.isEmpty());
        System.out.println("App password has expected 16-character length: " + (password.length() == 16));
        Class.forName("org.sqlite.JDBC");
        try (Connection db = DriverManager.getConnection("jdbc:sqlite:file:data/game.db?mode=ro")) {
            try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*), SUM(CASE WHEN reset_sent_at > 0 THEN 1 ELSE 0 END) FROM users")) {
                if (rs.next()) {
                    System.out.println("Registered account count: " + rs.getInt(1));
                    System.out.println("Accounts with OTP send request recorded: " + rs.getInt(2));
                }
            }
            try (PreparedStatement ps = db.prepareStatement("SELECT COUNT(*) FROM users WHERE email = ? COLLATE NOCASE")) {
                ps.setString(1, args.length > 0 ? args[0].trim() : sender);
                try (ResultSet rs = ps.executeQuery()) {
                    System.out.println("Requested address registered: " + (rs.next() && rs.getInt(1) > 0));
                }
            }
        }
        String host = Env.get("SMTP_HOST", "smtp.gmail.com");
        int port = Integer.parseInt(Env.get("SMTP_PORT", "465"));
        System.out.println("Checking SMTP TLS on " + host + ":" + port);
        try (SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket()) {
            socket.setSoTimeout(15000);
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            socket.connect(new InetSocketAddress(host, port), 15000);
            socket.startHandshake();
            System.out.println("TLS handshake OK");
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
            expect(in, 220);
            command(out, in, "EHLO [127.0.0.1]", 250);
            command(out, in, "AUTH LOGIN", 334);
            command(out, in, encode(sender), 334);
            command(out, in, encode(password), 235);
            System.out.println("SMTP authentication OK (no email sent)");
            command(out, in, "QUIT", 221);
        } catch (Exception e) {
            System.out.println("SMTP check failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.exit(1);
        }
    }
    static String encode(String text) { return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)); }
    static void command(BufferedWriter out, BufferedReader in, String command, int expected) throws IOException {
        out.write(command + "\r\n"); out.flush(); expect(in, expected);
    }
    static void expect(BufferedReader in, int expected) throws IOException {
        for (int i = 0; i < 100; i++) {
            String line = in.readLine();
            if (line == null || line.length() < 4) throw new IOException("Missing SMTP reply");
            if (line.charAt(3) == '-') continue;
            int code = Integer.parseInt(line.substring(0, 3));
            if (code != expected) throw new IOException("SMTP status " + code + " (expected " + expected + ")");
            return;
        }
        throw new IOException("SMTP reply too long");
    }
}
