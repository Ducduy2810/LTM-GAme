package vn.game.games;

import vn.game.db.UserRepository;
import vn.game.model.Question;
import vn.game.model.QuestionBank;
import vn.game.server.WebSocketClient;
import vn.game.util.JsonUtil;

import java.util.*;
import java.util.concurrent.*;

/**
 * Phòng chơi Đấu Trường Sinh Tử (Survival Arena).
 * Luật chơi:
 * - Tất cả người chơi bắt đầu với một lượng Máu (HP, mặc định 100) và 0 Điểm.
 * - Trải qua tối đa N vòng đấu (mặc định 25 vòng), độ khó tăng dần theo giai đoạn.
 * - Trả lời đúng: Giữ nguyên HP, nhận +10 Điểm.
 * - Trả lời sai hoặc không trả lời: Bị trừ HP theo hình phạt của giai đoạn hiện tại.
 * - Khi HP <= 0: Người chơi bị loại khỏi trận chiến sinh tồn.
 * - Hệ thống Mốc Thưởng (Milestone Bonus): Khi tỷ lệ người chơi bị loại đạt các mốc
 *   (25%, 50%, 75%, 90%, 95%), tất cả người sống sót nhận điểm thưởng thành tích.
 * - Điều kiện kết thúc:
 *   + Còn duy nhất 1 người sống sót -> Người đó chiến thắng.
 *   + Hết người sống sót (0 người) -> Người có điểm cao nhất chiến thắng.
 *   + Trải qua hết số vòng quy định -> Người có điểm cao nhất chiến thắng.
 */
public class SurvivalRoom {

    // ─── 1. CẤU TRÚC NGƯỜI CHƠI ───────────────────────────────────────────────

    public static class PlayerState {
        public final WebSocketClient client;
        public final String username;
        public volatile int hp;
        public volatile int score = 0;
        public volatile boolean isAlive = true;
        public volatile boolean isReady = false;
        public volatile Integer answer = null; // 0-3 hoặc null
        public volatile long answerTimeMs = Long.MAX_VALUE;

        public PlayerState(WebSocketClient client, String username, int initialHp) {
            this.client = client;
            this.username = username;
            this.hp = initialHp;
            this.score = 0;
            this.isAlive = initialHp > 0;
            this.isReady = false;
            this.answer = null;
            this.answerTimeMs = Long.MAX_VALUE;
        }
    }

    // ─── 2. CẤU HÌNH GIAI ĐOẠN ĐỘ KHÓ ─────────────────────────────────────────

    public static class PhaseConfig extends LinkedHashMap<String, Object> {
        public final String difficulty;
        public final int penalty;
        public final int timeLimit;
        public final String phaseName;

        public PhaseConfig(String difficulty, int penalty, int timeLimit, String phaseName) {
            this.difficulty = difficulty;
            this.penalty = penalty;
            this.timeLimit = timeLimit;
            this.phaseName = phaseName;

            put("difficulty", difficulty);
            put("penalty", penalty);
            put("timeLimit", timeLimit);
            put("phaseName", phaseName);
        }

        public String getDifficulty() { return difficulty; }
        public int getPenalty() { return penalty; }
        public int getTimeLimit() { return timeLimit; }
        public String getPhaseName() { return phaseName; }
    }

    // ─── 3. TRẠNG THÁI PHÒNG ─────────────────────────────────────────────────

    private final String roomCode;
    private volatile String roomName;
    private volatile String hostUsername;
    private volatile String topic;
    private volatile int startingHp;
    private volatile int totalRounds;
    private volatile int maxPlayers;

    private final UserRepository userRepository;
    private final ScheduledExecutorService scheduler;

    private final Map<WebSocketClient, PlayerState> players = new ConcurrentHashMap<>();

    // Theo dõi username đã từng trong phòng (để cho phép rejoin khi đang chơi)
    private final Set<String> recentlyLeftUsernames = Collections.synchronizedSet(new HashSet<>());
    private final List<Question> questions = new ArrayList<>();

    private volatile String gameState = "WAITING"; // WAITING, QUESTION, RESULT, END
    private volatile int currentRound = 0;
    private ScheduledFuture<?> currentTimer = null;
    private volatile long questionStartTime = 0;

    // Tổng số người chơi lúc bắt đầu trận đấu để tính mốc % bị loại
    private volatile int initialPlayerCount = 0;

