package vn.game.games;

import vn.game.db.UserRepository;
import vn.game.model.MatchService;
import vn.game.model.Leaderboard;
import vn.game.model.Question;
import vn.game.model.QuestionBank;
import vn.game.server.WebSocketClient;
import vn.game.util.JsonUtil;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ArenaRoom v2 – Luật Đấu Trường 100 gốc:
 *   - Một Người Chơi Chính (mainPlayer) đối đầu với N Đối Thủ (opponents).
 *   - Có thể pha Bot (BotOpponent) để đủ số lượng đối thủ.
 *   - Mỗi câu: nếu mainPlayer đúng → loại các đối thủ sai.
 *               nếu mainPlayer sai → mainPlayer thua, đối thủ còn lại thắng.
 *               nếu hết đối thủ → mainPlayer thắng.
 *   - Ba quyền trợ giúp (một lần mỗi loại): TRUST, ASK, SURVEY.
 *   - Điểm tính theo bảng mốc phần trăm đối thủ đã loại.
 */
public class ArenaRoom {

    // ─── 1. CẤU TRÚC NGƯỜI CHƠI THẬT ─────────────────────────────────────────

    public static class PlayerState {
        public final WebSocketClient client;
        public final String username;
        public volatile boolean isReady = false;
        // Trong trận
        public volatile boolean isAlive = true;
        public volatile boolean isMainPlayer = false; // Người Chơi Chính
        public volatile Integer answer = null;         // 0-3
        public volatile long answerTimeMs = Long.MAX_VALUE;
        public volatile int score = 0;
        // Quyền trợ giúp (main player only)
        public volatile boolean lifeTrustUsed = false;  // Tin người chơi
        public volatile boolean lifeAskUsed = false;    // Hỏi người chơi
        public volatile boolean lifeSurveyUsed = false; // Khảo sát

        public PlayerState(WebSocketClient client, String username) {
            this.client = client;
            this.username = username;
        }
    }

    // ─── 2. CẤU TRÚC BOT ĐỐI THỦ ─────────────────────────────────────────────

    public static class BotOpponent {
        public final String name;
        public volatile boolean isAlive = true;
        public volatile int answer = -1; // -1 = chưa trả lời
        private static final Random RNG = new Random();

        public BotOpponent(String name) { this.name = name; }

        /**
         * Bot trả lời dựa theo xác suất đúng tương ứng độ khó câu hỏi.
         * EASY: 75% | MEDIUM: 55% | HARD: 35%
         */
        public int generateAnswer(String difficulty, int correctIndex) {
            double correctChance = 0.55;
            if ("EASY".equalsIgnoreCase(difficulty)) correctChance = 0.75;
            if ("HARD".equalsIgnoreCase(difficulty)) correctChance = 0.35;

            if (RNG.nextDouble() < correctChance) {
                answer = correctIndex;
            } else {
                int wrong;
                do { wrong = RNG.nextInt(4); } while (wrong == correctIndex);
                answer = wrong;
            }
            return answer;
        }
    }

    // ─── 3. TRẠNG THÁI PHÒNG ─────────────────────────────────────────────────

    private final String roomCode;
    private volatile String roomName;
    private volatile String hostUsername;
    private volatile String topic;
    private volatile String difficulty;
    private volatile int questionCount;
    private volatile int timeLimit; // Thời gian cho người chơi phụ/bot
    private volatile int mainPlayerTimeLimit; // YÊU CẦU 2: Thời gian riêng cho người chơi chính
    private volatile int botCount; // Số bot đối thủ được bổ sung
    private String mainSelectionMode = "MANUAL";
    private String selectedMainPlayerUsername;
    private boolean closed = false;
    private String answerPhase = "NONE"; // OPPONENTS -> MAIN
    private long phaseDeadlineNanos;
    private long phaseStartNanos;
    private long timerGeneration;

    private final Set<String> kickedUsernames=new HashSet<>();
    public synchronized void banPlayer(String username) {
        int score=players.values().stream().filter(p -> username.equalsIgnoreCase(p.username)).mapToInt(p -> p.score).findFirst().orElse(0);
        vn.game.model.MatchService.departure(matchId,username,score,"KICKED");
        kickedUsernames.add(username.toLowerCase(Locale.ROOT));
    }
    private String matchId;
    private final UserRepository userRepository;
    private final ScheduledExecutorService scheduler;

    // Người chơi thật
    private final Map<WebSocketClient, PlayerState> players = new ConcurrentHashMap<>();

    // Theo dõi username đã từng trong phòng (để cho phép rejoin khi đang chơi)
    private final Set<String> recentlyLeftUsernames = Collections.synchronizedSet(new HashSet<>());

    // Bot đối thủ (chỉ active khi PLAYING)
    private final List<BotOpponent> bots = new CopyOnWriteArrayList<>();

    // Danh sách câu hỏi trận đấu
    private final List<Question> questions = new ArrayList<>();

    private volatile String gameState = "WAITING"; // WAITING, QUESTION, RESULT, END
    private volatile int currentQuestionIndex = 0;
    private ScheduledFuture<?> currentTimer = null;
    
    // YÊU CẦU 5: Tạm dừng trận đấu
    private volatile boolean isPaused = false;
    private volatile long pauseStartTime = 0;
    private volatile long remainingTimeBeforePause = 0;

    // Người chơi chính (username)
    private volatile String mainPlayerUsername = null;

    // Thống kê trận
    private volatile int totalInitialOpponents = 0;   // Tổng đối thủ ban đầu (thật + bot)
    private volatile int totalEliminatedOpponents = 0; // Tổng đối thủ đã loại

