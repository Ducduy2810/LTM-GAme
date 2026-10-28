package vn.game.games;

import vn.game.db.UserRepository;
import vn.game.model.Leaderboard;
import vn.game.model.QuestionBank;
import vn.game.server.WebSocketClient;
import vn.game.util.JsonUtil;

import java.util.*;
import java.util.concurrent.*;

public class ArenaGame implements AutoCloseable {

    private final UserRepository userRepository;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    // Danh sách các phòng: roomCode -> ArenaRoom
    private final Map<String, ArenaRoom> rooms = new ConcurrentHashMap<>();
    // Client -> Phòng hiện tại
    private final Map<WebSocketClient, ArenaRoom> clientRoomMap = new ConcurrentHashMap<>();
    // Client đang ở Sảnh
    private final Set<WebSocketClient> lobbyClients = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public static final String DEFAULT_ROOM_CODE = "DT100";

    public ArenaGame(UserRepository userRepository) {
        this.userRepository = userRepository;
        initDefaultRoom();
    }

    public ArenaGame() { this(null); }

    private void initDefaultRoom() {
        ArenaRoom defaultRoom = new ArenaRoom(
                DEFAULT_ROOM_CODE, "Sàn Đấu Mặc Định VTV3", "DuyNguyen",
                "ALL", "MEDIUM", 15, 5, 100,
                userRepository, scheduler
        );
        rooms.put(DEFAULT_ROOM_CODE, defaultRoom);
    }

    public boolean isAdmin(String username) {
        if (username == null) return false;
        if ("admin".equalsIgnoreCase(username) || "DuyNguyen".equalsIgnoreCase(username)) return true;
        if (userRepository != null) {
            return userRepository.isAdmin(username);
        }
        return false;
    }

    // ==========================================
    // 1. QUẢN LÝ SẢNH
    // ==========================================
    public synchronized void registerLobbyClient(WebSocketClient client) {
        lobbyClients.add(client);
        sendRoomList(client);
        // Gửi bảng xếp hạng top 10
        sendLeaderboard(client);
    }

    public synchronized void unregisterLobbyClient(WebSocketClient client) {
        lobbyClients.remove(client);
    }

    public List<Map<String, Object>> getRoomsSummaryList() {
        List<Map<String, Object>> list = new ArrayList<>();
        if (rooms.containsKey(DEFAULT_ROOM_CODE)) {
            list.add(rooms.get(DEFAULT_ROOM_CODE).toSummaryMap());
        }
        for (Map.Entry<String, ArenaRoom> entry : rooms.entrySet()) {
            if (!DEFAULT_ROOM_CODE.equalsIgnoreCase(entry.getKey())) {
                list.add(entry.getValue().toSummaryMap());
            }
        }
        return list;
    }