    // Bộ theo dõi các mốc % đã kích hoạt (25, 50, 75, 90, 95)
    private final Set<Integer> milestonesTriggered = Collections.synchronizedSet(new HashSet<>());

    // ─── 4. CONSTRUCTOR ───────────────────────────────────────────────────────

    public SurvivalRoom(String roomCode, String roomName, String hostUsername,
                        String topic, int startingHp, int totalRounds,
                        UserRepository userRepository, ScheduledExecutorService scheduler) {
        this.roomCode = roomCode;
        this.roomName = (roomName != null && !roomName.trim().isEmpty()) ? roomName.trim() : ("Đấu Trường Sinh Tử " + roomCode);
        this.hostUsername = hostUsername;
        this.topic = (topic != null) ? topic.toUpperCase().trim() : "ALL";
        this.startingHp = (startingHp > 0) ? startingHp : 100;
        this.totalRounds = (totalRounds > 0) ? totalRounds : 25;
        this.maxPlayers = 20; // Mặc định hiển thị
        this.userRepository = userRepository;
        this.scheduler = scheduler;
    }

    // ─── 5. GETTERS & TIỆN ÍCH QUYỀN ──────────────────────────────────────────

    public String getRoomCode()     { return roomCode; }
    public String getRoomName()     { return roomName; }
    public String getHostUsername() { return hostUsername; }
    public String getTopic()        { return topic; }
    public int getStartingHp()      { return startingHp; }
    public int getTotalRounds()     { return totalRounds; }
    public int getMaxPlayers()      { return maxPlayers; }
    public void setMaxPlayers(int count) { maxPlayers = Math.max(2, Math.min(100, count)); }
    public synchronized boolean canJoin(String username) {
        if (players.values().stream().anyMatch(p -> p.username.equalsIgnoreCase(username))) return true;
        boolean active = !"WAITING".equals(gameState) && !"END".equals(gameState);
        return active ? recentlyLeftUsernames.contains(username.toLowerCase()) : players.size() < maxPlayers;
    }
    public String getGameState()    { return gameState; }
    public int getCurrentRound()    { return currentRound; }
    public int getPlayerCount()     { return players.size(); }
    public boolean isEmpty()        { return players.isEmpty(); }

    public boolean isSuperAdmin(String username) {
        if (username == null) return false;
        if ("admin".equalsIgnoreCase(username) || "DuyNguyen".equalsIgnoreCase(username)) return true;
        return userRepository != null && userRepository.isAdmin(username);
    }

    public boolean isHostPresent() {
        if (hostUsername == null) return false;
        for (PlayerState p : players.values()) {
            if (hostUsername.equalsIgnoreCase(p.username)) return true;
        }
        return false;
    }

    public String getEffectiveHostUsername() {
        if (isHostPresent()) return hostUsername;
        for (PlayerState p : players.values()) {
            if (p.username != null) return p.username;
        }
        return hostUsername;
    }

    public boolean isHost(String username) {
        if (username == null) return false;
        String eff = getEffectiveHostUsername();
        return eff != null && eff.equalsIgnoreCase(username);
    }

    public boolean canControl(String username) {
        return isHost(username) || isSuperAdmin(username);
    }

    // ─── 6. CẤU HÌNH GIAI ĐOẠN (DIFFICULTY SCALING) ───────────────────────────

    /**
     * Trả về cấu hình giai đoạn (độ khó, mức phạt HP, thời gian, tên giai đoạn) theo vòng:
     * Rounds 1-5:   EASY     penalty=5   timeLimit=20s
     * Rounds 6-10:  MEDIUM   penalty=10  timeLimit=18s
     * Rounds 11-15: HARD     penalty=15  timeLimit=15s
     * Rounds 16-20: V.HARD   penalty=20  timeLimit=12s
     * Rounds 21-25: FINAL    penalty=25  timeLimit=10s
     */
    public PhaseConfig getPhaseConfig(int roundNumber) {
        if (roundNumber <= 5) {
            return new PhaseConfig("EASY", 5, 20, "EASY");
        } else if (roundNumber <= 10) {
            return new PhaseConfig("MEDIUM", 10, 18, "MEDIUM");
        } else if (roundNumber <= 15) {
            return new PhaseConfig("HARD", 15, 15, "HARD");
        } else if (roundNumber <= 20) {
            return new PhaseConfig("V.HARD", 20, 12, "V.HARD");
        } else {
            return new PhaseConfig("FINAL", 25, 10, "FINAL");
        }
    }