    // ID đếm bot
    private static final AtomicInteger BOT_COUNTER = new AtomicInteger(1);

    // ─── 4. CONSTRUCTOR ───────────────────────────────────────────────────────

    public ArenaRoom(String roomCode, String roomName, String hostUsername,
                     String topic, String difficulty,
                     int questionCount, int timeLimit, int botCount,
                     UserRepository userRepository, ScheduledExecutorService scheduler) {
        this.roomCode = roomCode;
        this.roomName = (roomName != null && !roomName.trim().isEmpty()) ? roomName.trim() : ("Phòng " + roomCode);
        this.hostUsername = hostUsername;
        this.topic = (topic != null) ? topic.toUpperCase() : "ALL";
        this.difficulty = (difficulty != null) ? difficulty.toUpperCase() : "MEDIUM";
        this.questionCount = QuestionBank.getArenaQuestions(this.topic, this.difficulty, questionCount > 0 ? questionCount : 15).size();
        this.timeLimit = timeLimit;
        this.mainPlayerTimeLimit = 15;
        this.botCount = botCount >= 0 ? botCount : 0;
        this.userRepository = userRepository;
        this.scheduler = scheduler;
        this.selectedMainPlayerUsername = hostUsername;
    }

    public synchronized void configureInitialMainPlayer(int mainTime, String mode) {
        mainPlayerTimeLimit = mainTime;
        mainSelectionMode = mode;
    }

    public synchronized void handleSelectMainPlayer(WebSocketClient client, String mode, String username) {
        if (closed || !canControl(client.getUsername())) { sendError(client, "Chỉ chủ phòng hoặc admin được chọn người chơi chính."); return; }
        if (!"WAITING".equals(gameState) && !"END".equals(gameState)) { sendError(client, "Chỉ đổi người chơi chính khi đang ở phòng chờ."); return; }
        if (!Set.of("MANUAL", "RANDOM").contains(mode)) { sendError(client, "Chế độ chọn người chính không hợp lệ."); return; }
        if ("MANUAL".equals(mode) && players.values().stream().noneMatch(p -> p.username.equalsIgnoreCase(username) && !p.client.isClosed())) {
            sendError(client, "Người được chọn phải đang có mặt trong phòng."); return;
        }
        mainSelectionMode = mode;
        selectedMainPlayerUsername = "MANUAL".equals(mode) ? username : null;
        broadcastRoomInfoToAll();
        broadcastPlayers();
    }

    public synchronized boolean canJoin(String username) {
        if(username==null||kickedUsernames.contains(username.toLowerCase(Locale.ROOT)))return false;
        return username != null && !closed && ("WAITING".equals(gameState) || "END".equals(gameState)
                || players.values().stream().anyMatch(p -> p.username.equalsIgnoreCase(username))
                || recentlyLeftUsernames.contains(username.toLowerCase(Locale.ROOT)));
    }

    private void scheduleNext(Runnable action, long milliseconds) {
        if (currentTimer != null) currentTimer.cancel(false);
        long generation = ++timerGeneration;
        currentTimer = scheduler.schedule(() -> {
            synchronized (ArenaRoom.this) {
                if (!closed && generation == timerGeneration) action.run();
            }
        }, Math.max(0, milliseconds), TimeUnit.MILLISECONDS);
    }

    public synchronized void close() {
        MatchService.finish(matchId, true, "Room closed", List.of());
        closed = true;
        gameState = "CLOSED";
        answerPhase = "NONE";
        ++timerGeneration;
        if (currentTimer != null) currentTimer.cancel(false);
        players.clear();
        bots.clear();
    }

    // ─── 5. GETTER ────────────────────────────────────────────────────────────

    public String getRoomCode()    { return roomCode; }
    public String getRoomName()    { return roomName; }
    public String getHostUsername(){ return hostUsername; }
    public String getGameState()   { return gameState; }
    public int getPlayerCount()    { return players.size(); }
    public boolean isEmpty()       { return players.isEmpty(); }
    public int getBotCount()       { return botCount; }

    public boolean isSuperAdmin(String username) {
        if (username == null) return false;
        return userRepository != null && userRepository.isAdmin(username);
    }

    public boolean isHost(String username) {
        return hostUsername != null && hostUsername.equalsIgnoreCase(username);
    }

    public boolean canControl(String username) {
        return isHost(username) || isSuperAdmin(username);
    }

    // ─── 6. THAM GIA / RỜI PHÒNG / SẴN SÀNG ─────────────────────────────────