    public void sendRoomList(WebSocketClient client) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "room_list");
        msg.put("rooms", getRoomsSummaryList());
        client.send(JsonUtil.toJson(msg));
    }

    public void sendLeaderboard(WebSocketClient client) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "leaderboard_update");
        msg.put("leaderboard", Leaderboard.getTop(10));
        client.send(JsonUtil.toJson(msg));
    }

    public void broadcastRoomListToLobby() {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "room_list");
        msg.put("rooms", getRoomsSummaryList());
        String json = JsonUtil.toJson(msg);
        lobbyClients.removeIf(WebSocketClient::isClosed);
        for (WebSocketClient c : lobbyClients) c.send(json);
    }

    // ==========================================
    // 2. TẠO PHÒNG MỚI (CREATE ROOM)
    // ==========================================
    public synchronized void handleCreateRoom(WebSocketClient client, Map<String, Object> req) {
        String username = client.getUsername();
        if (username == null) return;

        String code = (String) req.get("roomCode");
        if (code == null || code.trim().isEmpty()) {
            code = "P" + (1000 + new Random().nextInt(9000));
        } else {
            code = code.trim().toUpperCase().replaceAll("[^A-Z0-9_-]", "");
            if (code.isEmpty()) code = "P" + (1000 + new Random().nextInt(9000));
        }

        if (rooms.containsKey(code)) {
            sendError(client, "Mã phòng [" + code + "] đã tồn tại! Vui lòng chọn mã khác.");
            return;
        }

        String name       = (String) req.get("roomName");
        String topic      = (String) req.get("topic");
        String difficulty = (String) req.get("difficulty");

        for (String key : List.of("questionCount", "timeLimit", "mainPlayerTimeLimit", "botCount")) {
            if (req.containsKey(key) && (!(req.get(key) instanceof Number n)
                    || !Double.isFinite(n.doubleValue()) || n.doubleValue() != n.intValue())) {
                sendError(client, "Các giá trị thời gian, số câu và số bot phải là số nguyên."); return;
            }
        }

        int questionCount = req.get("questionCount") instanceof Number
                ? ((Number) req.get("questionCount")).intValue() : 15;
        int timeLimit = req.get("timeLimit") instanceof Number ? ((Number) req.get("timeLimit")).intValue() : 5;
        int mainTimeLimit = req.get("mainPlayerTimeLimit") instanceof Number ? ((Number) req.get("mainPlayerTimeLimit")).intValue() : 15;
        String mainSelectionMode = req.get("mainSelectionMode") instanceof String ? (String) req.get("mainSelectionMode") : "MANUAL";
        int botCount = req.get("botCount") instanceof Number
                ? ((Number) req.get("botCount")).intValue() : 0;

        if (timeLimit < 1 || timeLimit > 120 || mainTimeLimit < 1 || mainTimeLimit > 120
                || questionCount < 1 || questionCount > 100 || botCount < 0 || botCount > 100
                || !Set.of("MANUAL", "RANDOM").contains(mainSelectionMode)) {
            sendError(client, "Thời gian phải từ 1–120 giây, số câu từ 1–100, số bot từ 0–100 và chế độ chọn người chính hợp lệ.");
            return;
        }
        int available;
        try { available = QuestionBank.getArenaQuestions(topic, difficulty, questionCount).size(); }
        catch (IllegalArgumentException e) { sendError(client, e.getMessage()); return; }
        if (available == 0) { sendError(client, "Chưa có câu hỏi phù hợp với cấu hình này."); return; }

        ArenaRoom newRoom = new ArenaRoom(
                code, name, username,
                topic, difficulty,
                questionCount, timeLimit, botCount,
                userRepository, scheduler
        );
        newRoom.configureInitialMainPlayer(mainTimeLimit, mainSelectionMode);
        rooms.put(code, newRoom);
        joinRoomInternal(client, newRoom);

        Map<String, Object> resp = new HashMap<>();
        resp.put("type", "create_room_success");
        resp.put("roomCode", code);
        if (available < questionCount) resp.put("warning", "Chỉ có " + available + " câu đúng chủ đề/độ khó. Phòng sẽ dùng " + available + " câu, không lấy bù câu khác.");
        client.send(JsonUtil.toJson(resp));

        broadcastRoomListToLobby();
    }

    // ==========================================
    // 3. THAM GIA PHÒNG
    // ==========================================
    public synchronized void handleJoin(WebSocketClient client) {
        handleJoinRoom(client, DEFAULT_ROOM_CODE);
    }

    public synchronized void handleJoinRoom(WebSocketClient client, String roomCode) {
        if (roomCode == null || roomCode.trim().isEmpty()) roomCode = DEFAULT_ROOM_CODE;
        roomCode = roomCode.trim().toUpperCase();

        ArenaRoom room = rooms.get(roomCode);
        if (room == null) {
            sendError(client, "Không tìm thấy phòng với mã [" + roomCode + "]!");
            return;
        }

        // Cho phép vào xem kể cả đang đấu (spectator)
        joinRoomInternal(client, room);
        broadcastRoomListToLobby();
    }

    private synchronized void joinRoomInternal(WebSocketClient client, ArenaRoom room) {
        if (!room.canJoin(client.getUsername())) {
            sendError(client, "Trận đấu đang diễn ra hoặc phòng đã bị đóng. Không thể vào phòng lúc này.");
            return;
        }
        // Chỉ rời phòng cũ nếu client đang thực sự ở phòng nào đó
        if (clientRoomMap.containsKey(client)) {
            handleLeaveRoom(client);
        }
        lobbyClients.remove(client);
        if (room.handleJoin(client)) clientRoomMap.put(client, room);
        else lobbyClients.add(client);
    }

    // ==========================================
    // 4. RỜI PHÒNG
    // ==========================================
    public synchronized void handleLeaveRoom(WebSocketClient client) {
        ArenaRoom currentRoom = clientRoomMap.remove(client);
        if (currentRoom != null) {
            currentRoom.handleLeave(client);
            // Chỉ xóa phòng khi: không phải phòng mặc định, trống người, và không đang chơi
            boolean canDelete = currentRoom.isEmpty()
                    && !DEFAULT_ROOM_CODE.equalsIgnoreCase(currentRoom.getRoomCode())
                    && ("WAITING".equals(currentRoom.getGameState()) || "END".equals(currentRoom.getGameState()));
            if (canDelete) {
                rooms.remove(currentRoom.getRoomCode());
                currentRoom.close();
            }
        }
        lobbyClients.add(client);

        Map<String, Object> resp = new HashMap<>();
        resp.put("type", "left_room_success");
        client.send(JsonUtil.toJson(resp));

        broadcastRoomListToLobby();
    }

    // ==========================================
    // 5. SẴN SÀNG / BẮT ĐẦU / NỘP BÀI
    // ==========================================
    public void handleToggleReady(WebSocketClient client) {
        ArenaRoom room = clientRoomMap.get(client);
        if (room != null) room.handleToggleReady(client);
    }

    public void handleStartGame(WebSocketClient client) {
        ArenaRoom room = clientRoomMap.get(client);
        if (room != null) {
            room.handleStartGame(client);
            broadcastRoomListToLobby();
        } else {
            sendError(client, "Bạn chưa tham gia vào phòng nào!");
        }
    }

    public void handleSelectMainPlayer(WebSocketClient client, Map<String, Object> req) {
        ArenaRoom room = clientRoomMap.get(client);
        if (room != null) room.handleSelectMainPlayer(client,
                req.get("mainSelectionMode") instanceof String ? (String) req.get("mainSelectionMode") : "MANUAL",
                req.get("username") instanceof String ? (String) req.get("username") : "");
    }

    public void handleSubmitAnswer(WebSocketClient client, int answerIndex) {
        ArenaRoom room = clientRoomMap.get(client);
        if (room != null) room.handleSubmitAnswer(client, answerIndex);
    }

    // ==========================================
    // 6. BA QUYỀN TRỢ GIÚP
    // ==========================================
    public void handleLifeTrust(WebSocketClient client) {
        ArenaRoom room = clientRoomMap.get(client);
        if (room != null) room.handleLifeTrust(client);
    }

    public void handleLifeAsk(WebSocketClient client, String targetUsername) {
        ArenaRoom room = clientRoomMap.get(client);
        if (room != null) room.handleLifeAsk(client, targetUsername);
    }

    public void handleLifeSurvey(WebSocketClient client) {
        ArenaRoom room = clientRoomMap.get(client);
        if (room != null) room.handleLifeSurvey(client);
    }

    // ==========================================
    // 6.5. TẠM DỪNG & TIẾP TỤC (YÊU CẦU 5)
    // ==========================================
    public void handlePauseGame(WebSocketClient client) {
        ArenaRoom room = clientRoomMap.get(client);
        if (room != null) room.handlePauseGame(client);
    }

    public void handleResumeGame(WebSocketClient client) {
        ArenaRoom room = clientRoomMap.get(client);
        if (room != null) room.handleResumeGame(client);
    }

    // ==========================================
    // 7. BẢNG XẾP HẠNG
    // ==========================================
    public void handleGetLeaderboard(WebSocketClient client) {
        sendLeaderboard(client);
    }

    public synchronized void handleAdminClearLeaderboard(WebSocketClient client) {
        String username = client.getUsername();
        if (!isAdmin(username)) {
            sendError(client, "Chỉ Quản trị viên (Admin) mới có quyền xóa bảng xếp hạng!");
            return;
        }

        Leaderboard.clear();
        broadcastLeaderboard();
        sendLeaderboard(client);

        Map<String, Object> resp = new HashMap<>();
        resp.put("type", "admin_clear_leaderboard_success");
        resp.put("message", "Đã xóa toàn bộ Bảng Xếp Hạng thành công!");
        client.send(JsonUtil.toJson(resp));
    }

    public void broadcastLeaderboard() {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "leaderboard_update");
        msg.put("leaderboard", Leaderboard.getTop(10));
        String json = JsonUtil.toJson(msg);
        lobbyClients.removeIf(WebSocketClient::isClosed);
        for (WebSocketClient c : lobbyClients) c.send(json);
    }

    // ==========================================
    // 8. NGẮT KẾT NỐI & QUẢN TRỊ
    // ==========================================
    public synchronized void handleDisconnect(WebSocketClient client) {
        lobbyClients.remove(client);
        ArenaRoom room = clientRoomMap.remove(client);
        if (room != null) {
            room.handleDisconnect(client);
            boolean canDelete = room.isEmpty()
                    && !DEFAULT_ROOM_CODE.equalsIgnoreCase(room.getRoomCode())
                    && ("WAITING".equals(room.getGameState()) || "END".equals(room.getGameState()));
            if (canDelete) { rooms.remove(room.getRoomCode()); room.close(); }
            broadcastRoomListToLobby();
        }
    }

    public synchronized void handleAdminCloseRoom(WebSocketClient client, String roomCode) {
        if (roomCode == null) return;
        roomCode = roomCode.trim().toUpperCase(Locale.ROOT);
        ArenaRoom room = rooms.get(roomCode);
        if (room == null) { sendError(client, "Phòng không còn tồn tại."); return; }
        if (!room.isHost(client.getUsername()) && !isAdmin(client.getUsername())) {
            sendError(client, "Chỉ chủ phòng hoặc admin được xóa phòng."); return;
        }
        if (DEFAULT_ROOM_CODE.equalsIgnoreCase(roomCode)) { sendError(client, "Không thể xóa phòng mặc định."); return; }
        rooms.remove(roomCode);
        room.close();
        for (Map.Entry<WebSocketClient, ArenaRoom> entry : new ArrayList<>(clientRoomMap.entrySet())) {
            if (entry.getValue() == room) {
                WebSocketClient member = entry.getKey();
                clientRoomMap.remove(member);
                lobbyClients.add(member);
                member.send(JsonUtil.toJson(Map.of("type", "arena_room_closed", "message", "Phòng đã bị xóa bởi chủ phòng hoặc admin. Trận đấu được hủy.")));
            }
        }
        broadcastRoomListToLobby();
    }

    @Override
    public synchronized void close() {
        rooms.values().forEach(ArenaRoom::close);
        rooms.clear();
        clientRoomMap.clear();
        lobbyClients.clear();
        scheduler.shutdownNow();
    }

    private void sendError(WebSocketClient client, String message) {
        Map<String, Object> err = new HashMap<>();
        err.put("type", "arena_error");
        err.put("message", message);
        client.send(JsonUtil.toJson(err));
    }
}
