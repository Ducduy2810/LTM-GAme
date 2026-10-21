package vn.game;

import vn.game.db.UserRepository;
import vn.game.games.ArenaGame;
import vn.game.games.SurvivalGame;
import vn.game.games.WheelGame;
import vn.game.server.HttpAndWebSocketServer;
import vn.game.server.SessionManager;

import java.io.File;
import java.net.InetAddress;
import java.util.Enumeration;
import java.net.NetworkInterface;

public class Main {

    public static void main(String[] args) {
        int port = 3000;
        File publicDir = new File("public");
        if (!publicDir.exists()) {
            publicDir = new File("../public");
        }

        System.out.println("===============================================================");
        System.out.println("   🎮 HE THONG GAME PORTAL - JAVA PURE NETWORK PROGRAMMING   ");
        System.out.println("   - Game 1: Dau Truong 100 (100 Clients dong thoi, Timer 10s) ");
        System.out.println("   - Game 2: Chiec Non Ky Dieu (4 Nguoi choi theo luot, 10s)   ");
        System.out.println("   - Game 3: Dau Truong Sinh Tu (HP giam dan, Thuong moc loai) ");
        System.out.println("   - Xac thuc & Bao mat: Session Cookie + SHA-256 Hash + Salt  ");
        System.out.println("   - Giao thuc mang: TCP Socket + WebSocket RFC 6455 + HTTP    ");
        System.out.println("===============================================================");

        UserRepository userRepository = new UserRepository();
        SessionManager sessionManager = new SessionManager();
        ArenaGame arenaGame = new ArenaGame(userRepository);
        WheelGame wheelGame = new WheelGame(userRepository);
        SurvivalGame survivalGame = new SurvivalGame(userRepository);

        HttpAndWebSocketServer server = new HttpAndWebSocketServer(
                port, publicDir, userRepository, sessionManager, arenaGame, wheelGame, survivalGame
        );

        try {
            System.out.println("🌐 Truy cap tren may nay : http://localhost:" + port);
            System.out.println("📱 Cho ban be vao mang LAN, thu cac IP sau:");
            
            boolean foundLanIp = false;
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    String ip = addr.getHostAddress();
                    if (!addr.isLoopbackAddress() && ip.contains(".") && !ip.startsWith("169.254")) {
                        System.out.println("   👉 http://" + ip + ":" + port);
                        foundLanIp = true;
                    }
                }
            }
            
            if (!foundLanIp) {
                String localIp = InetAddress.getLocalHost().getHostAddress();
                System.out.println("   👉 http://" + localIp + ":" + port);
            }
            
            System.out.println("===============================================================");
            System.out.println("⚠️  NEU BAN BE KHONG VAO DUOC:");
            System.out.println("   1. Chay file 'open_firewall.bat' (Run as Administrator)");
            System.out.println("   2. Hoac mo Windows Defender Firewall -> Allow port " + port);
            System.out.println("   3. Kiem tra ca hai may cung mang WiFi/LAN");
            System.out.println("===============================================================");

            server.start();
        } catch (Exception e) {
            System.err.println("❌ Lỗi khởi động máy chủ: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
