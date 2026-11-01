package vn.game.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.Arrays;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

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
    private final Object heartbeatLock = new Object();
    private long lastHeartbeatNanos = System.nanoTime();
    private long pingSentNanos;
    private byte[] pendingPing;

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
        sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    /** Tất cả frame dùng chung khóa ghi để Ping/Pong không chen giữa frame JSON. */
    private void sendFrame(int opcode, byte[] payload) {
        if (closed || socket.isClosed()) return;
        synchronized (writeLock) {
            try {
                out.write(0x80 | opcode);

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
                        out.write((int) (((long) len >> (8 * i)) & 0xFF));
                    }
                }

                out.write(payload);
                out.flush();
            } catch (IOException e) {
                close();
            }
        }
    }

    public byte[] prepareHeartbeat(long nowNanos, long intervalMs) {
        synchronized (heartbeatLock) {
            if (isClosed() || pendingPing != null || nowNanos - lastHeartbeatNanos < TimeUnit.MILLISECONDS.toNanos(intervalMs)) return null;
            pendingPing = ByteBuffer.allocate(8).putLong(nowNanos).array();
            pingSentNanos = nowNanos;
            return pendingPing.clone();
        }
    }

    public boolean heartbeatExpired(long nowNanos, long timeoutMs) {
        synchronized (heartbeatLock) {
            return pendingPing != null && nowNanos - pingSentNanos >= TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        }
    }

    public void receivePong(byte[] payload) {
        synchronized (heartbeatLock) {
            if (pendingPing != null && Arrays.equals(pendingPing, payload)) {
                pendingPing = null;
                lastHeartbeatNanos = System.nanoTime();
            }
        }
    }

    public void sendPing(byte[] payload) { sendFrame(0x9, payload); }
    public void sendPong(byte[] payload) { if (payload.length <= 125) sendFrame(0xA, payload); }

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
