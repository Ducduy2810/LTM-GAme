import vn.game.games.SurvivalGame;
import vn.game.db.DatabaseManager;
import vn.game.db.UserRepository;
import vn.game.model.User;
import vn.game.games.SurvivalRoom;
import vn.game.model.Question;
import vn.game.model.QuestionBank;
import vn.game.server.WebSocketClient;
import vn.game.util.JsonUtil;
import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class SurvivalRulesIntegrationTest {
    private static int checks;
    private static UserRepository repository;
    private static final Client ADMIN=new Client("operator");
    public static void main(String[] args) throws Exception {
        if (!Files.exists(Path.of(".survival-test"))) throw new IllegalStateException("Use test_survival_rules.ps1");
        DatabaseManager.getInstance().init();
        repository=new UserRepository(DatabaseManager.getInstance());
        User operator=repository.register("operator",null,"TestOnlyPass123");operator.setRole("admin");repository.update(operator);
        try {
        earlyResultsAndFeedback();
        timeoutAndEliminatedPlayers();
        pauseAndResume();
        pauseStartingAndResult();
        reconnectAndDeadline();
        deleteRooms();
        System.out.println("PASS: " + checks + " Survival rules integration checks.");
        } finally { DatabaseManager.getInstance().close(); }
    }

    private static void earlyResultsAndFeedback() throws Exception {
        try (Trial t = new Trial(2, 100, 20)) {
            Map<String,Object> q = t.start(); int correct = correct(q), wrong = (correct + 1) % 4;
            t.room.handleSubmitAnswer(t.owner, 8);
            check(t.owner.count("survival_answer_locked") == 0, "invalid index does not consume answer");
            t.room.handleSubmitAnswer(t.owner, correct);
            check(t.owner.count("survival_round_result") == 0, "wait for last living participant");
            long before = System.nanoTime();
            t.room.handleSubmitAnswer(t.clients.get(1), wrong);
            Map<String,Object> result = t.owner.last("survival_round_result");
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 500, "last answer reveals result immediately instead of waiting twenty seconds");
            check(result.get("correctAnswerText").equals(((List<?>)q.get("options")).get(correct)), "server returns full correct answer text");
            Map<?,?> mine = personal(result);
            check(mine.get("status").equals("CORRECT") && Boolean.TRUE.equals(mine.get("isCorrect")), "correct player receives correct personal status");
            check(num(mine,"answer") == correct && num(mine,"hpLost") == 0, "correct choice and no HP loss reported");
            Map<?,?> guest = personal(t.clients.get(1).last("survival_round_result"));
            check(guest.get("status").equals("WRONG") && Boolean.FALSE.equals(guest.get("isCorrect")), "wrong player receives wrong personal status");
            check(num(guest,"answer") == wrong && num(guest,"hpLost") == 5, "wrong choice and actual penalty reported");
            check(guest.get("answerText").equals(((List<?>)q.get("options")).get(wrong)), "selected answer text matches player's own choice");
            t.room.handleSubmitAnswer(t.clients.get(1), correct);
            check(t.owner.count("survival_round_result") == 1, "duplicate submission cannot score round twice");
        }
        try (Trial t = new Trial(2, 100, 20)) {
            int correct = correct(t.start());
            CompletableFuture<Void> a = CompletableFuture.runAsync(() -> t.room.handleSubmitAnswer(t.owner, correct));
            CompletableFuture<Void> b = CompletableFuture.runAsync(() -> t.room.handleSubmitAnswer(t.clients.get(1), correct));
            CompletableFuture.allOf(a,b).get();
            check(t.owner.count("survival_round_result") == 1, "simultaneous final submissions produce one result");
            for (Object state : (List<?>)t.owner.last("survival_round_result").get("playerStates")) {
                check(num((Map<?,?>)state,"score") == 10 && num((Map<?,?>)state,"hp") == 100, "concurrent scoring awards ten points once");
            }
        }
    }

    private static void timeoutAndEliminatedPlayers() throws Exception {
        try (Trial t = new Trial(4, 3, 1)) {
            int correct = correct(t.start());
            t.room.handleSubmitAnswer(t.owner, correct);
            t.room.handleSubmitAnswer(t.clients.get(1), correct);
            t.room.handleSubmitAnswer(t.clients.get(2), (correct + 1) % 4);
            Map<String,Object> result = t.owner.await("survival_round_result", 0, 2000);
            check(personal(t.clients.get(3).last("survival_round_result")).get("status").equals("UNANSWERED"), "timeout distinguishes no answer from wrong choice");
            check(num(personal(t.clients.get(2).last("survival_round_result")),"hpLost") == 3, "reported HP loss is capped by remaining HP");
            check(num(result,"survivorsCount") == 2, "wrong and unanswered players eliminated at zero HP");
            Map<String,Object> next = t.owner.await("survival_new_question", 1, 6000);
            correct = correct(next);
            t.room.handleSubmitAnswer(t.owner, correct);
            check(t.owner.count("survival_round_result") == 1, "second round still waits for remaining participant");
            t.room.handleSubmitAnswer(t.clients.get(1), correct);
            check(t.owner.count("survival_round_result") == 2, "eliminated players do not block early reveal");
            Map<?,?> spectator = personal(t.clients.get(2).last("survival_round_result"));
            check(spectator.get("status").equals("SPECTATING") && Boolean.FALSE.equals(spectator.get("participated")), "eliminated viewer is not labelled wrong in later rounds");
        }
    }

    private static void pauseAndResume() throws Exception {
        try (Trial t = new Trial(2, 100, 1)) {
            t.start();
            t.room.handlePauseGame(t.clients.get(1));
            check(t.clients.get(1).count("survival_error") == 1, "guest cannot pause");
            t.room.handlePauseGame(ADMIN);
            Map<String,Object> paused = t.owner.last("survival_game_paused");
            double remaining = ((Number)paused.get("remainingTime")).doubleValue();
            check(remaining > 0 && remaining <= 1.001, "pause saves exact remaining question time");
            Thread.sleep(1100);
            check(t.owner.count("survival_round_result") == 0, "paused question does not time out");
            t.room.handleSubmitAnswer(t.owner, 0);
            t.room.handleSubmitAnswer(t.clients.get(1), 0);
            check(t.owner.count("survival_answer_locked") == 0 && t.clients.get(1).count("survival_answer_locked") == 0, "pause blocks all submissions");
            t.room.handleResumeGame(t.clients.get(1));
            check(t.clients.get(1).count("survival_error") == 2, "guest cannot resume");
            t.room.handleResumeGame(ADMIN);
            check(((Number)t.owner.last("survival_game_resumed").get("remainingTime")).doubleValue() == remaining, "resume uses saved remaining time");
            Map<String,Object> result = t.owner.await("survival_round_result",0,1800);
            check(personal(result).get("status").equals("UNANSWERED"), "resumed timeout scores no answer accurately");
        }
    }

    private static void pauseStartingAndResult() throws Exception {
        try (Trial t = new Trial(2,100,20)) {
            t.room.handleStartGame(t.owner);
            t.room.handlePauseGame(ADMIN);
            Thread.sleep(1600);
            check(t.owner.count("survival_new_question") == 0, "pause freezes starting countdown");
            t.room.handleResumeGame(ADMIN);
            int correct = correct(t.owner.await("survival_new_question",0,2200));
            t.room.handleSubmitAnswer(t.owner,correct); t.room.handleSubmitAnswer(t.clients.get(1),correct);
            t.room.handlePauseGame(ADMIN);
            check(t.owner.last("survival_game_paused").get("gameState").equals("RESULT"), "result interval can also be paused");
            Thread.sleep(5100);
            check(t.owner.count("survival_new_question") == 1, "paused result does not advance to next question");
            t.room.handleResumeGame(ADMIN);
            check(((Number)t.owner.last("survival_game_resumed").get("remainingTime")).doubleValue() > 4.5, "result countdown resumes with preserved time");
        }
    }

    private static void reconnectAndDeadline() throws Exception {
        try (Trial t = new Trial(2,100,20)) {
            int correct = correct(t.start());
            t.room.handleSubmitAnswer(t.owner,correct);
            t.room.handlePauseGame(ADMIN);
            Client replacement = new Client("owner");
            check(t.room.handleJoin(replacement), "existing player may reconnect");
            Map<String,Object> q = replacement.last("survival_new_question");
            check(Boolean.TRUE.equals(q.get("isPaused")) && Boolean.TRUE.equals(q.get("hasAnswered")), "reconnect preserves pause and answer lock");
            check(num(q,"selectedAnswer") == correct, "reconnect preserves selected answer");
            t.room.handleLeave(t.owner);
            check(t.room.isHost("owner"), "old connection cannot transfer host after replacement");
            t.room.handleResumeGame(ADMIN);
            t.room.handleSubmitAnswer(t.clients.get(1),correct);
            check(replacement.count("survival_round_result") == 1, "replacement connection participates in all-answered logic");
        }
        try (Trial t = new Trial(2,100,1)) {
            t.start();
            t.scheduler.execute(() -> sleep(1500));
            Thread.sleep(1100);
            t.room.handleSubmitAnswer(t.owner,0);
            check(t.owner.count("survival_answer_locked") == 0, "late answer rejected even with delayed scheduler callback");
            t.owner.await("survival_round_result",0,2000);
            check(t.owner.count("survival_round_result") == 1, "timeout produces one result");
        }
        try (Trial t = new Trial(2,100,1)) {
            t.start(); t.room.close(); Thread.sleep(1100);
            check(t.owner.count("survival_round_result") == 0, "closed room cancels result timer");
        }
    }

    private static void deleteRooms() throws Exception {
        UserRepository users=new UserRepository(DatabaseManager.getInstance());
        User adminUser=users.register("admin","admin@example.com","TestOnlyPass123");
        adminUser.setRole("admin");users.update(adminUser);
        try (SurvivalGame game = new SurvivalGame(users)) {
            Client owner = new Client("owner"), guest = new Client("guest"), outsider = new Client("outsider");
            game.handleCreateRoom(owner,Map.of("roomCode","RULES","roomName","Rules","topic","ALL","startingHp",100,"totalRounds",5));
            game.handleJoinRoom(guest,"RULES");
            game.handleAdminCloseRoom(outsider,"RULES");
            check(outsider.count("survival_error") == 1, "outsider cannot delete room");
            game.handleToggleReady(guest); game.handleStartGame(owner);
            game.handlePauseGame(owner);
            check(owner.count("survival_game_paused") == 0, "owner pause request denied");
            game.handleResumeGame(owner);
            check(owner.count("survival_game_resumed") == 0, "owner resume request denied");
            game.handleAdminCloseRoom(new Client("admin"),"RULES");
            check(owner.count("survival_room_closed") == 1 && guest.count("survival_room_closed") == 1, "admin cancellation notifies all members");
            check(game.getRoomsSummaryList().stream().noneMatch(r -> r.get("roomCode").equals("RULES")), "deleted room disappears from lobby");
            Thread.sleep(1600);
            check(owner.count("survival_new_question") == 0, "deleting starting room cancels countdown");
            Client admin = new Client("admin");
            game.handleAdminCloseRoom(admin,"DTST");
            check(game.getRoomsSummaryList().stream().anyMatch(r->r.get("roomCode").equals("DTST")), "system default room recreated after cancellation");
            game.handleCreateRoom(owner,Map.of("roomCode","ADMIN","topic","ALL"));
            game.handleAdminCloseRoom(admin,"ADMIN");
            check(owner.count("survival_room_closed") == 2, "admin can delete custom room");
        }
    }

    private static int correct(Map<String,Object> q) { return QuestionBank.getQuestions("ALL","MIX",100).stream().filter(x -> x.getText().equals(q.get("text"))).findFirst().orElseThrow().getCorrect(); }
    private static Map<?,?> personal(Map<String,Object> result) { return (Map<?,?>)result.get("myAnswerResult"); }
    private static int num(Map<?,?> data,String key) { return ((Number)data.get(key)).intValue(); }
    private static void check(boolean value,String message) { if (!value) throw new AssertionError(message); checks++; }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    private static class Trial implements AutoCloseable {
        final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        final List<Client> clients = new ArrayList<>(); final Client owner; final SurvivalRoom room;
        Trial(int count,int hp,int seconds) {
            room = new SurvivalRoom("TRIAL","Trial","owner","ALL",hp,5,repository,scheduler) {
                @Override public PhaseConfig getPhaseConfig(int round) { return new PhaseConfig("EASY",5,seconds,"EASY"); }
            };
            for (int i=0;i<count;i++) { Client c=new Client(i==0?"owner":"guest"+i); clients.add(c); room.handleJoin(c); if(i>0)room.handleToggleReady(c); }
            owner=clients.get(0);
        }
        Map<String,Object> start() throws Exception { room.handleStartGame(owner); return owner.await("survival_new_question",0,2500); }
        public void close() { room.close(); scheduler.shutdownNow(); }
    }
    private static class Client extends WebSocketClient {
        final List<Map<String,Object>> packets = new CopyOnWriteArrayList<>(); boolean closed;
        Client(String username) { super(new Socket(),InputStream.nullInputStream(),OutputStream.nullOutputStream(),username); }
        @Override public void send(String text) { if(!closed) packets.add(JsonUtil.parseObject(text)); }
        @Override public boolean isClosed() { return closed; }
        @Override public void close() { closed=true; }
        int count(String type) { return (int)packets.stream().filter(p->type.equals(p.get("type"))).count(); }
        Map<String,Object> last(String type) { return packets.stream().filter(p->type.equals(p.get("type"))).reduce((a,b)->b).orElseThrow(()->new AssertionError("Missing "+type)); }
        Map<String,Object> await(String type,int previous,long ms) throws Exception {
            long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(ms);
            while(count(type)<=previous && System.nanoTime()<end)Thread.sleep(5);
            if(count(type)<=previous)throw new AssertionError("Timeout "+type); return last(type);
        }
    }
}
