package vn.game.server;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import vn.game.model.ServerAnnouncement;
import vn.game.util.JsonUtil;

/** Server-Sent Events for every page, including clients currently outside a game room. */
public final class ClientEvents implements AutoCloseable {
    private static final class Subscriber {
        final Socket socket;final OutputStream out;final String username,sessionId;final Predicate<String> validSession;
        Subscriber(Socket socket,OutputStream out,String username,Predicate<String> validSession,String sessionId){this.socket=socket;this.out=out;this.username=username;this.validSession=validSession;this.sessionId=sessionId;}
        synchronized void send(String event,Map<String,Object> value)throws IOException{out.write(("event: "+event+"\ndata: "+JsonUtil.toJson(value)+"\n\n").getBytes(StandardCharsets.UTF_8));out.flush();}
        synchronized void ping()throws IOException{out.write(": keepalive\n\n".getBytes(StandardCharsets.US_ASCII));out.flush();}
        void close(){try{socket.close();}catch(IOException ignored){}}
    }
    private final Set<Subscriber> subscribers=ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor();
    private volatile boolean closed;private volatile String lastAnnouncement="";
    public void start(){scheduler.scheduleAtFixedRate(()->{try{Map<String,Object> a=ServerAnnouncement.publicView();String version=JsonUtil.toJson(a);if(!version.equals(lastAnnouncement))broadcast(a);}catch(Exception ignored){}},5,5,TimeUnit.SECONDS);}
    public void stream(Socket socket,OutputStream out,String username,Predicate<String> validSession,String sessionId)throws IOException{
        Subscriber s=new Subscriber(socket,out,username,validSession,sessionId);
        try{
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nCache-Control: no-store\r\nX-Accel-Buffering: no\r\nConnection: keep-alive\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.flush();subscribers.add(s);
            s.send("announcement",ServerAnnouncement.publicView());s.send("maintenance",vn.game.db.AdminStore.maintenanceStatus());long nextPing=System.currentTimeMillis()+12000;
            while(!closed&&!socket.isClosed()){
                if(username!=null&&!validSession.test(sessionId)){s.send("session_ended",Map.of("message","Phiên đăng nhập đã kết thúc."));break;}
                if(System.currentTimeMillis()>=nextPing){s.ping();nextPing=System.currentTimeMillis()+12000;}
                try{Thread.sleep(500);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
            }
        }catch(java.sql.SQLException e){throw new IOException("Cannot load announcements",e);}
        finally{subscribers.remove(s);s.close();}
    }
    /** Authenticated visitors anywhere on the portal; multiple tabs count once. */
    public Set<String> onlineUsernames(){
        Set<String> names=new HashSet<>();
        for(Subscriber s:subscribers)if(s.username!=null&&!s.socket.isClosed()&&s.validSession.test(s.sessionId))names.add(s.username.toLowerCase(Locale.ROOT));
        return names;
    }
    public void broadcast(Map<String,Object> value){lastAnnouncement=JsonUtil.toJson(value);for(Subscriber s:subscribers)try{s.send("announcement",value);}catch(IOException e){subscribers.remove(s);s.close();}}
    public void notifyUser(String username,Map<String,Object> value){for(Subscriber s:subscribers)if(username.equalsIgnoreCase(s.username))try{s.send("account_notice",value);}catch(IOException e){subscribers.remove(s);s.close();}}
    public void maintenance(Map<String,Object> value){for(Subscriber s:subscribers)try{s.send("maintenance",value);}catch(IOException e){subscribers.remove(s);s.close();}}
    public void close(){closed=true;scheduler.shutdownNow();subscribers.forEach(Subscriber::close);subscribers.clear();}
}