    public synchronized boolean handleJoin(WebSocketClient client) {
        String username = client.getUsername();
        if (username == null || !canJoin(username)) return false;
        PlayerState previous = players.values().stream().filter(p -> p.username.equalsIgnoreCase(username)).findFirst().orElse(null);

        // Xóa kết nối cũ cùng username nếu còn (reconnect/rejoin)
        players.keySet().removeIf(old -> {
            if (username.equalsIgnoreCase(old.getUsername())) {
                if (!old.equals(client)) old.close();
                return true;
            }
            return false;
        });

        // Chặn người mới hoàn toàn (chưa từng trong phòng) khi đang chơi.
        // Người đã rời phòng vẫn được vào lại xem / reconnect.
        boolean gameActive = !"WAITING".equals(gameState) && !"END".equals(gameState);
        if (gameActive && previous == null && !recentlyLeftUsernames.contains(username.toLowerCase())) {
            sendError(client, "Trận đấu đang diễn ra! Không thể vào phòng lúc này.");
            return false;
        }
        recentlyLeftUsernames.remove(username.toLowerCase());

        PlayerState state = new PlayerState(client, username);
        if (previous != null && gameActive) {
            state.isMainPlayer = previous.isMainPlayer;
            state.isAlive = previous.isAlive;
            state.isReady = previous.isReady;
            state.answer = previous.answer;
            state.answerTimeMs = previous.answerTimeMs;
            state.score = previous.score;
            state.lifeTrustUsed = previous.lifeTrustUsed;
            state.lifeAskUsed = previous.lifeAskUsed;
            state.lifeSurveyUsed = previous.lifeSurveyUsed;
        } else if (gameActive) state.isAlive = false;
        if (canControl(username)) state.isReady = true;
        players.put(client, state);

        // Chỉ gán hostUsername lần đầu tiên, không thay đổi khi host reconnect
        if (hostUsername == null || hostUsername.isEmpty()) {
            hostUsername = username;
            selectedMainPlayerUsername = username;
            state.isReady = true;
        }

        sendRoomInfo(client);
        
        // Nếu đang trong game, gửi trạng thái hiện tại
        if (!"WAITING".equals(gameState)) {
            sendCurrentGameState(client);
        }
        
        broadcastPlayers();
        return true;
    }

    public synchronized void handleLeave(WebSocketClient client) {
        removePlayer(client, false);
    }

    public synchronized void handleDisconnect(WebSocketClient client) {
        removePlayer(client, true);
    }

    private void removePlayer(WebSocketClient client, boolean disconnected) {
        // Kết nối cũ đã được thay khi reconnect không được chuyển chủ phòng hoặc xóa lựa chọn mới.
        if (!players.containsKey(client)) return;
        String leavingUsername = client.getUsername();
        boolean wasHost = (leavingUsername != null && leavingUsername.equalsIgnoreCase(hostUsername));

        // Ghi nhận username đã từng trong phòng để cho phép rejoin sau
        if (leavingUsername != null) {
            recentlyLeftUsernames.add(leavingUsername.toLowerCase());
        }

        players.remove(client);
        if (disconnected) broadcast(Map.of("type", "player_disconnected", "username", leavingUsername,
                "roomCode", roomCode, "message", "Người chơi [" + leavingUsername + "] đã mất kết nối."));
        if (leavingUsername != null && leavingUsername.equalsIgnoreCase(selectedMainPlayerUsername)) selectedMainPlayerUsername = null;

        if (players.isEmpty()) {
            // Chỉ reset khi không đang chơi — nếu đang chơi giữ nguyên để phòng tồn tại cho rejoin
            if ("WAITING".equals(gameState) || "END".equals(gameState)) {
                gameState = "WAITING";
                currentQuestionIndex = 0;
                if (currentTimer != null) { currentTimer.cancel(false); currentTimer = null; }
            }
        } else if (wasHost) {
            WebSocketClient nextHost = players.keySet().stream().filter(c -> !c.isClosed()).findFirst().orElse(null);
            if (nextHost != null) {
                PlayerState ps = players.get(nextHost);
                if (ps != null) {
                    ps.isReady = true;
                    hostUsername = ps.username;
                    broadcastRoomInfoToAll();
                }
            }
        }
        broadcastPlayers();
        broadcastRoomInfoToAll();
    }

    public synchronized void handleToggleReady(WebSocketClient client) {
        PlayerState state = players.get(client);
        if (state == null) return;
        if (!isHost(state.username)) state.isReady = !state.isReady;
        else state.isReady = true;
        broadcastPlayers();
    }

    // ─── 7. BẮT ĐẦU TRẬN ĐẤU ────────────────────────────────────────────────

    public synchronized void handleStartGame(WebSocketClient client) {
        String username = client.getUsername();
        if (username == null || closed) return;

        if (!canControl(username)) {
            sendError(client, "Chỉ Chủ phòng mới có quyền bắt đầu trận đấu!");
            return;
        }

        if (!isSuperAdmin(username)) {
            for (PlayerState p : players.values()) {
                if (!isHost(p.username) && !p.isReady) {
                    sendError(client, "Chưa thể bắt đầu! Vẫn còn người chơi chưa Sẵn sàng.");
                    return;
                }
            }
        }

        if ("WAITING".equals(gameState) || "END".equals(gameState)) {
            if (players.size() - 1 + botCount < 1) { sendError(client, "Cần ít nhất một đối thủ thật hoặc bot để bắt đầu."); return; }
            if ("MANUAL".equals(mainSelectionMode) && players.values().stream().noneMatch(p -> p.username.equalsIgnoreCase(selectedMainPlayerUsername))) {
                sendError(client, "Hãy chọn người chơi chính đang có mặt trong phòng trước khi bắt đầu."); return;
            }
            startGame();
        }
    }

