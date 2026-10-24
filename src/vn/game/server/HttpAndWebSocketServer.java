package vn.game.server;

import vn.game.config.Env;
import vn.game.db.UserRepository;
import vn.game.games.ArenaGame;
import vn.game.games.SurvivalGame;
import vn.game.games.WheelGame;
import vn.game.model.User;
import vn.game.util.JsonUtil;

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
    private final ExecutorService threadPool = Executors.newCachedThreadPool();
    private volatile boolean running = false;
    private ServerSocket serverSocket;

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

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
        threadPool.shutdownNow();
    }

    private void handleConnection(Socket socket) {
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // Đọc HTTP Header dòng đầu
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String initialLine = reader.readLine();
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
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
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
            handleHttpRequest(socket, out, reader, method, path, headers, sessionId);

        } catch (Exception e) {
            try { socket.close(); } catch (IOException ignored) {}
        }
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
                    // Trả lời Pong (0xA)
                    synchronized (out) {
                        out.write(0x8A);
                        out.write(0);
                        out.flush();
                    }
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
    private void handleHttpRequest(Socket socket, OutputStream out, BufferedReader reader,
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

        char[] bodyChars = new char[contentLength];
        int read = 0;
        while (read < contentLength) {
            int r = reader.read(bodyChars, read, contentLength - read);
            if (r == -1) break;
            read += r;
        }
        String body = new String(bodyChars);

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
        // Xây baseUrl từ Host header để hoạt động đúng cả khi truy cập qua LAN
        String host = headers.getOrDefault("host", "localhost:" + port);
        String baseUrl = "http://" + host;
        // Google OAuth luôn dùng localhost vì Google Console chỉ cho phép redirect về localhost
        String googleCallbackUri = "http://localhost:" + port + "/auth/google/callback";

        switch (path) {
            case "/auth/register": {
                String username = (String) req.get("username");
                String email = (String) req.get("email");
                String password = (String) req.get("password");

                if (username == null || email == null || password == null ||
                        username.trim().isEmpty() || email.trim().isEmpty() || password.trim().isEmpty()) {
                    sendJson(out, 400, Map.of("error", "Hãy nhập đủ thông tin (cả email)!"), null);
                    return;
                }

                try {
                    userRepository.register(username.trim(), email.trim(), password);
                    sendJson(out, 200, Map.of("success", true, "message", "Đăng ký thành công! Hãy tiến hành đăng nhập."), null);
                } catch (Exception e) {
                    sendJson(out, 400, Map.of("error", e.getMessage()), null);
                }
                break;
            }

            case "/auth/login": {
                String username = (String) req.get("username");
                String password = (String) req.get("password");

                User user = userRepository.findByUsername(username);
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
                String email = (String) req.get("email");
                User user = userRepository.findByEmail(email);
                if (user == null) {
                    sendJson(out, 400, Map.of("error", "Không tìm thấy tài khoản nào đăng ký bằng Email này."), null);
                    return;
                }

                String token = UUID.randomUUID().toString().replace("-", "");
                user.setResetToken(token);
                user.setResetExpires(System.currentTimeMillis() + 3600000); // 1 giờ
                userRepository.update(user);

                String resetLink = baseUrl + "/reset.html?token=" + token;

                System.out.println("\n==============================================");
                System.out.println("[JAVA SERVER] YÊU CẦU ĐỔI MẬT KHẨU CHO EMAIL: " + email);
                System.out.println("Bấm vào link sau để đặt lại mật khẩu:");
                System.out.println(resetLink);
                System.out.println("==============================================\n");

                sendJson(out, 200, Map.of("success", true, "message", "Đã tạo link khôi phục! Vui lòng kiểm tra màn hình Terminal."), null);
                break;
            }

            case "/auth/reset": {
                String token = (String) req.get("token");
                String newPassword = (String) req.get("newPassword");

                User user = userRepository.findByResetToken(token);
                if (user == null || user.getResetExpires() < System.currentTimeMillis()) {
                    sendJson(out, 400, Map.of("error", "Mã khôi phục không hợp lệ hoặc đã hết hạn (1 giờ)."), null);
                    return;
                }

                user.updatePassword(newPassword);
                userRepository.update(user);
                sendJson(out, 200, Map.of("success", true, "message", "Đã cập nhật mật khẩu! Đang đưa bạn về trang chủ..."), null);
                break;
            }

            case "/auth/google": {
                // Google OAuth chỉ hoạt động khi truy cập từ máy chủ (localhost)
                // vì Google Console chỉ cho phép redirect_uri là localhost
                boolean isLocalhost = host.startsWith("localhost") || host.startsWith("127.0.0.1");
                if (!isLocalhost) {
                    sendJson(out, 400, Map.of("error", "Đăng nhập bằng Google chỉ khả dụng khi truy cập từ máy chủ (localhost). Vui lòng dùng tài khoản thường khi chơi qua mạng LAN."), null);
                    return;
                }
                String clientId = Env.get("GOOGLE_CLIENT_ID");
                if (clientId.isEmpty()) {
                    sendResponse(out, 400, "text/html; charset=utf-8",
                            "<h3>Lỗi: Chưa cấu hình GOOGLE_CLIENT_ID trong file .env!</h3><br><a href='/'>Quay lại trang chủ</a>".getBytes(StandardCharsets.UTF_8), null);
                    return;
                }
                String redirectUri = URLEncoder.encode(googleCallbackUri, StandardCharsets.UTF_8);
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
                String redirectUri = googleCallbackUri;

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
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            default -> "Internal Server Error";
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
        sb.append("Content-Length: 0\r\n");
        sb.append("Connection: close\r\n\r\n");

        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
