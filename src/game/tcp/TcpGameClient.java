
package game.tcp;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public class TcpGameClient implements AutoCloseable {

    private final Socket socket;
    private final BufferedReader input;
    private final BufferedWriter output;
    private final String playerId;

    // Connect to the TCP game server
    public TcpGameClient(String host, int port, String playerId)
            throws IOException {

        this.playerId = playerId;

        socket = new Socket();

        socket.connect(
            new InetSocketAddress(host, port), 3000
        );

        socket.setTcpNoDelay(true);

        input = new BufferedReader(
            new InputStreamReader(
                socket.getInputStream(),
                StandardCharsets.UTF_8
            )
        );

        output = new BufferedWriter(
            new OutputStreamWriter(
                socket.getOutputStream(),
                StandardCharsets.UTF_8
            )
        );
    }

    // Join the game
    public void join(String token) throws IOException {
        send("JOIN|" + playerId + "|" + token);
    }

    // Request a dice roll
    public void rollDice() throws IOException {
        send("ROLL|" + playerId);
    }

    // Request to capture a cell
    public void claimCell(int row, int col)
            throws IOException {

        send("CLAIM|" + playerId
                + "|" + row + "|" + col);
    }

    // Request a shared board reset through TCP
public void resetBoard() throws IOException {
    send("RESET|" + playerId);
}

    // Send a message using TCP
    private synchronized void send(String message)
            throws IOException {

        output.write(message);
        output.write("\n");
        output.flush();
    }

    // Continuously receive server messages
    public void listen(Consumer<String> listener)
            throws IOException {

        String message;

        while ((message = input.readLine()) != null) {
            listener.accept(message);
        }
    }

    // Close the TCP connection
    @Override
    public void close() throws IOException {
        socket.close();
    }
}
