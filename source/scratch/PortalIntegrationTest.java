import vn.game.db.*;
import vn.game.games.*;
import vn.game.model.*;
import vn.game.server.*;
import vn.game.util.JsonUtil;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.*;

public class PortalIntegrationTest {
    static int checks;
    static HttpClient http=HttpClient.newHttpClient();
    static String base;
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    static HttpResponse<String> request(String path,String cookie,String body) throws Exception {
        var b=HttpRequest.newBuilder(URI.create(base+path));
        if(cookie!=null)b.header("Cookie",cookie);
        if(body!=null)b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    static void invoke(Object room,String method,Class<?>[] types,Object...args) throws Exception {
        Method m=room.getClass().getDeclaredMethod(method,types);m.setAccessible(true);m.invoke(room,args);
    }
    static class Client extends WebSocketClient {
        List<Map<String,Object>> packets=new CopyOnWriteArrayList<>();
        Client(String name){super(new Socket(),InputStream.nullInputStream(),OutputStream.nullOutputStream(),name);}
        @Override public void send(String text){packets.add(JsonUtil.parseObject(text));}
        @Override public boolean isClosed(){return false;}
        Map<String,Object> last(String type){return packets.stream().filter(p->type.equals(p.get("type"))).reduce((a,b)->b).orElseThrow();}
    }
    public static void main(String[] args) throws Exception {
        if(!Files.exists(Path.of(".portal-test")))throw new IllegalStateException("Run test_portal.ps1 in isolated directory");
        Class.forName("org.sqlite.JDBC");Files.createDirectories(Path.of("data"));
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:data/game.db");Statement st=c.createStatement()){
            st.execute("CREATE TABLE IF NOT EXISTS users(id INTEGER PRIMARY KEY AUTOINCREMENT,username TEXT NOT NULL UNIQUE COLLATE NOCASE,email TEXT UNIQUE,password_hash TEXT,salt TEXT,google_id TEXT UNIQUE,reset_token TEXT,reset_expires INTEGER NOT NULL DEFAULT 0,role TEXT NOT NULL DEFAULT 'user')");
            st.execute("INSERT OR IGNORE INTO users(username) VALUES('legacy')");
        }
        DatabaseManager db=DatabaseManager.getInstance();db.init();
        UserRepository repo=new UserRepository(db);
        check(repo.findByUsername("legacy")!=null,"migration preserves accounts");
        try(var backups=Files.list(Path.of("data/backups"))){check(backups.anyMatch(p->p.toString().endsWith(".db")),"migration takes consistent backup");}
        for(String name:List.of("admin","DuyNguyen","learner","other")){
            if(repo.findByUsername(name)==null)repo.register(name,name+"@example.com","TestOnlyPass123");
            check(!repo.isAdmin(name),"reserved username must not grant admin: "+name);
        }
        check(!new User(1,"admin","mainung28@gmail.com","pass").isAdmin(),"model cannot grant role by name or email");
        check(!User.fromMap(Map.of("username","admin")).isAdmin(),"legacy import requires explicit role");
        User operator=repo.findByUsername("other");operator.setRole("admin");repo.update(operator);
        check(repo.isAdmin("other"),"explicit persisted admin role works");
        String id=MatchService.start("ARENA","TEST",false,Map.of(),List.of("learner","admin"));
        ExecutorService writers=Executors.newFixedThreadPool(2);
        try{
            var a=writers.submit(()->MatchService.finish(id,false,"done",List.of(new MatchService.Result("learner",15,1,"WIN"))));
            var b=writers.submit(()->MatchService.finish(id,false,"duplicate",List.of(new MatchService.Result("learner",15,1,"WIN"))));
            check(a.get()!=b.get(),"only one concurrent completion commits");
        }finally{writers.shutdownNow();}
        check(MatchService.history("learner").size()==1,"match stored once");
        check(MatchService.history("other").isEmpty(),"history isolated by user id");
        String interrupted=MatchService.start("SURVIVAL","RESTART",false,Map.of(),List.of("learner"));
        MatchService.recoverInterrupted();
        check(!MatchService.finish(interrupted,false,"late",List.of()),"interrupted match cannot award a result");
        check(MatchService.history("learner").stream().anyMatch(r->r.get("status").equals("INTERRUPTED")),"restart marks unfinished match interrupted");
        var scheduler=new ScheduledThreadPoolExecutor(1);
        try{
            Client owner=new Client("learner");
            WheelRoom wheel=new WheelRoom("WTEST","Wheel","learner","ALL",10,1,repo,scheduler);
            wheel.addPlayer(owner);wheel.handleStartGame(owner);
            String masked=(String)owner.last("wheel_state").get("phrase");
            check(masked.matches("[_ ]+"),"wheel packets do not reveal secret letters");
            Field phraseField=WheelRoom.class.getDeclaredField("currentPhrase");phraseField.setAccessible(true);
            String secret=((WheelPhrase)phraseField.get(wheel)).getPhrase();
            wheel.handleSolvePhrase(owner,secret);
            check(owner.last("wheel_end_game").get("fullPhrase").equals(secret),"solution revealed at match end");
            wheel.close();
            check(MatchService.history("learner").stream().anyMatch(r->r.get("game_type").equals("WHEEL")&&r.get("status").equals("COMPLETED")),"wheel results persisted");
            ArenaRoom arena=new ArenaRoom("ATEST","Arena","learner","ALL","EASY",2,5,1,repo,scheduler);
            arena.handleJoin(owner);arena.handleStartGame(owner);
            invoke(arena,"endGame",new Class<?>[]{boolean.class,String.class},false,"test end");arena.close();
            check(MatchService.history("learner").stream().anyMatch(r->r.get("game_type").equals("ARENA")&&r.get("status").equals("COMPLETED")),"arena results persisted");
            SurvivalRoom survival=new SurvivalRoom("STEST","Survival","learner","ALL",100,5,repo,scheduler);
            survival.handleJoin(owner);survival.handleStartGame(owner);
            invoke(survival,"endGame",new Class<?>[]{String.class,String.class},"test end",null);survival.close();
            check(MatchService.history("learner").stream().anyMatch(r->r.get("game_type").equals("SURVIVAL")&&r.get("status").equals("COMPLETED")),"survival results persisted");
            check(Leaderboard.getTop(10).isEmpty(),"three entertainment games never add rank points");
        }finally{scheduler.shutdownNow();}
        SessionManager sessions=new SessionManager();
        String learner="session_id="+sessions.createSession("learner");
        String admin="session_id="+sessions.createSession("admin");
        String operatorCookie="session_id="+sessions.createSession("other");
        try(ArenaGame arena=new ArenaGame(repo);WheelGame wheel=new WheelGame(repo);SurvivalGame survival=new SurvivalGame(repo)){
            check(!arena.isAdmin("admin")&&!wheel.isAdmin("DuyNguyen")&&!survival.isAdmin("admin"),"game managers enforce database roles");
            HttpAndWebSocketServer server=new HttpAndWebSocketServer(0,new File(args[0]),repo,sessions,arena,wheel,survival);
            java.util.concurrent.atomic.AtomicBoolean bound=new java.util.concurrent.atomic.AtomicBoolean();
            Thread thread=new Thread(()->{try{server.start(()->bound.set(true));}catch(IOException e){throw new RuntimeException(e);}});thread.setDaemon(true);thread.start();
            while(server.getPort()==0)Thread.sleep(10);base="http://localhost:"+server.getPort();
            try{
                check(bound.get(),"startup recovery hook runs after successful port binding");
                check(request("/api/portal/overview",null,null).statusCode()==401,"overview requires login");
                check(request("/api/portal/admin",admin,null).statusCode()==403,"reserved name cannot access admin API");
                check(request("/api/portal/admin",operatorCookie,null).statusCode()==403,"admin must use dedicated admin portal session");
                check(request("/api/portal/overview",learner,null).body().contains("\"rankEnabled\":true"),"separate knowledge race rank is available");
                var qRes=request("/api/practice/next",learner,"{\"topic\":\"SCIENCE\",\"difficulty\":\"EASY\"}");
                var q=JsonUtil.parseObject(qRes.body());
                check(qRes.statusCode()==200&&!q.containsKey("correctIndex")&&!q.containsKey("correct"),"practice hides solutions before answering");
                String answer="{\"questionId\":\""+q.get("questionId")+"\",\"answer\":0}";
                check(request("/api/practice/answer",admin,answer).statusCode()==400,"another player account cannot answer the learner question");
                check(request("/api/practice/answer",learner,answer).statusCode()==200,"server marks practice answer");
                check(request("/api/practice/answer",learner,answer).statusCode()==400,"duplicate practice answer rejected");
                check(request("/api/practice/next",learner,"{\"topic\":\"BAD\",\"difficulty\":\"EASY\"}").statusCode()==400,"invalid topic rejected");
                check(request("/",null,null).body().contains("world-map"),"homepage served by Java server");
                check(request("/portal.js",null,null).statusCode()==200,"portal script served");
                Client guest=new Client("learner");
                wheel.handleCreateRoom(guest,Map.of("roomCode","CLOSE","roomName","Close","topic","ALL","botCount",1));
                wheel.handleStartGame(guest);
                wheel.handleAdminCloseRoom(new Client("other"),"CLOSE");
                check(MatchService.history("learner").stream().anyMatch(r->r.get("room_code").equals("CLOSE")&&r.get("status").equals("CANCELLED")),"admin closure cancels wheel history and timers");
                check(guest.last("left_wheel_room")!=null,"closed wheel room returns members to lobby");
                System.out.println("PASS: "+checks+" portal integration checks");
                if(args.length>1 && args[1].equals("preview")){
                    if(args.length>2 && args[2].equals("study")){
                        // Only populate the isolated preview database, never the application database.
                        for(int i=0;i<12;i++){
                            String name="Explorer"+(i+1);repo.register(name,name+"@example.com","TestOnlyPass123");
                            try(var c=AdminStore.connect();var ps=c.prepareStatement("INSERT INTO player_ranks(user_id,current_tier,current_step,current_points,highest_tier,highest_step,highest_points,ranked_games,ranked_wins,updated_at) SELECT id,'DIAMOND',14,?,'DIAMOND',14,?,10,5,? FROM users WHERE username=?")){
                                ps.setInt(1,250-i*12);ps.setInt(2,250-i*12);ps.setLong(3,System.currentTimeMillis());ps.setString(4,name);ps.executeUpdate();
                            }
                        }
                    }
                    System.out.println("PREVIEW_URL="+base);
                    long until=System.currentTimeMillis()+900_000;
                    while(!Files.exists(Path.of("stop-preview"))&&System.currentTimeMillis()<until)Thread.sleep(250);
                }
            }finally{server.stop();thread.join(2000);}
        }finally{db.close();}
    }
}
