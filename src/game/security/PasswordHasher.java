package game.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Demonstrates salted password hashing with PBKDF2. */
public final class PasswordHasher {
    private static final int ITERATIONS = 65_536;
    private static final int KEY_LENGTH = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    public static PasswordRecord hash(char[] password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return new PasswordRecord(
                Base64.getEncoder().encodeToString(salt),
                Base64.getEncoder().encodeToString(derive(password, salt))
        );
    }

    public static boolean verify(char[] password, PasswordRecord record) {
        byte[] salt = Base64.getDecoder().decode(record.salt());
        byte[] expected = Base64.getDecoder().decode(record.hash());
        byte[] actual = derive(password, salt);
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] derive(char[] password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Password hashing is unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }

    public record PasswordRecord(String salt, String hash) {
    }
}
