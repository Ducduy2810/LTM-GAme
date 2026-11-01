package vn.game.model;

public class WheelPhrase {
    private final String topic;
    private final String hint;
    private final String phrase;
    private final String explanation;

    public WheelPhrase(String topic, String hint, String phrase) {
        this(topic,hint,phrase,"");
    }
    public WheelPhrase(String topic, String hint, String phrase, String explanation) {
        this.topic = topic;
        this.hint = hint;
        this.phrase = phrase.trim().toUpperCase();
        this.explanation = explanation == null ? "" : explanation;
    }

    public String getTopic() {
        return topic;
    }

    public String getHint() {
        return hint;
    }

    public String getPhrase() {
        return phrase;
    }
    public String getExplanation() { return explanation; }
}
