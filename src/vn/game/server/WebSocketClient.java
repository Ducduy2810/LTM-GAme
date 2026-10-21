package vn.game.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Đại diện cho một kết nối WebSocket client theo chuẩn RFC 6455.
 */
public class WebSocketClient {
    private final String id;
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Object writeLock = new Object();
    private String username;
    private volatile boolean closed = false;

    public WebSocketClient(Socket socket, InputStream in, OutputStream out, String username) {
        this.id = UUID.randomUUID().toString();
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.username = username;
    }

    public String getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public boolean isClosed() {
        return closed || socket.isClosed();
    }

    /**
     * Gửi một khung tin văn bản (Text Frame) theo chuẩn RFC 6455 đến Client.
     * Đồng bộ hóa (Thread-Safe) để tránh xung đột dữ liệu khi nhiều luồng cùng gửi tin nhắn.
     */
    public void send(String text) {
        if (closed || socket.isClosed()) return;
        synchronized (writeLock) {
            try {
                byte[] payload = text.getBytes(StandardCharsets.UTF_8);
                out.write(0x81); // FIN (0x80) | Text Opcode (0x01)

                int len = payload.length;
                if (len < 126) {
                    out.write(len);
                } else if (len <= 65535) {
                    out.write(126);
                    out.write((len >> 8) & 0xFF);
                    out.write(len & 0xFF);
                } else {
                    out.write(127);
                    for (int i = 7; i >= 0; i--) {
                        out.write((int) ((len >> (8 * i)) & 0xFF));
                    }
                }

                out.write(payload);
                out.flush();
            } catch (IOException e) {
                close();
            }
        }
    }

    public void close() {
        if (closed) return;
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {}
    }

    public InputStream getInputStream() {
        return in;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WebSocketClient that = (WebSocketClient) o;
        return java.util.Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(id);
    }
}
