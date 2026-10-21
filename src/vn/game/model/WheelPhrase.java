package vn.game.model;

public class WheelPhrase {
    private final String topic;
    private final String hint;
    private final String phrase;

    public WheelPhrase(String topic, String hint, String phrase) {
        this.topic = topic;
        this.hint = hint;
        this.phrase = phrase.trim().toUpperCase();
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
}
