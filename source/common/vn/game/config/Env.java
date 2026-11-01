package vn.game.config;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class Env {
    private static volatile Map<String, String> values = Map.of();

    static {
        load();
    }

    public static synchronized void load() {
        File file = new File(".env");
        if (!file.exists()) {
            file = new File("../.env");
        }
        if (!file.exists()) {
            values = Map.of();
            return;
        }

        Map<String, String> loaded = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if(line.startsWith("\uFEFF"))line=line.substring(1).trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int idx = line.indexOf('=');
                if (idx > 0) {
                    String key = line.substring(0, idx).trim();
                    String val = line.substring(idx + 1).trim();
                    if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
                        val = val.substring(1, val.length() - 1);
                    }
                    loaded.put(key, val);
                }
            }
            // Thay toàn bộ snapshot để không giữ mật khẩu cũ hoặc key đã bị xóa.
            values = Map.copyOf(loaded);
        } catch (Exception e) {
            System.err.println("Lỗi đọc file .env: " + e.getMessage());
        }
    }

    public static String get(String key) {
        String val = values.get(key);
        if (val == null || val.isEmpty()) {
            val = System.getenv(key);
        }
        return val != null ? val : "";
    }

    public static String get(String key, String defaultValue) {
        String val = get(key);
        return val.isEmpty() ? defaultValue : val;
    }
}
