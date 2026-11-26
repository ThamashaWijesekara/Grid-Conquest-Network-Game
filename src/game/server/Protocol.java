package game.server;

import java.util.regex.Pattern;

/**
 * Wire protocol shared by the server and its clients.
 *
 * Every message is ONE line of UTF-8 text terminated by '\n', fields separated by '|'.
 * The event names deliberately match the ones already used by the UDP layer
 * (JOIN, HOVER, DICE, CLAIM, TURN_DONE) so the GUI can map them 1:1.
 *
 * Client -> Server                     Server -> Client
 * ---------------------------------    ------------------------------------------
 * JOIN|id|token                        WELCOME|id|gridSize|playerCount
 * ROLL|id                              ROSTER|activeId|claimsLeft|P1:score,P2:score
 * CLAIM|id|row|col                     CELL|ownerId|row|col          (snapshot)
 * HOVER|id|row|col                     SNAPSHOT_END|claimedCount
 * END_TURN|id                          JOIN|id|ONLINE
 * RESET|id                             LEAVE|id|reason
 * PING|id                              DICE|id|value
 * LEAVE|id                             CLAIM|id|row|col
 *                                      HOVER|id|row|col
 *                                      TURN_DONE|finishedId|nextId
 *                                      GAME_OVER|winner(s)|topScore|P1:score,...
 *                                      RESET|byId|firstPlayerId
 *                                      NOTICE|text
 *                                      ERROR|CODE|text
 *                                      PONG
 */
public final class Protocol {
    private Protocol() {
    }

    public static final int DEFAULT_PORT = 9900;
    public static final int DEFAULT_GRID_SIZE = 10;
    public static final int DEFAULT_MAX_PLAYERS = 5;

    /** Longest accepted client line in bytes. Longer lines are a protocol violation. */
    public static final int MAX_LINE_BYTES = 512;

    private static final Pattern PLAYER_ID = Pattern.compile("[A-Z0-9_]{1,16}");

    /** Upper-cases and trims an id the same way the GUI does. */
    public static String normalizeId(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase();
    }

    public static boolean isValidPlayerId(String id) {
        return id != null && PLAYER_ID.matcher(id).matches();
    }

    public static String[] split(String line) {
        return line.split("\\|", -1);
    }

    public static String join(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('|');
            sb.append(parts[i]);
        }
        return sb.toString();
    }
}
