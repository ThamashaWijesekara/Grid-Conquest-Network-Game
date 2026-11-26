package game.security;

import java.time.Duration;

public final class SecuritySelfTest {
    private SecuritySelfTest() {
    }

    public static void main(String[] args) throws Exception {
        testPasswordHashing();
        testTokens();
        testPackets();
        testLobbyAuthentication();
        if (args.length > 0 && "tls".equalsIgnoreCase(args[0])) testTlsConnection();
        System.out.println("All security checks passed");
    }

    private static void testPasswordHashing() {
        PasswordHasher.PasswordRecord record = PasswordHasher.hash("player123".toCharArray());
        require(PasswordHasher.verify("player123".toCharArray(), record), "correct password rejected");
        require(!PasswordHasher.verify("wrong-password".toCharArray(), record), "wrong password accepted");
    }

    private static void testTokens() {
        TokenManager tokens = new TokenManager("self-test-secret-key", Duration.ofMinutes(1));
        String token = tokens.issue("P1");
        require(tokens.verify(token) != null, "valid token rejected");
        require(tokens.verify(token + "x") == null, "modified token accepted");
    }

    private static void testPackets() {
        TokenManager tokens = new TokenManager("self-test-secret-key", Duration.ofMinutes(1));
        PacketSecurity packets = new PacketSecurity(tokens);
        String secured = packets.protect("CLAIM|P1|2|3", tokens.issue("P1"));
        require(packets.verify(secured) != null, "valid packet rejected");
        require(packets.verify(secured) == null, "replayed packet accepted");
        require(packets.verify(secured.replace("|2|3|", "|9|3|")) == null, "modified packet accepted");
    }

    private static void testLobbyAuthentication() {
        SecureLobbyServer lobby = new SecureLobbyServer(SecurityConfig.LOBBY_PORT);
        require(lobby.process("LOGIN|P1|player123").startsWith("AUTH_OK|"), "login rejected");
        require(lobby.process("LOGIN|P1|incorrect").startsWith("AUTH_FAILED|"), "bad login accepted");
    }

    private static void testTlsConnection() throws Exception {
        SecureLobbyClient client = new SecureLobbyClient("localhost", SecurityConfig.LOBBY_PORT);
        String response = client.login("P1", "player123".toCharArray());
        require(response.startsWith("AUTH_OK|"), "TLS login failed");
        System.out.println("TLS lobby connection passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
