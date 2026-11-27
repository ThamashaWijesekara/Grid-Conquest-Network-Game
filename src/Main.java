import game.security.SecureLobbyServer;
import game.server.GameServer;
import game.server.demo.BotClient;
import game.server.demo.StressTest;
import game.ui.InteractiveGridFrame;

import java.util.Arrays;

public class Main {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "server".equalsIgnoreCase(args[0])) {
            SecureLobbyServer.main(new String[0]);
        } else if (args.length > 0 && "game-server".equalsIgnoreCase(args[0])) {
            // Member 03: multi-threaded NIO game server (see docs/MEMBER3_SERVER.md)
            GameServer.main(Arrays.copyOfRange(args, 1, args.length));
        } else if (args.length > 0 && "game-bot".equalsIgnoreCase(args[0])) {
            BotClient.main(Arrays.copyOfRange(args, 1, args.length));
        } else if (args.length > 0 && "game-stress".equalsIgnoreCase(args[0])) {
            StressTest.main(Arrays.copyOfRange(args, 1, args.length));
        } else {
            InteractiveGridFrame.main(args);
        }
    }
}
