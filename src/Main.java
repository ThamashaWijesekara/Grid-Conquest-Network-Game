import game.security.SecureLobbyServer;
import game.ui.InteractiveGridFrame;

public class Main {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "server".equalsIgnoreCase(args[0])) {
            SecureLobbyServer.main(new String[0]);
        } else {
            InteractiveGridFrame.main(args);
        }
    }
}
