package vn.game.games;

import vn.game.db.UserRepository;
import vn.game.model.Leaderboard;
import vn.game.server.WebSocketClient;
import vn.game.util.JsonUtil;

import java.util.*;
import java.util.concurrent.*;

public class ArenaGame {

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
                "MIX", "MIX", 15, 5, 100,
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

        int questionCount = req.get("questionCount") instanceof Number
                ? ((Number) req.get("questionCount")).intValue() : 15;
        int timeLimit = 5;
        int botCount = req.get("botCount") instanceof Number
                ? ((Number) req.get("botCount")).intValue() : 0;

        ArenaRoom newRoom = new ArenaRoom(
                code, name, username,
                topic, difficulty,
                questionCount, timeLimit, botCount,
                userRepository, scheduler
        );
        newRoom.setMaxPlayers(req.get("maxPlayers") instanceof Number ? ((Number) req.get("maxPlayers")).intValue() : 101);
        rooms.put(code, newRoom);
        joinRoomInternal(client, newRoom);

        Map<String, Object> resp = new HashMap<>();
        resp.put("type", "create_room_success");
        resp.put("roomCode", code);
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
            sendError(client, "Phòng đã đủ người hoặc đang thi đấu. Chỉ người chơi cũ được kết nối lại.");
            return;
        }
        // Chỉ rời phòng cũ nếu client đang thực sự ở phòng nào đó
        if (clientRoomMap.containsKey(client)) {
            handleLeaveRoom(client);
        }
        lobbyClients.remove(client);
        clientRoomMap.put(client, room);
        room.handleJoin(client);
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
            if (canDelete) rooms.remove(room.getRoomCode());
            broadcastRoomListToLobby();
        }
    }

    public synchronized void handleAdminCloseRoom(WebSocketClient client, String roomCode) {
        String username = client.getUsername();
        if (!isAdmin(username)) {
            sendError(client, "Chỉ Super Admin mới có quyền đóng phòng!");
            return;
        }
        if (DEFAULT_ROOM_CODE.equalsIgnoreCase(roomCode)) {
            sendError(client, "Không thể đóng phòng mặc định của hệ thống!");
            return;
        }

        ArenaRoom room = rooms.remove(roomCode);
        if (room != null) {
            Map<String, Object> notice = new HashMap<>();
            notice.put("type", "arena_error");
            notice.put("message", "Phòng này đã bị đóng bởi Super Admin!");
            room.broadcast(notice);

            for (Map.Entry<WebSocketClient, ArenaRoom> entry : new ArrayList<>(clientRoomMap.entrySet())) {
                if (entry.getValue().equals(room)) {
                    clientRoomMap.remove(entry.getKey());
                    lobbyClients.add(entry.getKey());
                    sendRoomList(entry.getKey());
                }
            }
            broadcastRoomListToLobby();
        }
    }

    private void sendError(WebSocketClient client, String message) {
        Map<String, Object> err = new HashMap<>();
        err.put("type", "arena_error");
        err.put("message", message);
        client.send(JsonUtil.toJson(err));
    }
}
