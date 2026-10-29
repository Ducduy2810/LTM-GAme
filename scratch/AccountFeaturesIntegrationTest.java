import vn.game.config.Env;
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
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Isolated tests: no Google network calls, real emails, or production database. */
public class AccountFeaturesIntegrationTest {
    static final HttpClient HTTP=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    static int checks;static String base,adminCookie,adminCsrf;static UserRepository users;static SessionManager sessions;
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    static Map<String,Object> obj(String json){return JsonUtil.parseObject(json);}
    @SuppressWarnings("unchecked") static Map<String,Object> nested(Map<String,Object> m,String key){return (Map<String,Object>)m.get(key);}
    static HttpResponse<String> request(String path,String cookie,Map<String,Object> body,String csrf)throws Exception{
        var b=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(12));
        if(cookie!=null)b.header("Cookie",cookie);if(csrf!=null)b.header("X-CSRF-Token",csrf);
        if(body!=null)b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JsonUtil.toJson(body)));
        return HTTP.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    static String cookie(HttpResponse<?> r){return r.headers().firstValue("set-cookie").orElseThrow().split(";",2)[0];}
    record Player(String cookie,String csrf){}
    static Player login(String name,String password)throws Exception{
        var r=request("/auth/login",null,Map.of("username",name,"password",password),null);check(r.statusCode()==200,"player login: "+r.body());
        String c=cookie(r);Map<String,Object> me=obj(request("/auth/me",c,null,null).body());
        check(Boolean.TRUE.equals(me.get("loggedIn")),"session recognized");return new Player(c,(String)nested(me,"user").get("csrfToken"));
    }
    static Map<String,Object> admin(String path,Map<String,Object> body)throws Exception{
        var r=request("/api/admin"+path,adminCookie,body,adminCsrf);check(r.statusCode()==200,"admin "+path+": "+r.body());return obj(r.body());
    }
    static class Events implements AutoCloseable {
        final InputStream input;final List<Map<String,Object>> events=new CopyOnWriteArrayList<>();
        Events(String cookie)throws Exception{
            var b=HttpRequest.newBuilder(URI.create(base+"/api/events")).timeout(Duration.ofSeconds(60));if(cookie!=null)b.header("Cookie",cookie);
            var r=HTTP.send(b.build(),HttpResponse.BodyHandlers.ofInputStream());check(r.statusCode()==200,"SSE connects");check(r.headers().firstValue("content-type").orElse("").startsWith("text/event-stream"),"SSE content type");input=r.body();
            Thread reader=new Thread(()->{try(var in=new BufferedReader(new InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8))){String type="",line;while((line=in.readLine())!=null){if(line.startsWith("event: "))type=line.substring(7);if(line.startsWith("data: ")){Map<String,Object> e=new HashMap<>(obj(line.substring(6)));e.put("event",type);events.add(e);}}}catch(IOException ignored){}});reader.setDaemon(true);reader.start();
            await("announcement",null);
        }
        Map<String,Object> await(String type,String text)throws Exception{
            long end=System.currentTimeMillis()+7500;
            do{for(Map<String,Object> e:events)if(type.equals(e.get("event"))&&(text==null||JsonUtil.toJson(e).contains(text)))return e;Thread.sleep(20);}while(System.currentTimeMillis()<end);
            throw new AssertionError("Missing event "+type+" / "+text+": "+events);
        }
        public void close()throws IOException{input.close();}
    }
    static final class FakeGoogle extends GoogleOAuthService {
        Map<String,Object> profile=Map.of("sub","google-test-123","email","google@example.com","name","Google Tester","email_verified",true);
        String verifier,redirect;int calls;boolean fail;
        @Override protected Map<String,Object> exchange(String code,Attempt a)throws Exception{calls++;verifier=a.verifier();redirect=a.redirectUri();if(fail)throw new IOException("simulated network failure");return Map.of("access_token","test-token-only");}
        @Override protected Map<String,Object> userInfo(String token){return profile;}
    }
    static void configure(String redirect)throws Exception{
        Files.writeString(Path.of(".env"),"\uFEFFGOOGLE_CLIENT_ID=local-test-id\nGOOGLE_CLIENT_SECRET=local-test-secret\nAPP_BASE_URL="+base+"\nGOOGLE_REDIRECT_URI="+redirect+"\n");Env.load();
    }
    static Map<String,String> query(String uri){Map<String,String> m=new HashMap<>();for(String part:URI.create(uri).getRawQuery().split("&")){String[] kv=part.split("=",2);m.put(URLDecoder.decode(kv[0],java.nio.charset.StandardCharsets.UTF_8),URLDecoder.decode(kv[1],java.nio.charset.StandardCharsets.UTF_8));}return m;}
    static HttpResponse<String> googleFinish(Map<String,String> q,String stateCookie)throws Exception{return request("/auth/google/callback?code=test-code&state="+q.get("state"),stateCookie,null,null);}
    static void google(FakeGoogle oauth)throws Exception{
        var r=request("/auth/google",null,null,null);check(r.statusCode()==302,"OAuth starts redirect");String stateCookie=cookie(r);Map<String,String> q=query(r.headers().firstValue("location").orElseThrow());
        check(q.get("redirect_uri").equals(base+"/auth/google/callback"),"stable exact callback");check(q.get("scope").equals("openid email profile"),"OIDC verified email scope");check(q.get("code_challenge_method").equals("S256")&&q.get("code_challenge").length()==43,"PKCE S256 included");
        check(q.get("state").length()>=40&&stateCookie.endsWith(q.get("state")),"browser-bound random state cookie");check(r.headers().firstValue("set-cookie").orElseThrow().contains("HttpOnly; SameSite=Lax"),"state cookie protections");
        var bad=googleFinish(q,null);check(bad.headers().firstValue("location").orElse("").contains("google_state_invalid"),"callback without cookie rejected");check(oauth.calls==0,"invalid state never exchanges token");
        var ok=googleFinish(q,stateCookie);check(ok.headers().firstValue("location").orElse("").equals("/"),"Google callback completes");String player=cookie(ok);check(player.startsWith("session_id="),"Google callback issues session");
        check(oauth.verifier.length()==43&&oauth.redirect.equals(q.get("redirect_uri")),"token exchange uses captured verifier and callback");
        User g=users.findByGoogleId("google-test-123");check(g!=null&&g.getEmail().equals("google@example.com"),"verified Google account created");check(g.getPasswordHash()==null,"Google-only user has no unknown local password");check(Boolean.TRUE.equals(obj(request("/auth/me",player,null,null).body()).get("loggedIn")),"Google-created player enters portal");
        check(googleFinish(q,stateCookie).headers().firstValue("location").orElse("").contains("google_state_invalid"),"OAuth state cannot be replayed");
        oauth.profile=Map.of("sub","unverified","email","unsafe@example.com","email_verified",false);r=request("/auth/google",null,null,null);q=query(r.headers().firstValue("location").orElseThrow());
        check(googleFinish(q,cookie(r)).headers().firstValue("location").orElse("").contains("google_email_unverified"),"unverified Google email rejected");check(users.findByEmail("unsafe@example.com")==null,"unverified identity never creates account");
        oauth.profile=Map.of("sub","admin-sub","email","operator@admin.local","email_verified",true);r=request("/auth/google",null,null,null);q=query(r.headers().firstValue("location").orElseThrow());
        check(googleFinish(q,cookie(r)).headers().firstValue("location").orElse("").contains("google_account_restricted"),"Google cannot claim admin account");
        oauth.profile=Map.of("sub","linked-1","email","owner@example.com","email_verified",true);r=request("/auth/google",null,null,null);q=query(r.headers().firstValue("location").orElseThrow());googleFinish(q,cookie(r));
        check(users.findByUsername("owner").getGoogleId().equals("linked-1"),"verified email can link existing player");
        try{users.registerOrGetGoogleUser("conflicting-sub","owner@example.com","Other");throw new AssertionError("conflicting link accepted");}catch(IllegalArgumentException expected){checks++;}
        oauth.fail=true;r=request("/auth/google",null,null,null);q=query(r.headers().firstValue("location").orElseThrow());check(googleFinish(q,cookie(r)).headers().firstValue("location").orElse("").contains("google_network_failed"),"Google network failure gets friendly error");oauth.fail=false;
        configure("http://evil.example/auth/google/callback");check(request("/auth/google",null,null,null).headers().firstValue("location").orElse("").contains("google_redirect_config"),"public HTTP OAuth callback rejected");configure(base+"/auth/google/callback");
        oauth.profile=Map.of("sub","google-test-123","email","google@example.com","email_verified",true);
        admin("/users/action",Map.of("userId",g.getId(),"action","lock","minutes",60,"reason","Google account lock reason"));
        r=request("/auth/google",null,null,null);q=query(r.headers().firstValue("location").orElseThrow());var locked=googleFinish(q,cookie(r));check(cookie(locked).startsWith("auth_feedback="),"Google lock reason uses private feedback cookie");
        Map<String,Object> me=obj(request("/auth/me",cookie(locked),null,null).body());check(nested(me,"accountNotice").get("reason").equals("Google account lock reason"),"Google lock reason reaches portal");check(!Boolean.TRUE.equals(me.get("loggedIn")),"locked Google identity has no session");
        admin("/users/action",Map.of("userId",g.getId(),"action","unlock","reason","Test completed"));
    }
    static void announcements()throws Exception{
        Player owner=login("owner","PlayerPass123");Player guest=login("guest","PlayerPass123");
        try(Events anonymous=new Events(null);Events one=new Events(owner.cookie());Events two=new Events(guest.cookie())){
            check(request("/api/admin/announcement",owner.cookie(),Map.of("enabled",true,"text","bad"),owner.csrf()).statusCode()==401,"player cannot broadcast");
            check(request("/api/admin/announcement",adminCookie,Map.of("enabled",true,"text","bad"),null).statusCode()==403,"announcement requires admin CSRF");
            check(request("/api/admin/announcement",adminCookie,Map.of("enabled",true,"text","x".repeat(501)),adminCsrf).statusCode()==400,"announcement length validated");
            admin("/announcement",Map.of("enabled",true,"text","Thông báo học tập <script>không thực thi</script>","minutes",0));
            for(Events stream:List.of(anonymous,one,two))check(Boolean.TRUE.equals(stream.await("announcement","Thông báo học tập").get("enabled")),"global real-time broadcast received");
            check(ServerAnnouncement.read().get("text").toString().contains("Thông báo học tập"),"announcement persisted");
            try(Events later=new Events(null)){check(later.await("announcement","Thông báo học tập")!=null,"new client receives current announcement");}
            admin("/users/action",Map.of("userId",users.findByUsername("owner").getId(),"action","disconnect","reason","Ngắt phiên để kiểm tra"));
            Map<String,Object> notice=one.await("account_notice","Ngắt phiên để kiểm tra");check(nested(notice,"accountNotice").get("kind").equals("DISCONNECT"),"online portal receives disconnect reason");
            check(two.events.stream().noneMatch(e->e.get("event").equals("account_notice"))&&anonymous.events.stream().noneMatch(e->e.get("event").equals("account_notice")),"private reason isolated from other clients");
            check(!Boolean.TRUE.equals(obj(request("/auth/me",owner.cookie(),null,null).body()).get("loggedIn")),"disconnect revokes session");
            admin("/announcement",Map.of("enabled",false,"text","","minutes",0));
            long end=System.currentTimeMillis()+2000;while(two.events.stream().noneMatch(e->e.get("event").equals("announcement")&&!Boolean.TRUE.equals(e.get("enabled")))&&System.currentTimeMillis()<end)Thread.sleep(20);
            check(two.events.stream().anyMatch(e->e.get("event").equals("announcement")&&!Boolean.TRUE.equals(e.get("enabled"))),"clearing announcement reaches clients");
        }
        owner=login("owner","PlayerPass123");Map<String,Object> profile=obj(request("/auth/profile",owner.cookie(),null,null).body());Map<String,Object> notice=nested(profile,"accountNotice");check(notice.get("reason").equals("Ngắt phiên để kiểm tra"),"disconnect reason survives next login");
        check(request("/auth/notice/ack",owner.cookie(),Map.of("noticeId",notice.get("id")),null).statusCode()==403,"notice acknowledgement requires CSRF");
        check(request("/auth/notice/ack",guest.cookie(),Map.of("noticeId",notice.get("id")),guest.csrf()).statusCode()==200,"other user acknowledgement is harmless");check(!PlayerAccountStore.latestNotice("owner").isEmpty(),"other user cannot dismiss private reason");
        check(request("/auth/notice/ack",owner.cookie(),Map.of("noticeId",notice.get("id")),owner.csrf()).statusCode()==200,"owner acknowledges reason");check(PlayerAccountStore.latestNotice("owner").isEmpty(),"ack persists");
        admin("/users/action",Map.of("userId",users.findByUsername("guest").getId(),"action","lock","minutes",60,"reason","Lý do khóa riêng"));
        var locked=request("/auth/login",null,Map.of("username","guest","password","PlayerPass123"),null);check(locked.statusCode()==403&&locked.body().contains("Lý do khóa riêng"),"locked player sees reason after correct password");
        var wrong=request("/auth/login",null,Map.of("username","guest","password","incorrect"),null);check(wrong.statusCode()==400&&!wrong.body().contains("Lý do khóa riêng"),"incorrect password does not disclose lock reason");
        admin("/users/action",Map.of("userId",users.findByUsername("guest").getId(),"action","unlock","reason","Đã xử lý"));
        admin("/announcement",Map.of("enabled",true,"text","Expiration test","minutes",1));
        try(Connection c=AdminStore.connect();PreparedStatement ps=c.prepareStatement("UPDATE app_settings SET value=? WHERE key='announcement'")){Map<String,Object> value=new HashMap<>(ServerAnnouncement.read());value.put("expiresAt",System.currentTimeMillis()-1);ps.setString(1,JsonUtil.toJson(value));ps.executeUpdate();}
        check(Boolean.FALSE.equals(ServerAnnouncement.publicView().get("enabled")),"expired announcement not shown to new clients");
        admin("/announcement",Map.of("enabled",false,"text","","minutes",0));
    }
    static void profilesAndPasswords()throws Exception{
        Player owner=login("owner","PlayerPass123"),other=login("guest","PlayerPass123");
        check(request("/auth/profile",null,null,null).statusCode()==401,"profile requires login");
        Map<String,Object> profile=obj(request("/auth/profile",owner.cookie(),null,null).body());Map<String,Object> rank=nested(profile,"rank");
        check(rank.get("current_tier").equals("BRONZE")&&rank.get("highest_tier").equals("BRONZE"),"new profile current/best starts Bronze 3");check(Boolean.TRUE.equals(profile.get("rankEnabled")),"separate ranked queue enabled");
        check(!profile.containsKey("password_hash")&&!profile.containsKey("salt"),"profile excludes credentials");
        try(Connection c=AdminStore.connect();PreparedStatement ps=c.prepareStatement("UPDATE player_ranks SET current_tier='GOLD',current_step=6,current_points=80,highest_tier='DIAMOND',highest_step=12,highest_points=90 WHERE user_id=?")){ps.setInt(1,users.findByUsername("owner").getId());check(ps.executeUpdate()==1,"rank defaults persisted");}
        profile=obj(request("/auth/profile",owner.cookie(),null,null).body());rank=nested(profile,"rank");check(rank.get("currentName").equals("Vàng 3")&&rank.get("highestName").equals("Kim Cương 3"),"current and historical best read separately");
        for(String game:List.of("ARENA","WHEEL","SURVIVAL")){String id=MatchService.start(game,"RANK_TEST_"+game,false,Map.of(),List.of("owner"));check(MatchService.finish(id,false,"",List.of(new MatchService.Result("owner",10000,1,"WIN"))),"casual match completes "+game);}
        check(nested(obj(request("/auth/profile",owner.cookie(),null,null).body()),"rank").get("current_points").equals(rank.get("current_points")),"all three casual wins do not increase rank");
        check(request("/auth/change-password",owner.cookie(),Map.of("currentPassword","PlayerPass123","newPassword","NextPassword456"),null).statusCode()==403,"password mutation requires CSRF");
        check(request("/auth/change-password",owner.cookie(),Map.of("currentPassword","incorrect","newPassword","NextPassword456"),owner.csrf()).statusCode()==400,"wrong current password rejected");
        check(request("/auth/change-password",owner.cookie(),Map.of("currentPassword","PlayerPass123","newPassword","short"),owner.csrf()).statusCode()==400,"new password length enforced");
        Player second=login("owner","PlayerPass123");
        try(Events stream=new Events(second.cookie())){
            check(request("/auth/change-password",owner.cookie(),Map.of("currentPassword","PlayerPass123","newPassword","NextPassword456"),owner.csrf()).statusCode()==200,"player password changed");
            check(stream.await("account_notice","Mật khẩu")!=null,"other active page receives password change notice");
        }
        check(!Boolean.TRUE.equals(obj(request("/auth/me",second.cookie(),null,null).body()).get("loggedIn")),"all old sessions revoked");
        check(Boolean.TRUE.equals(obj(request("/auth/me",other.cookie(),null,null).body()).get("loggedIn")),"other user session remains active");
        check(request("/auth/login",null,Map.of("username","owner","password","PlayerPass123"),null).statusCode()==400,"old password rejected");login("owner","NextPassword456");
        check(users.findByUsername("owner").getPasswordHash().startsWith("pbkdf2$"),"new password stored with PBKDF2");
        Map<String,Object> integrations=admin("/integrations",null);check(!JsonUtil.toJson(integrations).contains("local-test-secret"),"admin diagnostics never reveal secrets");check(nested(integrations,"google").get("redirectUri").equals(base+"/auth/google/callback"),"diagnostics shows exact configured URI");
    }
    public static void main(String[] args)throws Exception{
        if(!Files.exists(Path.of(".account-test")))throw new IllegalStateException("Use test_account_features.ps1 isolated directory");
        DatabaseManager db=DatabaseManager.getInstance();db.init();users=new UserRepository(db);for(String name:List.of("owner","guest"))users.register(name,name+"@example.com","PlayerPass123");User operator=AdminAccount.create(users,"operator","AdminOnlyPass123!");operator.setEmail("operator@admin.local");users.update(operator);
        sessions=new SessionManager();ArenaGame arena=new ArenaGame(users);WheelGame wheel=new WheelGame(users);SurvivalGame survival=new SurvivalGame(users);FakeGoogle oauth=new FakeGoogle();
        HttpAndWebSocketServer server=new HttpAndWebSocketServer(0,new File(args[0]),users,sessions,arena,wheel,survival,oauth);
        Thread thread=new Thread(()->{try{server.start();}catch(IOException e){throw new RuntimeException(e);}});thread.setDaemon(true);thread.start();while(server.getPort()==0)Thread.sleep(10);base="http://localhost:"+server.getPort();configure(base+"/auth/google/callback");
        try{
            var r=request("/api/admin/login",null,Map.of("username","operator","password","AdminOnlyPass123!"),null);adminCookie=cookie(r);adminCsrf=(String)obj(r.body()).get("csrfToken");
            announcements();google(oauth);profilesAndPasswords();
            System.out.println("PASS: "+checks+" account, live SSE, rank, password and Google OAuth checks (isolated DB; no real email/provider calls).");
            if(args.length>1&&args[1].equals("preview")){System.out.println("PREVIEW_URL="+base);long end=System.currentTimeMillis()+600000;while(!Files.exists(Path.of("stop-preview"))&&System.currentTimeMillis()<end)Thread.sleep(200);}
        }finally{server.stop();arena.close();wheel.close();survival.close();db.close();thread.join(2000);}
    }
}