    // ─── 7. THAM GIA / RỜI PHÒNG / SẴN SÀNG ─────────────────────────────────

    public synchronized void handleJoin(WebSocketClient client) {
        String username = client.getUsername();
        if (username == null) return;

        // Xóa kết nối cũ trùng username (reconnect/rejoin)
        players.keySet().removeIf(old -> {
            if (username.equalsIgnoreCase(old.getUsername())) {
                if (!old.equals(client)) old.close();
                return true;
            }
            return false;
        });
        players.keySet().removeIf(WebSocketClient::isClosed);

        // Chặn người mới hoàn toàn khi đang chơi; cho phép người đã từng trong phòng vào lại
        boolean gameActive = !"WAITING".equals(gameState) && !"END".equals(gameState);
        if (gameActive && !recentlyLeftUsernames.contains(username.toLowerCase())) {
            sendError(client, "Trận đấu đang diễn ra! Không thể vào phòng lúc này.");
            return;
        }
        recentlyLeftUsernames.remove(username.toLowerCase());

        PlayerState state = new PlayerState(client, username, startingHp);
        
        // Khôi phục quyền chủ phòng nếu đây là chủ phòng ban đầu
        if (canControl(username)) {
            state.isReady = true;
        }
        
        players.put(client, state);

        // Chỉ gán hostUsername lần đầu tiên (khi phòng mới)
        if (hostUsername == null || hostUsername.isEmpty()) {
            hostUsername = username;
        }

        sendRoomInfo(client);
        
        // Nếu đang trong game, gửi trạng thái hiện tại cho người vừa vào
        if (!"WAITING".equals(gameState)) {
            sendCurrentGameState(client);
        }
        
        broadcastPlayers();
    }

