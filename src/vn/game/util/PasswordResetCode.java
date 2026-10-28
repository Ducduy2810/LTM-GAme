package vn.game.util;

import vn.game.model.User;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/** Mã chỉ được giữ dưới dạng băm có salt trong SQLite. */
public final class PasswordResetCode {
    public static final long VALID_FOR_MS = 10 * 60 * 1000L;
    public static final long RESEND_AFTER_MS = 60 * 1000L;
    public static final int MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordResetCode() {}

    public static String generate() {
        return String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
    }

    public static String hash(String email, String code) {
        String salt = User.generateSalt();
        return "otp:" + salt + ":" + User.hashPassword(email + ":" + code, salt);
    }

    public static boolean matches(String email, String code, String stored) {
        if (stored == null || !stored.startsWith("otp:")) return false;
        String[] parts = stored.split(":", 3);
        if (parts.length != 3) return false;
        String actual = User.hashPassword(email + ":" + code, parts[1]);
        return MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8));
    }
}
