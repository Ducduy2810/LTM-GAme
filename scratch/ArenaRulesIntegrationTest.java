import vn.game.db.DatabaseManager;
import vn.game.games.ArenaGame;
import vn.game.games.ArenaRoom;
import vn.game.model.Leaderboard;
import vn.game.model.Question;
import vn.game.model.QuestionBank;
import vn.game.server.WebSocketClient;
import vn.game.util.JsonUtil;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class ArenaRulesIntegrationTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (!Files.exists(Path.of(".arena-test"))) throw new IllegalStateException("Use test_arena_rules.ps1");
        DatabaseManager.getInstance().init();
        try {
            questionSelection();
            roleSelection();
            sequentialTurns();
            delayedTimersRejectLateAnswers();
            reconnectKeepsTurnAndLifelines();
            roomOwnershipAndDeletion();
            check(Leaderboard.getTop(100).isEmpty(), "aborted rooms must not write wins or losses");
            System.out.println("PASS: " + checks + " arena rules integration checks.");
        } finally { DatabaseManager.getInstance().close(); }
    }

    private static void questionSelection() {
        for (String topic : List.of("ALL", "NETWORKING", "SCIENCE", "HISTORY")) {
            for (String level : List.of("EASY", "MEDIUM", "HARD")) {
                List<Question> questions = QuestionBank.getArenaQuestions(topic, level, 100);
                check(!questions.isEmpty(), "question bank supports " + topic + "/" + level);
                Set<String> unique = new HashSet<>();
                for (Question question : questions) {
                    check(question.getDifficulty().equals(level), "strict difficulty");
                    check(topic.equals("ALL") || question.getTopic().equals(topic), "strict topic");
                    check(unique.add(question.getText()), "no repeated question to fill count");
                }
                List<Question> progressive = QuestionBank.getArenaQuestions(topic, "MIX", 10);
                int previous = 0;
                Set<String> levels = new HashSet<>();
                for (Question question : progressive) {
                    int next = List.of("EASY", "MEDIUM", "HARD").indexOf(question.getDifficulty());
                    check(next >= previous, "progressive questions increase difficulty");
                    previous = next;
                    levels.add(question.getDifficulty());
                    check(topic.equals("ALL") || question.getTopic().equals(topic), "progression respects topic");
                }
                check(levels.size() == 3, "short mixed game includes all three levels");
            }
        }
        boolean rejected = false;
        try { QuestionBank.getArenaQuestions("INVALID", "EASY", 5); } catch (IllegalArgumentException e) { rejected = true; }
        check(rejected, "invalid question config rejected");
        check(QuestionBank.getQuestions("NETWORKING", "EASY", 5).size() == 5, "legacy selector used by Survival retains its behavior");
    }

    private static void roleSelection() throws Exception {
        try (Trial trial = new Trial(1, 2, 0)) {
            int errors = trial.opponent.count("arena_error");
            trial.room.handleSelectMainPlayer(trial.opponent, "MANUAL", "opponent");
            check(trial.opponent.count("arena_error") == errors + 1, "opponent cannot choose main player");
            trial.room.handleSelectMainPlayer(trial.host, "MANUAL", "opponent");
            trial.room.handleStartGame(trial.host);
            check(trial.host.last("arena_game_started").get("mainPlayer").equals("opponent"), "manual selection respected");
            check(trial.room.isHost("owner"), "host controls independent from main player role");
            trial.room.handleSelectMainPlayer(trial.host, "RANDOM", "");
            check(trial.host.last("arena_error").get("message").toString().contains("phòng chờ"), "cannot change role mid-game");
        }
        for (int i = 0; i < 8; i++) {
            try (Trial trial = new Trial(1, 1, 3)) {
                trial.room.configureInitialMainPlayer(1, "RANDOM");
                trial.room.handleStartGame(trial.host);
                check(Set.of("owner", "opponent").contains(trial.host.last("arena_game_started").get("mainPlayer")), "random main is a connected human");
            }
        }
        try (Trial trial = new Trial(1, 1, 1)) {
            trial.room.handleSelectMainPlayer(trial.host, "MANUAL", "opponent");
            trial.room.handleLeave(trial.opponent);
            trial.room.handleStartGame(trial.host);
            check(trial.host.count("arena_game_started") == 0, "selected player leaving blocks start until new selection");
        }
        try (Trial trial = new Trial(1, 1, 0)) {
            trial.room.handleLeave(trial.opponent);
            trial.room.handleStartGame(trial.host);
            check(trial.host.count("arena_game_started") == 0, "cannot start without an opponent");
        }
    }

    private static void sequentialTurns() throws Exception {
        try (Trial trial = new Trial(1, 2, 0)) {
            Map<String, Object> info = trial.host.last("room_info");
            check(number(info, "timeLimit") == 1 && number(info, "mainPlayerTimeLimit") == 2, "configured timers reach room info");
            trial.room.handleStartGame(trial.host);
            Map<String, Object> question = trial.host.await("arena_new_question", 0, 2500);
            check(question.get("phase").equals("OPPONENTS") && Boolean.FALSE.equals(question.get("canAnswer")), "main waits during opponent phase");
            check(Boolean.TRUE.equals(trial.opponent.last("arena_new_question").get("canAnswer")), "opponent can answer first");
            int correct = correctAnswer(question);
            trial.room.handleSubmitAnswer(trial.host, correct);
            check(trial.host.count("arena_answer_locked") == 0, "early main answer rejected");
            trial.room.handleLifeTrust(trial.host);
            trial.room.handleLifeAsk(trial.host, "opponent");
            trial.room.handleLifeSurvey(trial.host);
            check(trial.host.count("arena_life_trust") + trial.host.count("arena_life_ask") + trial.host.count("arena_life_survey") == 0, "all lifelines blocked before main turn");
            trial.room.handleSubmitAnswer(trial.opponent, -1);
            check(trial.opponent.count("arena_answer_locked") == 0, "invalid option rejected without locking");
            trial.room.handleSubmitAnswer(trial.opponent, correct);
            trial.room.handleSubmitAnswer(trial.opponent, (correct + 1) % 4);
            check(trial.opponent.count("arena_answer_locked") == 1, "opponent can lock only one answer");
            int unauthorized = trial.opponent.count("arena_error");
            trial.room.handlePauseGame(trial.opponent);
            check(trial.opponent.count("arena_error") == unauthorized + 1, "opponent cannot pause");
            trial.room.handlePauseGame(trial.host);
            Thread.sleep(1100);
            check(trial.host.count("arena_main_player_turn") == 0, "pause freezes opponent phase without premature transition");
            trial.room.handleSubmitAnswer(trial.opponent, correct);
            check(trial.opponent.count("arena_answer_locked") == 1, "paused game accepts no new answer");
            trial.room.handleResumeGame(trial.host);
            trial.host.await("arena_main_player_turn", 0, 2000);
            check(trial.host.last("arena_main_player_turn").get("phase").equals("MAIN"), "server starts separate main phase");
            check(number(trial.host.last("arena_main_player_turn"), "timeLimit") == 2, "main receives full configured time");
            trial.room.handleSubmitAnswer(trial.opponent, correct);
            check(trial.opponent.count("arena_answer_locked") == 1, "opponent remains locked during main phase");
            trial.room.handleLifeSurvey(trial.opponent);
            check(trial.opponent.count("arena_life_survey") == 0, "opponent cannot use main lifelines");
            trial.room.handleLifeAsk(trial.host, "missing");
            trial.room.handleLifeAsk(trial.host, "opponent");
            check(trial.host.count("arena_life_ask") == 1, "invalid target does not consume ask lifeline");
            trial.room.handleLifeTrust(trial.host);
            trial.room.handleLifeSurvey(trial.host);
            check(trial.host.count("arena_life_trust") == 1 && trial.host.count("arena_life_survey") == 1, "lifelines available after opponent answers lock");
            trial.room.handleLifeTrust(trial.host);
            trial.room.handleLifeSurvey(trial.host);
            check(trial.host.count("arena_life_trust") == 1 && trial.host.count("arena_life_survey") == 1, "lifelines single-use");
            trial.room.handlePauseGame(trial.host);
            trial.room.handleSubmitAnswer(trial.host, correct);
            check(trial.host.count("arena_answer_locked") == 0, "main cannot answer while paused");
            trial.room.handleResumeGame(trial.host);
            trial.room.handleSubmitAnswer(trial.host, correct);
            check(trial.host.count("arena_answer_locked") == 1, "main can answer after resume");
            Map<String, Object> result = trial.host.await("arena_round_result", 0, 3500);
            check(Boolean.TRUE.equals(result.get("mainPlayerCorrect")), "main answer scored correctly");
            check(((List<?>) result.get("eliminatedRealOpponents")).isEmpty(), "opponent first locked answer retained");
            long phaseMs = TimeUnit.NANOSECONDS.toMillis(trial.host.time("arena_round_result") - trial.host.time("arena_main_player_turn"));
            check(phaseMs >= 1800, "early main answer does not shorten configured phase");
        }
    }

    private static void delayedTimersRejectLateAnswers() throws Exception {
        try (Trial trial = new Trial(1, 1, 0)) {
            trial.room.handleStartGame(trial.host);
            trial.host.await("arena_new_question", 0, 2500);
            trial.scheduler.execute(() -> sleep(1500));
            Thread.sleep(1100);
            trial.room.handleSubmitAnswer(trial.opponent, 0);
            check(trial.opponent.count("arena_answer_locked") == 0, "late opponent rejected even when scheduler callback is delayed");
            trial.host.await("arena_main_player_turn", 0, 2000);
            trial.scheduler.execute(() -> sleep(1500));
            Thread.sleep(1100);
            trial.room.handleSubmitAnswer(trial.host, 0);
            trial.room.handleLifeSurvey(trial.host);
            check(trial.host.count("arena_answer_locked") == 0 && trial.host.count("arena_life_survey") == 0, "late main answer and lifeline rejected independently of timer callback");
            trial.host.await("arena_round_result", 0, 2000);
        }
    }

    private static void roomOwnershipAndDeletion() throws Exception {
        try (ArenaGame game = new ArenaGame()) {
            Client owner = new Client("owner");
            Client guest = new Client("guest");
            Client outsider = new Client("outsider");
            Map<String, Object> config = new HashMap<>(Map.of("roomCode", "RULES", "roomName", "Rules test",
                    "topic", "NETWORKING", "difficulty", "HARD", "questionCount", 20, "botCount", 0,
                    "timeLimit", 1, "mainPlayerTimeLimit", 2, "mainSelectionMode", "MANUAL"));
            game.handleCreateRoom(owner, config);
            check(owner.last("create_room_success").containsKey("warning"), "insufficient questions produce clear warning");
            check(number(owner.last("room_info"), "questionCount") < 20, "room reports actual available count");
            game.handleJoinRoom(guest, "RULES");
            game.handleToggleReady(guest);
            game.handleAdminCloseRoom(outsider, "RULES");
            check(game.getRoomsSummaryList().stream().anyMatch(r -> r.get("roomCode").equals("RULES")), "outsider cannot delete room");
            game.handleSelectMainPlayer(owner, Map.of("mainSelectionMode", "MANUAL", "username", "guest"));
            game.handleStartGame(owner);
            check(owner.last("arena_game_started").get("mainPlayer").equals("guest"), "game routes manual main selection");
            Map<String, Object> question = owner.await("arena_new_question", 0, 2500);
            check(question.get("difficulty").equals("HARD") && question.get("topic").equals("NETWORKING"), "actual match respects selected difficulty and topic");
            game.handleAdminCloseRoom(owner, "RULES");
            check(owner.count("arena_room_closed") == 1 && guest.count("arena_room_closed") == 1, "host deletion returns all members to lobby");
            check(game.getRoomsSummaryList().stream().noneMatch(r -> r.get("roomCode").equals("RULES")), "deleted room removed from list");
            Thread.sleep(1200);
            check(guest.count("arena_main_player_turn") == 0 && guest.count("arena_round_result") == 0, "deletion cancels pending game timers");
            game.handleAdminCloseRoom(new Client("admin"), "DT100");
            check(game.getRoomsSummaryList().stream().anyMatch(r -> r.get("roomCode").equals("DT100")), "default room protected even from admin deletion");
            config.put("roomCode", "BAD"); config.put("timeLimit", 0);
            game.handleCreateRoom(owner, config);
            check(game.getRoomsSummaryList().stream().noneMatch(r -> r.get("roomCode").equals("BAD")), "invalid time config rejected");
            config.put("timeLimit", 1.5);
            game.handleCreateRoom(owner, config);
            check(game.getRoomsSummaryList().stream().noneMatch(r -> r.get("roomCode").equals("BAD")), "fractional time rejected rather than truncated");
        }
    }

    private static void reconnectKeepsTurnAndLifelines() throws Exception {
        try (Trial trial = new Trial(1, 2, 1)) {
            trial.room.handleStartGame(trial.host);
            trial.host.await("arena_new_question", 0, 2500);
            trial.host.await("arena_main_player_turn", 0, 2000);
            trial.room.handleLifeTrust(trial.host);
            Client reconnected = new Client("owner");
            check(trial.room.handleJoin(reconnected), "active player can reconnect");
            Map<String, Object> state = reconnected.last("arena_new_question");
            check(state.get("phase").equals("MAIN") && Boolean.TRUE.equals(state.get("isMainPlayer")), "reconnect preserves main role and phase");
            check(Boolean.TRUE.equals(state.get("lifeTrustUsed")), "reconnect preserves consumed lifeline");
            check(((Number) state.get("timeLimit")).doubleValue() <= 2, "reconnect receives remaining time rather than a fresh timer");
            trial.room.handleLeave(trial.host);
            check(trial.room.isHost("owner"), "old socket disconnect does not transfer host after replacement");
            trial.room.handleLifeTrust(reconnected);
            check(reconnected.count("arena_life_trust") == 0, "reconnect cannot reuse lifeline");
        }
    }

    private static int correctAnswer(Map<String, Object> packet) {
        return QuestionBank.getArenaQuestions("ALL", "MIX", 100).stream().filter(q -> q.getText().equals(packet.get("text"))).findFirst().orElseThrow().getCorrect();
    }
    private static int number(Map<String, Object> packet, String key) { return ((Number) packet.get(key)).intValue(); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    private static class Trial implements AutoCloseable {
        final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        final Client host = new Client("owner");
        final Client opponent = new Client("opponent");
        final ArenaRoom room;
        Trial(int opponentTime, int mainTime, int bots) {
            room = new ArenaRoom("TRIAL", "Trial", "owner", "SCIENCE", "EASY", 2, opponentTime, bots, null, scheduler);
            room.configureInitialMainPlayer(mainTime, "MANUAL");
            room.handleJoin(host); room.handleJoin(opponent); room.handleToggleReady(opponent);
        }
        public void close() { room.close(); scheduler.shutdownNow(); }
    }
    private record Packet(Map<String, Object> body, long nanoTime) {}
    private static class Client extends WebSocketClient {
        final List<Packet> packets = new CopyOnWriteArrayList<>();
        boolean closed;
        Client(String username) { super(new Socket(), InputStream.nullInputStream(), OutputStream.nullOutputStream(), username); }
        @Override public void send(String text) { if (!closed) packets.add(new Packet(JsonUtil.parseObject(text), System.nanoTime())); }
        @Override public boolean isClosed() { return closed; }
        @Override public void close() { closed = true; }
        int count(String type) { return (int) packets.stream().filter(p -> type.equals(p.body.get("type"))).count(); }
        Map<String, Object> last(String type) { return packets.stream().filter(p -> type.equals(p.body.get("type"))).reduce((a,b) -> b).orElseThrow(() -> new AssertionError("Missing " + type)).body; }
        long time(String type) { return packets.stream().filter(p -> type.equals(p.body.get("type"))).reduce((a,b) -> b).orElseThrow().nanoTime; }
        Map<String, Object> await(String type, int previous, long timeout) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
            while (count(type) <= previous && System.nanoTime() < deadline) Thread.sleep(5);
            if (count(type) <= previous) throw new AssertionError("Timeout waiting for " + type);
            return last(type);
        }
    }
}