    public synchronized void handleLeave(WebSocketClient client) {
        String leavingUsername = client.getUsername();
        boolean wasHost = (leavingUsername != null && leavingUsername.equalsIgnoreCase(hostUsername));

        // Ghi nhận username để cho phép rejoin sau
        if (leavingUsername != null) {
            recentlyLeftUsernames.add(leavingUsername.toLowerCase());
        }

        players.remove(client);
        players.keySet().removeIf(WebSocketClient::isClosed);

        if (players.isEmpty()) {
            // Chỉ reset khi không đang chơi
            if ("WAITING".equals(gameState) || "END".equals(gameState)) {
                gameState = "WAITING";
                currentRound = 0;
                milestonesTriggered.clear();
                if (currentTimer != null) { currentTimer.cancel(false); currentTimer = null; }
            }
        } else if (wasHost) {
            WebSocketClient nextHost = players.keySet().stream()
                    .filter(c -> !c.isClosed())
                    .findFirst()
                    .orElse(null);
            
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
        if (!isHost(state.username)) {
            state.isReady = !state.isReady;
        } else {
            state.isReady = true;
        }
        broadcastPlayers();
    }

    // ─── 8. BẮT ĐẦU TRẬN ĐẤU ────────────────────────────────────────────────

    public synchronized void handleStartGame(WebSocketClient client) {
        String username = client.getUsername();
        if (username == null) return;

        if (!canControl(username)) {
            sendError(client, "Chỉ Chủ phòng hoặc Super Admin mới có quyền bắt đầu trận đấu!");
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
            startGame();
        }
    }

    private synchronized void startGame() {
        gameState = "WAITING";
        currentRound = 0;
        milestonesTriggered.clear();

        // Đặt lại HP, Điểm và trạng thái sống sót của toàn bộ người chơi
        for (PlayerState p : players.values()) {
            p.hp = startingHp;
            p.score = 0;
            p.isAlive = startingHp > 0;
            p.answer = null;
            p.answerTimeMs = Long.MAX_VALUE;
        }

        initialPlayerCount = players.size();
        if (initialPlayerCount == 0) return;

        // YÊU CẦU 2: Tải câu hỏi theo độ khó tăng dần cho từng giai đoạn
        questions.clear();
        
        // Giai đoạn 1: Rounds 1-5 (EASY)
        List<Question> easyQuestions = QuestionBank.getQuestions(topic, "EASY", 5);
        questions.addAll(easyQuestions);
        
        // Giai đoạn 2: Rounds 6-10 (MEDIUM)
        List<Question> mediumQuestions = QuestionBank.getQuestions(topic, "MEDIUM", 5);
        questions.addAll(mediumQuestions);
        
        // Giai đoạn 3: Rounds 11-15 (HARD)
        List<Question> hardQuestions = QuestionBank.getQuestions(topic, "HARD", 5);
        questions.addAll(hardQuestions);
        
        // Giai đoạn 4: Rounds 16-20 (HARD - cực khó)
        List<Question> veryHardQuestions = QuestionBank.getQuestions(topic, "HARD", 5);
        questions.addAll(veryHardQuestions);
        
        // Giai đoạn 5: Rounds 21-25 (HARD - chung kết)
        List<Question> finalQuestions = QuestionBank.getQuestions(topic, "HARD", 5);
        questions.addAll(finalQuestions);

        // Phát thông báo bắt đầu trận đấu
        Map<String, Object> startMsg = new HashMap<>();
        startMsg.put("type", "survival_game_started");
        startMsg.put("roomCode", roomCode);
        startMsg.put("roomName", roomName);
        startMsg.put("totalRounds", totalRounds);
        startMsg.put("startingHp", startingHp);
        startMsg.put("playerCount", initialPlayerCount);
        broadcast(startMsg);

        if (currentTimer != null) currentTimer.cancel(false);
        currentTimer = scheduler.schedule(this::sendQuestion, 1500, TimeUnit.MILLISECONDS);
    }

    // ─── 9. GỬI CÂU HỎI MỚI ─────────────────────────────────────────────────

    private synchronized void sendQuestion() {
        long survivorsCount = countAlivePlayers();

        // Kiểm tra điều kiện kết thúc sớm
        if (survivorsCount == 0) {
            endGame("Tất cả người chơi đã bị loại! Trận đấu kết thúc.", null);
            return;
        }
        if (survivorsCount == 1 && initialPlayerCount > 1) {
            PlayerState winner = players.values().stream().filter(p -> p.isAlive).findFirst().orElse(null);
            endGame(null, winner != null ? winner.username : null);
            return;
        }
        if (currentRound >= totalRounds || currentRound >= questions.size()) {
            endGame("Đã hoàn thành toàn bộ số vòng quy định!", null);
            return;
        }

        currentRound++;
        gameState = "QUESTION";
        questionStartTime = System.currentTimeMillis();

        PhaseConfig phaseConfig = getPhaseConfig(currentRound);
        Question q = questions.get(currentRound - 1);

        // Đặt lại đáp án của người chơi còn sống
        for (PlayerState p : players.values()) {
            p.answer = null;
            p.answerTimeMs = Long.MAX_VALUE;
        }

        long totalEliminated = initialPlayerCount - survivorsCount;

        // Tạo danh sách trạng thái người chơi để gửi cùng câu hỏi
        List<Map<String, Object>> playerStates = new ArrayList<>();
        for (PlayerState p : players.values()) {
            Map<String, Object> map = new HashMap<>();
            map.put("username", p.username);
            map.put("hp", p.hp);
            map.put("score", p.score);
            map.put("isAlive", p.isAlive);
            playerStates.add(map);
        }

        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "survival_new_question");
        msg.put("questionId", currentRound);
        msg.put("totalQuestions", Math.min(totalRounds, questions.size()));
        msg.put("text", q.getText());
        msg.put("options", q.getOptions());
        msg.put("timeLimit", phaseConfig.timeLimit);
        msg.put("phaseName", phaseConfig.phaseName);
        msg.put("penalty", phaseConfig.penalty);
        msg.put("difficulty", phaseConfig.difficulty);
        msg.put("playersAlive", survivorsCount);
        msg.put("totalEliminated", totalEliminated);
        msg.put("round", currentRound);
        msg.put("playerStates", playerStates);
        broadcast(msg);

        if (currentTimer != null) currentTimer.cancel(false);
        currentTimer = scheduler.schedule(this::processResults, phaseConfig.timeLimit, TimeUnit.SECONDS);
    }

    // ─── 10. NHẬN ĐÁP ÁN TỪ NGƯỜI CHƠI ──────────────────────────────────────

