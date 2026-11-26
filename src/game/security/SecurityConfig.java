package game.security;

import java.time.Duration;

public final class SecurityConfig {
    public static final int LOBBY_PORT = 9877;
    public static final Duration TOKEN_LIFETIME = Duration.ofMinutes(30);
    public static final long PACKET_MAX_AGE_MILLIS = 10_000;

    private SecurityConfig() {
    }

    public static String sharedSecret() {
        return System.getenv().getOrDefault(
                "GRID_SECURITY_SECRET",
                "grid-conquest-class-demo-secret"
        );
    }
}
