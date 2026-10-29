package vn.game.server;

import vn.game.config.Env;
import vn.game.util.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Browser-bound authorization code flow; provider tokens never leave this server. */
public class GoogleOAuthService {
    public record Start(String state,String location,String redirectUri) {}
    public record Identity(String id,String email,String name) {}
    protected record Attempt(String redirectUri,String verifier,String clientId,String clientSecret,long expires) {}
    public static final class Failure extends Exception {
        public final String code;
        public Failure(String code){super(code);this.code=code;}
    }
    private final Map<String,Attempt> pending=new ConcurrentHashMap<>();
    private static String random(){byte[] b=new byte[32];new SecureRandom().nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    private static String encode(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
    public String redirectUri(int port)throws Failure{
        String configured=Env.get("GOOGLE_REDIRECT_URI");
        if(configured.isBlank())configured=Env.get("APP_BASE_URL","http://localhost:"+port).replaceAll("/+$","")+"/auth/google/callback";
        try{
            URI uri=URI.create(configured);String host=uri.getHost();boolean local="localhost".equalsIgnoreCase(host)||"127.0.0.1".equals(host)||"[::1]".equals(host)||"::1".equals(host);
            if(host==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!"/auth/google/callback".equals(uri.getPath())||!("https".equals(uri.getScheme())||local&&"http".equals(uri.getScheme())))throw new IllegalArgumentException();
            return uri.toString();
        }catch(IllegalArgumentException e){throw new Failure("google_redirect_config");}
    }
    public Map<String,Object> diagnostics(int port){
        Env.load();String redirect="";String issue="";try{redirect=redirectUri(port);}catch(Failure e){issue=e.code;}
        return Map.of("clientIdConfigured",!Env.get("GOOGLE_CLIENT_ID").isBlank(),"clientSecretConfigured",!Env.get("GOOGLE_CLIENT_SECRET").isBlank(),"redirectUri",redirect,"configurationError",issue);
    }
    public Start begin(int port)throws Failure{
        Env.load();String id=Env.get("GOOGLE_CLIENT_ID"),secret=Env.get("GOOGLE_CLIENT_SECRET");
        if(id.isBlank()||secret.isBlank())throw new Failure("google_not_configured");
        long now=System.currentTimeMillis();pending.entrySet().removeIf(e->e.getValue().expires()<now);
        if(pending.size()>=2000)throw new Failure("google_busy");
        String redirect=redirectUri(port),state=random(),verifier=random();pending.put(state,new Attempt(redirect,verifier,id,secret,now+600000));
        try{
            String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            String url="https://accounts.google.com/o/oauth2/v2/auth?client_id="+encode(id)+"&redirect_uri="+encode(redirect)+"&response_type=code&scope="+encode("openid email profile")+"&prompt=select_account&state="+encode(state)+"&code_challenge="+encode(challenge)+"&code_challenge_method=S256";
            return new Start(state,url,redirect);
        }catch(NoSuchAlgorithmException e){pending.remove(state);throw new IllegalStateException(e);}
    }
    public Identity finish(String code,String state,String cookie)throws Failure{
        if(state==null||cookie==null||state.length()>128||!MessageDigest.isEqual(state.getBytes(StandardCharsets.US_ASCII),cookie.getBytes(StandardCharsets.US_ASCII)))throw new Failure("google_state_invalid");
        Attempt attempt=pending.remove(state);
        if(attempt==null||attempt.expires()<System.currentTimeMillis())throw new Failure("google_state_invalid");
        if(code==null||code.isBlank())throw new Failure("google_cancelled");
        try{
            Map<String,Object> token=exchange(code,attempt);String access=token.get("access_token") instanceof String s?s:"";
            if(access.isBlank())throw new Failure("google_token_failed");
            Map<String,Object> profile=userInfo(access);
            String sub=profile.get("sub") instanceof String s?s:"",email=profile.get("email") instanceof String s?s.toLowerCase(Locale.ROOT):"",name=profile.get("name") instanceof String s?s:"";
            if(sub.isBlank()||sub.length()>255||!PasswordResetMailer.isEmail(email)||!Boolean.TRUE.equals(profile.get("email_verified")))throw new Failure("google_email_unverified");
            return new Identity(sub,email,name);
        }catch(Failure e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new Failure("google_network_failed");}
        catch(Exception e){System.err.println("Google OAuth request failed: "+e.getClass().getSimpleName());throw new Failure("google_network_failed");}
    }
    protected Map<String,Object> exchange(String code,Attempt a)throws Exception{
        String body="code="+encode(code)+"&client_id="+encode(a.clientId())+"&client_secret="+encode(a.clientSecret())+"&redirect_uri="+encode(a.redirectUri())+"&grant_type=authorization_code&code_verifier="+encode(a.verifier());
        HttpRequest request=HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token")).timeout(Duration.ofSeconds(20)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response=client().send(request,HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200){System.err.println("Google token endpoint returned HTTP "+response.statusCode());throw new Failure("google_token_failed");}
        return JsonUtil.parseObject(response.body());
    }
    protected Map<String,Object> userInfo(String access)throws Exception{
        HttpRequest request=HttpRequest.newBuilder(URI.create("https://openidconnect.googleapis.com/v1/userinfo")).timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+access).GET().build();
        HttpResponse<String> response=client().send(request,HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200)throw new Failure("google_profile_failed");return JsonUtil.parseObject(response.body());
    }
    private HttpClient client(){return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();}
}