    public synchronized void handleSubmitAnswer(WebSocketClient client, int answerIndex) {
        if (answerIndex < 0 || answerIndex > 3) return;
        PlayerState state = players.get(client);
        if (state != null && state.isAlive && "QUESTION".equals(gameState)) {
            if (System.currentTimeMillis() - questionStartTime >= getPhaseConfig(currentRound).timeLimit * 1000L) return;
            if (state.answer == null) {
                state.answer = answerIndex;
                state.answerTimeMs = Math.max(1, System.currentTimeMillis() - questionStartTime);
            }
        }
    }

    // ─── 11. XỬ LÝ KẾT QUẢ VÒNG & MỐC THƯỞNG ────────────────────────────────

    private synchronized void processResults() {
        gameState = "RESULT";
        Question q = questions.get(currentRound - 1);
        int correctIndex = q.getCorrect();

        PhaseConfig phaseConfig = getPhaseConfig(currentRound);
        int penalty = phaseConfig.penalty;

        List<String> eliminatedThisRound = new ArrayList<>();
        
        // YÊU CẦU 1: Thu thập thông tin đáp án của từng người chơi
        Map<String, Map<String, Object>> playerAnswerResults = new HashMap<>();

        // Kiểm tra đáp án của từng người chơi còn sống
        for (PlayerState p : players.values()) {
            if (!p.isAlive) continue;

            boolean isCorrect = (p.answer != null && p.answer == correctIndex);
            
            // YÊU CẦU 1: Lưu thông tin đáp án của người chơi
            Map<String, Object> answerInfo = new HashMap<>();
            answerInfo.put("answer", p.answer != null ? p.answer : -1);
            answerInfo.put("isCorrect", isCorrect);
            answerInfo.put("answerText", p.answer != null && p.answer >= 0 && p.answer < 4 ? q.getOptions().get(p.answer) : "Không trả lời");
            playerAnswerResults.put(p.username, answerInfo);

            if (isCorrect) {
                // Trả lời đúng: Giữ nguyên HP, thưởng +10 điểm
                p.score += 10;
            } else {
                // Trả lời sai hoặc không trả lời: Trừ HP theo mức phạt giai đoạn
                p.hp = Math.max(0, p.hp - Math.abs(penalty));
                if (p.hp <= 0) {
                    p.isAlive = false;
                    eliminatedThisRound.add(p.username);
                }
            }
        }

        // Tính tổng số người bị loại và người còn sống
        long survivorsCount = countAlivePlayers();
        long totalEliminated = initialPlayerCount - survivorsCount;

        // Xử lý hệ thống mốc thưởng (Milestone Bonus System)
        int milestoneBonus = 0;
        List<String> milestoneDescriptions = new ArrayList<>();
        int[][] milestones = {
                {25, 5},
                {50, 10},
                {75, 15},
                {90, 20},
                {95, 30}
        };

        if (initialPlayerCount > 0) {
            for (int[] m : milestones) {
                int pct = m[0];
                int bonus = m[1];
                if (!milestonesTriggered.contains(pct)) {
                    if ((totalEliminated * 100.0 / initialPlayerCount) >= pct) {
                        milestonesTriggered.add(pct);
                        milestoneBonus += bonus;
                        milestoneDescriptions.add(pct + "%");
                    }
                }
            }
        }

        // Thưởng điểm cho tất cả người chơi còn sống nếu đạt mốc
        if (milestoneBonus > 0) {
            for (PlayerState p : players.values()) {
                if (p.isAlive) {
                    p.score += milestoneBonus;
                }
            }
        }

        // Tạo danh sách trạng thái người chơi
        List<Map<String, Object>> playerStates = new ArrayList<>();
        for (PlayerState p : players.values()) {
            Map<String, Object> map = new HashMap<>();
            map.put("username", p.username);
            map.put("hp", p.hp);
            map.put("score", p.score);
            map.put("isAlive", p.isAlive);
            playerStates.add(map);
        }

        // Gửi kết quả vòng đấu
        Map<String, Object> resultMsg = new HashMap<>();
        resultMsg.put("type", "survival_round_result");
        resultMsg.put("correctAnswerIndex", correctIndex);
        resultMsg.put("correctAnswerText", q.getOptions().get(correctIndex));
        resultMsg.put("playerAnswerResults", playerAnswerResults); // YÊU CẦU 1: Gửi thông tin đáp án của người chơi
        resultMsg.put("round", currentRound);
        resultMsg.put("eliminatedThisRound", eliminatedThisRound);
        resultMsg.put("survivorsCount", survivorsCount);
        resultMsg.put("totalEliminated", totalEliminated);
        resultMsg.put("penalty", penalty);
        if (milestoneBonus > 0) {
            resultMsg.put("milestoneBonus", milestoneBonus);
            resultMsg.put("milestoneMessage", "Đã đạt mốc loại " + String.join(", ", milestoneDescriptions)
                    + " người chơi! Tất cả người sống sót nhận +" + milestoneBonus + " điểm thưởng!");
        }
        resultMsg.put("playerStates", playerStates);
        broadcast(resultMsg);

        // Lên lịch xử lý vòng tiếp theo hoặc kết thúc sau 5 giây
        if (currentTimer != null) currentTimer.cancel(false);
        currentTimer = scheduler.schedule(() -> {
            synchronized (SurvivalRoom.this) {
                if (survivorsCount == 0) {
                    endGame("Tất cả người chơi đã bị loại! Trận đấu kết thúc.", null);
                } else if (survivorsCount == 1 && initialPlayerCount > 1) {
                    PlayerState winner = players.values().stream().filter(p -> p.isAlive).findFirst().orElse(null);
                    endGame(null, winner != null ? winner.username : null);
                } else if (currentRound >= totalRounds || currentRound >= questions.size()) {
                    endGame("Đã hoàn thành toàn bộ " + totalRounds + " vòng đấu!", null);
                } else {
                    sendQuestion();
                }
            }
        }, 5, TimeUnit.SECONDS);
    }

