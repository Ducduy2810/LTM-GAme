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
            check(guest.last("room_kicked").get("message").toString().startsWith("Bạn đã bị kick"),"kick notification explicitly identifies kick in "+game);
            check(guest.last("room_kicked").get("reason").equals("Chủ phòng đã mời bạn rời phòng."),"kick notification preserves reason in "+game);
            post("/rooms/action",Map.of("game",game,"roomCode",code,"action","notice","reason","Thông báo thử"));check(owner.count("server_notice")>=1,"admin broadcasts to room "+game);
            post("/rooms/action",Map.of("game",game,"roomCode",code,"action","cancel","reason","Hủy để kiểm thử"));
            String closedEvent=game.equals("ARENA")?"arena_room_closed":game.equals("SURVIVAL")?"survival_room_closed":"left_wheel_room";
            check(Boolean.TRUE.equals(owner.last(closedEvent).get("cancelled"))&&owner.last(closedEvent).get("reason").equals("Hủy để kiểm thử"),"cancelled room notification carries exact reason "+game);
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
    static void contentFilters()throws Exception{
        String prefix="FILTER_MODE_FIXTURE_";Map<String,Integer> ids=new HashMap<>();
        String[][] fixtures={{"legacyEasy","LEGACY","EASY","NETWORKING","PUBLISHED","Lời giải"},{"legacyHard","LEGACY","HARD","SCIENCE","PUBLISHED","Lời giải"},{"bothMedium","BOTH","MEDIUM","NETWORKING","PUBLISHED","Lời giải"},{"bothHardDraft","BOTH","HARD","NETWORKING","DRAFT","Lời giải"},{"raceEasy","RACE","EASY","NETWORKING","PUBLISHED","Lời giải"},{"raceHard","RACE","HARD","NETWORKING","PUBLISHED","Lời giải"},{"raceHardArchived","RACE","HARD","NETWORKING","ARCHIVED","Lời giải"},{"bothEasy","BOTH","EASY","NETWORKING","PUBLISHED","Lời giải"},{"legacyMediumDraft","LEGACY","MEDIUM","NETWORKING","DRAFT","Lời giải"},{"missingExplanation","RACE","HARD","NETWORKING","PUBLISHED",""},{"wrongTopic","RACE","HARD","SCIENCE","PUBLISHED","Lời giải"}};
        try(var c=AdminStore.connect();var ps=c.prepareStatement("INSERT INTO game_questions(text,options_json,correct,topic,difficulty,usage,explanation,status,updated_at) VALUES(?,'[\"A\",\"B\",\"C\",\"D\"]',0,?,?,?,?,?,?)")){
            for(String[] f:fixtures){ps.setString(1,prefix+f[0]);ps.setString(2,f[3]);ps.setString(3,f[2]);ps.setString(4,f[1]);ps.setString(5,f[5]);ps.setString(6,f[4]);ps.setLong(7,System.currentTimeMillis());ps.executeUpdate();}
            try(var setTier=c.prepareStatement("UPDATE game_questions SET rank_tier=CASE difficulty WHEN 'EASY' THEN 'BRONZE' WHEN 'MEDIUM' THEN 'GOLD' ELSE 'DIAMOND' END WHERE text LIKE ? AND usage='RACE'")){setTier.setString(1,prefix+"%");setTier.executeUpdate();}
            for(var row:AdminStore.rows(c,"SELECT id,text FROM game_questions WHERE text LIKE ?",prefix+"%"))ids.put(row.get("text").toString().substring(prefix.length()),((Number)row.get("id")).intValue());
        }
        try{
            Set<Integer> legacy=new HashSet<>(),race=new HashSet<>(),daily=new HashSet<>();for(String key:List.of("legacyEasy","legacyHard","bothMedium","bothHardDraft","bothEasy","legacyMediumDraft"))legacy.add(ids.get(key));for(String key:List.of("bothMedium","bothHardDraft","bothEasy"))race.add(ids.get(key));daily.add(ids.get("bothHardDraft"));Set<Integer> ranked=Set.of(ids.get("raceEasy"),ids.get("raceHard"),ids.get("raceHardArchived"));
            String base="/content?kind=QUESTION&q="+prefix;
            check(items(get(base)).size()==fixtures.length,"unfiltered question bank includes all question scopes");
            for(String mode:List.of("ARENA","SURVIVAL","PRACTICE","RANKED","CASUAL","BOT","DAILY")){
                Set<Integer> found=new HashSet<>();for(var row:items(get(base+"&mode="+mode)))found.add(((Number)row.get("id")).intValue());Set<Integer> expected=Set.of("ARENA","SURVIVAL","PRACTICE").contains(mode)?legacy:mode.equals("DAILY")?daily:mode.equals("RANKED")?ranked:race;
                check(found.equals(expected),"mode filter follows actual question scope for "+mode);
            }
            check(items(get(base+"&mode=RANKED&rankTier=BRONZE")).size()==1,"ranked bank filters by major tier");
            check(items(get(base+"&mode=RANKED&rankTier=DIAMOND&difficulty=HARD")).size()==2,"rank tier combines with difficulty");
            check(items(get(base+"&mode=RANKED&rankTier=SHARED")).isEmpty(),"unassigned shared questions excluded from ranked filter");
            check(request("/api/admin"+base+"&rankTier=UNKNOWN",cookie,null,null,null).statusCode()==400,"invalid ranked pool filter rejected");
            check(items(get(base+"&difficulty=EASY")).size()==3,"easy filter spans all scopes");
            check(items(get(base+"&difficulty=MEDIUM")).size()==2,"medium filter spans all scopes");
            check(items(get(base+"&mode=RANKED&difficulty=HARD&status=PUBLISHED")).stream().allMatch(row->((Number)row.get("id")).intValue()==ids.get("raceHard"))&&items(get(base+"&mode=RANKED&difficulty=HARD&status=PUBLISHED")).size()==1,"mode difficulty search and status combine");
            check(items(get(base+"&mode=DAILY&difficulty=EASY")).isEmpty(),"daily mode plus easy difficulty has no matches");
            check(items(get(base+"&mode=DAILY&difficulty=HARD&status=DRAFT")).size()==1,"admin can filter drafts eligible for daily scope");
            check(request("/api/admin"+base+"&mode=INVALID",cookie,null,null,null).statusCode()==400,"unknown mode rejected");
            check(request("/api/admin"+base+"&difficulty=MIX",cookie,null,null,null).statusCode()==400,"unknown difficulty rejected");
            check(request("/api/admin/content?kind=WHEEL&mode=RANKED",cookie,null,null,null).statusCode()==400,"question filters not applied to wheel phrases");
            check(request("/api/admin"+base+"&mode=RANKED",null,null,null,null).statusCode()==401,"mode-filtered content remains admin-only");
            check(request("/api/admin/content?kind=WHEEL",cookie,null,null,null).statusCode()==200,"wheel bank remains available without question filters");
            try(var c=AdminStore.connect();var ps=c.prepareStatement("INSERT INTO game_questions(text,options_json,correct,topic,difficulty,usage,explanation,status,updated_at) VALUES(?,'[\"A\",\"B\",\"C\",\"D\"]',0,'NETWORKING','EASY','RACE','Lời giải','PUBLISHED',?)")){
                for(int i=0;i<505;i++){ps.setString(1,prefix+"bulk"+i);ps.setLong(2,System.currentTimeMillis());ps.addBatch();}ps.executeBatch();
            }
            check(items(get(base)).size()==500,"question listing still bounded at 500 results");
            Set<Integer> found=new HashSet<>();for(var row:items(get(base+"&mode=ARENA")))found.add(((Number)row.get("id")).intValue());check(found.equals(legacy),"mode filter runs before 500-row limit so older matching questions remain discoverable");
        }finally{try(var c=AdminStore.connect();var ps=c.prepareStatement("DELETE FROM game_questions WHERE text LIKE ?")){ps.setString(1,prefix+"%");ps.executeUpdate();}}
    }
    static void rankedQuestionEditor()throws Exception{
        String text="RANK_EDITOR_FIXTURE_";List<Integer> added=new ArrayList<>();
        try{
            var value=question("NETWORKING",text+"single");value.put("usage","RACE");value.put("rankTier","BRONZE");
            int id=((Number)post("/content/save",value).get("id")).intValue();added.add(id);
            var rows=items(get("/content?kind=QUESTION&q="+text+"&mode=RANKED&rankTier=BRONZE"));check(rows.size()==1&&rows.get(0).get("rank_tier").equals("BRONZE"),"admin can create and filter tier-specific ranked question");
            value.put("id",id);value.put("revision",1);value.put("difficulty","HARD");check(request("/api/admin/content/save",cookie,value,csrf,null).statusCode()==400,"Bronze rejects HARD difficulty");
            value.put("difficulty","MEDIUM");value.put("rankTier","GOLD");post("/content/save",value);check(items(get("/content?kind=QUESTION&q="+text+"&rankTier=GOLD")).size()==1,"question rank tier editable");
            value.put("revision",2);value.remove("rankTier");post("/content/save",value);check(items(get("/content?kind=QUESTION&q="+text+"&rankTier=GOLD")).size()==1,"older clients omitting tier preserve the existing assignment");
            value.put("revision",3);value.put("rankTier","INVALID");check(request("/api/admin/content/save",cookie,value,csrf,null).statusCode()==400,"unknown editor tier rejected");
            value.put("rankTier","GOLD");value.put("usage","LEGACY");check(request("/api/admin/content/save",cookie,value,csrf,null).statusCode()==400,"legacy scope cannot consume tier-specific questions");
            String csv="text,option_a,option_b,option_c,option_d,correct,topic,difficulty,usage,rank_tier,explanation,status\n"+text+"csv,A,B,C,D,B,NETWORKING,HARD,RACE,DIAMOND,Lời giải,DRAFT\n";
            post("/content/import",Map.of("kind","QUESTION","csv",csv,"dryRun",true));check(items(get("/content?kind=QUESTION&q="+text+"csv")).isEmpty(),"tier CSV preview does not write data");
            post("/content/import",Map.of("kind","QUESTION","csv",csv,"dryRun",false));var imported=items(get("/content?kind=QUESTION&q="+text+"csv&rankTier=DIAMOND"));check(imported.size()==1&&imported.get(0).get("rank_tier").equals("DIAMOND"),"CSV import retains major rank tier");added.add(((Number)imported.get(0).get("id")).intValue());
        }finally{try(var c=AdminStore.connect();var ps=c.prepareStatement("DELETE FROM game_questions WHERE id=?")){for(int id:added){ps.setInt(1,id);ps.executeUpdate();}}AdminStore.reloadContent();}
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
        var s=get("/settings");s.put("maintenance",true);s.put("maintenanceHours",1);post("/settings",s);
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
    static Socket eventStream(String session)throws Exception{
        URI uri=URI.create(base);Socket socket=new Socket(uri.getHost(),uri.getPort());socket.setSoTimeout(5000);
        socket.getOutputStream().write(("GET /api/events HTTP/1.1\r\nHost: "+uri.getAuthority()+"\r\n"+(session==null?"":"Cookie: session_id="+session+"\r\n")+"\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        var reader=new BufferedReader(new InputStreamReader(socket.getInputStream()));
        check(reader.readLine().contains("200"),"event stream opens");
        while(!reader.readLine().isEmpty()){}
        check(reader.readLine().startsWith("event:"),"stream registered before checking online count");return socket;
    }
    static int online()throws Exception{return ((Number)get("/overview").get("connections")).intValue();}
    @SuppressWarnings("unchecked") static int activeMatches()throws Exception{return ((Number)((Map<String,Object>)get("/overview").get("counts")).get("activeMatches")).intValue();}
    static void overviewStats()throws Exception{
        String ownerSession=sessions.createSession("owner"),guestSession=sessions.createSession("guest");
        check(online()==0,"stored login sessions without an open page are offline");
        try(Socket anonymous=eventStream(null);Socket ownerTab=eventStream(ownerSession);Socket duplicateTab=eventStream(sessions.createSession("owner"));Socket guestTab=eventStream(guestSession)){
            check(online()==2,"lobby users counted once across tabs; anonymous visitors excluded");
            LivePeer listener=new LivePeer();
            var ws=HTTP.newWebSocketBuilder().header("Cookie","session_id="+ownerSession).buildAsync(URI.create(base.replace("http:","ws:")+"/ws"),listener).join();
            check(online()==2,"game socket and lobby stream count the same player once");
            sessions.removeUserSessions("owner");check(online()==1,"revoked sessions excluded immediately from streams and game sockets");
            ws.abort();
            guestTab.setSoLinger(true,0);guestTab.close();
            long deadline=System.currentTimeMillis()+16000;
            while(online()!=0&&System.currentTimeMillis()<deadline)Thread.sleep(250);
            check(online()==0,"closing the last page removes online user after heartbeat");
        }finally{sessions.removeUserSessions("owner");sessions.removeUserSessions("guest");}
        check(activeMatches()==0,"no ongoing matches initially");
        String waiting=RaceStore.create("STAT_WAIT","Waiting stats","CASUAL",Map.of(),System.currentTimeMillis());
        check(activeMatches()==0,"waiting race room excluded");
        String legacy=MatchService.start("ARENA","STAT_ARENA",true,Map.of(),List.of("owner"));
        check(activeMatches()==1,"active legacy match counted");
        RaceStore.activate(waiting,Map.of(),false,System.currentTimeMillis());
        check(activeMatches()==2,"active race and legacy matches combined");
        MatchService.finish(legacy,false,"stats test",List.of());check(activeMatches()==1,"completed match excluded");
        RaceStore.cancel(waiting,"stats test");check(activeMatches()==0,"cancelled race excluded");
    }
    @SuppressWarnings("unchecked") static void maintenanceControl()throws Exception{
        var original=get("/settings");var config=RaceStore.settings();var duel=new LinkedHashMap<>(config);duel.put("targetPlayers",2);RaceStore.saveSettings("operator",duel);
        List<java.net.http.WebSocket> sockets=new ArrayList<>();
        var events=HTTP.send(HttpRequest.newBuilder(URI.create(base+"/api/events")).build(),HttpResponse.BodyHandlers.ofInputStream());
        List<String> streamLines=new CopyOnWriteArrayList<>();Thread reader=new Thread(()->{try(var lines=new BufferedReader(new InputStreamReader(events.body(),java.nio.charset.StandardCharsets.UTF_8))){String line;while((line=lines.readLine())!=null)streamLines.add(line);}catch(IOException ignored){}});reader.setDaemon(true);reader.start();
        try{
            var invalid=new LinkedHashMap<>(original);invalid.put("maintenance",true);invalid.remove("maintenanceUntil");
            check(request("/api/admin/settings",cookie,invalid,csrf,null).statusCode()==400,"maintenance requires a future end time");
            invalid.put("maintenanceHours",0);check(request("/api/admin/settings",cookie,invalid,csrf,null).statusCode()==400,"zero maintenance duration rejected");
            invalid.put("maintenanceHours",-1);check(request("/api/admin/settings",cookie,invalid,csrf,null).statusCode()==400,"negative maintenance duration rejected");
            invalid.remove("maintenanceHours");invalid.put("maintenanceUntil",System.currentTimeMillis()-1);check(request("/api/admin/settings",cookie,invalid,csrf,null).statusCode()==400,"past maintenance date rejected");
            String owner=sessions.createSession("owner"),guest=sessions.createSession("guest"),idle=sessions.createSession("outsider");
            List<LivePeer> peers=new ArrayList<>();
            for(String id:List.of(owner,guest)){
                LivePeer peer=new LivePeer();peers.add(peer);var ws=HTTP.newWebSocketBuilder().header("Cookie","session_id="+id).buildAsync(URI.create(base.replace("http:","ws:")+"/ws"),peer).join();sockets.add(ws);ws.sendText("{\"type\":\"race_queue\",\"mode\":\"RANKED\"}",true).join();
            }
            until(()->peers.get(0).packets.stream().anyMatch(p->p.get("type").equals("race_state")&&p.get("phase").equals("QUESTION")),"ranked match active before maintenance");
            String match=peers.get(0).packets.stream().filter(p->p.get("type").equals("race_state")&&p.get("phase").equals("QUESTION")).map(p->p.get("matchId").toString()).findFirst().orElseThrow();
            long before=System.currentTimeMillis();var active=new LinkedHashMap<>(original);active.put("maintenance",true);active.put("maintenanceHours",1);active.put("message","Nâng cấp dữ liệu thử nghiệm");post("/settings",active);
            var current=get("/settings");long until=((Number)current.get("maintenanceUntil")).longValue();check(until>=before+3600000&&until<=System.currentTimeMillis()+3600000,"hour duration converted to persisted end timestamp");
            check(sessions.getUsername(owner)==null&&sessions.getUsername(guest)==null&&sessions.getUsername(idle)==null,"maintenance revokes online and idle player sessions immediately");
            until(()->peers.stream().allMatch(peer->peer.packets.stream().anyMatch(p->p.get("type").equals("auth_error")&&p.get("message").toString().contains("Server đang bảo trì đến"))),"maintenance logout includes end time on real sockets");
            until(()->streamLines.stream().anyMatch(line->line.startsWith("data:")&&line.contains("Server đang bảo trì đến")),"maintenance notice reaches lobby and anonymous SSE visitors");
            var login=request("/auth/login",null,Map.of("username","owner","password","TestPlayerPass123"),null,null);check(login.statusCode()==503&&login.body().contains("Server đang bảo trì đến"),"player login blocked with maintenance deadline");
            var me=JsonUtil.parseObject(request("/auth/me","session_id="+owner,null,null,null).body());check(Boolean.FALSE.equals(me.get("loggedIn"))&&((Map<?,?>)me.get("maintenance")).get("until").equals(until),"identity endpoint returns logged-out maintenance status");
            check(request("/api/race/overview","session_id="+owner,null,null,null).statusCode()==503,"player APIs blocked during maintenance");
            for(String path:List.of("/auth/google","/auth/google/callback?code=unused")){var response=request(path,null,null,null,null);check(response.statusCode()==302&&response.headers().firstValue("location").orElse("").contains("error=maintenance"),"Google entry and callback blocked before account creation");}
            check(request("/api/admin/session",cookie,null,null,null).statusCode()==200,"admin session remains usable during maintenance");
            check(request("/api/admin/login",null,Map.of("username","operator","password",password),null,null).statusCode()==200,"admin can sign in during maintenance");
            check(RaceStore.history("owner",false).stream().anyMatch(r->r.get("id").equals(match)&&r.get("status").equals("CANCELLED")),"maintenance cancels active ranked match");
            try(var c=AdminStore.connect()){check(((Number)AdminStore.rows(c,"SELECT COUNT(*) AS n FROM rank_ledger WHERE match_id=?",match).get(0).get("n")).intValue()==0,"maintenance cancellation does not settle rank penalties or rewards");}
            check(activeMatches()==0,"all ongoing matches cleared by maintenance");
            active.remove("maintenanceHours");active.put("maintenanceUntil",System.currentTimeMillis()+2500);post("/settings",active);
            check(Boolean.TRUE.equals(get("/settings").get("maintenance")),"absolute end date activates maintenance");
            until(()->{try{return Boolean.FALSE.equals(JsonUtil.parseObject(request("/api/maintenance",null,null,null,null).body()).get("enabled"));}catch(Exception e){return false;}},"maintenance expires automatically without another admin save");
            check(Boolean.FALSE.equals(get("/settings").get("maintenance")),"admin view reflects expired maintenance");
            check(request("/auth/login",null,Map.of("username","owner","password","TestPlayerPass123"),null,null).statusCode()==200,"player login reopens automatically after expiry");
            check(Boolean.FALSE.equals(JsonUtil.parseObject(request("/auth/me","session_id="+owner,null,null,null).body()).get("loggedIn")),"revoked old session stays invalid after reopening");
            active.put("maintenanceHours",1);post("/settings",active);post("/settings",original);
            check(request("/auth/login",null,Map.of("username","guest","password","TestPlayerPass123"),null,null).statusCode()==200,"admin can end maintenance early");
        }finally{post("/settings",original);RaceStore.saveSettings("operator",config);events.body().close();for(var ws:sockets)ws.abort();}
    }
    static String configFixture(byte[] snapshot,Map<String,Object> config)throws Exception{
        Path file=Path.of("config-fixture-"+UUID.randomUUID()+".db");Files.write(file,snapshot);
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+file);Statement st=c.createStatement()){
            st.execute("DELETE FROM app_settings");
            st.execute("UPDATE users SET role='user' WHERE username='operator'");
            st.execute("UPDATE game_questions SET text='SOURCE DATA MUST NOT REPLACE LIVE QUESTIONS'");
            try(var ps=c.prepareStatement("INSERT INTO app_settings(key,value) VALUES(?,?)")){for(var e:config.entrySet()){ps.setString(1,e.getKey());ps.setString(2,JsonUtil.toJson(e.getValue()));ps.executeUpdate();}}
        }
        return AdminStore.uploadBackup("operator",Files.readAllBytes(file));
    }
    static Map<String,Object> cloneMap(Map<String,Object> map){return JsonUtil.parseObject(JsonUtil.toJson(map));}
    @SuppressWarnings("unchecked") static void backupConfigurationImports(byte[] snapshot)throws Exception{
        var runtime=get("/settings");var race=get("/race/settings");var announcement=get("/announcement");
        String hash=users.findByUsername("operator").getPasswordHash(),question;long matchCount,rankCount;
        try(var c=AdminStore.connect()){question=(String)AdminStore.rows(c,"SELECT text FROM game_questions ORDER BY id LIMIT 1").get(0).get("text");matchCount=((Number)AdminStore.rows(c,"SELECT COUNT(*) AS n FROM matches").get(0).get("n")).longValue();rankCount=((Number)AdminStore.rows(c,"SELECT COUNT(*) AS n FROM player_ranks").get(0).get("n")).longValue();}
        try{
            var source=cloneMap(runtime);source.put("message","Cấu hình từ file tải lên");((Map<String,Object>)source.get("enabled")).put("WHEEL",false);
            ((Map<String,Object>)((Map<String,Object>)source.get("defaults")).get("ARENA")).put("mainPlayerTimeLimit",23);
            var ranked=cloneMap(race);ranked.put("timeLimit",19);ranked.put("dailyCorrect",6);ranked.put("shieldReward",2);ranked.put("dailyEnabled",false);
            long expires=System.currentTimeMillis()+900000;
            String name=configFixture(snapshot,Map.of("runtime",source,"race_settings",ranked,"announcement",Map.of("enabled",true,"text","Thông báo từ file <img src=x>","expiresAt",expires),"unrecognized",Map.of("secret","ignored")));
            String path="/api/admin/backup-config?name="+name;
            check(request(path,null,null,null,null).statusCode()==401,"backup config preview requires admin");
            check(request(path,"session_id="+sessions.createSession("owner"),null,null,null).statusCode()==401,"players cannot preview backup configuration");
            check(request("/api/admin/backup-config?name=../../game.db",cookie,null,null,null).statusCode()==400,"configuration preview rejects path traversal");
            var preview=get("/backup-config?name="+name);var values=(Map<String,Object>)preview.get("values");
            check(values.size()==3&&!values.containsKey("unrecognized"),"preview contains only supported configuration groups");
            check(((Map<String,Object>)values.get("race_settings")).get("timeLimit").equals(19),"preview reads uploaded ranked settings");
            check(get("/settings").get("message").equals(runtime.get("message")),"preview does not change live configuration");
            Map<String,Object> apply=Map.of("name",name,"fingerprint",preview.get("fingerprint"));
            check(request("/api/admin/backup-config/apply",cookie,apply,null,null).statusCode()==403,"applying configuration requires CSRF");
            check(request("/api/admin/backup-config/apply",cookie,apply,csrf,"https://evil.example").statusCode()==403,"cross-origin configuration apply denied");
            check(request("/api/admin/backup-config/apply",cookie,Map.of("name",name,"fingerprint","0".repeat(64)),csrf,null).statusCode()==400,"unpreviewed or changed source fingerprint rejected");
            var result=post("/backup-config/apply",apply);String safety=(String)result.get("safetyBackup");
            check(AdminStore.backupFile(safety).length>100,"apply creates a recoverable backup before writing settings");
            check(((Map<String,Object>)((Map<String,Object>)get("/backup-config?name="+safety).get("values")).get("runtime")).get("message").equals(runtime.get("message")),"safety backup contains configuration from before apply");
            var actual=get("/settings");check(actual.get("message").equals(source.get("message"))&&!Boolean.TRUE.equals(((Map<?,?>)actual.get("enabled")).get("WHEEL")),"server configuration immediately reflects imported values");
            check(((Number)((Map<?,?>)((Map<?,?>)actual.get("defaults")).get("ARENA")).get("mainPlayerTimeLimit")).intValue()==23,"game defaults imported");
            var currentRace=get("/race/settings");check(((Number)currentRace.get("timeLimit")).intValue()==19&&((Number)currentRace.get("dailyCorrect")).intValue()==6&&Boolean.FALSE.equals(currentRace.get("dailyEnabled")),"rank and daily settings imported together");
            var currentNotice=get("/announcement");check(currentNotice.get("text").equals("Thông báo từ file <img src=x>")&&((Number)currentNotice.get("expiresAt")).longValue()==expires,"announcement imported without extending original expiration");
            check(users.findByUsername("operator").isAdmin()&&users.findByUsername("operator").getPasswordHash().equals(hash),"configuration import preserves live accounts and credentials");
            try(var c=AdminStore.connect()){check(AdminStore.rows(c,"SELECT text FROM game_questions ORDER BY id LIMIT 1").get(0).get("text").equals(question),"configuration import preserves live question content");check(AdminStore.rows(c,"SELECT key FROM app_settings WHERE key='unrecognized'").isEmpty(),"unknown settings are never imported");check(((Number)AdminStore.rows(c,"SELECT COUNT(*) AS n FROM matches").get(0).get("n")).longValue()==matchCount&&((Number)AdminStore.rows(c,"SELECT COUNT(*) AS n FROM player_ranks").get(0).get("n")).longValue()==rankCount,"configuration import preserves match history and rank records");}
            var broken=cloneMap(ranked);broken.put("timeLimit",1);var changed=cloneMap(source);changed.put("message","MUST NOT PARTIALLY APPLY");
            String bad=configFixture(snapshot,Map.of("runtime",changed,"race_settings",broken));
            check(request("/api/admin/backup-config?name="+bad,cookie,null,null,null).statusCode()==400,"invalid settings rejected in preview");
            check(request("/api/admin/backup-config/apply",cookie,Map.of("name",bad,"fingerprint","0".repeat(64)),csrf,null).statusCode()==400,"invalid source rejected by apply endpoint too");
            check(get("/settings").get("message").equals(source.get("message")),"invalid later group cannot partially apply earlier group");
            String transactional=configFixture(snapshot,Map.of("runtime",changed,"race_settings",ranked));var transactionalPreview=get("/backup-config?name="+transactional);
            try(var c=AdminStore.connect();var st=c.createStatement()){st.execute("CREATE TRIGGER fail_config_import BEFORE INSERT ON app_settings WHEN NEW.key='race_settings' BEGIN SELECT RAISE(ABORT,'fixture transaction failure'); END");}
            try{check(request("/api/admin/backup-config/apply",cookie,Map.of("name",transactional,"fingerprint",transactionalPreview.get("fingerprint")),csrf,null).statusCode()==503,"database error surfaces without claiming success");}
            finally{try(var c=AdminStore.connect();var st=c.createStatement()){st.execute("DROP TRIGGER fail_config_import");}}
            check(get("/settings").get("message").equals(source.get("message")),"database failure rolls back all configuration groups");
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+Path.of("data/backups",transactional));var ps=c.prepareStatement("UPDATE app_settings SET value=? WHERE key='runtime'")){ps.setString(1,JsonUtil.toJson(source));ps.executeUpdate();}
            check(request("/api/admin/backup-config/apply",cookie,Map.of("name",transactional,"fingerprint",transactionalPreview.get("fingerprint")),csrf,null).statusCode()==400,"source changed after preview cannot be applied");
            var expired=cloneMap(source);expired.put("maintenance",true);expired.put("maintenanceUntil",System.currentTimeMillis()-1000);
            String old=configFixture(snapshot,Map.of("runtime",expired,"announcement",Map.of("enabled",true,"text","Expired message","expiresAt",System.currentTimeMillis()-1000)));
            var oldPreview=get("/backup-config?name="+old);check(((List<?>)oldPreview.get("warnings")).size()==2,"expired maintenance and announcement explained in preview");
            post("/backup-config/apply",Map.of("name",old,"fingerprint",oldPreview.get("fingerprint")));
            check(Boolean.FALSE.equals(get("/settings").get("maintenance"))&&Boolean.FALSE.equals(get("/announcement").get("enabled")),"expired controls never reactivate when imported");
            check(((Number)get("/race/settings").get("timeLimit")).intValue()==19,"configuration groups absent from source retain current values");
            String empty=configFixture(snapshot,Map.of("content_seeded",true));check(request("/api/admin/backup-config?name="+empty,cookie,null,null,null).statusCode()==400,"file without supported configuration rejected");
            var maintenance=cloneMap(source);maintenance.put("maintenance",true);maintenance.put("maintenanceUntil",System.currentTimeMillis()+3000);
            String scheduled=configFixture(snapshot,Map.of("runtime",maintenance));var scheduledPreview=get("/backup-config?name="+scheduled);String player=sessions.createSession("guest");
            check(!((List<?>)scheduledPreview.get("warnings")).isEmpty(),"preview explains logout when imported maintenance is active");
            post("/backup-config/apply",Map.of("name",scheduled,"fingerprint",scheduledPreview.get("fingerprint")));
            check(sessions.getUsername(player)==null,"applying maintenance invokes player logout immediately");
            check(request("/auth/login",null,Map.of("username","guest","password","TestPlayerPass123"),null,null).statusCode()==503,"imported maintenance enforced by login endpoint");
            until(()->{try{return Boolean.FALSE.equals(AdminStore.maintenanceStatus().get("enabled"));}catch(Exception e){return false;}},"imported maintenance honors original expiration");
            check(items(get("/logs")).stream().anyMatch(row->row.get("action").equals("BACKUP_CONFIG_APPLY")),"configuration apply recorded in audit log");
        }finally{post("/settings",runtime);post("/race/settings",race);post("/announcement",Map.of("enabled",announcement.get("enabled"),"text",announcement.get("text"),"minutes",0));}
    }
    static HttpResponse<String> upload(byte[] data,String credential,String token,String origin,String type)throws Exception{
        var request=HttpRequest.newBuilder(URI.create(base+"/api/admin/backup-upload")).header("Content-Type",type).POST(HttpRequest.BodyPublishers.ofByteArray(data));
        if(credential!=null)request.header("Cookie",credential);if(token!=null)request.header("X-CSRF-Token",token);if(origin!=null)request.header("Origin",origin);
        return HTTP.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    static void backupUploads(byte[] snapshot)throws Exception{
        String binary="application/octet-stream";
        check(upload(snapshot,null,csrf,null,binary).statusCode()==401,"anonymous visitors cannot upload backups");
        check(upload(snapshot,"session_id="+sessions.createSession("owner"),csrf,null,binary).statusCode()==401,"players cannot upload backups");
        check(upload(snapshot,cookie,null,null,binary).statusCode()==403,"backup upload requires CSRF token");
        check(upload(snapshot,cookie,csrf,"https://evil.example",binary).statusCode()==403,"cross-origin backup upload rejected");
        check(upload(snapshot,cookie,csrf,null,"text/plain").statusCode()==415,"backup upload requires binary content type");
        check(upload(new byte[0],cookie,csrf,null,binary).statusCode()==400,"empty backup rejected");
        byte[] corrupt=new byte[1024];System.arraycopy("SQLite format 3\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,corrupt,0,16);
        check(upload(corrupt,cookie,csrf,null,binary).statusCode()==400,"corrupt database rejected even with SQLite header");
        Path fixture=Path.of("other-database.db");
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+fixture);Statement st=c.createStatement()){st.execute("CREATE TABLE unrelated(id INTEGER)");}
        check(upload(Files.readAllBytes(fixture),cookie,csrf,null,binary).statusCode()==400,"unrelated SQLite file rejected");
        var result=upload(snapshot,cookie,csrf,null,binary);check(result.statusCode()==200,"valid backup uploads: "+result.body());
        String name=(String)JsonUtil.parseObject(result.body()).get("name");check(name.startsWith("upload-"),"uploaded backup uses generated safe name");
        check(items(get("/backups")).stream().anyMatch(row->row.get("name").equals(name)),"uploaded backup listed");
        var downloaded=HTTP.send(HttpRequest.newBuilder(URI.create(base+"/api/admin/backup-file?name="+name)).header("Cookie",cookie).build(),HttpResponse.BodyHandlers.ofByteArray());
        check(downloaded.statusCode()==200&&Arrays.equals(downloaded.body(),snapshot),"uploaded backup downloads byte-for-byte unchanged");
        var again=upload(snapshot,cookie,csrf,null,binary);check(again.statusCode()==200&&!JsonUtil.parseObject(again.body()).get("name").equals(name),"uploading same file again never overwrites saved backup");
        Path large=Path.of("large-database.db");Files.write(large,snapshot);
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+large);Statement st=c.createStatement()){st.execute("CREATE TABLE upload_fixture(payload BLOB)");st.execute("INSERT INTO upload_fixture VALUES(zeroblob(2000000))");}
        check(upload(Files.readAllBytes(large),cookie,csrf,null,binary).statusCode()==200,"backup upload works above old 1.5 MB JSON request limit");
        URI uri=URI.create(base);
        try(Socket socket=new Socket(uri.getHost(),uri.getPort())){
            socket.setSoTimeout(5000);socket.getOutputStream().write(("POST /api/admin/backup-upload HTTP/1.1\r\nHost: "+uri.getAuthority()+"\r\nContent-Length: "+(AdminStore.MAX_BACKUP_UPLOAD_BYTES+1)+"\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            check(new BufferedReader(new InputStreamReader(socket.getInputStream())).readLine().contains("400"),"oversized upload rejected before reading file body");
        }
        try(var files=Files.list(Path.of("data/backups"))){check(files.noneMatch(path->path.toString().endsWith(".tmp")),"validation leaves no temporary uploaded files");}
        check(users.findByUsername("operator").verifyPassword(password),"upload preserves active accounts and database");
        check(items(get("/logs")).stream().anyMatch(row->row.get("action").equals("BACKUP_UPLOAD")),"backup upload audit recorded");
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
            overviewStats();get("/overview");get("/users");get("/rooms");get("/matches");idleSessions();hostPermissions();ownerWaitingDeletion();content();contentFilters();rankedQuestionEditor();settings();accountModeration();maintenanceControl();
            String backup=(String)post("/backup",Map.of()).get("name");check(!items(get("/backups")).isEmpty(),"backup listed");
            var bytes=HTTP.send(HttpRequest.newBuilder(URI.create(base+"/api/admin/backup-file?name="+backup)).header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
            check(bytes.statusCode()==200&&new String(bytes.body(),0,15,java.nio.charset.StandardCharsets.US_ASCII).equals("SQLite format 3"),"backup downloads a SQLite snapshot");
            backupUploads(bytes.body());backupConfigurationImports(bytes.body());
            check(request("/api/admin/backup-file?name=../../.env",cookie,null,null,null).statusCode()==400,"backup traversal blocked");
            check(items(get("/logs?q=HOST_KICK")).stream().anyMatch(r->r.get("action").equals("HOST_KICK")),"host kick audit persisted");
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