    private synchronized void startGame() {
        List<Question> selected;
        try{selected=QuestionBank.getArenaQuestions(topic,difficulty,questionCount);}catch(IllegalArgumentException e){broadcast(Map.of("type","arena_error","message",e.getMessage()));return;}
        if(selected.isEmpty()){broadcast(Map.of("type","arena_error","message","Chưa có câu hỏi đã xuất bản phù hợp."));return;}
        gameState = "STARTING";
        currentQuestionIndex = 0;
        totalEliminatedOpponents = 0;
        isPaused = false; // YÊU CẦU 5: Reset trạng thái tạm dừng

        questions.clear();
        questions.addAll(selected);

        // YÊU CẦU 4: Reset trạng thái người chơi VÀ quyền trợ giúp
        for (PlayerState p : players.values()) {
            p.isAlive = true;
            p.answer = null;
            p.answerTimeMs = Long.MAX_VALUE;
            p.score = 0;
            p.isMainPlayer = false;
            // Reset quyền trợ giúp cho trận mới
            p.lifeTrustUsed = false;
            p.lifeAskUsed = false;
            p.lifeSurveyUsed = false;
        }

        List<WebSocketClient> clientList = new ArrayList<>(players.keySet());
        if (clientList.isEmpty()) return;

        WebSocketClient mainClient = null;
        for (WebSocketClient c : clientList) {
            if ("MANUAL".equals(mainSelectionMode) && c.getUsername().equalsIgnoreCase(selectedMainPlayerUsername)) { mainClient = c; break; }
        }
        if (mainClient == null) {
            mainClient = clientList.get(new Random().nextInt(clientList.size()));
        }
        mainPlayerUsername = mainClient.getUsername();
        players.get(mainClient).isMainPlayer = true;

        bots.clear();
        for (int i = 0; i < botCount; i++) {
            bots.add(new BotOpponent("Bot_" + BOT_COUNTER.getAndIncrement()));
        }

        matchId = MatchService.start("ARENA", roomCode, !bots.isEmpty(),
                Map.of("topic",topic,"difficulty",difficulty,"questionCount",questionCount,"botCount",botCount,"questions",questions.stream().map(Question::snapshot).toList()),
                players.values().stream().map(p -> p.username).toList());
        int realOpponents = (int) players.values().stream().filter(p -> !p.isMainPlayer).count();
        totalInitialOpponents = realOpponents + bots.size();

        Map<String, Object> startMsg = new HashMap<>();
        startMsg.put("type", "arena_game_started");
        startMsg.put("roomCode", roomCode);
        startMsg.put("roomName", roomName);
        startMsg.put("mainPlayer", mainPlayerUsername);
        startMsg.put("questionCount", questions.size());
        startMsg.put("totalOpponents", totalInitialOpponents);
        startMsg.put("botCount", bots.size());
        startMsg.put("difficulty", difficulty);
        broadcast(startMsg);
        broadcastPlayers();

        scheduleNext(this::sendQuestion, 1000);
    }

    // ─── 8. GỬI CÂU HỎI ─────────────────────────────────────────────────────

    private synchronized void sendQuestion() {
        if (closed) return;
        // Kiểm tra kết thúc trận
        long aliveOpponents = countAliveOpponents();
        if (aliveOpponents == 0) {
            endGame(true, null);
            return;
        }
        if (currentQuestionIndex >= questions.size()) {
            endGame(false, "Hết câu hỏi! Trận đấu kết thúc.");
            return;
        }

        gameState = "QUESTION";
        answerPhase = "OPPONENTS";
        isPaused = false;
        phaseStartNanos = System.nanoTime();
        phaseDeadlineNanos = phaseStartNanos + TimeUnit.SECONDS.toNanos(timeLimit);
        Question q = questions.get(currentQuestionIndex);

        // Reset đáp án
        for (PlayerState p : players.values()) {
            p.answer = null;
            p.answerTimeMs = Long.MAX_VALUE;
        }

        // Bot tự sinh đáp án
        for (BotOpponent bot : bots) {
            if (bot.isAlive) {
                bot.generateAnswer(q.getDifficulty(), q.getCorrect());
            }
        }

        // Cùng xem câu hỏi, chỉ người chơi phụ được chốt trong lượt đầu.
        for (Map.Entry<WebSocketClient, PlayerState> entry : players.entrySet()) {
            WebSocketClient client = entry.getKey();
            PlayerState ps = entry.getValue();
            
            Map<String, Object> msg = new HashMap<>();
            msg.put("type", "arena_new_question");
            msg.put("questionId", currentQuestionIndex + 1);
            msg.put("totalQuestions", questions.size());
            msg.put("text", q.getText());
            msg.put("options", q.getOptions());
            msg.put("topic", q.getTopic());
            msg.put("difficulty", q.getDifficulty());
            msg.put("aliveOpponents", aliveOpponents);
            msg.put("totalEliminated", totalEliminatedOpponents);
            msg.put("totalInitialOpponents", totalInitialOpponents);
            
            msg.put("timeLimit", timeLimit);
            msg.put("phase", answerPhase);
            msg.put("isMainPlayer", ps.isMainPlayer);
            msg.put("canAnswer", !ps.isMainPlayer && ps.isAlive);
            msg.put("isAlive", ps.isAlive);
            msg.put("mainPlayerTimeLimit", mainPlayerTimeLimit);
            msg.put("mainPlayer", mainPlayerUsername);
            
            client.send(JsonUtil.toJson(msg));
        }

        scheduleNext(this::beginMainPlayerTurn, timeLimit * 1000L);
    }

