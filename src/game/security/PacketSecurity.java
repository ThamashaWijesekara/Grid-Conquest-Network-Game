package game.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Adds authentication, integrity and simple replay protection to UDP messages. */
public final class PacketSecurity {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final TokenManager tokens;
    private final byte[] integrityKey;
    private final Map<String, Long> receivedSignatures = new ConcurrentHashMap<>();

    public PacketSecurity(TokenManager tokens) {
        this.tokens = tokens;
        this.integrityKey = tokens.signingKey();
    }

    public String protect(String payload, String token) {
        long timestamp = Instant.now().toEpochMilli();
        String nonce = Long.toUnsignedString(RANDOM.nextLong(), 36);
        String content = payload + "|" + timestamp + "|" + nonce + "|" + token;
        return content + "|" + hmac(content);
    }

    public VerifiedPacket verify(String packet) {
        String[] parts = packet.split("\\|");
        if (parts.length < 7) return null;

        int signatureIndex = parts.length - 1;
        int tokenIndex = parts.length - 2;
        int nonceIndex = parts.length - 3;
        int timestampIndex = parts.length - 4;
        String signature = parts[signatureIndex];
        String content = packet.substring(0, packet.length() - signature.length() - 1);

        if (!MessageDigest.isEqual(
                hmac(content).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) return null;

        long timestamp;
        try {
            timestamp = Long.parseLong(parts[timestampIndex]);
        } catch (NumberFormatException e) {
            return null;
        }
        long now = Instant.now().toEpochMilli();
        if (Math.abs(now - timestamp) > SecurityConfig.PACKET_MAX_AGE_MILLIS) return null;

        TokenManager.TokenDetails details = tokens.verify(parts[tokenIndex]);
        if (details == null || !details.playerId().equals(parts[1])) return null;
        if (receivedSignatures.putIfAbsent(signature, now) != null) return null;
        receivedSignatures.entrySet().removeIf(entry -> now - entry.getValue() > 60_000);

        String payload = String.join("|", java.util.Arrays.copyOf(parts, timestampIndex));
        return new VerifiedPacket(payload, details.playerId(), parts[nonceIndex]);
    }

    private String hmac(String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(integrityKey, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Packet signing is unavailable", e);
        }
    }

    public record VerifiedPacket(String payload, String playerId, String nonce) {
    }
}