    // ─── 12. KẾT THÚC TRẬN ĐẤU ───────────────────────────────────────────────

    private synchronized void endGame(String overrideReason, String winnerOverride) {
        gameState = "END";
        if (currentTimer != null) {
            currentTimer.cancel(false);
            currentTimer = null;
        }

        // Xếp hạng người chơi:
        // 1. Người sống sót (isAlive) ưu tiên hơn người đã bị loại.
        // 2. Điểm số cao hơn đứng trước.
        // 3. Máu (HP) cao hơn đứng trước.
        List<PlayerState> standings = new ArrayList<>(players.values());
        standings.sort((a, b) -> {
            if (a.isAlive != b.isAlive) {
                return a.isAlive ? -1 : 1;
            }
            if (a.score != b.score) {
                return Integer.compare(b.score, a.score);
            }
            return Integer.compare(b.hp, a.hp);
        });

        String winner = winnerOverride;
        if (winner == null && !standings.isEmpty()) {
            winner = standings.get(0).username;
        }

        String reason = overrideReason;
        if (reason == null) {
            long survivors = countAlivePlayers();
            if (survivors == 1 && initialPlayerCount > 1) {
                reason = "🏆 " + winner + " là người sống sót duy nhất và trở thành Quán Quân Đấu Trường Sinh Tử!";
            } else if (survivors == 0) {
                reason = "Tất cả người chơi đều bị loại! " + winner + " chiến thắng với điểm số cao nhất!";
            } else {
                reason = "Trận đấu hoàn thành! " + winner + " chiến thắng với điểm số cao nhất!";
            }
        }

        List<Map<String, Object>> finalStandings = new ArrayList<>();
        int rank = 1;
        for (PlayerState p : standings) {
            Map<String, Object> map = new HashMap<>();
            map.put("rank", rank++);
            map.put("username", p.username);
            map.put("hp", p.hp);
            map.put("score", p.score);
            map.put("isAlive", p.isAlive);
            finalStandings.add(map);
        }

        Map<String, Object> endMsg = new HashMap<>();
        endMsg.put("type", "survival_end_game");
        endMsg.put("reason", reason);
        endMsg.put("winner", winner != null ? winner : "Không có");
        endMsg.put("finalStandings", finalStandings);
        broadcast(endMsg);

        // Sau 2 giây chuyển về trạng thái WAITING để chuẩn bị cho trận kế tiếp
        scheduler.schedule(() -> {
            synchronized (SurvivalRoom.this) {
                gameState = "WAITING";
                currentRound = 0;
                milestonesTriggered.clear();
            }
            broadcastPlayers();
        }, 2, TimeUnit.SECONDS);
    }

    // ─── 13. THÔNG TIN PHÒNG & BROADCAST ────────────────────────────────────

