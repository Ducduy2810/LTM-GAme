package vn.game.model;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** One outstanding question per account. Solutions only leave the server after submission. */
public final class PracticeService {
    private record Pending(String id, Question question, long expires) {}
    private final Map<String,Pending> pending = new ConcurrentHashMap<>();

    public synchronized Map<String,Object> next(String username, String topic, String difficulty) {
        pending.entrySet().removeIf(e -> e.getValue().expires() < System.currentTimeMillis());
        List<Question> bank = QuestionBank.getArenaQuestions(topic,difficulty,1000);
        if (bank.isEmpty()) throw new IllegalArgumentException("Chưa có câu hỏi phù hợp.");
        Question q=bank.get(new Random().nextInt(bank.size()));
        Pending p=new Pending(UUID.randomUUID().toString(),q,System.currentTimeMillis()+600_000);
        pending.put(username,p);
        return Map.of("questionId",p.id(),"text",q.getText(),"options",q.getOptions(),"topic",q.getTopic(),"difficulty",q.getDifficulty());
    }

    public synchronized Map<String,Object> answer(String username,String id,int index) {
        Pending p=pending.get(username);
        if (p==null || !p.id().equals(id) || p.expires()<System.currentTimeMillis())
            throw new IllegalArgumentException("Câu hỏi đã hết hạn hoặc đã trả lời. Hãy lấy câu mới.");
        if (index<0 || index>=p.question().getOptions().size()) throw new IllegalArgumentException("Đáp án không hợp lệ.");
        pending.remove(username);
        Question q=p.question();
        return Map.of("correct",index==q.getCorrect(),"correctIndex",q.getCorrect(),"correctText",q.getOptions().get(q.getCorrect()),"explanation",q.getExplanation());
    }
}
