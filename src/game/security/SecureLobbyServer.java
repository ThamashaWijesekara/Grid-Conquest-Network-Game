package game.security;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A small TLS lobby that demonstrates registration, login and token issuing. */
public final class SecureLobbyServer {
    private final int port;
    private final Map<String, PasswordHasher.PasswordRecord> users = new ConcurrentHashMap<>();
    private final TokenManager tokens = new TokenManager(
            SecurityConfig.sharedSecret(), SecurityConfig.TOKEN_LIFETIME);
    private volatile boolean running = true;

    public SecureLobbyServer(int port) {
        this.port = port;
        seedDemoUsers();
    }

    public void start() throws IOException {
        SSLServerSocketFactory factory = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
        try (SSLServerSocket server = (SSLServerSocket) factory.createServerSocket(port)) {
            System.out.println("Secure lobby listening on TLS port " + port);
            while (running) {
                SSLSocket client = (SSLSocket) server.accept();
                Thread handler = new Thread(() -> handle(client), "Secure-Lobby-Client");
                handler.setDaemon(true);
                handler.start();
            }
        }
    }

    private void handle(SSLSocket client) {
        try (client;
             BufferedReader input = new BufferedReader(new InputStreamReader(
                     client.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter output = new PrintWriter(client.getOutputStream(), true, StandardCharsets.UTF_8)) {
            String request = input.readLine();
            output.println(process(request));
        } catch (IOException e) {
            System.err.println("Secure lobby client error: " + e.getMessage());
        }
    }

    String process(String request) {
        if (request == null) return "ERROR|Empty request";
        String[] parts = request.split("\\|", 3);
        if (parts.length != 3) return "ERROR|Use ACTION|PLAYER|PASSWORD";

        String action = parts[0];
        String playerId = cleanPlayerId(parts[1]);
        char[] password = parts[2].toCharArray();
        try {
            if (playerId == null || password.length < 6) return "ERROR|Invalid credentials";
            return switch (action) {
                case "REGISTER" -> register(playerId, password);
                case "LOGIN" -> login(playerId, password);
                default -> "ERROR|Unknown action";
            };
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    private String register(String playerId, char[] password) {
        PasswordHasher.PasswordRecord record = PasswordHasher.hash(password);
        if (users.putIfAbsent(playerId, record) != null) return "AUTH_FAILED|Player already exists";
        return "REGISTERED|" + playerId;
    }

    private String login(String playerId, char[] password) {
        PasswordHasher.PasswordRecord record = users.get(playerId);
        if (record == null || !PasswordHasher.verify(password, record)) {
            return "AUTH_FAILED|Invalid credentials";
        }
        return "AUTH_OK|" + tokens.issue(playerId);
    }

    private static String cleanPlayerId(String value) {
        String cleaned = value == null ? "" : value.trim().toUpperCase();
        return cleaned.matches("[A-Z0-9_]{2,16}") ? cleaned : null;
    }

    private void seedDemoUsers() {
        for (int i = 1; i <= 5; i++) {
            users.put("P" + i, PasswordHasher.hash("player123".toCharArray()));
        }
    }

    public static void main(String[] args) throws IOException {
        new SecureLobbyServer(SecurityConfig.LOBBY_PORT).start();
    }
}
