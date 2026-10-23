package vn.game.games;

import vn.game.db.UserRepository;
import vn.game.model.WheelPhrase;
import vn.game.model.WheelPhraseBank;
import vn.game.server.WebSocketClient;
import vn.game.util.JsonUtil;

import java.util.*;
import java.util.concurrent.*;

public class WheelRoom {

    public static class WheelPlayerState {
        public final WebSocketClient client;
        public final String username;
        public int score = 0;
        public boolean isReady = false;

        public WheelPlayerState(WebSocketClient client, String username) {
            this.client = client;
            this.username = username;
        }

        public Map<String, Object> toMap(boolean isHost, boolean isSuperAdmin) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", client.getId());
            map.put("username", username);
            map.put("score", score);
            map.put("isReady", isReady);
            map.put("isHost", isHost);
            map.put("isSuperAdmin", isSuperAdmin);
            return map;
        }
    }

    private final String roomCode;
    private final String roomName;
    private volatile String hostUsername;
    private final String topic;
    private final int timeLimit; // giây mỗi lượt
    private static final int MAX_PLAYERS = 4;

    private final UserRepository userRepository;
    private final ScheduledExecutorService scheduler;
    private final Random random = new Random();

    // Danh sách người chơi trong phòng (tối đa 4 người)
    private final Map<WebSocketClient, WheelPlayerState> players = new ConcurrentHashMap<>();
    private final List<WheelPlayerState> orderedPlayers = new CopyOnWriteArrayList<>();

    private volatile String gameState = "WAITING"; // WAITING, PLAYING, END
    private volatile int turnIndex = 0;
    private volatile String currentAction = "SPIN"; // SPIN, GUESS, WAIT
    private volatile Object currentSpinValue = 0;
    private volatile WheelPhrase currentPhrase;
    private final Set<Character> revealedLetters = Collections.synchronizedSet(new HashSet<>());
    private ScheduledFuture<?> turnTimer = null;

    // Các ô trên nón kỳ diệu với tỉ lệ:
    // Xanh dương 100đ: 50% | Vàng 200đ: 25% | Đỏ 500đ: 15% | Đen 1000đ: 7% | Tím 2000đ: 3%
    private static final int[]  WHEEL_VALUES  = {  100,  200,  500,  1000,  2000 };
    private static final int[]  WHEEL_WEIGHTS = {   50,   25,   15,     7,     3 };

    private int spinWeighted() {
        int roll = random.nextInt(100);
        int cum = 0;
        for (int i = 0; i < WHEEL_WEIGHTS.length; i++) {
            cum += WHEEL_WEIGHTS[i];
            if (roll < cum) return WHEEL_VALUES[i];
        }
        return WHEEL_VALUES[0];
    }

    public WheelRoom(String roomCode, String roomName, String hostUsername,
                     String topic, int timeLimit,
                     UserRepository userRepository, ScheduledExecutorService scheduler) {
        this.roomCode = roomCode;
        this.roomName = (roomName != null && !roomName.trim().isEmpty()) ? roomName.trim() : ("Phòng " + roomCode);
        this.hostUsername = hostUsername;
        this.topic = (topic != null) ? topic : "ALL";
        this.timeLimit = (timeLimit >= 5) ? timeLimit : 10;
        this.userRepository = userRepository;
        this.scheduler = scheduler;
        this.currentPhrase = WheelPhraseBank.getRandomPhrase(this.topic);
    }

    public String getRoomCode() { return roomCode; }
    public String getRoomName() { return roomName; }
    public String getHostUsername() { return getEffectiveHostUsername(); }
    public String getTopic() { return topic; }
    public int getTimeLimit() { return timeLimit; }
    public String getGameState() { return gameState; }
    public int getPlayerCount() { return players.size(); }
    public int getMaxPlayers() { return MAX_PLAYERS; }

    public boolean isHostPresent() {
        if (hostUsername == null) return false;
        for (WheelPlayerState p : orderedPlayers) {
            if (hostUsername.equalsIgnoreCase(p.username)) return true;
        }
        return false;
    }

    public String getEffectiveHostUsername() {
        if (isHostPresent()) return hostUsername;
        if (!orderedPlayers.isEmpty()) return orderedPlayers.get(0).username;
        return hostUsername;
    }

    public boolean isHost(String username) {
        if (username == null) return false;
        String eff = getEffectiveHostUsername();
        return eff != null && eff.equalsIgnoreCase(username);
    }

    public boolean isSuperAdmin(String username) {
        if (username == null) return false;
        if ("admin".equalsIgnoreCase(username) || "DuyNguyen".equalsIgnoreCase(username)) return true;
        return userRepository != null && userRepository.isAdmin(username);
    }

    public boolean canControl(String username) {
        return isHost(username) || isSuperAdmin(username);
    }

    public Map<String, Object> toSummaryMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("roomCode", roomCode);
        map.put("roomName", roomName);
        map.put("hostUsername", getEffectiveHostUsername());
        map.put("topic", topic);
        map.put("topicName", WheelPhraseBank.getTopicDisplayName(topic));
        map.put("timeLimit", timeLimit);
        map.put("gameState", gameState);
        map.put("playerCount", players.size());
        map.put("maxPlayers", MAX_PLAYERS);
        return map;
    }

    public synchronized boolean addPlayer(WebSocketClient client) {
        String username = client.getUsername();
        if (username == null) return false;

        // Kiểm tra đang trong trận đấu: chỉ cho phép reconnect, không cho người mới vào
        boolean isReconnecting = false;
        for (WheelPlayerState ps : players.values()) {
            if (username.equalsIgnoreCase(ps.username)) {
                isReconnecting = true;
                break;
            }
        }
        if ("PLAYING".equals(gameState) && !isReconnecting) {
            sendError(client, "Trận đấu đang diễn ra! Không thể vào phòng lúc này.");
            return false;
        }

        // Xóa kết nối cũ nếu cùng 1 username vào lại
        removePlayerByUsername(username, client);

        if (players.size() >= MAX_PLAYERS) {
            sendError(client, "Phòng này đã đầy (Tối đa 4 người chơi)!");
            return false;
        }

        WheelPlayerState state = new WheelPlayerState(client, username);
        
        // Khôi phục quyền chủ phòng nếu đây là chủ phòng gốc
        if (isHost(username) || isSuperAdmin(username)) {
            state.isReady = true;
        }

        players.put(client, state);
        orderedPlayers.add(state);

        // Chỉ gán hostUsername lần đầu tiên
        if (hostUsername == null || hostUsername.isEmpty()) {
            hostUsername = username;
        }

        sendRoomInfo(client);
        broadcastPlayers();
        broadcastChat("👋 [" + username + "] đã tham gia phòng.");

        if ("PLAYING".equals(gameState)) {
            sendState(client);
        }

        return true;
    }

    private void removePlayerByUsername(String username, WebSocketClient newClient) {
        for (Map.Entry<WebSocketClient, WheelPlayerState> entry : players.entrySet()) {
            if (entry.getValue().username.equalsIgnoreCase(username)) {
                WebSocketClient oldClient = entry.getKey();
                if (!oldClient.equals(newClient)) {
                    oldClient.close();
                }
                players.remove(oldClient);
                orderedPlayers.remove(entry.getValue());
            }
        }
    }

    public synchronized void removePlayer(WebSocketClient client) {
        WheelPlayerState removed = players.remove(client);
        if (removed != null) {
            int removeIdx = orderedPlayers.indexOf(removed);
            String removedUsername = removed.username;
            boolean wasHost = isHost(removedUsername);
            
            orderedPlayers.remove(removed);
            broadcastChat("🚪 [" + removedUsername + "] đã rời phòng.");

            if (players.isEmpty()) {
                gameState = "WAITING";
                cancelTimer();
                return;
            }

            // Nếu Chủ phòng rời đi, chuyển quyền cho người tiếp theo
            if (wasHost && !orderedPlayers.isEmpty()) {
                WheelPlayerState tempHost = orderedPlayers.get(0);
                tempHost.isReady = true;
                hostUsername = tempHost.username;
                broadcastChat("👑 [" + tempHost.username + "] đã trở thành Chủ phòng mới.");
                broadcastRoomInfoToAll();
            }

            // Nếu đang trong trận đấu mà người đến lượt rời đi -> chuyển lượt ngay
            if ("PLAYING".equals(gameState)) {
                if (removeIdx != -1) {
                    if (removeIdx < turnIndex) {
                        turnIndex--;
                    }
                    if (removeIdx == turnIndex) {
                        if (turnIndex >= orderedPlayers.size()) turnIndex = 0;
                        currentAction = "SPIN";
                        cancelTimer();
                        broadcastState();
                        startTurnTimer();
                    }
                }
            }

            broadcastPlayers();
        }
    }

    public synchronized void toggleReady(WebSocketClient client) {
        WheelPlayerState state = players.get(client);
        if (state == null) return;
        if (!isHost(state.username)) {
            state.isReady = !state.isReady;
        }
        broadcastPlayers();
    }

    public synchronized void handleStartGame(WebSocketClient client) {
        String username = client.getUsername();
        if (username == null) return;

        if (!canControl(username)) {
            sendError(client, "Chỉ Chủ phòng mới có quyền bấm bắt đầu ván đấu!");
            return;
        }

        // Kiểm tra điều kiện bắt đầu
        if (!isSuperAdmin(username) && orderedPlayers.size() > 1) {
            boolean allReady = true;
            for (WheelPlayerState p : orderedPlayers) {
                if (!isHost(p.username) && !p.isReady) {
                    allReady = false;
                    break;
                }
            }
            if (!allReady) {
                sendError(client, "Chưa thể bắt đầu! Vẫn còn người chơi chưa bấm 'Sẵn sàng'.");
                return;
            }
        }

        if ("WAITING".equals(gameState) || "END".equals(gameState)) {
            startGame();
        }
    }

    private synchronized void startGame() {
        gameState = "PLAYING";
        turnIndex = 0;
        currentAction = "SPIN";
        revealedLetters.clear();

        // Lấy ngẫu nhiên ô chữ mới từ ngân hàng câu hỏi theo chủ đề
        currentPhrase = WheelPhraseBank.getRandomPhrase(topic);

        for (WheelPlayerState p : orderedPlayers) {
            p.score = 0;
        }

        broadcastChat("🎬 Trò chơi bắt đầu! Chủ đề ô chữ: [" + WheelPhraseBank.getTopicDisplayName(currentPhrase.getTopic()) + "]");
        broadcastChat("💡 Gợi ý: " + currentPhrase.getHint());

        Map<String, Object> startMsg = new HashMap<>();
        startMsg.put("type", "wheel_game_started");
        startMsg.put("roomCode", roomCode);
        startMsg.put("roomName", roomName);
        startMsg.put("topic", currentPhrase.getTopic());
        startMsg.put("topicName", WheelPhraseBank.getTopicDisplayName(currentPhrase.getTopic()));
        startMsg.put("hint", currentPhrase.getHint());
        broadcast(startMsg);

        broadcastState();
        startTurnTimer();
    }

    public synchronized void handleSpin(WebSocketClient client) {
        if (!"PLAYING".equals(gameState) || orderedPlayers.isEmpty()) return;
        if (turnIndex >= orderedPlayers.size()) turnIndex = 0;

        WheelPlayerState current = orderedPlayers.get(turnIndex);
        if (!current.client.equals(client) || !"SPIN".equals(currentAction)) {
            return;
        }

        cancelTimer();
        int spinResult = spinWeighted();

        // Phát thông báo quay nón kèm kết quả
        Map<String, Object> spinAnim = new HashMap<>();
        spinAnim.put("type", "wheel_spin_result");
        spinAnim.put("username", current.username);
        spinAnim.put("result", spinResult);
        broadcast(spinAnim);

        broadcastChat("🎡 [" + current.username + "] vừa quay vào ô: " + formatSpinName(spinResult));

        // Tất cả kết quả quay đều là điểm số (int), không còn ô đặc biệt
        currentSpinValue = spinResult;
        currentAction = "GUESS";
        broadcastState();
        startTurnTimer();
    }

    public synchronized void handleGuess(WebSocketClient client, String letterStr) {
        if (!"PLAYING".equals(gameState) || orderedPlayers.isEmpty() || letterStr == null || letterStr.isEmpty()) return;
        if (turnIndex >= orderedPlayers.size()) turnIndex = 0;

        WheelPlayerState current = orderedPlayers.get(turnIndex);
        if (!current.client.equals(client) || !"GUESS".equals(currentAction)) return;

        char letter = Character.toUpperCase(letterStr.charAt(0));
        if (letter < 'A' || letter > 'Z') {
            sendError(client, "Vui lòng chỉ đoán các chữ cái từ A đến Z!");
            return;
        }

        cancelTimer();

        if (revealedLetters.contains(letter)) {
            currentAction = "WAIT";
            broadcastChat("❌ Chữ cái [" + letter + "] đã được lật rồi. Rất tiếc bạn bị mất lượt!");
            broadcastState();
            scheduler.schedule(this::nextTurn, 2, TimeUnit.SECONDS);
            return;
        }

        String phrase = currentPhrase.getPhrase();
        int count = 0;
        for (char c : phrase.toCharArray()) {
            if (c == letter) count++;
        }

        if (count > 0) {
            revealedLetters.add(letter);
            int pts = ((Number) currentSpinValue).intValue() * count;
            current.score += pts;
            broadcastChat("✅ Chính xác! Có " + count + " chữ [" + letter + "]! [" + current.username + "] nhận +" + pts + " điểm.");

            // Kiểm tra xem đã giải xong ô chữ chưa
            if (isPuzzleSolved()) {
                endGame("🎉 CHIẾN THẮNG! [" + current.username + "] đã mở chữ cái cuối cùng và giải mã ô chữ thành công! 🏆");
                return;
            }

            // Đúng thì được thưởng quay tiếp sau 1.5 giây
            currentAction = "WAIT";
            broadcastState();
            scheduler.schedule(() -> {
                synchronized (WheelRoom.this) {
                    if ("PLAYING".equals(gameState) && !orderedPlayers.isEmpty()) {
                        currentAction = "SPIN";
                        startTurnTimer();
                    }
                }
            }, 1500, TimeUnit.MILLISECONDS);
        } else {
            currentAction = "WAIT";
            broadcastChat("❌ Không có chữ [" + letter + "] nào trong ô chữ! [" + current.username + "] mất lượt.");
            broadcastState();
            scheduler.schedule(this::nextTurn, 2, TimeUnit.SECONDS);
        }
    }

    public synchronized void handleSolvePhrase(WebSocketClient client, String solution) {
        if (!"PLAYING".equals(gameState) || orderedPlayers.isEmpty() || solution == null) return;
        if (turnIndex >= orderedPlayers.size()) turnIndex = 0;

        WheelPlayerState current = orderedPlayers.get(turnIndex);
        if (!current.client.equals(client)) return;

        cancelTimer();
        String target = normalizePhrase(currentPhrase.getPhrase());
        String guess = normalizePhrase(solution);

        if (target.equalsIgnoreCase(guess)) {
            // Đoán trúng cả ô chữ!
            current.score += 1000;
            // Lật tất cả các chữ cái
            for (char c : currentPhrase.getPhrase().toCharArray()) {
                if (c >= 'A' && c <= 'Z') revealedLetters.add(c);
            }
            endGame("🌟 XUẤT SẮC! [" + current.username + "] đã đoán trúng toàn bộ ô chữ: \"" + currentPhrase.getPhrase() + "\" (+1000đ)! 🏆");
        } else {
            currentAction = "WAIT";
            broadcastChat("❌ [" + current.username + "] đoán ô chữ \"" + solution.trim() + "\" nhưng KHÔNG CHÍNH XÁC! Mất lượt.");
            broadcastState();
            scheduler.schedule(this::nextTurn, 2, TimeUnit.SECONDS);
        }
    }

    private boolean isPuzzleSolved() {
        String phrase = currentPhrase.getPhrase();
        for (char c : phrase.toCharArray()) {
            if (c >= 'A' && c <= 'Z' && !revealedLetters.contains(c)) {
                return false;
            }
        }
        return true;
    }

    private String normalizePhrase(String str) {
        return str.replaceAll("\\s+", "").toUpperCase();
    }

    private synchronized void nextTurn() {
        cancelTimer();
        if (orderedPlayers.isEmpty()) {
            gameState = "WAITING";
            return;
        }
        currentSpinValue = 0; // Reset giá trị ô quay cho lượt mới
        currentAction = "SPIN";
        turnIndex = (turnIndex + 1) % orderedPlayers.size();
        broadcastState();
        startTurnTimer();
    }

    private synchronized void startTurnTimer() {
        cancelTimer();
        if (!"PLAYING".equals(gameState) || orderedPlayers.isEmpty()) return;
        if (turnIndex >= orderedPlayers.size()) turnIndex = 0;

        broadcastState();
        turnTimer = scheduler.schedule(() -> {
            synchronized (WheelRoom.this) {
                if ("PLAYING".equals(gameState) && !orderedPlayers.isEmpty() && turnIndex < orderedPlayers.size()) {
                    WheelPlayerState current = orderedPlayers.get(turnIndex);
                    broadcastChat("⏱️ Hết " + timeLimit + "s! [" + current.username + "] không kịp thao tác và bị mất lượt.");
                    nextTurn();
                }
            }
        }, timeLimit, TimeUnit.SECONDS);
    }

    private void cancelTimer() {
        if (turnTimer != null) {
            turnTimer.cancel(false);
            turnTimer = null;
        }
    }

    private synchronized void endGame(String reason) {
        gameState = "END";
        cancelTimer();

        // Tìm quán quân có điểm cao nhất
        WheelPlayerState winner = null;
        for (WheelPlayerState p : orderedPlayers) {
            if (winner == null || p.score > winner.score) {
                winner = p;
            }
        }

        List<String> winners = new ArrayList<>();
        if (winner != null && winner.score > 0) {
            winners.add(winner.username + " (" + winner.score + "đ)");
        }

        broadcastChat(reason);
        broadcastState();

        Map<String, Object> endMsg = new HashMap<>();
        endMsg.put("type", "wheel_end_game");
        endMsg.put("reason", reason);
        endMsg.put("winner", winner != null ? winner.username : "Không có");
        endMsg.put("winners", winners);
        endMsg.put("fullPhrase", currentPhrase.getPhrase());
        broadcast(endMsg);
    }

    public void sendRoomInfo(WebSocketClient client) {
        Map<String, Object> info = new HashMap<>();
        info.put("type", "wheel_room_info");
        info.put("roomCode", roomCode);
        info.put("roomName", roomName);
        info.put("hostUsername", getEffectiveHostUsername());
        info.put("topic", topic);
        info.put("topicName", WheelPhraseBank.getTopicDisplayName(topic));
        info.put("timeLimit", timeLimit);
        info.put("maxPlayers", MAX_PLAYERS);
        client.send(JsonUtil.toJson(info));
    }

    public void broadcastRoomInfoToAll() {
        Map<String, Object> info = new HashMap<>();
        info.put("type", "wheel_room_info");
        info.put("roomCode", roomCode);
        info.put("roomName", roomName);
        info.put("hostUsername", getEffectiveHostUsername());
        info.put("topic", topic);
        info.put("topicName", WheelPhraseBank.getTopicDisplayName(topic));
        info.put("timeLimit", timeLimit);
        info.put("maxPlayers", MAX_PLAYERS);
        broadcast(info);
    }

    public void broadcastPlayers() {
        List<Map<String, Object>> list = new ArrayList<>();
        boolean allReady = true;

        for (WheelPlayerState p : orderedPlayers) {
            boolean userIsHost = isHost(p.username);
            boolean userIsAdmin = isSuperAdmin(p.username);
            list.add(p.toMap(userIsHost, userIsAdmin));

            if (!userIsHost && !p.isReady) {
                allReady = false;
            }
        }

        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "update_wheel_players");
        msg.put("players", list);
        msg.put("hostUsername", getEffectiveHostUsername());
        msg.put("allReady", allReady);
        msg.put("hasAdminInRoom", isSuperAdmin(getEffectiveHostUsername()));
        broadcast(msg);
    }

    public void broadcastState() {
        if (orderedPlayers.isEmpty()) return;
        if (turnIndex >= orderedPlayers.size()) turnIndex = 0;

        List<Map<String, Object>> pList = new ArrayList<>();
        for (WheelPlayerState p : orderedPlayers) {
            pList.add(p.toMap(isHost(p.username), isSuperAdmin(p.username)));
        }

        List<String> revList = new ArrayList<>();
        for (Character c : revealedLetters) revList.add(c.toString());

        WheelPlayerState current = orderedPlayers.get(turnIndex);

        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "wheel_state");
        msg.put("players", pList);
        msg.put("gameState", gameState);
        msg.put("turnIndex", turnIndex);
        msg.put("currentTurnUsername", current.username);
        msg.put("phrase", currentPhrase.getPhrase());
        msg.put("topic", currentPhrase.getTopic());
        msg.put("topicName", WheelPhraseBank.getTopicDisplayName(currentPhrase.getTopic()));
        msg.put("hint", currentPhrase.getHint());
        msg.put("revealedLetters", revList);
        msg.put("currentAction", currentAction);
        msg.put("currentSpinValue", currentSpinValue);
        msg.put("timeLimit", timeLimit);
        msg.put("hostUsername", hostUsername);

        broadcast(msg);
    }

    private void sendState(WebSocketClient client) {
        if (orderedPlayers.isEmpty()) return;
        if (turnIndex >= orderedPlayers.size()) turnIndex = 0;

        List<Map<String, Object>> pList = new ArrayList<>();
        for (WheelPlayerState p : orderedPlayers) {
            pList.add(p.toMap(isHost(p.username), isSuperAdmin(p.username)));
        }

        List<String> revList = new ArrayList<>();
        for (Character c : revealedLetters) revList.add(c.toString());

        WheelPlayerState current = orderedPlayers.get(turnIndex);

        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "wheel_state");
        msg.put("players", pList);
        msg.put("gameState", gameState);
        msg.put("turnIndex", turnIndex);
        msg.put("currentTurnUsername", current.username);
        msg.put("phrase", currentPhrase.getPhrase());
        msg.put("topic", currentPhrase.getTopic());
        msg.put("topicName", WheelPhraseBank.getTopicDisplayName(currentPhrase.getTopic()));
        msg.put("hint", currentPhrase.getHint());
        msg.put("revealedLetters", revList);
        msg.put("currentAction", currentAction);
        msg.put("currentSpinValue", currentSpinValue);
        msg.put("timeLimit", timeLimit);
        msg.put("hostUsername", hostUsername);

        client.send(JsonUtil.toJson(msg));
    }

    private void broadcastChat(String text) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "wheel_chat");
        msg.put("message", text);
        broadcast(msg);
    }

    private void sendError(WebSocketClient client, String text) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "wheel_error");
        msg.put("message", text);
        client.send(JsonUtil.toJson(msg));
    }

    private void broadcast(Map<String, Object> map) {
        String json = JsonUtil.toJson(map);
        for (WebSocketClient c : players.keySet()) {
            if (!c.isClosed()) {
                c.send(json);
            }
        }
    }

    private String formatSpinName(int val) {
        if (val == 100)  return "🔵 100 ĐIỂM";
        if (val == 200)  return "🟡 200 ĐIỂM";
        if (val == 500)  return "🔴 500 ĐIỂM";
        if (val == 1000) return "⚫ 1000 ĐIỂM";
        if (val == 2000) return "🟣 2000 ĐIỂM";
        return val + " ĐIỂM";
    }
}
