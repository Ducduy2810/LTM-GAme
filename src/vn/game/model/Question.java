package vn.game.model;

import java.util.List;

public class Question {
    private int id;
    private String text;
    private List<String> options;
    private int correct; // 0-based index
    private String topic = "ALL"; // NETWORKING, SCIENCE, HISTORY, ALL
    private String difficulty = "MEDIUM"; // EASY, MEDIUM, HARD

    public Question(int id, String text, List<String> options, int correct) {
        this(id, text, options, correct, "ALL", "MEDIUM");
    }

    public Question(int id, String text, List<String> options, int correct, String topic, String difficulty) {
        this.id = id;
        this.text = text;
        this.options = options;
        this.correct = correct;
        this.topic = topic != null ? topic.toUpperCase() : "ALL";
        this.difficulty = difficulty != null ? difficulty.toUpperCase() : "MEDIUM";
    }

    public int getId() { return id; }
    public String getText() { return text; }
    public List<String> getOptions() { return options; }
    public int getCorrect() { return correct; }
    public String getTopic() { return topic; }
    public String getDifficulty() { return difficulty; }
}
