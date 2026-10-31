package vn.game.games;

import vn.game.db.*;
import vn.game.model.*;
import vn.game.server.WebSocketClient;
import vn.game.util.JsonUtil;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Single authoritative coordinator for queues, private rooms, bots and daily trials. */
public final class KnowledgeRaceGame implements AutoCloseable {
    private static final class Player {
        final String name;final boolean bot,shield,doubleRp;WebSocketClient client;boolean forfeited;long disconnected;
        int score,correct;long botAt;int botAnswer;
        Player(String name,boolean bot,WebSocketClient client,boolean shield,boolean doubleRp){this.name=name;this.bot=bot;this.client=client;this.shield=shield;this.doubleRp=doubleRp;}
    }
    private static final class Entry {
        final String name,mode;final long joined;final int total;final boolean shield,doubleRp;WebSocketClient client;long disconnected;
        Entry(WebSocketClient client,String mode,long now,int total,boolean shield,boolean doubleRp){this.name=client.getUsername();this.client=client;this.mode=mode;joined=now;this.total=total;this.shield=shield;this.doubleRp=doubleRp;}
    }
    private record Answer(int index,long elapsed,int order){}
    private static final class Room {
        final String id,code,name,mode;String host,phase="WAITING",token="",reason="";final List<Question> questions;final int seconds,botCount;
        final Map<String,Player> players=new LinkedHashMap<>();final Set<String> kicked=new HashSet<>();final Map<String,Answer> answers=new LinkedHashMap<>();
        int round=-1;long opened,deadline,next,finished;Map<String,Map<String,Object>> results=Map.of();final Map<String,Object> rules;
        Room(String id,String code,String name,String mode,String host,List<Question> qs,int seconds,int bots,Map<String,Object> rules){this.id=id;this.code=code;this.name=name;this.mode=mode;this.host=host;questions=qs;this.seconds=seconds;botCount=bots;this.rules=Map.copyOf(rules);}
    }
    private final Map<String,Room> rooms=new LinkedHashMap<>();private final Map<String,Room> membership=new HashMap<>();
    private final Map<String,Entry> queue=new LinkedHashMap<>();private final LongSupplier clock;private final Random random=new java.security.SecureRandom();
    private long nextQueueUpdate;
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"knowledge-race");t.setDaemon(true);return t;});
    public KnowledgeRaceGame(){this(System::currentTimeMillis);}
    public KnowledgeRaceGame(LongSupplier clock){this.clock=clock;}
    public void start(){scheduler.scheduleAtFixedRate(()->{try{tick();}catch(Exception e){System.err.println("Knowledge race tick: "+e.getClass().getSimpleName());}},250,250,TimeUnit.MILLISECONDS);}
    private static boolean terminal(Room room){return Set.of("COMPLETED","CANCELLED").contains(room.phase);}
    private static boolean bool(Map<String,Object> input,String key){return Boolean.TRUE.equals(input.get(key));}
    private static String text(Map<String,Object> input,String key){return AdminStore.text(input,key);}
    private static int number(Map<String,Object> input,String key,int fallback){return input.containsKey(key)?RaceStore.integer(input,key):fallback;}
    private static void send(WebSocketClient client,String type,Map<String,Object> value){if(client==null||client.isClosed())return;Map<String,Object> p=new LinkedHashMap<>(value);p.put("type",type);client.send(JsonUtil.toJson(p));}
    private static void error(WebSocketClient client,String message){send(client,"race_error",Map.of("message",message));}
    private void idle(String username){
        Room previous=membership.get(username);if(queue.containsKey(username)||previous!=null&&!terminal(previous))throw new IllegalArgumentException("Bạn đang ghép trận hoặc ở phòng khác. Rời phòng hiện tại trước.");
        if(previous!=null)membership.remove(username);
    }
    private Map<String,Object> allowed(String mode)throws SQLException{
        var settings=RaceStore.settings();var runtime=AdminStore.settings();
        if(Boolean.TRUE.equals(runtime.get("maintenance")))throw new IllegalArgumentException(AdminStore.maintenanceMessage(runtime));
        if(!Boolean.TRUE.equals(settings.get("enabled"))||mode.equals("RANKED")&&!Boolean.TRUE.equals(settings.get("rankedEnabled"))||mode.equals("DAILY")&&!Boolean.TRUE.equals(settings.get("dailyEnabled")))throw new IllegalArgumentException("Chế độ này đang được admin tạm đóng.");return settings;
    }
    @SuppressWarnings("unchecked") private int rankTotal(String username)throws SQLException{return ((Number)((Map<String,Object>)RaceStore.account(username).get("current")).get("total")).intValue();}
    public synchronized void handle(WebSocketClient client,Map<String,Object> input){
        try{
            String type=text(input,"type"),username=client.getUsername();
            if(username==null||AdminStore.isBlocked(username))throw new IllegalArgumentException("Vui lòng đăng nhập lại.");
            switch(type){
                case "race_resume":resume(client,text(input,"mode"));break;
                case "race_queue":{
                    String mode=text(input,"mode");if(!Set.of("RANKED","CASUAL").contains(mode))throw new IllegalArgumentException("Chọn đấu rank hoặc đấu thường.");allowed(mode);idle(username);
                    boolean shield=mode.equals("RANKED")&&bool(input,"useShield"),doubleRp=mode.equals("RANKED")&&bool(input,"useDouble");
                    var account=RaceStore.account(username);var cards=(Map<?,?>)account.get("cards");if(shield&&((Number)cards.get("shields")).intValue()<1||doubleRp&&((Number)cards.get("doubles")).intValue()<1)throw new IllegalArgumentException("Bạn chưa có đủ thẻ đã chọn.");
                    RaceStore.questions(false,((Number)RaceStore.settings().get("questionCount")).intValue());queue.put(username,new Entry(client,mode,clock.getAsLong(),rankTotal(username),shield,doubleRp));queued(queue.get(username));matchmake(clock.getAsLong());break;
                }
                case "race_cancel_queue":{Entry e=queue.get(username);if(e!=null&&e.client==client)queue.remove(username);send(client,"race_state",Map.of("phase","IDLE"));break;}
                case "race_create":create(client,input);break;
                case "race_join":join(client,text(input,"roomCode").toUpperCase(Locale.ROOT),text(input,"mode"));break;
                case "race_start":{Room room=owned(client);if(!room.phase.equals("WAITING"))throw new IllegalArgumentException("Trận đã bắt đầu.");allowed(room.mode);activate(room,clock.getAsLong());break;}
                case "race_answer":answer(client,input);break;
                case "race_leave":leave(client);break;
                case "race_delete":{Room room=owned(client);if(!room.phase.equals("WAITING"))throw new IllegalArgumentException("Chỉ xóa được phòng đang chờ.");cancel(room,"Chủ phòng xóa phòng chờ");break;}
                case "race_kick":{
                    Room room=owned(client);if(!Set.of("WAITING","QUESTION","RESULT").contains(room.phase))throw new IllegalArgumentException("Không thể kick ở trạng thái này.");kick(room,text(input,"targetUsername"),"Chủ phòng đưa bạn ra khỏi phòng");break;
                }
                case "race_daily":daily(client);break;
                default:throw new IllegalArgumentException("Thao tác Đường đua không hợp lệ.");
            }
        }catch(IllegalArgumentException e){error(client,e.getMessage());}catch(SQLException e){System.err.println("Knowledge race storage: "+e.getErrorCode());error(client,"Chưa lưu được thao tác. Hãy thử lại.");}
    }
    private void resume(WebSocketClient client,String requestedMode)throws SQLException{
        String name=client.getUsername();Entry e=queue.get(name);
        if(!requestedMode.isEmpty()&&!Set.of("RANKED","CASUAL","BOT","DAILY").contains(requestedMode))throw new IllegalArgumentException("Chế độ không hợp lệ.");
        if(e!=null&&!requestedMode.isEmpty()&&!e.mode.equals(requestedMode)){otherMode(client,e.mode);return;}
        if(e!=null){e.client=client;e.disconnected=0;queued(e);return;}
        Room room=membership.get(name);if(room==null){send(client,"race_state",Map.of("phase","IDLE"));return;}
        Player p=room.players.get(name);
        if(p.forfeited&&!terminal(room)){departed(client,room);return;}
        if(!requestedMode.isEmpty()&&!room.mode.equals(requestedMode)){
            if(terminal(room))send(client,"race_state",Map.of("phase","IDLE"));else otherMode(client,room.mode);return;
        }
        p.client=client;p.disconnected=0;broadcast(room);
    }
    private void otherMode(WebSocketClient client,String activeMode){send(client,"race_state",Map.of("phase","OTHER_MODE","activeMode",activeMode,"message","Tài khoản đang ghép trận hoặc chơi ở chế độ khác. Hãy tiếp tục/rời trận đó trước khi tạo trận mới."));}
    private Room owned(WebSocketClient client){Room room=current(client);if(!Objects.equals(room.host,client.getUsername())||!Set.of("CASUAL","BOT").contains(room.mode))throw new IllegalArgumentException("Chỉ chủ phòng đấu thường được thực hiện thao tác này.");return room;}
    private Room current(WebSocketClient client){Room room=membership.get(client.getUsername());if(room==null||room.players.get(client.getUsername()).client!=client||room.players.get(client.getUsername()).forfeited&&!terminal(room))throw new IllegalArgumentException("Bạn không thuộc phòng hiện tại.");return room;}
    private Room newRoom(String mode,String name,String host,int count,int seconds,int bots)throws SQLException{
        List<Question> qs=RaceStore.questions(mode.equals("DAILY"),count);String code=UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);
        var rules=RaceStore.settings();String id=RaceStore.create(code,name,mode,Map.of("questionCount",count,"timeLimit",seconds,"botCount",bots,"topic","NETWORKING","rpRulesVersion",1,"rules",rules),clock.getAsLong());
        Room room=new Room(id,code,name,mode,host,qs,seconds,bots,rules);rooms.put(id,room);return room;
    }
    private void add(Room room,WebSocketClient client,boolean shield,boolean doubleRp)throws SQLException{
        RaceStore.join(room.id,client.getUsername(),shield,doubleRp);Player p=new Player(client.getUsername(),false,client,shield,doubleRp);room.players.put(p.name,p);membership.put(p.name,room);
    }
    private void create(WebSocketClient client,Map<String,Object> input)throws SQLException{
        String mode=bool(input,"withBots")?"BOT":"CASUAL";var settings=allowed(mode);idle(client.getUsername());
        int count=number(input,"questionCount",((Number)settings.get("questionCount")).intValue()),seconds=number(input,"timeLimit",15),bots=number(input,"botCount",mode.equals("BOT")?3:0);
        if(count<10||count>15||seconds<3||seconds>120||bots<0||bots>3||mode.equals("BOT")&&bots==0)throw new IllegalArgumentException("Cần 10–15 câu, 3–120 giây và 0–3 bot (chơi máy cần ít nhất 1 bot).");
        String name=text(input,"roomName");if(name.isBlank())name="Đường đua của "+client.getUsername();if(name.length()>80)throw new IllegalArgumentException("Tên phòng tối đa 80 ký tự.");
        Room room=newRoom(mode,name,client.getUsername(),count,seconds,bots);try{add(room,client,false,false);broadcast(room);}catch(Exception e){cancel(room,"Không thể tạo phòng");throw e;}
    }
    private void join(WebSocketClient client,String code,String requestedMode)throws SQLException{
        allowed("CASUAL");idle(client.getUsername());Room room=rooms.values().stream().filter(r->r.code.equals(code)&&r.phase.equals("WAITING")&&Set.of("CASUAL","BOT").contains(r.mode)).findFirst().orElseThrow(()->new IllegalArgumentException("Không tìm thấy phòng chờ."));
        if(!requestedMode.isEmpty()&&(!requestedMode.equals(room.mode)||requestedMode.equals("CASUAL")&&room.botCount>0))throw new IllegalArgumentException("Phòng này thuộc chế độ khác. Chọn phòng đấu thường không bot.");
        if(room.kicked.contains(client.getUsername()))throw new IllegalArgumentException("Bạn đã bị kick khỏi phòng này.");if(room.players.size()+room.botCount>=4)throw new IllegalArgumentException("Phòng đã đủ chỗ.");add(room,client,false,false);broadcast(room);
    }
    private void daily(WebSocketClient client)throws SQLException{
        var settings=allowed("DAILY");idle(client.getUsername());
        @SuppressWarnings("unchecked") var status=(Map<String,Object>)RaceStore.account(client.getUsername()).get("daily");if(Boolean.TRUE.equals(status.get("used")))throw new IllegalArgumentException("Bạn đã dùng lượt thử thách hôm nay. Lượt mới bắt đầu lúc 00:00 giờ Việt Nam.");
        Room room=newRoom("DAILY","Thử thách hằng ngày",null,10,((Number)settings.get("dailyTimeLimit")).intValue(),0);
        try{add(room,client,false,false);activate(room,clock.getAsLong());}catch(Exception e){cancel(room,"Không thể bắt đầu thử thách");throw e;}
    }
    private void queued(Entry e)throws SQLException{
        queued(e,RaceStore.settings());
    }
    private void queued(Entry e,Map<String,Object> s){
        send(e.client,"race_state",Map.of("phase","QUEUED","mode",e.mode,"joinedAt",e.joined,"fillAt",e.joined+((Number)s.get("queueSeconds")).longValue()*1000,"waitingPlayers",queue.values().stream().filter(x->x.mode.equals(e.mode)&&x.client!=null).count(),"serverTime",clock.getAsLong()));
    }
    private void matchmake(long now)throws SQLException{
        if(queue.isEmpty())return;var settings=RaceStore.settings();int target=((Number)settings.get("targetPlayers")).intValue();long delay=((Number)settings.get("queueSeconds")).longValue()*1000;
        for(Entry oldest:new ArrayList<>(queue.values())){
            if(!queue.containsKey(oldest.name)||oldest.client==null)continue;
            try{allowed(oldest.mode);}catch(IllegalArgumentException e){queue.remove(oldest.name);send(oldest.client,"race_state",Map.of("phase","IDLE","message",e.getMessage()));continue;}
            int tolerance=200+(int)Math.min(2000,(now-oldest.joined)/1000*15);
            List<Entry> group=queue.values().stream().filter(e->e.client!=null&&e.mode.equals(oldest.mode)&&(!e.mode.equals("RANKED")||Math.abs(e.total-oldest.total)<=tolerance)).sorted(Comparator.comparingInt(e->Math.abs(e.total-oldest.total))).limit(target).toList();
            if(group.size()<target&&now-oldest.joined<delay)continue;
            Room room=null;
            try{
                room=newRoom(oldest.mode,oldest.mode.equals("RANKED")?"Đường đua xếp hạng":"Đường đua đấu thường",null,((Number)settings.get("questionCount")).intValue(),((Number)settings.get("timeLimit")).intValue(),target-group.size());
                for(Entry e:group){if(AdminStore.isBlocked(e.name))throw new IllegalArgumentException("Có tài khoản vừa bị hạn chế.");add(room,e.client,e.shield,e.doubleRp);}
                activate(room,now);for(Entry e:group)queue.remove(e.name);
            }catch(Exception ex){if(room!=null)cancel(room,"Không thể khởi tạo trận");for(Entry e:group){queue.remove(e.name);send(e.client,"race_state",Map.of("phase","IDLE","message",ex instanceof IllegalArgumentException?ex.getMessage():"Chưa tạo được trận, hãy ghép lại."));}}
        }
    }
    private void activate(Room room,long now)throws SQLException{
        int humans=room.players.size();if(!room.mode.equals("DAILY")&&humans+room.botCount<2)throw new IllegalArgumentException("Cần ít nhất 2 người hoặc thêm bot.");
        Map<String,Integer> bots=new LinkedHashMap<>();for(int i=0;i<room.botCount;i++){String name="🤖 Máy "+(i+1);while(room.players.containsKey(name))name+="_";bots.put(name,i);}
        RaceStore.activate(room.id,bots,room.mode.equals("DAILY"),now);for(String name:bots.keySet())room.players.put(name,new Player(name,true,null,false,false));
        try{nextRound(room,now);}catch(SQLException e){cancel(room,"Không lưu được câu hỏi; trận không tính RP");throw e;}
    }
    private void nextRound(Room room,long now)throws SQLException{
        int next=room.round+1;String token=UUID.randomUUID().toString();long deadline=now+room.seconds*1000L;
        RaceStore.openRound(room.id,next+1,token,room.questions.get(next),now,deadline);
        room.round=next;room.phase="QUESTION";room.token=token;room.opened=now;room.deadline=deadline;room.answers.clear();
        Question q=room.questions.get(next);double accuracy=switch(q.getDifficulty()){case "EASY"->.82;case "HARD"->.46;default->.65;};
        for(Player p:room.players.values())if(p.bot){p.botAt=now+Math.max(300,(long)(room.seconds*1000*(.22+random.nextDouble()*.65)));p.botAnswer=random.nextDouble()<accuracy?q.getCorrect():(q.getCorrect()+1+random.nextInt(3))%4;}
        broadcast(room);
    }
    private void answer(WebSocketClient client,Map<String,Object> input)throws SQLException{
        Room room=current(client);long now=clock.getAsLong();
        if(!room.phase.equals("QUESTION")||now>=room.deadline||!room.id.equals(text(input,"matchId"))||!room.token.equals(text(input,"questionToken")))throw new IllegalArgumentException("Câu hỏi đã hết lượt hoặc không thuộc trận hiện tại.");
        int index=RaceStore.integer(input,"answer");if(index<0||index>3)throw new IllegalArgumentException("Đáp án cần là A–D.");
        recordAnswer(room,room.players.get(client.getUsername()),index,now);broadcast(room);if(allAnswered(room))resolve(room,now);
    }
    private void recordAnswer(Room room,Player p,int index,long now)throws SQLException{
        if(room.answers.containsKey(p.name))throw new IllegalArgumentException("Bạn đã chốt đáp án câu này.");int order=room.answers.size()+1;long elapsed=Math.max(0,now-room.opened);
        RaceStore.answer(room.id,room.round+1,p.name,index,elapsed,order);room.answers.put(p.name,new Answer(index,elapsed,order));
    }
    private boolean allAnswered(Room room){return room.players.values().stream().filter(p->!p.forfeited).allMatch(p->room.answers.containsKey(p.name));}
    private void resolve(Room room,long now)throws SQLException{
        Question q=room.questions.get(room.round);String first=room.answers.entrySet().stream().filter(e->e.getValue().index()==q.getCorrect()&&!room.players.get(e.getKey()).forfeited).min(Comparator.comparingInt(e->e.getValue().order())).map(Map.Entry::getKey).orElse("");
        Map<String,Integer> points=new LinkedHashMap<>();for(var e:room.answers.entrySet()){Player p=room.players.get(e.getKey());int awarded=!p.forfeited&&e.getValue().index()==q.getCorrect()?(room.mode.equals("DAILY")?2:e.getKey().equals(first)?3:2):0;points.put(p.name,awarded);}
        RaceStore.closeRound(room.id,room.round+1,points);
        for(var e:points.entrySet()){Player p=room.players.get(e.getKey());p.score+=e.getValue();if(e.getValue()>0)p.correct++;}
        room.phase="RESULT";room.next=now+2200;broadcast(room);
    }
    private List<RaceStore.Result> placements(Room room){
        List<Player> ordered=room.players.values().stream().sorted(Comparator.comparing((Player p)->p.forfeited).thenComparing(Comparator.comparingInt((Player p)->p.score).reversed()).thenComparing(p->p.name)).toList();
        List<RaceStore.Result> results=new ArrayList<>();int index=0;
        while(index<ordered.size()){
            Player first=ordered.get(index);int count=1;while(index+count<ordered.size()&&ordered.get(index+count).forfeited==first.forfeited&&(first.forfeited||ordered.get(index+count).score==first.score))count++;
            int rp=room.mode.equals("RANKED")&&ordered.size()>=2?RankRules.tiedReward(ordered.size(),index,count,room.rules):0;
            for(int i=index;i<index+count;i++){Player p=ordered.get(i);int earned=p.forfeited||p.correct==0?Math.min(0,rp):rp;results.add(new RaceStore.Result(p.name,p.bot,p.score,p.correct,index+1,p.forfeited,earned));}index+=count;
        }return results;
    }
    private void finish(Room room,long now)throws SQLException{room.results=RaceStore.finish(room.id,placements(room));room.phase="COMPLETED";room.finished=now;broadcast(room);}
    public synchronized void tick()throws SQLException{
        long now=clock.getAsLong();
        for(Entry e:new ArrayList<>(queue.values()))if(e.client==null&&now-e.disconnected>=10000)queue.remove(e.name);
        matchmake(now);
        if(!queue.isEmpty()&&now>=nextQueueUpdate){var settings=RaceStore.settings();for(Entry e:queue.values())queued(e,settings);nextQueueUpdate=now+1000;}
        for(Room room:new ArrayList<>(rooms.values())){
            try{
                if(room.phase.equals("QUESTION")){
                    for(Player p:room.players.values())if(p.bot&&!room.answers.containsKey(p.name)&&p.botAt<=now&&now<room.deadline)recordAnswer(room,p,p.botAnswer,now);
                    if(now>=room.deadline||allAnswered(room))resolve(room,now);
                }else if(room.phase.equals("RESULT")&&now>=room.next){if(room.round+1>=room.questions.size())finish(room,now);else nextRound(room,now);}
                else if(terminal(room)&&now-room.finished>600000){rooms.remove(room.id);membership.entrySet().removeIf(e->e.getValue()==room);}
                else if(room.phase.equals("WAITING"))for(Player p:new ArrayList<>(room.players.values()))if(p.client==null&&now-p.disconnected>30000)removeWaiting(room,p);
            }catch(SQLException e){System.err.println("Race state persistence failed; cancelling match "+room.id);cancel(room,"Lỗi lưu dữ liệu; trận không tính RP hoặc dùng thẻ");}
        }
    }
    private Map<String,Object> state(Room room,Player self){
        Map<String,Object> state=new LinkedHashMap<>();state.put("phase",room.phase);state.put("mode",room.mode);state.put("matchId",room.id);state.put("roomCode",room.code);state.put("roomName",room.name);state.put("host",Objects.toString(room.host,""));state.put("me",self.name);state.put("round",room.round+1);state.put("questionCount",room.questions.size());state.put("seconds",room.seconds);state.put("botCount",room.botCount);state.put("serverTime",clock.getAsLong());state.put("deadline",room.deadline);state.put("reason",room.reason);
        var resultList=placements(room);List<Map<String,Object>> players=new ArrayList<>();for(Player p:room.players.values()){
            var row=new LinkedHashMap<String,Object>();row.put("username",p.name);row.put("bot",p.bot);row.put("score",p.score);row.put("correct",p.correct);row.put("answered",room.answers.containsKey(p.name));row.put("online",p.bot||p.client!=null);row.put("forfeited",p.forfeited);row.put("placement",resultList.stream().filter(r->r.username().equals(p.name)).findFirst().orElseThrow().place());row.put("result",room.results.getOrDefault(p.name,Map.of()));players.add(row);
        }state.put("players",players);
        if(room.round>=0&&!terminal(room)){
            Question q=room.questions.get(room.round);state.put("question",Map.of("text",q.getText(),"options",q.getOptions(),"difficulty",q.getDifficulty(),"token",room.token));
            Answer answer=room.answers.get(self.name);state.put("myAnswer",answer==null?-1:answer.index());
            if(room.phase.equals("RESULT"))state.put("solution",Map.of("correctIndex",q.getCorrect(),"explanation",q.getExplanation(),"correct",answer!=null&&answer.index()==q.getCorrect()));
        }return state;
    }
    private void broadcast(Room room){for(Player p:room.players.values())if(!p.bot&&(!p.forfeited||terminal(room)))send(p.client,"race_state",state(room,p));}
    private void removeWaiting(Room room,Player p)throws SQLException{
        RaceStore.leaveWaiting(room.id,p.name);room.players.remove(p.name);membership.remove(p.name,room);
        if(room.players.isEmpty()){cancel(room,"Phòng chờ không còn người chơi");return;}
        if(p.name.equals(room.host))room.host=room.players.values().iterator().next().name;broadcast(room);
    }
    private void leave(WebSocketClient client)throws SQLException{
        Entry e=queue.get(client.getUsername());if(e!=null){if(e.client==client)queue.remove(e.name);send(client,"race_state",Map.of("phase","IDLE"));return;}
        Room room=current(client);Player p=room.players.get(client.getUsername());
        if(room.phase.equals("WAITING"))removeWaiting(room,p);else if(terminal(room))membership.remove(p.name,room);else{
            RaceStore.forfeit(room.id,p.name);
            p.forfeited=true;p.client=null;broadcast(room);departed(client,room);return;
        }
        send(client,"race_state",Map.of("phase","IDLE","matchId",room.id,"message","Đã rời phòng."));
    }
    private void departed(WebSocketClient client,Room room){
        String message=room.mode.equals("RANKED")?"Bạn đã bỏ cuộc và bị tính thua trận xếp hạng này. Không thể vào lại trận. RP được chốt khi trận kết thúc.":"Bạn đã rời trận và không thể vào lại. Kết quả được ghi khi trận kết thúc.";
        send(client,"race_state",Map.of("phase","IDLE","mode",room.mode,"matchId",room.id,"forfeited",true,"message",message));
    }
    private void cancel(Room room,String reason)throws SQLException{RaceStore.cancel(room.id,reason);room.phase="CANCELLED";room.reason=reason;room.finished=clock.getAsLong();broadcast(room);}
    private void kick(Room room,String target,String reason)throws SQLException{
        Player p=room.players.get(target);if(p==null||p.bot||target.equals(room.host))throw new IllegalArgumentException("Chọn người chơi khác trong phòng.");room.kicked.add(target);notifyKicked(p.client,reason);
        if(room.phase.equals("WAITING"))removeWaiting(room,p);else{p.forfeited=true;p.client=null;broadcast(room);}
    }
    private void notifyKicked(WebSocketClient client,String reason){
        send(client,"race_kicked",Map.of("reason",reason,"message","Bạn đã bị kick khỏi phòng. Lý do: "+reason));
    }
    public synchronized void disconnect(WebSocketClient client){
        Entry e=queue.get(client.getUsername());if(e!=null&&e.client==client){e.client=null;e.disconnected=clock.getAsLong();}
        Room room=membership.get(client.getUsername());if(room!=null){Player p=room.players.get(client.getUsername());if(p.client==client){p.client=null;p.disconnected=clock.getAsLong();broadcast(room);}}
    }
    public synchronized void moderate(String username,String reason){
        Entry e=queue.remove(username);if(e!=null)send(e.client,"race_state",Map.of("phase","IDLE","queueCancelled",true,"reason",reason,"message","Lượt tìm trận đã bị hủy. Lý do: "+reason));Room room=membership.get(username);if(room==null||terminal(room))return;Player p=room.players.get(username);
        try{if(room.phase.equals("WAITING"))removeWaiting(room,p);else{p.forfeited=true;p.client=null;broadcast(room);}}catch(SQLException ex){System.err.println("Race moderation storage failed");}
    }
    public synchronized List<Map<String,Object>> lobby(){return rooms.values().stream().filter(r->r.phase.equals("WAITING")&&Set.of("CASUAL","BOT").contains(r.mode)).map(r->Map.<String,Object>of("roomCode",r.code,"roomName",r.name,"players",r.players.size(),"bots",r.botCount,"host",Objects.toString(r.host,""),"questionCount",r.questions.size(),"seconds",r.seconds)).toList();}
    public synchronized Map<String,Object> adminView()throws SQLException{
        List<Map<String,Object>> list=new ArrayList<>();for(Room room:rooms.values())if(!terminal(room)){Player first=room.players.values().stream().findFirst().orElse(null);if(first!=null)list.add(state(room,first));}
        List<Map<String,Object>> waiting=new ArrayList<>();for(Entry e:queue.values())waiting.add(Map.of("username",e.name,"mode",e.mode,"joinedAt",e.joined,"rankTotal",e.total,"online",e.client!=null));
        try(var c=AdminStore.connect()){return Map.of("settings",RaceStore.settings(),"rooms",list,"queue",waiting,"history",RaceStore.history("",true),"players",AdminStore.rows(c,"SELECT u.username,r.current_step,r.current_points,r.highest_step,r.highest_points,r.ranked_games,r.ranked_wins,COALESCE(c.shields,0) AS shields,COALESCE(c.doubles,0) AS doubles FROM users u LEFT JOIN player_ranks r ON r.user_id=u.id LEFT JOIN player_cards c ON c.user_id=u.id WHERE LOWER(u.role)<>'admin' ORDER BY u.id DESC LIMIT 200"));}
    }
    public synchronized void adminAction(String actor,Map<String,Object> input)throws SQLException{
        String action=text(input,"action"),target=text(input,"targetUsername"),reason=AdminStore.required(input,"reason",500);
        if(Set.of("setRank","setCards","resetDaily").contains(action)){
            if(queue.containsKey(target))throw new IllegalArgumentException("Người chơi đang ghép trận. Xóa khỏi hàng chờ trước.");RaceStore.adminPlayer(actor,target,action,input,reason);return;
        }
        if(action.equals("dequeue")){Entry e=queue.remove(target);if(e==null)throw new IllegalArgumentException("Người chơi không có trong hàng chờ.");send(e.client,"race_state",Map.of("phase","IDLE","queueCancelled",true,"reason",reason,"message","Lượt tìm trận đã bị hủy. Lý do: "+reason));}
        else{
            Room room=rooms.get(text(input,"matchId"));if(room==null||terminal(room))throw new IllegalArgumentException("Trận không còn hoạt động.");
            if(action.equals("cancel"))cancel(room,"Admin: "+reason);
            else if(action.equals("kick")){if(target.equals(room.host)){
                Player p=room.players.get(target);notifyKicked(p.client,reason);if(room.phase.equals("WAITING"))removeWaiting(room,p);else{p.forfeited=true;p.client=null;broadcast(room);}
            }else kick(room,target,reason);}
            else if(action.equals("notice"))for(Player p:room.players.values())send(p.client,"server_notice",Map.of("message",reason));
            else throw new IllegalArgumentException("Thao tác không hợp lệ.");
        }
        AdminStore.audit(actor,"RACE_"+action.toUpperCase(Locale.ROOT),text(input,"matchId"),input);
    }
    public synchronized void close(){scheduler.shutdownNow();for(Room room:rooms.values())if(!terminal(room))try{cancel(room,"Máy chủ dừng; không tính RP hoặc dùng thẻ");}catch(SQLException ignored){}queue.clear();}
    public synchronized void maintenance(String reason)throws SQLException{
        for(Entry e:queue.values())send(e.client,"race_state",Map.of("phase","IDLE","queueCancelled",true,"reason",reason,"message",reason));
        queue.clear();for(Room room:rooms.values())if(!terminal(room))cancel(room,reason);
    }
}
