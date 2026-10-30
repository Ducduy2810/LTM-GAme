package vn.game.model;

import java.util.List;

public class Question {
    private int id;
    private String text;
    private List<String> options;
    private int correct; // 0-based index
    private String topic = "ALL"; // NETWORKING, SCIENCE, HISTORY, ALL
    private String difficulty = "MEDIUM"; // EASY, MEDIUM, HARD
    private final String explanation;

    public Question(int id, String text, List<String> options, int correct) {
        this(id, text, options, correct, "ALL", "MEDIUM");
    }

    public Question(int id, String text, List<String> options, int correct, String topic, String difficulty) {
        this(id,text,options,correct,topic,difficulty,"");
    }

    public Question(int id, String text, List<String> options, int correct, String topic, String difficulty, String explanation) {
        this.id = id;
        this.text = text;
        this.options = List.copyOf(options);
        this.correct = correct;
        this.topic = topic != null ? topic.toUpperCase() : "ALL";
        this.difficulty = difficulty != null ? difficulty.toUpperCase() : "MEDIUM";
        this.explanation = explanation == null ? "" : explanation;
    }

    public int getId() { return id; }
    public String getText() { return text; }
    public List<String> getOptions() { return options; }
    public int getCorrect() { return correct; }
    public String getTopic() { return topic; }
    public String getDifficulty() { return difficulty; }
    public String getExplanation() { return explanation; }
    public java.util.Map<String,Object> snapshot() {
        return java.util.Map.of("id",id,"text",text,"options",options,"correct",correct,"topic",topic,"difficulty",difficulty,"explanation",explanation);
    }
}
