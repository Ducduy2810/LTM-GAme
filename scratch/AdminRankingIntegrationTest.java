import vn.game.db.*;
import vn.game.games.*;
import vn.game.server.*;
import vn.game.util.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** Isolated administrator leaderboard and separated daily configuration tests. */
public class AdminRankingIntegrationTest {
    static int checks;static String base,cookie,csrf,player;static final HttpClient HTTP=HttpClient.newHttpClient();
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    static Map<String,Object> json(String text){return JsonUtil.parseObject(text);}
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object v){return (Map<String,Object>)v;}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> items(Map<String,Object> v){return (List<Map<String,Object>>)v.get("items");}
    static int n(Object value){return ((Number)value).intValue();}
    static void sql(Connection c,String text,Object...args)throws SQLException{try(PreparedStatement ps=c.prepareStatement(text)){for(int i=0;i<args.length;i++)ps.setObject(i+1,args[i]);ps.executeUpdate();}}
    static HttpResponse<String> request(String path,String session,Map<String,Object> body,String token)throws Exception{
        var b=HttpRequest.newBuilder(URI.create(base+path)).timeout(java.time.Duration.ofSeconds(15));if(session!=null)b.header("Cookie",session);if(token!=null)b.header("X-CSRF-Token",token);if(body!=null)b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JsonUtil.toJson(body)));return HTTP.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    static Map<String,Object> get(String path)throws Exception{var r=request("/api/admin"+path,cookie,null,null);check(r.statusCode()==200,"GET "+path+": "+r.body());return json(r.body());}
    static Map<String,Object> post(String path,Map<String,Object> body)throws Exception{var r=request("/api/admin"+path,cookie,body,csrf);check(r.statusCode()==200,"POST "+path+": "+r.body());return json(r.body());}
    static void seed()throws Exception{
        try(Connection c=AdminStore.connect()){
            c.setAutoCommit(false);
            for(int i=0;i<124;i++){
                String name=i<120?String.format("Board%03d",i):i==120?"locked_perm":i==121?"locked_temp":i==122?"unplayed":"operator";
                if(!name.equals("operator"))sql(c,"INSERT INTO users(username,role,blocked_until) VALUES(?,'user',?)",name,i==120?-1:i==121?System.currentTimeMillis()+3600000:0);
                int id=n(AdminStore.rows(c,"SELECT id FROM users WHERE username=?",name).get(0).get("id"));int rp=i<120?1000-i:i==120?5000:i==121?4000:9000;
                sql(c,"INSERT INTO player_ranks(user_id,current_tier,current_step,current_points,highest_tier,highest_step,highest_points,ranked_games,ranked_wins,updated_at) VALUES(?,'DIAMOND',14,?,'DIAMOND',14,?,?,?,?)",id,rp,rp+10,i==122?0:10,5,System.currentTimeMillis());
            }c.commit();
        }
    }
    static void leaderboard()throws Exception{
        check(request("/api/admin/leaderboard",null,null,null).statusCode()==401,"anonymous cannot inspect admin ranking");check(request("/api/admin/leaderboard",player,null,null).statusCode()==401,"player session cannot inspect admin ranking");
        var board=get("/leaderboard");check(n(board.get("total"))==120&&items(board).size()==50,"admin ranking includes all ranked players with 50-row pagination");
        var counts=map(board.get("counts"));check(n(counts.get("eligibleCount"))==120&&n(counts.get("blockedCount"))==2&&n(counts.get("rankedCount"))==122,"counts exclude administrator and unplayed accounts");
        check(items(board).get(0).get("username").equals("Board000")&&n(items(board).get(0).get("position"))==1,"ranking ordered by progress RP");check(items(board).get(0).get("title").equals("Huyền Thoại"),"elite title computed from official global position");
        var second=get("/leaderboard?offset=50");check(n(items(second).get(0).get("position"))==51,"page two keeps global positions");var third=get("/leaderboard?offset=100");check(items(third).size()==20&&n(items(third).get(0).get("position"))==101,"admin can see beyond public top 100");check(items(third).get(0).get("title").equals(""),"rank beyond top 100 has no elite title");
        var found=get("/leaderboard?q=Board105");check(items(found).size()==1&&n(items(found).get(0).get("position"))==106,"search does not renumber server rank");check(items(found).get(0).get("rankName").equals("Kim Cương 1")&&items(found).get(0).containsKey("highestRankName"),"current and highest rank included");
        var blocked=get("/leaderboard?status=blocked");check(items(blocked).size()==2&&items(blocked).stream().allMatch(r->r.get("position")==null&&Boolean.TRUE.equals(r.get("blocked"))&&r.get("title").equals("")),"blocked accounts inspectable without official position/title");
        var all=get("/leaderboard?status=all&offset=100");check(n(all.get("total"))==122&&items(all).size()==22,"all filter includes blocked accounts after official entries");
        check(n(get("/leaderboard?q=not_a_player").get("total"))==0,"empty search has zero total");check(items(get("/leaderboard?offset=9999")).isEmpty(),"out-of-range page is empty");check(n(get("/leaderboard?offset=-1").get("offset"))==0,"negative pagination clamped");
        check(request("/api/admin/leaderboard?status=invalid",cookie,null,null).statusCode()==400,"invalid status rejected");
        var publicBoard=RaceStore.leaderboard();check(publicBoard.size()==100,"public board remains top 100");var firstHundred=new ArrayList<Map<String,Object>>(items(board));firstHundred.addAll(items(second));for(int i=0;i<100;i++)check(publicBoard.get(i).get("username").equals(firstHundred.get(i).get("username")),"public/admin ranking consistency "+i);
        String encoded=URLEncoder.encode("%' OR 1=1 --",java.nio.charset.StandardCharsets.UTF_8);check(n(get("/leaderboard?q="+encoded).get("total"))==0,"search text cannot inject SQL");
    }
    static void configuration()throws Exception{
        Map<String,Object> original=RaceStore.settings();
        check(request("/api/admin/daily/settings",player,null,null).statusCode()==401,"player cannot read admin daily configuration");check(request("/api/admin/daily/settings",cookie,Map.of("dailyTimeLimit",9),null).statusCode()==403,"daily configuration requires CSRF");
        check(get("/daily/settings").keySet().equals(RaceStore.DAILY_SETTING_KEYS),"daily config endpoint exposes only daily keys");
        try{
            post("/daily/settings",Map.of("dailyTimeLimit",9,"dailyCorrect",9,"shieldReward",2,"doubleReward",3));var afterDaily=RaceStore.settings();check(n(afterDaily.get("timeLimit"))==15&&n(afterDaily.get("firstRP"))==25&&n(afterDaily.get("dailyTimeLimit"))==9,"daily changes preserve ranked settings");
            post("/race/rank-settings",Map.of("timeLimit",18));check(n(RaceStore.settings().get("dailyTimeLimit"))==9&&n(RaceStore.settings().get("shieldReward"))==2,"rank changes preserve current daily settings");
            check(request("/api/admin/race/rank-settings",cookie,Map.of("dailyTimeLimit",7),csrf).statusCode()==400,"rank config cannot overwrite daily keys");check(request("/api/admin/daily/settings",cookie,Map.of("firstRP",50),csrf).statusCode()==400,"daily config cannot overwrite rank rewards");
            var beforeInvalid=RaceStore.settings();check(request("/api/admin/daily/settings",cookie,Map.of("dailyTimeLimit",0),csrf).statusCode()==400,"invalid daily timer rejected");check(request("/api/admin/daily/settings",cookie,Map.of("dailyCorrect",11),csrf).statusCode()==400,"daily target capped at ten");check(request("/api/admin/daily/settings",cookie,Map.of("shieldReward",-1),csrf).statusCode()==400,"negative daily reward rejected");check(RaceStore.settings().equals(beforeInvalid),"invalid daily edits never commit");
            post("/race/service-settings",Map.of("enabled",false));check(Boolean.FALSE.equals(RaceStore.settings().get("enabled"))&&Boolean.TRUE.equals(RaceStore.settings().get("dailyEnabled")),"master switch independent of daily permission");post("/race/service-settings",Map.of("enabled",true));
            var one=CompletableFuture.runAsync(()->{try{post("/daily/settings",Map.of("dailyTimeLimit",12));}catch(Exception e){throw new CompletionException(e);}});var two=CompletableFuture.runAsync(()->{try{post("/race/rank-settings",Map.of("timeLimit",20));}catch(Exception e){throw new CompletionException(e);}});CompletableFuture.allOf(one,two).join();check(n(RaceStore.settings().get("timeLimit"))==20&&n(RaceStore.settings().get("dailyTimeLimit"))==12,"simultaneous rank and daily saves retain both edits");
            String match=RaceStore.create("DAILYQA","Daily admin reset fixture","DAILY",Map.of(),System.currentTimeMillis());RaceStore.join(match,"player",false,false);RaceStore.activate(match,Map.of(),true,System.currentTimeMillis());
            check(request("/api/admin/daily/reset",cookie,Map.of("targetUsername","player","reason","Check active attempt guard"),csrf).statusCode()==400,"cannot reset daily while account participates in active match");RaceStore.cancel(match,"End isolated daily fixture");
            check(request("/api/admin/daily/reset",cookie,Map.of("targetUsername","player","reason",""),csrf).statusCode()==400,"daily reset requires reason");post("/daily/reset",Map.of("targetUsername","player","reason","Isolated support override"));check(Boolean.FALSE.equals(map(RaceStore.account("player").get("daily")).get("used")),"daily reset reissues attempt after player exits");
            try(var c=AdminStore.connect()){check(!AdminStore.rows(c,"SELECT id FROM admin_audit_logs WHERE action='DAILY_SETTINGS'").isEmpty(),"daily config audited separately");check(!AdminStore.rows(c,"SELECT id FROM admin_audit_logs WHERE action='RACE_RESETDAILY'").isEmpty(),"daily support reset audited");}
        }finally{RaceStore.saveSettings("operator",original);}
    }
    public static void main(String[] args)throws Exception{
        if(!Files.exists(Path.of(".admin-ranking-test")))throw new IllegalStateException("Use isolated test_admin_ranking.ps1");DatabaseManager db=DatabaseManager.getInstance();db.init();UserRepository users=new UserRepository(db);AdminAccount.create(users,"operator","AdminOnlyPass123!");users.register("player","player@example.com","PlayerPass123");seed();SessionManager sessions=new SessionManager();player="session_id="+sessions.createSession("player");
        ArenaGame arena=new ArenaGame(users);WheelGame wheel=new WheelGame(users);SurvivalGame survival=new SurvivalGame(users);HttpAndWebSocketServer server=new HttpAndWebSocketServer(0,new File(args[0]),users,sessions,arena,wheel,survival);Thread t=new Thread(()->{try{server.start();}catch(IOException e){throw new RuntimeException(e);}});t.setDaemon(true);t.start();while(server.getPort()==0)Thread.sleep(10);base="http://localhost:"+server.getPort();
        try{
            var login=request("/api/admin/login",null,Map.of("username","operator","password","AdminOnlyPass123!"),null);check(login.statusCode()==200,"dedicated admin login");cookie=login.headers().firstValue("set-cookie").orElseThrow().split(";",2)[0];csrf=(String)json(login.body()).get("csrfToken");leaderboard();configuration();System.out.println("PASS: "+checks+" administrator ranking, pagination, privacy and isolated daily/rank configuration checks.");
            if(args.length>1&&args[1].equals("preview")){System.out.println("PREVIEW_URL="+base);long end=System.currentTimeMillis()+900000;while(!Files.exists(Path.of("stop-preview"))&&System.currentTimeMillis()<end)Thread.sleep(200);}
        }finally{server.stop();arena.close();wheel.close();survival.close();db.close();t.join(2000);}
    }
}
