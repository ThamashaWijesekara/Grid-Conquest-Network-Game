package game.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/** Issues short-lived, signed authentication tokens without external libraries. */
public final class TokenManager {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] signingKey;
    private final Duration lifetime;

    public TokenManager(String secret, Duration lifetime) {
        if (secret == null || secret.length() < 16) {
            throw new IllegalArgumentException("Token secret must contain at least 16 characters");
        }
        this.signingKey = secret.getBytes(StandardCharsets.UTF_8);
        this.lifetime = lifetime;
    }

    public String issue(String playerId) {
        byte[] nonce = new byte[18];
        RANDOM.nextBytes(nonce);
        String body = playerId + ":" + Instant.now().plus(lifetime).toEpochMilli() + ":"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        String encodedBody = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(body.getBytes(StandardCharsets.UTF_8));
        return encodedBody + "." + sign(encodedBody);
    }

    public TokenDetails verify(String token) {
        if (token == null) return null;
        String[] pieces = token.split("\\.", 2);
        if (pieces.length != 2 || !MessageDigest.isEqual(
                sign(pieces[0]).getBytes(StandardCharsets.UTF_8),
                pieces[1].getBytes(StandardCharsets.UTF_8))) {
            return null;
        }

        try {
            String body = new String(Base64.getUrlDecoder().decode(pieces[0]), StandardCharsets.UTF_8);
            String[] fields = body.split(":", 3);
            long expiresAt = Long.parseLong(fields[1]);
            if (fields.length != 3 || Instant.now().toEpochMilli() > expiresAt) return null;
            return new TokenDetails(fields[0], expiresAt);
        } catch (RuntimeException e) {
            return null;
        }
    }

    byte[] signingKey() {
        return signingKey.clone();
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Token signing is unavailable", e);
        }
    }

    public record TokenDetails(String playerId, long expiresAt) {
    }
}
