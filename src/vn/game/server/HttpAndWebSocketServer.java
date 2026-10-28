package vn.game.server;

import vn.game.config.Env;
import vn.game.db.UserRepository;
import vn.game.games.ArenaGame;
import vn.game.games.SurvivalGame;
import vn.game.games.WheelGame;
import vn.game.model.User;
import vn.game.util.JsonUtil;
import vn.game.util.PasswordResetCode;
import vn.game.util.PasswordResetMailer;
import vn.game.util.EmailVerification;

import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Máy chủ hợp nhất TCP ServerSocket:
 * - Xử lý HTTP REST API (Login, Register, Logout, Me, Forgot Password, Reset Password)
 * - Phục vụ Static Web Files (HTML, CSS, JS)
 * - Xử lý nâng cấp giao thức WebSocket RFC 6455 trên cùng một cổng (Port 3000)
 */
public class HttpAndWebSocketServer {

    private final int port;
    private final File publicDir;
    private final UserRepository userRepository;
    private final SessionManager sessionManager;
    private final ArenaGame arenaGame;
    private final WheelGame wheelGame;
    private final SurvivalGame survivalGame;
    private final PasswordResetMailer resetMailer = new PasswordResetMailer();
    private final ExecutorService threadPool = Executors.newCachedThreadPool();
    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private final Set<WebSocketClient> connections = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
    private static final long HEARTBEAT_INTERVAL_MS = 10_000;
    private static final long HEARTBEAT_TIMEOUT_MS = 20_000;

    public HttpAndWebSocketServer(int port, File publicDir, UserRepository userRepository,
                                  SessionManager sessionManager, ArenaGame arenaGame,
                                  WheelGame wheelGame, SurvivalGame survivalGame) {
        this.port = port;
        this.publicDir = publicDir;
        this.userRepository = userRepository;
        this.sessionManager = sessionManager;
        this.arenaGame = arenaGame;
        this.wheelGame = wheelGame;
        this.survivalGame = survivalGame;
    }

    public void start() throws IOException {
        // Bind rõ ràng 0.0.0.0 để chấp nhận kết nối từ mạng LAN
        serverSocket = new ServerSocket(port, 50, java.net.InetAddress.getByName("0.0.0.0"));
        running = true;
        heartbeat.scheduleAtFixedRate(this::checkConnections, 1, 1, TimeUnit.SECONDS);
        System.out.println("🚀 [Java Server] Đang lắng nghe kết nối tại cổng " + port + " (0.0.0.0)");

        while (running) {
            try {
                Socket socket = serverSocket.accept();
                threadPool.submit(() -> handleConnection(socket));
            } catch (SocketException e) {
                if (!running) break;
            }
        }
    }

    public int getPort() { return running && serverSocket != null ? serverSocket.getLocalPort() : port; }

    public void stop() {
        running = false;
        heartbeat.shutdownNow();
        connections.forEach(WebSocketClient::close);
        connections.clear();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
        threadPool.shutdownNow();
    }

    private void checkConnections() {
        if (!running) return;
        long now = System.nanoTime();
        for (WebSocketClient client : connections) {
            if (client.isClosed() || client.heartbeatExpired(now, HEARTBEAT_TIMEOUT_MS)) {
                client.close(); // Đánh thức luồng đọc để xử lý rời phòng đúng một lần.
                continue;
            }
            byte[] ping = client.prepareHeartbeat(now, HEARTBEAT_INTERVAL_MS);
            if (ping != null && !threadPool.isShutdown()) threadPool.execute(() -> client.sendPing(ping));
        }
    }

    private void handleConnection(Socket socket) {
        try {
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            // Đọc HTTP Header dòng đầu
            String initialLine = readHttpLine(in);
            if (initialLine == null || initialLine.trim().isEmpty()) {
                socket.close();
                return;
            }

            String[] parts = initialLine.split(" ");
            if (parts.length < 2) {
                socket.close();
                return;
            }

            String method = parts[0].toUpperCase();
            String path = parts[1];

            // Đọc các Headers
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readHttpLine(in)) != null && !line.isEmpty()) {
                int colonIdx = line.indexOf(':');
                if (colonIdx > 0) {
                    String k = line.substring(0, colonIdx).trim().toLowerCase();
                    String v = line.substring(colonIdx + 1).trim();
                    headers.put(k, v);
                }
            }

