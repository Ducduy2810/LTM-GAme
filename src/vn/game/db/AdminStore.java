package vn.game.db;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import vn.game.model.*;
import vn.game.util.JsonUtil;

/** Admin persistence uses independent transactions, never the shared auth connection. */
public final class AdminStore {
    private AdminStore() {}
    public static Connection connect() throws SQLException {
        Connection c=DriverManager.getConnection("jdbc:sqlite:data/game.db");
        try(Statement st=c.createStatement()) { st.execute("PRAGMA foreign_keys=ON"); st.execute("PRAGMA busy_timeout=5000"); }
        return c;
    }
    public static void init() throws Exception {
        try(Connection c=connect();Statement st=c.createStatement()) {
            Set<String> columns=new HashSet<>();
            try(ResultSet rs=st.executeQuery("PRAGMA table_info(users)")){while(rs.next())columns.add(rs.getString("name"));}
            if(!columns.contains("blocked_until"))st.execute("ALTER TABLE users ADD COLUMN blocked_until INTEGER NOT NULL DEFAULT 0");
            if(!columns.contains("blocked_reason"))st.execute("ALTER TABLE users ADD COLUMN blocked_reason TEXT NOT NULL DEFAULT ''");
            st.execute("CREATE TABLE IF NOT EXISTS admin_audit_logs(id INTEGER PRIMARY KEY AUTOINCREMENT,actor TEXT NOT NULL,action TEXT NOT NULL,target TEXT NOT NULL,details TEXT NOT NULL,created_at INTEGER NOT NULL)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_audit_time ON admin_audit_logs(created_at DESC)");
            st.execute("CREATE TABLE IF NOT EXISTS app_settings(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS education_topics(scope TEXT NOT NULL,code TEXT NOT NULL,name TEXT NOT NULL,active INTEGER NOT NULL DEFAULT 1,PRIMARY KEY(scope,code))");
            st.execute("CREATE TABLE IF NOT EXISTS game_questions(id INTEGER PRIMARY KEY AUTOINCREMENT,text TEXT NOT NULL,options_json TEXT NOT NULL,correct INTEGER NOT NULL CHECK(correct BETWEEN 0 AND 3),topic TEXT NOT NULL,difficulty TEXT NOT NULL,explanation TEXT NOT NULL DEFAULT '',status TEXT NOT NULL DEFAULT 'DRAFT',revision INTEGER NOT NULL DEFAULT 1,updated_at INTEGER NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS wheel_phrases(id INTEGER PRIMARY KEY AUTOINCREMENT,phrase TEXT NOT NULL,hint TEXT NOT NULL,topic TEXT NOT NULL,explanation TEXT NOT NULL DEFAULT '',status TEXT NOT NULL DEFAULT 'DRAFT',revision INTEGER NOT NULL DEFAULT 1,updated_at INTEGER NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS content_revisions(id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL,content_id INTEGER NOT NULL,revision INTEGER NOT NULL,payload_json TEXT NOT NULL,actor TEXT NOT NULL,created_at INTEGER NOT NULL)");
            boolean hasUsage=false;try(ResultSet rs=st.executeQuery("PRAGMA table_info(game_questions)")){while(rs.next())if("usage".equals(rs.getString("name")))hasUsage=true;}
            if(!hasUsage)st.execute("ALTER TABLE game_questions ADD COLUMN usage TEXT NOT NULL DEFAULT 'LEGACY'");
        }
        PlayerAccountStore.init();seedContent(); reloadContent();
    }
    private static void seedContent() throws Exception {
        try(Connection c=connect()) {
            c.setAutoCommit(false);
            try {
                try(PreparedStatement ps=c.prepareStatement("SELECT value FROM app_settings WHERE key='content_seeded'" );ResultSet rs=ps.executeQuery()){if(rs.next()){c.rollback();return;}}
                String[][] topics={{"QUESTION","NETWORKING","Lập trình & mạng máy tính"},{"QUESTION","SCIENCE","Khoa học & công nghệ"},{"QUESTION","HISTORY","Lịch sử"},{"WHEEL","IT","Công nghệ thông tin & Mạng"},{"WHEEL","GEOGRAPHY","Địa lý & du lịch"},{"WHEEL","PROVERBS","Ca dao & tục ngữ"},{"WHEEL","GENERAL","Văn hóa & kiến thức chung"}};
                try(PreparedStatement ps=c.prepareStatement("INSERT OR IGNORE INTO education_topics(scope,code,name) VALUES(?,?,?)")){for(String[] t:topics){for(int i=0;i<3;i++)ps.setString(i+1,t[i]);ps.addBatch();}ps.executeBatch();}
                try(PreparedStatement ps=c.prepareStatement("INSERT INTO game_questions(text,options_json,correct,topic,difficulty,explanation,status,updated_at) VALUES(?,?,?,?,?,?,'PUBLISHED',?)")){
                    for(Question q:QuestionBank.seedQuestions()){ps.setString(1,q.getText());ps.setString(2,JsonUtil.toJson(q.getOptions()));ps.setInt(3,q.getCorrect());ps.setString(4,q.getTopic());ps.setString(5,q.getDifficulty());ps.setString(6,q.getExplanation());ps.setLong(7,System.currentTimeMillis());ps.addBatch();}ps.executeBatch();
                }
                try(PreparedStatement ps=c.prepareStatement("INSERT INTO wheel_phrases(phrase,hint,topic,explanation,status,updated_at) VALUES(?,?,?,?,'PUBLISHED',?)")){
                    for(WheelPhrase p:WheelPhraseBank.seedPhrases()){ps.setString(1,p.getPhrase());ps.setString(2,p.getHint());ps.setString(3,p.getTopic());ps.setString(4,p.getExplanation());ps.setLong(5,System.currentTimeMillis());ps.addBatch();}ps.executeBatch();
                }
                try(Statement st=c.createStatement()){st.executeUpdate("INSERT INTO app_settings(key,value) VALUES('content_seeded','true')");}
                c.commit();
            }catch(Exception e){c.rollback();throw e;}
        }
    }
    @SuppressWarnings("unchecked")
    public static synchronized void reloadContent() throws SQLException {
        List<Question> questions=new ArrayList<>();List<WheelPhrase> phrases=new ArrayList<>();
        try(Connection c=connect();Statement st=c.createStatement()) {
            try(ResultSet rs=st.executeQuery("SELECT q.* FROM game_questions q JOIN education_topics t ON t.scope='QUESTION' AND t.code=q.topic WHERE q.status='PUBLISHED' AND t.active=1 AND q.usage<>'RACE' ORDER BY q.id")){
                while(rs.next())questions.add(new Question(rs.getInt("id"),rs.getString("text"),(List<String>)(List<?>)JsonUtil.parse(rs.getString("options_json")),rs.getInt("correct"),rs.getString("topic"),rs.getString("difficulty"),rs.getString("explanation")));
            }
            try(ResultSet rs=st.executeQuery("SELECT p.* FROM wheel_phrases p JOIN education_topics t ON t.scope='WHEEL' AND t.code=p.topic WHERE p.status='PUBLISHED' AND t.active=1 ORDER BY p.id")){
                while(rs.next())phrases.add(new WheelPhrase(rs.getString("topic"),rs.getString("hint"),rs.getString("phrase"),rs.getString("explanation")));
            }
        }
        QuestionBank.useDatabaseQuestions(questions);WheelPhraseBank.useDatabasePhrases(phrases);
    }
    public static List<Map<String,Object>> rows(Connection c,String sql,Object...params) throws SQLException {
        List<Map<String,Object>> result=new ArrayList<>();
        try(PreparedStatement ps=c.prepareStatement(sql)){
            for(int i=0;i<params.length;i++)ps.setObject(i+1,params[i]);
            try(ResultSet rs=ps.executeQuery()){int count=rs.getMetaData().getColumnCount();while(rs.next()){Map<String,Object> row=new LinkedHashMap<>();for(int i=1;i<=count;i++)row.put(rs.getMetaData().getColumnLabel(i),rs.getObject(i));result.add(row);}}
        }return result;
    }
    public static void audit(Connection c,String actor,String action,String target,Object details) throws SQLException {
        try(PreparedStatement ps=c.prepareStatement("INSERT INTO admin_audit_logs(actor,action,target,details,created_at) VALUES(?,?,?,?,?)")){
            ps.setString(1,actor);ps.setString(2,action);ps.setString(3,target);ps.setString(4,JsonUtil.toJson(details));ps.setLong(5,System.currentTimeMillis());ps.executeUpdate();
        }
    }
    public static void audit(String actor,String action,String target,Object details) throws SQLException {try(Connection c=connect()){audit(c,actor,action,target,details);}}
    public static boolean isBlocked(String username) {
        try(Connection c=connect();PreparedStatement ps=c.prepareStatement("SELECT blocked_until FROM users WHERE username=?")){
            ps.setString(1,username);try(ResultSet rs=ps.executeQuery()){if(!rs.next())return true;long until=rs.getLong(1);return until==-1||until>System.currentTimeMillis();}
        }catch(SQLException e){return true;}
    }
    public static Map<String,Object> overview() throws SQLException {
        try(Connection c=connect()){
            return Map.of("counts",rows(c,"SELECT (SELECT COUNT(*) FROM users WHERE LOWER(role)<>'admin') AS players,(SELECT COUNT(*) FROM users WHERE blocked_until=-1 OR blocked_until>?) AS blocked,(SELECT COUNT(*) FROM matches) AS matches,(SELECT COUNT(*) FROM game_questions) AS questions,(SELECT COUNT(*) FROM wheel_phrases) AS phrases",System.currentTimeMillis()).get(0),
                    "recentMatches",rows(c,"SELECT id,game_type,room_code,status,started_at,ended_at FROM matches ORDER BY started_at DESC LIMIT 8"),"settings",settings());
        }
    }
    public static List<Map<String,Object>> users(String query,int offset) throws SQLException {
        try(Connection c=connect()){return rows(c,"SELECT id,username,email,role,blocked_until,blocked_reason FROM users WHERE username LIKE ? OR email LIKE ? ORDER BY id DESC LIMIT 50 OFFSET ?","%"+query+"%","%"+query+"%",offset);}
    }
    public static String userAction(String actor,int id,String action,long minutes,String reason) throws SQLException {
        try(Connection c=connect()){
            c.setAutoCommit(false);
            try{
                List<Map<String,Object>> found=rows(c,"SELECT username,role FROM users WHERE id=?",id);
                if(found.isEmpty())throw new IllegalArgumentException("Không tìm thấy tài khoản.");
                Map<String,Object> target=found.get(0);String username=(String)target.get("username");
                if("admin".equalsIgnoreCase(String.valueOf(target.get("role"))))throw new IllegalArgumentException("Không được thao tác lên tài khoản quản trị tại mục người chơi.");
                if(!Set.of("lock","unlock","disconnect").contains(action))throw new IllegalArgumentException("Thao tác tài khoản không hợp lệ.");
                if("lock".equals(action)){
                    if(minutes!=-1&&(minutes<1||minutes>525600))throw new IllegalArgumentException("Thời gian khóa không hợp lệ.");
                    try(PreparedStatement ps=c.prepareStatement("UPDATE users SET blocked_until=?,blocked_reason=? WHERE id=?")){
                        ps.setLong(1,minutes==-1?-1:System.currentTimeMillis()+minutes*60_000);ps.setString(2,reason);ps.setInt(3,id);ps.executeUpdate();
                    }
                }else if("unlock".equals(action))try(PreparedStatement ps=c.prepareStatement("UPDATE users SET blocked_until=0,blocked_reason='' WHERE id=?")){ps.setInt(1,id);ps.executeUpdate();}
                PlayerAccountStore.addNotice(c,id,action.toUpperCase(Locale.ROOT),reason);
                audit(c,actor,"USER_"+action.toUpperCase(Locale.ROOT),username,Map.of("reason",reason,"minutes",minutes));c.commit();return username;
            }catch(Exception e){c.rollback();throw e;}
        }
    }
    public static List<Map<String,Object>> topics() throws SQLException {try(Connection c=connect()){return rows(c,"SELECT * FROM education_topics ORDER BY scope,name");}}
    public static synchronized void saveTopic(String actor,Map<String,Object> value) throws SQLException {
        String scope=text(value,"scope"),code=text(value,"code").toUpperCase(Locale.ROOT),name=required(value,"name",100);
        if(!Set.of("QUESTION","WHEEL").contains(scope)||!code.matches("[A-Z][A-Z0-9_]{1,31}")||Set.of("ALL","MIX","RANDOM").contains(code))throw new IllegalArgumentException("Mã chủ đề không hợp lệ (2–32 ký tự A–Z, 0–9, _).");
        boolean active=Boolean.TRUE.equals(value.get("active"));
        try(Connection c=connect()){
            c.setAutoCommit(false);try{
                try(PreparedStatement ps=c.prepareStatement("INSERT INTO education_topics(scope,code,name,active) VALUES(?,?,?,?) ON CONFLICT(scope,code) DO UPDATE SET name=excluded.name,active=excluded.active")){
                    ps.setString(1,scope);ps.setString(2,code);ps.setString(3,name);ps.setInt(4,active?1:0);ps.executeUpdate();
                }audit(c,actor,"TOPIC_SAVE",scope+":"+code,value);c.commit();
            }catch(Exception e){c.rollback();throw e;}
        }reloadContent();
    }
    public static List<Map<String,Object>> content(String kind,String query,String status) throws SQLException {
        String table=table(kind),field="QUESTION".equals(kind)?"text":"phrase";
        try(Connection c=connect()){
            List<Map<String,Object>> result=rows(c,"SELECT * FROM "+table+" WHERE ("+field+" LIKE ? OR topic LIKE ?) AND (?='' OR status=?) ORDER BY id DESC LIMIT 500","%"+query+"%","%"+query+"%",status,status);
            for(Map<String,Object> row:result)if(row.containsKey("options_json"))row.put("options",JsonUtil.parse((String)row.remove("options_json")));
            return result;
        }
    }
    private static String table(String kind){if("QUESTION".equals(kind))return "game_questions";if("WHEEL".equals(kind))return "wheel_phrases";throw new IllegalArgumentException("Loại nội dung không hợp lệ.");}
    public static String text(Map<String,Object> value,String key){return value.get(key) instanceof String s?s.trim():"";}
    public static String required(Map<String,Object> value,String key,int max){String s=text(value,key);if(s.isEmpty()||s.length()>max)throw new IllegalArgumentException("Trường "+key+" cần 1–"+max+" ký tự.");return s;}
    private static Map<String,Object> validateContent(Connection c,String kind,Map<String,Object> input) throws SQLException {
        table(kind);Map<String,Object> v=new LinkedHashMap<>(input);
        String topic=required(v,"topic",32).toUpperCase(Locale.ROOT),status=required(v,"status",12);
        if(!Set.of("DRAFT","PUBLISHED","ARCHIVED").contains(status))throw new IllegalArgumentException("Trạng thái nội dung không hợp lệ.");
        List<Map<String,Object>> topics=rows(c,"SELECT active FROM education_topics WHERE scope=? AND code=?",kind,topic);
        if(topics.isEmpty()||("PUBLISHED".equals(status)&&((Number)topics.get(0).get("active")).intValue()!=1))throw new IllegalArgumentException("Chủ đề không tồn tại hoặc đang bị ẩn.");
        v.put("topic",topic);v.put("status",status);String explanation=text(v,"explanation");if(explanation.length()>4000)throw new IllegalArgumentException("Lời giải quá dài.");v.put("explanation",explanation);
        if("QUESTION".equals(kind)){
            String usage=text(v,"usage");if(usage.isEmpty())usage="LEGACY";
            if(!Set.of("LEGACY","RACE","BOTH").contains(usage))throw new IllegalArgumentException("Phạm vi câu hỏi không hợp lệ.");
            if(!usage.equals("LEGACY")&&(!topic.equals("NETWORKING")||explanation.isBlank()))throw new IllegalArgumentException("Câu hỏi Đường đua cần chủ đề NETWORKING và lời giải lý thuyết.");v.put("usage",usage);
            v.put("text",required(v,"text",2000));String difficulty=required(v,"difficulty",10);
            if(!Set.of("EASY","MEDIUM","HARD").contains(difficulty))throw new IllegalArgumentException("Độ khó không hợp lệ.");
            if(!(v.get("options") instanceof List<?> list)||list.size()!=4)throw new IllegalArgumentException("Cần đúng 4 lựa chọn.");
            List<String> options=new ArrayList<>();for(Object o:list){if(!(o instanceof String s)||s.trim().isEmpty()||s.length()>1000)throw new IllegalArgumentException("Lựa chọn cần 1–1000 ký tự.");options.add(s.trim());}v.put("options",options);
            if(!(v.get("correct") instanceof Number n)||n.doubleValue()!=n.intValue()||n.intValue()<0||n.intValue()>3)throw new IllegalArgumentException("Đáp án đúng phải là A–D.");
        }else{
            String phrase=java.text.Normalizer.normalize(required(v,"phrase",150),java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+","").replace('đ','d').replace('Đ','D').toUpperCase(Locale.ROOT).replaceAll("\\s+"," ");
            if(!phrase.matches("[A-Z]+(?: [A-Z]+)*"))throw new IllegalArgumentException("Ô chữ chỉ gồm chữ cái và khoảng trắng.");
            v.put("phrase",phrase);v.put("hint",required(v,"hint",1000));
        }return v;
    }
    private static int writeContent(Connection c,String actor,String kind,Map<String,Object> input) throws SQLException {
        Map<String,Object> v=validateContent(c,kind,input);String table=table(kind);int id=input.get("id") instanceof Number n?n.intValue():0;
        boolean question="QUESTION".equals(kind);int revision=1;
        if(id>0){List<Map<String,Object>> old=rows(c,"SELECT revision FROM "+table+" WHERE id=?",id);if(old.isEmpty())throw new IllegalArgumentException("Nội dung không còn tồn tại.");int expected=input.get("revision") instanceof Number n?n.intValue():0;int actual=((Number)old.get(0).get("revision")).intValue();if(expected!=actual)throw new IllegalArgumentException("Nội dung đã được thay đổi. Hãy tải lại trước khi lưu.");revision=actual+1;}
        String fields=question?"text=?,options_json=?,correct=?,topic=?,difficulty=?,usage=?,explanation=?,status=?,revision=?,updated_at=?":"phrase=?,hint=?,topic=?,explanation=?,status=?,revision=?,updated_at=?";
        String sql=id>0?"UPDATE "+table+" SET "+fields+" WHERE id=?":"INSERT INTO "+table+(question?"(text,options_json,correct,topic,difficulty,usage,explanation,status,revision,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)":"(phrase,hint,topic,explanation,status,revision,updated_at) VALUES(?,?,?,?,?,?,?)");
        try(PreparedStatement ps=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)){
            int i=1;if(question){ps.setString(i++,(String)v.get("text"));ps.setString(i++,JsonUtil.toJson(v.get("options")));ps.setInt(i++,((Number)v.get("correct")).intValue());ps.setString(i++,(String)v.get("topic"));ps.setString(i++,(String)v.get("difficulty"));ps.setString(i++,(String)v.get("usage"));}
            else{ps.setString(i++,(String)v.get("phrase"));ps.setString(i++,(String)v.get("hint"));ps.setString(i++,(String)v.get("topic"));}
            ps.setString(i++,(String)v.get("explanation"));ps.setString(i++,(String)v.get("status"));ps.setInt(i++,revision);ps.setLong(i++,System.currentTimeMillis());if(id>0)ps.setInt(i,id);ps.executeUpdate();
            if(id==0)try(ResultSet keys=ps.getGeneratedKeys()){if(keys.next())id=keys.getInt(1);else throw new SQLException("Missing content id");}
        }
        v.put("id",id);v.put("revision",revision);
        try(PreparedStatement ps=c.prepareStatement("INSERT INTO content_revisions(kind,content_id,revision,payload_json,actor,created_at) VALUES(?,?,?,?,?,?)")){
            ps.setString(1,kind);ps.setInt(2,id);ps.setInt(3,revision);ps.setString(4,JsonUtil.toJson(v));ps.setString(5,actor);ps.setLong(6,System.currentTimeMillis());ps.executeUpdate();
        }audit(c,actor,"CONTENT_SAVE",kind+":"+id,v);return id;
    }
    public static synchronized int saveContent(String actor,String kind,Map<String,Object> input) throws SQLException {
        int id;try(Connection c=connect()){
            c.setAutoCommit(false);try{id=writeContent(c,actor,kind,input);c.commit();}catch(Exception e){c.rollback();throw e;}
        }reloadContent();return id;
    }
    public static synchronized Map<String,Object> importContent(String actor,String kind,String csv,boolean dryRun) throws SQLException {
        List<Map<String,Object>> values=vn.game.util.ContentCsv.parse(kind,csv);
        try(Connection c=connect()){
            c.setAutoCommit(false);try{
                for(int i=0;i<values.size();i++)try{validateContent(c,kind,values.get(i));}catch(IllegalArgumentException e){throw new IllegalArgumentException("Dòng dữ liệu "+(i+1)+": "+e.getMessage());}
                if(dryRun){c.rollback();return Map.of("count",values.size(),"preview",values.subList(0,Math.min(5,values.size())));}
                for(Map<String,Object> v:values)writeContent(c,actor,kind,v);
                audit(c,actor,"CONTENT_IMPORT",kind,Map.of("count",values.size()));c.commit();
            }catch(Exception e){c.rollback();throw e;}
        }reloadContent();return Map.of("count",values.size());
    }
    public static List<Map<String,Object>> revisions(String kind,int id) throws SQLException {table(kind);try(Connection c=connect()){return rows(c,"SELECT revision,actor,created_at,payload_json FROM content_revisions WHERE kind=? AND content_id=? ORDER BY revision DESC LIMIT 30",kind,id);}}
    public static List<Map<String,Object>> matches(String query,String game,String status,int offset) throws SQLException {
        try(Connection c=connect()){return rows(c,"SELECT m.id,m.game_type,m.mode,m.room_code,m.status,m.started_at,m.ended_at,m.reason, (SELECT COUNT(*) FROM match_players p WHERE p.match_id=m.id) AS player_count FROM matches m WHERE (?='' OR m.game_type=?) AND (?='' OR m.status=?) AND (?='' OR EXISTS(SELECT 1 FROM match_players p WHERE p.match_id=m.id AND p.username LIKE ?)) ORDER BY m.started_at DESC LIMIT 50 OFFSET ?",game,game,status,status,query,"%"+query+"%",offset);}
    }
    public static Map<String,Object> match(String id) throws SQLException {
        try(Connection c=connect()){List<Map<String,Object>> found=rows(c,"SELECT * FROM matches WHERE id=?",id);if(found.isEmpty())throw new IllegalArgumentException("Không tìm thấy trận.");return Map.of("match",found.get(0),"players",rows(c,"SELECT username,score,placement,outcome FROM match_players WHERE match_id=? ORDER BY placement,score DESC",id));}
    }
    public static List<Map<String,Object>> logs(String query,int offset) throws SQLException {try(Connection c=connect()){return rows(c,"SELECT * FROM admin_audit_logs WHERE actor LIKE ? OR action LIKE ? OR target LIKE ? ORDER BY id DESC LIMIT 50 OFFSET ?","%"+query+"%","%"+query+"%","%"+query+"%",offset);}}
    @SuppressWarnings("unchecked")
    public static Map<String,Object> settings() throws SQLException {
        Map<String,Object> result=new LinkedHashMap<>();result.put("maintenance",false);result.put("message","Máy chủ đang bảo trì. Vui lòng quay lại sau.");
        result.put("enabled",Map.of("ARENA",true,"WHEEL",true,"SURVIVAL",true));
        result.put("defaults",Map.of("ARENA",Map.of("questionCount",10,"timeLimit",5,"mainPlayerTimeLimit",15,"botCount",0),"WHEEL",Map.of("timeLimit",10,"botCount",0),"SURVIVAL",Map.of("startingHp",100,"totalRounds",25)));
        try(Connection c=connect();PreparedStatement ps=c.prepareStatement("SELECT value FROM app_settings WHERE key='runtime'" );ResultSet rs=ps.executeQuery()){if(rs.next())result.putAll((Map<String,Object>)JsonUtil.parse(rs.getString(1)));}return result;
    }
    @SuppressWarnings("unchecked")
    public static Map<String,Object> applyDefaults(String game,Map<String,Object> request) {
        try{Map<String,Object> result=new HashMap<>((Map<String,Object>)((Map<?,?>)settings().get("defaults")).get(game));result.putAll(request);return result;}catch(SQLException e){throw new IllegalStateException("Chưa tải được cấu hình máy chủ.");}
    }
    public static String newMatchDenied(String game){try{Map<String,Object> s=settings();if(Boolean.TRUE.equals(s.get("maintenance")))return (String)s.get("message");if(!Boolean.TRUE.equals(((Map<?,?>)s.get("enabled")).get(game)))return "Trò chơi tạm ngừng nhận trận mới.";return null;}catch(SQLException e){return "Chưa tải được trạng thái máy chủ.";}}
    public static void saveSettings(String actor,Map<String,Object> v) throws SQLException {
        if(!(v.get("maintenance") instanceof Boolean)||!(v.get("enabled") instanceof Map<?,?> enabled)||!(v.get("defaults") instanceof Map<?,?> defaults))throw new IllegalArgumentException("Cấu hình không hợp lệ.");
        required(v,"message",500);
        for(String game:List.of("ARENA","WHEEL","SURVIVAL")){
            if(!(enabled.get(game) instanceof Boolean)||!(defaults.get(game) instanceof Map<?,?> d))throw new IllegalArgumentException("Thiếu cấu hình "+game);
            if(game.equals("ARENA")){range(d,"questionCount",1,100);range(d,"timeLimit",1,120);range(d,"mainPlayerTimeLimit",1,120);range(d,"botCount",0,100);}
            if(game.equals("WHEEL")){range(d,"timeLimit",5,120);range(d,"botCount",0,3);}
            if(game.equals("SURVIVAL")){range(d,"startingHp",1,1000);range(d,"totalRounds",5,25);}
        }
        try(Connection c=connect()){c.setAutoCommit(false);try{
            try(PreparedStatement ps=c.prepareStatement("INSERT INTO app_settings(key,value) VALUES('runtime',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")){ps.setString(1,JsonUtil.toJson(v));ps.executeUpdate();}
            audit(c,actor,"SETTINGS_SAVE","server",v);c.commit();
        }catch(Exception e){c.rollback();throw e;}}
    }
    private static void range(Map<?,?> v,String key,int min,int max){if(!(v.get(key) instanceof Number n)||n.doubleValue()!=n.intValue()||n.intValue()<min||n.intValue()>max)throw new IllegalArgumentException(key+" phải trong khoảng "+min+"–"+max);}
    public static String backup(String actor) throws Exception {
        Path folder=Path.of("data/backups");Files.createDirectories(folder);String name="admin-"+System.currentTimeMillis()+"-"+UUID.randomUUID().toString().substring(0,8)+".db";
        try(Connection c=connect();PreparedStatement ps=c.prepareStatement("VACUUM INTO ?")){ps.setString(1,folder.resolve(name).toString());ps.execute();}
        audit(actor,"DATABASE_BACKUP",name,Map.of());return name;
    }
    public static List<Map<String,Object>> backups() throws IOException {
        Path folder=Path.of("data/backups");if(!Files.isDirectory(folder))return List.of();List<Map<String,Object>> result=new ArrayList<>();
        try(var stream=Files.list(folder)){for(Path p:stream.filter(p->p.getFileName().toString().matches("(?:admin|before-history|before-admin|before-race)-[A-Za-z0-9-]+\\.db")).sorted(Comparator.reverseOrder()).limit(50).toList())result.add(Map.of("name",p.getFileName().toString(),"size",Files.size(p),"created_at",Files.getLastModifiedTime(p).toMillis()));}return result;
    }
    public static byte[] backupFile(String name) throws IOException {
        if(!name.matches("(?:admin|before-history|before-admin|before-race)-[A-Za-z0-9-]+\\.db"))throw new IllegalArgumentException("Tên bản sao lưu không hợp lệ.");
        Path folder=Path.of("data/backups").toAbsolutePath().normalize(),file=folder.resolve(name).normalize();
        if(!file.getParent().equals(folder)||!Files.isRegularFile(file)||Files.isSymbolicLink(file))throw new IllegalArgumentException("Không tìm thấy bản sao lưu.");return Files.readAllBytes(file);
    }
}
