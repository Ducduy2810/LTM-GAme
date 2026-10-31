package vn.game.db;

import vn.game.model.*;
import vn.game.util.JsonUtil;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Durable match snapshots, answers, RP ledger and daily rewards. Never trusts client results. */
public final class RaceStore {
    private RaceStore(){}
    public static final ZoneId ZONE=ZoneId.of("Asia/Ho_Chi_Minh");
    public static String today(long time){return Instant.ofEpochMilli(time).atZone(ZONE).toLocalDate().toString();}
    private static void update(Connection c,String sql,Object...params)throws SQLException{try(PreparedStatement ps=c.prepareStatement(sql)){for(int i=0;i<params.length;i++)ps.setObject(i+1,params[i]);ps.executeUpdate();}}
    public static void init()throws Exception{
        try(Connection c=AdminStore.connect();Statement st=c.createStatement()){
            boolean exists;try(ResultSet rs=st.executeQuery("SELECT name FROM sqlite_master WHERE name='race_matches'")){exists=rs.next();}
            if(!exists){java.nio.file.Files.createDirectories(java.nio.file.Path.of("data/backups"));st.execute("VACUUM INTO 'data/backups/before-race-"+System.currentTimeMillis()+".db'");}
            st.execute("CREATE TABLE IF NOT EXISTS race_matches(id TEXT PRIMARY KEY,room_code TEXT NOT NULL UNIQUE,room_name TEXT NOT NULL,mode TEXT NOT NULL CHECK(mode IN ('RANKED','CASUAL','BOT','DAILY')),status TEXT NOT NULL,config_json TEXT NOT NULL,started_at INTEGER NOT NULL,ended_at INTEGER,reason TEXT NOT NULL DEFAULT '')");
            st.execute("CREATE TABLE IF NOT EXISTS race_players(match_id TEXT NOT NULL REFERENCES race_matches(id),username TEXT NOT NULL,user_id INTEGER REFERENCES users(id),bot INTEGER NOT NULL,score INTEGER NOT NULL DEFAULT 0,correct_count INTEGER NOT NULL DEFAULT 0,placement INTEGER NOT NULL DEFAULT 0,forfeited INTEGER NOT NULL DEFAULT 0,use_shield INTEGER NOT NULL DEFAULT 0,use_double INTEGER NOT NULL DEFAULT 0,result_json TEXT NOT NULL DEFAULT '{}',PRIMARY KEY(match_id,username))");
            st.execute("CREATE TABLE IF NOT EXISTS race_rounds(match_id TEXT NOT NULL REFERENCES race_matches(id),round_no INTEGER NOT NULL,token TEXT NOT NULL,snapshot_json TEXT NOT NULL,opened_at INTEGER NOT NULL,deadline INTEGER NOT NULL,closed INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(match_id,round_no))");
            st.execute("CREATE TABLE IF NOT EXISTS race_answers(match_id TEXT NOT NULL,round_no INTEGER NOT NULL,username TEXT NOT NULL,answer INTEGER NOT NULL,elapsed_ms INTEGER NOT NULL,answer_order INTEGER NOT NULL,points INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(match_id,round_no,username),FOREIGN KEY(match_id,round_no) REFERENCES race_rounds(match_id,round_no))");
            st.execute("CREATE TABLE IF NOT EXISTS race_participation(user_id INTEGER PRIMARY KEY REFERENCES users(id),match_id TEXT NOT NULL REFERENCES race_matches(id))");
            st.execute("CREATE TABLE IF NOT EXISTS rank_ledger(match_id TEXT NOT NULL REFERENCES race_matches(id),user_id INTEGER NOT NULL REFERENCES users(id),base_delta INTEGER NOT NULL,delta INTEGER NOT NULL,before_step INTEGER NOT NULL,before_rp INTEGER NOT NULL,after_step INTEGER NOT NULL,after_rp INTEGER NOT NULL,card TEXT NOT NULL,PRIMARY KEY(match_id,user_id))");
            st.execute("CREATE TABLE IF NOT EXISTS player_cards(user_id INTEGER PRIMARY KEY REFERENCES users(id),shields INTEGER NOT NULL DEFAULT 0 CHECK(shields>=0),doubles INTEGER NOT NULL DEFAULT 0 CHECK(doubles>=0))");
            st.execute("CREATE TABLE IF NOT EXISTS daily_attempts(user_id INTEGER NOT NULL REFERENCES users(id),day TEXT NOT NULL,match_id TEXT NOT NULL UNIQUE REFERENCES race_matches(id),rewarded INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(user_id,day))");
            st.execute("CREATE INDEX IF NOT EXISTS idx_race_players_user ON race_players(user_id,match_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_race_matches_time ON race_matches(started_at DESC)");
            Set<String> columns=new HashSet<>();try(ResultSet rs=st.executeQuery("PRAGMA table_info(player_ranks)")){while(rs.next())columns.add(rs.getString("name"));}
            if(!columns.contains("current_step")){
                st.execute("ALTER TABLE player_ranks ADD COLUMN current_step INTEGER NOT NULL DEFAULT 0");st.execute("ALTER TABLE player_ranks ADD COLUMN highest_step INTEGER NOT NULL DEFAULT 0");st.execute("ALTER TABLE player_ranks ADD COLUMN ranked_games INTEGER NOT NULL DEFAULT 0");st.execute("ALTER TABLE player_ranks ADD COLUMN ranked_wins INTEGER NOT NULL DEFAULT 0");st.execute("ALTER TABLE player_ranks ADD COLUMN highest_title TEXT NOT NULL DEFAULT ''");
                // No former casual score is converted to RP. Existing personal display tiers are mapped only if present.
                for(String tier:List.of("BRONZE","SILVER","GOLD","PLATINUM","DIAMOND")){int step=List.of("BRONZE","SILVER","GOLD","PLATINUM","DIAMOND").indexOf(tier)*3;
                    update(c,"UPDATE player_ranks SET current_step=?,current_points=MIN(current_points,99) WHERE current_tier=?",step,tier);
                    update(c,"UPDATE player_ranks SET highest_step=?,highest_points=MIN(highest_points,99) WHERE highest_tier=?",step,tier);}
                st.execute("UPDATE player_ranks SET highest_step=current_step,highest_points=current_points WHERE highest_step<current_step OR (highest_step=current_step AND highest_points<current_points)");
            }
        }
        try(Connection c=AdminStore.connect();Statement st=c.createStatement()){
            st.execute("UPDATE player_ranks SET current_tier='BRONZE',current_points=MIN(current_points,99) WHERE current_tier='UNRANKED'");
            st.execute("UPDATE player_ranks SET highest_tier='BRONZE',highest_points=MIN(highest_points,99) WHERE highest_tier='UNRANKED'");
        }
        seedQuestions();seedRankedQuestions();
    }
    private static void seedQuestions()throws SQLException{
        try(Connection c=AdminStore.connect()){
            c.setAutoCommit(false);try{
                if(!AdminStore.rows(c,"SELECT value FROM app_settings WHERE key='race_questions_seeded'").isEmpty()){c.rollback();return;}
                for(Question q:NetworkRaceQuestions.all())update(c,"INSERT INTO game_questions(text,options_json,correct,topic,difficulty,explanation,status,usage,updated_at) VALUES(?,?,?,'NETWORKING',?,?,'PUBLISHED','RACE',?)",q.getText(),JsonUtil.toJson(q.getOptions()),q.getCorrect(),q.getDifficulty(),q.getExplanation(),System.currentTimeMillis());
                update(c,"INSERT INTO app_settings(key,value) VALUES('race_questions_seeded','true')");c.commit();
            }catch(Exception e){c.rollback();throw e;}
        }
    }
    private static void seedRankedQuestions()throws SQLException{
        try(Connection c=AdminStore.connect()){
            c.setAutoCommit(false);try{
                if(!AdminStore.rows(c,"SELECT value FROM app_settings WHERE key='rank_tier_questions_seeded_v1'").isEmpty()){c.rollback();return;}
                for(var seed:RankedQuestionBank.seeds()){
                    Question q=seed.question();
                    update(c,"INSERT INTO game_questions(text,options_json,correct,topic,difficulty,explanation,status,usage,rank_tier,updated_at) VALUES(?,?,?,'NETWORKING',?,?,'PUBLISHED','RACE',?,?)",q.getText(),JsonUtil.toJson(q.getOptions()),q.getCorrect(),q.getDifficulty(),q.getExplanation(),seed.tier(),System.currentTimeMillis());
                }
                update(c,"INSERT INTO app_settings(key,value) VALUES('rank_tier_questions_seeded_v1','true')");c.commit();
            }catch(Exception e){c.rollback();throw e;}
        }
    }
    public static Map<String,Integer> rankTotals(Collection<String> names)throws SQLException{
        Map<String,Integer> result=new HashMap<>();List<String> list=new ArrayList<>(names);
        try(Connection c=AdminStore.connect()){
            for(int start=0;start<list.size();start+=900){
                var batch=list.subList(start,Math.min(start+900,list.size()));
                for(var row:AdminStore.rows(c,"SELECT u.username,r.current_step*100+r.current_points AS total FROM users u JOIN player_ranks r ON r.user_id=u.id WHERE u.username IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+")",batch.toArray()))result.put((String)row.get("username"),((Number)row.get("total")).intValue());
            }
        }
        return result;
    }
    private static final String RANKED_SOURCE=" FROM game_questions q JOIN education_topics t ON t.scope='QUESTION' AND t.code=q.topic WHERE q.topic='NETWORKING' AND q.status='PUBLISHED' AND q.usage='RACE' AND q.rank_tier=? AND t.active=1 AND q.explanation<>''";
    private static final Random QUESTION_RANDOM=new java.security.SecureRandom();
    private static Map<String,Integer> remainingQuestions(String tier,int count,List<Question> used){
        Map<String,Integer> remaining=RankedQuestionBank.requirements(tier,count);Set<Integer> ids=new HashSet<>();
        for(Question q:used){
            if(q.getId()<=0||!ids.add(q.getId())||!remaining.containsKey(q.getDifficulty())||remaining.get(q.getDifficulty())<=0)throw new IllegalArgumentException("Trạng thái câu hỏi đã dùng không hợp lệ.");
            remaining.put(q.getDifficulty(),remaining.get(q.getDifficulty())-1);
        }
        return remaining;
    }
    private static String excludeUsed(List<Question> used){return used.isEmpty()?"":" AND q.id NOT IN ("+String.join(",",Collections.nCopies(used.size(),"?"))+")";}
    private static List<Object> questionParameters(String tier,List<Question> used){List<Object> params=new ArrayList<>();params.add(tier);for(Question q:used)params.add(q.getId());return params;}
    private static void requireQuestions(Connection c,String tier,Map<String,Integer> required,List<Question> used)throws SQLException{
        Map<String,Integer> available=new HashMap<>();
        for(var row:AdminStore.rows(c,"SELECT q.difficulty,COUNT(*) AS total"+RANKED_SOURCE+excludeUsed(used)+" GROUP BY q.difficulty",questionParameters(tier,used).toArray()))available.put((String)row.get("difficulty"),((Number)row.get("total")).intValue());
        for(var entry:required.entrySet())if(available.getOrDefault(entry.getKey(),0)<entry.getValue()){
            String difficulty=Map.of("EASY","Dễ","MEDIUM","Trung bình","HARD","Khó").get(entry.getKey());
            throw new IllegalArgumentException("Bộ câu hỏi bậc "+RankedQuestionBank.name(tier)+" cần ít nhất "+entry.getValue()+" câu "+difficulty+" chưa dùng, đã xuất bản dành riêng cho bậc này. Admin bổ sung ngân hàng câu hỏi.");
        }
    }
    /** Capacity check only: joining a queue never draws or caches a complete paper. */
    public static void validateRankedPool(String tier,int count)throws SQLException{
        try(Connection c=AdminStore.connect()){requireQuestions(c,tier,remainingQuestions(tier,count,List.of()),List.of());}
    }
    /** Draw exactly one question when a round opens, from the live eligible bank. */
    @SuppressWarnings("unchecked")
    public static Question nextRankedQuestion(String tier,int count,List<Question> used)throws SQLException{
        Map<String,Integer> remaining=remainingQuestions(tier,count,used);int total=remaining.values().stream().mapToInt(Integer::intValue).sum();
        if(total<=0)throw new IllegalArgumentException("Trận đã đủ số câu hỏi.");
        try(Connection c=AdminStore.connect()){
            c.setAutoCommit(false);try{
                requireQuestions(c,tier,remaining,used);
                int position=QUESTION_RANDOM.nextInt(total);String difficulty=null;
                for(var entry:remaining.entrySet()){position-=entry.getValue();if(position<0){difficulty=entry.getKey();break;}}
                List<Object> params=questionParameters(tier,used);params.add(difficulty);
                var rows=AdminStore.rows(c,"SELECT q.*"+RANKED_SOURCE+excludeUsed(used)+" AND q.difficulty=? ORDER BY RANDOM() LIMIT 1",params.toArray());
                if(rows.isEmpty())throw new IllegalArgumentException("Không còn câu hỏi hợp lệ cho lượt này.");
                var row=rows.get(0);Question question=new Question(((Number)row.get("id")).intValue(),(String)row.get("text"),(List<String>)(List<?>)JsonUtil.parse((String)row.get("options_json")),((Number)row.get("correct")).intValue(),"NETWORKING",(String)row.get("difficulty"),(String)row.get("explanation"));
                c.commit();return question;
            }catch(Exception e){c.rollback();throw e;}
        }
    }
    public static void recoverInterrupted()throws SQLException{
        try(Connection c=AdminStore.connect()){
            c.setAutoCommit(false);try{update(c,"UPDATE race_matches SET status='INTERRUPTED',ended_at=?,reason='Máy chủ khởi động lại; không tính RP hoặc dùng thẻ' WHERE status IN ('WAITING','ACTIVE')",System.currentTimeMillis());update(c,"DELETE FROM race_participation");c.commit();}catch(Exception e){c.rollback();throw e;}
        }
    }
    public static Map<String,Object> defaultSettings(){
        Map<String,Object> value=new LinkedHashMap<>(Map.of("enabled",true,"rankedEnabled",true,"dailyEnabled",true,"questionCount",10,"timeLimit",15,"queueSeconds",60,"targetPlayers",4,"dailyTimeLimit",7));
        value.putAll(Map.of("firstRP",25,"secondRP",10,"duelSecondRP",0,"thirdRP",-10,"fourthRP",-15,"rankBuffer",20,"demotionRP",70,"dailyCorrect",8,"shieldReward",1,"doubleReward",1));
        return value;
    }
    public static Map<String,Object> settings()throws SQLException{
        Map<String,Object> value=defaultSettings();
        try(Connection c=AdminStore.connect()){var rows=AdminStore.rows(c,"SELECT value FROM app_settings WHERE key='race_settings'");if(!rows.isEmpty())value.putAll(JsonUtil.parseObject((String)rows.get(0).get("value")));}return value;
    }
    public static final Set<String> DAILY_SETTING_KEYS=Set.of("dailyEnabled","dailyTimeLimit","dailyCorrect","shieldReward","doubleReward");
    private static final Set<String> RANK_SETTING_KEYS=Set.of("rankedEnabled","questionCount","timeLimit","queueSeconds","targetPlayers","firstRP","secondRP","duelSecondRP","thirdRP","fourthRP","rankBuffer","demotionRP");
    public static Map<String,Object> dailySettings()throws SQLException{
        var all=settings();Map<String,Object> result=new LinkedHashMap<>();for(String key:DAILY_SETTING_KEYS)result.put(key,all.get(key));return result;
    }
    public static synchronized void saveScopedSettings(String actor,Map<String,Object> input,String scope)throws SQLException{
        Set<String> keys=switch(scope){case "DAILY"->DAILY_SETTING_KEYS;case "RANK"->RANK_SETTING_KEYS;case "SERVICE"->Set.of("enabled");default->throw new IllegalArgumentException("Phạm vi cấu hình không hợp lệ.");};
        if(input.isEmpty()||!keys.containsAll(input.keySet()))throw new IllegalArgumentException("Cấu hình chứa trường ngoài phạm vi "+scope+".");
        Map<String,Object> merged=new LinkedHashMap<>(settings());merged.putAll(input);writeSettings(actor,merged,scope.equals("DAILY")?"DAILY_SETTINGS":scope.equals("SERVICE")?"RACE_SERVICE_SETTINGS":"RACE_SETTINGS");
    }
    public static synchronized void saveSettings(String actor,Map<String,Object> input)throws SQLException{writeSettings(actor,input,"RACE_SETTINGS");}
    private static void writeSettings(String actor,Map<String,Object> input,String auditAction)throws SQLException{
        Map<String,Object> value=normalizeSettings(input,settings());
        try(Connection c=AdminStore.connect()){c.setAutoCommit(false);try{update(c,"INSERT INTO app_settings(key,value) VALUES('race_settings',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",JsonUtil.toJson(value));AdminStore.audit(c,actor,auditAction,"server",value);c.commit();}catch(Exception e){c.rollback();throw e;}}
    }
    public static Map<String,Object> normalizeSettings(Map<String,Object> input,Map<String,Object> defaults){
        Map<String,Object> value=new LinkedHashMap<>();for(String key:List.of("enabled","rankedEnabled","dailyEnabled")){if(!(input.get(key) instanceof Boolean))throw new IllegalArgumentException("Thiếu trạng thái "+key);value.put(key,input.get(key));}
        int[][] bounds={{10,15},{3,120},{10,300},{2,4},{3,120}};String[] names={"questionCount","timeLimit","queueSeconds","targetPlayers","dailyTimeLimit"};
        for(int i=0;i<names.length;i++){Object raw=input.get(names[i]);if(!(raw instanceof Number n)||n.intValue()!=n.doubleValue()||n.intValue()<bounds[i][0]||n.intValue()>bounds[i][1])throw new IllegalArgumentException("Giá trị "+names[i]+" cần "+bounds[i][0]+"–"+bounds[i][1]);value.put(names[i],n.intValue());}
        int[][] rpBounds={{1,100},{0,100},{0,100},{-100,0},{-100,0},{0,99},{0,99},{1,10},{0,10},{0,10}};
        String[] rpNames={"firstRP","secondRP","duelSecondRP","thirdRP","fourthRP","rankBuffer","demotionRP","dailyCorrect","shieldReward","doubleReward"};
        for(int i=0;i<rpNames.length;i++){String key=rpNames[i];Object raw=input.getOrDefault(key,defaults.get(key));if(!(raw instanceof Number n)||n.intValue()!=n.doubleValue()||n.intValue()<rpBounds[i][0]||n.intValue()>rpBounds[i][1])throw new IllegalArgumentException("Giá trị "+key+" cần "+rpBounds[i][0]+"–"+rpBounds[i][1]);value.put(key,n.intValue());}
        if((int)value.get("secondRP")>(int)value.get("firstRP")||(int)value.get("duelSecondRP")>(int)value.get("firstRP")||(int)value.get("fourthRP")>(int)value.get("thirdRP"))throw new IllegalArgumentException("RP các vị trí phải giảm dần.");
        return value;
    }
    public static List<Question> questions(boolean hard,int count)throws SQLException{
        List<Question> list=new ArrayList<>();
        try(Connection c=AdminStore.connect()){
            for(Map<String,Object> row:AdminStore.rows(c,"SELECT q.* FROM game_questions q JOIN education_topics t ON t.scope='QUESTION' AND t.code=q.topic WHERE q.topic='NETWORKING' AND q.status='PUBLISHED' AND q.usage IN ('RACE','BOTH') AND q.rank_tier='' AND t.active=1 AND q.explanation<>'' AND (?=0 OR q.difficulty='HARD')",hard?1:0)){
                @SuppressWarnings("unchecked") List<String> options=(List<String>)(List<?>)JsonUtil.parse((String)row.get("options_json"));
                list.add(new Question(((Number)row.get("id")).intValue(),(String)row.get("text"),options,((Number)row.get("correct")).intValue(),"NETWORKING",(String)row.get("difficulty"),(String)row.get("explanation")));
            }
        }
        if(list.size()<count)throw new IllegalArgumentException("Cần ít nhất "+count+" câu "+(hard?"Khó ":"")+"Lập trình mạng đã xuất bản cho Đường đua. Admin kiểm tra ngân hàng câu hỏi.");
        Collections.shuffle(list);return List.copyOf(list.subList(0,count));
    }
    private static int userId(Connection c,String username)throws SQLException{
        var rows=AdminStore.rows(c,"SELECT id FROM users WHERE username=? COLLATE NOCASE AND LOWER(role)<>'admin' AND blocked_until<>-1 AND blocked_until<=?",username,System.currentTimeMillis());
        if(rows.isEmpty())throw new IllegalArgumentException("Tài khoản không còn được phép chơi.");return ((Number)rows.get(0).get("id")).intValue();
    }
    private static void ensureRank(Connection c,int id)throws SQLException{
        update(c,"INSERT OR IGNORE INTO player_ranks(user_id,current_tier,highest_tier,updated_at) VALUES(?,'BRONZE','BRONZE',?)",id,System.currentTimeMillis());
        update(c,"INSERT OR IGNORE INTO player_cards(user_id) VALUES(?)",id);
    }
    private static Map<String,Object> availableCards(Connection c,int id)throws SQLException{
        // A shield selected in a forfeited match may still be consumed when that match settles.
        return AdminStore.rows(c,"SELECT MAX(0,pc.shields-(SELECT COUNT(*) FROM race_players p JOIN race_matches m ON m.id=p.match_id WHERE p.user_id=pc.user_id AND p.forfeited=1 AND p.use_shield=1 AND m.mode='RANKED' AND m.status='ACTIVE')) AS shields,pc.doubles FROM player_cards pc WHERE pc.user_id=?",id).get(0);
    }
    public static Map<String,Object> account(String username)throws SQLException{
        try(Connection c=AdminStore.connect()){
            int id=userId(c,username);ensureRank(c,id);
            Map<String,Object> row=AdminStore.rows(c,"SELECT * FROM player_ranks WHERE user_id=?",id).get(0);
            RankRules.Position current=new RankRules.Position(((Number)row.get("current_step")).intValue(),((Number)row.get("current_points")).intValue());
            RankRules.Position highest=new RankRules.Position(((Number)row.get("highest_step")).intValue(),((Number)row.get("highest_points")).intValue());
            int place=rankPosition(c,id);String title=((Number)row.get("ranked_games")).intValue()>0?RankRules.title(current.step(),place):"";
            Map<String,Object> result=new LinkedHashMap<>();result.put("current",current.json());result.put("highest",highest.json());result.put("title",title);result.put("highestTitle",row.get("highest_title"));result.put("serverPosition",place);result.put("games",row.get("ranked_games"));result.put("wins",row.get("ranked_wins"));
            result.put("cards",availableCards(c,id));
            var daily=AdminStore.rows(c,"SELECT match_id,rewarded FROM daily_attempts WHERE user_id=? AND day=?",id,today(System.currentTimeMillis()));result.put("daily",Map.of("date",today(System.currentTimeMillis()),"used",!daily.isEmpty(),"matchId",daily.isEmpty()?"":daily.get(0).get("match_id")));return result;
        }
    }
    private static int rankPosition(Connection c,int id)throws SQLException{
        List<Map<String,Object>> all=AdminStore.rows(c,"SELECT r.user_id FROM player_ranks r JOIN users u ON u.id=r.user_id WHERE r.ranked_games>0 AND LOWER(u.role)<>'admin' AND u.blocked_until<>-1 AND u.blocked_until<=? ORDER BY (r.current_step*100+r.current_points) DESC,r.ranked_wins DESC,u.username COLLATE NOCASE",System.currentTimeMillis());
        for(int i=0;i<all.size();i++)if(((Number)all.get(i).get("user_id")).intValue()==id)return i+1;return 0;
    }
    public static List<Map<String,Object>> leaderboard()throws SQLException{
        try(Connection c=AdminStore.connect()){
            var list=AdminStore.rows(c,"SELECT u.username,r.current_step,r.current_points,r.highest_step,r.highest_points,r.ranked_games,r.ranked_wins FROM player_ranks r JOIN users u ON u.id=r.user_id WHERE r.ranked_games>0 AND LOWER(u.role)<>'admin' AND u.blocked_until<>-1 AND u.blocked_until<=? ORDER BY (r.current_step*100+r.current_points) DESC,r.ranked_wins DESC,u.username COLLATE NOCASE LIMIT 100",System.currentTimeMillis());
            for(int i=0;i<list.size();i++){var row=list.get(i);int step=((Number)row.get("current_step")).intValue();row.put("position",i+1);row.put("rankName",new RankRules.Position(step,((Number)row.get("current_points")).intValue()).name());row.put("title",RankRules.title(step,i+1));}return list;
        }
    }
    public static Map<String,Object> adminLeaderboard(String query,String status,int offset)throws SQLException{
        if(!Set.of("eligible","blocked","all").contains(status))throw new IllegalArgumentException("Bộ lọc xếp hạng không hợp lệ.");
        int pageOffset=Math.max(0,offset);long now=System.currentTimeMillis();
        String cte="""
            WITH ranked AS (
                SELECT u.id AS user_id,u.username,u.blocked_until,
                    CASE WHEN u.blocked_until=-1 OR u.blocked_until>? THEN 1 ELSE 0 END AS blocked,
                    r.current_step,r.current_points,r.highest_step,r.highest_points,r.highest_title,
                    r.ranked_games,r.ranked_wins,r.updated_at
                FROM player_ranks r JOIN users u ON u.id=r.user_id
                WHERE r.ranked_games>0 AND LOWER(u.role)<>'admin'
            ), positions AS (
                SELECT user_id,ROW_NUMBER() OVER (ORDER BY (current_step*100+current_points) DESC,ranked_wins DESC,username COLLATE NOCASE) AS position
                FROM ranked WHERE blocked=0
            )
            """;
        String filter=" WHERE r.username LIKE ? AND (?='all' OR (?='eligible' AND r.blocked=0) OR (?='blocked' AND r.blocked=1))";
        try(Connection c=AdminStore.connect()){
            c.setAutoCommit(false);
            var counts=AdminStore.rows(c,cte+"SELECT COUNT(*) AS rankedCount,COALESCE(SUM(CASE WHEN blocked=0 THEN 1 ELSE 0 END),0) AS eligibleCount,COALESCE(SUM(blocked),0) AS blockedCount FROM ranked",now).get(0);
            var total=AdminStore.rows(c,cte+"SELECT COUNT(*) AS total FROM ranked r"+filter,now,"%"+query.trim()+"%",status,status,status).get(0).get("total");
            var rows=AdminStore.rows(c,cte+"SELECT r.*,p.position FROM ranked r LEFT JOIN positions p ON p.user_id=r.user_id"+filter+" ORDER BY CASE WHEN p.position IS NULL THEN 1 ELSE 0 END,p.position,(r.current_step*100+r.current_points) DESC,r.ranked_wins DESC,r.username COLLATE NOCASE LIMIT 50 OFFSET ?",now,"%"+query.trim()+"%",status,status,status,pageOffset);
            for(var row:rows){int step=((Number)row.get("current_step")).intValue();int position=row.get("position") instanceof Number n?n.intValue():0;
                row.put("blocked",((Number)row.get("blocked")).intValue()==1);row.put("rankName",new RankRules.Position(step,((Number)row.get("current_points")).intValue()).name());
                row.put("highestRankName",new RankRules.Position(((Number)row.get("highest_step")).intValue(),((Number)row.get("highest_points")).intValue()).name());
                row.put("title",RankRules.title(step,position));row.put("totalRP",step*100+((Number)row.get("current_points")).intValue());
            }
            c.commit();return Map.of("items",rows,"total",total,"offset",pageOffset,"limit",50,"counts",counts);
        }
    }
    public static String create(String code,String name,String mode,Map<String,Object> config,long now)throws SQLException{
        String id=UUID.randomUUID().toString();try(Connection c=AdminStore.connect()){update(c,"INSERT INTO race_matches(id,room_code,room_name,mode,status,config_json,started_at) VALUES(?,?,?,?,'WAITING',?,?)",id,code,name,mode,JsonUtil.toJson(config),now);}return id;
    }
    public static void join(String match,String username,boolean shield,boolean doubleRp)throws SQLException{
        try(Connection c=AdminStore.connect()){c.setAutoCommit(false);try{
            int id=userId(c,username);ensureRank(c,id);
            update(c,"INSERT INTO race_participation(user_id,match_id) VALUES(?,?)",id,match);
            var cards=availableCards(c,id);
            if(shield&&((Number)cards.get("shields")).intValue()<1||doubleRp&&((Number)cards.get("doubles")).intValue()<1)throw new IllegalArgumentException("Bạn không có đủ thẻ đã chọn.");
            update(c,"INSERT INTO race_players(match_id,username,user_id,bot,use_shield,use_double) VALUES(?,?,?,0,?,?) ON CONFLICT(match_id,username) DO UPDATE SET forfeited=0,use_shield=excluded.use_shield,use_double=excluded.use_double",match,username,id,shield?1:0,doubleRp?1:0);c.commit();
        }catch(Exception e){c.rollback();throw e;}}
    }
    public static void leaveWaiting(String match,String username)throws SQLException{try(Connection c=AdminStore.connect()){c.setAutoCommit(false);try{update(c,"DELETE FROM race_participation WHERE match_id=? AND user_id=(SELECT id FROM users WHERE username=?)",match,username);update(c,"DELETE FROM race_players WHERE match_id=? AND username=?",match,username);c.commit();}catch(Exception e){c.rollback();throw e;}}}
    public static void activate(String match,Map<String,Integer> bots,boolean daily,long now)throws SQLException{
        try(Connection c=AdminStore.connect()){c.setAutoCommit(false);try{
            update(c,"UPDATE race_matches SET status='ACTIVE',started_at=? WHERE id=? AND status='WAITING'",now,match);
            for(String name:bots.keySet())update(c,"INSERT INTO race_players(match_id,username,bot) VALUES(?,?,1)",match,name);
            if(daily)update(c,"INSERT INTO daily_attempts(user_id,day,match_id) SELECT user_id,?,? FROM race_players WHERE match_id=? AND bot=0",today(now),match,match);
            c.commit();
        }catch(Exception e){c.rollback();if(e instanceof SQLException&&e.getMessage().contains("UNIQUE"))throw new IllegalArgumentException("Bạn đã dùng lượt thử thách hôm nay hoặc đang có trận khác.");throw e;}}
    }
    public static void openRound(String match,int round,String token,Question q,long opened,long deadline)throws SQLException{try(Connection c=AdminStore.connect()){update(c,"INSERT INTO race_rounds(match_id,round_no,token,snapshot_json,opened_at,deadline) VALUES(?,?,?,?,?,?)",match,round,token,JsonUtil.toJson(q.snapshot()),opened,deadline);}}
    public static void answer(String match,int round,String name,int answer,long elapsed,int order)throws SQLException{try(Connection c=AdminStore.connect()){update(c,"INSERT INTO race_answers(match_id,round_no,username,answer,elapsed_ms,answer_order) VALUES(?,?,?,?,?,?)",match,round,name,answer,elapsed,order);}}
    public static void closeRound(String match,int round,Map<String,Integer> points)throws SQLException{try(Connection c=AdminStore.connect()){c.setAutoCommit(false);try{for(var entry:points.entrySet())update(c,"UPDATE race_answers SET points=? WHERE match_id=? AND round_no=? AND username=?",entry.getValue(),match,round,entry.getKey());update(c,"UPDATE race_rounds SET closed=1 WHERE match_id=? AND round_no=?",match,round);c.commit();}catch(Exception e){c.rollback();throw e;}}}
    /** Record an intentional departure immediately; RP is settled once with the match. */
    public static void forfeit(String match,String username)throws SQLException{
        try(Connection c=AdminStore.connect()){
            c.setAutoCommit(false);try{
                update(c,"UPDATE race_players SET forfeited=1 WHERE match_id=? AND username=? AND EXISTS(SELECT 1 FROM race_matches WHERE id=? AND status='ACTIVE')",match,username,match);
                // Scope release to the old match so late actions cannot remove a newer membership.
                update(c,"DELETE FROM race_participation WHERE match_id=? AND user_id=(SELECT user_id FROM race_players WHERE match_id=? AND username=? AND forfeited=1)",match,match,username);
                c.commit();
            }catch(Exception e){c.rollback();throw e;}
        }
    }
    public record Result(String username,boolean bot,int score,int correct,int place,boolean forfeited,int rp){}
    public static Map<String,Map<String,Object>> finish(String match,List<Result> results)throws SQLException{
        try(Connection c=AdminStore.connect()){
            c.setAutoCommit(false);try{
                int changed;try(PreparedStatement ps=c.prepareStatement("UPDATE race_matches SET status='COMPLETED',ended_at=? WHERE id=? AND status='ACTIVE'")){ps.setLong(1,System.currentTimeMillis());ps.setString(2,match);changed=ps.executeUpdate();}
                if(changed!=1){c.rollback();return savedResults(c,match);}
                var matchRow=AdminStore.rows(c,"SELECT mode,config_json FROM race_matches WHERE id=?",match).get(0);String mode=(String)matchRow.get("mode");Map<String,Object> config=JsonUtil.parseObject((String)matchRow.get("config_json"));
                @SuppressWarnings("unchecked") Map<String,Object> rules=(Map<String,Object>)config.getOrDefault("rules",settings());Map<String,Map<String,Object>> output=new HashMap<>();
                for(Result r:results){
                    Map<String,Object> result=new LinkedHashMap<>();result.put("rpDelta",0);result.put("baseDelta",0);result.put("card","");result.put("rewarded",false);
                    if(!r.bot()){
                        var player=AdminStore.rows(c,"SELECT user_id,use_shield,use_double FROM race_players WHERE match_id=? AND username=?",match,r.username()).get(0);int id=((Number)player.get("user_id")).intValue();
                        if(mode.equals("RANKED")){
                            var rank=AdminStore.rows(c,"SELECT * FROM player_ranks WHERE user_id=?",id).get(0);RankRules.Position before=new RankRules.Position(((Number)rank.get("current_step")).intValue(),((Number)rank.get("current_points")).intValue());
                            int delta=r.rp();String card="";
                            if(delta<0&&((Number)player.get("use_shield")).intValue()==1){delta=0;card="SHIELD";consume(c,id,"shields");}
                            else if(delta>0&&((Number)player.get("use_double")).intValue()==1){delta*=2;card="DOUBLE";consume(c,id,"doubles");}
                            RankRules.Position after=RankRules.advance(before,delta,((Number)rules.get("rankBuffer")).intValue(),((Number)rules.get("demotionRP")).intValue());RankRules.Position peak=new RankRules.Position(((Number)rank.get("highest_step")).intValue(),((Number)rank.get("highest_points")).intValue());if(after.total()>peak.total())peak=after;
                            update(c,"UPDATE player_ranks SET current_step=?,current_tier=?,current_points=?,highest_step=?,highest_tier=?,highest_points=?,ranked_games=ranked_games+1,ranked_wins=ranked_wins+?,updated_at=? WHERE user_id=?",after.step(),after.tier(),after.rp(),peak.step(),peak.tier(),peak.rp(),r.place()==1&&!r.forfeited()&&r.correct()>0?1:0,System.currentTimeMillis(),id);
                            update(c,"INSERT INTO rank_ledger VALUES(?,?,?,?,?,?,?,?,?)",match,id,r.rp(),delta,before.step(),before.rp(),after.step(),after.rp(),card);
                            result.put("baseDelta",r.rp());result.put("rpDelta",delta);result.put("before",before.json());result.put("after",after.json());result.put("promoted",after.step()>before.step());result.put("demoted",after.step()<before.step());result.put("card",card);
                        }else if(mode.equals("DAILY")&&r.correct()>=((Number)rules.get("dailyCorrect")).intValue()&&!r.forfeited()){
                            update(c,"UPDATE player_cards SET shields=shields+?,doubles=doubles+? WHERE user_id=?",rules.get("shieldReward"),rules.get("doubleReward"),id);update(c,"UPDATE daily_attempts SET rewarded=1 WHERE match_id=? AND user_id=?",match,id);result.put("rewarded",true);
                        }
                    }
                    output.put(r.username(),result);update(c,"UPDATE race_players SET score=?,correct_count=?,placement=?,forfeited=?,result_json=? WHERE match_id=? AND username=?",r.score(),r.correct(),r.place(),r.forfeited()?1:0,JsonUtil.toJson(result),match,r.username());
                }
                // Special titles are computed after all players' RP have been committed to this transaction.
                if(mode.equals("RANKED"))for(Result r:results)if(!r.bot()){
                    var row=AdminStore.rows(c,"SELECT r.user_id,r.current_step,r.highest_title FROM player_ranks r JOIN users u ON u.id=r.user_id WHERE u.username=?",r.username()).get(0);int id=((Number)row.get("user_id")).intValue();String title=RankRules.title(((Number)row.get("current_step")).intValue(),rankPosition(c,id));
                    if(!title.isEmpty()&&!"Huyền Thoại".equals(row.get("highest_title")))update(c,"UPDATE player_ranks SET highest_title=? WHERE user_id=?",title,id);
                }
                update(c,"DELETE FROM race_participation WHERE match_id=?",match);c.commit();return output;
            }catch(Exception e){c.rollback();throw e;}
        }
    }
    private static void consume(Connection c,int id,String column)throws SQLException{try(PreparedStatement ps=c.prepareStatement("UPDATE player_cards SET "+column+"="+column+"-1 WHERE user_id=? AND "+column+">0")){ps.setInt(1,id);if(ps.executeUpdate()!=1)throw new SQLException("Card no longer available");}}
    private static Map<String,Map<String,Object>> savedResults(Connection c,String match)throws SQLException{Map<String,Map<String,Object>> out=new HashMap<>();for(var p:AdminStore.rows(c,"SELECT username,result_json FROM race_players WHERE match_id=?",match))out.put((String)p.get("username"),JsonUtil.parseObject((String)p.get("result_json")));return out;}
    public static void cancel(String match,String reason)throws SQLException{try(Connection c=AdminStore.connect()){c.setAutoCommit(false);try{update(c,"UPDATE race_matches SET status='CANCELLED',ended_at=?,reason=? WHERE id=? AND status IN ('WAITING','ACTIVE')",System.currentTimeMillis(),reason,match);update(c,"DELETE FROM race_participation WHERE match_id=?",match);c.commit();}catch(Exception e){c.rollback();throw e;}}}
    public static List<Map<String,Object>> history(String username,boolean admin)throws SQLException{
        try(Connection c=AdminStore.connect()){
            var rows=AdminStore.rows(c,"SELECT m.*,p.username,p.score,p.correct_count,p.placement,p.forfeited,p.result_json FROM race_matches m JOIN race_players p ON p.match_id=m.id WHERE p.bot=0 AND (?=1 OR p.username=?) ORDER BY m.started_at DESC LIMIT 50",admin?1:0,username);
            for(var row:rows){row.remove("config_json");row.put("result",JsonUtil.parseObject((String)row.remove("result_json")));}return rows;
        }
    }
    public static Map<String,Object> review(String username,String match,boolean admin)throws SQLException{
        try(Connection c=AdminStore.connect()){
            var matches=AdminStore.rows(c,"SELECT id,mode,status,room_name,reason,started_at FROM race_matches WHERE id=?",match);if(matches.isEmpty())throw new IllegalArgumentException("Không tìm thấy trận.");
            if(!admin&&AdminStore.rows(c,"SELECT 1 FROM race_players WHERE match_id=? AND username=? AND bot=0",match,username).isEmpty())throw new IllegalArgumentException("Bạn không thuộc trận này.");
            if(Set.of("ACTIVE","WAITING").contains(matches.get(0).get("status")))throw new IllegalArgumentException("Chỉ xem đáp án sau khi trận kết thúc.");
            List<Map<String,Object>> questions=new ArrayList<>();for(var row:AdminStore.rows(c,"SELECT r.round_no,r.snapshot_json,a.answer,a.elapsed_ms,a.points FROM race_rounds r LEFT JOIN race_answers a ON a.match_id=r.match_id AND a.round_no=r.round_no AND a.username=? WHERE r.match_id=? AND r.closed=1 ORDER BY r.round_no",username,match)){
                var q=JsonUtil.parseObject((String)row.remove("snapshot_json"));row.put("question",q);row.put("correct",row.get("answer") instanceof Number n&&n.intValue()==((Number)q.get("correct")).intValue());questions.add(row);
            }
            return Map.of("match",matches.get(0),"questions",questions,"players",AdminStore.rows(c,"SELECT username,bot,score,correct_count,placement,forfeited,result_json FROM race_players WHERE match_id=? ORDER BY placement,username",match));
        }
    }
    public static void adminPlayer(String actor,String target,String action,Map<String,Object> input,String reason)throws SQLException{
        try(Connection c=AdminStore.connect()){c.setAutoCommit(false);try{
            // Staff may repair even a locked player, but never an administrator.
            var found=AdminStore.rows(c,"SELECT id FROM users WHERE username=? COLLATE NOCASE AND LOWER(role)<>'admin'",target);if(found.isEmpty())throw new IllegalArgumentException("Không tìm thấy người chơi.");int id=((Number)found.get(0).get("id")).intValue();ensureRank(c,id);
            if(!AdminStore.rows(c,"SELECT 1 FROM race_players p JOIN race_matches m ON m.id=p.match_id WHERE p.user_id=? AND m.status IN ('WAITING','ACTIVE')",id).isEmpty())throw new IllegalArgumentException("Người chơi còn trận chưa chốt kết quả. Chờ kết thúc hoặc hủy trận trước khi sửa dữ liệu.");
            Map<String,Object> before=new LinkedHashMap<>();before.put("rank",AdminStore.rows(c,"SELECT * FROM player_ranks WHERE user_id=?",id));before.put("cards",AdminStore.rows(c,"SELECT * FROM player_cards WHERE user_id=?",id));before.put("daily",AdminStore.rows(c,"SELECT * FROM daily_attempts WHERE user_id=? AND day=?",id,today(System.currentTimeMillis())));
            if(action.equals("setRank")){
                RankRules.Position position=new RankRules.Position(integer(input,"step"),integer(input,"rp"));var row=AdminStore.rows(c,"SELECT highest_step,highest_points FROM player_ranks WHERE user_id=?",id).get(0);RankRules.Position peak=new RankRules.Position(((Number)row.get("highest_step")).intValue(),((Number)row.get("highest_points")).intValue());if(position.total()>peak.total())peak=position;
                update(c,"UPDATE player_ranks SET current_step=?,current_tier=?,current_points=?,highest_step=?,highest_tier=?,highest_points=?,updated_at=? WHERE user_id=?",position.step(),position.tier(),position.rp(),peak.step(),peak.tier(),peak.rp(),System.currentTimeMillis(),id);
            }else if(action.equals("setCards")){
                int shields=integer(input,"shields"),doubles=integer(input,"doubles");if(shields<0||shields>999||doubles<0||doubles>999)throw new IllegalArgumentException("Số thẻ cần 0–999.");update(c,"UPDATE player_cards SET shields=?,doubles=? WHERE user_id=?",shields,doubles,id);
            }else if(action.equals("resetDaily"))update(c,"DELETE FROM daily_attempts WHERE user_id=? AND day=?",id,today(System.currentTimeMillis()));
            else throw new IllegalArgumentException("Thao tác không hợp lệ.");
            AdminStore.audit(c,actor,"RACE_"+action.toUpperCase(Locale.ROOT),target,Map.of("reason",reason,"before",before,"request",input));c.commit();
        }catch(Exception e){c.rollback();throw e;}}
    }
    public static int integer(Map<String,Object> input,String key){if(!(input.get(key) instanceof Number n)||n.doubleValue()!=n.intValue())throw new IllegalArgumentException("Trường "+key+" cần số nguyên.");return n.intValue();}
}
