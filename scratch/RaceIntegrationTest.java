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
    static List<Question> drawRankedQuestions(String tier,int count)throws Exception{
        List<Question> used=new ArrayList<>();while(used.size()<count)used.add(RaceStore.nextRankedQuestion(tier,count,used));return used;
    }
    static void rankedPools()throws Exception{
        String oldText;int oldId,oldRevision;
        try(var c=AdminStore.connect();var st=c.createStatement()){
            var legacy=AdminStore.rows(c,"SELECT id,text,revision FROM game_questions WHERE rank_tier='' ORDER BY id LIMIT 1").get(0);oldId=num(legacy.get("id"));oldText=(String)legacy.get("text");oldRevision=num(legacy.get("revision"));
            st.execute("DELETE FROM game_questions WHERE rank_tier<>''");st.execute("DELETE FROM app_settings WHERE key='rank_tier_questions_seeded_v1'");st.execute("ALTER TABLE game_questions DROP COLUMN rank_tier");
            try(var ps=c.prepareStatement("UPDATE game_questions SET text=?,revision=? WHERE id=?")){ps.setString(1,"Edited legacy question to preserve");ps.setInt(2,oldRevision+1);ps.setInt(3,oldId);ps.executeUpdate();}
        }
        AdminStore.init();RaceStore.init();
        try(var c=AdminStore.connect()){
            var preserved=AdminStore.rows(c,"SELECT text,revision,rank_tier FROM game_questions WHERE id=?",oldId).get(0);check(preserved.get("text").equals("Edited legacy question to preserve")&&num(preserved.get("revision"))==oldRevision+1&&preserved.get("rank_tier").equals(""),"rank bank migration preserves existing edited questions");
            try(var ps=c.prepareStatement("UPDATE game_questions SET text=?,revision=? WHERE id=?")){ps.setString(1,oldText);ps.setInt(2,oldRevision);ps.setInt(3,oldId);ps.executeUpdate();}
        }
        check(AdminStore.backups().stream().anyMatch(row->row.get("name").toString().startsWith("before-rank-pools-")),"existing database backed up before rank-pool migration");
        Set<Integer> rankedIds=new HashSet<>();Set<String> texts=new HashSet<>();Set<Integer> answerPositions=new HashSet<>();
        for(String tier:RankedQuestionBank.TIERS){
            var bank=drawRankedQuestions(tier,15);check(bank.size()==15,"rank pool initialized: "+tier);
            try(var c=AdminStore.connect()){
                for(Question q:bank){check(rankedIds.add(q.getId())&&texts.add(q.getText()),"rank pools have distinct question ids and texts");check(q.getOptions().size()==4&&new HashSet<>(q.getOptions()).size()==4&&!q.getExplanation().isBlank(),"seed question has four distinct choices and explanation");answerPositions.add(q.getCorrect());check(AdminStore.rows(c,"SELECT rank_tier FROM game_questions WHERE id=?",q.getId()).get(0).get("rank_tier").equals(tier),"question belongs to requested major tier");}
            }
        }
        check(rankedIds.size()==75&&answerPositions.size()>=3,"75 distinct seeded questions with varied answer positions");
        for(int count=10;count<=15;count++){
            double previous=0;
            for(String tier:RankedQuestionBank.TIERS){
                var questions=drawRankedQuestions(tier,count);Map<String,Integer> frequencies=new HashMap<>();for(Question q:questions)frequencies.merge(q.getDifficulty(),1,Integer::sum);
                check(frequencies.equals(RankedQuestionBank.requirements(tier,count)),"exact difficulty plan for "+tier+" at "+count+" questions");
                double mean=questions.stream().mapToInt(q->Map.of("EASY",1,"MEDIUM",2,"HARD",3).get(q.getDifficulty())).average().orElseThrow();check(mean>previous,"difficulty strictly rises between major tiers");previous=mean;
            }
        }
        check(RaceStore.questions(false,32).stream().noneMatch(q->rankedIds.contains(q.getId())),"casual and bot questions exclude ranked pools");
        check(RaceStore.questions(true,10).stream().allMatch(q->q.getDifficulty().equals("HARD")&&!rankedIds.contains(q.getId())),"daily remains HARD from shared pool only");
        long seeded=count("game_questions");RaceStore.init();check(count("game_questions")==seeded,"rank pool seeding is idempotent");
        var original=RaceStore.settings();var duel=new HashMap<>(original);duel.put("targetPlayers",2);RaceStore.saveSettings("operator",duel);
        AtomicLong now=new AtomicLong(System.currentTimeMillis());KnowledgeRaceGame game=new KnowledgeRaceGame(now::get);Client bronze=new Client("host"),silver=new Client("guest");
        try{
            setRank(game,"host",2,95);setRank(game,"guest",3,0);act(game,bronze,"race_queue",Map.of("mode","RANKED"));act(game,silver,"race_queue",Map.of("mode","RANKED"));
            check(bronze.state().get("phase").equals("QUEUED")&&silver.state().get("phase").equals("QUEUED"),"near-boundary Bronze and Silver do not share a ranked match");
            now.addAndGet(61000);game.tick();
            check(bronze.state().get("rankTier").equals("BRONZE")&&silver.state().get("rankTier").equals("SILVER"),"bot-filled rooms each retain their own rank pool");
            String bronzeId=(String)bronze.state().get("matchId"),silverId=(String)silver.state().get("matchId");check(!bronzeId.equals(silverId),"separate major tiers use separate rooms");
            try(var c=AdminStore.connect()){
                var config=obj((String)AdminStore.rows(c,"SELECT config_json FROM race_matches WHERE id=?",bronzeId).get(0).get("config_json"));check(config.get("rankTier").equals("BRONZE")&&map(config.get("difficultyPlan")).equals(Map.of("EASY",10)),"rank pool and difficulty plan persisted with match");
            }
            game.adminAction("operator",Map.of("action","cancel","matchId",bronzeId,"reason","Tier fixture cleanup"));game.adminAction("operator",Map.of("action","cancel","matchId",silverId,"reason","Tier fixture cleanup"));
            setRank(game,"host",2,95);act(game,bronze,"race_queue",Map.of("mode","RANKED"));
            try(var c=AdminStore.connect();var st=c.createStatement()){st.execute("UPDATE player_ranks SET current_step=3,current_tier='SILVER',current_points=0,highest_step=3,highest_tier='SILVER' WHERE user_id=(SELECT id FROM users WHERE username='host')");}
            act(game,silver,"race_queue",Map.of("mode","RANKED"));check(bronze.state().get("phase").equals("QUESTION")&&bronze.state().get("rankTier").equals("SILVER"),"queued rank refresh uses current tier after an older match settles");
            game.adminAction("operator",Map.of("action","cancel","matchId",bronze.state().get("matchId"),"reason","Tier refresh fixture cleanup"));
            setRank(game,"host",0,0);
            try(var c=AdminStore.connect();var st=c.createStatement()){st.execute("UPDATE game_questions SET status='ARCHIVED' WHERE rank_tier='BRONZE'");}
            try{
                RaceStore.init();check(count("game_questions")==seeded,"restart does not recreate archived ranked questions");
                boolean rejected=false;try{drawRankedQuestions("BRONZE",10);}catch(IllegalArgumentException e){rejected=e.getMessage().contains("Đồng");}check(rejected,"insufficient tier bank fails clearly instead of borrowing questions");
                long errors=bronze.errors();act(game,bronze,"race_queue",Map.of("mode","RANKED"));check(bronze.errors()==errors+1,"missing tier questions rejected before queue entry");check(drawRankedQuestions("SILVER",10).size()==10,"missing Bronze bank does not affect Silver");
            }finally{try(var c=AdminStore.connect();var st=c.createStatement()){st.execute("UPDATE game_questions SET status='PUBLISHED' WHERE rank_tier='BRONZE'");}}
        }finally{game.close();RaceStore.saveSettings("operator",original);}
    }
    static Map<String,Object> roundSnapshot(String match,int round)throws Exception{
        try(var c=AdminStore.connect()){return obj((String)AdminStore.rows(c,"SELECT snapshot_json FROM race_rounds WHERE match_id=? AND round_no=?",match,round).get(0).get("snapshot_json"));}
    }
    static int drawnCount(KnowledgeRaceGame game,String match)throws Exception{
        var field=KnowledgeRaceGame.class.getDeclaredField("rooms");field.setAccessible(true);Object room=((Map<?,?>)field.get(game)).get(match);var questions=room.getClass().getDeclaredField("questions");questions.setAccessible(true);return ((List<?>)questions.get(room)).size();
    }
    static void perRoundQuestionDraws()throws Exception{
        var original=RaceStore.settings();var duel=new HashMap<>(original);duel.put("targetPlayers",2);RaceStore.saveSettings("operator",duel);
        AtomicLong now=new AtomicLong(System.currentTimeMillis());KnowledgeRaceGame game=new KnowledgeRaceGame(now::get);
        List<Map<String,Object>> bank;try(var c=AdminStore.connect()){bank=AdminStore.rows(c,"SELECT id,text,options_json,correct,status,revision,updated_at FROM game_questions WHERE rank_tier='BRONZE'");}
        try{
            setRank(game,"host",0,50);setRank(game,"guest",0,50);Client first=new Client("host"),other=new Client("guest");long roundsBefore=count("race_rounds");
            act(game,first,"race_queue",Map.of("mode","RANKED"));check(count("race_rounds")==roundsBefore,"entering ranked queue only checks capacity; no question drawn");
            act(game,other,"race_queue",Map.of("mode","RANKED"));String id=(String)first.state().get("matchId");var initial=roundSnapshot(id,1);int firstId=num(initial.get("id"));String text=(String)initial.get("text"),token=(String)map(first.state().get("question")).get("token");
            check(drawnCount(game,id)==1&&num(first.state().get("questionCount"))==10,"opening rank match holds exactly one drawn question and total count ten");
            check(map(first.state().get("question")).equals(map(other.state().get("question"))),"both players receive the same one-question draw");
            check(!map(first.state().get("question")).containsKey("correct")&&!first.state().containsKey("questions"),"live packet exposes neither answer nor future questions");
            try(var c=AdminStore.connect();var st=c.createStatement()){
                st.execute("UPDATE game_questions SET status='ARCHIVED' WHERE rank_tier='BRONZE'");
                try(var ps=c.prepareStatement("UPDATE game_questions SET text='Changed after round opened',correct=? WHERE id=?")){ps.setInt(1,(num(initial.get("correct"))+1)%4);ps.setInt(2,firstId);ps.executeUpdate();}
                try(var ps=c.prepareStatement("INSERT INTO game_questions(text,options_json,correct,topic,difficulty,usage,rank_tier,explanation,status,updated_at) VALUES(?,'[\"A\",\"B\",\"C\",\"D\"]',0,'NETWORKING','EASY','RACE','BRONZE','On demand fixture','PUBLISHED',?)")){
                    for(int i=0;i<9;i++){ps.setString(1,"PER_ROUND_FIXTURE_"+i);ps.setLong(2,System.currentTimeMillis());ps.addBatch();}ps.executeBatch();
                }
            }
            game.disconnect(first);Client resumed=new Client("host");act(game,resumed,"race_resume",Map.of("mode","RANKED"));
            check(map(resumed.state().get("question")).get("text").equals(text)&&map(resumed.state().get("question")).get("token").equals(token),"reconnect keeps current snapshot after bank was edited");
            check(drawnCount(game,id)==1,"reconnect does not draw another question");
            answer(game,resumed,num(initial.get("correct")));answer(game,other,num(initial.get("correct")));check(resumed.state().get("phase").equals("RESULT")&&Boolean.TRUE.equals(map(resumed.state().get("solution")).get("correct")),"round uses original answer rather than admin edit");
            now.addAndGet(2300);game.tick();check(num(resumed.state().get("round"))==2&&map(resumed.state().get("question")).get("text").toString().startsWith("PER_ROUND_FIXTURE_"),"round two uses newly published live-bank question unavailable at match creation");
            check(drawnCount(game,id)==2,"only opened rounds have selected questions in memory");
            String secondToken=(String)map(resumed.state().get("question")).get("token");act(game,resumed,"race_resume",Map.of("mode","RANKED"));game.tick();check(map(resumed.state().get("question")).get("token").equals(secondToken)&&drawnCount(game,id)==2,"repeated resume and tick do not reroll current round");
            Set<Integer> ids=new HashSet<>();ids.add(firstId);
            for(int round=2;round<=10;round++){
                var question=roundSnapshot(id,round);check(ids.add(num(question.get("id"))),"on-demand question ids never repeat in a match");
                answer(game,resumed,num(question.get("correct")));answer(game,other,num(question.get("correct")));now.addAndGet(2300);game.tick();
            }
            check(resumed.state().get("phase").equals("COMPLETED")&&drawnCount(game,id)==10&&ids.size()==10,"on-demand match completes configured ten rounds rather than stopping at first draw");
            var review=RaceStore.review("host",id,false);check(list(review.get("questions")).size()==10&&map(list(review.get("questions")).get(0).get("question")).get("text").equals(text),"review preserves actually drawn snapshots after live bank edits");
            try(var c=AdminStore.connect();var st=c.createStatement()){st.execute("UPDATE game_questions SET status='PUBLISHED' WHERE rank_tier='BRONZE'");}
            Client next=new Client("host"),nextOther=new Client("guest");act(game,next,"race_queue",Map.of("mode","RANKED"));act(game,nextOther,"race_queue",Map.of("mode","RANKED"));String empty=(String)next.state().get("matchId");long ledgers=count("rank_ledger");
            try(var c=AdminStore.connect();var st=c.createStatement()){st.execute("UPDATE game_questions SET status='ARCHIVED' WHERE rank_tier='BRONZE'");}
            int correct=correct(empty,1);answer(game,next,correct);answer(game,nextOther,correct);now.addAndGet(2300);game.tick();
            check(next.state().get("phase").equals("CANCELLED")&&next.state().get("reason").toString().contains("Đồng"),"bank shortage mid-match cancels clearly instead of hanging or borrowing from another rank");
            check(count("rank_ledger")==ledgers&&drawnCount(game,empty)==1,"failed next draw never settles RP or appends an unserved question");
        }finally{
            game.close();RaceStore.saveSettings("operator",original);
            try(var c=AdminStore.connect()){
                try(var ps=c.prepareStatement("DELETE FROM game_questions WHERE text LIKE 'PER_ROUND_FIXTURE_%'")){ps.executeUpdate();}
                try(var ps=c.prepareStatement("UPDATE game_questions SET text=?,options_json=?,correct=?,status=?,revision=?,updated_at=? WHERE id=?")){
                    for(var row:bank){ps.setString(1,(String)row.get("text"));ps.setString(2,(String)row.get("options_json"));ps.setInt(3,num(row.get("correct")));ps.setString(4,(String)row.get("status"));ps.setInt(5,num(row.get("revision")));ps.setLong(6,((Number)row.get("updated_at")).longValue());ps.setInt(7,num(row.get("id")));ps.addBatch();}ps.executeBatch();
                }
            }
        }
    }
    static void direct()throws Exception{
        AtomicLong now=new AtomicLong(System.currentTimeMillis());KnowledgeRaceGame g=new KnowledgeRaceGame(now::get);
        Client[] p={new Client("racer0"),new Client("racer1"),new Client("racer2"),new Client("racer3")};
        try{
            var settings=RaceStore.settings();check(num(settings.get("timeLimit"))==15&&num(settings.get("queueSeconds"))==60&&num(settings.get("dailyTimeLimit"))==7,"requested defaults 60s queue, 15s rank, 7s daily");
            check(RaceStore.questions(true,10).size()==10,"ten distinct HARD networking questions available");
            setRank(g,"racer0",2,95);setRank(g,"racer1",2,80);setRank(g,"racer2",2,5);setRank(g,"racer3",2,0);
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
            check(num(rank("racer2").get("step"))==2&&num(rank("racer2").get("rp"))==0,"third loses ten without immediate demotion");check(num(rank("racer3").get("step"))==1&&num(rank("racer3").get("rp"))==70,"fourth loss at zero demotes");
            var peak=map(RaceStore.account("racer3").get("highest"));check(num(peak.get("step"))==2,"highest rank preserved after demotion");
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
            casual(g,now);casualMatchmaking(g,now);daily(g,now);cards(g,now);configAndForfeits(g,now);intentionalLeaveAndReconnect(g,now);timeouts(g,now);
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
        for(Client c:p)setRank(g,c.getUsername(),3,50);
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
    static String participation(String name)throws Exception{
        try(var c=AdminStore.connect()){var rows=AdminStore.rows(c,"SELECT p.match_id FROM race_participation p JOIN users u ON u.id=p.user_id WHERE u.username=?",name);return rows.isEmpty()?null:(String)rows.get(0).get("match_id");}
    }
    static void requeueAfterForfeit()throws Exception{
        var original=RaceStore.settings();var duel=new LinkedHashMap<>(original);duel.put("targetPlayers",2);RaceStore.saveSettings("operator",duel);
        AtomicLong now=new AtomicLong(System.currentTimeMillis());KnowledgeRaceGame game=new KnowledgeRaceGame(now::get);
        Client a=new Client("racer0"),b=new Client("racer1"),c=new Client("racer2");
        try{
            for(String name:List.of("racer0","racer1","racer2","racer3"))setRank(game,name,0,50);
            game.adminAction("operator",Map.of("action","setCards","targetUsername","racer0","shields",1,"doubles",1,"reason","Requeue card fixture"));
            act(game,a,"race_queue",Map.of("mode","RANKED","useShield",true));act(game,b,"race_queue",Map.of("mode","RANKED"));String old=(String)a.state().get("matchId"),oldToken=(String)map(a.state().get("question")).get("token");
            game.disconnect(b);Client connected=new Client("racer1");long errors=connected.errors();act(game,connected,"race_queue",Map.of("mode","RANKED"));check(connected.errors()==errors+1,"unexpected disconnect keeps old match ownership and cannot start a second match");act(game,connected,"race_resume",Map.of("mode","RANKED"));b=connected;
            check(b.state().get("matchId").equals(old),"disconnected player resumes old match normally");answer(game,b,correct(old,1));
            act(game,a,"race_leave",Map.of());check(participation("racer0")==null,"intentional leave releases database participation immediately");
            check(num(map(RaceStore.account("racer0").get("cards")).get("shields"))==0,"shield pending settlement in old forfeited match cannot be reused");
            errors=a.errors();act(game,a,"race_queue",Map.of("mode","RANKED","useShield",true));check(a.errors()==errors+1,"pending shield is rejected before matchmaking starts");
            errors=a.errors();act(game,a,"race_queue",Map.of("mode","RANKED"));check(a.errors()==errors&&a.state().get("phase").equals("QUEUED"),"intentional quitter can immediately queue without waiting for old match");
            Client replacement=new Client("racer0");act(game,replacement,"race_resume",Map.of("mode","RANKED"));check(replacement.state().get("phase").equals("QUEUED"),"new socket resumes new queue rather than old forfeited room");a=replacement;
            game.tick();act(game,c,"race_queue",Map.of("mode","RANKED"));String next=(String)a.state().get("matchId");check(next!=null&&!next.equals(old)&&next.equals(participation("racer0")),"new match starts and owns database slot while previous match is active");
            errors=a.errors();act(game,a,"race_answer",Map.of("matchId",old,"questionToken",oldToken,"answer",0));check(a.errors()==errors+1,"new socket cannot answer abandoned match");
            game.adminAction("operator",Map.of("action","kick","matchId",old,"targetUsername","racer0","reason","Late old-room action"));check(next.equals(participation("racer0")),"late old-room kick cannot release new membership");
            while(!b.state().get("phase").equals("COMPLETED")){
                if(b.state().get("phase").equals("QUESTION")&&num(b.state().get("myAnswer"))<0)answer(game,b,correct(old,num(b.state().get("round"))));
                now.addAndGet(2300);game.tick();
            }
            check(next.equals(participation("racer0"))&&a.state().get("matchId").equals(next),"old match settlement cannot remove or replace current match");
            String settledOld=old;check(RaceStore.history("racer0",false).stream().anyMatch(row->row.get("id").equals(settledOld)&&num(row.get("forfeited"))==1),"old loss remains recorded after joining a new match");
            check(num(map(RaceStore.account("racer0").get("cards")).get("shields"))==1,"unused pending shield becomes available after old settlement");
            errors=a.errors();answer(game,a,correct(next,num(a.state().get("round"))));check(a.errors()==errors,"player can continue answering new match after old match ends");
            game.adminAction("operator",Map.of("action","cancel","matchId",next,"reason","Requeue completion fixture"));
            for(String removal:List.of("kick","moderate")){
                a=new Client("racer0");b=new Client("racer1");c=new Client("racer2");act(game,a,"race_queue",Map.of("mode","RANKED"));act(game,b,"race_queue",Map.of("mode","RANKED"));old=(String)a.state().get("matchId");
                if(removal.equals("kick"))game.adminAction("operator",Map.of("action","kick","matchId",old,"targetUsername","racer0","reason","Requeue after kick"));else game.moderate("racer0","Requeue after session disconnect");
                check(participation("racer0")==null,removal+" releases old database slot");act(game,a,"race_queue",Map.of("mode","RANKED"));check(a.state().get("phase").equals("QUEUED"),removal+" permits immediate new ranked queue");act(game,c,"race_queue",Map.of("mode","RANKED"));next=(String)a.state().get("matchId");check(!next.equals(old),removal+" starts another match successfully");
                game.adminAction("operator",Map.of("action","cancel","matchId",old,"reason","Old room cleanup"));check(next.equals(participation("racer0"))&&a.state().get("matchId").equals(next),"old cancellation cannot affect new match after "+removal);
                game.adminAction("operator",Map.of("action","cancel","matchId",next,"reason","New room cleanup"));
            }
            a=new Client("racer0");act(game,a,"race_create",Map.of("withBots",true,"botCount",1,"questionCount",10,"timeLimit",3));act(game,a,"race_start",Map.of());old=(String)a.state().get("matchId");game.adminAction("operator",Map.of("action","kick","matchId",old,"targetUsername","racer0","reason","Admin kicks room host"));
            act(game,a,"race_queue",Map.of("mode","RANKED"));check(a.state().get("phase").equals("QUEUED")&&participation("racer0")==null,"admin kicking room host also allows immediate requeue");act(game,a,"race_cancel_queue",Map.of());
        }finally{game.close();RaceStore.saveSettings("operator",original);}
    }
    static void kickNotifications()throws Exception{
        KnowledgeRaceGame game=new KnowledgeRaceGame();
        try{
            Client[] players={new Client("racer0"),new Client("racer1"),new Client("racer2"),new Client("racer3")};
            for(Client p:players)act(game,p,"race_queue",Map.of("mode","RANKED"));
            String id=(String)players[0].state().get("matchId"),reason="Gửi đáp án gây rối <img src=x>";
            game.adminAction("operator",Map.of("action","kick","matchId",id,"targetUsername","racer0","reason",reason));
            check(players[0].last("race_kicked").get("reason").equals(reason),"ranked admin kick sends exact reason");
            check(players[0].last("race_kicked").get("message").toString().startsWith("Bạn đã bị kick"),"ranked admin kick clearly identifies removal");
            Client back=new Client("racer0");act(game,back,"race_resume",Map.of("mode","RANKED"));check(back.state().get("phase").equals("IDLE"),"kicked ranked player cannot continue match");
            Client searching=new Client("observer");act(game,searching,"race_queue",Map.of("mode","RANKED"));
            game.adminAction("operator",Map.of("action","dequeue","targetUsername","observer","reason","Tạm dừng ghép trận để bảo trì"));
            check(searching.state().get("phase").equals("IDLE")&&Boolean.TRUE.equals(searching.state().get("queueCancelled")),"admin queue removal explicitly signals cancellation");
            check(searching.state().get("reason").equals("Tạm dừng ghép trận để bảo trì"),"queue cancellation sends exact reason to searching player");
            game.adminAction("operator",Map.of("action","cancel","matchId",id,"reason","Kiểm tra hủy trận"));
            check(players[1].state().get("phase").equals("CANCELLED")&&players[1].state().get("reason").toString().contains("Kiểm tra hủy trận"),"active players receive cancelled state and reason");
            Client host=new Client("host"),guest=new Client("guest");
            act(game,host,"race_create",Map.of("withBots",true,"botCount",1,"questionCount",10,"timeLimit",3));act(game,guest,"race_join",Map.of("roomCode",host.state().get("roomCode")));act(game,host,"race_start",Map.of());
            game.adminAction("operator",Map.of("action","kick","matchId",host.state().get("matchId"),"targetUsername","host","reason","Chủ phòng vi phạm nội quy"));
            check(host.last("race_kicked").get("reason").equals("Chủ phòng vi phạm nội quy"),"admin kicking active room host also sends reason");
        }finally{game.close();}
    }
    static void intentionalLeaveAndReconnect(KnowledgeRaceGame g,AtomicLong now)throws Exception{
        Client[] players={new Client("racer0"),new Client("racer1"),new Client("racer2"),new Client("racer3")};
        for(Client p:players)act(g,p,"race_queue",Map.of("mode","RANKED"));
        String id=(String)players[0].state().get("matchId"),code=(String)players[0].state().get("roomCode");
        int right=correct(id,1);answer(g,players[0],right);g.disconnect(players[0]);
        now.addAndGet(61000);g.tick();
        Client reconnected=new Client("racer0");act(g,reconnected,"race_resume",Map.of("mode","RANKED"));
        check(reconnected.state().get("matchId").equals(id),"unexpected disconnect can resume same active ranked match after more than 30 seconds");
        check(num(reconnected.state().get("myAnswer"))==right,"reconnection preserves submitted answer");
        check(list(reconnected.state().get("players")).stream().anyMatch(p->p.get("username").equals("racer0")&&!Boolean.TRUE.equals(p.get("forfeited"))&&num(p.get("score"))==3),"unexpected disconnect preserves points and is not a loss");
        g.disconnect(players[0]);
        check(list(players[1].state().get("players")).stream().anyMatch(p->p.get("username").equals("racer0")&&Boolean.TRUE.equals(p.get("online"))),"late disconnect of old socket cannot disconnect replacement");
        now.addAndGet(2300);g.tick();answer(g,reconnected,correct(id,2));
        check(num(reconnected.state().get("myAnswer"))==correct(id,2),"reconnected player can keep answering");
        act(g,reconnected,"race_leave",Map.of());
        check(reconnected.state().get("phase").equals("IDLE")&&Boolean.TRUE.equals(reconnected.state().get("forfeited")),"intentional leave immediately acknowledges forfeited loss");
        check(reconnected.state().get("message").toString().contains("thua"),"intentional ranked leave explicitly reports loss");
        check(RaceStore.history("racer0",false).stream().anyMatch(r->r.get("id").equals(id)&&num(r.get("forfeited"))==1&&r.get("status").equals("ACTIVE")),"forfeit saved before match ends");
        Client returner=new Client("racer0");act(g,returner,"race_resume",Map.of("mode","RANKED"));
        check(returner.state().get("phase").equals("IDLE")&&Boolean.TRUE.equals(returner.state().get("forfeited")),"new connection cannot resume voluntarily abandoned match");
        act(g,returner,"race_resume",Map.of("mode","CASUAL"));check(returner.state().get("phase").equals("IDLE"),"other mode cannot offer abandoned match as resumable");
        long errors=returner.errors();act(g,returner,"race_join",Map.of("roomCode",code));check(returner.errors()==errors+1,"room code cannot bypass forfeit");
        long oldErrors=players[0].errors();answer(g,players[0],0);check(players[0].errors()==oldErrors+1,"old socket cannot answer after forfeit");
        act(g,returner,"race_answer",Map.of("matchId",id,"questionToken",map(players[1].state().get("question")).get("token"),"answer",0));check(returner.errors()==errors+2,"forfeited account cannot submit answers using new socket");
        for(int i=0;i<10&&!players[1].state().get("phase").equals("COMPLETED");i++){now.addAndGet(16000);g.tick();now.addAndGet(2300);g.tick();}
        check(players[1].state().get("phase").equals("COMPLETED"),"remaining players finish abandoned match normally");
        var loss=RaceStore.history("racer0",false).stream().filter(r->r.get("id").equals(id)).findFirst().orElseThrow();
        check(num(loss.get("forfeited"))==1&&num(loss.get("placement"))==4,"forfeiter loses even with higher score than remaining players");
        check(num(map(loss.get("result")).get("rpDelta"))==-15,"forfeiter receives last-place RP according to existing rules");
        try(var c=AdminStore.connect()){check(num(AdminStore.rows(c,"SELECT COUNT(*) AS n FROM rank_ledger l JOIN users u ON u.id=l.user_id WHERE l.match_id=? AND u.username='racer0'",id).get(0).get("n"))==1,"forfeit and match completion settle RP exactly once");}
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
        act(g,a,"race_leave",Map.of());long n=a.errors();act(g,a,"race_queue",Map.of("mode","RANKED"));check(a.errors()==n&&a.state().get("phase").equals("QUEUED"),"forfeiter can queue again before old match settles");act(g,a,"race_cancel_queue",Map.of());act(g,b,"race_leave",Map.of());
        for(int r=0;r<10;r++){g.tick();now.addAndGet(2300);g.tick();}
        check(num(rank("host").get("total"))==before,"all-forfeit tie cannot award positive RP");Client resumed=new Client("host");act(g,resumed,"race_resume",Map.of());check(resumed.state().get("phase").equals("IDLE"),"old completed match cannot replace menu after requeue");check(RaceStore.history("host",false).stream().anyMatch(row->row.get("id").equals(left)&&row.get("status").equals("COMPLETED")),"old forfeited result remains available in history");
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
            var duel=new LinkedHashMap<>(config);duel.put("targetPlayers",2);RaceStore.saveSettings("operator",duel);
            Peer quitter=peers.get(0),remaining=peers.get(1),nextOpponent=peers.get(2);
            quitter.send("race_queue",Map.of("mode","RANKED"));remaining.send("race_queue",Map.of("mode","RANKED"));
            until(()->quitter.state().getOrDefault("phase","").equals("QUESTION")&&!quitter.state().get("matchId").equals(id),"live original duel starts");String old=(String)quitter.state().get("matchId");
            quitter.send("race_leave",Map.of());until(()->quitter.state().getOrDefault("phase","").equals("IDLE"),"live intentional leave acknowledged");
            quitter.send("race_queue",Map.of("mode","RANKED"));until(()->quitter.state().getOrDefault("phase","").equals("QUEUED"),"live player can requeue immediately before old match ends");
            nextOpponent.send("race_queue",Map.of("mode","RANKED"));until(()->quitter.state().getOrDefault("phase","").equals("QUESTION")&&!quitter.state().get("matchId").equals(old),"real new match starts while old opponent is still playing");String next=(String)quitter.state().get("matchId");
            check(remaining.state().get("matchId").equals(old),"remaining opponent keeps original duel");
            check(request("/api/admin/race/action",adminCookie,Map.of("action","cancel","matchId",old,"reason","Old live match cleanup"),adminCsrf).statusCode()==200,"old live match can end independently");
            until(()->remaining.state().getOrDefault("phase","").equals("CANCELLED"),"old opponent receives cancellation");
            check(quitter.state().get("matchId").equals(next)&&next.equals(participation("racer0")),"old match cancellation preserves new live match and database membership");
            quitter.send("race_answer",Map.of("matchId",next,"questionToken",map(quitter.state().get("question")).get("token"),"answer",correct(next,1)));
            until(()->num(quitter.state().getOrDefault("myAnswer",-1))>=0,"new live match accepts answers after old room closes");
            check(request("/api/admin/race/action",adminCookie,Map.of("action","cancel","matchId",next,"reason","New live match cleanup"),adminCsrf).statusCode()==200,"new live fixture cleaned up");
            var changed=new HashMap<>(config);changed.put("timeLimit",18);check(request("/api/admin/race/settings",adminCookie,changed,adminCsrf).statusCode()==200,"admin can change ranked question timer");
            check(num(RaceStore.settings().get("timeLimit"))==18,"ranked config persisted");RaceStore.saveSettings("operator",config);
        }finally{for(Peer p:peers)if(p.socket!=null)p.socket.abort();}
    }
    public static void main(String[] args)throws Exception{
        if(!Files.exists(Path.of(".race-test")))throw new IllegalStateException("Use test_race.ps1 isolated directory");DatabaseManager db=DatabaseManager.getInstance();db.init();users=new UserRepository(db);
        for(String name:List.of("racer0","racer1","racer2","racer3","host","guest","observer","daily"))users.register(name,name+"@example.com","PlayerPass123");AdminAccount.create(users,"operator","AdminOnlyPass123!");
        rules();rankedPools();perRoundQuestionDraws();direct();requeueAfterForfeit();kickNotifications();SessionManager sessions=new SessionManager();ArenaGame arena=new ArenaGame(users);WheelGame wheel=new WheelGame(users);SurvivalGame survival=new SurvivalGame(users);HttpAndWebSocketServer server=new HttpAndWebSocketServer(0,new File(args[0]),users,sessions,arena,wheel,survival);
        Thread t=new Thread(()->{try{server.start();}catch(IOException e){throw new RuntimeException(e);}});t.setDaemon(true);t.start();while(server.getPort()==0)Thread.sleep(10);base="http://localhost:"+server.getPort();
        try{live(server);System.out.println("PASS: "+checks+" ranked/casual/daily, RP, cards, reconnect, anti-replay, admin and real WebSocket checks.");if(args.length>1&&args[1].equals("preview")){System.out.println("PREVIEW_URL="+base);long end=System.currentTimeMillis()+1_200_000;while(!Files.exists(Path.of("stop-preview"))&&System.currentTimeMillis()<end)Thread.sleep(200);}}
        finally{server.stop();arena.close();wheel.close();survival.close();db.close();t.join(2000);}
    }
}
