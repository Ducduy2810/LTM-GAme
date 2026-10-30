package vn.game.games;

import vn.game.db.UserRepository;
import vn.game.server.WebSocketClient;
import vn.game.util.JsonUtil;

import java.util.*;
import java.util.concurrent.*;

public class WheelGame implements AutoCloseable {

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
                "",
                "ALL",
                10,
                userRepository,
                scheduler
        );
        rooms.put(DEFAULT_ROOM_CODE, defaultRoom);
    }

    public boolean isAdmin(String username) {
        if (username == null) return false;
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
        String denied=vn.game.db.AdminStore.newMatchDenied("WHEEL");
        if(denied!=null){sendError(client,denied);return;}
        req=vn.game.db.AdminStore.applyDefaults("WHEEL",req);
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

        // Rời phòng cũ nếu đang ở phòng nào đó (không gửi message nếu chưa ở phòng)
        if (clientRoomMap.containsKey(client)) {
            handleLeaveRoom(client);
        }

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

            // Chỉ xóa phòng khi: không phải phòng mặc định, trống người, và không đang chơi
            boolean canDelete = room.getPlayerCount() == 0
                    && !DEFAULT_ROOM_CODE.equalsIgnoreCase(room.getRoomCode())
                    && !"PLAYING".equals(room.getGameState());
            if (canDelete) {
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
    public synchronized void handleAdminCloseRoom(WebSocketClient client, String code) {
        if(code==null){sendError(client,"Thiếu mã phòng.");return;}
        code=code.trim().toUpperCase(Locale.ROOT);
        WheelRoom room=rooms.get(code);
        if(room==null){sendError(client,"Phòng không còn tồn tại.");return;}
        if(isAdmin(client.getUsername())) {
            adminCancelRoom(code,"Quản trị viên hủy phòng");return;
        }
        // Starting a match and checking its idle state use the same room monitor.
        synchronized(room) {
            if(clientRoomMap.get(client)!=room||!room.isHost(client.getUsername())) {
                sendError(client,"Chỉ chủ phòng được xóa phòng chờ của mình.");return;
            }
            if(DEFAULT_ROOM_CODE.equalsIgnoreCase(code)) {sendError(client,"Không được xóa phòng mặc định.");return;}
            if(!Set.of("WAITING","END").contains(room.getGameState())) {
                sendError(client,"Trận đấu đang diễn ra. Chủ phòng chỉ được xóa khi ở sảnh chờ.");return;
            }
            try{vn.game.db.AdminStore.audit(client.getUsername(),"HOST_ROOM_DELETE","WHEEL:"+code,Map.of("state",room.getGameState()));}
            catch(java.sql.SQLException e){sendError(client,"Không ghi được nhật ký. Vui lòng thử lại.");return;}
            adminCancelRoom(code,"Chủ phòng đã xóa phòng chờ");
        }
    }

    public synchronized void adminCancelRoom(String code,String reason) {
        if(code==null)throw new IllegalArgumentException("Thiếu mã phòng.");
        code=code.trim().toUpperCase(Locale.ROOT);
        WheelRoom room=rooms.remove(code);
        if(room==null)throw new IllegalArgumentException("Phòng không còn tồn tại.");
        String notification=reason+(Set.of("WAITING","END").contains(room.getGameState())?".":". Trận đấu đã hủy.");
        room.close();
        for (Map.Entry<WebSocketClient,WheelRoom> entry:new ArrayList<>(clientRoomMap.entrySet())) {
            if(entry.getValue()==room) {
                WebSocketClient member=entry.getKey();clientRoomMap.remove(member);
                member.send(JsonUtil.toJson(Map.of("type","left_wheel_room","message",notification)));
                registerLobbyClient(member);
            }
        }
        if(DEFAULT_ROOM_CODE.equalsIgnoreCase(code))initDefaultRoom();
        broadcastRoomListToLobby();
    }

    public synchronized List<Map<String,Object>> adminRooms() {
        List<Map<String,Object>> result=new ArrayList<>();
        for(WheelRoom room:rooms.values()) {
            Map<String,Object> row=new LinkedHashMap<>(room.toSummaryMap());
            row.put("game","WHEEL");
            List<Map<String,Object>> members=new ArrayList<>();
            for(Map.Entry<WebSocketClient,WheelRoom> entry:clientRoomMap.entrySet())if(entry.getValue()==room)
                members.add(Map.of("username",entry.getKey().getUsername(),"host",room.isHost(entry.getKey().getUsername())));
            row.put("members",members);result.add(row);
        }return result;
    }

    public synchronized void adminNotice(String code,String message) {
        WheelRoom room=rooms.get(code.toUpperCase(Locale.ROOT));
        if(room==null)throw new IllegalArgumentException("Phòng không còn tồn tại.");
        for(Map.Entry<WebSocketClient,WheelRoom> entry:clientRoomMap.entrySet())if(entry.getValue()==room)
            entry.getKey().send(JsonUtil.toJson(Map.of("type","server_notice","message",message)));
    }

    public synchronized void adminKick(String code,String target,String actor,String reason) {
        WheelRoom room=rooms.get(code.toUpperCase(Locale.ROOT));
        if(room==null)throw new IllegalArgumentException("Phòng không còn tồn tại.");
        kickMember(room,target,actor,true,reason);
    }

    public synchronized void handleHostKick(WebSocketClient actor,String target) {
        WheelRoom room=clientRoomMap.get(actor);
        if(room==null||!room.isHost(actor.getUsername())) {sendError(actor,"Chỉ chủ phòng được kick thành viên.");return;}
        try {kickMember(room,target,actor.getUsername(),false,"Chủ phòng đã mời bạn rời phòng.");}
        catch(IllegalArgumentException e){sendError(actor,e.getMessage());}
    }

    private void kickMember(WheelRoom room,String target,String actor,boolean admin,String reason) {
        if(target==null||target.isBlank())throw new IllegalArgumentException("Hãy chọn người chơi cần kick.");
        if(!admin&&(actor.equalsIgnoreCase(target)||isAdmin(target)))throw new IllegalArgumentException("Không thể kick bản thân hoặc quản trị viên.");
        WebSocketClient member=null;
        for(Map.Entry<WebSocketClient,WheelRoom> entry:clientRoomMap.entrySet())
            if(entry.getValue()==room&&target.equalsIgnoreCase(entry.getKey().getUsername())){member=entry.getKey();break;}
        if(member==null)throw new IllegalArgumentException("Người chơi không ở trong phòng này.");
        try {vn.game.db.AdminStore.audit(actor,admin?"ADMIN_KICK":"HOST_KICK","WHEEL:"+room.getRoomCode(),Map.of("target",target,"reason",reason));}
        catch(java.sql.SQLException e){throw new IllegalStateException("Không ghi được nhật ký kick.",e);}
        room.banPlayer(target);
        handleLeaveRoom(member);
        member.send(JsonUtil.toJson(Map.of("type","room_kicked","game","WHEEL","roomCode",room.getRoomCode(),"message",reason)));
        for(Map.Entry<WebSocketClient,WheelRoom> entry:clientRoomMap.entrySet())if(entry.getValue()==room)
            entry.getKey().send(JsonUtil.toJson(Map.of("type","server_notice","message",target+" đã bị mời rời phòng.")));
        broadcastRoomListToLobby();
    }

    // ==========================================
    // 7. BẮT ĐẦU TRẬN ĐẤU & THAO TÁC GAME
    // ==========================================
    public void handleStartGame(WebSocketClient client) {
        String denied=vn.game.db.AdminStore.newMatchDenied("WHEEL");
        if(denied!=null){sendError(client,denied);return;}
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
            room.handleDisconnect(client);
            boolean canDelete = room.getPlayerCount() == 0
                    && !DEFAULT_ROOM_CODE.equalsIgnoreCase(room.getRoomCode())
                    && !"PLAYING".equals(room.getGameState());
            if (canDelete) rooms.remove(room.getRoomCode());
            broadcastRoomListToLobby();
        }
    }

    @Override
    public synchronized void close() {
        rooms.values().forEach(WheelRoom::close);
        rooms.clear();
        clientRoomMap.clear();
        lobbyClients.clear();
        scheduler.shutdownNow();
    }

    private void sendError(WebSocketClient client, String text) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "wheel_error");
        msg.put("message", text);
        client.send(JsonUtil.toJson(msg));
    }
}
