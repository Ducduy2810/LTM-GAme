import vn.game.config.Env;
import vn.game.db.DatabaseManager;
import vn.game.db.UserRepository;
import vn.game.model.User;
import vn.game.server.HttpAndWebSocketServer;
import vn.game.server.SessionManager;
import vn.game.util.JsonUtil;
import vn.game.util.PasswordResetCode;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.*;

/** Chạy bằng test_password_reset.ps1: DB, cấu hình và SMTP hoàn toàn cục bộ. */
public class PasswordResetIntegrationTest {
    private static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private static String baseUrl;
    private static DatabaseManager db;
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (!Files.exists(Path.of(".password-reset-test"))) throw new IllegalStateException("Run via test_password_reset.ps1 in an isolated directory.");
        // Tạo schema cũ trước để xác minh nâng cấp giữ nguyên dữ liệu.
        Class.forName("org.sqlite.JDBC");
        Files.createDirectories(Path.of("data"));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:data/game.db"); Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE users (id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT NOT NULL UNIQUE COLLATE NOCASE, email TEXT UNIQUE COLLATE NOCASE, password_hash TEXT, salt TEXT, google_id TEXT UNIQUE, reset_token TEXT, reset_expires INTEGER NOT NULL DEFAULT 0, role TEXT NOT NULL DEFAULT 'user')");
            st.execute("INSERT INTO users (username, email) VALUES ('legacy', 'legacy@example.com')");
        }
        db = DatabaseManager.getInstance();
        db.init();
        db.close();
        db.init(); // migration phải chạy lại an toàn.
        UserRepository users = new UserRepository(db);
        check(users.findByUsername("legacy") != null, "migration preserves existing account");
        users.register("otp_user", "Player@Example.com", "OldPassword123");
        users.register("other", "other@example.com", "OtherPassword123");
        SessionManager sessions = new SessionManager();
        String oldSession = sessions.createSession("otp_user");
        String unrelatedSession = sessions.createSession("other");

        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(Path.of(args[0]))) { keys.load(input, "local-test-only".toCharArray()); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, "local-test-only".toCharArray());
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), null, null);
        try (FakeSmtp smtp = new FakeSmtp(ssl)) {
            configure(smtp.port(), "local-test-password");
            int port;
            try (ServerSocket probe = new ServerSocket(0)) { port = probe.getLocalPort(); }
            baseUrl = "http://localhost:" + port;
            HttpAndWebSocketServer server = new HttpAndWebSocketServer(port, new File(args[1]), users, sessions, null, null, null);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> running = executor.submit(() -> { try { server.start(); } catch (IOException e) { throw new UncheckedIOException(e); } });
            try {
                for (int i = 0; i < 100; i++) {
                    try (Socket ready = new Socket("localhost", port)) { break; }
                    catch (IOException e) { if (running.isDone()) running.get(); Thread.sleep(20); }
                }
                check(request("GET", "/reset.html", null).statusCode() == 200, "reset page served");
                check(request("GET", "/reset.js", null).statusCode() == 200, "reset script served");
                registrationChecks(smtp, users);
                check(request("GET", "/auth/forgot", null).statusCode() == 405, "forgot requires POST");
                check(post("/auth/forgot", Map.of("email", "bad\r\nRCPT TO:<x@example.com>")).statusCode() == 400, "reject SMTP header injection");
                check(post("/auth/forgot", Map.of("email", 42)).statusCode() == 400, "reject invalid JSON field type");
                HttpResponse<String> known = forgot(" PLAYER@example.com ");
                check(known.statusCode() == 200, "registered email accepted case-insensitively");
                Mail first = smtp.take();
                check(first.recipient.equals("Player@Example.com"), "mail goes to registered address");
                String code = first.code();
                String stored = users.findByUsername("otp_user").getResetToken();
                check(stored.startsWith("otp:") && !stored.equals(code) && !known.body().contains(code), "OTP is hashed and not returned by API");
                check(PasswordResetCode.matches("player@example.com", code, stored), "emailed OTP matches stored hash");
                check(!PasswordResetCode.matches("other@example.com", code, stored), "OTP bound to email");
                HttpResponse<String> unknown = forgot("unknown@example.com");
                check(unknown.statusCode() == 404 && !unknown.body().contains("\"success\":true"), "unregistered email reports error instead of claiming delivery");
                check(forgot("player@example.com").statusCode() == 429, "cooldown reports wait instead of claiming a new delivery");
                check(smtp.messages.isEmpty(), "resend cooldown prevents duplicate email");
                check(reset("other@example.com", code, "NewPassword123").statusCode() == 400, "cannot reset a different account");
                check(post("/auth/reset", Map.of("token", stored, "newPassword", "NewPassword123")).statusCode() == 400, "legacy token cannot bypass OTP");
                check(reset("player@example.com", code, "short").statusCode() == 400, "reject short password without consuming code");
                String wrong = wrongCode(code);
                check(reset("player@example.com", wrong, "NewPassword123").statusCode() == 400, "wrong code rejected");
                String unicodePassword = "MậtKhẩuMới🔑123";
                check(reset("PLAYER@example.com", code, unicodePassword).statusCode() == 200, "valid code resets Unicode password via UTF-8 HTTP");
                check(users.findByUsername("otp_user").verifyPassword(unicodePassword), "new password persisted");
                check(!users.findByUsername("otp_user").verifyPassword("OldPassword123"), "old password no longer works");
                check(sessions.getUsername(oldSession) == null && sessions.getUsername(unrelatedSession) != null, "only target account sessions revoked");
                check(reset("player@example.com", code, "ReplayPassword123").statusCode() == 400, "OTP cannot be reused");
                check(post("/auth/login", Map.of("username", "otp_user", "password", unicodePassword)).statusCode() == 200, "login with new password succeeds");

                allowResend();
                forgot("player@example.com");
                String expired = smtp.take().code();
                sql("UPDATE users SET reset_expires = 1 WHERE username = 'otp_user'");
                check(reset("player@example.com", expired, "NewPassword123").statusCode() == 400, "expired OTP rejected");
                allowResend();
                forgot("player@example.com");
                String limited = smtp.take().code();
                for (int i = 0; i < 5; i++) check(reset("player@example.com", wrongCode(limited), "NewPassword123").statusCode() == 400, "wrong attempt " + (i + 1));
                check(reset("player@example.com", limited, "NewPassword123").statusCode() == 400, "five wrong attempts lock challenge even with correct code");
                allowResend();
                forgot("player@example.com");
                String replacement = smtp.take().code();
                check(reset("player@example.com", replacement, "FreshPassword123").statusCode() == 200, "new challenge clears attempt limit");

                allowResend();
                forgot("player@example.com");
                String oldHash = users.findByUsername("otp_user").getResetToken();
                smtp.take();
                allowResend();
                forgot("player@example.com");
                String replacementCode = smtp.take().code();
                String newHash = users.findByUsername("otp_user").getResetToken();
                check(!oldHash.equals(newHash), "resend replaces previous challenge");
                users.cancelPasswordReset(users.findByUsername("otp_user").getId(), oldHash);
                check(users.findByUsername("otp_user").getResetToken().equals(newHash), "failed older send cannot clear newer code");
                CompletableFuture<HttpResponse<String>> a = CompletableFuture.supplyAsync(() -> uncheckedReset(replacementCode));
                CompletableFuture<HttpResponse<String>> b = CompletableFuture.supplyAsync(() -> uncheckedReset(replacementCode));
                check((a.get().statusCode() == 200 ? 1 : 0) + (b.get().statusCode() == 200 ? 1 : 0) == 1, "concurrent reset consumes OTP only once");

                allowResend();
                smtp.rejectAuthentication = true;
                check(forgot("player@example.com").statusCode() == 503, "SMTP authentication failure reported");
                check(users.findByUsername("otp_user").getResetToken() == null, "failed send clears unusable challenge");
                smtp.rejectAuthentication = false;
                check(forgot("player@example.com").statusCode() == 200, "can retry immediately after delivery failure");
                smtp.take();
                configure(smtp.port(), "");
                check(forgot("player@example.com").statusCode() == 503, "missing credentials reported before issuing OTP");
                // Reload trực tiếp ở endpoint, không gọi Env.load trong test.
                Files.writeString(Path.of(".env"), "GMAIL_USER=sender@example.com\nGMAIL_APP_PASSWORD=local-test-password\nSMTP_HOST=localhost\nSMTP_PORT=" + smtp.port() + "\n", StandardCharsets.UTF_8);
                allowResend();
                check(forgot("player@example.com").statusCode() == 200, "mail configuration change applies without server restart");
                smtp.take();
                Files.writeString(Path.of(".env"), "GMAIL_USER=sender@example.com\nSMTP_HOST=localhost\nSMTP_PORT=" + smtp.port() + "\n", StandardCharsets.UTF_8);
                check(forgot("player@example.com").statusCode() == 503, "removed credential does not retain previous cached password");
                check(smtp.messages.isEmpty(), "no unexpected email sent");
                check(smtp.failure == null, "fake SMTP exchange completed correctly");
                configure(smtp.port(),"local-test-password");
                vn.game.util.SmtpTransport.checkConnection();
                check(smtp.messages.isEmpty(),"implicit TLS connection check sends no email");
                try(FakeSmtp startTls=new FakeSmtp(ssl,true)){
                    Files.writeString(Path.of(".env"),"GMAIL_USER=sender@example.com\nGMAIL_APP_PASSWORD=local-test-password\nSMTP_HOST=localhost\nSMTP_PORT="+startTls.port()+"\nSMTP_SECURITY=starttls\n",StandardCharsets.UTF_8);
                    vn.game.util.SmtpTransport.checkConnection();
                    check(startTls.messages.isEmpty(),"STARTTLS connection check sends no email");
                    new vn.game.util.PasswordResetMailer().sendCode("local@example.com","123456");
                    check(startTls.take().code().equals("123456"),"STARTTLS upgrades before authentication and delivers MIME email");
                    check(startTls.failure==null,"STARTTLS local exchange completed correctly");
                }
                System.out.println("PASS: " + checks + " registration and password reset integration checks (local TLS SMTP, isolated DB).");
            } finally {
                server.stop();
                executor.shutdownNow();
                running.get(5, TimeUnit.SECONDS);
            }
        } finally { db.close(); }
    }

    private static void registrationChecks(FakeSmtp smtp, UserRepository users) throws Exception {
        configure(smtp.port(), "local-test-password");
        check(request("GET", "/auth/register", null).statusCode() == 405, "registration requires POST");
        check(request("GET", "/auth/verify-email", null).statusCode() == 405, "confirmation requires POST");
        check(request("GET", "/verify-email.html", null).statusCode() == 200, "confirmation page served");
        String[] invalidEmails = {"bad-email", "a@", "a@example", "a@@example.com", "a b@example.com",
                ".a@example.com", "a..b@example.com", "a.@example.com", "a@example..com", "a@-example.com",
                "a@example-.com", "a@exam_ple.com", "a@example.c", "a".repeat(65) + "@example.com"};
        for (String email : invalidEmails) check(register("invalid_email_user", email).statusCode() == 400, "invalid email rejected: " + email);
        check(users.findByUsername("invalid_email_user") == null, "invalid email creates no account");
        check(register("otp_user", "new@example.com").statusCode() == 400, "duplicate username rejected");
        check(register("new_user", "PLAYER@example.com").statusCode() == 400, "duplicate email rejected");
        configure(smtp.port(), "");
        check(register("new_user", "new@example.com").statusCode() == 503, "registration requires working mail configuration");
        check(!users.hasPendingRegistration("new_user"), "missing mail config does not reserve account");
        configure(smtp.port(), "local-test-password");
        HttpResponse<String> submitted = register("new_user", " NEW@example.com ");
        check(submitted.statusCode() == 200 && submitted.body().contains("verificationRequired"), "registration requires link confirmation");
        Mail mail = smtp.take();
        String token = mail.token();
        check(mail.recipient.equals("new@example.com") && mail.body.contains("30 phút"), "confirmation email uses correct recipient and expiry");
        check(mail.body.contains(baseUrl + "/verify-email.html#token="), "confirmation uses trusted configured origin");
        String hash = pendingHash("new@example.com");
        check(hash != null && !hash.equals(token) && !submitted.body().contains(token), "token is hashed and never exposed in registration response");
        check(users.findByUsername("new_user") == null && users.hasPendingRegistration("new_user"), "unconfirmed account stays pending");
        check(post("/auth/login", Map.of("username", "new_user", "password", "RegistrationPassword123")).statusCode() == 403, "pending account cannot login");
        check(forgot("new@example.com").statusCode() == 404, "pending account cannot reset password to bypass confirmation");
        check(register("new_user", "new@example.com").statusCode() == 429, "resend cooldown enforced");
        check(register("new_user", "different@example.com").statusCode() == 400, "pending username cannot be reserved for different email");
        check(register("different_user", "new@example.com").statusCode() == 400, "resend cannot change pending username");
        check(post("/auth/register", Map.of("username", "new_user", "email", "new@example.com", "password", "AttackerPassword123")).statusCode() == 400, "resend cannot replace pending password");
        check(smtp.messages.isEmpty(), "blocked requests send no extra mail");
        check(confirm("a".repeat(43)).statusCode() == 400, "unknown token rejected");
        check(confirm("invalid").statusCode() == 400, "malformed token rejected");
        check(request("GET", "/verify-email.html", null).statusCode() == 200 && users.findByUsername("new_user") == null, "page preview GET alone does not activate account");
        check(confirm(token).statusCode() == 200, "valid email link creates account");
        check(users.findByUsername("new_user").verifyPassword("RegistrationPassword123"), "pending password preserved on activation");
        check(pendingHash("new@example.com") == null, "confirmation consumes pending request");
        check(confirm(token).statusCode() == 400, "confirmation link cannot be reused");
        check(post("/auth/login", Map.of("username", "new_user", "password", "RegistrationPassword123")).statusCode() == 200, "confirmed account can login");
        check(register("duplicate_email_user", "new@example.com").statusCode() == 400, "confirmed email cannot be reused");

        register("expiring_user", "expiring@example.com");
        String expired = smtp.take().token();
        sql("UPDATE pending_registrations SET expires = 1 WHERE email = 'expiring@example.com'");
        check(confirm(expired).statusCode() == 400, "expired link rejected");
        check(register("expiring_user", "expiring@example.com").statusCode() == 200, "expired registration can be requested again");
        String oldToken = smtp.take().token();
        String oldHash = pendingHash("expiring@example.com");
        sql("UPDATE pending_registrations SET sent_at = 0 WHERE email = 'expiring@example.com'");
        check(register("expiring_user", "expiring@example.com").statusCode() == 200, "can resend after cooldown");
        String replacement = smtp.take().token();
        check(confirm(oldToken).statusCode() == 400, "resend invalidates previous link");
        users.cancelEmailVerification("expiring@example.com", oldHash);
        check(pendingHash("expiring@example.com") != null, "failed older send cannot cancel replacement");
        CompletableFuture<HttpResponse<String>> a = CompletableFuture.supplyAsync(() -> uncheckedConfirm(replacement));
        CompletableFuture<HttpResponse<String>> b = CompletableFuture.supplyAsync(() -> uncheckedConfirm(replacement));
        check((a.get().statusCode() == 200 ? 1 : 0) + (b.get().statusCode() == 200 ? 1 : 0) == 1, "concurrent activation consumes link once");

        smtp.rejectAuthentication = true;
        check(register("failed_user", "failed@example.com").statusCode() == 503, "SMTP failure reported");
        check(pendingHash("failed@example.com") == null && users.findByUsername("failed_user") == null, "failed send leaves no unusable account");
        smtp.rejectAuthentication = false;
        check(register("failed_user", "failed@example.com").statusCode() == 200, "can retry after send failure");
        smtp.take();
        check(smtp.messages.isEmpty() && smtp.failure == null, "registration mail exchange clean");
    }

    private static String pendingHash(String email) throws SQLException {
        try (PreparedStatement ps = db.getConnection().prepareStatement("SELECT token_hash FROM pending_registrations WHERE email = ?")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }
    private static HttpResponse<String> register(String username, String email) throws Exception {
        return post("/auth/register", Map.of("username", username, "email", email, "password", "RegistrationPassword123"));
    }
    private static HttpResponse<String> confirm(String token) throws Exception {
        return post("/auth/verify-email", Map.of("token", token));
    }
    private static HttpResponse<String> uncheckedConfirm(String token) {
        try { return confirm(token); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void configure(int port, String password) throws IOException {
        Files.writeString(Path.of(".env"), "GMAIL_USER=sender@example.com\nGMAIL_APP_PASSWORD=" + password
                + "\nSMTP_HOST=localhost\nSMTP_PORT=" + port + "\nAPP_BASE_URL=" + (baseUrl == null ? "http://localhost:3000" : baseUrl) + "\n", StandardCharsets.UTF_8);
        Env.load();
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
    private static void sql(String statement) throws SQLException { try (Statement st = db.getConnection().createStatement()) { st.executeUpdate(statement); } }
    private static void allowResend() throws SQLException { sql("UPDATE users SET reset_sent_at = 0 WHERE username = 'otp_user'"); }
    private static String wrongCode(String code) { return code.equals("000000") ? "000001" : "000000"; }
    private static HttpResponse<String> forgot(String email) throws Exception { return post("/auth/forgot", Map.of("email", email)); }
    private static HttpResponse<String> reset(String email, String code, String password) throws Exception {
        return post("/auth/reset", Map.of("email", email, "code", code, "newPassword", password));
    }
    private static HttpResponse<String> uncheckedReset(String code) {
        try { return reset("player@example.com", code, "ConcurrentPassword123"); } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static HttpResponse<String> post(String path, Map<String, Object> body) throws Exception { return request("POST", path, body); }
    private static HttpResponse<String> request(String method, String path, Map<String, Object> body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(10));
        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(JsonUtil.toJson(body)));
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private record Mail(String recipient, String body) {
        String token() {
            Matcher match = Pattern.compile("/verify-email\\.html#token=([A-Za-z0-9_-]{43})").matcher(body);
            if (!match.find()) throw new AssertionError("Confirmation link missing from UTF-8 email");
            return match.group(1);
        }
        String code() {
            Matcher match = Pattern.compile("Mã xác nhận của bạn: ([0-9]{6})").matcher(body);
            if (!match.find()) throw new AssertionError("OTP missing from UTF-8 email body");
            return match.group(1);
        }
    }

    private static class FakeSmtp implements AutoCloseable {
        final ServerSocket listener;
        final SSLContext context;final boolean startTls;
        final BlockingQueue<Mail> messages = new LinkedBlockingQueue<>();
        volatile boolean rejectAuthentication;
        volatile Throwable failure;
        final Thread thread;
        FakeSmtp(SSLContext context) throws IOException {
            this(context,false);
        }
        FakeSmtp(SSLContext context,boolean startTls) throws IOException {
            this.context=context;this.startTls=startTls;
            listener = startTls?new ServerSocket(0,10,InetAddress.getLoopbackAddress()):context.getServerSocketFactory().createServerSocket(0, 10, InetAddress.getLoopbackAddress());
            thread = new Thread(() -> {
                while (!listener.isClosed()) {
                    try (Socket socket = listener.accept()) { exchange(socket); }
                    catch (Exception e) { if (!listener.isClosed()) failure = e; }
                }
            });
            thread.setDaemon(true);
            thread.start();
        }
        int port() { return listener.getLocalPort(); }
        Mail take() throws InterruptedException {
            Mail mail = messages.poll(5, TimeUnit.SECONDS);
            if (mail == null) throw new AssertionError("No email received", failure);
            return mail;
        }
        void exchange(Socket socket) throws IOException {
            socket.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
            reply(out, "220 localhost test SMTP");
            require(in, "EHLO [127.0.0.1]");
            reply(out, "250-localhost\r\n250 AUTH LOGIN");
            if(startTls){
                require(in,"STARTTLS");reply(out,"220 upgrade TLS");
                SSLSocket tls=(SSLSocket)context.getSocketFactory().createSocket(socket,"localhost",socket.getPort(),true);tls.setUseClientMode(false);tls.startHandshake();
                in=new BufferedReader(new InputStreamReader(tls.getInputStream(),StandardCharsets.US_ASCII));out=new BufferedWriter(new OutputStreamWriter(tls.getOutputStream(),StandardCharsets.US_ASCII));
                require(in,"EHLO [127.0.0.1]");reply(out,"250 AUTH LOGIN");
            }
            require(in, "AUTH LOGIN"); reply(out, "334 username");
            require(in, Base64.getEncoder().encodeToString("sender@example.com".getBytes(StandardCharsets.UTF_8)));
            reply(out, "334 password");
            require(in, Base64.getEncoder().encodeToString("local-test-password".getBytes(StandardCharsets.UTF_8)));
            if (rejectAuthentication) { reply(out, "535 Authentication failed"); return; }
            reply(out, "235 authenticated");
            String command=in.readLine();if("QUIT".equals(command)){reply(out,"221 bye");return;}
            if(!"MAIL FROM:<sender@example.com>".equals(command))throw new IOException("Unexpected sender command");reply(out, "250 sender ok");
            String recipient = in.readLine();
            if (recipient == null || !recipient.startsWith("RCPT TO:<") || !recipient.endsWith(">")) throw new IOException("Invalid recipient command");
            reply(out, "250 recipient ok");
            require(in, "DATA"); reply(out, "354 send message");
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null && !line.equals(".")) data.append(line).append("\r\n");
            String message = data.toString();
            if (!message.contains("Content-Type: text/plain; charset=UTF-8") || !message.contains("Subject: =?UTF-8?B?")) throw new IOException("Missing MIME headers");
            String body = new String(Base64.getMimeDecoder().decode(message.substring(message.indexOf("\r\n\r\n") + 4)), StandardCharsets.UTF_8);
            messages.add(new Mail(recipient.substring(9, recipient.length() - 1), body));
            reply(out, "250 queued");
            require(in, "QUIT"); reply(out, "221 bye");
        }
        void require(BufferedReader in, String expected) throws IOException {
            if (!expected.equals(in.readLine())) throw new IOException("Unexpected SMTP command");
        }
        void reply(BufferedWriter out, String value) throws IOException { out.write(value + "\r\n"); out.flush(); }
        public void close() throws Exception { listener.close(); thread.join(5000); }
    }
}
