package vn.game.util;

import vn.game.config.Env;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** SMTP over verified TLS, with implicit TLS or STARTTLS; no plaintext credentials. */
public final class SmtpTransport {
    private static final int TIMEOUT=15000;
    public static final class Failure extends IOException {
        public final String code;
        Failure(String code,String message){super(message);this.code=code;}
    }
    private record Config(String sender,String password,String host,int port,boolean startTls){}
    private static Config config()throws Failure{
        String sender=Env.get("GMAIL_USER").trim(),password=Env.get("GMAIL_APP_PASSWORD").replaceAll("\\s+","");
        if(!PasswordResetMailer.isEmail(sender)||password.isEmpty())throw new Failure("SMTP_CONFIG","Máy chủ chưa cấu hình tài khoản gửi email.");
        int port;try{port=Integer.parseInt(Env.get("SMTP_PORT","465"));if(port<1||port>65535)throw new NumberFormatException();}catch(NumberFormatException e){throw new Failure("SMTP_CONFIG","SMTP_PORT không hợp lệ.");}
        String security=Env.get("SMTP_SECURITY","auto").toUpperCase(Locale.ROOT);
        if(!Set.of("AUTO","SSL","TLS","STARTTLS").contains(security))throw new Failure("SMTP_CONFIG","SMTP_SECURITY cần auto, ssl hoặc starttls.");
        boolean startTls=security.equals("STARTTLS")||security.equals("TLS")||security.equals("AUTO")&&port==587;
        return new Config(sender,password,Env.get("SMTP_HOST","smtp.gmail.com"),port,startTls);
    }
    public static Map<String,Object> diagnostics(){
        Env.load();Map<String,Object> result=new LinkedHashMap<>();result.put("senderConfigured",PasswordResetMailer.isEmail(Env.get("GMAIL_USER").trim()));result.put("passwordConfigured",!Env.get("GMAIL_APP_PASSWORD").isBlank());result.put("host",Env.get("SMTP_HOST","smtp.gmail.com"));result.put("port",Env.get("SMTP_PORT","465"));result.put("security",Env.get("SMTP_SECURITY","auto"));return result;
    }
    private static final class Connection implements AutoCloseable {
        Socket socket;BufferedReader in;BufferedWriter out;
        void streams()throws IOException{in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));out=new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.US_ASCII));}
        void command(String value,int...codes)throws IOException{out.write(value+"\r\n");out.flush();expect(in,codes);}
        public void close(){try{if(socket!=null)socket.close();}catch(IOException ignored){}}
    }
    private static SSLSocket secure(Socket raw,Config c)throws IOException{
        SSLSocket ssl=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(raw,c.host(),c.port(),true);
        ssl.setSoTimeout(TIMEOUT);SSLParameters p=ssl.getSSLParameters();p.setEndpointIdentificationAlgorithm("HTTPS");ssl.setSSLParameters(p);ssl.startHandshake();return ssl;
    }
    private static Connection connect(Config config)throws IOException{
        Connection c=new Connection();String stage="CONNECT";
        try{
            c.socket=new Socket();c.socket.setSoTimeout(TIMEOUT);c.socket.connect(new InetSocketAddress(config.host(),config.port()),TIMEOUT);
            if(!config.startTls()){stage="TLS";c.socket=secure(c.socket,config);}
            c.streams();stage="GREETING";expect(c.in,220);c.command("EHLO [127.0.0.1]",250);
            if(config.startTls()){stage="TLS";c.command("STARTTLS",220);c.socket=secure(c.socket,config);c.streams();c.command("EHLO [127.0.0.1]",250);}
            stage="AUTH";c.command("AUTH LOGIN",334);c.command(base64(config.sender()),334);c.command(base64(config.password()),235);return c;
        }catch(IOException e){c.close();if(e instanceof Failure f&&f.code.equals("SMTP_AUTH"))throw f;
            if(e instanceof SSLException||stage.equals("TLS"))throw new Failure("SMTP_TLS","Không thiết lập được kết nối TLS với máy chủ email.");
            if(stage.equals("AUTH"))throw new Failure("SMTP_AUTH","Máy chủ email từ chối đăng nhập. Kiểm tra địa chỉ gửi và mật khẩu ứng dụng Gmail.");
            if(stage.equals("CONNECT"))throw new Failure("SMTP_CONNECT","Không kết nối được máy chủ email. Kiểm tra quyền truy cập mạng, cổng SMTP hoặc cấu hình hosting.");
            throw new Failure("SMTP_PROTOCOL","Máy chủ email trả về phản hồi không hợp lệ.");
        }
    }
    public static void checkConnection()throws IOException{Env.load();try(Connection c=connect(config())){try{c.command("QUIT",221);}catch(IOException ignored){}}}
    public static void send(String recipient,String subject,String body)throws IOException{
        Config cfg=config();if(!PasswordResetMailer.isEmail(recipient))throw new Failure("SMTP_RECIPIENT","Địa chỉ nhận email không hợp lệ.");
        try(Connection c=connect(cfg)){
            try{
                c.command("MAIL FROM:<"+cfg.sender()+">",250);c.command("RCPT TO:<"+recipient+">",250,251);c.command("DATA",354);
                String encoded=Base64.getMimeEncoder(76,new byte[]{13,10}).encodeToString(body.getBytes(StandardCharsets.UTF_8));
                c.out.write("From: Game Portal <"+cfg.sender()+">\r\nTo: <"+recipient+">\r\nSubject: =?UTF-8?B?"+base64(subject)+"?=\r\nDate: "+DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now())+"\r\nMessage-ID: <"+UUID.randomUUID()+"@"+cfg.sender().substring(cfg.sender().indexOf('@')+1)+">\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n"+encoded+"\r\n.\r\n");c.out.flush();expect(c.in,250);
            }catch(IOException e){if(e instanceof Failure f)throw f;throw new Failure("SMTP_SEND","Kết nối bị gián đoạn khi gửi email. Vui lòng thử lại.");}
            try{c.command("QUIT",221);}catch(IOException ignored){}
        }
    }
    private static String base64(String value){return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));}
    private static void expect(BufferedReader reader,int...codes)throws IOException{
        int status=-1;
        for(int count=0;count<100;count++){
            String line=reader.readLine();if(line==null||!line.matches("[0-9]{3}[ -].*"))throw new Failure("SMTP_PROTOCOL","Phản hồi SMTP không hợp lệ.");
            int next=Integer.parseInt(line.substring(0,3));if(status!=-1&&next!=status)throw new Failure("SMTP_PROTOCOL","Phản hồi SMTP không hợp lệ.");status=next;if(line.charAt(3)=='-')continue;
            for(int code:codes)if(status==code)return;
            if(status==534||status==535)throw new Failure("SMTP_AUTH","Máy chủ email từ chối xác thực. Hãy kiểm tra mật khẩu ứng dụng Gmail.");
            throw new Failure(status==550||status==553?"SMTP_RECIPIENT":"SMTP_REJECTED","Máy chủ email từ chối yêu cầu (mã "+status+").");
        }throw new Failure("SMTP_PROTOCOL","Phản hồi SMTP quá dài.");
    }
}
