package vn.game.server;

import java.net.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import vn.game.db.*;
import vn.game.model.User;
import vn.game.util.JsonUtil;
import vn.game.config.Env;

/** Independent admin HTTP API. All mutations require a dedicated session and CSRF token. */
public final class AdminController {
    public interface Runtime {
        Map<String,Object> rooms();
        int connections();
        void disconnect(String username,String reason);
        void roomAction(String actor,String game,String room,String action,String target,String reason);
        default void announce(Map<String,Object> announcement){}
        default Map<String,Object> integrations(){return Map.of();}
        default Map<String,Object> race()throws SQLException{return Map.of();}
        default void raceAction(String actor,Map<String,Object> input)throws SQLException{}
    }
    public record Reply(int status,Map<String,Object> data,String cookie,byte[] file,String filename) {
        static Reply json(int status,Map<String,Object> data){return new Reply(status,data,null,null,null);}
    }
    private static final class Attempt {int failures;long reset;Attempt(long now){reset=now+15*60_000L;}}
    private final UserRepository users;
    private final Runtime runtime;
    private final AdminSessions sessions=new AdminSessions();
    private final Map<String,Attempt> attempts=new ConcurrentHashMap<>();
    public AdminController(UserRepository users,Runtime runtime){this.users=users;this.runtime=runtime;}
    private static Map<String,String> query(String query){Map<String,String> result=new HashMap<>();if(query!=null)for(String p:query.split("&")){String[] parts=p.split("=",2);result.put(URLDecoder.decode(parts[0],java.nio.charset.StandardCharsets.UTF_8),parts.length<2?"":URLDecoder.decode(parts[1],java.nio.charset.StandardCharsets.UTF_8));}return result;}
    private static int number(Map<String,Object> v,String key,int defaultValue){if(!v.containsKey(key))return defaultValue;if(!(v.get(key) instanceof Number n)||n.doubleValue()!=n.intValue())throw new IllegalArgumentException("Trường "+key+" cần số nguyên.");return n.intValue();}
    private static int offset(Map<String,String> q){try{return Math.max(0,Integer.parseInt(q.getOrDefault("offset","0")));}catch(NumberFormatException e){throw new IllegalArgumentException("Trang dữ liệu không hợp lệ.");}}
    private String cookie(String id,boolean clear){boolean secure=Env.get("APP_BASE_URL").startsWith("https://");return "admin_session="+(clear?"":id)+"; Path=/api/admin; HttpOnly; SameSite=Strict"+(secure?"; Secure":"")+(clear?"; Max-Age=0":"; Max-Age=28800");}
    private boolean validOrigin(Map<String,String> headers){
        if("cross-site".equals(headers.get("sec-fetch-site")))return false;
        String origin=headers.get("origin");if(origin==null)return true;
        try{URI uri=URI.create(origin);String host=headers.get("host");if(uri.getRawAuthority()!=null&&uri.getRawAuthority().equalsIgnoreCase(host))return true;String configured=Env.get("APP_BASE_URL");if(!configured.isBlank()){URI base=URI.create(configured);return Objects.equals(uri.getScheme(),base.getScheme())&&Objects.equals(uri.getRawAuthority(),base.getRawAuthority());}}catch(Exception ignored){}return false;
    }
    private synchronized boolean limited(String key){long now=System.currentTimeMillis();attempts.entrySet().removeIf(e->e.getValue().reset<now);if(attempts.size()>5000)return true;Attempt a=attempts.get(key);return a!=null&&a.failures>=5;}
    private synchronized void failed(String key){attempts.computeIfAbsent(key,k->new Attempt(System.currentTimeMillis())).failures++;}
    @SuppressWarnings("unchecked")
    public Reply handle(String method,String path,String query,String body,Map<String,String> headers,String remoteIp) {
        try{
            if(!Set.of("GET","POST").contains(method))return Reply.json(405,Map.of("error","Phương thức không hỗ trợ."));
            if(!validOrigin(headers))return Reply.json(403,Map.of("error","Nguồn yêu cầu không hợp lệ."));
            Map<String,Object> v=Map.of();
            if("POST".equals(method)){
                if(!headers.getOrDefault("content-type","").toLowerCase(Locale.ROOT).startsWith("application/json"))return Reply.json(415,Map.of("error","Yêu cầu cần JSON."));
                v=JsonUtil.parseObject(body);
            }
            String id=AdminSessions.cookieValue(headers.get("cookie"),"admin_session");
            if("/api/admin/login".equals(path)){
                if(!"POST".equals(method))return Reply.json(405,Map.of("error","Dùng POST để đăng nhập."));
                String username=AdminStore.required(v,"username",64),password=v.get("password") instanceof String p?p:"";
                if(password.length()>128)return Reply.json(400,Map.of("error","Mật khẩu không hợp lệ."));
                String limitKey=remoteIp+":"+username.toLowerCase(Locale.ROOT);
                if(limited(limitKey)||limited(remoteIp))return Reply.json(429,Map.of("error","Đăng nhập sai nhiều lần. Thử lại sau 15 phút."));
                User user=users.findByUsername(username);
                if(user==null||!user.isAdmin()||AdminStore.isBlocked(username)||!user.verifyPassword(password)){
                    failed(limitKey);failed(remoteIp);AdminStore.audit(username,"ADMIN_LOGIN_FAILED","login",Map.of("remote",remoteIp));
                    return Reply.json(401,Map.of("error","Thông tin quản trị không hợp lệ."));
                }
                if(!user.getPasswordHash().startsWith("pbkdf2$")){user.setPasswordHash(User.secureHashPassword(password,user.getSalt()));users.update(user);}
                sessions.remove(id);String newId=sessions.create(user.getUsername(),user.getPasswordHash());AdminSessions.Session s=sessions.get(newId);
                AdminStore.audit(user.getUsername(),"ADMIN_LOGIN","login",Map.of("remote",remoteIp));
                return new Reply(200,Map.of("username",s.username,"csrfToken",s.csrf),cookie(newId,false),null,null);
            }
            AdminSessions.Session session=sessions.get(id,"POST".equals(method)||path.equals("/api/admin/session"));
            User principal=session==null?null:users.findByUsername(session.username);
            if(session==null||principal==null||!principal.isAdmin()||!Objects.equals(principal.getPasswordHash(),session.credentialVersion)||AdminStore.isBlocked(session.username)){
                sessions.remove(id);return new Reply(401,Map.of("error","Phiên quản trị đã hết hạn. Vui lòng đăng nhập."),cookie("",true),null,null);
            }
            if("POST".equals(method)&&!sessions.csrf(session,headers.get("x-csrf-token")))return Reply.json(403,Map.of("error","Mã xác nhận phiên không hợp lệ. Tải lại trang."));
            String actor=session.username;Map<String,String> q=query(query);Object result;
            if("GET".equals(method))switch(path){
                case "/api/admin/session":result=Map.of("username",actor,"csrfToken",session.csrf,"idleMinutes",30);break;
                case "/api/admin/overview":{Map<String,Object> data=new LinkedHashMap<>(AdminStore.overview());data.put("connections",runtime.connections());result=data;break;}
                case "/api/admin/users":result=Map.of("items",AdminStore.users(q.getOrDefault("q",""),offset(q)));break;
                case "/api/admin/rooms":result=runtime.rooms();break;
                case "/api/admin/topics":result=Map.of("items",AdminStore.topics());break;
                case "/api/admin/content":result=Map.of("items",AdminStore.content(q.getOrDefault("kind","QUESTION"),q.getOrDefault("q",""),q.getOrDefault("status","")));break;
                case "/api/admin/revisions":result=Map.of("items",AdminStore.revisions(q.getOrDefault("kind","QUESTION"),Integer.parseInt(q.getOrDefault("id","0"))));break;
                case "/api/admin/matches":result=Map.of("items",AdminStore.matches(q.getOrDefault("q",""),q.getOrDefault("game",""),q.getOrDefault("status",""),offset(q)));break;
                case "/api/admin/match":result=AdminStore.match(q.getOrDefault("id",""));break;
                case "/api/admin/logs":result=Map.of("items",AdminStore.logs(q.getOrDefault("q",""),offset(q)));break;
                case "/api/admin/settings":result=AdminStore.settings();break;
                case "/api/admin/announcement":result=vn.game.model.ServerAnnouncement.read();break;
                case "/api/admin/integrations":result=runtime.integrations();break;
                case "/api/admin/race":result=runtime.race();break;
                case "/api/admin/leaderboard":result=RaceStore.adminLeaderboard(q.getOrDefault("q",""),q.getOrDefault("status","eligible"),offset(q));break;
                case "/api/admin/race/settings":result=RaceStore.settings();break;
                case "/api/admin/daily/settings":result=RaceStore.dailySettings();break;
                case "/api/admin/race/review":result=RaceStore.review(q.getOrDefault("username",""),q.getOrDefault("id",""),true);break;
                case "/api/admin/backups":result=Map.of("items",AdminStore.backups());break;
                case "/api/admin/backup-file":{String name=q.getOrDefault("name","");byte[] file=AdminStore.backupFile(name);AdminStore.audit(actor,"BACKUP_DOWNLOAD",name,Map.of());return new Reply(200,Map.of(),null,file,name);}
                default:return Reply.json(404,Map.of("error","Không tìm thấy chức năng."));
            }else switch(path){
                case "/api/admin/logout":sessions.remove(id);AdminStore.audit(actor,"ADMIN_LOGOUT","session",Map.of());return new Reply(200,Map.of("success",true),cookie("",true),null,null);
                case "/api/admin/password":{
                    String old=v.get("currentPassword") instanceof String s?s:"",password=v.get("newPassword") instanceof String s?s:"";
                    User user=users.findByUsername(actor);if(!user.verifyPassword(old))throw new IllegalArgumentException("Mật khẩu hiện tại không đúng.");
                    if(password.length()<12||password.length()>128||password.isBlank())throw new IllegalArgumentException("Mật khẩu quản trị cần 12–128 ký tự.");
                    user.setSalt(User.generateSalt());user.setPasswordHash(User.secureHashPassword(password,user.getSalt()));users.update(user);
                    sessions.removeUser(actor);AdminStore.audit(actor,"ADMIN_PASSWORD_CHANGE","account",Map.of());return new Reply(200,Map.of("success",true),cookie("",true),null,null);
                }
                case "/api/admin/users/action":{
                    String action=AdminStore.required(v,"action",20),reason=AdminStore.required(v,"reason",500);
                    String target=AdminStore.userAction(actor,number(v,"userId",0),action,number(v,"minutes",60),reason);
                    if(!action.equals("unlock"))runtime.disconnect(target,reason);result=Map.of("success",true);break;
                }
                case "/api/admin/rooms/action":{
                    String game=AdminStore.required(v,"game",15),room=AdminStore.required(v,"roomCode",40),action=AdminStore.required(v,"action",15),reason=AdminStore.required(v,"reason",500),target=AdminStore.text(v,"targetUsername");
                    if(!Set.of("cancel","kick","notice").contains(action)||!Set.of("ARENA","WHEEL","SURVIVAL").contains(game))throw new IllegalArgumentException("Thao tác phòng không hợp lệ.");
                    AdminStore.audit(actor,"ROOM_"+action.toUpperCase(Locale.ROOT)+"_REQUEST",game+":"+room,Map.of("reason",reason,"target",target));
                    runtime.roomAction(actor,game,room,action,target,reason);
                    AdminStore.audit(actor,"ROOM_"+action.toUpperCase(Locale.ROOT)+"_DONE",game+":"+room,Map.of("reason",reason,"target",target));result=Map.of("success",true);break;
                }
                case "/api/admin/topics/save":AdminStore.saveTopic(actor,v);result=Map.of("success",true);break;
                case "/api/admin/content/save":{String kind=AdminStore.required(v,"kind",10);result=Map.of("id",AdminStore.saveContent(actor,kind,v));break;}
                case "/api/admin/content/import":result=AdminStore.importContent(actor,AdminStore.required(v,"kind",10),AdminStore.required(v,"csv",450000),Boolean.TRUE.equals(v.get("dryRun")));break;
                case "/api/admin/settings":AdminStore.saveSettings(actor,v);result=Map.of("success",true);break;
                case "/api/admin/announcement":vn.game.model.ServerAnnouncement.save(actor,v);runtime.announce(vn.game.model.ServerAnnouncement.publicView());result=Map.of("success",true);break;
                case "/api/admin/race/settings":RaceStore.saveSettings(actor,v);result=Map.of("success",true);break;
                case "/api/admin/race/rank-settings":RaceStore.saveScopedSettings(actor,v,"RANK");result=Map.of("success",true);break;
                case "/api/admin/race/service-settings":RaceStore.saveScopedSettings(actor,v,"SERVICE");result=Map.of("success",true);break;
                case "/api/admin/daily/settings":RaceStore.saveScopedSettings(actor,v,"DAILY");result=Map.of("success",true);break;
                case "/api/admin/daily/reset":runtime.raceAction(actor,Map.of("action","resetDaily","targetUsername",AdminStore.required(v,"targetUsername",64),"reason",AdminStore.required(v,"reason",500)));result=Map.of("success",true);break;
                case "/api/admin/race/action":runtime.raceAction(actor,v);result=Map.of("success",true);break;
                case "/api/admin/mail-test":{
                    try{vn.game.util.SmtpTransport.checkConnection();AdminStore.audit(actor,"SMTP_CONNECTION_TEST","server",Map.of("success",true));result=Map.of("success",true,"message","Kết nối TLS và xác thực SMTP thành công. Không gửi email thử.");}
                    catch(vn.game.util.SmtpTransport.Failure e){AdminStore.audit(actor,"SMTP_CONNECTION_TEST","server",Map.of("success",false,"code",e.code));return Reply.json(503,Map.of("error",e.getMessage(),"code",e.code));}
                    break;
                }
                case "/api/admin/backup":result=Map.of("name",AdminStore.backup(actor));break;
                default:return Reply.json(404,Map.of("error","Không tìm thấy chức năng."));
            }
            return Reply.json(200,(Map<String,Object>)result);
        }catch(IllegalArgumentException e){return Reply.json(400,Map.of("error",e.getMessage()==null?"Dữ liệu không hợp lệ.":e.getMessage()));
        }catch(Exception e){System.err.println("Admin API: "+e);return Reply.json(503,Map.of("error","Chưa thực hiện được thao tác. Kiểm tra kết nối và thử lại."));}
    }
}
