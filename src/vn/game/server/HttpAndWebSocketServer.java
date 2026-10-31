package vn.game.server;

import vn.game.config.Env;
import vn.game.db.UserRepository;
import vn.game.db.AdminStore;
import vn.game.db.PlayerAccountStore;
import vn.game.db.RaceStore;
import vn.game.games.KnowledgeRaceGame;
import vn.game.games.ArenaGame;
import vn.game.games.SurvivalGame;
import vn.game.games.WheelGame;
import vn.game.model.User;
import vn.game.model.MatchService;
import vn.game.model.PracticeService;
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
    private final KnowledgeRaceGame raceGame=new KnowledgeRaceGame();
    private final PasswordResetMailer resetMailer = new PasswordResetMailer();
    private final PracticeService practice = new PracticeService();
    private final AdminController adminController;
    private final ClientEvents clientEvents=new ClientEvents();
    private final GoogleOAuthService googleOAuth;
    private record Feedback(Map<String,Object> value,long expires){}
    private final Map<String,Feedback> feedback=new ConcurrentHashMap<>();
    private final Map<WebSocketClient,String> socketSessions=new ConcurrentHashMap<>();
    private final ExecutorService threadPool = Executors.newCachedThreadPool();
    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private final Set<WebSocketClient> connections = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
    private static final long HEARTBEAT_INTERVAL_MS = 10_000;
    private static final long HEARTBEAT_TIMEOUT_MS = 20_000;
    private final Object playerAccessLock=new Object();
    private String lastMaintenance="";

    public HttpAndWebSocketServer(int port, File publicDir, UserRepository userRepository,
                                  SessionManager sessionManager, ArenaGame arenaGame,
                                  WheelGame wheelGame, SurvivalGame survivalGame) {
        this(port,publicDir,userRepository,sessionManager,arenaGame,wheelGame,survivalGame,new GoogleOAuthService());
    }

    public HttpAndWebSocketServer(int port,File publicDir,UserRepository userRepository,SessionManager sessionManager,
                                  ArenaGame arenaGame,WheelGame wheelGame,SurvivalGame survivalGame,GoogleOAuthService googleOAuth) {
        this.port = port;
        this.publicDir = publicDir;
        this.userRepository = userRepository;
        this.sessionManager = sessionManager;
        this.arenaGame = arenaGame;
        this.wheelGame = wheelGame;
        this.survivalGame = survivalGame;
        this.googleOAuth=googleOAuth;
        this.adminController=new AdminController(userRepository,new AdminController.Runtime(){
            public Map<String,Object> rooms(){return Map.of("items",java.util.stream.Stream.of(arenaGame.adminRooms(),wheelGame.adminRooms(),survivalGame.adminRooms()).flatMap(List::stream).toList());}
            public int connections(){
                Set<String> online=clientEvents.onlineUsernames();
                for(WebSocketClient client:connections){
                    String name=client.getUsername();
                    if(!client.isClosed()&&name!=null&&sessionManager.getUsername(socketSessions.get(client))!=null)online.add(name.toLowerCase(Locale.ROOT));
                }
                return online.size();
            }
            public void disconnect(String username,String reason){
                endPlayerSessions(username,"Phiên đã kết thúc bởi quản trị viên. Lý do: "+reason,true);
            }
            public void announce(Map<String,Object> value){clientEvents.broadcast(value);}
            public void maintenanceChanged(){enforceMaintenance();}
            public Map<String,Object> integrations(){return Map.of("google",googleOAuth.diagnostics(getPort()),"smtp",vn.game.util.SmtpTransport.diagnostics());}
            public Map<String,Object> race()throws java.sql.SQLException{return raceGame.adminView();}
            public void raceAction(String actor,Map<String,Object> input)throws java.sql.SQLException{raceGame.adminAction(actor,input);}
            public void roomAction(String actor,String game,String room,String action,String target,String reason){
                switch(game){
                    case "ARENA":if(action.equals("cancel"))arenaGame.adminCancelRoom(room,reason);else if(action.equals("kick"))arenaGame.adminKick(room,target,actor,reason);else arenaGame.adminNotice(room,reason);break;
                    case "WHEEL":if(action.equals("cancel"))wheelGame.adminCancelRoom(room,reason);else if(action.equals("kick"))wheelGame.adminKick(room,target,actor,reason);else wheelGame.adminNotice(room,reason);break;
                    case "SURVIVAL":if(action.equals("cancel"))survivalGame.adminCancelRoom(room,reason);else if(action.equals("kick"))survivalGame.adminKick(room,target,actor,reason);else survivalGame.adminNotice(room,reason);break;
                    default:throw new IllegalArgumentException("Trò chơi không hợp lệ.");
                }
            }
        });
    }

    public void start() throws IOException {
        start(() -> {});
    }

    public void start(Runnable onBound) throws IOException {
        // Bind rõ ràng 0.0.0.0 để chấp nhận kết nối từ mạng LAN
        serverSocket = new ServerSocket(port, 50, java.net.InetAddress.getByName("0.0.0.0"));
        try { onBound.run(); }
        catch (RuntimeException e) { serverSocket.close(); throw e; }
        running = true;
        clientEvents.start();
        raceGame.start();
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
        clientEvents.close();
        raceGame.close();
        connections.forEach(WebSocketClient::close);
        connections.clear();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
        threadPool.shutdownNow();
    }

    private void checkConnections() {
        if (!running) return;
        try{enforceMaintenance();}catch(Exception e){System.err.println("Maintenance update failed: "+e.getClass().getSimpleName());}
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
    private Map<String,Object> maintenanceStatus(){
        try{return AdminStore.maintenanceStatus();}catch(java.sql.SQLException e){throw new IllegalStateException("Chưa tải được trạng thái bảo trì.",e);}
    }
    private String createPlayerSession(String name){
        synchronized(playerAccessLock){if(Boolean.TRUE.equals(maintenanceStatus().get("enabled")))return null;return sessionManager.createSession(name);}
    }
    private void enforceMaintenance(){
        synchronized(playerAccessLock){
            var status=maintenanceStatus();boolean active=Boolean.TRUE.equals(status.get("enabled"));String version=JsonUtil.toJson(status);
            boolean changed=!version.equals(lastMaintenance);
            if(changed)clientEvents.maintenance(status);
            if(active){
                String message=(String)status.get("message");
                // Revoke every login, including players with no game socket open.
                for(String name:sessionManager.usernames())endPlayerSessions(name,message,Map.of("kind","MAINTENANCE","reason",message),false);
                if(changed){
                    try{raceGame.maintenance(message);}catch(java.sql.SQLException e){throw new IllegalStateException(e);}
                    for(var room:arenaGame.adminRooms())arenaGame.adminCancelRoom((String)room.get("roomCode"),message);
                    for(var room:wheelGame.adminRooms())wheelGame.adminCancelRoom((String)room.get("roomCode"),message);
                    for(var room:survivalGame.adminRooms())survivalGame.adminCancelRoom((String)room.get("roomCode"),message);
                }
            }
            lastMaintenance=version;
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
            var maintenance=maintenanceStatus();
            if(Boolean.TRUE.equals(maintenance.get("enabled"))||username!=null&&(AdminStore.isBlocked(username)||userRepository.isAdmin(username)))username=null;
            client = new WebSocketClient(socket, in, out, username);

            if (username == null) {
                Map<String, Object> err = new HashMap<>();
                err.put("type", "auth_error");
                err.put("message", Boolean.TRUE.equals(maintenance.get("enabled"))?maintenance.get("message"):"Bạn phải đăng nhập để chơi game!");
                if(Boolean.TRUE.equals(maintenance.get("enabled")))err.put("accountNotice",Map.of("kind","MAINTENANCE","reason",maintenance.get("message")));
                client.send(JsonUtil.toJson(err));
                Thread.sleep(200);
                client.close();
                return;
            }

            // Vòng lặp lắng nghe các Frame từ WebSocket Client
            connections.add(client);
            socketSessions.put(client,sessionId);
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
                socketSessions.remove(client);
                raceGame.disconnect(client);
                arenaGame.handleDisconnect(client);
                wheelGame.handleDisconnect(client);
                survivalGame.handleDisconnect(client);
                client.close();
            }
        }
    }

    private void routeWebSocketMessage(WebSocketClient client, String text) {
        try {
            if(Boolean.TRUE.equals(maintenanceStatus().get("enabled"))){enforceMaintenance();client.close();return;}
            if(!Objects.equals(client.getUsername(),sessionManager.getUsername(socketSessions.get(client)))||AdminStore.isBlocked(client.getUsername())||userRepository.isAdmin(client.getUsername())){client.close();return;}
            Map<String, Object> req = JsonUtil.parseObject(text);
            String type = (String) req.get("type");
            if (type == null) return;
            if(type.startsWith("race_")){raceGame.handle(client,req);return;}

        switch (type) {
            case "kick_arena_player":arenaGame.handleHostKick(client,requestText(req,"targetUsername"));break;
            case "kick_wheel_player":wheelGame.handleHostKick(client,requestText(req,"targetUsername"));break;
            case "kick_survival_player":survivalGame.handleHostKick(client,requestText(req,"targetUsername"));break;
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
            case "close_wheel_room":
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

        int maxBody=path.equals("/api/admin/backup-upload")?AdminStore.MAX_BACKUP_UPLOAD_BYTES:path.startsWith("/api/admin/")?1500000:65536;
        if (contentLength < 0 || contentLength > maxBody) {
            sendJson(out, 400, Map.of("error", "Nội dung yêu cầu quá lớn hoặc không hợp lệ."), null);
            socket.close();
            return;
        }
        // Content-Length tính theo byte, không phải số ký tự (mật khẩu có thể có dấu).
        byte[] bodyBytes = in.readNBytes(contentLength);
        if (bodyBytes.length != contentLength) throw new EOFException("Thiếu nội dung HTTP.");
        String body = path.equals("/api/admin/backup-upload")?"":new String(bodyBytes, StandardCharsets.UTF_8);

        if("GET".equals(method)&&"/api/events".equals(path)){
            String name=sessionManager.getUsername(sessionId);
            if(name!=null&&(AdminStore.isBlocked(name)||userRepository.isAdmin(name)))name=null;
            clientEvents.stream(socket,out,name,id->sessionManager.getUsername(id)!=null,sessionId);return;
        }

        if(path.startsWith("/api/admin/")){
            AdminController.Reply reply=adminController.handle(method,path,query,bodyBytes,headers,socket.getInetAddress().getHostAddress());
            if(reply.file()!=null){
                String response="HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nCache-Control: no-store\r\nContent-Disposition: attachment; filename=\""+reply.filename()+"\"\r\nContent-Length: "+reply.file().length+"\r\nConnection: close\r\n\r\n";
                out.write(response.getBytes(StandardCharsets.UTF_8));out.write(reply.file());out.flush();
            }else sendJson(out,reply.status(),reply.data(),reply.cookie());
            socket.close();return;
        }
        if("/api/maintenance".equals(path)){sendJson(out,200,maintenanceStatus(),null);socket.close();return;}
        if(path.startsWith("/api/")||path.startsWith("/auth/")){
            var maintenance=maintenanceStatus();
            if(Boolean.TRUE.equals(maintenance.get("enabled"))){
                String message=(String)maintenance.get("message");
                if(path.startsWith("/auth/google")){sendRedirect(out,"/?error=maintenance",null);}
                else if(path.equals("/auth/me"))sendJson(out,200,Map.of("loggedIn",false,"maintenance",maintenance,"accountNotice",Map.of("kind","MAINTENANCE","reason",message)),"session_id=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
                else sendJson(out,503,Map.of("error",message,"maintenance",maintenance),"session_id=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
                socket.close();return;
            }
        }
        if("GET".equals(method)&&"/api/catalog".equals(path)){
            try{sendJson(out,200,Map.of("topics",AdminStore.topics().stream().filter(t->((Number)t.get("active")).intValue()==1).toList(),"settings",AdminStore.settings()),null);}
            catch(Exception e){sendJson(out,503,Map.of("error","Chưa tải được danh mục."),null);}
            socket.close();return;
        }
        if("/admin".equals(path)||"/admin/".equals(path)||"/admin/login".equals(path))path="/admin.html";
        if(path.startsWith("/api/race/")){
            handleRaceApi(out,method,path,query,sessionId);socket.close();return;
        }

        if (path.startsWith("/api/portal/") || path.startsWith("/api/practice/")) {
            handlePortalApi(out, method, path, body, sessionId);
            socket.close();
            return;
        }

        // --- LEADERBOARD API ---
        if ("GET".equals(method) && "/api/leaderboard".equals(path)) {
            Map<String, Object> lb = new HashMap<>();
            lb.put("leaderboard", vn.game.model.Leaderboard.getTop(20));
            sendJson(out, 200, lb, null);
            socket.close();
            return;
        }

        if ("/api/leaderboard/clear".equals(path)) {
            sendJson(out,403,Map.of("error","Legacy leaderboard is read-only."),null);
            socket.close();return;
        }

        // --- CÁC ROUTE REST API ---
        if (path.startsWith("/auth/")) {
            handleAuthApi(out, method, path, query, body, sessionId, headers);
            socket.close();
            return;
        }

        // --- PHỤC VỤ FILE TĨNH (HTML, CSS, JS) ---
        if ("GET".equals(method) || "HEAD".equals(method)) {
            serveStaticFile(out, path, method, headers);
        } else {
            sendResponse(out, 405, "text/plain", "Method Not Allowed".getBytes(StandardCharsets.UTF_8), null);
        }
        socket.close();
    }

    private void handlePortalApi(OutputStream out, String method, String path, String body, String sessionId) throws IOException {
        String username = sessionManager.getUsername(sessionId);
        if (username == null) { sendJson(out,401,Map.of("error","Vui lòng đăng nhập."),null); return; }
        if(AdminStore.isBlocked(username)||userRepository.isAdmin(username)){sendJson(out,403,Map.of("error","Tài khoản không được truy cập cổng người chơi."),null);return;}
        try {
            switch (path) {
                case "/api/portal/overview":
                    if (!"GET".equals(method)) { sendJson(out,405,Map.of("error","Method Not Allowed"),null); return; }
                    sendJson(out,200,Map.of("history",MatchService.history(username),"rankEnabled",true,
                            "rooms",Map.of("arena",arenaGame.getRoomsSummaryList(),"wheel",wheelGame.getRoomsSummaryList(),
                            "survival",survivalGame.getRoomsSummaryList())),null);
                    return;
                case "/api/portal/admin":
                    sendJson(out,403,Map.of("error","Sử dụng trang /admin và phiên quản trị riêng."),null);
                    return;
                case "/api/practice/next": {
                    if (!"POST".equals(method)) { sendJson(out,405,Map.of("error","Method Not Allowed"),null); return; }
                    Map<String,Object> req=JsonUtil.parseObject(body);
                    sendJson(out,200,practice.next(username,requestText(req,"topic"),requestText(req,"difficulty")),null);
                    return;
                }
                case "/api/practice/answer": {
                    if (!"POST".equals(method)) { sendJson(out,405,Map.of("error","Method Not Allowed"),null); return; }
                    Map<String,Object> req=JsonUtil.parseObject(body);
                    Object value=req.get("answer");
                    if (!(value instanceof Number n) || n.doubleValue()!=n.intValue()) throw new IllegalArgumentException("Đáp án không hợp lệ.");
                    sendJson(out,200,practice.answer(username,requestText(req,"questionId"),((Number)value).intValue()),null);
                    return;
                }
                default: sendJson(out,404,Map.of("error","Không tìm thấy chức năng."),null);
            }
        } catch (IllegalArgumentException e) { sendJson(out,400,Map.of("error",e.getMessage()),null);
        } catch (Exception e) {
            System.err.println("Portal API: " + e.getMessage());
            sendJson(out,503,Map.of("error","Chưa tải được dữ liệu. Vui lòng thử lại."),null);
        }
    }

    private void handleAuthApi(OutputStream out, String method, String path, String query, String body, String sessionId, Map<String, String> headers) throws IOException {
        Map<String, Object> req = JsonUtil.parseObject(body);

        if (("/auth/forgot".equals(path) || "/auth/reset".equals(path)
                || "/auth/register".equals(path) || "/auth/verify-email".equals(path)) && !"POST".equals(method)) {
            sendJson(out, 405, Map.of("error", "Vui lòng gửi yêu cầu bằng POST."), null);
            return;
        }

        switch (path) {
            case "/auth/profile": {
                String username=sessionManager.getUsername(sessionId);
                if(!"GET".equals(method)){sendJson(out,405,Map.of("error","Dùng GET để xem hồ sơ."),null);return;}
                if(username==null||userRepository.isAdmin(username)||AdminStore.isBlocked(username)){sendJson(out,401,Map.of("error","Vui lòng đăng nhập lại."),null);return;}
                try{sendJson(out,200,PlayerAccountStore.profile(username),null);}catch(java.sql.SQLException e){sendJson(out,503,Map.of("error","Chưa tải được hồ sơ."),null);}return;
            }
            case "/auth/change-password":
            case "/auth/notice/ack": {
                String username=sessionManager.getUsername(sessionId);
                if(!"POST".equals(method)){sendJson(out,405,Map.of("error","Dùng POST để cập nhật tài khoản."),null);return;}
                if(username==null||userRepository.isAdmin(username)||AdminStore.isBlocked(username)){sendJson(out,401,Map.of("error","Vui lòng đăng nhập lại."),null);return;}
                if(!sessionManager.validCsrf(sessionId,headers.get("x-csrf-token"))||"cross-site".equals(headers.get("sec-fetch-site"))){sendJson(out,403,Map.of("error","Phiên xác nhận không hợp lệ. Tải lại trang."),null);return;}
                try{
                    if(path.equals("/auth/notice/ack")){
                        Object noticeId=req.get("noticeId");if(!(noticeId instanceof Number n)||n.doubleValue()!=n.intValue()||n.intValue()<1)throw new IllegalArgumentException("Thông báo không hợp lệ.");
                        PlayerAccountStore.acknowledge(username,((Number)noticeId).intValue());sendJson(out,200,Map.of("success",true),null);
                    }else{
                        PlayerAccountStore.changePassword(username,requestText(req,"currentPassword"),requestText(req,"newPassword"));
                        endPlayerSessions(username,"Mật khẩu đã được đổi. Vui lòng đăng nhập lại.",false);
                        sendJson(out,200,Map.of("success",true,"message","Đã đổi mật khẩu. Vui lòng đăng nhập lại."),"session_id=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
                    }
                }catch(IllegalArgumentException e){sendJson(out,400,Map.of("error",e.getMessage()),null);}catch(java.sql.SQLException e){sendJson(out,503,Map.of("error","Không cập nhật được tài khoản. Vui lòng thử lại."),null);}return;
            }
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
                    sendMailFailure(out,e);
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
                String username = requestText(req,"username").trim();
                String password = requestText(req,"password");

                User user = userRepository.findByUsername(username);
                if (user == null && userRepository.hasPendingRegistration(username)) {
                    sendJson(out, 403, Map.of("error", "Tài khoản đang chờ xác nhận email. Hãy mở email đăng ký và bấm liên kết để kích hoạt."), null);
                    return;
                }
                if (user == null || !user.verifyPassword(password)) {
                    sendJson(out, 400, Map.of("error", "Sai tên đăng nhập hoặc mật khẩu!"), null);
                    return;
                }

                if(user.isAdmin()) {sendJson(out,403,Map.of("error","Tài khoản quản trị đăng nhập tại /admin."),null);return;}
                if(AdminStore.isBlocked(user.getUsername())){
                    try{Map<String,Object> restriction=PlayerAccountStore.restriction(user.getUsername());sendJson(out,403,Map.of("error",PlayerAccountStore.restrictionMessage(restriction),"accountNotice",restriction),null);}catch(java.sql.SQLException e){sendJson(out,403,Map.of("error","Tài khoản đang bị khóa."),null);}return;
                }

                String newSessionId = createPlayerSession(user.getUsername());
                if(newSessionId==null){var maintenance=maintenanceStatus();sendJson(out,503,Map.of("error",maintenance.get("message"),"maintenance",maintenance),null);return;}
                String cookieHeader = playerCookie(newSessionId);
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
                resp.put("maintenance",maintenanceStatus());
                if(username!=null&&(AdminStore.isBlocked(username)||userRepository.isAdmin(username))){
                    if(!userRepository.isAdmin(username))try{resp.put("accountNotice",PlayerAccountStore.restriction(username));}catch(java.sql.SQLException ignored){}
                    sessionManager.removeSession(sessionId);username=null;
                }
                if (username != null) {
                    resp.put("loggedIn", true);
                    User u = userRepository.findByUsername(username);
                    boolean isAdmin = userRepository.isAdmin(username);
                    Map<String, Object> userMap = new HashMap<>();
                    userMap.put("username", username);
                    userMap.put("isAdmin", isAdmin);
                    userMap.put("email", u != null && u.getEmail() != null ? u.getEmail() : "");
                    userMap.put("csrfToken",sessionManager.getCsrfToken(sessionId));
                    try{Map<String,Object> profile=PlayerAccountStore.profile(username);userMap.put("rank",profile.get("rank"));userMap.put("googleLinked",profile.get("googleLinked"));userMap.put("hasLocalPassword",profile.get("hasLocalPassword"));resp.put("accountNotice",profile.get("accountNotice"));}
                    catch(java.sql.SQLException e){sendJson(out,503,Map.of("error","Chưa tải được thông tin tài khoản."),null);return;}
                    resp.put("user", userMap);
                } else {
                    resp.put("loggedIn", false);
                }
                String feedbackId=AdminSessions.cookieValue(headers.get("cookie"),"auth_feedback");
                Feedback flash=feedbackId==null?null:feedback.remove(feedbackId);
                if(flash!=null&&flash.expires()>System.currentTimeMillis())resp.put("accountNotice",flash.value());
                sendJson(out, 200, resp, feedbackId==null?null:"auth_feedback=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
                break;
            }

            case "/auth/forgot": {
                String email = requestText(req, "email").trim().toLowerCase(Locale.ROOT);
                User account=userRepository.findByEmail(email);
                if(account!=null&&account.isAdmin()){sendJson(out,403,Map.of("error","Tài khoản quản trị khôi phục mật khẩu tại máy server."),null);return;}
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
                    System.err.println("Không gửi được email khôi phục: " + e.getClass().getSimpleName());
                    sendMailFailure(out,e);
                    return;
                }
                System.out.println("[Password reset] SMTP đã tiếp nhận email gửi mã xác nhận.");
                sendJson(out, 200, Map.of("success", true,
                        "message", "Đã gửi mã xác nhận đến email đăng ký. Mã có hiệu lực 10 phút; vui lòng kiểm tra cả thư rác.",
                        "retryAfter", PasswordResetCode.RESEND_AFTER_MS / 1000), null);
                break;
            }

            case "/auth/reset": {
                User adminAccount=userRepository.findByEmail(requestText(req,"email").trim());
                if(adminAccount!=null&&adminAccount.isAdmin()){sendJson(out,403,Map.of("error","Tài khoản quản trị dùng công cụ khôi phục cục bộ."),null);return;}
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
                endPlayerSessions(username,"Mật khẩu đã được đặt lại. Vui lòng đăng nhập bằng mật khẩu mới.",false);
                sendJson(out, 200, Map.of("success", true, "message", "Đã đổi mật khẩu thành công! Hãy đăng nhập bằng mật khẩu mới."),
                        "session_id=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
                break;
            }

            case "/auth/google": {
                if(!"GET".equals(method)){sendJson(out,405,Map.of("error","Dùng GET để đăng nhập Google."),null);return;}
                try {
                    Env.load();String redirect=googleOAuth.redirectUri(getPort());URI uri=URI.create(redirect);
                    String authority=uri.getRawAuthority();
                    if(!authority.equalsIgnoreCase(headers.getOrDefault("host",""))&&!authority.equalsIgnoreCase(headers.getOrDefault("x-forwarded-host",""))){
                        sendRedirect(out,uri.getScheme()+"://"+authority+"/auth/google",null);return;
                    }
                    GoogleOAuthService.Start auth=googleOAuth.begin(getPort());
                    String stateCookie="oauth_state="+auth.state()+"; Path=/auth/google; HttpOnly; SameSite=Lax; Max-Age=600"+(redirect.startsWith("https://")?"; Secure":"");
                    sendRedirect(out,auth.location(),stateCookie);
                }catch(GoogleOAuthService.Failure e){sendRedirect(out,"/?error="+e.code,null);}
                return;
            }
            case "/auth/google/callback": {
                if(!"GET".equals(method)){sendJson(out,405,Map.of("error","Dùng GET cho Google callback."),null);return;}
                Map<String,String> values=new HashMap<>();
                try {for(String part:query.split("&")){String[] kv=part.split("=",2);values.put(URLDecoder.decode(kv[0],StandardCharsets.UTF_8),kv.length>1?URLDecoder.decode(kv[1],StandardCharsets.UTF_8):"");}}
                catch(IllegalArgumentException e){sendRedirect(out,"/?error=google_state_invalid",null);return;}
                try {
                    GoogleOAuthService.Identity identity=googleOAuth.finish(values.get("code"),values.get("state"),AdminSessions.cookieValue(headers.get("cookie"),"oauth_state"));
                    User user=userRepository.registerOrGetGoogleUser(identity.id(),identity.email(),identity.name());
                    if(user.isAdmin()){sendRedirect(out,"/?error=google_account_restricted",null);return;}
                    if(AdminStore.isBlocked(user.getUsername())){
                        Map<String,Object> notice=PlayerAccountStore.restriction(user.getUsername());
                        sendRedirect(out,"/",feedbackCookie(notice));return;
                    }
                    String id=createPlayerSession(user.getUsername());if(id==null){sendRedirect(out,"/?error=maintenance",null);return;}sendRedirect(out,"/",playerCookie(id));
                }catch(GoogleOAuthService.Failure e){sendRedirect(out,"/?error="+e.code,"oauth_state=; Path=/auth/google; HttpOnly; SameSite=Lax; Max-Age=0");}
                catch(IllegalArgumentException e){sendRedirect(out,"/?error=google_account_restricted",null);}
                catch(Exception e){System.err.println("Google account completion failed: "+e.getClass().getSimpleName());sendRedirect(out,"/?error=google_account_failed",null);}
                return;
            }

            default:
                sendJson(out, 404, Map.of("error", "API endpoint not found"), null);
                break;
        }
    }

    private String playerCookie(String id){return "session_id="+id+"; Path=/; HttpOnly; SameSite=Lax"+((Env.get("APP_BASE_URL").startsWith("https://")||Env.get("GOOGLE_REDIRECT_URI").startsWith("https://"))?"; Secure":"");}
    private String feedbackCookie(Map<String,Object> value){
        long now=System.currentTimeMillis();feedback.entrySet().removeIf(e->e.getValue().expires()<now);
        String id=UUID.randomUUID().toString();feedback.put(id,new Feedback(value,now+300000));return "auth_feedback="+id+"; Path=/; HttpOnly; SameSite=Lax; Max-Age=300"+(Env.get("APP_BASE_URL").startsWith("https://")?"; Secure":"");
    }
    private void endPlayerSessions(String username,String message,boolean moderation){
        Map<String,Object> notice=Map.of();if(moderation)try{notice=PlayerAccountStore.latestNotice(username);}catch(java.sql.SQLException ignored){}
        endPlayerSessions(username,message,notice,moderation);
    }
    private void endPlayerSessions(String username,String message,Map<String,Object> notice,boolean moderation){
        Map<String,Object> event=Map.of("username",username,"message",message,"accountNotice",notice);
        clientEvents.notifyUser(username,event);sessionManager.removeUserSessions(username);
        if(moderation)raceGame.moderate(username,message);
        for(WebSocketClient c:connections)if(username.equalsIgnoreCase(c.getUsername())){
            Map<String,Object> packet=new HashMap<>(event);packet.put("type","auth_error");c.send(JsonUtil.toJson(packet));c.close();
        }
    }
    private void sendMailFailure(OutputStream out,IOException error)throws IOException{
        if(error instanceof vn.game.util.SmtpTransport.Failure f)sendJson(out,503,Map.of("error",f.getMessage(),"code",f.code),null);
        else sendJson(out,503,Map.of("error","Không gửi được email. Vui lòng thử lại hoặc liên hệ quản trị viên.","code","SMTP_SEND"),null);
    }
    private void handleRaceApi(OutputStream out,String method,String path,String query,String sessionId)throws IOException{
        String username=sessionManager.getUsername(sessionId);
        if(username==null||AdminStore.isBlocked(username)||userRepository.isAdmin(username)){sendJson(out,401,Map.of("error","Vui lòng đăng nhập người chơi."),null);return;}
        if(!method.equals("GET")){sendJson(out,405,Map.of("error","Dùng GET để xem dữ liệu."),null);return;}
        try{
            Map<String,Object> result;
            switch(path){
                case "/api/race/overview":result=Map.of("account",RaceStore.account(username),"settings",RaceStore.settings(),"rooms",raceGame.lobby(),"leaderboard",RaceStore.leaderboard());break;
                case "/api/race/history":result=Map.of("items",RaceStore.history(username,false));break;
                case "/api/race/review":{
                    String match="";for(String part:query.split("&")){String[] pair=part.split("=",2);if(pair[0].equals("id")&&pair.length==2)match=URLDecoder.decode(pair[1],StandardCharsets.UTF_8);}
                    result=RaceStore.review(username,match,false);break;
                }
                default:sendJson(out,404,Map.of("error","Không tìm thấy dữ liệu Đường đua."),null);return;
            }sendJson(out,200,result,null);
        }catch(IllegalArgumentException e){sendJson(out,400,Map.of("error",e.getMessage()),null);}catch(java.sql.SQLException e){sendJson(out,503,Map.of("error","Chưa tải được dữ liệu Đường đua."),null);}
    }
    private static String requestText(Map<String, Object> req, String key) {
        return req.get(key) instanceof String ? (String) req.get(key) : "";
    }

    private void serveStaticFile(OutputStream out, String path, String method, Map<String,String> headers) throws IOException {
        if ("/".equals(path)) path = "/index.html";
        File file = new File(publicDir, path);

        if (!file.exists() || file.isDirectory()) {
            sendResponse(out, 404, "text/plain", "404 Not Found".getBytes(StandardCharsets.UTF_8), null);
            return;
        }

        String mime = getMimeType(file.getName());
        if (mime.startsWith("video/") || "HEAD".equals(method)) {
            serveMediaFile(out, file, mime, method, headers.get("range"));
            return;
        }
        byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
        sendResponse(out, 200, mime, bytes, null);
    }

    private String getMimeType(String name) {
        name = name.toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".mp4") || name.endsWith(".m4v")) return "video/mp4";
        if (name.endsWith(".webm")) return "video/webm";
        return "application/octet-stream";
    }

    /** Stream a bounded byte range so browsers can load and loop videos without buffering whole files. */
    private void serveMediaFile(OutputStream out, File file, String mime, String method, String range) throws IOException {
        long size=file.length(),start=0,end=size-1;boolean partial=false;
        if ("GET".equals(method) && range!=null) {
            try {
                if(!range.matches("bytes=\\d*-\\d*"))throw new IllegalArgumentException();
                String[] bounds=range.substring(6).split("-",-1);
                if(bounds[0].isEmpty()){
                    long suffix=Long.parseLong(bounds[1]);if(suffix<=0)throw new IllegalArgumentException();start=Math.max(0,size-suffix);
                } else {
                    start=Long.parseLong(bounds[0]);if(!bounds[1].isEmpty())end=Math.min(end,Long.parseLong(bounds[1]));
                }
                if(size==0||start>=size||start>end)throw new IllegalArgumentException();partial=true;
            } catch(IllegalArgumentException e) {
                out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */"+size+"\r\nAccept-Ranges: bytes\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.flush();return;
            }
        }
        long length=size==0?0:end-start+1;
        String response="HTTP/1.1 "+(partial?"206 Partial Content":"200 OK")+"\r\nContent-Type: "+mime+"\r\nAccept-Ranges: bytes\r\nContent-Length: "+length+"\r\n"+(partial?"Content-Range: bytes "+start+"-"+end+"/"+size+"\r\n":"")+"Cache-Control: no-cache\r\nConnection: close\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.UTF_8));
        if(!"HEAD".equals(method))try(var input=new java.io.RandomAccessFile(file,"r")){
            input.seek(start);byte[] buffer=new byte[64*1024];long remaining=length;
            while(remaining>0){int read=input.read(buffer,0,(int)Math.min(buffer.length,remaining));if(read<0)break;out.write(buffer,0,read);remaining-=read;}
        }
        out.flush();
    }

    private void sendJson(OutputStream out, int status, Map<String, Object> map, String setCookie) throws IOException {
        byte[] bytes = JsonUtil.toJson(map).getBytes(StandardCharsets.UTF_8);
        sendResponse(out, status, "application/json; charset=utf-8", bytes, setCookie);
    }

    private void sendResponse(OutputStream out, int status, String mime, byte[] body, String setCookie) throws IOException {
        String statusText = switch (status) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 415 -> "Unsupported Media Type";
            case 429 -> "Too Many Requests";
            case 503 -> "Service Unavailable";
            default -> "OK";
        };

        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append(" ").append(statusText).append("\r\n");
        sb.append("Content-Type: ").append(mime).append("\r\n");
        if (mime.startsWith("application/json")) sb.append("Cache-Control: no-store\r\n");
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
