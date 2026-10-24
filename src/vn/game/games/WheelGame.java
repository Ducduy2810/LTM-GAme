package vn.game.games;

import vn.game.db.UserRepository;
import vn.game.server.WebSocketClient;
import vn.game.util.JsonUtil;

import java.util.*;
import java.util.concurrent.*;

public class WheelGame {

    private final UserRepository userRepository;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    // Danh sách các phòng Chiếc Nón Kỳ Diệu: roomCode -> WheelRoom
    private final Map<String, WheelRoom> rooms = new ConcurrentHashMap<>();
    // Tra cứu phòng hiện tại của mỗi Client: WebSocketClient -> WheelRoom
    private final Map<WebSocketClient, WheelRoom> clientRoomMap = new ConcurrentHashMap<>();
    // Tập hợp các Client đang ở Sảnh phòng (Lobby) để nhận danh sách phòng realtime
    private final Set<WebSocketClient> lobbyClients = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public static final String DEFAULT_ROOM_CODE = "CNKD";

    public WheelGame(UserRepository userRepository) {
        this.userRepository = userRepository;
        initDefaultRoom();
    }

    public WheelGame() {
        this(null);
    }

    private void initDefaultRoom() {
        WheelRoom defaultRoom = new WheelRoom(
                DEFAULT_ROOM_CODE,
                "Sàn Đấu Chiếc Nón VTV3",
                "DuyNguyen",
                "ALL",
                10,
                userRepository,
                scheduler
        );
        rooms.put(DEFAULT_ROOM_CODE, defaultRoom);
    }

    public boolean isAdmin(String username) {
        if (username == null) return false;
        if ("admin".equalsIgnoreCase(username) || "DuyNguyen".equalsIgnoreCase(username)) return true;
        return userRepository != null && userRepository.isAdmin(username);
    }

    // ==========================================
    // 1. QUẢN LÝ SẢNH & DANH SÁCH PHÒNG
    // ==========================================
    public synchronized void registerLobbyClient(WebSocketClient client) {
        lobbyClients.add(client);
        sendRoomList(client);
    }

    public synchronized void unregisterLobbyClient(WebSocketClient client) {
        lobbyClients.remove(client);
    }

    public List<Map<String, Object>> getRoomsSummaryList() {
        List<Map<String, Object>> list = new ArrayList<>();
        // Phòng mặc định lên đầu
        if (rooms.containsKey(DEFAULT_ROOM_CODE)) {
            list.add(rooms.get(DEFAULT_ROOM_CODE).toSummaryMap());
        }
        for (Map.Entry<String, WheelRoom> entry : rooms.entrySet()) {
            if (!DEFAULT_ROOM_CODE.equalsIgnoreCase(entry.getKey())) {
                list.add(entry.getValue().toSummaryMap());
            }
        }
        return list;
    }

