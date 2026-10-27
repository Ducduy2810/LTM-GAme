import vn.game.games.ArenaRoom;
import vn.game.games.SurvivalRoom;
import vn.game.model.*;
import vn.game.server.WebSocketClient;
import java.io.*;
import java.net.Socket;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Offline regression tests. No network, user accounts or production database writes. */
public class GameRulesTest {
    static class Client extends WebSocketClient {
        final List<String> messages = new ArrayList<>();
        Client(String name) { super(new Socket(), InputStream.nullInputStream(), OutputStream.nullOutputStream(), name); }
        @Override public void send(String text) { messages.add(text); }
    }
    // Capture timers without sleeping; each game transition is invoked explicitly.
    static class Clock extends ScheduledThreadPoolExecutor {
        Clock() { super(1); }
        long delayMs;
        @Override public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
            delayMs = unit.toMillis(delay);
            return super.schedule(() -> {}, 1, TimeUnit.DAYS);
        }
    }
    static Object get(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o);
    }
    static void set(Object o, String name, Object value) throws Exception {
        Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); f.set(o, value);
    }
    static void call(Object o, String name) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(o);
    }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        set(vn.game.db.DatabaseManager.getInstance(), "connection", java.sql.DriverManager.getConnection("jdbc:sqlite::memory:"));
        call(vn.game.db.DatabaseManager.getInstance(), "createTables");
        for (String topic : List.of("ALL", "MIX", "NETWORKING", "SCIENCE", "HISTORY")) {
            for (String level : List.of("EASY", "MEDIUM", "HARD")) {
                List<Question> qs = QuestionBank.getQuestions(topic, level, 20);
                check(qs.size() == 20, "Question count");
                for (Question q : qs) {
                    check(q.getDifficulty().equals(level), "Wrong difficulty fallback");
                    if (!topic.equals("ALL") && !topic.equals("MIX")) check(q.getTopic().equals(topic), "Wrong topic fallback");
                }
            }
            List<Question> qs = QuestionBank.getQuestions(topic, "MIX", 15);
            for (int i = 0; i < 15; i++) check(qs.get(i).getDifficulty().equals(i < 5 ? "EASY" : i < 10 ? "MEDIUM" : "HARD"), "Progression");
        }
        check(Leaderboard.calcMilestonePoints(4, 100) == 0, "Before first milestone");
        check(Leaderboard.calcMilestonePoints(5, 100) == 500, "First milestone");
        check(Leaderboard.calcMilestonePoints(13, 100) == 1250, "13 eliminated must stop at milestone 10");
        Clock clock = new Clock();
        try {
            Client host = new Client("host"), opponent = new Client("opponent");
            ArenaRoom room = new ArenaRoom("TEST", "Test", "host", "MIX", "MIX", 15, 5, 100, null, clock);
            room.setMaxPlayers(2);
            room.handleJoin(host); room.handleJoin(opponent); room.handleToggleReady(opponent);
            check(!room.canJoin("third"), "Room capacity");
            room.handleStartGame(host);
            List<ArenaRoom.BotOpponent> bots = (List<ArenaRoom.BotOpponent>) get(room, "bots");
            check(bots.size() == 99, "100 opponents cap must include real players");
            call(room, "sendQuestion");
            Map<WebSocketClient, ArenaRoom.PlayerState> players = (Map<WebSocketClient, ArenaRoom.PlayerState>) get(room, "players");
            Question q = ((List<Question>) get(room, "questions")).get(0);
            room.handleSubmitAnswer(host, q.getCorrect());
            check(players.get(host).answer == null, "Main cannot answer before opponents");
            room.handlePauseGame(host);
            room.handleSubmitAnswer(opponent, q.getCorrect());
            check(players.get(opponent).answer == null, "Paused answers blocked");
            room.handleResumeGame(host);
            check(clock.delayMs <= 5000 && clock.delayMs > 0, "Opponent timer preserved");
            room.handleSubmitAnswer(opponent, q.getCorrect());
            call(room, "beginMainTurn");
            check(clock.delayMs == 60000, "Main has full 60 seconds after opponent phase");
            room.handleSubmitAnswer(opponent, (q.getCorrect() + 1) % 4);
            check(players.get(opponent).answer == q.getCorrect(), "Opponent answer stays locked");
            room.handleLifeSurvey(host);
            check(players.get(host).lifeSurveyUsed, "Lifeline used");
            room.handleDisconnect(host);
            check((boolean) get(room, "isPaused"), "Disconnect pauses game");
            check(room.isHost("host"), "Disconnect must preserve host authority");
            Client rejoined = new Client("host"); room.handleJoin(rejoined);
            check(players.get(rejoined).isMainPlayer && players.get(rejoined).lifeSurveyUsed, "Reconnect preserves role and lifeline");
            check(rejoined.messages.stream().anyMatch(m -> m.contains("room_info") && m.contains("\"isHost\":true") && m.contains("\"paused\":true")), "Returning host gets pause authority in room snapshot");
            check(rejoined.messages.stream().anyMatch(m -> m.contains("arena_new_question") && m.contains("\"questionId\":1") && m.contains("\"paused\":true")), "First-question reconnect snapshot");
            room.handleResumeGame(opponent);
            check((boolean) get(room, "isPaused"), "Non-host cannot resume after host reconnects");
            room.handleResumeGame(rejoined);
            check(!(boolean) get(room, "isPaused"), "Returning host can resume the match");
            check(clock.delayMs > 59000 && clock.delayMs <= 60000, "Main timer preserved");
            for (int i = 0; i < bots.size(); i++) bots.get(i).answer = i < 13 ? (q.getCorrect() + 1) % 4 : q.getCorrect();
            room.handleSubmitAnswer(rejoined, q.getCorrect());
            check(players.get(rejoined).score == 1250, "Round scoring at 13 eliminated");
            check(opponent.messages.stream().anyMatch(m -> m.contains("arena_round_result") && m.contains("\"eliminated\":5") && m.contains("\"eliminated\":10")), "Milestone events");
            set(room, "gameState", "END"); room.handleStartGame(rejoined);
            check(!players.get(rejoined).lifeSurveyUsed, "New match resets lifelines");
            ArenaRoom solo = new ArenaRoom("SOLO", "Solo", "solo", "ALL", "EASY", 5, 5, 100, null, clock);
            Client soloHost = new Client("solo"); solo.handleJoin(soloHost); solo.handleStartGame(soloHost);
            call(solo, "sendQuestion"); call(solo, "beginMainTurn");
            solo.handleDisconnect(soloHost);
            Client soloReturn = new Client("solo"); solo.handleJoin(soloReturn); solo.handleResumeGame(soloReturn);
            check(!(boolean) get(solo, "isPaused") && clock.delayMs > 59000, "Only human player can reconnect and resume a bot match");
            SurvivalRoom survival = new SurvivalRoom("SURV", "Survival", "host", "HISTORY", 100, 25, null, clock);
            Client s1 = new Client("host"), s2 = new Client("rival");
            survival.handleJoin(s1); survival.handleJoin(s2);
            call(survival, "startGame");
            List<Question> survivalQuestions = (List<Question>) get(survival, "questions");
            for (int i = 0; i < 25; i++) check(survivalQuestions.get(i).getDifficulty().equals(i < 5 ? "EASY" : i < 10 ? "MEDIUM" : "HARD"), "Survival question phase difficulty");
            set(survival, "currentRound", 5); call(survival, "sendQuestion");
            Question medium = survivalQuestions.get(5);
            survival.handleSubmitAnswer(s1, -2);
            survival.handleSubmitAnswer(s1, (medium.getCorrect() + 1) % 4);
            survival.handleSubmitAnswer(s2, medium.getCorrect());
            call(survival, "processResults");
            check(s1.messages.stream().anyMatch(m -> m.contains("survival_round_result") && m.contains("answerText") && m.contains("correctAnswerText") && m.contains("\"isCorrect\":false") && m.contains("\"isCorrect\":true")), "Personal survival answer feedback");
            System.out.println("PASS: question filters/progression, capacity/bots, sequential turns, pause/resume, reconnect, lifeline reset, 13-player scoring.");
            System.out.println("PASS: Survival EASY -> MEDIUM -> HARD questions and personal correct/wrong result data.");
        } finally { clock.shutdownNow(); vn.game.db.DatabaseManager.getInstance().close(); }
    }
}
