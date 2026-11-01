package vn.game.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class User {
    private int id;
    private String username;
    private String email;
    private String passwordHash;
    private String salt;
    private String googleId;
    private String resetToken;
    private long resetExpires;
    private String role = "user";

    public User() {}

    public User(int id, String username, String email, String password) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.salt = generateSalt();
        this.passwordHash = hashPassword(password, this.salt);
        this.role = "user";
    }

    public static String generateSalt() {
        SecureRandom random = new SecureRandom();
        byte[] saltBytes = new byte[16];
        random.nextBytes(saltBytes);
        return Base64.getEncoder().encodeToString(saltBytes);
    }

    public static String hashPassword(String password, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt.getBytes(StandardCharsets.UTF_8));
            byte[] hashedPassword = md.digest(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashedPassword);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    public boolean verifyPassword(String inputPassword) {
        if (this.salt == null || this.passwordHash == null) return false;
        if(inputPassword == null) return false;
        if(passwordHash.startsWith("pbkdf2$")) {
            return MessageDigest.isEqual(secureHashPassword(inputPassword,salt).getBytes(StandardCharsets.UTF_8),passwordHash.getBytes(StandardCharsets.UTF_8));
        }
        String computed = hashPassword(inputPassword, this.salt);
        return computed.equals(this.passwordHash);
    }

    public static String secureHashPassword(String password,String salt) {
        javax.crypto.spec.PBEKeySpec spec=new javax.crypto.spec.PBEKeySpec(password.toCharArray(),salt.getBytes(StandardCharsets.UTF_8),600000,256);
        try {return "pbkdf2$600000$"+Base64.getEncoder().encodeToString(javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());}
        catch(java.security.GeneralSecurityException e){throw new IllegalStateException(e);}
        finally{spec.clearPassword();}
    }

    public void updatePassword(String newPassword) {
        this.salt = generateSalt();
        this.passwordHash = hashPassword(newPassword, this.salt);
        this.resetToken = null;
        this.resetExpires = 0;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("id", id);
        map.put("username", username);
        map.put("email", email);
        map.put("passwordHash", passwordHash);
        map.put("salt", salt);
        map.put("googleId", googleId);
        map.put("resetToken", resetToken);
        map.put("resetExpires", resetExpires);
        map.put("role", role);
        return map;
    }

    public static User fromMap(Map<String, Object> map) {
        User u = new User();
        if (map.get("id") != null) u.setId(((Number) map.get("id")).intValue());
        if (map.get("username") != null) u.setUsername((String) map.get("username"));
        if (map.get("email") != null) u.setEmail((String) map.get("email"));
        if (map.get("passwordHash") != null) u.setPasswordHash((String) map.get("passwordHash"));
        if (map.get("salt") != null) u.setSalt((String) map.get("salt"));
        if (map.get("googleId") != null) u.setGoogleId((String) map.get("googleId"));
        if (map.get("resetToken") != null) u.setResetToken((String) map.get("resetToken"));
        if (map.get("resetExpires") != null) u.setResetExpires(((Number) map.get("resetExpires")).longValue());
        
        if (map.get("role") != null) {
            u.setRole((String) map.get("role"));
        } else {
            u.setRole("user");
        }
        return u;
    }

    public boolean isAdmin() {
        return "admin".equalsIgnoreCase(role);
    }

    // Getters and Setters
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getSalt() { return salt; }
    public void setSalt(String salt) { this.salt = salt; }
    public String getGoogleId() { return googleId; }
    public void setGoogleId(String googleId) { this.googleId = googleId; }
    public String getResetToken() { return resetToken; }
    public void setResetToken(String resetToken) { this.resetToken = resetToken; }
    public long getResetExpires() { return resetExpires; }
    public void setResetExpires(long resetExpires) { this.resetExpires = resetExpires; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
}