    public void sendRoomList(WebSocketClient client) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "wheel_room_list");
        msg.put("rooms", getRoomsSummaryList());
        client.send(JsonUtil.toJson(msg));
    }

    public void broadcastRoomListToLobby() {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "wheel_room_list");
        msg.put("rooms", getRoomsSummaryList());
        String json = JsonUtil.toJson(msg);

        lobbyClients.removeIf(WebSocketClient::isClosed);
        for (WebSocketClient c : lobbyClients) {
            c.send(json);
        }
    }

    // ==========================================
    // 2. TẠO PHÒNG MỚI (CREATE ROOM)
    // ==========================================
    public synchronized void handleCreateRoom(WebSocketClient client, Map<String, Object> req) {
        String username = client.getUsername();
        if (username == null) return;

        String roomName = (String) req.get("roomName");
        String roomCode = (String) req.get("roomCode");
        String topic = (String) req.get("topic");
        int timeLimit = (req.get("timeLimit") instanceof Number) ? ((Number) req.get("timeLimit")).intValue() : 10;
        int botCount  = (req.get("botCount")  instanceof Number) ? ((Number) req.get("botCount")).intValue()  : 1;

        if (roomCode == null || roomCode.trim().isEmpty()) {
            roomCode = generateUniqueRoomCode();
        } else {
            roomCode = roomCode.trim().toUpperCase();
            if (rooms.containsKey(roomCode)) {
                sendError(client, "Mã phòng [" + roomCode + "] đã tồn tại! Vui lòng chọn mã khác.");
                return;
            }
        }

        if (roomName == null || roomName.trim().isEmpty()) {
            roomName = "Phòng của " + username;
        }

        WheelRoom room = new WheelRoom(
                roomCode,
                roomName,
                username,
                topic,
                timeLimit,
                botCount,
                userRepository,
                scheduler
        );

        rooms.put(roomCode, room);

        // Rời phòng cũ nếu đang ở phòng nào đó
        handleLeaveRoom(client);

        // Gia nhập phòng vừa tạo với tư cách Host
        room.addPlayer(client);
        clientRoomMap.put(client, room);
        unregisterLobbyClient(client);

        broadcastRoomListToLobby();
    }

    private String generateUniqueRoomCode() {
        Random r = new Random();
        for (int i = 0; i < 1000; i++) {
            int num = 1000 + r.nextInt(9000);
            String code = "W" + num;
            if (!rooms.containsKey(code)) {
                return code;
            }
        }
        return "W" + System.currentTimeMillis() % 10000;
    }

    // ==========================================
    // 3. THAM GIA PHÒNG (JOIN ROOM)
    // ==========================================
    public synchronized void handleJoinRoom(WebSocketClient client, String roomCode) {
        if (roomCode == null || roomCode.trim().isEmpty()) {
            roomCode = DEFAULT_ROOM_CODE;
        }
        roomCode = roomCode.trim().toUpperCase();

        WheelRoom room = rooms.get(roomCode);
        if (room == null) {
            sendError(client, "Không tìm thấy phòng có mã: " + roomCode);
            return;
        }

        if (room.getPlayerCount() >= room.getMaxPlayers()) {
            sendError(client, "Phòng [" + roomCode + "] đã đầy (Tối đa 4 người)!");
            return;
        }

        // Rời phòng cũ nếu có
        handleLeaveRoom(client);

        boolean success = room.addPlayer(client);
        if (success) {
            clientRoomMap.put(client, room);
            unregisterLobbyClient(client);
            broadcastRoomListToLobby();
        }
    }

    // Tương thích ngược với lời gọi cũ 'join_wheel'
    public synchronized void handleJoin(WebSocketClient client) {
        handleJoinRoom(client, DEFAULT_ROOM_CODE);
    }

    // ==========================================
    // 4. RỜI PHÒNG (LEAVE ROOM)
    // ==========================================
    public synchronized void handleLeaveRoom(WebSocketClient client) {
        WheelRoom room = clientRoomMap.remove(client);
        if (room != null) {
            room.removePlayer(client);

            // Nếu không phải phòng mặc định và không còn ai -> dọn dẹp phòng
            if (!DEFAULT_ROOM_CODE.equalsIgnoreCase(room.getRoomCode()) && room.getPlayerCount() == 0) {
                rooms.remove(room.getRoomCode());
            }

            broadcastRoomListToLobby();
        }

        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "left_wheel_room");
        client.send(JsonUtil.toJson(msg));

        // Đưa client về sảnh
        registerLobbyClient(client);
    }

    // ==========================================
    // 5. BẬT / TẮT SẴN SÀNG (TOGGLE READY)
    // ==========================================
    public void handleToggleReady(WebSocketClient client) {
        WheelRoom room = clientRoomMap.get(client);
        if (room != null) {
            room.toggleReady(client);
        }
    }

    // ==========================================
    // 6. SUPER ADMIN ĐÓNG PHÒNG
    // ==========================================
    public synchronized void handleAdminCloseRoom(WebSocketClient client, String roomCode) {
        String username = client.getUsername();
        if (!isAdmin(username)) {
            sendError(client, "Bạn không có quyền quản trị viên tối cao (Super Admin)!");
            return;
        }

        if (DEFAULT_ROOM_CODE.equalsIgnoreCase(roomCode)) {
            sendError(client, "Không thể đóng phòng mặc định của hệ thống!");
            return;
        }

        WheelRoom room = rooms.remove(roomCode);
        if (room != null) {
            for (Map.Entry<WebSocketClient, WheelRoom> entry : new HashMap<>(clientRoomMap).entrySet()) {
                if (entry.getValue().equals(room)) {
                    clientRoomMap.remove(entry.getKey());
                    sendError(entry.getKey(), "Phòng đã bị đóng bởi Super Admin [" + username + "]!");
                    handleLeaveRoom(entry.getKey());
                }
            }
            broadcastRoomListToLobby();
        }
    }

    // ==========================================
    // 7. BẮT ĐẦU TRẬN ĐẤU & THAO TÁC GAME
    // ==========================================
    public void handleStartGame(WebSocketClient client) {
        WheelRoom room = clientRoomMap.get(client);
        if (room != null) {
            room.handleStartGame(client);
            broadcastRoomListToLobby();
        } else {
            sendError(client, "Bạn chưa tham gia phòng nào!");
        }
    }

    public void handleSpin(WebSocketClient client) {
        WheelRoom room = clientRoomMap.get(client);
        if (room != null) {
            room.handleSpin(client);
        }
    }

    public void handleGuess(WebSocketClient client, String letter) {
        WheelRoom room = clientRoomMap.get(client);
        if (room != null) {
            room.handleGuess(client, letter);
        }
    }

    public void handleSolvePhrase(WebSocketClient client, String solution) {
        WheelRoom room = clientRoomMap.get(client);
        if (room != null) {
            room.handleSolvePhrase(client, solution);
        }
    }

    // ==========================================
    // 8. XỬ LÝ NGẮT KẾT NỐI (DISCONNECT)
    // ==========================================
    public synchronized void handleDisconnect(WebSocketClient client) {
        unregisterLobbyClient(client);
        WheelRoom room = clientRoomMap.remove(client);
        if (room != null) {
            room.removePlayer(client);
            if (!DEFAULT_ROOM_CODE.equalsIgnoreCase(room.getRoomCode()) && room.getPlayerCount() == 0) {
                rooms.remove(room.getRoomCode());
            }
            broadcastRoomListToLobby();
        }
    }

    private void sendError(WebSocketClient client, String text) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "wheel_error");
        msg.put("message", text);
        client.send(JsonUtil.toJson(msg));
    }
}
