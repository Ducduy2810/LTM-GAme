package vn.game.server;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {
    // Map lưu sessionId -> username
    private final Map<String, String> sessions = new ConcurrentHashMap<>();
    private final Map<String,String> csrfTokens=new ConcurrentHashMap<>();
    private record Ended(Map<String,Object> notice,long expires) {}
    private final Map<String,Ended> ended=new ConcurrentHashMap<>();
    private final Map<String,Map<String,Object>> loginNotices=new ConcurrentHashMap<>();
    public static Map<String,Object> replacementNotice(){return Map.of("kind","SESSION_REPLACED","reason","Tài khoản của bạn vừa được đăng nhập ở nơi khác. Phiên trên thiết bị này đã kết thúc. Nếu không phải bạn, hãy đổi mật khẩu.");}

    public synchronized String createSession(String username) {
        boolean replaced=hasUserSession(username);
        removeUserSessions(username,replacementNotice());
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        sessions.put(sessionId, username);
        csrfTokens.put(sessionId,UUID.randomUUID().toString());
        if(replaced)loginNotices.put(sessionId,Map.of("kind","SESSION_STARTED","reason","Đã đăng nhập tại đây và kết thúc các phiên đăng nhập trước của tài khoản. Trận đấu thường đang chơi ở phiên cũ được xử lý như rời trận."));
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
            loginNotices.remove(sessionId);
        }
    }

    public synchronized void removeUserSessions(String username) {removeUserSessions(username,Map.of());}
    public synchronized void removeUserSessions(String username,Map<String,Object> notice) {
        long now=System.currentTimeMillis();ended.entrySet().removeIf(e->e.getValue().expires()<now);
        sessions.entrySet().removeIf(entry -> {if(entry.getValue().equalsIgnoreCase(username)){String id=entry.getKey();csrfTokens.remove(id);loginNotices.remove(id);if(!notice.isEmpty())ended.put(id,new Ended(Map.copyOf(notice),now+600000));return true;}return false;});
        if(ended.size()>10000)ended.clear();
    }
    public synchronized boolean hasUserSession(String username){return sessions.values().stream().anyMatch(name->name.equalsIgnoreCase(username));}
    public Map<String,Object> endedNotice(String sessionId){Ended value=sessionId==null?null:ended.get(sessionId);return value!=null&&value.expires()>System.currentTimeMillis()?value.notice():Map.of();}
    public Map<String,Object> takeLoginNotice(String sessionId){Map<String,Object> notice=loginNotices.remove(sessionId);return notice==null?Map.of():notice;}
    public java.util.Set<String> usernames(){return new java.util.HashSet<>(sessions.values());}
    public String getCsrfToken(String sessionId){return sessionId!=null&&sessions.containsKey(sessionId)?csrfTokens.get(sessionId):null;}
    public boolean validCsrf(String sessionId,String token){String expected=getCsrfToken(sessionId);return expected!=null&&token!=null&&java.security.MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),token.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
}