    private synchronized void beginMainPlayerTurn() {
        if (closed || isPaused || !"QUESTION".equals(gameState) || !"OPPONENTS".equals(answerPhase)) return;
        answerPhase = "MAIN";
        phaseStartNanos = System.nanoTime();
        phaseDeadlineNanos = phaseStartNanos + TimeUnit.SECONDS.toNanos(mainPlayerTimeLimit);
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "arena_main_player_turn");
        msg.put("phase", answerPhase);
        msg.put("timeLimit", mainPlayerTimeLimit);
        msg.put("message", "Đã khóa đáp án người chơi phụ. Người chơi chính bắt đầu trả lời và được sử dụng quyền trợ giúp.");
        broadcast(msg);
        scheduleNext(this::processResults, mainPlayerTimeLimit * 1000L);
    }

    // ─── 9. GỬI ĐÁP ÁN ──────────────────────────────────────────────────────

    public synchronized void handleSubmitAnswer(WebSocketClient client, int answerIndex) {
        PlayerState state = players.get(client);
        if (closed || state == null || !state.isAlive || !"QUESTION".equals(gameState)) return;
        if (isPaused || System.nanoTime() >= phaseDeadlineNanos
                || (state.isMainPlayer ? !"MAIN".equals(answerPhase) : !"OPPONENTS".equals(answerPhase))) {
            sendError(client, "Chưa đến lượt, đã hết thời gian hoặc trận đang tạm dừng."); return;
        }
        if (answerIndex < 0 || answerIndex >= questions.get(currentQuestionIndex).getOptions().size()) {
            sendError(client, "Đáp án không hợp lệ."); return;
        }
        if (state.answer == null) {
            state.answer = answerIndex;
            state.answerTimeMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - phaseStartNanos));
            client.send(JsonUtil.toJson(Map.of("type", "arena_answer_locked", "answer", answerIndex)));
            // Giữ nguyên thời hạn lượt chính; chốt sớm không làm kết thúc lượt sớm.
        }
    }

    // ─── 10. XỬ LÝ KẾT QUẢ VÒNG ─────────────────────────────────────────────

    private synchronized void processResults() {
        if (closed || isPaused || !"QUESTION".equals(gameState) || !"MAIN".equals(answerPhase)) return;
        gameState = "RESULT";
        answerPhase = "NONE";
        Question q = questions.get(currentQuestionIndex);
        int correctIndex = q.getCorrect();

        PlayerState mainState = findMainPlayerState();
        boolean mainCorrect = (mainState != null && mainState.answer != null && mainState.answer == correctIndex);

        List<String> eliminatedBotNames = new ArrayList<>();
        int botAliveCount = 0;
        int botCorrectCount = 0;

        for (BotOpponent bot : bots) {
            if (!bot.isAlive) continue;
            botAliveCount++;
            if (bot.answer == correctIndex) {
                botCorrectCount++;
            } else if (mainCorrect) {
                bot.isAlive = false;
                eliminatedBotNames.add(bot.name);
                totalEliminatedOpponents++;
            }
        }

        List<String> eliminatedRealOpponents = new ArrayList<>();
        int realOpponentAlive = 0;
        int realOpponentCorrect = 0;

        for (PlayerState p : players.values()) {
            if (p.isMainPlayer || !p.isAlive) continue;
            realOpponentAlive++;
            boolean correct = (p.answer != null && p.answer == correctIndex);
            if (correct) {
                realOpponentCorrect++;
            } else if (mainCorrect) {
                p.isAlive = false;
                eliminatedRealOpponents.add(p.username);
                totalEliminatedOpponents++;
            }
        }

        // YÊU CẦU 6: Tính điểm theo cơ chế mới
        if (mainState != null) {
            mainState.score = Leaderboard.calcMilestonePoints(totalEliminatedOpponents, totalInitialOpponents);
        }

        long aliveOpponentsNow = countAliveOpponents();

        // YÊU CẦU 6: Gửi thông tin các mốc điểm
        List<Map<String, Object>> milestones = new ArrayList<>();
        for (Leaderboard.MilestoneInfo m : Leaderboard.getMilestones(totalEliminatedOpponents, totalInitialOpponents)) {
            milestones.add(m.toMap());
        }

        Map<String, Object> resultMsg = new HashMap<>();
        resultMsg.put("type", "arena_round_result");
        resultMsg.put("correctAnswerIndex", correctIndex);
        resultMsg.put("explanation",q.getExplanation());
        resultMsg.put("mainPlayerCorrect", mainCorrect);
        resultMsg.put("mainPlayer", mainPlayerUsername);
        resultMsg.put("eliminatedRealOpponents", eliminatedRealOpponents);
        resultMsg.put("eliminatedBotCount", eliminatedBotNames.size());
        resultMsg.put("aliveOpponents", aliveOpponentsNow);
        resultMsg.put("totalEliminated", totalEliminatedOpponents);
        resultMsg.put("totalInitialOpponents", totalInitialOpponents);
        resultMsg.put("currentScore", mainState != null ? mainState.score : 0);
        resultMsg.put("milestones", milestones); // YÊU CẦU 6
        resultMsg.put("roundSurvivorsInfo", buildSurvivorsInfo(botAliveCount - eliminatedBotNames.size(), realOpponentAlive - eliminatedRealOpponents.size(), botCorrectCount, realOpponentCorrect));
        broadcast(resultMsg);

        currentQuestionIndex++;

        scheduleNext(() -> {
            synchronized (ArenaRoom.this) {
                if (!mainCorrect) {
                    endGame(false, mainPlayerUsername + " trả lời sai — Đối thủ chiến thắng!");
                } else if (aliveOpponentsNow == 0) {
                    endGame(true, null);
                } else if (currentQuestionIndex >= questions.size()) {
                    endGame(false, "Hết câu hỏi! Còn " + aliveOpponentsNow + " đối thủ tồn tại.");
                } else {
                    sendQuestion();
                }
            }
        }, 5000);
    }

    private Map<String, Object> buildSurvivorsInfo(int botAlive, int realOpponentAlive, int botCorrect, int realOpponentCorrect) {
        Map<String, Object> info = new HashMap<>();
        info.put("botsAlive", botAlive);
        info.put("realOpponentsAlive", realOpponentAlive);
        info.put("botsCorrectCount", botCorrect);
        info.put("realOpponentsCorrectCount", realOpponentCorrect);
        return info;
    }

    // ─── 11. KẾT THÚC TRẬN ───────────────────────────────────────────────────

    private synchronized void endGame(boolean mainWon, String overrideReason) {
        if (closed || "END".equals(gameState) || "WAITING".equals(gameState)) return;
        gameState = "END";
        if (currentTimer != null) { currentTimer.cancel(false); currentTimer = null; }

        // Tính điểm mốc cuối cùng
        PlayerState mainState = findMainPlayerState();
        int finalScore = mainState != null ? Leaderboard.calcMilestonePoints(totalEliminatedOpponents, totalInitialOpponents) : 0;
        if (mainState != null) mainState.score = finalScore;

        String reason;
        if (overrideReason != null) {
            reason = overrideReason;
        } else if (mainWon) {
            reason = "🏆 " + mainPlayerUsername + " đã loại tất cả " + totalInitialOpponents + " đối thủ và chinh phục Đấu Trường 100!";
        } else {
            reason = "Trận đấu kết thúc.";
        }

        // Entertainment results never contribute to competitive rank.
        List<MatchService.Result> results = new ArrayList<>();
        for (PlayerState p : players.values()) {
            boolean won = p.isMainPlayer ? mainWon : !mainWon && p.isAlive;
            results.add(new MatchService.Result(p.username, p.score, 0, won ? "WIN" : "LOSS"));
        }
        MatchService.finish(matchId, false, reason, results);

        Map<String, Object> endMsg = new HashMap<>();
        endMsg.put("type", "arena_end_game");
        endMsg.put("reason", reason);
        endMsg.put("mainWon", mainWon);
        endMsg.put("mainPlayer", mainPlayerUsername);
        endMsg.put("totalEliminated", totalEliminatedOpponents);
        endMsg.put("totalInitialOpponents", totalInitialOpponents);
        endMsg.put("finalScore", finalScore);
        endMsg.put("leaderboard", Leaderboard.getTop(10));
        broadcast(endMsg);

        // Reset về WAITING để có thể chơi lại
        scheduleNext(() -> {
            synchronized (ArenaRoom.this) {
                gameState = "WAITING";
                mainPlayerUsername = null;
                bots.clear();
                for (PlayerState player : players.values()) player.isMainPlayer = false;
                broadcastPlayers();
            }
        }, 1000);
    }

    // ─── 12. BA QUYỀN TRỢ GIÚP ───────────────────────────────────────────────

    private PlayerState requireLifeline(WebSocketClient client) {
        PlayerState state = players.get(client);
        if (closed || state == null || !state.isMainPlayer || !state.isAlive || !"QUESTION".equals(gameState)
                || !"MAIN".equals(answerPhase) || isPaused || state.answer != null || System.nanoTime() >= phaseDeadlineNanos) {
            sendError(client, "Chỉ người chơi chính được dùng trợ giúp trong lượt của mình, trước khi chốt đáp án và khi trận không tạm dừng.");
            return null;
        }
        return state;
    }

    /**
     * TRUST (Tin người chơi): Tiết lộ đáp án của 1 đối thủ thật đã khóa đáp án.
     */
    public synchronized void handleLifeTrust(WebSocketClient client) {
        PlayerState mainState = requireLifeline(client);
        if (mainState == null) return;
        if (mainState.lifeTrustUsed) { sendError(client, "Bạn đã dùng quyền Tin Người Chơi rồi!"); return; }

        // Tìm 1 đối thủ thật đã trả lời
        for (PlayerState p : players.values()) {
            if (!p.isMainPlayer && p.isAlive && p.answer != null) {
                mainState.lifeTrustUsed = true;
                Map<String, Object> msg = new HashMap<>();
                msg.put("type", "arena_life_trust");
                msg.put("opponentUsername", p.username);
                msg.put("opponentAnswer", p.answer);
                client.send(JsonUtil.toJson(msg));
                return;
            }
        }
        // Không tìm được đối thủ thật đã trả lời → lấy từ bot
        for (BotOpponent bot : bots) {
            if (bot.isAlive && bot.answer >= 0) {
                mainState.lifeTrustUsed = true;
                Map<String, Object> msg = new HashMap<>();
                msg.put("type", "arena_life_trust");
                msg.put("opponentUsername", bot.name);
                msg.put("opponentAnswer", bot.answer);
                client.send(JsonUtil.toJson(msg));
                return;
            }
        }
        sendError(client, "Chưa có đối thủ nào đã trả lời để tham khảo.");
    }

    /**
     * ASK (Hỏi người chơi): Người chơi chính chọn 1 đối thủ cụ thể để tham khảo.
     */
    public synchronized void handleLifeAsk(WebSocketClient client, String targetUsername) {
        PlayerState mainState = requireLifeline(client);
        if (mainState == null) return;
        if (mainState.lifeAskUsed) { sendError(client, "Bạn đã dùng quyền Hỏi Người Chơi rồi!"); return; }

        for (PlayerState p : players.values()) {
            if (!p.isMainPlayer && p.username.equalsIgnoreCase(targetUsername) && p.isAlive && p.answer != null) {
                mainState.lifeAskUsed = true;
                Map<String, Object> msg = new HashMap<>();
                msg.put("type", "arena_life_ask");
                msg.put("opponentUsername", p.username);
                msg.put("opponentAnswer", p.answer);
                client.send(JsonUtil.toJson(msg));
                return;
            }
        }
        sendError(client, "Đối thủ chưa chọn đáp án hoặc không tồn tại.");
    }

    /**
     * SURVEY (Khảo sát người chơi): Hiển thị % đáp án của toàn bộ đối thủ (thật + bot).
     */
    public synchronized void handleLifeSurvey(WebSocketClient client) {
        PlayerState mainState = requireLifeline(client);
        if (mainState == null) return;
        if (mainState.lifeSurveyUsed) { sendError(client, "Bạn đã dùng quyền Khảo Sát rồi!"); return; }

        int[] counts = new int[4];
        int total = 0;

        for (PlayerState p : players.values()) {
            if (!p.isMainPlayer && p.isAlive && p.answer != null && p.answer >= 0 && p.answer < 4) {
                counts[p.answer]++;
                total++;
            }
        }
        for (BotOpponent bot : bots) {
            if (bot.isAlive && bot.answer >= 0 && bot.answer < 4) {
                counts[bot.answer]++;
                total++;
            }
        }

        if (total == 0) { sendError(client, "Không có đáp án đối thủ để khảo sát."); return; }
        mainState.lifeSurveyUsed = true;
        double[] pcts = new double[4];
        if (total > 0) {
            for (int i = 0; i < 4; i++) pcts[i] = Math.round(counts[i] * 100.0 / total);
        }

        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "arena_life_survey");
        msg.put("distribution", new double[]{pcts[0], pcts[1], pcts[2], pcts[3]});
        msg.put("totalRespondents", total);
        client.send(JsonUtil.toJson(msg));
    }

    // ─── 13. THÔNG TIN PHÒNG & BROADCAST ────────────────────────────────────

    private void sendCurrentGameState(WebSocketClient client) {
        if (!"QUESTION".equals(gameState) || currentQuestionIndex >= questions.size()) return;
        Question q = questions.get(currentQuestionIndex);
        PlayerState state = players.get(client);
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "arena_new_question");
        msg.put("mainPlayer", mainPlayerUsername);
        msg.put("questionId", currentQuestionIndex + 1);
        msg.put("totalQuestions", questions.size());
        msg.put("text", q.getText());
        msg.put("options", q.getOptions());
        msg.put("topic", q.getTopic());
        msg.put("difficulty", q.getDifficulty());
        msg.put("aliveOpponents", countAliveOpponents());
        msg.put("totalEliminated", totalEliminatedOpponents);
        msg.put("totalInitialOpponents", totalInitialOpponents);
        msg.put("timeLimit", remainingPhaseMs() / 1000.0);
        msg.put("phase", answerPhase);
        msg.put("isPaused", isPaused);
        msg.put("isMainPlayer", state != null && state.isMainPlayer);
        msg.put("isAlive", state != null && state.isAlive);
        msg.put("hasAnswered", state != null && state.answer != null);
        msg.put("canAnswer", state != null && state.isAlive && state.answer == null && !isPaused
                && (state.isMainPlayer ? "MAIN".equals(answerPhase) : "OPPONENTS".equals(answerPhase)));
        if (state != null) {
            msg.put("lifeTrustUsed", state.lifeTrustUsed);
            msg.put("lifeAskUsed", state.lifeAskUsed);
            msg.put("lifeSurveyUsed", state.lifeSurveyUsed);
        }
        client.send(JsonUtil.toJson(msg));
    }

    public void sendRoomInfo(WebSocketClient client) {
        Map<String, Object> info = new HashMap<>();
        info.put("type", "room_info");
        info.put("roomCode", roomCode);
        info.put("roomName", roomName);
        info.put("hostUsername", hostUsername);
        info.put("topic", topic);
        info.put("topicName", getTopicDisplayName(topic));
        info.put("difficulty", difficulty);
        info.put("difficultyName", getDifficultyDisplayName(difficulty));
        info.put("questionCount", questionCount);
        info.put("timeLimit", timeLimit);
        info.put("mainPlayerTimeLimit", mainPlayerTimeLimit);
        info.put("mainPlayer", mainPlayerUsername);
        info.put("mainSelectionMode", mainSelectionMode);
        info.put("selectedMainPlayer", selectedMainPlayerUsername);
        info.put("gameState", gameState);
        info.put("botCount", botCount);
        info.put("isHost", isHost(client.getUsername()));
        info.put("isSuperAdmin", isSuperAdmin(client.getUsername()));
        client.send(JsonUtil.toJson(info));
    }

    public void broadcastRoomInfoToAll() {
        for (WebSocketClient c : players.keySet()) {
            if (!c.isClosed()) {
                sendRoomInfo(c);
            }
        }
    }

    public void broadcastPlayers() {
        boolean allReady = true;
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<WebSocketClient, PlayerState> entry : players.entrySet()) {
            PlayerState p = entry.getValue();
            boolean isUserHost = isHost(p.username);
            boolean isUserAdmin = isSuperAdmin(p.username);
            boolean ready = isUserHost || isUserAdmin || p.isReady;
            if (!isUserHost && !isUserAdmin && !ready) allReady = false;

            Map<String, Object> map = new HashMap<>();
            map.put("username", p.username);
            map.put("isAlive", p.isAlive);
            map.put("isReady", ready);
            map.put("isHost", isUserHost);
            map.put("isSuperAdmin", isUserAdmin);
            map.put("isMainPlayer", p.isMainPlayer);
            map.put("score", p.score);
            map.put("isBot", false);
            list.add(map);
        }

        // Thêm bot vào danh sách để hiển thị trên khán đài (phòng chờ)
        for (int i = 1; i <= botCount; i++) {
            Map<String, Object> botMap = new HashMap<>();
            botMap.put("username", "Bot_" + i);
            botMap.put("isAlive", true);
            botMap.put("isReady", true);
            botMap.put("isHost", false);
            botMap.put("isSuperAdmin", false);
            botMap.put("isMainPlayer", false);
            botMap.put("score", 0);
            botMap.put("isBot", true);
            list.add(botMap);
        }

        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "update_arena_players");
        msg.put("players", list);
        msg.put("hostUsername", hostUsername);
        msg.put("roomCode", roomCode);
        msg.put("roomName", roomName);
        msg.put("botCount", botCount);
        msg.put("allReady", allReady);
        msg.put("mainPlayer", mainPlayerUsername);
        msg.put("gameState", gameState);
        msg.put("mainSelectionMode", mainSelectionMode);
        msg.put("selectedMainPlayer", selectedMainPlayerUsername);
        broadcast(msg);
    }

    public Map<String, Object> toSummaryMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("roomCode", roomCode);
        map.put("roomName", roomName);
        map.put("hostUsername", hostUsername != null ? hostUsername : "Chưa có");
        map.put("topic", topic);
        map.put("topicName", getTopicDisplayName(topic));
        map.put("difficulty", difficulty);
        map.put("difficultyName", getDifficultyDisplayName(difficulty));
        map.put("questionCount", questionCount);
        map.put("timeLimit", timeLimit);
        map.put("mainPlayerTimeLimit", mainPlayerTimeLimit);
        map.put("mainSelectionMode", mainSelectionMode);
        map.put("selectedMainPlayer", selectedMainPlayerUsername);
        map.put("botCount", botCount);
        map.put("playerCount", players.size());
        map.put("gameState", gameState);
        return map;
    }

    public void broadcast(Map<String, Object> map) {
        String json = JsonUtil.toJson(map);
        for (WebSocketClient client : players.keySet()) {
            if (!client.isClosed()) client.send(json);
        }
    }

    // ─── 14. TIỆN ÍCH ────────────────────────────────────────────────────────

    // YÊU CẦU 5: Tạm dừng và tiếp tục trận đấu
    private long remainingPhaseMs() {
        return isPaused ? remainingTimeBeforePause : Math.max(0, (phaseDeadlineNanos - System.nanoTime() + 999999) / 1000000);
    }

    public synchronized void handlePauseGame(WebSocketClient client) {
        if (closed || !isSuperAdmin(client.getUsername())) { sendError(client, "Chỉ quản trị viên được tạm dừng."); return; }
        if (!"QUESTION".equals(gameState) || isPaused || remainingPhaseMs() <= 0) { sendError(client, "Không thể tạm dừng ở thời điểm này."); return; }
        remainingTimeBeforePause = remainingPhaseMs();
        isPaused = true;
        pauseStartTime = System.nanoTime();
        ++timerGeneration;
        if (currentTimer != null) currentTimer.cancel(false);
        broadcast(Map.of("type", "arena_game_paused", "phase", answerPhase,
                "remainingTime", remainingTimeBeforePause / 1000.0, "message", "Trận đấu đã tạm dừng."));
    }

    public synchronized void handleResumeGame(WebSocketClient client) {
        if (closed || !isSuperAdmin(client.getUsername())) { sendError(client, "Chỉ quản trị viên được tiếp tục."); return; }
        if (!isPaused || !"QUESTION".equals(gameState)) { sendError(client, "Trận đấu chưa được tạm dừng."); return; }
        isPaused = false;
        long now = System.nanoTime();
        phaseStartNanos += now - pauseStartTime;
        phaseDeadlineNanos = now + TimeUnit.MILLISECONDS.toNanos(remainingTimeBeforePause);
        broadcast(Map.of("type", "arena_game_resumed", "phase", answerPhase,
                "remainingTime", remainingTimeBeforePause / 1000.0, "message", "Trận đấu tiếp tục."));
        scheduleNext("OPPONENTS".equals(answerPhase) ? this::beginMainPlayerTurn : this::processResults, remainingTimeBeforePause);
    }

    private PlayerState findMainPlayerState() {
        for (PlayerState p : players.values()) {
            if (p.isMainPlayer) return p;
        }
        return null;
    }

    private long countAliveOpponents() {
        long realAlive = players.values().stream().filter(p -> !p.isMainPlayer && p.isAlive).count();
        long botAlive = bots.stream().filter(b -> b.isAlive).count();
        return realAlive + botAlive;
    }

    private void sendError(WebSocketClient client, String message) {
        Map<String, Object> err = new HashMap<>();
        err.put("type", "arena_error");
        err.put("message", message);
        client.send(JsonUtil.toJson(err));
    }

    public static String getTopicDisplayName(String code) {
        if ("NETWORKING".equalsIgnoreCase(code)) return "Lập Trình & Mạng Máy Tính";
        if ("SCIENCE".equalsIgnoreCase(code))    return "Khoa Học & Đời Sống";
        if ("HISTORY".equalsIgnoreCase(code))    return "Lịch Sử & Xã Hội";
        return "Tổng Hợp Gameshow";
    }

    public static String getDifficultyDisplayName(String code) {
        if (Set.of("MIX", "ALL", "RANDOM").contains(code)) return "Tăng dần: Dễ → Trung bình → Khó";
        if ("EASY".equalsIgnoreCase(code))  return "Dễ (Bot đúng ~75%)";
        if ("HARD".equalsIgnoreCase(code))  return "Khó (Bot đúng ~35%)";
        return "Trung Bình (Bot đúng ~55%)";
    }
}
