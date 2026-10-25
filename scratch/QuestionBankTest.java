import vn.game.games.ArenaGame;
import vn.game.model.QuestionBank;
import vn.game.server.*;
import vn.game.util.JsonUtil;
import java.io.*;
import java.lang.reflect.*;
import java.net.Socket;
import java.util.*;

/** Run in a temporary directory: this test exercises persistence and admin HTTP permissions. */
public class QuestionBankTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        check(!new File("src").exists(), "Run this test from a temporary directory, not the project root");
        if (args.length > 0) {
            check(QuestionBank.listQuestions().stream().anyMatch(q -> q.get("text").equals("Câu hỏi kiểm thử lưu trữ")), "Persistence reload");
            System.out.println("PASS: question bank reload from disk."); return;
        }
        SessionManager sessions = new SessionManager();
        String admin = sessions.createSession("admin"), player = sessions.createSession("player");
        HttpAndWebSocketServer server = new HttpAndWebSocketServer(0, new File("public"), null, sessions, new ArenaGame(), null, null);
        Method handle = HttpAndWebSocketServer.class.getDeclaredMethod("handleHttpRequest", Socket.class, OutputStream.class, BufferedReader.class, String.class, String.class, Map.class, String.class);
        handle.setAccessible(true);
        java.util.function.BiFunction<String, String, String> get = (method, session) -> {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                handle.invoke(server, new Socket(), out, new BufferedReader(new StringReader("")), method, "/api/questions", Map.of(), session);
                return out.toString(java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) { throw new RuntimeException(e); }
        };
        check(get.apply("GET", null).contains("403"), "Anonymous cannot see correct answers");
        check(get.apply("GET", player).contains("403"), "Players cannot see bank");
        check(get.apply("GET", admin).contains("200"), "Admin can list bank");
        int before = QuestionBank.listQuestions().size();
        Map<String, Object> question = new HashMap<>(Map.of("text", "Câu hỏi kiểm thử lưu trữ", "options", List.of("Một", "Hai", "Ba", "Bốn"), "correct", 1, "topic", "SCIENCE", "difficulty", "HARD"));
        // ASCII JSON transport matches the manager UI's unicode escaping.
        String json = JsonUtil.toJson(question);
        StringBuilder ascii = new StringBuilder();
        for (char c : json.toCharArray()) { if (c > 127) ascii.append(String.format("\\u%04x", (int) c)); else ascii.append(c); }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        handle.invoke(server, new Socket(), out, new BufferedReader(new StringReader(ascii.toString())), "POST", "/api/questions", Map.of("content-length", String.valueOf(ascii.length())), admin);
        check(out.toString().contains("200") && QuestionBank.listQuestions().size() == before + 1, "Admin create: " + out.toString(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, Object> saved = QuestionBank.listQuestions().get(before);
        question.put("id", saved.get("id")); question.put("correct", 2);
        QuestionBank.saveQuestion(question, false);
        check(QuestionBank.listQuestions().size() == before + 1, "Update must not duplicate");
        question.put("correct", 9);
        try { QuestionBank.saveQuestion(question, false); throw new AssertionError("Invalid answer accepted"); }
        catch (IllegalArgumentException expected) {}
        QuestionBank.saveQuestion(Map.of("id", saved.get("id")), true);
        check(QuestionBank.listQuestions().size() == before, "Delete");
        question.remove("id"); question.put("correct", 1); QuestionBank.saveQuestion(question, false);
        check(new File("data/questions.json").exists(), "Persistence file");
        server.stop();
        System.out.println("PASS: admin API permissions, Unicode create, edit, delete, validation and persistence.");
    }
}
