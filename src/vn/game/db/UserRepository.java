package vn.game.db;

import vn.game.model.User;
import vn.game.util.JsonUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class UserRepository {
    private static final String DATA_FILE = "data/users.json";
    private final Map<String, User> usersByUsername = new ConcurrentHashMap<>();
    private final AtomicInteger idCounter = new AtomicInteger(1);

    public UserRepository() {
        loadFromFile();
    }

    public synchronized User register(String username, String email, String password) throws Exception {
        if (usersByUsername.containsKey(username.toLowerCase())) {
            throw new Exception("Tên tài khoản này đã có người sử dụng!");
        }
        for (User u : usersByUsername.values()) {
            if (u.getEmail() != null && u.getEmail().equalsIgnoreCase(email)) {
                throw new Exception("Email này đã được sử dụng!");
            }
        }

        int id = idCounter.getAndIncrement();
        User user = new User(id, username, email, password);
        usersByUsername.put(username.toLowerCase(), user);
        saveToFile();
        return user;
    }

    public User findByUsername(String username) {
        if (username == null) return null;
        return usersByUsername.get(username.toLowerCase());
    }

    public User findByEmail(String email) {
        if (email == null) return null;
        for (User u : usersByUsername.values()) {
            if (u.getEmail() != null && u.getEmail().equalsIgnoreCase(email)) {
                return u;
            }
        }
        return null;
    }

    public User findByResetToken(String token) {
        if (token == null) return null;
        for (User u : usersByUsername.values()) {
            if (token.equals(u.getResetToken())) {
                return u;
            }
        }
        return null;
    }

    public boolean isAdmin(String username) {
        if (username == null) return false;
        if ("admin".equalsIgnoreCase(username) || "DuyNguyen".equalsIgnoreCase(username)) return true;
        User u = findByUsername(username);
        if (u != null && u.getEmail() != null && "mainung28@gmail.com".equalsIgnoreCase(u.getEmail())) return true;
        return u != null && u.isAdmin();
    }

    public boolean isAdmin(User user) {
        if (user == null) return false;
        if ("admin".equalsIgnoreCase(user.getUsername()) || "DuyNguyen".equalsIgnoreCase(user.getUsername())) return true;
        if (user.getEmail() != null && "mainung28@gmail.com".equalsIgnoreCase(user.getEmail())) return true;
        return user.isAdmin();
    }

    public User findByGoogleId(String googleId) {
        if (googleId == null) return null;
        for (User u : usersByUsername.values()) {
            if (googleId.equals(u.getGoogleId())) {
                return u;
            }
        }
        return null;
    }

    public synchronized User registerOrGetGoogleUser(String googleId, String email, String name) {
        // 1. Kiểm tra theo googleId
        User existing = findByGoogleId(googleId);
        if (existing != null) return existing;

        // 2. Kiểm tra theo email nếu đã từng đăng ký bằng email
        if (email != null) {
            existing = findByEmail(email);
            if (existing != null) {
                existing.setGoogleId(googleId);
                saveToFile();
                return existing;
            }
        }

        // 3. Tạo tài khoản mới từ thông tin Google
        String baseUsername = (name != null && !name.trim().isEmpty())
                ? name.replaceAll("[^a-zA-Z0-9_]", "")
                : (email != null ? email.split("@")[0] : "google_user");
        if (baseUsername.isEmpty()) baseUsername = "google_user";

        String finalUsername = baseUsername;
        int count = 1;
        while (usersByUsername.containsKey(finalUsername.toLowerCase())) {
            finalUsername = baseUsername + "_" + count++;
        }

        int id = idCounter.getAndIncrement();
        User newUser = new User();
        newUser.setId(id);
        newUser.setUsername(finalUsername);
        newUser.setEmail(email);
        newUser.setGoogleId(googleId);
        newUser.setSalt(User.generateSalt());
        newUser.setPasswordHash(User.hashPassword(UUID.randomUUID().toString(), newUser.getSalt()));

        usersByUsername.put(finalUsername.toLowerCase(), newUser);
        saveToFile();
        return newUser;
    }

    public synchronized void update(User user) {
        usersByUsername.put(user.getUsername().toLowerCase(), user);
        saveToFile();
    }

    private synchronized void saveToFile() {
        try {
            File dir = new File("data");
            if (!dir.exists()) dir.mkdirs();

            List<Map<String, Object>> list = new ArrayList<>();
            for (User u : usersByUsername.values()) {
                list.add(u.toMap());
            }

            String json = JsonUtil.toJson(list);
            Files.writeString(new File(DATA_FILE).toPath(), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Lỗi lưu file người dùng: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private synchronized void loadFromFile() {
        File file = new File(DATA_FILE);
        if (!file.exists()) return;

        try {
            String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            Object obj = JsonUtil.parse(json);
            if (obj instanceof List) {
                List<?> list = (List<?>) obj;
                int maxId = 0;
                for (Object item : list) {
                    if (item instanceof Map) {
                        User user = User.fromMap((Map<String, Object>) item);
                        usersByUsername.put(user.getUsername().toLowerCase(), user);
                        if (user.getId() > maxId) maxId = user.getId();
                    }
                }
                idCounter.set(maxId + 1);
            }
        } catch (Exception e) {
            System.err.println("Lỗi đọc dữ liệu người dùng từ " + DATA_FILE + ": " + e.getMessage());
        }
    }
}
