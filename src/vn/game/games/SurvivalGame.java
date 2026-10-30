package vn.game.games;

import vn.game.db.UserRepository;
import vn.game.server.WebSocketClient;
import vn.game.util.JsonUtil;

import java.util.*;
import java.util.concurrent.*;

/**
 * Trình quản lý sảnh và danh sách phòng chơi Đấu Trường Sinh Tử (Survival Arena).
 * Xử lý tạo phòng, tham gia, rời phòng, bắt đầu trận đấu, nộp đáp án và quản trị phòng.
 */
public class SurvivalGame implements AutoCloseable {

    private final UserRepository userRepository;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    // Danh sách các phòng sinh tử: roomCode -> SurvivalRoom
    private final Map<String, SurvivalRoom> rooms = new ConcurrentHashMap<>();
    // Client -> Phòng hiện tại
    private final Map<WebSocketClient, SurvivalRoom> clientRoomMap = new ConcurrentHashMap<>();
    // Client đang ở Sảnh
    private final Set<WebSocketClient> lobbyClients = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public static final String DEFAULT_ROOM_CODE = "DTST";

    public SurvivalGame(UserRepository userRepository) {
        this.userRepository = userRepository;
        initDefaultRoom();
    }

    public SurvivalGame() {
        this(null);
    }

