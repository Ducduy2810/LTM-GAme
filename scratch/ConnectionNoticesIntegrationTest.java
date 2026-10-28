import vn.game.db.*;
import vn.game.games.*;
import vn.game.server.*;
import vn.game.util.JsonUtil;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

public class ConnectionNoticesIntegrationTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        if (!Files.exists(Path.of(".presence-test"))) throw new IllegalStateException("Use test_connection_notices.ps1");
        framingAndHeartbeat();
        DatabaseManager db = DatabaseManager.getInstance(); db.init();
        UserRepository users = new UserRepository(db);
        SessionManager sessions = new SessionManager();
        List<Peer> peers = new ArrayList<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ArenaGame arena = new ArenaGame(users); WheelGame wheel = new WheelGame(users); SurvivalGame survival = new SurvivalGame(users)) {
            HttpAndWebSocketServer server = new HttpAndWebSocketServer(0, new File(args[0]), users, sessions, arena, wheel, survival);
            Future<?> running = executor.submit(() -> { try { server.start(); } catch(IOException e) { throw new UncheckedIOException(e); } });
            try {
                long startup = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while(server.getPort()==0 && System.nanoTime()<startup) Thread.sleep(5);
                check(server.getPort()>0,"server started on isolated port");
                Map<String,List<Peer>> rooms = new LinkedHashMap<>();
                Peer outsider = peer(server,users,sessions,peers,"outside",true);
                for (String game : List.of("A","S","W")) {
                    Peer owner = peer(server,users,sessions,peers,"owner_"+game,true);
                    Peer guest = peer(server,users,sessions,peers,"guest_"+game,true);
                    Peer lost = peer(server,users,sessions,peers,"lost_"+game,false);
                    rooms.put(game,new ArrayList<>(List.of(owner,guest,lost)));
                    String create = game.equals("A")?"create_room":game.equals("S")?"create_survival_room":"create_wheel_room";
                    Map<String,Object> configuration = new HashMap<>(Map.of("type",create,"roomCode",game,"roomName","Presence "+game,"topic","ALL",
                            "timeLimit",120,"mainPlayerTimeLimit",120,"botCount",0,"totalRounds",25));
                    owner.send(configuration);
                    owner.await(p->p.getOrDefault("type","").toString().endsWith("room_info"),4000);
                    for(Peer p:List.of(guest,lost)) { p.send(Map.of("type",join(game),"roomCode",game)); p.await(m->m.getOrDefault("type","").toString().endsWith("room_info"),4000); }
                    Peer voluntary = peer(server,users,sessions,peers,"left_"+game,true);
                    voluntary.send(Map.of("type",join(game),"roomCode",game));
                    voluntary.await(p->p.getOrDefault("type","").toString().endsWith("room_info"),4000);
                    voluntary.send(Map.of("type",leave(game)));
                    voluntary.await(p->p.getOrDefault("type","").toString().contains("left_"),4000);
                    voluntary.close();
                    check(owner.count(p->isLost(p,"left_"+game))==0,"explicit leave not reported as lost in "+game);

                    Peer abrupt = peer(server,users,sessions,peers,"abrupt_"+game,true);
                    abrupt.send(Map.of("type",join(game),"roomCode",game));
                    abrupt.await(p->p.getOrDefault("type","").toString().endsWith("room_info"),4000);
                    for(Peer p:List.of(guest,lost,abrupt)) p.send(Map.of("type",ready(game)));
                    owner.await(p->Boolean.TRUE.equals(p.get("allReady")) && p.get("players") instanceof List<?> l && l.size()==4,4000);
                    if(game.equals("A")) {
                        owner.send(Map.of("type","select_arena_main_player","mainSelectionMode","MANUAL","username","guest_A"));
                        owner.await(p->"guest_A".equals(p.get("selectedMainPlayer")),4000);
                    }
                    owner.send(Map.of("type",game.equals("A")?"start_arena_game":game.equals("S")?"start_survival_game":"start_wheel_game"));
                    Map<String,Object> started = owner.await(p->p.getOrDefault("type","").toString().endsWith("game_started"),4000);
                    if(game.equals("A")) {
                        check(started.get("mainPlayer").equals("guest_A"),"main player name broadcast to observer");
                        check(guest.await(p->"arena_game_started".equals(p.get("type")),4000).get("mainPlayer").equals("guest_A"),"main player receives same name");
                        check(owner.await(p->"arena_new_question".equals(p.get("type")),4000).get("mainPlayer").equals("guest_A"),"question carries authoritative main name");
                        Peer replacement = peer(server,users,sessions,peers,"guest_A",true);
                        replacement.send(Map.of("type","join_room","roomCode","A"));
                        check(replacement.await(p->"arena_new_question".equals(p.get("type")),4000).get("mainPlayer").equals("guest_A"),"reconnect restores main name for podium");
                        rooms.get(game).set(1,replacement);
                    }
                    if(game.equals("S")) {
                        owner.await(p->"survival_new_question".equals(p.get("type")),4000);
                        owner.send(Map.of("type","pause_survival_game"));
                        owner.await(p->"survival_game_paused".equals(p.get("type")),4000);
                    }
                    abrupt.close();
                    owner.await(p->isLost(p,"abrupt_"+game),4000);
                    rooms.get(game).get(1).await(p->isLost(p,"abrupt_"+game),4000);
                    check(owner.count(p->isLost(p,"abrupt_"+game))==1,"abrupt close notifies owner once in "+game);
                    check(rooms.get(game).get(1).count(p->isLost(p,"abrupt_"+game))==1,"abrupt close notifies other player in "+game);
                }
                for(var entry:rooms.entrySet()) {
                    String game=entry.getKey(); Peer owner=entry.getValue().get(0), guest=entry.getValue().get(1);
                    owner.await(p->isLost(p,"lost_"+game),35000);
                    guest.await(p->isLost(p,"lost_"+game),4000);
                    check(owner.count(p->isLost(p,"lost_"+game))==1,"heartbeat timeout notifies once in "+game);
                    check(owner.pongsSent>0 && guest.pongsSent>0,"healthy browser-style Pong connections preserved in "+game);
                    check(owner.count(p->"player_disconnected".equals(p.get("type")) && !game.equals(p.get("roomCode")))==0,"notifications do not leak across rooms in "+game);
                    check(owner.count(p->isLost(p,"left_"+game))==0,"later cleanup does not falsely report voluntary leave in "+game);
                }
                check(rooms.get("A").get(0).count(p->isLost(p,"guest_A"))==0,"replacing old socket does not report live main player disconnected");
                check(outsider.count(p->"player_disconnected".equals(p.get("type")))==0,"lobby outsider receives no room disconnects");
                System.out.println("PASS: "+checks+" real WebSocket presence, all-game disconnect and heartbeat checks.");
            } finally { peers.forEach(Peer::close); server.stop(); running.get(5,TimeUnit.SECONDS); }
        } finally { executor.shutdownNow(); db.close(); }
    }

    private static void framingAndHeartbeat() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WebSocketClient c = new WebSocketClient(new Socket(),InputStream.nullInputStream(),out,"unit");
        long now = System.nanoTime()+TimeUnit.SECONDS.toNanos(11);
        byte[] ping=c.prepareHeartbeat(now,10000);
        check(ping!=null,"idle connection requests Ping");
        check(c.prepareHeartbeat(now,10000)==null,"only one Ping outstanding");
        check(!c.heartbeatExpired(now+TimeUnit.SECONDS.toNanos(19),20000),"Pong grace period respected");
        c.receivePong(new byte[]{1});
        check(c.heartbeatExpired(now+TimeUnit.SECONDS.toNanos(21),20000),"wrong Pong cannot fake liveness");
        c.receivePong(ping);
        check(!c.heartbeatExpired(now+TimeUnit.SECONDS.toNanos(40),20000),"matching Pong clears timeout");
        c.sendPong(new byte[]{7,8,9});
        DataInputStream frames=new DataInputStream(new ByteArrayInputStream(out.toByteArray()));
        check(frames.readUnsignedByte()==0x8A && frames.readUnsignedByte()==3 && Arrays.equals(frames.readNBytes(3),new byte[]{7,8,9}),"Pong echoes Ping payload");
        out.reset(); c.send("x".repeat(70000));
        frames=new DataInputStream(new ByteArrayInputStream(out.toByteArray()));
        check(frames.readUnsignedByte()==0x81 && frames.readUnsignedByte()==127 && frames.readLong()==70000,"large JSON frame uses valid 64-bit length");
        c.close();
    }
    private static Peer peer(HttpAndWebSocketServer server,UserRepository users,SessionManager sessions,List<Peer> peers,String name,boolean autoPong) throws Exception {
        if(users.findByUsername(name)==null)users.register(name,name+"@example.com","LocalTestPassword123");
        Peer peer=new Peer(server.getPort(),sessions.createSession(name),autoPong); peers.add(peer); return peer;
    }
    private static String join(String game){return game.equals("A")?"join_room":game.equals("S")?"join_survival_room":"join_wheel_room";}
    private static String leave(String game){return game.equals("A")?"leave_room":game.equals("S")?"leave_survival_room":"leave_wheel_room";}
    private static String ready(String game){return game.equals("A")?"toggle_ready":game.equals("S")?"toggle_survival_ready":"toggle_wheel_ready";}
    private static boolean isLost(Map<String,Object> p,String username){return "player_disconnected".equals(p.get("type"))&&username.equals(p.get("username"));}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}

    private static class Peer implements AutoCloseable {
        final Socket socket; final DataInputStream in; final OutputStream out; final boolean autoPong;
        final List<Map<String,Object>> packets=new CopyOnWriteArrayList<>();
        volatile int pongsSent; volatile Throwable failure;
        Peer(int port,String session,boolean autoPong) throws Exception {
            this.autoPong=autoPong; socket=new Socket("127.0.0.1",port); in=new DataInputStream(socket.getInputStream()); out=socket.getOutputStream();
            out.write(("GET /ws HTTP/1.1\r\nHost: localhost:"+port+"\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nCookie: session_id="+session+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.flush();
            StringBuilder headers=new StringBuilder();int b;while((b=in.read())!=-1){headers.append((char)b);if(headers.toString().endsWith("\r\n\r\n"))break;}
            if(!headers.toString().startsWith("HTTP/1.1 101"))throw new IOException("Upgrade failed");
            Thread reader=new Thread(()->{
                try {
                    while(!socket.isClosed()){
                        int opcode=in.readUnsignedByte()&15;int length=in.readUnsignedByte()&127;
                        long size=length==126?in.readUnsignedShort():length==127?in.readLong():length;
                        if(size<0||size>1024*1024)throw new IOException("Bad server frame");
                        byte[] bytes=in.readNBytes((int)size);if(bytes.length!=size)throw new EOFException();
                        if(opcode==1)packets.add(JsonUtil.parseObject(new String(bytes,StandardCharsets.UTF_8)));
                        else if(opcode==9&&autoPong){frame(10,bytes);pongsSent++;}
                        else if(opcode==8)break;
                    }
                }catch(Exception e){if(!socket.isClosed()&&!(e instanceof EOFException))failure=e;}
            });reader.setDaemon(true);reader.start();
        }
        void send(Map<String,Object> packet) throws IOException{frame(1,JsonUtil.toJson(packet).getBytes(StandardCharsets.UTF_8));}
        synchronized void frame(int opcode,byte[] data) throws IOException{
            out.write(128|opcode);int n=data.length;if(n<126)out.write(128|n);else{out.write(128|126);out.write(n>>8);out.write(n);}
            byte[] mask={1,2,3,4};out.write(mask);for(int i=0;i<n;i++)out.write(data[i]^mask[i%4]);out.flush();
        }
        long count(Predicate<Map<String,Object>> filter){return packets.stream().filter(filter).count();}
        Map<String,Object> await(Predicate<Map<String,Object>> filter,long timeout) throws Exception{
            long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);
            while(System.nanoTime()<deadline){Optional<Map<String,Object>> found=packets.stream().filter(filter).findFirst();if(found.isPresent())return found.get();if(failure!=null)throw new AssertionError("Socket reader failed",failure);Thread.sleep(5);}
            throw new AssertionError("Timed out waiting for packet; types="+packets.stream().map(p->p.get("type")).toList());
        }
        public void close(){try{socket.close();}catch(IOException ignored){}}
    }
}
