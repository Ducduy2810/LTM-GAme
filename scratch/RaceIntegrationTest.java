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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

public class RaceIntegrationTest {
    static int checks;static UserRepository users;static String base,adminCookie,adminCsrf;static final HttpClient HTTP=HttpClient.newHttpClient();
    static void check(boolean v,String message){if(!v)throw new AssertionError(message);checks++;}
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> list(Object value){return (List<Map<String,Object>>)value;}
    static int num(Object value){return ((Number)value).intValue();}
    static Map<String,Object> obj(String json){return JsonUtil.parseObject(json);}
    static class Client extends WebSocketClient {
        final List<Map<String,Object>> packets=new ArrayList<>();
        Client(String name){super(new Socket(),InputStream.nullInputStream(),OutputStream.nullOutputStream(),name);}
        public void send(String text){packets.add(obj(text));}public boolean isClosed(){return false;}
        Map<String,Object> last(String type){return packets.stream().filter(p->type.equals(p.get("type"))).reduce((a,b)->b).orElseThrow(()->new AssertionError("Missing "+type+" for "+getUsername()));}
        Map<String,Object> state(){return last("race_state");}
        long errors(){return packets.stream().filter(p->p.get("type").equals("race_error")).count();}
    }
    static void act(KnowledgeRaceGame game,Client client,String type,Map<String,Object> extra){var req=new HashMap<>(extra);req.put("type",type);game.handle(client,req);}
    static Map<String,Object> rank(String user)throws Exception{return map(RaceStore.account(user).get("current"));}
    static void setRank(KnowledgeRaceGame g,String name,int step,int rp)throws Exception{g.adminAction("operator",Map.of("action","setRank","targetUsername",name,"step",step,"rp",rp,"reason","Isolated rank boundary fixture"));}
    static int correct(String match,int round)throws Exception{try(Connection c=AdminStore.connect()){var row=AdminStore.rows(c,"SELECT snapshot_json FROM race_rounds WHERE match_id=? AND round_no=?",match,round).get(0);return num(obj((String)row.get("snapshot_json")).get("correct"));}}
    static void answer(KnowledgeRaceGame g,Client p,int answer){var s=p.state();act(g,p,"race_answer",Map.of("matchId",s.get("matchId"),"questionToken",map(s.get("question")).get("token"),"answer",answer));}
    static long count(String table)throws Exception{try(Connection c=AdminStore.connect()){return ((Number)AdminStore.rows(c,"SELECT COUNT(*) AS n FROM "+table).get(0).get("n")).longValue();}}
    static void rules(){
        check(RankRules.advance(new RankRules.Position(0,90),25).equals(new RankRules.Position(1,15)),"division promotion keeps overflow");
        check(RankRules.advance(new RankRules.Position(2,95),25).equals(new RankRules.Position(3,40)),"new major tier grants 20 buffer plus overflow");
        check(RankRules.advance(new RankRules.Position(3,5),-15).equals(new RankRules.Position(3,0)),"first loss at positive RP stops at zero");
        check(RankRules.advance(new RankRules.Position(3,0),-10).equals(new RankRules.Position(2,70)),"only losing from zero demotes with 70");
        check(RankRules.advance(new RankRules.Position(0,0),-15).equals(new RankRules.Position(0,0)),"Bronze 3 floor");
        check(RankRules.advance(new RankRules.Position(14,98),50).equals(new RankRules.Position(14,148)),"Diamond 1 continues accumulating");
        check(RankRules.tiedReward(2,0,2)==13,"duel tie averages reward rounded in player favor");check(RankRules.tiedReward(4,2,2)==-12,"negative tie rounding");
        check(RankRules.title(14,1).equals("Huyền Thoại")&&RankRules.title(14,100).equals("Cao Thủ")&&RankRules.title(13,1).isEmpty()&&RankRules.title(14,101).isEmpty(),"elite title gate and boundaries");
        for(int step=0;step<15;step++){var p=new RankRules.Position(step,0);check(p.name().endsWith(String.valueOf(3-step%3)),"division label "+step);}
    }
    static void direct()throws Exception{
        AtomicLong now=new AtomicLong(System.currentTimeMillis());KnowledgeRaceGame g=new KnowledgeRaceGame(now::get);
        Client[] p={new Client("racer0"),new Client("racer1"),new Client("racer2"),new Client("racer3")};
        try{
            var settings=RaceStore.settings();check(num(settings.get("timeLimit"))==15&&num(settings.get("queueSeconds"))==60&&num(settings.get("dailyTimeLimit"))==7,"requested defaults 60s queue, 15s rank, 7s daily");
            check(RaceStore.questions(true,10).size()==10,"ten distinct HARD networking questions available");
            setRank(g,"racer0",2,95);setRank(g,"racer1",2,80);setRank(g,"racer2",3,5);setRank(g,"racer3",3,0);
            long ledgers=count("rank_ledger");for(Client c:p)act(g,c,"race_queue",Map.of("mode","RANKED","questionCount",1,"timeLimit",1,"score",99999));
            var initial=p[0].state();check(initial.get("phase").equals("QUESTION"),"four human players start immediately");check(num(initial.get("seconds"))==15&&num(initial.get("questionCount"))==10,"client cannot override ranked timing or question count");check(list(initial.get("players")).size()==4,"four-member shared ranked match");
            String id=(String)initial.get("matchId");check(list(initial.get("players")).stream().noneMatch(r->Boolean.TRUE.equals(r.get("bot"))),"full PvP contains no bot");check(!map(initial.get("question")).containsKey("correct")&&!initial.containsKey("solution"),"live state does not expose answer or explanation");
            Client outsider=new Client("observer");long errors=outsider.errors();act(g,outsider,"race_answer",Map.of("matchId",id,"questionToken",map(initial.get("question")).get("token"),"answer",0));check(outsider.errors()==errors+1,"outsider cannot submit to match");
            boolean blocked=false;try{RaceStore.review("racer0",id,false);}catch(IllegalArgumentException expected){blocked=true;}check(blocked,"active match answers cannot be fetched through review API");
            long beforeErrors=p[0].errors();act(g,p[0],"race_queue",Map.of("mode","RANKED"));check(p[0].errors()==beforeErrors+1,"same account cannot enter two matches");
            for(int round=1;round<=10;round++){
                int right=correct(id,round);String token=(String)map(p[0].state().get("question")).get("token");
                for(int i=0;i<4;i++){now.addAndGet(10);answer(g,p[i],round<=(i==0?10:i==1?8:i==2?5:2)?right:(right+1)%4);if(i==0){long n=p[i].errors();answer(g,p[i],right);check(p[i].errors()==n+1,"duplicate answer rejected round "+round);}}
                check(p[0].state().get("phase").equals("RESULT"),"all answered resolves round "+round);check(num(list(p[0].state().get("players")).get(0).get("score"))==round*3,"fastest correct gets three points round "+round);
                check(map(p[0].state().get("solution")).get("explanation").toString().length()>20,"theory feedback available after resolution");
                now.addAndGet(2300);g.tick();if(round<10){long n=p[0].errors();act(g,p[0],"race_answer",Map.of("matchId",id,"questionToken",token,"answer",right));check(p[0].errors()==n+1,"stale round token rejected");}
            }
            check(p[0].state().get("phase").equals("COMPLETED"),"match completes");check(count("rank_ledger")==ledgers+4,"exactly one RP ledger entry per human");
            check(num(rank("racer0").get("step"))==3&&num(rank("racer0").get("rp"))==40,"winning crosses tier with buffer");check(num(rank("racer1").get("rp"))==90,"second earns ten RP");
            check(num(rank("racer2").get("step"))==3&&num(rank("racer2").get("rp"))==0,"third loses ten without immediate demotion");check(num(rank("racer3").get("step"))==2&&num(rank("racer3").get("rp"))==70,"fourth loss at zero demotes");
            var peak=map(RaceStore.account("racer3").get("highest"));check(num(peak.get("step"))==3,"highest rank preserved after demotion");
            RaceStore.finish(id,List.of());check(count("rank_ledger")==ledgers+4&&num(rank("racer0").get("rp"))==40,"duplicate settlement cannot award RP twice");
            var review=RaceStore.review("racer2",id,false);check(list(review.get("questions")).stream().filter(r->Boolean.FALSE.equals(r.get("correct"))).count()==5,"review includes exactly the player's five wrong questions");
            blocked=false;try{RaceStore.review("observer",id,false);}catch(IllegalArgumentException expected){blocked=true;}check(blocked,"other account cannot read private review");
            // Reconnect replaces socket ownership, while disconnect alone never fabricates results.
            Client solo=new Client("observer");act(g,solo,"race_queue",Map.of("mode","RANKED"));now.addAndGet(59000);g.tick();check(solo.state().get("phase").equals("QUEUED"),"no bots before 60 seconds");
            Client wrongMode=new Client("observer");act(g,wrongMode,"race_resume",Map.of("mode","CASUAL"));check(wrongMode.state().get("phase").equals("OTHER_MODE")&&wrongMode.state().get("activeMode").equals("RANKED"),"casual entry does not take over an existing ranked queue");
            now.addAndGet(100);g.tick();check(solo.state().get("phase").equals("QUEUED"),"original ranked queue still belongs to original client");
            g.disconnect(solo);Client replacement=new Client("observer");act(g,replacement,"race_resume",Map.of("mode","RANKED"));check(replacement.state().get("phase").equals("QUEUED"),"queued account reconnects within grace period");
            now.addAndGet(1100);g.tick();check(replacement.state().get("phase").equals("QUESTION"),"fills bots after 60 seconds");check(list(replacement.state().get("players")).stream().filter(r->Boolean.TRUE.equals(r.get("bot"))).count()==3,"fallback bots explicitly marked");
            long n=solo.errors();act(g,solo,"race_answer",Map.of("matchId",replacement.state().get("matchId"),"questionToken",map(replacement.state().get("question")).get("token"),"answer",0));check(solo.errors()==n+1,"replaced socket cannot answer");String fallback=(String)replacement.state().get("matchId");g.adminAction("operator",Map.of("action","cancel","matchId",fallback,"reason","Isolated cancellation"));check(replacement.state().get("phase").equals("CANCELLED"),"admin cancels ranked match");check(RaceStore.history("observer",false).get(0).get("status").equals("CANCELLED"),"cancel persisted");
            casual(g,now);casualMatchmaking(g,now);daily(g,now);cards(g,now);configAndForfeits(g,now);timeouts(g,now);
        }finally{g.close();}
    }
    static void casual(KnowledgeRaceGame g,AtomicLong now)throws Exception{
        Client owner=new Client("host"),guest=new Client("guest"),stranger=new Client("observer");int rp=num(rank("host").get("rp"));
        act(g,owner,"race_create",Map.of("roomName","<img onerror=bad>","questionCount",10,"timeLimit",3));String code=(String)owner.state().get("roomCode");act(g,guest,"race_join",Map.of("roomCode",code));
        long errors=guest.errors();act(g,guest,"race_delete",Map.of());check(guest.errors()==errors+1,"guest cannot delete room");act(g,owner,"race_kick",Map.of("targetUsername","guest"));check(guest.last("race_kicked").get("message").toString().contains("Chủ phòng"),"host kick provides reason");
        errors=guest.errors();act(g,guest,"race_join",Map.of("roomCode",code));check(guest.errors()==errors+1,"kicked account cannot rejoin waiting room");
        act(g,owner,"race_delete",Map.of());check(owner.state().get("phase").equals("CANCELLED"),"owner deletes waiting room");
        act(g,owner,"race_create",Map.of("withBots",true,"botCount",2,"questionCount",10,"timeLimit",3));String id=(String)owner.state().get("matchId");
        long modeErrors=guest.errors();act(g,guest,"race_join",Map.of("roomCode",owner.state().get("roomCode"),"mode","CASUAL"));check(guest.errors()==modeErrors+1,"casual entry cannot join bot room by code");
        act(g,guest,"race_join",Map.of("roomCode",owner.state().get("roomCode")));act(g,owner,"race_start",Map.of());
        Client otherMode=new Client("host");act(g,otherMode,"race_resume",Map.of("mode","CASUAL"));check(otherMode.state().get("phase").equals("OTHER_MODE")&&otherMode.state().get("activeMode").equals("BOT"),"casual entry cannot steal active bot match");
        errors=owner.errors();act(g,owner,"race_delete",Map.of());check(owner.errors()==errors+1,"owner cannot delete active game");errors=stranger.errors();act(g,stranger,"race_kick",Map.of("targetUsername","guest"));check(stranger.errors()==errors+1,"outsider cannot kick");
        act(g,owner,"race_kick",Map.of("targetUsername","guest"));check(guest.last("race_kicked")!=null,"host can kick during casual game");
        for(int r=1;r<=10;r++){now.addAndGet(1);answer(g,owner,correct(id,r));now.addAndGet(3100);g.tick();now.addAndGet(2300);g.tick();}
        check(owner.state().get("phase").equals("COMPLETED"),"bot practice completes");check(num(rank("host").get("rp"))==rp,"practice never changes rank");
        act(g,otherMode,"race_resume",Map.of("mode","CASUAL"));check(otherMode.state().get("phase").equals("IDLE"),"old completed bot match does not replace casual setup");
    }
    static void daily(KnowledgeRaceGame g,AtomicLong now)throws Exception{
        Client player=new Client("daily");act(g,player,"race_daily",Map.of());String id=(String)player.state().get("matchId");check(num(player.state().get("seconds"))==7,"daily configured short timer");
        for(int r=1;r<=10;r++){int right=correct(id,r);answer(g,player,r<=8?right:(right+1)%4);now.addAndGet(2300);g.tick();}
        check(player.state().get("phase").equals("COMPLETED"),"daily completes ten hard questions");var cards=map(RaceStore.account("daily").get("cards"));check(num(cards.get("shields"))==1&&num(cards.get("doubles"))==1,"8/10 grants both reward cards once");
        RaceStore.finish(id,List.of());check(num(map(RaceStore.account("daily").get("cards")).get("shields"))==1,"daily reward idempotent");check(num(rank("daily").get("rp"))==0,"daily does not affect RP");
        long errors=player.errors();act(g,player,"race_daily",Map.of());check(player.errors()==errors+1,"second daily start rejected");
        g.adminAction("operator",Map.of("action","resetDaily","targetUsername","daily","reason","Isolated staff override"));act(g,player,"race_daily",Map.of());check(player.state().get("phase").equals("QUESTION"),"admin can reissue daily attempt");g.adminAction("operator",Map.of("action","cancel","matchId",player.state().get("matchId"),"reason","Fixture complete"));
        check(Boolean.TRUE.equals(map(RaceStore.account("daily").get("daily")).get("used")),"cancelled daily still consumes daily attempt");
    }
    static void casualMatchmaking(KnowledgeRaceGame g,AtomicLong now)throws Exception{
        Client[] players={new Client("host"),new Client("guest"),new Client("daily"),new Client("racer0")};
        Map<String,Map<String,Object>> before=new HashMap<>();for(Client p:players)before.put(p.getUsername(),RaceStore.account(p.getUsername()));long ledger=count("rank_ledger");
        act(g,players[0],"race_queue",Map.of("mode","CASUAL","useShield",true,"useDouble",true));act(g,players[1],"race_queue",Map.of("mode","CASUAL"));
        Client rankPeer=new Client("observer");act(g,rankPeer,"race_queue",Map.of("mode","RANKED"));check(players[0].state().get("phase").equals("QUEUED"),"casual and ranked queues do not combine players");
        act(g,players[2],"race_queue",Map.of("mode","CASUAL"));act(g,players[3],"race_queue",Map.of("mode","CASUAL"));
        check(players[0].state().get("phase").equals("QUESTION")&&players[0].state().get("mode").equals("CASUAL"),"four casual players start an automatic casual match");
        check(rankPeer.state().get("phase").equals("QUEUED"),"ranked player remains in separate queue");act(g,rankPeer,"race_cancel_queue",Map.of());
        String id=(String)players[0].state().get("matchId");check(num(players[0].state().get("seconds"))==15,"automatic casual match uses server-configured timer");
        for(int round=1;round<=10;round++){int right=correct(id,round);for(Client p:players)answer(g,p,right);now.addAndGet(2300);g.tick();}
        check(players[0].state().get("phase").equals("COMPLETED"),"automatic casual PvP completes");check(count("rank_ledger")==ledger,"casual matchmaking writes no RP ledger");
        for(Client p:players){var after=RaceStore.account(p.getUsername());var old=before.get(p.getUsername());check(after.get("current").equals(old.get("current"))&&after.get("highest").equals(old.get("highest")),"casual PvP preserves current/best rank "+p.getUsername());check(after.get("cards").equals(old.get("cards"))&&after.get("games").equals(old.get("games")),"casual PvP uses no cards or ranked game stats "+p.getUsername());}
        var previous=RaceStore.account("observer");act(g,rankPeer,"race_queue",Map.of("mode","CASUAL"));now.addAndGet(59000);g.tick();check(rankPeer.state().get("phase").equals("QUEUED"),"casual fallback waits full 60 seconds");now.addAndGet(1000);g.tick();check(rankPeer.state().get("phase").equals("QUESTION")&&list(rankPeer.state().get("players")).stream().filter(p->Boolean.TRUE.equals(p.get("bot"))).count()==3,"casual fallback fills exactly three bots after 60 seconds");
        id=(String)rankPeer.state().get("matchId");for(int round=1;round<=10;round++){answer(g,rankPeer,correct(id,round));now.addAndGet(14000);g.tick();now.addAndGet(2300);g.tick();}
        check(rankPeer.state().get("phase").equals("COMPLETED"),"casual fallback match completes");var after=RaceStore.account("observer");check(previous.get("current").equals(after.get("current"))&&previous.get("cards").equals(after.get("cards")),"winning fallback casual match changes neither RP nor cards");
    }
    static void cards(KnowledgeRaceGame g,AtomicLong now)throws Exception{
        Client[] p={new Client("racer0"),new Client("racer1"),new Client("racer2"),new Client("racer3")};
        for(Client c:p)g.adminAction("operator",Map.of("action","setCards","targetUsername",c.getUsername(),"shields",2,"doubles",2,"reason","Isolated inventory fixture"));
        int before=num(rank("racer0").get("total"));int beforeLoser=num(rank("racer3").get("total"));
        for(Client c:p)act(g,c,"race_queue",Map.of("mode","RANKED","useShield",true,"useDouble",true));String id=(String)p[0].state().get("matchId");
        long errors=p[0].errors();try{g.adminAction("operator",Map.of("action","setRank","targetUsername","racer0","step",0,"rp",0,"reason","Reject active mutation"));throw new AssertionError("active admin edit accepted");}catch(IllegalArgumentException expected){checks++;}
        for(int r=1;r<=10;r++){int right=correct(id,r);for(int i=0;i<4;i++)answer(g,p[i],r<=10-i*2?right:(right+1)%4);now.addAndGet(2300);g.tick();}
        var winner=map(RaceStore.account("racer0").get("cards"));var loser=map(RaceStore.account("racer3").get("cards"));check(num(rank("racer0").get("total"))==before+50,"x2 applies to positive RP");check(num(winner.get("doubles"))==1&&num(winner.get("shields"))==2,"win consumes only double card");check(num(loser.get("shields"))==1&&num(loser.get("doubles"))==2,"loss consumes only shield");check(num(rank("racer3").get("total"))==beforeLoser,"shield prevents RP loss and demotion");
    }
    static void timeouts(KnowledgeRaceGame g,AtomicLong now)throws Exception{
        Client[] p={new Client("racer0"),new Client("racer1"),new Client("racer2"),new Client("racer3")};int total=num(rank("racer0").get("total"));
        for(Client c:p)act(g,c,"race_queue",Map.of("mode","RANKED"));String id=(String)p[0].state().get("matchId");
        now.addAndGet(15000);long n=p[0].errors();answer(g,p[0],correct(id,1));check(p[0].errors()==n+1,"answer exactly at deadline rejected");g.tick();check(p[0].state().get("phase").equals("RESULT"),"unanswered questions resolve at deadline");
        for(int r=1;r<=10;r++){if(r>1){now.addAndGet(15000);g.tick();}now.addAndGet(2300);g.tick();}
        check(p[0].state().get("phase").equals("COMPLETED"),"all AFK match completes");check(num(rank("racer0").get("total"))==total,"no free RP for zero-correct AFK tie");
        for(Client c:p)act(g,c,"race_queue",Map.of("mode","RANKED"));String interrupted=(String)p[0].state().get("matchId");long ledger=count("rank_ledger");RaceStore.recoverInterrupted();check(count("rank_ledger")==ledger,"restart recovery never fabricates RP results");check(RaceStore.history("racer0",false).stream().anyMatch(r->r.get("id").equals(interrupted)&&r.get("status").equals("INTERRUPTED")),"interrupted match persisted");
    }
    static void configAndForfeits(KnowledgeRaceGame g,AtomicLong now)throws Exception{
        Map<String,Object> original=RaceStore.settings(),duel=new HashMap<>(original);duel.put("targetPlayers",2);duel.put("firstRP",50);duel.put("timeLimit",18);
        Client a=new Client("host"),b=new Client("observer");RaceStore.saveSettings("operator",duel);
        int start=num(rank("host").get("total"));act(g,a,"race_queue",Map.of("mode","RANKED"));act(g,b,"race_queue",Map.of("mode","RANKED"));String id=(String)a.state().get("matchId");
        check(num(a.state().get("seconds"))==18&&list(a.state().get("players")).size()==2,"new admin rules affect new two-player rank");
        RaceStore.saveSettings("operator",original);check(num(a.state().get("seconds"))==18,"changing settings never changes active match timer");
        for(int r=1;r<=10;r++){int right=correct(id,r);answer(g,a,right);answer(g,b,(right+1)%4);now.addAndGet(2300);g.tick();}
        check(num(rank("host").get("total"))>=start+50,"active match uses captured 50 RP reward after global config reverted");
        check(num(map(list(a.state().get("players")).stream().filter(p->p.get("username").equals("observer")).findFirst().orElseThrow().get("result")).get("rpDelta"))==0,"second place in a duel gets zero RP");
        RaceStore.saveSettings("operator",duel);int before=num(rank("host").get("total"));act(g,a,"race_queue",Map.of("mode","RANKED"));act(g,b,"race_queue",Map.of("mode","RANKED"));String left=(String)a.state().get("matchId");
        act(g,a,"race_leave",Map.of());long n=a.errors();act(g,a,"race_queue",Map.of("mode","RANKED"));check(a.errors()==n+1,"forfeiter cannot queue again before settlement");act(g,b,"race_leave",Map.of());
        for(int r=0;r<10;r++){g.tick();now.addAndGet(2300);g.tick();}
        check(num(rank("host").get("total"))==before,"all-forfeit tie cannot award positive RP");Client resumed=new Client("host");act(g,resumed,"race_resume",Map.of());check(resumed.state().get("phase").equals("COMPLETED"),"forfeited player can resume completed result");act(g,resumed,"race_leave",Map.of());check(resumed.state().get("phase").equals("IDLE"),"forfeited completed result can return to menu");
        RaceStore.saveSettings("operator",original);
        var disabled=new HashMap<>(original);disabled.put("rankedEnabled",false);RaceStore.saveSettings("operator",disabled);n=resumed.errors();act(g,resumed,"race_queue",Map.of("mode","RANKED"));check(resumed.errors()==n+1,"admin can close ranked queue");RaceStore.saveSettings("operator",original);
        var invalid=new HashMap<>(original);invalid.put("timeLimit",0);boolean rejected=false;try{RaceStore.saveSettings("operator",invalid);}catch(IllegalArgumentException expected){rejected=true;}check(rejected&&num(RaceStore.settings().get("timeLimit"))==15,"invalid setting rejected without changing active configuration");
        check(RaceStore.today(java.time.Instant.parse("2026-10-30T16:59:59Z").toEpochMilli()).equals("2026-10-30")&&RaceStore.today(java.time.Instant.parse("2026-10-30T17:00:00Z").toEpochMilli()).equals("2026-10-31"),"daily day resets at midnight Vietnam time");
        try(var c=AdminStore.connect()){check(!AdminStore.rows(c,"SELECT id FROM admin_audit_logs WHERE action='RACE_SETTINGS'").isEmpty(),"rank settings audited");check(!AdminStore.rows(c,"SELECT id FROM admin_audit_logs WHERE action='RACE_SETCARDS'").isEmpty(),"inventory changes audited");}
    }
    static HttpResponse<String> request(String path,String cookie,Map<String,Object> body,String csrf)throws Exception{
        var b=HttpRequest.newBuilder(URI.create(base+path)).timeout(java.time.Duration.ofSeconds(10));if(cookie!=null)b.header("Cookie",cookie);if(csrf!=null)b.header("X-CSRF-Token",csrf);if(body!=null)b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JsonUtil.toJson(body)));return HTTP.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    static String login(String name)throws Exception{var r=request("/auth/login",null,Map.of("username",name,"password","PlayerPass123"),null);check(r.statusCode()==200,"real player login "+name);return r.headers().firstValue("set-cookie").orElseThrow().split(";",2)[0];}
    static class Peer implements java.net.http.WebSocket.Listener {
        final List<Map<String,Object>> packets=new CopyOnWriteArrayList<>();StringBuilder buf=new StringBuilder();java.net.http.WebSocket socket;
        public void onOpen(java.net.http.WebSocket w){socket=w;w.request(1);}public CompletionStage<?> onText(java.net.http.WebSocket w,CharSequence text,boolean last){buf.append(text);if(last){packets.add(obj(buf.toString()));buf.setLength(0);}w.request(1);return CompletableFuture.completedFuture(null);}
        void send(String type,Map<String,Object> extra){var p=new HashMap<>(extra);p.put("type",type);socket.sendText(JsonUtil.toJson(p),true).join();}
        Map<String,Object> state(){return packets.stream().filter(p->p.get("type").equals("race_state")).reduce((a,b)->b).orElse(Map.of());}
    }
    static void until(BooleanSupplier condition,String message)throws Exception{long end=System.currentTimeMillis()+5000;while(!condition.getAsBoolean()&&System.currentTimeMillis()<end)Thread.sleep(20);check(condition.getAsBoolean(),message);}
    static void live(HttpAndWebSocketServer server)throws Exception{
        var admin=request("/api/admin/login",null,Map.of("username","operator","password","AdminOnlyPass123!"),null);check(admin.statusCode()==200,"admin logs in");adminCookie=admin.headers().firstValue("set-cookie").orElseThrow().split(";",2)[0];adminCsrf=(String)obj(admin.body()).get("csrfToken");
        check(request("/api/race/overview",null,null,null).statusCode()==401,"race data requires authentication");String player=login("racer0");check(request("/api/admin/race",player,null,null).statusCode()==401,"player cannot inspect admin queues");
        var overview=obj(request("/api/race/overview",player,null,null).body());check(overview.containsKey("leaderboard")&&overview.containsKey("account"),"player overview includes ranked profile and board");
        var config=map(obj(request("/api/admin/race",adminCookie,null,null).body()).get("settings"));check(request("/api/admin/race/settings",adminCookie,config,null).statusCode()==403,"rank administration requires CSRF");
        var peers=new ArrayList<Peer>();try{
            for(int i=0;i<4;i++){Peer p=new Peer();HTTP.newWebSocketBuilder().header("Cookie",login("racer"+i)).buildAsync(URI.create(base.replace("http:","ws:")+"/ws"),p).join();peers.add(p);p.send("race_queue",Map.of("mode","RANKED"));}
            until(()->peers.stream().allMatch(p->p.state().getOrDefault("phase","").equals("QUESTION")),"four real WebSockets share question");String id=(String)peers.get(0).state().get("matchId");String token=(String)map(peers.get(0).state().get("question")).get("token");
            check(peers.stream().allMatch(p->map(p.state().get("question")).get("token").equals(token)),"all clients receive same opaque question token");
            check(request("/api/race/review?id="+id,player,null,null).statusCode()==400,"HTTP cannot leak active question answer");
            java.util.concurrent.atomic.AtomicBoolean recovered=new java.util.concurrent.atomic.AtomicBoolean();HttpAndWebSocketServer collision=new HttpAndWebSocketServer(server.getPort(),new File("."),users,new SessionManager(),null,null,null);
            try{collision.start(()->recovered.set(true));throw new AssertionError("Second server unexpectedly bound same port");}catch(BindException expected){checks++;}finally{collision.stop();}
            check(!recovered.get(),"failed second server cannot run match recovery against live server");
            int right=correct(id,1);for(Peer p:peers)p.send("race_answer",Map.of("matchId",id,"questionToken",token,"answer",right));until(()->peers.stream().allMatch(p->p.state().get("phase").equals("RESULT")),"real answer submissions resolve shared scoreboard");
            var scores=list(peers.get(0).state().get("players"));check(scores.stream().filter(p->num(p.get("score"))==3).count()==1&&scores.stream().filter(p->num(p.get("score"))==2).count()==3,"exactly one fastest correct plus three later correct");
            check(request("/api/admin/race/action",adminCookie,Map.of("action","cancel","matchId",id,"reason","Live protocol test"),adminCsrf).statusCode()==200,"admin cancels real ranked match");until(()->peers.stream().allMatch(p->p.state().get("phase").equals("CANCELLED")),"admin cancellation broadcasts to every real client");
            check(request("/api/race/review?id="+id,login("observer"),null,null).statusCode()==400,"HTTP review isolated per account");
            var changed=new HashMap<>(config);changed.put("timeLimit",18);check(request("/api/admin/race/settings",adminCookie,changed,adminCsrf).statusCode()==200,"admin can change ranked question timer");
            check(num(RaceStore.settings().get("timeLimit"))==18,"ranked config persisted");RaceStore.saveSettings("operator",config);
        }finally{for(Peer p:peers)if(p.socket!=null)p.socket.abort();}
    }
    public static void main(String[] args)throws Exception{
        if(!Files.exists(Path.of(".race-test")))throw new IllegalStateException("Use test_race.ps1 isolated directory");DatabaseManager db=DatabaseManager.getInstance();db.init();users=new UserRepository(db);
        for(String name:List.of("racer0","racer1","racer2","racer3","host","guest","observer","daily"))users.register(name,name+"@example.com","PlayerPass123");AdminAccount.create(users,"operator","AdminOnlyPass123!");
        rules();direct();SessionManager sessions=new SessionManager();ArenaGame arena=new ArenaGame(users);WheelGame wheel=new WheelGame(users);SurvivalGame survival=new SurvivalGame(users);HttpAndWebSocketServer server=new HttpAndWebSocketServer(0,new File(args[0]),users,sessions,arena,wheel,survival);
        Thread t=new Thread(()->{try{server.start();}catch(IOException e){throw new RuntimeException(e);}});t.setDaemon(true);t.start();while(server.getPort()==0)Thread.sleep(10);base="http://localhost:"+server.getPort();
        try{live(server);System.out.println("PASS: "+checks+" ranked/casual/daily, RP, cards, reconnect, anti-replay, admin and real WebSocket checks.");if(args.length>1&&args[1].equals("preview")){System.out.println("PREVIEW_URL="+base);long end=System.currentTimeMillis()+1_200_000;while(!Files.exists(Path.of("stop-preview"))&&System.currentTimeMillis()<end)Thread.sleep(200);}}
        finally{server.stop();arena.close();wheel.close();survival.close();db.close();t.join(2000);}
    }
}
