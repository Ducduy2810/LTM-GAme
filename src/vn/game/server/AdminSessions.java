package vn.game.server;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Dedicated admin sessions: 30 minute idle timeout and 8 hour absolute lifetime. */
public final class AdminSessions {
    public static final long IDLE_MS=30*60_000L,MAX_MS=8*60*60_000L;
    public static final class Session {
        public final String username,csrf,credentialVersion;
        public final long created;
        private volatile long lastSeen;
        Session(String username,long now,String credentialVersion){this.username=username;this.credentialVersion=credentialVersion;csrf=UUID.randomUUID().toString();created=lastSeen=now;}
    }
    private final Map<String,Session> sessions=new ConcurrentHashMap<>();
    public String create(String username){return create(username,"");}
    public String create(String username,String credentialVersion){long now=System.currentTimeMillis();sessions.entrySet().removeIf(e->expired(e.getValue(),now));String id=UUID.randomUUID().toString()+UUID.randomUUID();sessions.put(id,new Session(username,now,credentialVersion));return id;}
    private boolean expired(Session s,long now){return now-s.lastSeen>IDLE_MS||now-s.created>MAX_MS;}
    public Session get(String id){return get(id,true);}
    public Session get(String id,boolean touch){if(id==null)return null;Session s=sessions.get(id);if(s==null)return null;long now=System.currentTimeMillis();if(expired(s,now)){sessions.remove(id,s);return null;}if(touch)s.lastSeen=now;return s;}
    public boolean csrf(Session s,String token){return token!=null&&MessageDigest.isEqual(s.csrf.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8));}
    public void remove(String id){if(id!=null)sessions.remove(id);}
    public void removeUser(String username){sessions.entrySet().removeIf(e->e.getValue().username.equalsIgnoreCase(username));}
    public static String cookieValue(String header,String name){if(header!=null)for(String item:header.split(";")){String[] p=item.trim().split("=",2);if(p.length==2&&name.equals(p[0]))return p[1];}return null;}
}
