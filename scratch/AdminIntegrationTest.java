import vn.game.db.*;
import vn.game.games.*;
import vn.game.model.*;
import vn.game.server.*;
import vn.game.util.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class AdminIntegrationTest {
    static int checks;static String base,cookie,csrf;static String password="AdminTestOnlyPass123!";
    static final HttpClient HTTP=HttpClient.newHttpClient();
    static UserRepository users;static SessionManager sessions;static ArenaGame arena;static WheelGame wheel;static SurvivalGame survival;
    static void check(boolean v,String m){if(!v)throw new AssertionError(m);checks++;}
    static HttpResponse<String> request(String path,String session,Map<String,Object> body,String token,String origin) throws Exception {
        var b=HttpRequest.newBuilder(URI.create(base+path)).timeout(java.time.Duration.ofSeconds(15));
        if(session!=null)b.header("Cookie",session);if(token!=null)b.header("X-CSRF-Token",token);if(origin!=null)b.header("Origin",origin);
        if(body!=null)b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JsonUtil.toJson(body)));
        return HTTP.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    static Map<String,Object> get(String path)throws Exception {var r=request("/api/admin"+path,cookie,null,null,null);check(r.statusCode()==200,"GET "+path+": "+r.body());return JsonUtil.parseObject(r.body());}
    static Map<String,Object> post(String path,Map<String,Object> body)throws Exception {var r=request("/api/admin"+path,cookie,body,csrf,null);check(r.statusCode()==200,"POST "+path+": "+r.body());return JsonUtil.parseObject(r.body());}
    static void login()throws Exception{var r=request("/api/admin/login",null,Map.of("username","operator","password",password),null,null);check(r.statusCode()==200,"dedicated admin login");cookie=r.headers().firstValue("set-cookie").orElseThrow().split(";",2)[0];csrf=(String)JsonUtil.parseObject(r.body()).get("csrfToken");check(r.headers().firstValue("set-cookie").orElse("").contains("Path=/api/admin"),"admin cookie restricted to admin API");}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> items(Map<String,Object> r){return (List<Map<String,Object>>)r.get("items");}
    @SuppressWarnings("unchecked") static <T>T room(Object manager,String code)throws Exception{Field f=manager.getClass().getDeclaredField("rooms");f.setAccessible(true);return (T)((Map<?,?>)f.get(manager)).get(code);}
    static Map<String,Object> question(String topic,String text){return new LinkedHashMap<>(Map.of("kind","QUESTION","text",text,"options",List.of("Một","Hai","Ba","Bốn"),"correct",0,"topic",topic,"difficulty","EASY","explanation","Giải thích dùng để kiểm thử.","status","PUBLISHED"));}
    static class Client extends WebSocketClient {
        final List<Map<String,Object>> packets=new CopyOnWriteArrayList<>();
        Client(String name){super(new Socket(),InputStream.nullInputStream(),OutputStream.nullOutputStream(),name);}
        @Override public void send(String text){packets.add(JsonUtil.parseObject(text));}
        @Override public boolean isClosed(){return false;}
        long count(String type){return packets.stream().filter(p->type.equals(p.get("type"))).count();}
        Map<String,Object> last(String type){return packets.stream().filter(p->type.equals(p.get("type"))).reduce((a,b)->b).orElseThrow(()->new AssertionError("Missing "+type));}
    }
    static class LivePeer implements java.net.http.WebSocket.Listener {
        final List<Map<String,Object>> packets=new CopyOnWriteArrayList<>();final StringBuilder buffer=new StringBuilder();volatile boolean closed;
        public void onOpen(java.net.http.WebSocket ws){ws.request(1);}
        public CompletionStage<?> onText(java.net.http.WebSocket ws,CharSequence data,boolean last){buffer.append(data);if(last){packets.add(JsonUtil.parseObject(buffer.toString()));buffer.setLength(0);}ws.request(1);return CompletableFuture.completedFuture(null);}
        public CompletionStage<?> onClose(java.net.http.WebSocket ws,int status,String reason){closed=true;return CompletableFuture.completedFuture(null);}
    }
    static void until(BooleanSupplier predicate,String message)throws Exception{long deadline=System.currentTimeMillis()+5000;while(!predicate.getAsBoolean()&&System.currentTimeMillis()<deadline)Thread.sleep(10);check(predicate.getAsBoolean(),message);}
    static void hostPermissions()throws Exception{
        for(String game:List.of("ARENA","WHEEL","SURVIVAL")){
            Client owner=new Client("owner"),guest=new Client("guest"),outsider=new Client("outsider");String code="K_"+game;
            Map<String,Object> req=Map.of("roomCode",code,"roomName","Kick rules","topic","ALL","botCount",1,"questionCount",2,"timeLimit",1,"mainPlayerTimeLimit",1);
            switch(game){
                case "ARENA":{
                    arena.handleCreateRoom(owner,req);arena.handleJoinRoom(guest,code);ArenaRoom r=room(arena,code);
                    arena.handleHostKick(outsider,"guest");check(r.getPlayerCount()==2,"arena outsider cannot kick");arena.handleHostKick(owner,"owner");check(r.getPlayerCount()==2,"arena owner cannot kick self");
                    arena.handleToggleReady(guest);arena.handleStartGame(owner);long pauseErrors=owner.count("arena_error");r.handlePauseGame(owner);check(owner.count("arena_error")==pauseErrors+1&&owner.count("arena_game_paused")==0,"arena owner pause denied");
                    long errors=owner.count("arena_error");arena.handleAdminCloseRoom(owner,code);check(owner.count("arena_error")==errors+1,"arena owner cannot delete active match");
                    arena.handleHostKick(owner,"guest");check(guest.count("room_kicked")==1&&r.getPlayerCount()==1,"arena host kicks during match");arena.handleJoinRoom(new Client("guest"),code);check(r.getPlayerCount()==1,"arena kicked account cannot reconnect");break;
                }
                case "WHEEL":{
                    wheel.handleCreateRoom(owner,req);wheel.handleJoinRoom(guest,code);WheelRoom r=room(wheel,code);
                    wheel.handleHostKick(outsider,"guest");wheel.handleHostKick(owner,"owner");check(r.getPlayerCount()==2,"wheel kick needs host and other member");
                    wheel.handleToggleReady(guest);wheel.handleStartGame(owner);long errors=owner.count("wheel_error");wheel.handleAdminCloseRoom(owner,code);check(owner.count("wheel_error")==errors+1,"wheel owner cannot delete active match");wheel.handleHostKick(owner,"guest");check(guest.count("room_kicked")==1&&r.getPlayerCount()==1,"wheel host kicks during match");wheel.handleJoinRoom(new Client("guest"),code);check(r.getPlayerCount()==1,"wheel kicked account cannot reconnect");break;
                }
                case "SURVIVAL":{
                    survival.handleCreateRoom(owner,req);survival.handleJoinRoom(guest,code);SurvivalRoom r=room(survival,code);
                    survival.handleHostKick(outsider,"guest");survival.handleHostKick(owner,"owner");check(r.getPlayerCount()==2,"survival kick needs host and other member");
                    survival.handleToggleReady(guest);survival.handleStartGame(owner);long errors=owner.count("survival_error");r.handlePauseGame(owner);check(owner.count("survival_error")==errors+1,"survival owner cannot pause");survival.handleHostKick(owner,"guest");check(guest.count("room_kicked")==1&&r.getPlayerCount()==1,"survival host kicks during match");survival.handleJoinRoom(new Client("guest"),code);check(r.getPlayerCount()==1,"survival kicked account cannot reconnect");break;
                }
            }
            post("/rooms/action",Map.of("game",game,"roomCode",code,"action","notice","reason","Thông báo thử"));check(owner.count("server_notice")>=1,"admin broadcasts to room "+game);
            post("/rooms/action",Map.of("game",game,"roomCode",code,"action","cancel","reason","Hủy để kiểm thử"));
            check(MatchService.history("owner").stream().anyMatch(r->code.equals(r.get("room_code"))&&r.get("status").equals("CANCELLED")),"admin cancellation persisted "+game);
            check(MatchService.history("guest").stream().anyMatch(r->code.equals(r.get("room_code"))),"kicked player remains in match history "+game);
        }
        Client owner=new Client("owner"),guest=new Client("guest"),other=new Client("outsider");
        arena.handleCreateRoom(owner,Map.of("roomCode","ONE","topic","ALL","botCount",1));arena.handleCreateRoom(other,Map.of("roomCode","TWO","topic","ALL","botCount",1));arena.handleJoinRoom(guest,"TWO");
        arena.handleHostKick(owner,"guest");check(((ArenaRoom)room(arena,"TWO")).getPlayerCount()==2,"host cannot kick a member of another room");
        owner=new Client("owner");guest=new Client("guest");other=new Client("outsider");
        wheel.handleCreateRoom(owner,Map.of("roomCode","LAST","topic","ALL","botCount",0));wheel.handleJoinRoom(guest,"LAST");wheel.handleJoinRoom(other,"LAST");wheel.handleToggleReady(guest);wheel.handleToggleReady(other);wheel.handleStartGame(owner);
        WheelRoom last=room(wheel,"LAST");Field turn=WheelRoom.class.getDeclaredField("turnIndex");turn.setAccessible(true);turn.setInt(last,2);last.broadcastState();wheel.handleHostKick(owner,"outsider");
        check(owner.last("wheel_state").get("currentTurnUsername").equals("owner"),"kicking last active wheel slot moves turn to first remaining slot");
        post("/rooms/action",Map.of("game","WHEEL","roomCode","LAST","action","cancel","reason","Kết thúc kiểm tra lượt"));
        Client survivalOwner=new Client("owner"),survivalGuest=new Client("guest"),survivalOther=new Client("outsider");
        survival.handleCreateRoom(survivalOwner,Map.of("roomCode","EARLY","topic","ALL","totalRounds",5));survival.handleJoinRoom(survivalGuest,"EARLY");survival.handleJoinRoom(survivalOther,"EARLY");survival.handleToggleReady(survivalGuest);survival.handleToggleReady(survivalOther);survival.handleStartGame(survivalOwner);
        until(()->survivalOwner.count("survival_new_question")>0,"survival question begins for live kick test");survival.handleSubmitAnswer(survivalOwner,0);survival.handleSubmitAnswer(survivalOther,0);survival.handleHostKick(survivalOwner,"guest");
        check(survivalOwner.count("survival_round_result")==1,"kicked unanswered participant does not block early result");
        post("/rooms/action",Map.of("game","SURVIVAL","roomCode","EARLY","action","cancel","reason","Kết thúc kiểm tra câu trả lời"));
    }
    static void ownerWaitingDeletion()throws Exception{
        for(String game:List.of("ARENA","WHEEL","SURVIVAL")){
            Client owner=new Client("owner"),guest=new Client("guest"),outsider=new Client("outsider");String code="DEL_"+game;
            java.util.function.BiConsumer<Client,Map<String,Object>> create=game.equals("ARENA")?arena::handleCreateRoom:game.equals("WHEEL")?wheel::handleCreateRoom:survival::handleCreateRoom;
            java.util.function.BiConsumer<Client,String> join=game.equals("ARENA")?arena::handleJoinRoom:game.equals("WHEEL")?wheel::handleJoinRoom:survival::handleJoinRoom;
            java.util.function.BiConsumer<Client,String> close=game.equals("ARENA")?arena::handleAdminCloseRoom:game.equals("WHEEL")?wheel::handleAdminCloseRoom:survival::handleAdminCloseRoom;
            java.util.function.Supplier<List<Map<String,Object>>> list=game.equals("ARENA")?arena::getRoomsSummaryList:game.equals("WHEEL")?wheel::getRoomsSummaryList:survival::getRoomsSummaryList;
            Object manager=game.equals("ARENA")?arena:game.equals("WHEEL")?wheel:survival;
            create.accept(owner,Map.of("roomCode",code,"topic","ALL"));join.accept(guest,code);
            close.accept(outsider,code);close.accept(guest,code);
            check(list.get().stream().anyMatch(r->r.get("roomCode").equals(code)),"non-owner cannot delete waiting room "+game);
            close.accept(owner,code);check(list.get().stream().noneMatch(r->r.get("roomCode").equals(code)),"owner deletes waiting room "+game);
            String notification=game.equals("ARENA")?"arena_room_closed":game.equals("WHEEL")?"left_wheel_room":"survival_room_closed";
            check(guest.count(notification)==1,"waiting room deletion returns guest to lobby "+game);
            check(MatchService.history("owner").stream().noneMatch(r->r.get("room_code").equals(code)),"waiting deletion does not create a match result "+game);
            create.accept(owner,Map.of("roomCode",code,"topic","ALL"));Object r=room(manager,code);Field state=r.getClass().getDeclaredField("gameState");state.setAccessible(true);
            for(String active:List.of("STARTING","QUESTION","RESULT","PLAYING")){
                state.set(r,active);close.accept(owner,code);check(list.get().stream().anyMatch(row->row.get("roomCode").equals(code)),"owner deletion rejected in "+active+" / "+game);
            }
            state.set(r,"END");close.accept(owner,code);check(list.get().stream().noneMatch(row->row.get("roomCode").equals(code)),"ended room may be deleted "+game);
            String defaultCode=game.equals("ARENA")?"DT100":game.equals("WHEEL")?"CNKD":"DTST";
            join.accept(owner,defaultCode);close.accept(owner,defaultCode);check(list.get().stream().anyMatch(row->row.get("roomCode").equals(defaultCode)),"default room protected from host deletion "+game);
        }
        check(!items(get("/logs?q=HOST_ROOM_DELETE")).isEmpty(),"waiting room deletions are audited");
    }
    static void content()throws Exception{
        post("/topics/save",Map.of("scope","QUESTION","code","TEST_Q","name","Chủ đề thử","active",true));
        var q=question("TEST_Q","Nội dung trước chỉnh sửa");int id=((Number)post("/content/save",q).get("id")).intValue();
        check(QuestionBank.getArenaQuestions("TEST_Q","EASY",5).get(0).getExplanation().contains("Giải thích"),"published database questions used by game bank");
        Client owner=new Client("owner");arena.handleCreateRoom(owner,Map.of("roomCode","SNAP","topic","TEST_Q","difficulty","EASY","questionCount",1,"botCount",1));arena.handleStartGame(owner);
        q.put("id",id);q.put("revision",1);q.put("text","Nội dung sau chỉnh sửa");post("/content/save",q);
        check(QuestionBank.getArenaQuestions("TEST_Q","EASY",5).get(0).getText().contains("sau"),"edits affect future questions");
        String matchId=(String)MatchService.history("owner").stream().filter(r->r.get("room_code").equals("SNAP")).findFirst().orElseThrow().get("id");
        check(((Map<?,?>)get("/match?id="+matchId).get("match")).get("config_json").toString().contains("trước"),"active match retains persisted old question snapshot");
        check(request("/api/admin/content/save",cookie,q,csrf,null).statusCode()==400,"stale revision rejected");
        check(items(get("/revisions?kind=QUESTION&id="+id)).size()==2,"content revisions retained");
        q.put("revision",2);q.put("status","ARCHIVED");post("/content/save",q);
        check(items(get("/content?kind=QUESTION&q=TEST_Q")).size()==1,"archived content remains manageable");
        boolean archived=false;try{archived=QuestionBank.getArenaQuestions("TEST_Q","EASY",3).isEmpty();}catch(IllegalArgumentException e){archived=true;}check(archived,"archived questions not served to games");
        post("/topics/save",Map.of("scope","WHEEL","code","TEST_W","name","Ô chữ thử","active",true));
        post("/content/save",Map.of("kind","WHEEL","phrase","Kiến thức","hint","Điều học được","topic","TEST_W","explanation","Một khái niệm giáo dục.","status","PUBLISHED"));
        check(WheelPhraseBank.getRandomPhrase("TEST_W").getPhrase().equals("KIEN THUC"),"published phrases normalized and live");
        String csv="text,option_a,option_b,option_c,option_d,correct,topic,difficulty,explanation,status\r\n\"Dòng, có dấu phẩy\",A,B,C,D,A,SCIENCE,EASY,\"Giải thích\nđược trích dẫn\",DRAFT\r\n";
        int before=items(get("/content?kind=QUESTION")).size();
        check(((Number)post("/content/import",Map.of("kind","QUESTION","csv",csv,"dryRun",true)).get("count")).intValue()==1,"CSV preview handles quoted commas and newlines");
        check(items(get("/content?kind=QUESTION")).size()==before,"CSV preview does not write");
        post("/content/import",Map.of("kind","QUESTION","csv",csv,"dryRun",false));check(items(get("/content?kind=QUESTION")).size()==before+1,"CSV import committed");
        String bad=csv+"Bad,A,B,C,D,X,SCIENCE,EASY,,DRAFT\r\n";check(request("/api/admin/content/import",cookie,Map.of("kind","QUESTION","csv",bad,"dryRun",false),csrf,null).statusCode()==400,"invalid CSV rejected atomically");
        check(items(get("/content?kind=QUESTION")).size()==before+1,"failed CSV import writes no partial rows");
    }
    @SuppressWarnings("unchecked") static void settings()throws Exception{
        var s=get("/settings");s.put("maintenance",true);post("/settings",s);
        Client owner=new Client("owner");int count=arena.getRoomsSummaryList().size();arena.handleCreateRoom(owner,Map.of("roomCode","MAINT","topic","ALL"));check(arena.getRoomsSummaryList().size()==count&&owner.count("arena_error")==1,"maintenance blocks new rooms on server");
        s.put("maintenance",false);Map<String,Object> enabled=new LinkedHashMap<>((Map<String,Object>)s.get("enabled"));enabled.put("WHEEL",false);s.put("enabled",enabled);post("/settings",s);
        wheel.handleCreateRoom(owner,Map.of("roomCode","DISABLED","topic","ALL"));check(owner.count("wheel_error")==1,"disabled game blocks new rooms");
        enabled.put("WHEEL",true);Map<String,Object> defaults=new LinkedHashMap<>((Map<String,Object>)s.get("defaults"));defaults.put("ARENA",Map.of("questionCount",2,"timeLimit",4,"mainPlayerTimeLimit",8,"botCount",1));s.put("defaults",defaults);post("/settings",s);
        arena.handleCreateRoom(owner,Map.of("roomCode","DEFAULTS","topic","ALL"));check(((ArenaRoom)room(arena,"DEFAULTS")).getBotCount()==1,"room defaults taken from database");
        var catalog=JsonUtil.parseObject(request("/api/catalog",null,null,null,null).body());check(catalog.containsKey("topics"),"public catalog exposes topic names but no answers");
    }
    static void accountModeration()throws Exception{
        String playerId=sessions.createSession("guest"),playerCookie="session_id="+playerId;
        LivePeer peer=new LivePeer();java.net.http.WebSocket connection=HTTP.newWebSocketBuilder().header("Cookie",playerCookie).buildAsync(URI.create(base.replace("http:","ws:")+"/ws"),peer).join();
        connection.sendText("{\"type\":\"get_rooms\"}",true).join();until(()->peer.packets.stream().anyMatch(p->p.get("type").equals("room_list")),"player connected over real WebSocket before lock");
        int guest=users.findByUsername("guest").getId();
        post("/users/action",Map.of("userId",guest,"action","lock","minutes",60,"reason","Kiểm tra khóa"));
        until(()->peer.closed||peer.packets.stream().anyMatch(p->p.get("type").equals("auth_error")),"locking online account closes live WebSocket");connection.abort();
        check(sessions.getUsername(playerId)==null,"lock revokes existing player sessions");
        check(request("/auth/login",null,Map.of("username","guest","password","TestPlayerPass123"),null,null).statusCode()==403,"locked account cannot log in");
        check(!Boolean.TRUE.equals(JsonUtil.parseObject(request("/auth/me",playerCookie,null,null,null).body()).get("loggedIn")),"locked session cannot authenticate");
        post("/users/action",Map.of("userId",guest,"action","unlock","reason","Kiểm tra mở khóa"));
        check(request("/auth/login",null,Map.of("username","guest","password","TestPlayerPass123"),null,null).statusCode()==200,"unlocked account can log in");
        check(request("/api/admin/users/action",cookie,Map.of("userId",users.findByUsername("operator").getId(),"action","lock","minutes",60,"reason","Không được phép"),csrf,null).statusCode()==400,"player moderation cannot lock admin");
    }
    static void idleSessions()throws Exception{
        AdminSessions store=new AdminSessions();String id=store.create("operator");var s=store.get(id);Field seen=s.getClass().getDeclaredField("lastSeen");seen.setAccessible(true);seen.setLong(s,System.currentTimeMillis()-AdminSessions.IDLE_MS-1);check(store.get(id,false)==null,"idle admin sessions expire");
    }
    public static void main(String[] args)throws Exception{
        if(!Files.exists(Path.of(".admin-test")))throw new IllegalStateException("Run test_admin.ps1 in an isolated directory");
        DatabaseManager db=DatabaseManager.getInstance();db.init();users=new UserRepository(db);
        for(String name:List.of("owner","guest","outsider"))users.register(name,name+"@example.com","TestPlayerPass123");
        AdminAccount.create(users,"operator",password);sessions=new SessionManager();
        arena=new ArenaGame(users);wheel=new WheelGame(users);survival=new SurvivalGame(users);
        HttpAndWebSocketServer server=new HttpAndWebSocketServer(0,new File(args[0]),users,sessions,arena,wheel,survival);
        Thread thread=new Thread(()->{try{server.start();}catch(IOException e){throw new RuntimeException(e);}});thread.setDaemon(true);thread.start();
        while(server.getPort()==0)Thread.sleep(10);base="http://localhost:"+server.getPort();
        try{
            check(request("/admin",null,null,null,null).body().contains("admin-app"),"dedicated admin page served");
            String player="session_id="+sessions.createSession("owner");
            check(request("/api/admin/overview",player,null,null,null).statusCode()==401,"player cookie cannot access admin");
            check(request("/api/admin/login",null,Map.of("username","owner","password","TestPlayerPass123"),null,null).statusCode()==401,"player cannot use admin login");
            check(request("/auth/login",null,Map.of("username","operator","password",password),null,null).statusCode()==403,"admin cannot use player login");
            login();
            check(request("/api/admin/settings",cookie,Map.of(),null,null).statusCode()==403,"admin mutations require CSRF token");
            check(request("/api/admin/overview",cookie,null,null,"https://evil.example").statusCode()==403,"cross-origin admin request denied");
            get("/overview");get("/users");get("/rooms");get("/matches");idleSessions();hostPermissions();ownerWaitingDeletion();content();settings();accountModeration();
            String backup=(String)post("/backup",Map.of()).get("name");check(!items(get("/backups")).isEmpty(),"backup listed");
            var bytes=HTTP.send(HttpRequest.newBuilder(URI.create(base+"/api/admin/backup-file?name="+backup)).header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
            check(bytes.statusCode()==200&&new String(bytes.body(),0,15,java.nio.charset.StandardCharsets.US_ASCII).equals("SQLite format 3"),"backup downloads a SQLite snapshot");
            check(request("/api/admin/backup-file?name=../../.env",cookie,null,null,null).statusCode()==400,"backup traversal blocked");
            check(items(get("/logs")).stream().anyMatch(r->r.get("action").equals("HOST_KICK")),"host kick audit persisted");
            check(Leaderboard.getTop(20).isEmpty(),"entertainment never contributes rank");
            post("/password",Map.of("currentPassword",password,"newPassword","AdminTestOnlyPass456!"));password="AdminTestOnlyPass456!";
            check(request("/api/admin/overview",cookie,null,null,null).statusCode()==401,"password change invalidates admin sessions");login();
            User reset=users.findByUsername("operator");reset.setSalt(User.generateSalt());reset.setPasswordHash(User.secureHashPassword(password,reset.getSalt()));users.update(reset);
            check(request("/api/admin/overview",cookie,null,null,null).statusCode()==401,"local password reset also invalidates existing admin cookie");login();
            System.out.println("PASS: "+checks+" admin, content, session, moderation and all-game permission checks");
            if(args.length>1&&args[1].equals("preview")){System.out.println("PREVIEW_URL="+base);long until=System.currentTimeMillis()+600_000;while(!Files.exists(Path.of("stop-preview"))&&System.currentTimeMillis()<until)Thread.sleep(250);}
        }finally{server.stop();arena.close();wheel.close();survival.close();db.close();thread.join(2000);}
    }
}