    private void initDefaultRoom() {
        SurvivalRoom defaultRoom = new SurvivalRoom(
                DEFAULT_ROOM_CODE,
                "Đấu Trường Sinh Tử Mặc Định",
                "",
                "ALL",
                100,
                25,
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
    // 1. QUẢN LÝ SẢNH (LOBBY)
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
        if (rooms.containsKey(DEFAULT_ROOM_CODE)) {
            list.add(rooms.get(DEFAULT_ROOM_CODE).toSummaryMap());
        }
        for (Map.Entry<String, SurvivalRoom> entry : rooms.entrySet()) {
            if (!DEFAULT_ROOM_CODE.equalsIgnoreCase(entry.getKey())) {
                list.add(entry.getValue().toSummaryMap());
            }
        }
        return list;
    }

    public void sendRoomList(WebSocketClient client) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "survival_room_list");
        msg.put("rooms", getRoomsSummaryList());
        client.send(JsonUtil.toJson(msg));
    }

    public void broadcastRoomListToLobby() {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "survival_room_list");
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
        String denied=vn.game.db.AdminStore.newMatchDenied("SURVIVAL");
        if(denied!=null){sendError(client,denied);return;}
        req=vn.game.db.AdminStore.applyDefaults("SURVIVAL",req);
        String username = client.getUsername();
        if (username == null) return;

        String code = (String) req.get("roomCode");
        if (code == null || code.trim().isEmpty()) {
            code = "ST" + (1000 + new Random().nextInt(9000));
        } else {
            code = code.trim().toUpperCase().replaceAll("[^A-Z0-9_-]", "");
            if (code.isEmpty()) code = "ST" + (1000 + new Random().nextInt(9000));
        }

        if (!code.matches("[A-Z0-9_-]{1,32}")) {sendError(client,"Invalid room code: use 1-32 letters, digits, _ or -.");return;}
        if (rooms.containsKey(code)) {
            sendError(client, "Mã phòng [" + code + "] đã tồn tại! Vui lòng chọn mã khác.");
            return;
        }

        String name  = (String) req.get("roomName");
        String topic = (String) req.get("topic");

        int startingHp = req.get("startingHp") instanceof Number
                ? ((Number) req.get("startingHp")).intValue() : 100;
        int totalRounds = req.get("totalRounds") instanceof Number
                ? ((Number) req.get("totalRounds")).intValue() : 25;

        SurvivalRoom newRoom = new SurvivalRoom(
                code, name, username,
                topic, startingHp, totalRounds,
                userRepository, scheduler
        );
        rooms.put(code, newRoom);
        joinRoomInternal(client, newRoom);

        Map<String, Object> resp = new HashMap<>();
        resp.put("type", "create_survival_room_success");
        resp.put("roomCode", code);
        client.send(JsonUtil.toJson(resp));

        broadcastRoomListToLobby();
    }

    // ==========================================
    // 3. THAM GIA PHÒNG (JOIN ROOM)
    // ==========================================

    public synchronized void handleJoin(WebSocketClient client) {
        handleJoinRoom(client, DEFAULT_ROOM_CODE);
    }

    public synchronized void handleJoinRoom(WebSocketClient client, String roomCode) {
        if (roomCode == null || roomCode.trim().isEmpty()) roomCode = DEFAULT_ROOM_CODE;
        roomCode = roomCode.trim().toUpperCase();

        SurvivalRoom room = rooms.get(roomCode);
        if (room == null) {
            sendError(client, "Không tìm thấy phòng sinh tử với mã [" + roomCode + "]!");
            return;
        }

        joinRoomInternal(client, room);
        broadcastRoomListToLobby();
    }

    private synchronized void joinRoomInternal(WebSocketClient client, SurvivalRoom room) {
        if (!room.canJoin(client.getUsername())) { sendError(client, "Trận đấu đang diễn ra hoặc phòng đã đóng. Không thể vào lúc này."); return; }
        // Chỉ rời phòng cũ nếu client đang thực sự ở phòng nào đó
        if (clientRoomMap.containsKey(client)) {
            SurvivalRoom currentRoom = clientRoomMap.remove(client);
            if (currentRoom != null) {
                currentRoom.handleLeave(client);
                boolean canDelete = currentRoom.isEmpty()
                        && !DEFAULT_ROOM_CODE.equalsIgnoreCase(currentRoom.getRoomCode())
                        && ("WAITING".equals(currentRoom.getGameState()) || "END".equals(currentRoom.getGameState()));
                if (canDelete) { rooms.remove(currentRoom.getRoomCode()); currentRoom.close(); }
            }
        }
        lobbyClients.remove(client);
        if (room.handleJoin(client)) clientRoomMap.put(client, room);
        else lobbyClients.add(client);
    }

    // ==========================================
    // 4. RỜI PHÒNG (LEAVE ROOM)
    // ==========================================

    public synchronized void handleLeaveRoom(WebSocketClient client) {
        SurvivalRoom currentRoom = clientRoomMap.remove(client);
        if (currentRoom != null) {
            currentRoom.handleLeave(client);
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
        resp.put("type", "left_survival_room_success");
        client.send(JsonUtil.toJson(resp));

        Map<String, Object> resp2 = new HashMap<>();
        resp2.put("type", "survival_left_room_success");
        client.send(JsonUtil.toJson(resp2));

        broadcastRoomListToLobby();
    }

    // ==========================================
    // 5. SẴN SÀNG / BẮT ĐẦU / NỘP BÀI
    // ==========================================

    public void handleToggleReady(WebSocketClient client) {
        SurvivalRoom room = clientRoomMap.get(client);
        if (room != null) room.handleToggleReady(client);
    }

    public void handleStartGame(WebSocketClient client) {
        String denied=vn.game.db.AdminStore.newMatchDenied("SURVIVAL");
        if(denied!=null){sendError(client,denied);return;}
        SurvivalRoom room = clientRoomMap.get(client);
        if (room != null) {
            room.handleStartGame(client);
            broadcastRoomListToLobby();
        } else {
            sendError(client, "Bạn chưa tham gia vào phòng sinh tử nào!");
        }
    }

    public void handleSubmitAnswer(WebSocketClient client, int answerIndex) {
        SurvivalRoom room = clientRoomMap.get(client);
        if (room != null) room.handleSubmitAnswer(client, answerIndex);
    }

    public void handlePauseGame(WebSocketClient client) {
        SurvivalRoom room = clientRoomMap.get(client);
        if (room != null) room.handlePauseGame(client);
    }

    public void handleResumeGame(WebSocketClient client) {
        SurvivalRoom room = clientRoomMap.get(client);
        if (room != null) room.handleResumeGame(client);
    }

    // ==========================================
    // 6. NGẮT KẾT NỐI & QUẢN TRỊ
    // ==========================================

    public synchronized void handleDisconnect(WebSocketClient client) {
        lobbyClients.remove(client);
        SurvivalRoom room = clientRoomMap.remove(client);
        if (room != null) {
            room.handleDisconnect(client);
            boolean canDelete = room.isEmpty()
                    && !DEFAULT_ROOM_CODE.equalsIgnoreCase(room.getRoomCode())
                    && ("WAITING".equals(room.getGameState()) || "END".equals(room.getGameState()));
            if (canDelete) { rooms.remove(room.getRoomCode()); room.close(); }
            broadcastRoomListToLobby();
        }
    }

    public synchronized void handleAdminCloseRoom(WebSocketClient client, String code) {
        if(code==null){sendError(client,"Thiếu mã phòng.");return;}
        code=code.trim().toUpperCase(Locale.ROOT);
        SurvivalRoom room=rooms.get(code);
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
            try{vn.game.db.AdminStore.audit(client.getUsername(),"HOST_ROOM_DELETE","SURVIVAL:"+code,Map.of("state",room.getGameState()));}
            catch(java.sql.SQLException e){sendError(client,"Không ghi được nhật ký. Vui lòng thử lại.");return;}
            adminCancelRoom(code,"Chủ phòng đã xóa phòng chờ");
        }
    }

    public synchronized void adminCancelRoom(String code,String reason) {
        if(code==null)throw new IllegalArgumentException("Thiếu mã phòng.");
        code=code.trim().toUpperCase(Locale.ROOT);
        SurvivalRoom room=rooms.remove(code);
        if(room==null)throw new IllegalArgumentException("Phòng không còn tồn tại.");
        String notification=reason+(Set.of("WAITING","END").contains(room.getGameState())?".":". Trận đấu đã hủy.");
        room.close();
        for (Map.Entry<WebSocketClient,SurvivalRoom> entry:new ArrayList<>(clientRoomMap.entrySet())) {
            if(entry.getValue()==room) {
                WebSocketClient member=entry.getKey();clientRoomMap.remove(member);
                member.send(JsonUtil.toJson(Map.of("type","survival_room_closed","message",notification)));
                registerLobbyClient(member);
            }
        }
        if(DEFAULT_ROOM_CODE.equalsIgnoreCase(code))initDefaultRoom();
        broadcastRoomListToLobby();
    }

    public synchronized List<Map<String,Object>> adminRooms() {
        List<Map<String,Object>> result=new ArrayList<>();
        for(SurvivalRoom room:rooms.values()) {
            Map<String,Object> row=new LinkedHashMap<>(room.toSummaryMap());
            row.put("game","SURVIVAL");
            List<Map<String,Object>> members=new ArrayList<>();
            for(Map.Entry<WebSocketClient,SurvivalRoom> entry:clientRoomMap.entrySet())if(entry.getValue()==room)
                members.add(Map.of("username",entry.getKey().getUsername(),"host",room.isHost(entry.getKey().getUsername())));
            row.put("members",members);result.add(row);
        }return result;
    }

    public synchronized void adminNotice(String code,String message) {
        SurvivalRoom room=rooms.get(code.toUpperCase(Locale.ROOT));
        if(room==null)throw new IllegalArgumentException("Phòng không còn tồn tại.");
        for(Map.Entry<WebSocketClient,SurvivalRoom> entry:clientRoomMap.entrySet())if(entry.getValue()==room)
            entry.getKey().send(JsonUtil.toJson(Map.of("type","server_notice","message",message)));
    }

    public synchronized void adminKick(String code,String target,String actor,String reason) {
        SurvivalRoom room=rooms.get(code.toUpperCase(Locale.ROOT));
        if(room==null)throw new IllegalArgumentException("Phòng không còn tồn tại.");
        kickMember(room,target,actor,true,reason);
    }

    public synchronized void handleHostKick(WebSocketClient actor,String target) {
        SurvivalRoom room=clientRoomMap.get(actor);
        if(room==null||!room.isHost(actor.getUsername())) {sendError(actor,"Chỉ chủ phòng được kick thành viên.");return;}
        try {kickMember(room,target,actor.getUsername(),false,"Chủ phòng đã mời bạn rời phòng.");}
        catch(IllegalArgumentException e){sendError(actor,e.getMessage());}
    }

    private void kickMember(SurvivalRoom room,String target,String actor,boolean admin,String reason) {
        if(target==null||target.isBlank())throw new IllegalArgumentException("Hãy chọn người chơi cần kick.");
        if(!admin&&(actor.equalsIgnoreCase(target)||isAdmin(target)))throw new IllegalArgumentException("Không thể kick bản thân hoặc quản trị viên.");
        WebSocketClient member=null;
        for(Map.Entry<WebSocketClient,SurvivalRoom> entry:clientRoomMap.entrySet())
            if(entry.getValue()==room&&target.equalsIgnoreCase(entry.getKey().getUsername())){member=entry.getKey();break;}
        if(member==null)throw new IllegalArgumentException("Người chơi không ở trong phòng này.");
        try {vn.game.db.AdminStore.audit(actor,admin?"ADMIN_KICK":"HOST_KICK","SURVIVAL:"+room.getRoomCode(),Map.of("target",target,"reason",reason));}
        catch(java.sql.SQLException e){throw new IllegalStateException("Không ghi được nhật ký kick.",e);}
        room.banPlayer(target);
        handleLeaveRoom(member);
        member.send(JsonUtil.toJson(Map.of("type","room_kicked","game","SURVIVAL","roomCode",room.getRoomCode(),"message",reason)));
        for(Map.Entry<WebSocketClient,SurvivalRoom> entry:clientRoomMap.entrySet())if(entry.getValue()==room)
            entry.getKey().send(JsonUtil.toJson(Map.of("type","server_notice","message",target+" đã bị mời rời phòng.")));
        broadcastRoomListToLobby();
    }

    @Override
    public synchronized void close() {
        rooms.values().forEach(SurvivalRoom::close);
        rooms.clear();
        clientRoomMap.clear();
        lobbyClients.clear();
        scheduler.shutdownNow();
    }

    private void sendError(WebSocketClient client, String message) {
        Map<String, Object> err = new HashMap<>();
        err.put("type", "survival_error");
        err.put("message", message);
        client.send(JsonUtil.toJson(err));
    }
}