            // Lấy Session ID từ Cookie nếu có
            String sessionId = extractSessionId(headers.get("cookie"));

            // Kiểm tra xem đây có phải là yêu cầu nâng cấp lên WebSocket không
            String upgrade = headers.get("upgrade");
            if ("websocket".equalsIgnoreCase(upgrade)) {
                handleWebSocketUpgrade(socket, in, out, headers, path, sessionId);
                return;
            }

            // Xử lý các HTTP Request thông thường (REST API hoặc File tĩnh)
            handleHttpRequest(socket, out, in, method, path, headers, sessionId);

        } catch (Exception e) {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private static String readHttpLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int value;
        while ((value = in.read()) != -1) {
            if (value == '\n') return line.toString(StandardCharsets.ISO_8859_1).replaceFirst("\\r$", "");
            if (line.size() >= 8192) throw new IOException("HTTP header quá dài.");
            line.write(value);
        }
        return line.size() == 0 ? null : line.toString(StandardCharsets.ISO_8859_1);
    }

    private String extractSessionId(String cookieHeader) {
        if (cookieHeader == null) return null;
        String[] cookies = cookieHeader.split(";");
        for (String c : cookies) {
            c = c.trim();
            if (c.startsWith("session_id=")) {
                return c.substring("session_id=".length()).trim();
            }
        }
        return null;
    }

    // ==========================================
    // 1. XỬ LÝ WEBSOCKET RFC 6455
    // ==========================================
    private void handleWebSocketUpgrade(Socket socket, InputStream in, OutputStream out,
                                       Map<String, String> headers, String path, String sessionId) {
        WebSocketClient client = null;
        try {
            String key = headers.get("sec-websocket-key");
            if (key == null) {
                socket.close();
                return;
            }

            // Tính toán mã Sec-WebSocket-Accept
            String magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest((key + magic).getBytes(StandardCharsets.UTF_8));
            String accept = Base64.getEncoder().encodeToString(hash);

            // Gửi phản hồi HTTP 101 Switching Protocols
            String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.flush();

            String username = sessionManager.getUsername(sessionId);
            client = new WebSocketClient(socket, in, out, username);

            if (username == null) {
                Map<String, Object> err = new HashMap<>();
                err.put("type", "auth_error");
                err.put("message", "Bạn phải đăng nhập để chơi game!");
                client.send(JsonUtil.toJson(err));
                Thread.sleep(200);
                client.close();
                return;
            }

            // Vòng lặp lắng nghe các Frame từ WebSocket Client
            connections.add(client);
            DataInputStream dataIn = new DataInputStream(in);
            while (!client.isClosed()) {
                int b0 = dataIn.read();
                if (b0 == -1) break;

                int opcode = b0 & 0x0F;
                int b1 = dataIn.read();
                if (b1 == -1) break;

                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;

                if (len == 126) {
                    len = dataIn.readUnsignedShort();
                } else if (len == 127) {
                    len = dataIn.readLong();
                }
                if (len < 0 || len > 1024 * 1024 || (opcode >= 0x8 && len > 125)) throw new IOException("WebSocket frame không hợp lệ.");

                byte[] mask = new byte[4];
                if (masked) {
                    dataIn.readFully(mask);
                }

                byte[] payload = new byte[(int) len];
                dataIn.readFully(payload);

                if (masked) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] = (byte) (payload[i] ^ mask[i % 4]);
                    }
                }

                // Opcode 0x8 là Close Frame
                if (opcode == 0x8) {
                    break;
                }

                // Opcode 0x9 là Ping
                if (opcode == 0x9) {
                    client.sendPong(payload);
                    continue;
                }
                if (opcode == 0xA) {
                    client.receivePong(payload);
                    continue;
                }

                // Opcode 0x1 là Text Frame (JSON Message)
                if (opcode == 0x1) {
                    String msgText = new String(payload, StandardCharsets.UTF_8);
                    routeWebSocketMessage(client, msgText);
                }
            }

        } catch (Exception e) {
            // Client ngắt kết nối
        } finally {
            if (client != null) {
                connections.remove(client);
                arenaGame.handleDisconnect(client);
                wheelGame.handleDisconnect(client);
                survivalGame.handleDisconnect(client);
                client.close();
            }
        }
    }

    private void routeWebSocketMessage(WebSocketClient client, String text) {
        try {
            Map<String, Object> req = JsonUtil.parseObject(text);
            String type = (String) req.get("type");
            if (type == null) return;

        switch (type) {
            case "get_rooms":
                arenaGame.registerLobbyClient(client);
                break;
            case "create_room":
                arenaGame.handleCreateRoom(client, req);
                break;
            case "join_room": {
                String roomCode = (String) req.get("roomCode");
                arenaGame.handleJoinRoom(client, roomCode);
                break;
            }
            case "leave_room":
                arenaGame.handleLeaveRoom(client);
                break;
            case "toggle_ready":
                arenaGame.handleToggleReady(client);
                break;
            case "close_arena_room":
            case "admin_close_room": {
                String roomCode = (String) req.get("roomCode");
                arenaGame.handleAdminCloseRoom(client, roomCode);
                break;
            }
            case "join_arena":
                arenaGame.handleJoin(client);
                break;
            case "start_arena_game":
                arenaGame.handleStartGame(client);
                break;
            case "select_arena_main_player":
                arenaGame.handleSelectMainPlayer(client, req);
                break;
            case "submit_arena_answer":
                if (req.get("answer") instanceof Number) {
                    arenaGame.handleSubmitAnswer(client, ((Number) req.get("answer")).intValue());
                }
                break;
            case "arena_life_trust":
                arenaGame.handleLifeTrust(client);
                break;
            case "arena_life_ask": {
                String lifeTarget = (String) req.get("targetUsername");
                if (lifeTarget != null) arenaGame.handleLifeAsk(client, lifeTarget);
                break;
            }
            case "arena_life_survey":
                arenaGame.handleLifeSurvey(client);
                break;
            case "arena_pause_game":
                arenaGame.handlePauseGame(client);
                break;
            case "arena_resume_game":
                arenaGame.handleResumeGame(client);
                break;
            case "get_leaderboard":
                arenaGame.handleGetLeaderboard(client);
                break;
            case "admin_clear_leaderboard":
                arenaGame.handleAdminClearLeaderboard(client);
                break;
            case "get_wheel_rooms":
                wheelGame.registerLobbyClient(client);
                break;
            case "create_wheel_room":
                wheelGame.handleCreateRoom(client, req);
                break;
            case "join_wheel_room": {
                String roomCode = (String) req.get("roomCode");
                wheelGame.handleJoinRoom(client, roomCode);
                break;
            }
            case "leave_wheel_room":
                wheelGame.handleLeaveRoom(client);
                break;
            case "toggle_wheel_ready":
                wheelGame.handleToggleReady(client);
                break;
            case "admin_close_wheel_room": {
                String roomCode = (String) req.get("roomCode");
                wheelGame.handleAdminCloseRoom(client, roomCode);
                break;
            }
            case "join_wheel":
                wheelGame.handleJoin(client);
                break;
            case "start_wheel_game":
                wheelGame.handleStartGame(client);
                break;
            case "wheel_spin":
                wheelGame.handleSpin(client);
                break;
            case "wheel_guess":
                String letter = (String) req.get("letter");
                if (letter != null) {
                    wheelGame.handleGuess(client, letter);
                }
                break;
            case "wheel_solve_phrase":
                String phrase = (String) req.get("phrase");
                if (phrase != null) {
                    wheelGame.handleSolvePhrase(client, phrase);
                }
                break;
            case "get_survival_rooms":
                survivalGame.registerLobbyClient(client);
                break;
            case "create_survival_room":
                survivalGame.handleCreateRoom(client, req);
                break;
            case "join_survival_room": {
                String roomCode = (String) req.get("roomCode");
                survivalGame.handleJoinRoom(client, roomCode);
                break;
            }
            case "leave_survival_room":
                survivalGame.handleLeaveRoom(client);
                break;
            case "toggle_survival_ready":
                survivalGame.handleToggleReady(client);
                break;
            case "start_survival_game":
                survivalGame.handleStartGame(client);
                break;
            case "submit_survival_answer":
                if (req.get("answer") instanceof Number) {
                    survivalGame.handleSubmitAnswer(client, ((Number) req.get("answer")).intValue());
                }
                break;
            case "pause_survival_game":
                survivalGame.handlePauseGame(client);
                break;
            case "resume_survival_game":
                survivalGame.handleResumeGame(client);
                break;
            case "close_survival_room":
            case "admin_close_survival_room": {
                String roomCode = (String) req.get("roomCode");
                survivalGame.handleAdminCloseRoom(client, roomCode);
                break;
            }
        }
        } catch (Exception e) {
            System.err.println("❌ Lỗi xử lý WebSocket message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ==========================================
    // 2. XỬ LÝ HTTP REST API & TÀI NGUYÊN TĨNH
    // ==========================================
    private void handleHttpRequest(Socket socket, OutputStream out, InputStream in,
                                  String method, String path, Map<String, String> headers, String sessionId) throws IOException {
        String query = "";
        int qIdx = path.indexOf('?');
        if (qIdx != -1) {
            query = path.substring(qIdx + 1);
            path = path.substring(0, qIdx);
        }

        // Đọc Body nếu có
        int contentLength = 0;
        String clStr = headers.get("content-length");
        if (clStr != null) {
            try { contentLength = Integer.parseInt(clStr); } catch (NumberFormatException ignored) {}
        }

        if (contentLength < 0 || contentLength > 65536) {
            sendJson(out, 400, Map.of("error", "Nội dung yêu cầu quá lớn hoặc không hợp lệ."), null);
            socket.close();
            return;
        }
        // Content-Length tính theo byte, không phải số ký tự (mật khẩu có thể có dấu).
        byte[] bodyBytes = in.readNBytes(contentLength);
        if (bodyBytes.length != contentLength) throw new EOFException("Thiếu nội dung HTTP.");
        String body = new String(bodyBytes, StandardCharsets.UTF_8);

        // --- LEADERBOARD API ---
        if ("GET".equals(method) && "/api/leaderboard".equals(path)) {
            Map<String, Object> lb = new HashMap<>();
            lb.put("leaderboard", vn.game.model.Leaderboard.getTop(20));
            sendJson(out, 200, lb, null);
            socket.close();
            return;
        }

        if ("POST".equals(method) && "/api/leaderboard/clear".equals(path)) {
            String username = sessionManager.getUsername(sessionId);
            if (username == null || !userRepository.isAdmin(username)) {
                sendJson(out, 403, Map.of("error", "Chỉ Super Admin mới có quyền xóa bảng xếp hạng!"), null);
                socket.close();
                return;
            }
            vn.game.model.Leaderboard.clear();
            arenaGame.broadcastLeaderboard();
            sendJson(out, 200, Map.of("success", true, "message", "Đã xóa toàn bộ bảng xếp hạng thành công!"), null);
            socket.close();
            return;
        }

        // --- CÁC ROUTE REST API ---
        if (path.startsWith("/auth/")) {
            handleAuthApi(out, method, path, query, body, sessionId, headers);
            socket.close();
            return;
        }

        // --- PHỤC VỤ FILE TĨNH (HTML, CSS, JS) ---
        if ("GET".equals(method)) {
            serveStaticFile(out, path);
        } else {
            sendResponse(out, 405, "text/plain", "Method Not Allowed".getBytes(StandardCharsets.UTF_8), null);
        }
        socket.close();
    }

    private void handleAuthApi(OutputStream out, String method, String path, String query, String body, String sessionId, Map<String, String> headers) throws IOException {
        Map<String, Object> req = JsonUtil.parseObject(body);

        if (("/auth/forgot".equals(path) || "/auth/reset".equals(path)
                || "/auth/register".equals(path) || "/auth/verify-email".equals(path)) && !"POST".equals(method)) {
            sendJson(out, 405, Map.of("error", "Vui lòng gửi yêu cầu bằng POST."), null);
            return;
        }

        switch (path) {
            case "/auth/register": {
                String username = requestText(req, "username").trim();
                String email = requestText(req, "email").trim().toLowerCase(Locale.ROOT);
                String password = requestText(req, "password");
                if (username.isEmpty() || username.length() > 64 || !PasswordResetMailer.isEmail(email)) {
                    sendJson(out, 400, Map.of("error", "Hãy nhập tên đăng nhập (tối đa 64 ký tự) và email hợp lệ."), null);
                    return;
                }
                if (password.isBlank() || password.length() < 8 || password.length() > 128) {
                    sendJson(out, 400, Map.of("error", "Mật khẩu phải có từ 8 đến 128 ký tự."), null);
                    return;
                }

                Env.load();
                if (!resetMailer.isConfigured()) {
                    sendJson(out, 503, Map.of("error", "Chức năng gửi email chưa được cấu hình. Vui lòng liên hệ quản trị viên."), null);
                    return;
                }
                String baseUrl;
                try {
                    URI configured = URI.create(Env.get("APP_BASE_URL", "http://localhost:" + port));
                    if ((!"http".equals(configured.getScheme()) && !"https".equals(configured.getScheme()))
                            || configured.getHost() == null || configured.getUserInfo() != null
                            || configured.getQuery() != null || configured.getFragment() != null
                            || (configured.getPath() != null && !configured.getPath().isEmpty() && !"/".equals(configured.getPath()))) {
                        throw new IllegalArgumentException();
                    }
                    baseUrl = configured.toString().replaceAll("/+$", "");
                } catch (IllegalArgumentException e) {
                    sendJson(out, 503, Map.of("error", "APP_BASE_URL chưa hợp lệ. Vui lòng liên hệ quản trị viên."), null);
                    return;
                }
                String token = EmailVerification.generateToken();
                String hash = EmailVerification.hash(token);
                try {
                    if (!userRepository.issueEmailVerification(username, email, password, hash, System.currentTimeMillis())) {
                        sendJson(out, 429, Map.of("error", "Vui lòng chờ 60 giây trước khi gửi lại liên kết xác nhận.", "retryAfter", 60), null);
                        return;
                    }
                } catch (Exception e) {
                    sendJson(out, 400, Map.of("error", e.getMessage()), null);
                    return;
                }
                try {
                    resetMailer.sendRegistrationLink(email, baseUrl + "/verify-email.html#token=" + token);
                } catch (IOException e) {
                    userRepository.cancelEmailVerification(email, hash);
                    System.err.println("Không gửi được email xác nhận đăng ký: " + e.getMessage());
                    sendJson(out, 503, Map.of("error", "Không gửi được email xác nhận. Hãy kiểm tra lại email hoặc thử lại sau."), null);
                    return;
                }
                System.out.println("[Registration] SMTP đã tiếp nhận email chứa liên kết xác nhận.");
                sendJson(out, 200, Map.of("success", true, "verificationRequired", true, "retryAfter", 60,
                        "message", "Đã gửi liên kết xác nhận. Hãy mở email và bấm liên kết trong 30 phút để kích hoạt tài khoản. Kiểm tra cả thư rác. Nếu chưa nhận được, chờ 60 giây rồi đăng ký lại với cùng thông tin để gửi lại."), null);
                break;
            }

            case "/auth/verify-email": {
                String token = requestText(req, "token");
                if (!token.matches("[A-Za-z0-9_-]{43}")) {
                    sendJson(out, 400, Map.of("error", "Liên kết xác nhận không hợp lệ."), null);
                    return;
                }
                try {
                    User user = userRepository.confirmEmailVerification(EmailVerification.hash(token), System.currentTimeMillis());
                    if (user == null) {
                        sendJson(out, 400, Map.of("error", "Liên kết đã hết hạn, đã được sử dụng hoặc đã được thay bằng liên kết mới. Hãy đăng ký lại để nhận email mới."), null);
                        return;
                    }
                    sendJson(out, 200, Map.of("success", true, "message", "Email đã được xác nhận. Tài khoản đã được kích hoạt, bạn có thể đăng nhập."), null);
                } catch (Exception e) {
                    sendJson(out, 400, Map.of("error", e.getMessage()), null);
                }
                break;
            }

            case "/auth/login": {
                String username = (String) req.get("username");
                String password = (String) req.get("password");

                User user = userRepository.findByUsername(username);
                if (user == null && userRepository.hasPendingRegistration(username)) {
                    sendJson(out, 403, Map.of("error", "Tài khoản đang chờ xác nhận email. Hãy mở email đăng ký và bấm liên kết để kích hoạt."), null);
                    return;
                }
                if (user == null || !user.verifyPassword(password)) {
                    sendJson(out, 400, Map.of("error", "Sai tên đăng nhập hoặc mật khẩu!"), null);
                    return;
                }

                String newSessionId = sessionManager.createSession(user.getUsername());
                String cookieHeader = "session_id=" + newSessionId + "; Path=/; HttpOnly; SameSite=Lax";
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", true);
                resp.put("user", Map.of("username", user.getUsername()));

                sendJson(out, 200, resp, cookieHeader);
                break;
            }

            case "/auth/logout": {
                if (sessionId != null) {
                    sessionManager.removeSession(sessionId);
                }
                String clearCookie = "session_id=; Path=/; Max-Age=0";
                sendJson(out, 200, Map.of("success", true), clearCookie);
                break;
            }

            case "/auth/me": {
                String username = sessionManager.getUsername(sessionId);
                Map<String, Object> resp = new HashMap<>();
                if (username != null) {
                    resp.put("loggedIn", true);
                    User u = userRepository.findByUsername(username);
                    boolean isAdmin = userRepository.isAdmin(username);
                    Map<String, Object> userMap = new HashMap<>();
                    userMap.put("username", username);
                    userMap.put("isAdmin", isAdmin);
                    userMap.put("email", u != null && u.getEmail() != null ? u.getEmail() : "");
                    resp.put("user", userMap);
                } else {
                    resp.put("loggedIn", false);
                }
                sendJson(out, 200, resp, null);
                break;
            }

            case "/auth/forgot": {
                String email = requestText(req, "email").trim().toLowerCase(Locale.ROOT);
                if (!PasswordResetMailer.isEmail(email)) {
                    sendJson(out, 400, Map.of("error", "Vui lòng nhập email hợp lệ."), null);
                    return;
                }
                if (userRepository.findByEmail(email) == null) {
                    sendJson(out, 404, Map.of("error", "Email này chưa được đăng ký. Hãy nhập email đã gắn với tài khoản của bạn hoặc đăng ký tài khoản mới."), null);
                    return;
                }
                // Người quản trị có thể vừa cập nhật mật khẩu ứng dụng trong .env.
                Env.load();
                if (!resetMailer.isConfigured()) {
                    sendJson(out, 503, Map.of("error", "Chức năng gửi email chưa được cấu hình. Vui lòng liên hệ quản trị viên."), null);
                    return;
                }
                String code = PasswordResetCode.generate();
                String hash = PasswordResetCode.hash(email, code);
                User user = userRepository.issuePasswordReset(email, hash, System.currentTimeMillis());
                if (user == null) {
                    sendJson(out, 429, Map.of("error", "Bạn vừa yêu cầu mã. Vui lòng chờ 60 giây trước khi gửi lại và kiểm tra cả thư rác.",
                            "retryAfter", PasswordResetCode.RESEND_AFTER_MS / 1000), null);
                    return;
                }
                try {
                    resetMailer.sendCode(user.getEmail(), code);
                } catch (IOException e) {
                    userRepository.cancelPasswordReset(user.getId(), hash);
                    System.err.println("Không gửi được email khôi phục: " + e.getMessage());
                    sendJson(out, 503, Map.of("error", "Chưa gửi được email. Vui lòng thử lại hoặc liên hệ quản trị viên kiểm tra cấu hình gửi mail."), null);
                    return;
                }
                System.out.println("[Password reset] SMTP đã tiếp nhận email gửi mã xác nhận.");
                sendJson(out, 200, Map.of("success", true,
                        "message", "Đã gửi mã xác nhận đến email đăng ký. Mã có hiệu lực 10 phút; vui lòng kiểm tra cả thư rác.",
                        "retryAfter", PasswordResetCode.RESEND_AFTER_MS / 1000), null);
                break;
            }

            case "/auth/reset": {
                String email = requestText(req, "email").trim().toLowerCase(Locale.ROOT);
                String code = requestText(req, "code").trim();
                String newPassword = requestText(req, "newPassword");
                if (!PasswordResetMailer.isEmail(email) || !code.matches("[0-9]{6}")) {
                    sendJson(out, 400, Map.of("error", "Vui lòng nhập email và mã xác nhận gồm 6 chữ số."), null);
                    return;
                }
                if (newPassword.isBlank() || newPassword.length() < 8 || newPassword.length() > 128) {
                    sendJson(out, 400, Map.of("error", "Mật khẩu mới phải có từ 8 đến 128 ký tự."), null);
                    return;
                }
                String username = userRepository.resetPasswordWithCode(email, code, newPassword, System.currentTimeMillis());
                if (username == null) {
                    sendJson(out, 400, Map.of("error", "Mã không đúng, đã hết hạn hoặc đã nhập sai quá 5 lần. Vui lòng yêu cầu mã mới nếu cần."), null);
                    return;
                }
                sessionManager.removeUserSessions(username);
                sendJson(out, 200, Map.of("success", true, "message", "Đã đổi mật khẩu thành công! Hãy đăng nhập bằng mật khẩu mới."),
                        "session_id=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
                break;
            }

            // case "/auth/google": {
            //     String clientId = Env.get("GOOGLE_CLIENT_ID");
            //     if (clientId.isEmpty()) {
            //         sendResponse(out, 400, "text/html; charset=utf-8",
            //                 "<h3>Lỗi: Chưa cấu hình GOOGLE_CLIENT_ID trong file .env!</h3><br><a href='/'>Quay lại trang chủ</a>".getBytes(StandardCharsets.UTF_8), null);
            //         return;
            //     }
            //     String redirectUri = URLEncoder.encode("http://localhost:" + port + "/auth/google/callback", StandardCharsets.UTF_8);
            //     String scope = URLEncoder.encode("openid email profile", StandardCharsets.UTF_8);
            //     String googleAuthUrl = "https://accounts.google.com/o/oauth2/v2/auth" +
            //             "?client_id=" + clientId +
            //             "&redirect_uri=" + redirectUri +
            //             "&response_type=code" +
            //             "&scope=" + scope +
            //             "&prompt=select_account";
            //     sendRedirect(out, googleAuthUrl, null);
            //     break;
            // }


            case "/auth/google": {
                String clientId = Env.get("GOOGLE_CLIENT_ID");
                if (clientId.isEmpty()) {
                    sendResponse(out, 400, "text/html; charset=utf-8",
                            "<h3>Lỗi: Chưa cấu hình GOOGLE_CLIENT_ID trong file .env!</h3><br><a href='/'>Quay lại trang chủ</a>".getBytes(StandardCharsets.UTF_8), null);
                    return;
                }
                
                // ĐÃ SỬA: Lấy URI động
                String dynamicUri = getDynamicRedirectUri(headers);
                String redirectUri = URLEncoder.encode(dynamicUri, StandardCharsets.UTF_8);
                
                String scope = URLEncoder.encode("openid email profile", StandardCharsets.UTF_8);
                String googleAuthUrl = "https://accounts.google.com/o/oauth2/v2/auth" +
                        "?client_id=" + clientId +
                        "&redirect_uri=" + redirectUri +
                        "&response_type=code" +
                        "&scope=" + scope +
                        "&prompt=select_account";
                sendRedirect(out, googleAuthUrl, null);
                break;
            }

            case "/auth/google/callback": {
                String code = null;
                if (query != null && !query.isEmpty()) {
                    for (String param : query.split("&")) {
                        String[] kv = param.split("=", 2);
                        if ("code".equals(kv[0]) && kv.length > 1) {
                            code = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                        }
                    }
                }

                if (code == null) {
                    sendRedirect(out, "/?error=google_auth_failed", null);
                    return;
                }

                String clientId = Env.get("GOOGLE_CLIENT_ID");
                String clientSecret = Env.get("GOOGLE_CLIENT_SECRET");
                // String redirectUri = "http://localhost:" + port + "/auth/google/callback";
                // ĐÃ SỬA: Lấy URI động
                String redirectUri = getDynamicRedirectUri(headers);

                try {
                    // 1. Gửi request POST đổi authorization code lấy access_token
                    HttpClient httpClient = HttpClient.newHttpClient();
                    String tokenBody = "code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                            + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                            + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)
                            + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                            + "&grant_type=authorization_code";

                    HttpRequest tokenReq = HttpRequest.newBuilder()
                            .uri(URI.create("https://oauth2.googleapis.com/token"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(tokenBody))
                            .build();

                    HttpResponse<String> tokenResp = httpClient.send(tokenReq, HttpResponse.BodyHandlers.ofString());
                    Map<String, Object> tokenJson = JsonUtil.parseObject(tokenResp.body());
                    String accessToken = (String) tokenJson.get("access_token");

                    if (accessToken == null) {
                        System.err.println("Lỗi đổi token Google: " + tokenResp.body());
                        sendRedirect(out, "/?error=token_error", null);
                        return;
                    }

                    // 2. Lấy thông tin user từ Google
                    HttpRequest userReq = HttpRequest.newBuilder()
                            .uri(URI.create("https://www.googleapis.com/oauth2/v3/userinfo"))
                            .header("Authorization", "Bearer " + accessToken)
                            .GET()
                            .build();

                    HttpResponse<String> userResp = httpClient.send(userReq, HttpResponse.BodyHandlers.ofString());
                    Map<String, Object> userJson = JsonUtil.parseObject(userResp.body());
                    String googleId = (String) userJson.get("sub");
                    String email = (String) userJson.get("email");
                    String name = (String) userJson.get("name");

                    // 3. Đăng ký hoặc lấy user trong UserRepository
                    User user = userRepository.registerOrGetGoogleUser(googleId, email, name);
                    String newSessionId = sessionManager.createSession(user.getUsername());
                    String cookieHeader = "session_id=" + newSessionId + "; Path=/; HttpOnly; SameSite=Lax";

                    sendRedirect(out, "/", cookieHeader);
                } catch (Exception e) {
                    System.err.println("Lỗi xử lý Google Callback: " + e.getMessage());
                    sendRedirect(out, "/?error=oauth_exception", null);
                }
                break;
            }

            default:
                sendJson(out, 404, Map.of("error", "API endpoint not found"), null);
                break;
        }
    }

    private static String requestText(Map<String, Object> req, String key) {
        return req.get(key) instanceof String ? (String) req.get(key) : "";
    }

    // Hàm tự động ghép URL động dựa theo Header của Client / Ngrok
    private String getDynamicRedirectUri(Map<String, String> headers) {
        String proto = headers.getOrDefault("x-forwarded-proto", "http");
        String host = headers.getOrDefault("x-forwarded-host", headers.get("host"));
        if (host == null || host.isEmpty()) {
            host = "localhost:" + port;
        }
        return proto + "://" + host + "/auth/google/callback";
    }

    private void serveStaticFile(OutputStream out, String path) throws IOException {
        if ("/".equals(path)) path = "/index.html";
        File file = new File(publicDir, path);

        if (!file.exists() || file.isDirectory()) {
            sendResponse(out, 404, "text/plain", "404 Not Found".getBytes(StandardCharsets.UTF_8), null);
            return;
        }

        String mime = getMimeType(file.getName());
        byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
        sendResponse(out, 200, mime, bytes, null);
    }

    private String getMimeType(String name) {
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private void sendJson(OutputStream out, int status, Map<String, Object> map, String setCookie) throws IOException {
        byte[] bytes = JsonUtil.toJson(map).getBytes(StandardCharsets.UTF_8);
        sendResponse(out, status, "application/json; charset=utf-8", bytes, setCookie);
    }

    private void sendResponse(OutputStream out, int status, String mime, byte[] body, String setCookie) throws IOException {
        String statusText = switch (status) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 429 -> "Too Many Requests";
            case 503 -> "Service Unavailable";
            default -> "OK";
        };

        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append(" ").append(statusText).append("\r\n");
        sb.append("Content-Type: ").append(mime).append("\r\n");
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        if (setCookie != null) {
            sb.append("Set-Cookie: ").append(setCookie).append("\r\n");
        }
        sb.append("Connection: close\r\n\r\n");

        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private void sendRedirect(OutputStream out, String location, String setCookie) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 302 Found\r\n");
        sb.append("Location: ").append(location).append("\r\n");
        if (setCookie != null) {
            sb.append("Set-Cookie: ").append(setCookie).append("\r\n");
        }
        sb.append("Connection: close\r\n\r\n");

        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
