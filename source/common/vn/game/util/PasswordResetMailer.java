package vn.game.util;

import vn.game.config.Env;

import java.io.IOException;

/** Nội dung thư xác nhận tài khoản và mã khôi phục, gửi bằng SMTP TLS. */
public final class PasswordResetMailer {

    public boolean isConfigured() {
        return isEmail(Env.get("GMAIL_USER")) && !Env.get("GMAIL_APP_PASSWORD").isBlank();
    }

    public static boolean isEmail(String email) {
        return email != null && email.length() <= 254 && email.indexOf('@') <= 64
                && email.matches("[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*"
                + "@(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}");
    }

    public void sendCode(String recipient, String code) throws IOException {
        String body = "Bạn vừa yêu cầu đặt lại mật khẩu Game Portal.\r\n\r\n"
                + "Mã xác nhận của bạn: " + code + "\r\n\r\n"
                + "Mã có hiệu lực trong 10 phút và chỉ dùng được một lần.\r\n"
                + "Không chia sẻ mã này với người khác.\r\n"
                + "Nếu bạn không thực hiện yêu cầu này, hãy bỏ qua email này.\r\n";
        sendMessage(recipient, "Mã đặt lại mật khẩu Game Portal", body);
    }

    public void sendRegistrationLink(String recipient, String link) throws IOException {
        String body = "Bạn vừa yêu cầu đăng ký tài khoản Game Portal.\r\n\r\n"
                + "Bấm vào liên kết sau để xác nhận email và kích hoạt tài khoản:\r\n"
                + link + "\r\n\r\n"
                + "Liên kết có hiệu lực trong 30 phút và chỉ dùng được một lần.\r\n"
                + "Chỉ xác nhận nếu chính bạn vừa đăng ký tài khoản. Nếu không, hãy bỏ qua email này.\r\n";
        sendMessage(recipient, "Xác nhận email đăng ký Game Portal", body);
    }

    private void sendMessage(String recipient,String subject,String body) throws IOException {
        SmtpTransport.send(recipient,subject,body);
    }
    public void checkConnection() throws IOException { SmtpTransport.checkConnection(); }
}
