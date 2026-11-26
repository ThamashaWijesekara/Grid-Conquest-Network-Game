package game.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * The authoritative, thread-safe game room (session manager).
 *
 * Many worker threads call into this class at the same time (one per client that currently
 * has something to say) and a timer thread calls in when a turn expires. All of them share
 * the same board, so every operation that reads-then-writes game state runs inside ONE
 * {@link ReentrantLock}. Critical sections are tiny (array lookups plus queueing a few
 * strings), so one coarse lock is both simple to reason about and fast enough.
 *
 * Why broadcasting happens while the lock is held: every client then receives the events in
 * exactly the order the room applied them, so all boards stay identical. Broadcasting never
 * blocks because {@link ClientSession#send(String)} only appends to a queue.
 *
 * What is deliberately NOT under the lock: the HOVER relay. It changes no game state, so it
 * reads the concurrent player map lock-free and never slows down real moves.
 *
 * The server decides everything that matters for fairness: it rolls the dice, checks whose
 * turn it is, how many claims are left and whether a cell is free. A client cannot fake a 6,
 * claim extra cells, or play out of turn.
 */
final class GameRoom {
    private final int gridSize;
    private final int maxPlayers;
    private final long turnTimeoutMs;
    private final ScheduledExecutorService scheduler;
    private final IntSupplier dice;
    private final Consumer<String> log;
    /** Decides whether (playerId, token) from a JOIN is allowed in. Supplied by the Secure Network member's module. */
    private final BiPredicate<String, String> authenticator;

    private final ReentrantLock lock = new ReentrantLock();

    // ---- guarded by 'lock' ----
    private final String[][] owners;
    private int claimedCount;
    private final Map<String, Integer> scores = new TreeMap<>();   // survives reconnects
    private String activeId;
    private int remainingClaims;
    private boolean rolled;
    private boolean gameOver;
    private ScheduledFuture<?> turnTimer;
    private long turnEpoch;

    /** Connected players, sorted by id = turn order. Writes under 'lock', reads anywhere. */
    private final ConcurrentSkipListMap<String, ClientSession> players = new ConcurrentSkipListMap<>();

    // ---- statistics (lock-free) ----
    private final LongAdder messagesHandled = new LongAdder();
    private final LongAdder movesAccepted = new LongAdder();
    private final LongAdder movesRejected = new LongAdder();
    private final LongAdder turnTimeouts = new LongAdder();
    private final Set<String> workerThreadsSeen = ConcurrentHashMap.newKeySet();

    GameRoom(int gridSize, int maxPlayers, long turnTimeoutMs, ScheduledExecutorService scheduler,
             IntSupplier dice, BiPredicate<String, String> authenticator, Consumer<String> log) {
        this.gridSize = gridSize;
        this.maxPlayers = maxPlayers;
        this.turnTimeoutMs = turnTimeoutMs;
        this.scheduler = scheduler;
        this.dice = dice;
        this.authenticator = authenticator;
        this.log = log;
        this.owners = new String[gridSize][gridSize];
    }

    // =====================================================================================
    //  Entry points called from worker threads
    // =====================================================================================

    /** Handles one request line from one client. Runs on a pool worker thread. */
    void onLine(ClientSession s, String line) {
        messagesHandled.increment();
        workerThreadsSeen.add(Thread.currentThread().getName());

        String[] p = Protocol.split(line);
        String cmd = p[0].trim().toUpperCase();

        if (cmd.equals("JOIN")) {
            join(s, p);
            return;
        }
        if (cmd.equals("PING")) {
            s.send("PONG");
            return;
        }

        String me = s.getPlayerId();
        if (me == null) {
            reject(s, "NOT_JOINED", "Send JOIN|id|token first");
            return;
        }
        // Anti-spoofing: the id inside a message must be the id this connection joined with.
        if (p.length < 2 || !me.equals(Protocol.normalizeId(p[1]))) {
            reject(s, "ID_MISMATCH", "This connection is " + me);
            return;
        }

        switch (cmd) {
            case "ROLL" -> roll(s, me);
            case "CLAIM" -> claim(s, me, p);
            case "HOVER" -> hover(s, me, p);
            case "END_TURN" -> endTurn(s, me);
            case "RESET" -> reset(me);
            case "LEAVE" -> s.close("client left");
            default -> reject(s, "UNKNOWN_COMMAND", clean(cmd));
        }
    }

    /** Runs after the last queued line of a closed connection. */
    void onDisconnect(ClientSession s, String reason) {
        String id = s.getPlayerId();
        if (id == null) return;
        lock.lock();
        try {
            if (!players.remove(id, s)) return;
            broadcast(Protocol.join("LEAVE", id, clean(reason)));
            log.accept("room: " + id + " left (" + reason + "), " + players.size() + " player(s) remain");

            if (players.isEmpty()) {
                // Last player gone: start from a clean board for whoever joins next.
                resetBoardLocked();
                scores.clear();
                activeId = null;
            } else if (id.equals(activeId)) {
                passTurn(id);                 // never leave the game waiting on a ghost
            } else if (players.size() < 2) {
                cancelTurnTimer();
            }
        } finally {
            lock.unlock();
        }
    }

    // =====================================================================================
    //  Game commands
    // =====================================================================================

    private void join(ClientSession s, String[] p) {
        String id = Protocol.normalizeId(p.length > 1 ? p[1] : "");
        if (!Protocol.isValidPlayerId(id)) {
            reject(s, "BAD_ID", "Use 1-16 letters, digits or underscore");
            return;
        }
        // p[2] is the lobby token issued by the secure lobby (game.security.TokenManager).
        // Checked BEFORE taking the room lock: verifying an HMAC needs no game state, and a
        // flood of bad logins must not be able to slow down players who are already in a game.
        String token = p.length > 2 ? p[2] : "";
        if (!authenticator.test(id, token)) {
            reject(s, "AUTH_FAILED", "Invalid or expired lobby token for " + id);
            s.close("authentication failed");
            return;
        }
        lock.lock();
        try {
            if (s.isClosed()) return;
            if (s.getPlayerId() != null) {
                reject(s, "ALREADY_JOINED", "You are " + s.getPlayerId());
                return;
            }
            if (players.containsKey(id)) {
                reject(s, "DUPLICATE_ID", id + " is already connected");
                return;
            }
            if (players.size() >= maxPlayers) {
                reject(s, "ROOM_FULL", "Room allows " + maxPlayers + " players");
                return;
            }

            s.setPlayerId(id);
            players.put(id, s);
            scores.putIfAbsent(id, 0);
            if (activeId == null || !players.containsKey(activeId)) {
                activeId = id;
                rolled = false;
                remainingClaims = 0;
            }

            s.send(Protocol.join("WELCOME", id, gridSize, players.size()));
            sendSnapshot(s);
            broadcastExcept(s, Protocol.join("JOIN", id, "ONLINE"));
            if (turnTimer == null || turnTimer.isDone()) restartTurnTimer();
            log.accept("room: " + id + " joined (" + players.size() + "/" + maxPlayers + "), active=" + activeId);
        } finally {
            lock.unlock();
        }
    }

    private void roll(ClientSession s, String me) {
        lock.lock();
        try {
            if (gameOver) { reject(s, "GAME_OVER", "Game finished - RESET to play again"); return; }
            if (!me.equals(activeId)) { reject(s, "NOT_YOUR_TURN", "Active player is " + activeId); return; }
            if (rolled) { reject(s, "ALREADY_ROLLED", remainingClaims + " claim(s) left"); return; }

            int value = dice.getAsInt();
            rolled = true;
            remainingClaims = value;
            movesAccepted.increment();
            broadcast(Protocol.join("DICE", me, value));
            restartTurnTimer();
            log.accept("room: " + me + " rolled " + value);
        } finally {
            lock.unlock();
        }
    }

    private void claim(ClientSession s, String me, String[] p) {
        Integer row = p.length > 3 ? parseInt(p[2]) : null;
        Integer col = p.length > 3 ? parseInt(p[3]) : null;
        if (row == null || col == null) {
            reject(s, "BAD_REQUEST", "CLAIM|id|row|col");
            return;
        }
        lock.lock();
        try {
            if (gameOver) { reject(s, "GAME_OVER", "Game finished - RESET to play again"); return; }
            if (!me.equals(activeId)) { reject(s, "NOT_YOUR_TURN", "Active player is " + activeId); return; }
            if (!rolled || remainingClaims <= 0) { reject(s, "NO_ROLL", "Roll the dice first"); return; }
            if (!inBounds(row, col)) { reject(s, "OUT_OF_RANGE", "Cell " + row + "," + col); return; }
            if (owners[row][col] != null) {
                reject(s, "CELL_TAKEN", row + "," + col + " owned by " + owners[row][col]);
                return;
            }

            owners[row][col] = me;
            claimedCount++;
            scores.merge(me, 1, Integer::sum);
            remainingClaims--;
            movesAccepted.increment();
            broadcast(Protocol.join("CLAIM", me, row, col));
            log.accept("room: " + me + " claimed (" + row + "," + col + "), " + remainingClaims + " left");

            if (claimedCount == gridSize * gridSize) {
                endGame();
            } else if (remainingClaims == 0) {
                passTurn(me);
            } else {
                restartTurnTimer();
            }
        } finally {
            lock.unlock();
        }
    }

    private void endTurn(ClientSession s, String me) {
        lock.lock();
        try {
            if (gameOver) { reject(s, "GAME_OVER", "Game finished - RESET to play again"); return; }
            if (!me.equals(activeId)) { reject(s, "NOT_YOUR_TURN", "Active player is " + activeId); return; }
            movesAccepted.increment();
            passTurn(me);
        } finally {
            lock.unlock();
        }
    }

    private void reset(String by) {
        lock.lock();
        try {
            resetBoardLocked();
            activeId = players.isEmpty() ? null : players.firstKey();
            broadcast(Protocol.join("RESET", by, activeId));
            restartTurnTimer();
            log.accept("room: board reset by " + by);
        } finally {
            lock.unlock();
        }
    }

    /** High-frequency, state-free: relayed to everybody else WITHOUT taking the room lock. */
    private void hover(ClientSession s, String me, String[] p) {
        Integer row = p.length > 3 ? parseInt(p[2]) : null;
        Integer col = p.length > 3 ? parseInt(p[3]) : null;
        if (row == null || col == null || !inBounds(row, col)) {
            reject(s, "BAD_REQUEST", "HOVER|id|row|col inside the grid");
            return;
        }
        String msg = Protocol.join("HOVER", me, row, col);
        for (ClientSession other : players.values()) {
            if (other != s) other.send(msg);
        }
    }

    // =====================================================================================
    //  Turn handling (all of these require 'lock' to be held by the caller)
    // =====================================================================================

    private void passTurn(String finishedId) {
        String next = nextAfter(finishedId);
        activeId = next;
        rolled = false;
        remainingClaims = 0;
        broadcast(Protocol.join("TURN_DONE", finishedId, next));
        restartTurnTimer();
        log.accept("room: turn " + finishedId + " -> " + next);
    }

    private String nextAfter(String id) {
        Map.Entry<String, ClientSession> e = players.higherEntry(id);
        return e != null ? e.getKey() : players.firstKey();
    }

    private void endGame() {
        gameOver = true;
        cancelTurnTimer();
        int top = Integer.MIN_VALUE;
        for (int v : scores.values()) top = Math.max(top, v);
        List<String> winners = new ArrayList<>();
        for (Map.Entry<String, Integer> e : scores.entrySet()) {
            if (e.getValue() == top) winners.add(e.getKey());
        }
        broadcast(Protocol.join("GAME_OVER", String.join("+", winners), top, scoreboard()));
        log.accept("room: GAME OVER, winner(s) " + winners + " with " + top);
    }

    private void resetBoardLocked() {
        for (String[] row : owners) java.util.Arrays.fill(row, null);
        claimedCount = 0;
        scores.replaceAll((k, v) -> 0);
        rolled = false;
        remainingClaims = 0;
        gameOver = false;
        cancelTurnTimer();
    }

    /**
     * (Re)starts the inactivity timer of the current turn.
     *
     * The timer runs on another thread, so it can fire at the very moment the player acts.
     * Each timer is stamped with an epoch; any change of turn state bumps the epoch, and a
     * timer whose epoch is out of date does nothing. That removes the timer-vs-move race.
     */
    private void restartTurnTimer() {
        cancelTurnTimer();
        if (turnTimeoutMs <= 0 || gameOver || activeId == null || players.size() < 2) return;
        final long epoch = turnEpoch;
        final String who = activeId;
        turnTimer = scheduler.schedule(() -> onTurnTimeout(epoch, who), turnTimeoutMs, TimeUnit.MILLISECONDS);
    }

    private void cancelTurnTimer() {
        turnEpoch++;
        if (turnTimer != null) {
            turnTimer.cancel(false);
            turnTimer = null;
        }
    }

    /** Runs on the scheduler thread. */
    private void onTurnTimeout(long epoch, String who) {
        lock.lock();
        try {
            if (epoch != turnEpoch || gameOver || !who.equals(activeId)) return;   // stale timer
            turnTimeouts.increment();
            broadcast(Protocol.join("NOTICE", "Turn timer expired for " + who));
            log.accept("room: turn timer expired for " + who);
            passTurn(who);
        } finally {
            lock.unlock();
        }
    }

    // =====================================================================================
    //  Helpers
    // =====================================================================================

    /** Sends the current board to a player who just joined (called under the lock = consistent). */
    private void sendSnapshot(ClientSession s) {
        StringBuilder roster = new StringBuilder();
        for (String id : players.keySet()) {
            if (roster.length() > 0) roster.append(',');
            roster.append(id).append(':').append(scores.getOrDefault(id, 0));
        }
        s.send(Protocol.join("ROSTER", activeId, remainingClaims, roster));
        for (int r = 0; r < gridSize; r++) {
            for (int c = 0; c < gridSize; c++) {
                if (owners[r][c] != null) s.send(Protocol.join("CELL", owners[r][c], r, c));
            }
        }
        s.send(Protocol.join("SNAPSHOT_END", claimedCount));
        if (gameOver) {
            s.send(Protocol.join("NOTICE", "Game is over - send RESET|id to play again"));
        }
    }

    private String scoreboard() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : scores.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        return sb.toString();
    }

    private void broadcast(String line) {
        for (ClientSession s : players.values()) s.send(line);
    }

    private void broadcastExcept(ClientSession skip, String line) {
        for (ClientSession s : players.values()) {
            if (s != skip) s.send(line);
        }
    }

    private void reject(ClientSession s, String code, String text) {
        movesRejected.increment();
        s.send(Protocol.join("ERROR", code, clean(text)));
    }

    private boolean inBounds(int row, int col) {
        return row >= 0 && row < gridSize && col >= 0 && col < gridSize;
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Keeps free text from breaking the one-line, '|'-separated protocol. */
    private static String clean(String text) {
        return text == null ? "" : text.replace('|', '/').replace('\n', ' ').replace('\r', ' ');
    }

    // =====================================================================================
    //  Administration / diagnostics
    // =====================================================================================

    ClientSession sessionOf(String id) {
        return players.get(Protocol.normalizeId(id));
    }

    List<String> playerIds() {
        return new ArrayList<>(players.keySet());
    }

    String statsLine() {
        lock.lock();
        try {
            return "room: players=" + players.keySet() + " active=" + activeId
                    + " claimed=" + claimedCount + "/" + gridSize * gridSize
                    + " gameOver=" + gameOver
                    + " | handled=" + messagesHandled.sum()
                    + " accepted=" + movesAccepted.sum()
                    + " rejected=" + movesRejected.sum()
                    + " turnTimeouts=" + turnTimeouts.sum()
                    + " workerThreadsUsed=" + workerThreadsSeen.size();
        } finally {
            lock.unlock();
        }
    }

    int workerThreadsUsed() {
        return workerThreadsSeen.size();
    }

    long turnTimeoutCount() {
        return turnTimeouts.sum();
    }

    /** Self-check used by the stress test. @return null when consistent, otherwise a description. */
    String checkInvariants() {
        lock.lock();
        try {
            int owned = 0;
            Map<String, Integer> perOwner = new TreeMap<>();
            for (String[] row : owners) {
                for (String o : row) {
                    if (o != null) {
                        owned++;
                        perOwner.merge(o, 1, Integer::sum);
                    }
                }
            }
            if (owned != claimedCount) return "claimedCount " + claimedCount + " != owned cells " + owned;
            int scoreSum = 0;
            for (int v : scores.values()) scoreSum += v;
            if (scoreSum != claimedCount) return "score sum " + scoreSum + " != claimedCount " + claimedCount;
            for (Map.Entry<String, Integer> e : perOwner.entrySet()) {
                if (!e.getValue().equals(scores.get(e.getKey()))) {
                    return "score of " + e.getKey() + " is " + scores.get(e.getKey()) + " but owns " + e.getValue();
                }
            }
            if (!players.isEmpty() && (activeId == null || !players.containsKey(activeId))) {
                return "active player " + activeId + " is not connected";
            }
            if (remainingClaims < 0 || remainingClaims > 6) return "remainingClaims " + remainingClaims;
            return null;
        } finally {
            lock.unlock();
        }
    }

    void shutdown() {
        lock.lock();
        try {
            cancelTurnTimer();
        } finally {
            lock.unlock();
        }
    }
}