    private void sendCurrentGameState(WebSocketClient client) {
        // Gửi trạng thái game hiện tại cho người vừa join vào giữa chừng
        if ("QUESTION".equals(gameState) && currentRound > 0 && currentRound <= questions.size()) {
            PhaseConfig phaseConfig = getPhaseConfig(currentRound);
            Question q = questions.get(currentRound - 1);
            
            long survivorsCount = countAlivePlayers();
            long totalEliminated = initialPlayerCount - survivorsCount;
            
            // Tạo danh sách trạng thái người chơi
            List<Map<String, Object>> playerStates = new ArrayList<>();
            for (PlayerState p : players.values()) {
                Map<String, Object> map = new HashMap<>();
                map.put("username", p.username);
                map.put("hp", p.hp);
                map.put("score", p.score);
                map.put("isAlive", p.isAlive);
                playerStates.add(map);
            }
            
            Map<String, Object> msg = new HashMap<>();
            msg.put("type", "survival_new_question");
            msg.put("questionId", currentRound);
            msg.put("totalQuestions", Math.min(totalRounds, questions.size()));
            msg.put("text", q.getText());
            msg.put("options", q.getOptions());
            msg.put("timeLimit", phaseConfig.timeLimit);
            msg.put("phaseName", phaseConfig.phaseName);
            msg.put("penalty", phaseConfig.penalty);
            msg.put("difficulty", phaseConfig.difficulty);
            msg.put("playersAlive", survivorsCount);
            msg.put("totalEliminated", totalEliminated);
            msg.put("round", currentRound);
            msg.put("playerStates", playerStates);
            msg.put("spectatorMode", true); // Đánh dấu là chế độ xem
            client.send(JsonUtil.toJson(msg));
        }
    }

    public void sendRoomInfo(WebSocketClient client) {
        Map<String, Object> info = new HashMap<>();
        info.put("type", "survival_room_info");
        info.put("roomCode", roomCode);
        info.put("roomName", roomName);
        info.put("hostUsername", getEffectiveHostUsername());
        info.put("topic", topic);
        info.put("topicName", getTopicDisplayName(topic));
        info.put("startingHp", startingHp);
        info.put("totalRounds", totalRounds);
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
            if (!isUserHost && !isUserAdmin && !ready) {
                allReady = false;
            }

            Map<String, Object> map = new HashMap<>();
            map.put("username", p.username);
            map.put("hp", p.hp);
            map.put("score", p.score);
            map.put("isAlive", p.isAlive);
            map.put("isReady", ready);
            map.put("isHost", isUserHost);
            map.put("isSuperAdmin", isUserAdmin);
            list.add(map);
        }

        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "survival_update_players");
        msg.put("players", list);
        msg.put("hostUsername", getEffectiveHostUsername());
        msg.put("roomCode", roomCode);
        msg.put("roomName", roomName);
        msg.put("allReady", allReady);
        broadcast(msg);
    }

    public Map<String, Object> toSummaryMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("roomCode", roomCode);
        map.put("roomName", roomName);
        map.put("hostUsername", getEffectiveHostUsername());
        map.put("topic", topic);
        map.put("topicName", getTopicDisplayName(topic));
        map.put("startingHp", startingHp);
        map.put("totalRounds", totalRounds);
        map.put("maxPlayers", maxPlayers);
        map.put("playerCount", players.size());
        map.put("gameState", gameState);
        return map;
    }

    public void broadcast(Map<String, Object> map) {
        String json = JsonUtil.toJson(map);
        for (WebSocketClient client : players.keySet()) {
            if (!client.isClosed()) {
                client.send(json);
            }
        }
    }

    // ─── 14. TIỆN ÍCH NỘI BỘ ────────────────────────────────────────────────

    private long countAlivePlayers() {
        return players.values().stream().filter(p -> p.isAlive).count();
    }

    private void sendError(WebSocketClient client, String message) {
        Map<String, Object> err = new HashMap<>();
        err.put("type", "survival_error");
        err.put("message", message);
        client.send(JsonUtil.toJson(err));
    }

    public static String getTopicDisplayName(String code) {
        if ("NETWORKING".equalsIgnoreCase(code)) return "Lập Trình & Mạng Máy Tính";
        if ("SCIENCE".equalsIgnoreCase(code))    return "Khoa Học & Đời Sống";
        if ("HISTORY".equalsIgnoreCase(code))    return "Lịch Sử & Xã Hội";
        return "Tổng Hợp Kiến Thức";
    }
}
