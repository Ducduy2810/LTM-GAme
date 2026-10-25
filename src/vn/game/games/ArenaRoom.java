package vn.game.games;

import vn.game.db.UserRepository;
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
 *   - Điểm tăng tại mỗi mốc 5 đối thủ bị loại.
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
    private Map<String, Object> lastRoundResult;
    private ScheduledFuture<?> currentTimer = null;
    private volatile long questionStartTime = 0;
    private volatile boolean mainTurn = false;
    private final Map<String, PlayerState> disconnectedPlayers = new HashMap<>();
    private volatile int maxPlayers = 101;

    public void setMaxPlayers(int count) { maxPlayers = Math.max(1, Math.min(101, count)); }
    public synchronized boolean canJoin(String username) {
        return players.values().stream().anyMatch(p -> p.username.equalsIgnoreCase(username))
            || disconnectedPlayers.containsKey(username.toLowerCase())
            || (("WAITING".equals(gameState) || "END".equals(gameState)) && players.size() < maxPlayers);
    }
    
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
        this.questionCount = Math.max(1, Math.min(50, questionCount));
        this.timeLimit = 5;
        this.mainPlayerTimeLimit = 60;
        this.botCount = Math.max(0, Math.min(100, botCount));
        this.userRepository = userRepository;
        this.scheduler = scheduler;
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
        if ("admin".equalsIgnoreCase(username) || "DuyNguyen".equalsIgnoreCase(username)) return true;
        return userRepository != null && userRepository.isAdmin(username);
    }

    public boolean isHost(String username) {
        return hostUsername != null && hostUsername.equalsIgnoreCase(username);
    }

    public boolean canControl(String username) {
        return isHost(username) || isSuperAdmin(username);
    }

    // ─── 6. THAM GIA / RỜI PHÒNG / SẴN SÀNG ─────────────────────────────────

    public synchronized void handleJoin(WebSocketClient client) {
        String username = client.getUsername();
        if (username == null) return;
        PlayerState previous = disconnectedPlayers.remove(username.toLowerCase());
        for (PlayerState p : players.values()) {
            if (p.username.equalsIgnoreCase(username)) previous = p;
        }

        // Xóa kết nối cũ cùng username nếu còn (reconnect/rejoin)
        players.keySet().removeIf(old -> {
            if (username.equalsIgnoreCase(old.getUsername())) {
                if (!old.equals(client)) old.close();
                return true;
            }
            return false;
        });
        players.keySet().removeIf(WebSocketClient::isClosed);

        // Chặn người mới hoàn toàn (chưa từng trong phòng) khi đang chơi.
        // Người đã rời phòng vẫn được vào lại xem / reconnect.
        boolean gameActive = !"WAITING".equals(gameState) && !"END".equals(gameState);
        if (gameActive && previous == null && !recentlyLeftUsernames.contains(username.toLowerCase())) {
            sendError(client, "Trận đấu đang diễn ra! Không thể vào phòng lúc này.");
            return;
        }
        recentlyLeftUsernames.remove(username.toLowerCase());

        PlayerState state = new PlayerState(client, username);
        if (previous != null) {
            state.isReady = previous.isReady;
            state.isAlive = previous.isAlive;
            state.isMainPlayer = previous.isMainPlayer;
            state.answer = previous.answer;
            state.answerTimeMs = previous.answerTimeMs;
            state.score = previous.score;
            state.lifeTrustUsed = previous.lifeTrustUsed;
            state.lifeAskUsed = previous.lifeAskUsed;
            state.lifeSurveyUsed = previous.lifeSurveyUsed;
        }
        if (canControl(username)) state.isReady = true;
        players.put(client, state);

        // Chỉ gán hostUsername lần đầu tiên, không thay đổi khi host reconnect
        if (hostUsername == null || hostUsername.isEmpty()) {
            hostUsername = username;
        }

        sendRoomInfo(client);
        
        // Nếu đang trong game, gửi trạng thái hiện tại
        if (!"WAITING".equals(gameState)) {
            sendCurrentGameState(client);
        }
        
        broadcastPlayers();
    }

    public synchronized void handleLeave(WebSocketClient client) {
        handleLeave(client, false);
    }

    public synchronized void handleDisconnect(WebSocketClient client) {
        handleLeave(client, true);
    }

    private void handleLeave(WebSocketClient client, boolean disconnected) {
        String leavingUsername = client.getUsername();
        boolean wasHost = (leavingUsername != null && leavingUsername.equalsIgnoreCase(hostUsername));
        PlayerState current = players.get(client);
        boolean active = "QUESTION".equals(gameState) || "RESULT".equals(gameState);
        if (!disconnected && active && current != null && current.isMainPlayer) {
            endGame(false, current.username + " đã rời trận đấu.");
            active = false;
        }
        if (!disconnected && active && current != null && current.isAlive) {
            current.isAlive = false;
            totalEliminatedOpponents++;
        }

        // Ghi nhận username đã từng trong phòng để cho phép rejoin sau
        if (leavingUsername != null) {
            recentlyLeftUsernames.add(leavingUsername.toLowerCase());
        }

        PlayerState leaving = players.remove(client);
        if (leaving != null && active) {
            disconnectedPlayers.put(leaving.username.toLowerCase(), leaving);
            if (disconnected && leaving.isAlive && !isPaused && "QUESTION".equals(gameState)) pauseGame("Mất kết nối: " + leaving.username);
        }
        players.keySet().removeIf(WebSocketClient::isClosed);

        if (players.isEmpty()) {
            // Chỉ reset khi không đang chơi — nếu đang chơi giữ nguyên để phòng tồn tại cho rejoin
            if ("WAITING".equals(gameState) || "END".equals(gameState)) {
                gameState = "WAITING";
                currentQuestionIndex = 0;
                if (currentTimer != null) { currentTimer.cancel(false); currentTimer = null; }
            }
        } else if (wasHost && !(disconnected && active)) {
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
        if (username == null) return;

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
            if (players.size() < 2 && botCount == 0) {
                sendError(client, "Cần ít nhất một đối thủ thật hoặc bot để bắt đầu.");
                return;
            }
            startGame();
        }
    }

    private synchronized void startGame() {
        gameState = "WAITING";
        currentQuestionIndex = 0;
        totalEliminatedOpponents = 0;
        disconnectedPlayers.clear();
        isPaused = false; // YÊU CẦU 5: Reset trạng thái tạm dừng

        questions.clear();
        questions.addAll(QuestionBank.getQuestions(topic, difficulty, questionCount));

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
            if (isHost(c.getUsername())) { mainClient = c; break; }
        }
        if (mainClient == null) {
            mainClient = clientList.get(new Random().nextInt(clientList.size()));
        }
        mainPlayerUsername = mainClient.getUsername();
        players.get(mainClient).isMainPlayer = true;

        bots.clear();
        for (int i = 0; i < Math.min(botCount, 101 - players.size()); i++) {
            bots.add(new BotOpponent("Bot_" + (i + 1)));
        }

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

        if (currentTimer != null) currentTimer.cancel(false);
        currentTimer = scheduler.schedule(this::sendQuestion, 1, TimeUnit.SECONDS);
    }

    // ─── 8. GỬI CÂU HỎI ─────────────────────────────────────────────────────

    private synchronized void sendQuestion() {
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
        mainTurn = false;
        questionStartTime = System.currentTimeMillis();
        Question q = questions.get(currentQuestionIndex);

        // Reset đáp án
        for (PlayerState p : java.util.stream.Stream.concat(players.values().stream(), disconnectedPlayers.values().stream()).toList()) {
            p.answer = null;
            p.answerTimeMs = Long.MAX_VALUE;
        }

        // Bot tự sinh đáp án
        for (BotOpponent bot : bots) {
            if (bot.isAlive) {
                bot.generateAnswer(q.getDifficulty(), q.getCorrect());
            }
        }

        // Gửi câu hỏi cho cả người chơi chính và đối thủ đồng thời
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
            msg.put("phase", "OPPONENTS");
            msg.put("isMainPlayer", ps.isMainPlayer);
            msg.put("isAlive", ps.isAlive);
            msg.put("lifeTrustUsed", ps.lifeTrustUsed);
            msg.put("lifeAskUsed", ps.lifeAskUsed);
            msg.put("lifeSurveyUsed", ps.lifeSurveyUsed);

            client.send(JsonUtil.toJson(msg));
        }

        // Khóa lượt đối thủ trước khi mở 60 giây cho người chơi chính.
        if (currentTimer != null) currentTimer.cancel(false);
        currentTimer = scheduler.schedule(this::beginMainTurn, timeLimit, TimeUnit.SECONDS);
        if (disconnectedPlayers.values().stream().anyMatch(p -> p.isAlive)) pauseGame("Đang chờ người chơi kết nối lại");
    }

    // ─── 9. GỬI ĐÁP ÁN ──────────────────────────────────────────────────────

    private synchronized void beginMainTurn() {
        if (isPaused || !"QUESTION".equals(gameState) || mainTurn) return;
        mainTurn = true;
        questionStartTime = System.currentTimeMillis();
        broadcast(Map.of("type", "arena_main_player_turn", "timeLimit", mainPlayerTimeLimit,
                "message", "Người chơi phụ đã khóa đáp án. Người chơi chính có 60 giây trả lời và dùng trợ giúp."));
        currentTimer = scheduler.schedule(this::processResults, mainPlayerTimeLimit, TimeUnit.SECONDS);
    }

    public synchronized void handleSubmitAnswer(WebSocketClient client, int answerIndex) {
        PlayerState state = players.get(client);
        if (isPaused || answerIndex < 0 || answerIndex > 3 || state == null || !state.isAlive
                || !"QUESTION".equals(gameState) || state.isMainPlayer != mainTurn) return;
        if (System.currentTimeMillis() - questionStartTime >= (mainTurn ? mainPlayerTimeLimit : timeLimit) * 1000L) return;
        if (state.answer == null) {
            state.answer = answerIndex;
            state.answerTimeMs = Math.max(1, System.currentTimeMillis() - questionStartTime);
            if (mainTurn) {
                if (currentTimer != null) currentTimer.cancel(false);
                processResults();
            }
        }
    }

    // ─── 10. XỬ LÝ KẾT QUẢ VÒNG ─────────────────────────────────────────────

    private synchronized void processResults() {
        if (isPaused || !"QUESTION".equals(gameState)) return;
        gameState = "RESULT";
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

        int previouslyEliminated = totalEliminatedOpponents - eliminatedBotNames.size();
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
            if (m.eliminated > previouslyEliminated) milestones.add(m.toMap());
        }

        Map<String, Object> resultMsg = new HashMap<>();
        resultMsg.put("type", "arena_round_result");
        resultMsg.put("correctAnswerIndex", correctIndex);
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
        lastRoundResult = resultMsg;
        broadcast(resultMsg);
        broadcastPlayers();

        currentQuestionIndex++;

        if (currentTimer != null) currentTimer.cancel(false);
        currentTimer = scheduler.schedule(() -> {
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
        }, Math.max(5, 3 + milestones.size() / 4), TimeUnit.SECONDS);
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

        // Lưu vào bảng xếp hạng
        if (mainPlayerUsername != null) {
            Leaderboard.recordMatch(mainPlayerUsername, totalEliminatedOpponents, totalInitialOpponents, mainWon);
        }

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

    }

    // ─── 12. BA QUYỀN TRỢ GIÚP ───────────────────────────────────────────────

    /**
     * TRUST (Tin người chơi): Tiết lộ đáp án của 1 đối thủ thật đã khóa đáp án.
     */
    public synchronized void handleLifeTrust(WebSocketClient client) {
        PlayerState mainState = players.get(client);
        if (mainState == null || !mainState.isMainPlayer || isPaused || !mainTurn || mainState.answer != null || !"QUESTION".equals(gameState)) return;
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
        PlayerState mainState = players.get(client);
        if (mainState == null || !mainState.isMainPlayer || isPaused || !mainTurn || mainState.answer != null || !"QUESTION".equals(gameState)) return;
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
        PlayerState mainState = players.get(client);
        if (mainState == null || !mainState.isMainPlayer || isPaused || !mainTurn || mainState.answer != null || !"QUESTION".equals(gameState)) return;
        if (mainState.lifeSurveyUsed) { sendError(client, "Bạn đã dùng quyền Khảo Sát rồi!"); return; }
        mainState.lifeSurveyUsed = true;

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
        if ("QUESTION".equals(gameState) || "RESULT".equals(gameState)) {
            client.send(JsonUtil.toJson(Map.of("type", "arena_game_started", "mainPlayer", mainPlayerUsername,
                    "totalOpponents", totalInitialOpponents, "botCount", bots.size(), "difficulty", difficulty)));
        }
        if ("RESULT".equals(gameState) && lastRoundResult != null) {
            client.send(JsonUtil.toJson(lastRoundResult));
            return;
        }
        // Gửi trạng thái game hiện tại cho người vừa join
        if ("QUESTION".equals(gameState) && currentQuestionIndex >= 0 && currentQuestionIndex < questions.size()) {
            Question q = questions.get(currentQuestionIndex);
            long aliveOpponents = countAliveOpponents();
            
            PlayerState state = players.get(client);
            boolean isMainPlayer = (state != null && state.isMainPlayer);
            
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
            PlayerState main = findMainPlayerState();
            msg.put("currentScore", main != null ? main.score : 0);
            long elapsed = (isPaused ? pauseStartTime : System.currentTimeMillis()) - questionStartTime;
            msg.put("timeLimit", Math.max(0, (int) Math.ceil(((mainTurn ? mainPlayerTimeLimit : timeLimit) * 1000L - elapsed) / 1000.0)));
            msg.put("phase", mainTurn ? "MAIN" : "OPPONENTS");
            msg.put("isAlive", state != null && state.isAlive);
            msg.put("answer", state != null && state.answer != null ? state.answer : -1);
            msg.put("lifeTrustUsed", state != null && state.lifeTrustUsed);
            msg.put("lifeAskUsed", state != null && state.lifeAskUsed);
            msg.put("lifeSurveyUsed", state != null && state.lifeSurveyUsed);
            msg.put("isMainPlayer", isMainPlayer);
            msg.put("paused", isPaused);
            client.send(JsonUtil.toJson(msg));
        }
    }

    public void sendRoomInfo(WebSocketClient client) {
        Map<String, Object> info = new HashMap<>();
        info.put("type", "room_info");
        info.put("gameState", gameState);
        info.put("paused", isPaused);
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
        info.put("botCount", botCount);
        info.put("maxPlayers", maxPlayers);
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
        for (int i = 1; i <= (bots.isEmpty() ? Math.min(botCount, 101 - players.size()) : bots.size()); i++) {
            Map<String, Object> botMap = new HashMap<>();
            botMap.put("username", "Bot_" + i);
            botMap.put("isAlive", bots.isEmpty() || i > bots.size() || bots.get(i - 1).isAlive);
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
        map.put("botCount", botCount);
        map.put("playerCount", players.size());
        map.put("maxPlayers", maxPlayers);
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
    public synchronized void handlePauseGame(WebSocketClient client) {
        String username = client.getUsername();
        if (!canControl(username)) {
            sendError(client, "Chỉ chủ phòng hoặc admin mới có thể tạm dừng trận đấu!");
            return;
        }
        
        if (!"QUESTION".equals(gameState)) {
            sendError(client, "Chỉ có thể tạm dừng khi đang có câu hỏi!");
            return;
        }
        
        if (isPaused) {
            sendError(client, "Trận đấu đã được tạm dừng rồi!");
            return;
        }
        
        pauseGame(username);
    }

    private void pauseGame(String username) {
        isPaused = true;
        pauseStartTime = System.currentTimeMillis();
        
        // Giữ thời gian còn lại của lượt đang diễn ra.
        long elapsed = System.currentTimeMillis() - questionStartTime;
        remainingTimeBeforePause = ((mainTurn ? mainPlayerTimeLimit : timeLimit) * 1000L) - elapsed;
        if (remainingTimeBeforePause < 0) remainingTimeBeforePause = 0;
        
        // Hủy timer hiện tại
        if (currentTimer != null) currentTimer.cancel(false);
        
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "arena_game_paused");
        msg.put("message", "⏸️ Trận đấu đã tạm dừng bởi " + username);
        msg.put("pausedBy", username);
        broadcast(msg);
    }
    
    public synchronized void handleResumeGame(WebSocketClient client) {
        String username = client.getUsername();
        if (!canControl(username)) {
            sendError(client, "Chỉ chủ phòng hoặc admin mới có thể tiếp tục trận đấu!");
            return;
        }
        
        if (!isPaused) {
            sendError(client, "Trận đấu chưa bị tạm dừng!");
            return;
        }
        if (disconnectedPlayers.values().stream().anyMatch(p -> p.isAlive)) {
            sendError(client, "Chờ người chơi mất kết nối vào lại phòng trước khi tiếp tục.");
            return;
        }
        
        isPaused = false;
        
        // Điều chỉnh thời gian bắt đầu câu hỏi
        long pauseDuration = System.currentTimeMillis() - pauseStartTime;
        questionStartTime += pauseDuration;
        
        if (currentTimer != null) currentTimer.cancel(false);
        currentTimer = scheduler.schedule(mainTurn ? this::processResults : this::beginMainTurn,
                Math.max(1, remainingTimeBeforePause), TimeUnit.MILLISECONDS);

        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "arena_game_resumed");
        msg.put("message", "▶️ Trận đấu tiếp tục!");
        msg.put("resumedBy", username);
        msg.put("remainingTime", (remainingTimeBeforePause + 999) / 1000);
        msg.put("phase", mainTurn ? "MAIN" : "OPPONENTS");
        broadcast(msg);
    }

    private PlayerState findMainPlayerState() {
        for (PlayerState p : players.values()) {
            if (p.isMainPlayer) return p;
        }
        for (PlayerState p : disconnectedPlayers.values()) if (p.isMainPlayer) return p;
        return null;
    }

    private long countAliveOpponents() {
        long realAlive = java.util.stream.Stream.concat(players.values().stream(), disconnectedPlayers.values().stream()).filter(p -> !p.isMainPlayer && p.isAlive).count();
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
        if ("MIX".equalsIgnoreCase(code) || "ALL".equalsIgnoreCase(code)) return "Tăng dần: Dễ → Trung bình → Khó";
        if ("EASY".equalsIgnoreCase(code))  return "Dễ (Bot đúng ~75%)";
        if ("HARD".equalsIgnoreCase(code))  return "Khó (Bot đúng ~35%)";
        return "Trung Bình (Bot đúng ~55%)";
    }
}
