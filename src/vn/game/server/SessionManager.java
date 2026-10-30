package vn.game.server;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {
    // Map lưu sessionId -> username
    private final Map<String, String> sessions = new ConcurrentHashMap<>();
    private final Map<String,String> csrfTokens=new ConcurrentHashMap<>();

    public String createSession(String username) {
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        sessions.put(sessionId, username);
        csrfTokens.put(sessionId,UUID.randomUUID().toString());
        return sessionId;
    }

    public String getUsername(String sessionId) {
        if (sessionId == null) return null;
        return sessions.get(sessionId);
    }

    public void removeSession(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
            csrfTokens.remove(sessionId);
        }
    }

    public void removeUserSessions(String username) {
        sessions.entrySet().removeIf(entry -> {if(entry.getValue().equalsIgnoreCase(username)){csrfTokens.remove(entry.getKey());return true;}return false;});
    }
    public String getCsrfToken(String sessionId){return sessionId!=null&&sessions.containsKey(sessionId)?csrfTokens.get(sessionId):null;}
    public boolean validCsrf(String sessionId,String token){String expected=getCsrfToken(sessionId);return expected!=null&&token!=null&&java.security.MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),token.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
}
