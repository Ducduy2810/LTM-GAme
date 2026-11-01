import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import vn.game.db.*;
import vn.game.games.*;
import vn.game.model.*;
import vn.game.server.*;
import vn.game.util.JsonUtil;

public class PlayerLifecycleIntegrationTest {
    static String base,adminCookie;static int checks;static UserRepository users;
    static final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final List<Peer> peers=new ArrayList<>();
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
    static HttpResponse<String> request(String path,String cookie,Map<String,Object> body)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(10));
        if(cookie!=null)builder.header("Cookie",cookie);
        if(body!=null)builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JsonUtil.toJson(body)));
        return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    static String cookie(HttpResponse<?> response){return response.headers().firstValue("set-cookie").orElseThrow().split(";",2)[0];}
    static Map<String,Object> object(String body){return JsonUtil.parseObject(body);}
    static String login(String name)throws Exception{var response=request("/auth/login",null,Map.of("username",name,"password","PlayerPass123"));check(response.statusCode()==200,"login "+name);return cookie(response);}
    static Peer peer(String name)throws Exception{if(users.findByUsername(name)==null)users.register(name,name+"@example.com","PlayerPass123");Peer p=new Peer(name,login(name));peers.add(p);return p;}
    static List<Map<String,Object>> rows(String sql,Object...args)throws Exception{try(Connection c=AdminStore.connect()){return AdminStore.rows(c,sql,args);}}
    static void until(BooleanSupplier condition,String message)throws Exception{long end=System.currentTimeMillis()+8000;while(System.currentTimeMillis()<end){if(condition.getAsBoolean()){checks++;return;}Thread.sleep(25);}throw new AssertionError(message);}
    static boolean online(String name){try{var response=request("/api/admin/users?q="+name,adminCookie,null);var items=(List<?>)object(response.body()).get("items");return items.stream().map(v->(Map<?,?>)v).anyMatch(row->name.equals(row.get("username"))&&Boolean.TRUE.equals(row.get("online")));}catch(Exception e){throw new RuntimeException(e);}}
    static class Peer implements WebSocket.Listener,AutoCloseable {
        final String name,cookie;WebSocket socket;final List<Map<String,Object>> packets=new CopyOnWriteArrayList<>();final StringBuilder buffer=new StringBuilder();
        Peer(String name,String cookie)throws Exception{this.name=name;this.cookie=cookie;socket=http.newWebSocketBuilder().header("Cookie",cookie).connectTimeout(Duration.ofSeconds(5)).buildAsync(URI.create(base.replace("http:","ws:")+"/ws"),this).get(8,TimeUnit.SECONDS);}
        public void onOpen(WebSocket socket){socket.request(1);}
        public CompletionStage<?> onText(WebSocket socket,CharSequence text,boolean last){buffer.append(text);if(last){packets.add(object(buffer.toString()));buffer.setLength(0);}socket.request(1);return null;}
        void send(Map<String,Object> data){socket.sendText(JsonUtil.toJson(data),true).join();}
        void send(String type){send(Map.of("type",type));}
        Map<String,Object> await(Predicate<Map<String,Object>> predicate)throws Exception{until(()->packets.stream().anyMatch(predicate),"packet missing for "+name+": "+packets);return packets.stream().filter(predicate).reduce((a,b)->b).orElseThrow();}
        public void close(){if(socket!=null)socket.abort();}
    }
    static void presenceAndSessions()throws Exception{
        users.register("presence","presence@example.com","PlayerPass123");String first=login("presence");
        check(!online("presence"),"HTTP login alone is not online presence");
        var stream=http.send(HttpRequest.newBuilder(URI.create(base+"/api/events")).header("Cookie",first).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
        until(()->online("presence"),"SSE portal visitor is online");
        Peer old=new Peer("presence",first);peers.add(old);
        String second=login("presence");
        var ended=old.await(p->"auth_error".equals(p.get("type")));
        check("SESSION_REPLACED".equals(((Map<?,?>)ended.get("accountNotice")).get("kind")),"old socket gets explicit replacement reason");
        var oldMe=object(request("/auth/me",first,null).body());check(Boolean.FALSE.equals(oldMe.get("loggedIn")),"old login revoked");
        check("SESSION_REPLACED".equals(((Map<?,?>)oldMe.get("accountNotice")).get("kind")),"reason survives an HTTP refresh");
        var newMe=object(request("/auth/me",second,null).body());check(Boolean.TRUE.equals(newMe.get("loggedIn")),"new login remains valid");
        check(newMe.containsKey("loginNotice"),"new login informed about session replacement");
        stream.body().close();old.close();until(()->!online("presence"),"revoked sockets do not count as online");
        request("/auth/login",null,Map.of("username","presence","password","wrong"));
        check(Boolean.TRUE.equals(object(request("/auth/me",second,null).body()).get("loggedIn")),"wrong password cannot evict valid user");
        var pool=Executors.newFixedThreadPool(2);
        try{var a=pool.submit(()->login("presence"));var b=pool.submit(()->login("presence"));String ca=a.get(),cb=b.get();int valid=0;for(String c:List.of(ca,cb))if(Boolean.TRUE.equals(object(request("/auth/me",c,null).body()).get("loggedIn")))valid++;check(valid==1,"concurrent logins leave exactly one valid session");}finally{pool.shutdownNow();}
        Peer portal=peer("portal_only");
        check(online("portal_only"),"WebSocket player appears online");portal.close();until(()->!online("portal_only"),"closed player becomes offline automatically");
    }
    static String create(Peer host,String game,String code,int bots)throws Exception{
        String type=game.equals("ARENA")?"create_room":game.equals("WHEEL")?"create_wheel_room":"create_survival_room";
        host.send(new HashMap<>(Map.of("type",type,"roomCode",code,"roomName",code,"topic","ALL","difficulty","EASY","botCount",bots,"questionCount",5,"totalRounds",5,"timeLimit",60)));
        host.await(p->p.getOrDefault("type","").toString().endsWith("room_info"));return code;
    }
    static String joinType(String game){return game.equals("ARENA")?"join_room":game.equals("WHEEL")?"join_wheel_room":"join_survival_room";}
    static String leaveType(String game){return game.equals("ARENA")?"leave_room":game.equals("WHEEL")?"leave_wheel_room":"leave_survival_room";}
    static String startType(String game){return game.equals("ARENA")?"start_arena_game":game.equals("WHEEL")?"start_wheel_game":"start_survival_game";}
    static String readyType(String game){return game.equals("ARENA")?"toggle_ready":game.equals("WHEEL")?"toggle_wheel_ready":"toggle_survival_ready";}
    static String matchId(String code)throws Exception{return (String)rows("SELECT id FROM matches WHERE room_code=? ORDER BY started_at DESC LIMIT 1",code).get(0).get("id");}
    static void casual(String game,boolean abrupt,ArenaGame arena,WheelGame wheel,SurvivalGame survival)throws Exception{
        String suffix=game+(abrupt?"D":"L"),code="POLICY_"+suffix;
        Peer host=peer("host_"+suffix),leaver=peer("leave_"+suffix),other=peer("other_"+suffix);
        create(host,game,code,0);
        for(Peer p:List.of(leaver,other)){p.send(Map.of("type",joinType(game),"roomCode",code));p.await(m->m.getOrDefault("type","").toString().endsWith("room_info"));p.send(readyType(game));p.await(m->m.get("players") instanceof List<?> list&&list.stream().anyMatch(v->v instanceof Map<?,?> row&&p.name.equals(row.get("username"))&&Boolean.TRUE.equals(row.get("isReady"))));}
        host.send(startType(game));host.await(p->p.getOrDefault("type","").toString().endsWith("game_started"));String id=matchId(code);
        host.send(Map.of("type",joinType(game),"roomCode",code));Thread.sleep(50);
        check(!"FORFEITED".equals(rows("SELECT outcome FROM match_players WHERE match_id=? AND username=?",id,host.name).get(0).get("outcome")),game+" duplicate join is not a departure");
        if(abrupt)leaver.close();else leaver.send(leaveType(game));
        until(()->{try{return "FORFEITED".equals(rows("SELECT outcome FROM match_players WHERE match_id=? AND username=?",id,leaver.name).get(0).get("outcome"));}catch(Exception e){throw new RuntimeException(e);}},game+" departure persisted");
        var row=rows("SELECT score,outcome FROM match_players WHERE match_id=? AND username=?",id,leaver.name).get(0);check(((Number)row.get("score")).intValue()==0,game+" forfeiter gets zero score");
        if(!abrupt){leaver.send(Map.of("type",joinType(game),"roomCode",code));leaver.await(p->p.getOrDefault("message","").toString().contains("Hãy chờ trận mới"));checks++;}
        check("ACTIVE".equals(rows("SELECT status FROM matches WHERE id=?",id).get(0).get("status")),game+" other humans continue playing");
        MatchService.finish(id,false,"Test completion",List.of(new MatchService.Result(leaver.name,999,1,"WIN"),new MatchService.Result(host.name,5,1,"WIN")));
        check("FORFEITED".equals(rows("SELECT outcome FROM match_players WHERE match_id=? AND username=?",id,leaver.name).get(0).get("outcome")),game+" completion cannot grant abandoned player a win");
        switch(game){case "ARENA"->arena.adminCancelRoom(code,"Test cleanup");case "WHEEL"->wheel.adminCancelRoom(code,"Test cleanup");case "SURVIVAL"->survival.adminCancelRoom(code,"Test cleanup");}
    }
    static void solo(String game)throws Exception{
        Peer host=peer("solo_"+game);String code=create(host,game,"SOLO_"+game,1);
        host.send(startType(game));host.await(p->p.getOrDefault("type","").toString().endsWith("game_started"));String id=matchId(code);host.send(leaveType(game));
        until(()->{try{return "CANCELLED".equals(rows("SELECT status FROM matches WHERE id=?",id).get(0).get("status"));}catch(Exception e){throw new RuntimeException(e);}},game+" solo bot match cancelled immediately");
        check("FORFEITED".equals(rows("SELECT outcome FROM match_players WHERE match_id=?",id).get(0).get("outcome")),game+" cancellation preserves abandonment");
    }
    static void raceBot()throws Exception{
        Peer host=peer("race_bot");host.send(Map.of("type","race_create","withBots",true,"roomName","Policy bots","questionCount",10,"timeLimit",15,"botCount",1));
        host.await(p->"WAITING".equals(p.get("phase")));host.send("race_start");var state=host.await(p->"QUESTION".equals(p.get("phase")));String id=(String)state.get("matchId");host.send("race_leave");host.await(p->"IDLE".equals(p.get("phase")));
        check("CANCELLED".equals(rows("SELECT status FROM race_matches WHERE id=?",id).get(0).get("status")),"race bot departure cancels match");
    }
    static void takeoverInMatch(WheelGame wheel)throws Exception{
        Peer host=peer("takeover_host"),victim=peer("takeover_player"),other=peer("takeover_other");String code=create(host,"WHEEL","TAKEOVER",0);
        for(Peer p:List.of(victim,other)){p.send(Map.of("type","join_wheel_room","roomCode",code));p.await(m->m.getOrDefault("type","").toString().endsWith("room_info"));p.send("toggle_wheel_ready");p.await(m->m.get("players") instanceof List<?> list&&list.stream().anyMatch(v->v instanceof Map<?,?> row&&p.name.equals(row.get("username"))&&Boolean.TRUE.equals(row.get("isReady"))));}
        host.send("start_wheel_game");host.await(p->"wheel_game_started".equals(p.get("type")));String id=matchId(code),newCookie=login(victim.name);
        victim.await(p->"auth_error".equals(p.get("type"))&&p.get("accountNotice") instanceof Map<?,?> n&&"SESSION_REPLACED".equals(n.get("kind")));
        check("FORFEITED".equals(rows("SELECT outcome FROM match_players WHERE match_id=? AND username=?",id,victim.name).get(0).get("outcome")),"login takeover during a game forfeits old participation");
        Peer replacement=new Peer(victim.name,newCookie);peers.add(replacement);replacement.send(Map.of("type","join_wheel_room","roomCode",code));replacement.await(p->p.getOrDefault("message","").toString().contains("Hãy chờ trận mới"));
        wheel.adminKick(code,other.name,"operator","Test administrative kick");other.await(p->"room_kicked".equals(p.get("type")));
        check("KICKED".equals(rows("SELECT outcome FROM match_players WHERE match_id=? AND username=?",id,other.name).get(0).get("outcome")),"administrative kick stays distinct from voluntary forfeit");
        check(other.packets.stream().noneMatch(p->"match_forfeited".equals(p.get("type"))),"kick does not produce a contradictory abandonment notice");
        wheel.adminCancelRoom(code,"Test cleanup");
    }
    public static void main(String[] args)throws Exception{
        if(!Files.exists(Path.of(".player-policy-test")))throw new IllegalStateException("Isolated test directory required");
        var db=DatabaseManager.getInstance();db.init();users=new UserRepository(db);AdminAccount.create(users,"operator","AdminPolicyTestPass123");
        var executor=Executors.newSingleThreadExecutor();
        try(var arena=new ArenaGame(users);var wheel=new WheelGame(users);var survival=new SurvivalGame(users)){
            var server=new HttpAndWebSocketServer(0,new File(args[0]),users,new SessionManager(),arena,wheel,survival);
            var run=executor.submit(()->{try{server.start();}catch(IOException e){throw new UncheckedIOException(e);}});
            try{
                until(()->server.getPort()>0,"server starts");base="http://127.0.0.1:"+server.getPort();
                var auth=request("/api/admin/login",null,Map.of("username","operator","password","AdminPolicyTestPass123"));check(auth.statusCode()==200,"admin login");adminCookie=cookie(auth);
                presenceAndSessions();
                for(String game:List.of("ARENA","WHEEL","SURVIVAL")){casual(game,false,arena,wheel,survival);casual(game,true,arena,wheel,survival);}
                solo("ARENA");solo("WHEEL");raceBot();takeoverInMatch(wheel);
                System.out.println("PASS: "+checks+" lifecycle checks: online/offline, session takeover and concurrent login, all 3 games voluntary/abrupt abandonment, result exclusion, rejoin blocking, and bot cancellation.");
            }finally{peers.forEach(Peer::close);server.stop();run.get(5,TimeUnit.SECONDS);}
        }finally{executor.shutdownNow();db.close();}
    }
}
