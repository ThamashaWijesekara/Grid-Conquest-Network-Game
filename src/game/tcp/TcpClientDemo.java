
package game.tcp;

import java.io.IOException;
import java.util.Scanner;

public class TcpClientDemo {

    public static void main(String[] args)
            throws IOException {

        String playerId =
                args.length > 0 ? args[0] : "P1";

        String token =
                args.length > 1 ? args[1] : "ONLINE";

        try (
            TcpGameClient client = new TcpGameClient(
                "127.0.0.1", 9900, playerId
            );

            Scanner scanner = new Scanner(System.in)
        ) {

            // Receive messages from the server
            Thread receiver = new Thread(() -> {
                try {
                    client.listen(message ->
                        System.out.println(
                            "SERVER: " + message
                        )
                    );
                } catch (IOException e) {
                    System.out.println(
                        "Connection closed: "
                        + e.getMessage()
                    );
                }
            });

            receiver.setDaemon(true);
            receiver.start();

            // Join the game
            client.join(token);

            System.out.println("Player: " + playerId);
            System.out.println("Available commands:");
            System.out.println("roll");
            System.out.println("claim 2 3");
            System.out.println("exit");

            // Read player commands
            while (scanner.hasNextLine()) {

                String command = scanner.nextLine().trim();
                String[] parts = command.split("\\s+");

                if (command.equalsIgnoreCase("roll")) {

                    client.rollDice();

                } else if (
                    parts[0].equalsIgnoreCase("claim")
                    && parts.length == 3
                ) {

                    try {
                        int row = Integer.parseInt(parts[1]);
                        int col = Integer.parseInt(parts[2]);

                        client.claimCell(row, col);

                    } catch (NumberFormatException e) {
                        System.out.println(
                            "Enter valid row and column numbers."
                        );
                    }

                } else if (
                    command.equalsIgnoreCase("exit")
                ) {

                    break;

                } else {

                    System.out.println("Invalid command.");
                }
            }
        }
    }
}
