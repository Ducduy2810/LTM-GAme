package vn.game.games;

import vn.game.db.UserRepository;
import vn.game.model.WheelPhrase;
import vn.game.model.WheelPhraseBank;
import vn.game.server.WebSocketClient;
import vn.game.util.JsonUtil;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class WheelRoom {

    // ─── 1. NGƯỜI CHƠI THẬT ──────────────────────────────────────────────────
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
            map.put("isBot", false);
            return map;
        }
    }

    // ─── 2. BOT PLAYER ───────────────────────────────────────────────────────
    public static class BotPlayer {
        public final String username;
        public int score = 0;
        private static final Random RNG = new Random();
        private final double skill; // 0.0 (ngu) → 1.0 (giỏi)

        private static final AtomicInteger BOT_COUNTER = new AtomicInteger(1);

        public BotPlayer(double skill) {
            this.username = "Bot_" + BOT_COUNTER.getAndIncrement();
            this.skill = Math.max(0.0, Math.min(1.0, skill));
        }

        /** Chọn chữ cái để đoán: skill cao → ưu tiên chữ có trong câu */
        public char pickLetter(Set<Character> revealed, String phrase) {
            List<Character> inPhrase = new ArrayList<>();
            for (char c = 'A'; c <= 'Z'; c++) {
                if (!revealed.contains(c) && phrase.indexOf(c) >= 0) inPhrase.add(c);
            }
            if (!inPhrase.isEmpty() && RNG.nextDouble() < (0.3 + skill * 0.6)) {
                // Ưu tiên chữ xuất hiện nhiều nhất
                char best = inPhrase.get(0);
                int bestCount = 0;
                for (char c : inPhrase) {
                    int cnt = 0;
                    for (char p : phrase.toCharArray()) if (p == c) cnt++;
                    if (cnt > bestCount) { bestCount = cnt; best = c; }
                }
                return best;
            }
            // Ngẫu nhiên từ chưa lật
            List<Character> pool = new ArrayList<>();
            for (char c = 'A'; c <= 'Z'; c++) if (!revealed.contains(c)) pool.add(c);
            if (pool.isEmpty()) return 'A';
            return pool.get(RNG.nextInt(pool.size()));
        }

        /** Thử đoán cả câu; null = không thử lượt này */
        public String trySolve(String phrase, Set<Character> revealed) {
            long unknown = phrase.chars()
                    .filter(c -> c >= 'A' && c <= 'Z' && !revealed.contains((char) c))
                    .distinct().count();
            if (unknown > skill * 3) return null;  // Còn quá nhiều ẩn
            if (RNG.nextDouble() > (0.25 + skill * 0.55)) return null;
            return phrase; // Bot biết đáp án
        }

        public Map<String, Object> toMap(boolean isHost, boolean isSuperAdmin) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", "bot-" + username);
            map.put("username", username);
            map.put("score", score);
            map.put("isReady", true);
            map.put("isHost", isHost);
            map.put("isSuperAdmin", isSuperAdmin);
            map.put("isBot", true);
            return map;
        }
    }

    // ─── 3. PLAYER SLOT (thống nhất người thật + bot) ────────────────────────
    public static class PlayerSlot {
        public final WheelPlayerState real; // null nếu là bot
        public final BotPlayer bot;          // null nếu là người thật

        public PlayerSlot(WheelPlayerState real) { this.real = real; this.bot = null; }
        public PlayerSlot(BotPlayer bot)          { this.real = null; this.bot = bot; }

        public boolean isBot()           { return bot != null; }
        public String getUsername()      { return isBot() ? bot.username : real.username; }
        public int getScore()            { return isBot() ? bot.score : real.score; }
        public void addScore(int pts)    { if (isBot()) bot.score += pts; else real.score += pts; }
        public boolean isReady()         { return isBot() || real.isReady; }

        public Map<String, Object> toMap(boolean isHost, boolean isSuperAdmin) {
            return isBot() ? bot.toMap(isHost, isSuperAdmin)
                           : real.toMap(isHost, isSuperAdmin);
        }
    }

    // ─── 4. TRẠNG THÁI PHÒNG ────────────────────────────────────────────────
    private final String roomCode;
    private final String roomName;
    private volatile String hostUsername;
    private final String topic;
    private final int timeLimit;
    private final int maxBotCount; // Số bot tối đa chủ phòng cho phép thêm
    private static final int MAX_PLAYERS = 4;

    private final UserRepository userRepository;
    private final ScheduledExecutorService scheduler;
    private final Random random = new Random();

    // Người thật: WebSocketClient → WheelPlayerState
    private final Map<WebSocketClient, WheelPlayerState> players = new ConcurrentHashMap<>();

    // Theo dõi username đã từng trong phòng (để cho phép rejoin khi đang chơi)
    private final Set<String> recentlyLeftUsernames = Collections.synchronizedSet(new HashSet<>());
    // Thứ tự lượt đấu (người thật + bot)
    private final List<PlayerSlot> orderedPlayers = new CopyOnWriteArrayList<>();

    private volatile String gameState = "WAITING";
    private volatile int turnIndex = 0;
    private volatile String currentAction = "SPIN";
    private volatile Object currentSpinValue = 0;
    private volatile WheelPhrase currentPhrase;
    private final Set<Character> revealedLetters = Collections.synchronizedSet(new HashSet<>());
    private ScheduledFuture<?> turnTimer = null;

    // ─── 5. VÒNG QUAY ────────────────────────────────────────────────────────
    private static final int[] WHEEL_VALUES  = { 100, 200, 500, 1000, 2000 };
    private static final int[] WHEEL_WEIGHTS = {  50,  25,  15,    7,    3 };

    private int spinWeighted() {
        int roll = random.nextInt(100);
        int cum = 0;
        for (int i = 0; i < WHEEL_WEIGHTS.length; i++) {
            cum += WHEEL_WEIGHTS[i];
            if (roll < cum) return WHEEL_VALUES[i];
        }
        return WHEEL_VALUES[0];
    }

    // ─── 6. CONSTRUCTOR ──────────────────────────────────────────────────────
    public WheelRoom(String roomCode, String roomName, String hostUsername,
                     String topic, int timeLimit, int maxBotCount,
                     UserRepository userRepository, ScheduledExecutorService scheduler) {
        this.roomCode  = roomCode;
        this.roomName  = (roomName != null && !roomName.trim().isEmpty()) ? roomName.trim() : ("Phòng " + roomCode);
        this.hostUsername = hostUsername;
        this.topic     = (topic != null) ? topic : "ALL";
        this.timeLimit = (timeLimit >= 5) ? timeLimit : 10;
        this.maxBotCount = Math.max(0, Math.min(3, maxBotCount)); // 0–3 bot
        this.userRepository = userRepository;
        this.scheduler = scheduler;
        this.currentPhrase = WheelPhraseBank.getRandomPhrase(this.topic);
    }

    /** Constructor tương thích ngược — mặc định 1 bot tối đa */
    public WheelRoom(String roomCode, String roomName, String hostUsername,
                     String topic, int timeLimit,
                     UserRepository userRepository, ScheduledExecutorService scheduler) {
        this(roomCode, roomName, hostUsername, topic, timeLimit, 1, userRepository, scheduler);
    }

    // ─── 7. GETTERS ──────────────────────────────────────────────────────────
    public String getRoomCode()   { return roomCode; }
    public String getRoomName()   { return roomName; }
    public String getTopic()      { return topic; }
    public int getTimeLimit()     { return timeLimit; }
    public String getGameState()  { return gameState; }
    public int getPlayerCount()   { return players.size(); }
    public int getMaxPlayers()    { return MAX_PLAYERS; }
    public String getHostUsername() { return getEffectiveHostUsername(); }

    public boolean isHostPresent() {
        if (hostUsername == null) return false;
        for (PlayerSlot s : orderedPlayers) {
            if (!s.isBot() && hostUsername.equalsIgnoreCase(s.getUsername())) return true;
        }
        return false;
    }

    public String getEffectiveHostUsername() {
        if (isHostPresent()) return hostUsername;
        for (PlayerSlot s : orderedPlayers) if (!s.isBot()) return s.getUsername();
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
        map.put("maxBotCount", maxBotCount);
        map.put("gameState", gameState);
        map.put("playerCount", players.size());
        map.put("maxPlayers", MAX_PLAYERS);
        return map;
    }

    // ─── 8. THAM GIA / RỜI PHÒNG ────────────────────────────────────────────
    public synchronized boolean addPlayer(WebSocketClient client) {
        String username = client.getUsername();
        if (username == null) return false;

        // Chặn người mới hoàn toàn khi đang chơi; cho phép người đã từng trong phòng vào lại
        boolean gameActive = "PLAYING".equals(gameState);
        if (gameActive && !recentlyLeftUsernames.contains(username.toLowerCase())) {
            sendError(client, "Trận đấu đang diễn ra! Không thể vào phòng lúc này.");
            return false;
        }
        recentlyLeftUsernames.remove(username.toLowerCase());

        removePlayerByUsername(username, client);

        if (players.size() >= MAX_PLAYERS) {
            sendError(client, "Phòng đã đầy (Tối đa 4 người)!");
            return false;
        }

        WheelPlayerState state = new WheelPlayerState(client, username);
        if (isHost(username) || isSuperAdmin(username)) state.isReady = true;

        players.put(client, state);
        orderedPlayers.add(new PlayerSlot(state));

        if (hostUsername == null || hostUsername.isEmpty()) hostUsername = username;

        sendRoomInfo(client);
        broadcastPlayers();
        broadcastChat("👋 [" + username + "] đã tham gia phòng.");

        if ("PLAYING".equals(gameState)) sendState(client);
        return true;
    }

    private void removePlayerByUsername(String username, WebSocketClient newClient) {
        Iterator<Map.Entry<WebSocketClient, WheelPlayerState>> it = players.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<WebSocketClient, WheelPlayerState> entry = it.next();
            if (entry.getValue().username.equalsIgnoreCase(username)) {
                WebSocketClient old = entry.getKey();
                if (!old.equals(newClient)) old.close();
                it.remove();
                orderedPlayers.removeIf(s -> !s.isBot() && s.real == entry.getValue());
            }
        }
    }

    public synchronized void removePlayer(WebSocketClient client) {
        removePlayer(client, false);
    }

    public synchronized void handleDisconnect(WebSocketClient client) {
        removePlayer(client, true);
    }

    private void removePlayer(WebSocketClient client, boolean disconnected) {
        boolean wasHost = isHost(client.getUsername());
        WheelPlayerState removed = players.remove(client);
        if (removed == null) return;

        // Ghi nhận username để cho phép rejoin sau
        recentlyLeftUsernames.add(removed.username.toLowerCase());

        int removeIdx = -1;
        for (int i = 0; i < orderedPlayers.size(); i++) {
            PlayerSlot s = orderedPlayers.get(i);
            if (!s.isBot() && s.real == removed) { removeIdx = i; break; }
        }
        if (removeIdx >= 0) orderedPlayers.remove(removeIdx);

        String removedUsername = removed.username;
        if (disconnected) {
            broadcast(Map.of("type", "player_disconnected", "username", removedUsername,
                    "roomCode", roomCode, "message", "Người chơi [" + removedUsername + "] đã mất kết nối."));
            broadcastChat("📡 [" + removedUsername + "] đã mất kết nối.");
        } else broadcastChat("🚪 [" + removedUsername + "] đã rời phòng.");

        if (players.isEmpty()) {
            gameState = "WAITING";
            cancelTimer();
            orderedPlayers.clear(); // Xóa luôn bot
            return;
        }

        if (wasHost) {
            // Chuyển quyền cho người thật tiếp theo (không chuyển cho bot)
            for (PlayerSlot s : orderedPlayers) {
                if (!s.isBot()) {
                    s.real.isReady = true;
                    hostUsername = s.getUsername();
                    broadcastChat("👑 [" + hostUsername + "] đã trở thành Chủ phòng mới.");
                    broadcastRoomInfoToAll();
                    break;
                }
            }
        }

        if ("PLAYING".equals(gameState)) {
            if (removeIdx >= 0 && removeIdx < turnIndex) turnIndex--;
            if (turnIndex >= orderedPlayers.size()) turnIndex = 0;
            if (removeIdx == turnIndex || orderedPlayers.isEmpty()) {
                currentAction = "SPIN";
                cancelTimer();
                if (!orderedPlayers.isEmpty()) {
                    broadcastState();
                    scheduleCurrentTurn();
                }
            }
        }
        broadcastPlayers();
    }

    public synchronized void toggleReady(WebSocketClient client) {
        WheelPlayerState state = players.get(client);
        if (state == null) return;
        if (!isHost(state.username)) state.isReady = !state.isReady;
        broadcastPlayers();
    }

    // ─── 9. BẮT ĐẦU TRẬN ────────────────────────────────────────────────────
    public synchronized void handleStartGame(WebSocketClient client) {
        String username = client.getUsername();
        if (username == null) return;
        if (!canControl(username)) {
            sendError(client, "Chỉ Chủ phòng mới có quyền bắt đầu ván đấu!");
            return;
        }
        if (!isSuperAdmin(username) && players.size() > 1) {
            for (WheelPlayerState p : players.values()) {
                if (!isHost(p.username) && !p.isReady) {
                    sendError(client, "Vẫn còn người chơi chưa bấm Sẵn sàng!");
                    return;
                }
            }
        }
        if ("WAITING".equals(gameState) || "END".equals(gameState)) startGame();
    }

    private synchronized void startGame() {
        gameState = "PLAYING";
        turnIndex = 0;
        currentAction = "SPIN";
        currentSpinValue = 0;
        revealedLetters.clear();
        currentPhrase = WheelPhraseBank.getRandomPhrase(topic);

        // Reset điểm tất cả người thật
        for (PlayerSlot s : orderedPlayers) {
            if (s.isBot()) s.bot.score = 0;
            else s.real.score = 0;
        }

        // Thêm bot theo maxBotCount được cấu hình khi tạo phòng
        // Xóa bot cũ trước
        orderedPlayers.removeIf(PlayerSlot::isBot);
        int realCount = players.size();
        // Số bot cần: đủ 2 người tối thiểu, nhưng không vượt quá maxBotCount
        int botsNeeded = (maxBotCount > 0) ? Math.min(maxBotCount, Math.max(0, 2 - realCount)) : 0;
        double[] skills = { 0.4, 0.65, 0.8 };
        for (int i = 0; i < botsNeeded && i < skills.length; i++) {
            BotPlayer bot = new BotPlayer(skills[i]);
            orderedPlayers.add(new PlayerSlot(bot));
            broadcastChat("🤖 Bot [" + bot.username + "] đã tham gia để đủ người chơi!");
        }

        broadcastChat("🎬 Trò chơi bắt đầu! Chủ đề: [" + WheelPhraseBank.getTopicDisplayName(currentPhrase.getTopic()) + "]");
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
        scheduleCurrentTurn();
    }

    // ─── 10. ĐIỀU PHỐI LƯỢT ──────────────────────────────────────────────────
    /**
     * Kiểm tra lượt hiện tại: nếu là bot thì lên lịch hành động tự động,
     * nếu là người thật thì bắt đầu đếm giờ bình thường.
     */
    private void scheduleCurrentTurn() {
        if (orderedPlayers.isEmpty()) return;
        if (turnIndex >= orderedPlayers.size()) turnIndex = 0;
        PlayerSlot current = orderedPlayers.get(turnIndex);
        if (current.isBot()) {
            scheduleBotAction(current.bot);
        } else {
            startTurnTimer();
        }
    }

    /** Lên lịch bot tự động hành động sau 1.5–2.5 giây (giống người thật) */
    private void scheduleBotAction(BotPlayer bot) {
        cancelTimer();
        long delay = 1500 + random.nextInt(1000); // 1.5s – 2.5s
        broadcastState();
        turnTimer = scheduler.schedule(() -> {
            synchronized (WheelRoom.this) {
                if (!"PLAYING".equals(gameState)) return;
                if (turnIndex >= orderedPlayers.size()) return;
                PlayerSlot slot = orderedPlayers.get(turnIndex);
                if (!slot.isBot() || slot.bot != bot) return;

                if ("SPIN".equals(currentAction)) {
                    doBotSpin(bot);
                } else if ("GUESS".equals(currentAction)) {
                    doBotGuess(bot);
                }
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void doBotSpin(BotPlayer bot) {
        int spinResult = spinWeighted();
        Map<String, Object> spinAnim = new HashMap<>();
        spinAnim.put("type", "wheel_spin_result");
        spinAnim.put("username", bot.username);
        spinAnim.put("result", spinResult);
        broadcast(spinAnim);
        broadcastChat("🎡 [" + bot.username + "] vừa quay vào ô: " + formatSpinName(spinResult));
        currentSpinValue = spinResult;
        currentAction = "GUESS";
        // Sau khi spin, lên lịch bot đoán chữ
        scheduleBotAction(bot);
    }

    private void doBotGuess(BotPlayer bot) {
        String phrase = currentPhrase.getPhrase();
        // Thử đoán cả câu trước
        String solve = bot.trySolve(phrase, new HashSet<>(revealedLetters));
        if (solve != null) {
            broadcastChat("🤖 [" + bot.username + "] thử đoán cả câu: \"" + solve + "\"");
            String target = normalizePhrase(phrase);
            if (target.equalsIgnoreCase(normalizePhrase(solve))) {
                bot.score += 1000;
                for (char c : phrase.toCharArray()) if (c >= 'A' && c <= 'Z') revealedLetters.add(c);
                endGame("🌟 Bot [" + bot.username + "] đã đoán trúng toàn bộ ô chữ! (+1000đ)");
            } else {
                broadcastChat("❌ Bot [" + bot.username + "] đoán sai câu! Mất lượt.");
                nextTurn();
            }
            return;
        }
        // Đoán chữ cái
        char letter = bot.pickLetter(new HashSet<>(revealedLetters), phrase);
        if (revealedLetters.contains(letter)) {
            broadcastChat("❌ Bot [" + bot.username + "] chọn chữ [" + letter + "] đã lật rồi! Mất lượt.");
            nextTurn();
            return;
        }
        int count = 0;
        for (char c : phrase.toCharArray()) if (c == letter) count++;
        if (count > 0) {
            revealedLetters.add(letter);
            int pts = ((Number) currentSpinValue).intValue() * count;
            bot.score += pts;
            broadcastChat("🤖 Bot [" + bot.username + "] đoán chữ [" + letter + "] — Có " + count + " chữ! +" + pts + " điểm.");
            if (isPuzzleSolved()) {
                endGame("🎉 Bot [" + bot.username + "] mở chữ cuối và giải xong ô chữ! 🏆");
                return;
            }
            // Đúng → quay tiếp
            currentAction = "SPIN";
            scheduleBotAction(bot);
        } else {
            broadcastChat("🤖 Bot [" + bot.username + "] đoán chữ [" + letter + "] — Không có! Mất lượt.");
            nextTurn();
        }
    }

    // ─── 11. HÀNH ĐỘNG NGƯỜI THẬT ────────────────────────────────────────────
    public synchronized void handleSpin(WebSocketClient client) {
        if (!"PLAYING".equals(gameState) || orderedPlayers.isEmpty()) return;
        if (turnIndex >= orderedPlayers.size()) turnIndex = 0;
        PlayerSlot current = orderedPlayers.get(turnIndex);
        if (current.isBot()) return;
        if (!current.real.client.equals(client) || !"SPIN".equals(currentAction)) return;

        cancelTimer();
        int spinResult = spinWeighted();

        Map<String, Object> spinAnim = new HashMap<>();
        spinAnim.put("type", "wheel_spin_result");
        spinAnim.put("username", current.getUsername());
        spinAnim.put("result", spinResult);
        broadcast(spinAnim);
        broadcastChat("🎡 [" + current.getUsername() + "] vừa quay vào ô: " + formatSpinName(spinResult));

        currentSpinValue = spinResult;
        currentAction = "GUESS";
        broadcastState();
        startTurnTimer();
    }

    public synchronized void handleGuess(WebSocketClient client, String letterStr) {
        if (!"PLAYING".equals(gameState) || orderedPlayers.isEmpty() || letterStr == null || letterStr.isEmpty()) return;
        if (turnIndex >= orderedPlayers.size()) turnIndex = 0;
        PlayerSlot current = orderedPlayers.get(turnIndex);
        if (current.isBot()) return;
        if (!current.real.client.equals(client) || !"GUESS".equals(currentAction)) return;

        char letter = Character.toUpperCase(letterStr.charAt(0));
        if (letter < 'A' || letter > 'Z') { sendError(client, "Chỉ đoán chữ A–Z!"); return; }

        cancelTimer();

        if (revealedLetters.contains(letter)) {
            currentAction = "WAIT";
            broadcastChat("❌ Chữ [" + letter + "] đã được lật! [" + current.getUsername() + "] mất lượt.");
            broadcastState();
            scheduler.schedule(this::nextTurn, 2, TimeUnit.SECONDS);
            return;
        }

        String phrase = currentPhrase.getPhrase();
        int count = 0;
        for (char c : phrase.toCharArray()) if (c == letter) count++;

        if (count > 0) {
            revealedLetters.add(letter);
            int pts = ((Number) currentSpinValue).intValue() * count;
            current.addScore(pts);
            broadcastChat("✅ Có " + count + " chữ [" + letter + "]! [" + current.getUsername() + "] +" + pts + " điểm.");
            if (isPuzzleSolved()) {
                endGame("🎉 [" + current.getUsername() + "] mở chữ cuối và giải xong ô chữ! 🏆");
                return;
            }
            currentAction = "WAIT";
            broadcastState();
            scheduler.schedule(() -> {
                synchronized (WheelRoom.this) {
                    if ("PLAYING".equals(gameState) && !orderedPlayers.isEmpty()) {
                        currentAction = "SPIN";
                        scheduleCurrentTurn();
                    }
                }
            }, 1500, TimeUnit.MILLISECONDS);
        } else {
            currentAction = "WAIT";
            broadcastChat("❌ Không có chữ [" + letter + "]! [" + current.getUsername() + "] mất lượt.");
            broadcastState();
            scheduler.schedule(this::nextTurn, 2, TimeUnit.SECONDS);
        }
    }

    public synchronized void handleSolvePhrase(WebSocketClient client, String solution) {
        if (!"PLAYING".equals(gameState) || orderedPlayers.isEmpty() || solution == null) return;
        if (turnIndex >= orderedPlayers.size()) turnIndex = 0;
        PlayerSlot current = orderedPlayers.get(turnIndex);
        if (current.isBot()) return;
        if (!current.real.client.equals(client)) return;

        cancelTimer();
        String target = normalizePhrase(currentPhrase.getPhrase());
        String guess  = normalizePhrase(solution);

        if (target.equalsIgnoreCase(guess)) {
            current.addScore(1000);
            for (char c : currentPhrase.getPhrase().toCharArray())
                if (c >= 'A' && c <= 'Z') revealedLetters.add(c);
            endGame("🌟 [" + current.getUsername() + "] đoán trúng toàn bộ ô chữ! (+1000đ) 🏆");
        } else {
            currentAction = "WAIT";
            broadcastChat("❌ [" + current.getUsername() + "] đoán \"" + solution.trim() + "\" — SAI! Mất lượt.");
            broadcastState();
            scheduler.schedule(this::nextTurn, 2, TimeUnit.SECONDS);
        }
    }

    // ─── 12. TIỆN ÍCH ────────────────────────────────────────────────────────
    private boolean isPuzzleSolved() {
        for (char c : currentPhrase.getPhrase().toCharArray())
            if (c >= 'A' && c <= 'Z' && !revealedLetters.contains(c)) return false;
        return true;
    }

    private String normalizePhrase(String str) {
        return str.replaceAll("\\s+", "").toUpperCase();
    }

    private synchronized void nextTurn() {
        cancelTimer();
        if (orderedPlayers.isEmpty()) { gameState = "WAITING"; return; }
        currentSpinValue = 0;
        currentAction = "SPIN";
        turnIndex = (turnIndex + 1) % orderedPlayers.size();
        broadcastState();
        scheduleCurrentTurn();
    }

    private synchronized void startTurnTimer() {
        cancelTimer();
        if (!"PLAYING".equals(gameState) || orderedPlayers.isEmpty()) return;
        if (turnIndex >= orderedPlayers.size()) turnIndex = 0;
        broadcastState();
        final int idx = turnIndex;
        turnTimer = scheduler.schedule(() -> {
            synchronized (WheelRoom.this) {
                if (!"PLAYING".equals(gameState) || idx != turnIndex) return;
                if (turnIndex >= orderedPlayers.size()) return;
                PlayerSlot cur = orderedPlayers.get(turnIndex);
                broadcastChat("⏱️ Hết giờ! [" + cur.getUsername() + "] mất lượt.");
                nextTurn();
            }
        }, timeLimit, TimeUnit.SECONDS);
    }

    public synchronized void close() {
        gameState = "CLOSED";
        cancelTimer();
        players.clear();
        orderedPlayers.clear();
    }

    private void cancelTimer() {
        if (turnTimer != null) { turnTimer.cancel(false); turnTimer = null; }
    }

    private synchronized void endGame(String reason) {
        gameState = "END";
        cancelTimer();

        PlayerSlot winner = null;
        for (PlayerSlot s : orderedPlayers)
            if (winner == null || s.getScore() > winner.getScore()) winner = s;

        List<String> winners = new ArrayList<>();
        if (winner != null && winner.getScore() > 0)
            winners.add(winner.getUsername() + " (" + winner.getScore() + "đ)");

        broadcastChat(reason);
        broadcastState();

        Map<String, Object> endMsg = new HashMap<>();
        endMsg.put("type", "wheel_end_game");
        endMsg.put("reason", reason);
        endMsg.put("winner", winner != null ? winner.getUsername() : "Không có");
        endMsg.put("winners", winners);
        endMsg.put("fullPhrase", currentPhrase.getPhrase());
        broadcast(endMsg);
    }

    // ─── 13. BROADCAST ────────────────────────────────────────────────────────
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
        for (PlayerSlot s : orderedPlayers) {
            boolean uIsHost  = !s.isBot() && isHost(s.getUsername());
            boolean uIsAdmin = !s.isBot() && isSuperAdmin(s.getUsername());
            list.add(s.toMap(uIsHost, uIsAdmin));
            if (!uIsHost && !s.isReady()) allReady = false;
        }
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "update_wheel_players");
        msg.put("players", list);
        msg.put("hostUsername", getEffectiveHostUsername());
        msg.put("allReady", allReady);
        msg.put("hasAdminInRoom", isSuperAdmin(getEffectiveHostUsername()));
        broadcast(msg);
    }

    private List<Map<String, Object>> buildPlayerList() {
        List<Map<String, Object>> pList = new ArrayList<>();
        for (PlayerSlot s : orderedPlayers) {
            boolean uIsHost  = !s.isBot() && isHost(s.getUsername());
            boolean uIsAdmin = !s.isBot() && isSuperAdmin(s.getUsername());
            pList.add(s.toMap(uIsHost, uIsAdmin));
        }
        return pList;
    }

    public void broadcastState() {
        if (orderedPlayers.isEmpty()) return;
        if (turnIndex >= orderedPlayers.size()) turnIndex = 0;
        PlayerSlot current = orderedPlayers.get(turnIndex);

        List<String> revList = new ArrayList<>();
        for (Character c : revealedLetters) revList.add(c.toString());

        Map<String, Object> msg = buildStateMap(current);
        broadcast(msg);
    }

    private void sendState(WebSocketClient client) {
        if (orderedPlayers.isEmpty()) return;
        if (turnIndex >= orderedPlayers.size()) turnIndex = 0;
        PlayerSlot current = orderedPlayers.get(turnIndex);
        client.send(JsonUtil.toJson(buildStateMap(current)));
    }

    private Map<String, Object> buildStateMap(PlayerSlot current) {
        List<String> revList = new ArrayList<>();
        for (Character c : revealedLetters) revList.add(c.toString());

        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "wheel_state");
        msg.put("players", buildPlayerList());
        msg.put("gameState", gameState);
        msg.put("turnIndex", turnIndex);
        msg.put("currentTurnUsername", current.getUsername());
        msg.put("phrase", currentPhrase.getPhrase());
        msg.put("topic", currentPhrase.getTopic());
        msg.put("topicName", WheelPhraseBank.getTopicDisplayName(currentPhrase.getTopic()));
        msg.put("hint", currentPhrase.getHint());
        msg.put("revealedLetters", revList);
        msg.put("currentAction", currentAction);
        msg.put("currentSpinValue", currentSpinValue);
        msg.put("timeLimit", timeLimit);
        msg.put("hostUsername", hostUsername);
        return msg;
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
            if (!c.isClosed()) c.send(json);
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
