package vn.game.server;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {
    // Map lưu sessionId -> username
    private final Map<String, String> sessions = new ConcurrentHashMap<>();

    public String createSession(String username) {
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        sessions.put(sessionId, username);
        return sessionId;
    }

    public String getUsername(String sessionId) {
        if (sessionId == null) return null;
        return sessions.get(sessionId);
    }

    public void removeSession(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    public void removeUserSessions(String username) {
        sessions.entrySet().removeIf(entry -> entry.getValue().equalsIgnoreCase(username));
    }
}
