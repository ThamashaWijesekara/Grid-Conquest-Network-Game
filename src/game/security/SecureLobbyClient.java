package game.security;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

public final class SecureLobbyClient {
    private final String host;
    private final int port;

    public SecureLobbyClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public String login(String playerId, char[] password) throws IOException {
        return request("LOGIN", playerId, password);
    }

    public String register(String playerId, char[] password) throws IOException {
        return request("REGISTER", playerId, password);
    }

    private String request(String action, String playerId, char[] password) throws IOException {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        try (SSLSocket socket = (SSLSocket) factory.createSocket(host, port);
             BufferedReader input = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter output = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {
            output.println(action + "|" + playerId + "|" + new String(password));
            return input.readLine();
        }
    }
}
