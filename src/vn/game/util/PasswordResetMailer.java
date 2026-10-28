package vn.game.util;

import vn.game.config.Env;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.UUID;

/** SMTP qua TLS trực tiếp (Gmail: cổng 465), không cần thư viện ngoài. */
public final class PasswordResetMailer {
    private static final int TIMEOUT_MS = 15_000;

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

    private void sendMessage(String recipient, String subject, String body) throws IOException {
        String sender = Env.get("GMAIL_USER");
        if (!isConfigured() || !isEmail(recipient)) {
            throw new IOException("Chưa cấu hình tài khoản gửi email hợp lệ.");
        }
        String host = Env.get("SMTP_HOST", "smtp.gmail.com");
        int port;
        try {
            port = Integer.parseInt(Env.get("SMTP_PORT", "465"));
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            throw new IOException("SMTP_PORT không hợp lệ.");
        }

        try (SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket()) {
            socket.setSoTimeout(TIMEOUT_MS);
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            socket.startHandshake();
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
            expect(reader, 220);
            command(writer, reader, "EHLO [127.0.0.1]", 250);
            command(writer, reader, "AUTH LOGIN", 334);
            command(writer, reader, base64(sender), 334);
            command(writer, reader, base64(Env.get("GMAIL_APP_PASSWORD").replace(" ", "")), 235);
            command(writer, reader, "MAIL FROM:<" + sender + ">", 250);
            command(writer, reader, "RCPT TO:<" + recipient + ">", 250, 251);
            command(writer, reader, "DATA", 354);

            String encodedBody = Base64.getMimeEncoder(76, new byte[]{13, 10})
                    .encodeToString(body.getBytes(StandardCharsets.UTF_8));
            writer.write("From: Game Portal <" + sender + ">\r\n"
                    + "To: <" + recipient + ">\r\n"
                    + "Subject: =?UTF-8?B?" + base64(subject) + "?=\r\n"
                    + "Date: " + DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now()) + "\r\n"
                    + "Message-ID: <" + UUID.randomUUID() + "@" + sender.substring(sender.indexOf('@') + 1) + ">\r\n"
                    + "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n"
                    + "Content-Transfer-Encoding: base64\r\n\r\n" + encodedBody + "\r\n.\r\n");
            writer.flush();
            expect(reader, 250);
            // Sau 250, email đã được SMTP tiếp nhận; lỗi QUIT không hủy mã đã gửi.
            try { command(writer, reader, "QUIT", 221); } catch (IOException ignored) {}
        }
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void command(BufferedWriter writer, BufferedReader reader, String value, int... codes) throws IOException {
        writer.write(value + "\r\n");
        writer.flush();
        expect(reader, codes);
    }

    private static void expect(BufferedReader reader, int... codes) throws IOException {
        String line;
        int status = -1;
        for (int count = 0; count < 100; count++) {
            line = reader.readLine();
            if (line == null || !line.matches("[0-9]{3}[ -].*")) throw new IOException("Phản hồi SMTP không hợp lệ.");
            int current = Integer.parseInt(line.substring(0, 3));
            if (status != -1 && current != status) throw new IOException("Phản hồi SMTP không hợp lệ.");
            status = current;
            if (line.charAt(3) == '-') continue;
            for (int code : codes) if (status == code) return;
            // Không ghi phản hồi SMTP thô vì có thể chứa thông tin tài khoản.
            throw new IOException("SMTP trả về mã " + status + ".");
        }
        throw new IOException("Phản hồi SMTP quá dài.");
    }
}
