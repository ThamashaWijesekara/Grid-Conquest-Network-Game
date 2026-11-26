package game.server.demo;

import game.security.SecurityConfig;
import game.security.TokenManager;
import game.server.GameServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Automated proof that the multi-threaded server is correct under concurrency.
 * Run:  java game.server.demo.StressTest        (exit code 0 = everything passed)
 *
 * Each scenario starts its own server on a free port and attacks one thing that can go wrong
 * when many threads share one game: join races, turn-timer-vs-move races, ordering,
 * disconnect hand-over, high-frequency relays and connection churn.
 */
public final class StressTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        long t0 = System.nanoTime();
        testJoinRaces();
        testFullGameWithBots();
        testRuleEnforcement();
        testTurnTimerChain();
        testRacingPlayersAgainstTimer();
        testDisconnectHandover();
        testHoverFlood();
        testConnectionChurn();
        testLobbyTokenAuth();
        System.out.printf("%n%d passed, %d failed in %.1fs%n", passed, failed, (System.nanoTime() - t0) / 1e9);
        System.exit(failed == 0 ? 0 : 1);
    }

    // =====================================================================================
    //  Scenarios
    // =====================================================================================

    /** Many sockets send JOIN at the very same instant: exactly one may win each id / each seat. */
    private static void testJoinRaces() throws Exception {
        section("1. Join races (duplicate ids, full room)");

        try (GameServer server = startServer(c -> c.maxPlayers = 5)) {
            List<String> results = raceJoins(server.getPort(), idsRepeated(3, 4));      // P1,P2,P3 x4 contenders
            long welcomes = results.stream().filter(r -> r.contains("WELCOME")).count();
            long dups = results.stream().filter(r -> r.contains("DUPLICATE_ID")).count();
            check("12 contenders for 3 ids -> exactly 3 winners", welcomes == 3, results.toString());
            check("the other 9 get DUPLICATE_ID", dups == 9, results.toString());
            for (String id : new String[]{"P1", "P2", "P3"}) {
                long w = results.stream().filter(r -> r.startsWith(id + " ") && r.contains("WELCOME")).count();
                check("id " + id + " granted exactly once", w == 1, "granted " + w + "x");
            }
            check("room invariants hold", server.checkInvariants() == null, String.valueOf(server.checkInvariants()));
        }

        try (GameServer server = startServer(c -> c.maxPlayers = 5)) {
            List<String> results = raceJoins(server.getPort(), idsDistinct(9));
            long welcomes = results.stream().filter(r -> r.contains("WELCOME")).count();
            long full = results.stream().filter(r -> r.contains("ROOM_FULL")).count();
            check("9 distinct players for 5 seats -> exactly 5 admitted", welcomes == 5, results.toString());
            check("the other 4 get ROOM_FULL", full == 4, results.toString());
        }
    }

    /** Five bots play a complete game; afterwards every client must hold the identical board. */
    private static void testFullGameWithBots() throws Exception {
        section("2. Five concurrent bots play a full game");
        try (GameServer server = startServer(c -> c.turnTimeoutMs = 0)) {
            List<BotClient> bots = new ArrayList<>();
            List<Thread> threads = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                BotClient b = new BotClient("127.0.0.1", server.getPort(), "P" + i, 0, s -> { });
                bots.add(b);
                Thread t = new Thread(b, "bot-" + i);
                threads.add(t);
            }
            threads.forEach(Thread::start);

            boolean allDone = true;
            for (BotClient b : bots) allDone &= b.awaitFinished(60_000);
            check("all 5 bots reached GAME_OVER", allDone && bots.stream().allMatch(BotClient::isGameOver), "timeout");

            String ref = bots.get(0).boardFingerprint();
            check("every client sees the SAME final board",
                    bots.stream().allMatch(b -> b.boardFingerprint().equals(ref)), "boards differ");
            check("board is completely filled (100 cells)", bots.get(0).cellsOwned() == 100,
                    "owned " + bots.get(0).cellsOwned());
            String ref2 = bots.get(0).scoreboard();
            check("every client got the same scoreboard", bots.stream().allMatch(b -> b.scoreboard().equals(ref2)), ref2);
            int sum = 0;
            for (String part : ref2.split(",")) sum += Integer.parseInt(part.split(":")[1]);
            check("scores add up to 100 (no cell counted twice)", sum == 100, ref2);
            check("no request was rejected (burst claims stayed in order)",
                    bots.stream().allMatch(b -> b.errors() == 0),
                    bots.stream().map(BotClient::lastError).filter(s -> !s.isEmpty()).findFirst().orElse(""));
            check("room invariants hold", server.checkInvariants() == null, String.valueOf(server.checkInvariants()));
            check("more than one worker thread did the work", server.workerThreadsUsed() > 1,
                    "threads used: " + server.workerThreadsUsed());
            System.out.println("      winner(s): " + bots.get(0).winner() + "  scoreboard: " + ref2
                    + "  worker threads used: " + server.workerThreadsUsed());
            bots.forEach(BotClient::stop);
        }
    }

    /** The server, not the client, decides what is legal. Fixed dice (=3) makes the script deterministic. */
    private static void testRuleEnforcement() throws Exception {
        section("3. Server-side rule enforcement / anti-cheating");
        try (GameServer server = startServer(c -> {
            c.dice = () -> 3;
            c.turnTimeoutMs = 0;
        });
             LineClient p1 = new LineClient(server.getPort());
             LineClient p2 = new LineClient(server.getPort());
             LineClient raw = new LineClient(server.getPort())) {

            raw.send("ROLL|P1");
            check("request before JOIN -> NOT_JOINED", raw.await(starts("ERROR|NOT_JOINED"), 2000) != null, "");
            raw.send("JOIN|bad id!|x");
            check("illegal player id -> BAD_ID", raw.await(starts("ERROR|BAD_ID"), 2000) != null, "");

            p1.send("JOIN|p1|x");   // lower case on purpose: the server normalises ids
            check("P1 joins (id normalised to upper case)", p1.await(starts("WELCOME|P1|10"), 2000) != null, "");
            p2.send("JOIN|P2|x");
            check("P2 joins", p2.await(starts("WELCOME|P2|10"), 2000) != null, "");

            p2.send("ROLL|P2");
            check("rolling out of turn -> NOT_YOUR_TURN", p2.await(starts("ERROR|NOT_YOUR_TURN"), 2000) != null, "");
            p2.send("ROLL|P1");
            check("impersonating P1 -> ID_MISMATCH", p2.await(starts("ERROR|ID_MISMATCH"), 2000) != null, "");
            p1.send("CLAIM|P1|0|0");
            check("claim before rolling -> NO_ROLL", p1.await(starts("ERROR|NO_ROLL"), 2000) != null, "");

            p1.send("ROLL|P1|6");   // client tries to dictate a 6
            check("dice are rolled by the server (client's '6' ignored)",
                    p1.await(starts("DICE|P1|3"), 2000) != null && p2.await(starts("DICE|P1|3"), 2000) != null, "");
            p1.send("ROLL|P1");
            check("second roll in the same turn -> ALREADY_ROLLED", p1.await(starts("ERROR|ALREADY_ROLLED"), 2000) != null, "");
            p1.send("CLAIM|P1|99|0");
            check("cell outside the grid -> OUT_OF_RANGE", p1.await(starts("ERROR|OUT_OF_RANGE"), 2000) != null, "");
            p1.send("CLAIM|P1|0|0");
            check("valid claim is broadcast to the other player",
                    p2.await(starts("CLAIM|P1|0|0"), 2000) != null, "");
            p1.send("CLAIM|P1|0|0");
            check("claiming an owned cell -> CELL_TAKEN", p1.await(starts("ERROR|CELL_TAKEN"), 2000) != null, "");
            p1.send("CLAIM|P1|0|1");
            p1.send("CLAIM|P1|0|2");
            check("after the 3rd claim the turn passes to P2",
                    p2.await(starts("TURN_DONE|P1|P2"), 2000) != null, "");
            p1.send("CLAIM|P1|5|5");
            check("P1 can no longer claim -> NOT_YOUR_TURN", p1.await(starts("ERROR|NOT_YOUR_TURN"), 2000) != null, "");
            p1.send("FLY|P1");
            check("unknown command -> UNKNOWN_COMMAND", p1.await(starts("ERROR|UNKNOWN_COMMAND"), 2000) != null, "");

            raw.sendRaw("X".repeat(600) + "\n");
            check("over-long line -> ERROR|PROTOCOL", raw.await(starts("ERROR|PROTOCOL"), 2000) != null, "");
            check("... and the connection is dropped", raw.await(starts("<EOF>"), 2000) != null, "");
            check("room invariants hold", server.checkInvariants() == null, String.valueOf(server.checkInvariants()));
        }
    }

    /** Nobody acts, so only the timer thread moves the game: the turn chain must be gap-free and duplicate-free. */
    private static void testTurnTimerChain() throws Exception {
        section("4. Turn timer (separate thread) passes idle turns exactly once");
        try (GameServer server = startServer(c -> c.turnTimeoutMs = 100);
             LineClient p1 = new LineClient(server.getPort());
             LineClient p2 = new LineClient(server.getPort());
             LineClient p3 = new LineClient(server.getPort())) {
            p1.send("JOIN|P1|x");
            p1.await(starts("SNAPSHOT_END"), 2000);
            p2.send("JOIN|P2|x");
            p2.await(starts("SNAPSHOT_END"), 2000);
            p3.send("JOIN|P3|x");
            p3.await(starts("SNAPSHOT_END"), 2000);
            Thread.sleep(1500);

            List<String> turns = p1.historyStarting("TURN_DONE|");
            check("at least 8 timeouts in 1.5s at 100ms", turns.size() >= 8, turns.size() + " turn changes");
            check("turn chain has no gaps or doubles", chainIsConsistent(turns), turns.toString());
            check("the idle P1 was passed first", !turns.isEmpty() && turns.get(0).startsWith("TURN_DONE|P1|"),
                    turns.isEmpty() ? "none" : turns.get(0));
            check("all three clients saw the same turn sequence",
                    suffixOf(p1.historyStarting("TURN_DONE|"), p2.historyStarting("TURN_DONE|"))
                            && suffixOf(p1.historyStarting("TURN_DONE|"), p3.historyStarting("TURN_DONE|")), "");
        }
    }

    /**
     * Worst case for the timer: players act at random moments, some right at the 40 ms timeout.
     * Whatever interleaving happens, all clients must observe the same event stream.
     */
    private static void testRacingPlayersAgainstTimer() throws Exception {
        section("5. Players racing the turn timer (random timing, 2 seconds)");
        try (GameServer server = startServer(c -> c.turnTimeoutMs = 40)) {
            int n = 4;
            List<LineClient> clients = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                LineClient c = new LineClient(server.getPort());
                c.send("JOIN|P" + i + "|x");
                c.await(starts("SNAPSHOT_END"), 2000);
                clients.add(c);
            }
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            List<Thread> actors = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final LineClient c = clients.get(i);
                final String me = "P" + (i + 1);
                final Random rnd = new Random(1000 + i);
                Thread t = new Thread(() -> {
                    try {
                        while (System.nanoTime() < end) {
                            Thread.sleep(rnd.nextInt(60));
                            c.send("ROLL|" + me);
                            Thread.sleep(rnd.nextInt(30));
                            c.send("CLAIM|" + me + "|" + rnd.nextInt(10) + "|" + rnd.nextInt(10));
                            if (rnd.nextInt(5) == 0) c.send("END_TURN|" + me);
                        }
                    } catch (Exception ignored) {
                    }
                }, "actor-" + me);
                actors.add(t);
            }
            actors.forEach(Thread::start);
            for (Thread t : actors) t.join();
            Thread.sleep(500);                                  // let everything drain

            List<List<String>> logs = new ArrayList<>();
            for (LineClient c : clients) logs.add(c.historyStarting("DICE|", "CLAIM|", "TURN_DONE|"));
            List<String> longest = Collections.max(logs, (a, b) -> Integer.compare(a.size(), b.size()));
            boolean same = true;
            for (List<String> log : logs) same &= suffixOf(longest, log);
            check("all 4 clients saw one identical ordered event stream", same,
                    logs.stream().map(List::size).toList().toString());
            check("turn chain consistent despite timer/move races",
                    chainIsConsistent(longest.stream().filter(s -> s.startsWith("TURN_DONE|")).toList()), "");
            check("timer actually fired during the test", server.turnTimeouts() > 0, "timeouts=" + server.turnTimeouts());
            check("room invariants hold", server.checkInvariants() == null, String.valueOf(server.checkInvariants()));
            System.out.println("      events=" + longest.size() + " timeouts=" + server.turnTimeouts());
            for (LineClient c : clients) c.close();
        }
    }

    /** A player vanishes mid-turn: the game must not wait for a ghost. */
    private static void testDisconnectHandover() throws Exception {
        section("6. Disconnect hand-over");
        try (GameServer server = startServer(c -> c.turnTimeoutMs = 0)) {
            LineClient p1 = new LineClient(server.getPort());
            LineClient p2 = new LineClient(server.getPort());
            LineClient p3 = new LineClient(server.getPort());
            p1.send("JOIN|P1|x");
            p1.await(starts("SNAPSHOT_END"), 2000);
            p2.send("JOIN|P2|x");
            p2.await(starts("SNAPSHOT_END"), 2000);
            p3.send("JOIN|P3|x");
            p3.await(starts("SNAPSHOT_END"), 2000);

            p1.close();     // P1 is the active player
            check("others are told P1 left", p2.await(starts("LEAVE|P1|"), 2000) != null, "");
            check("turn is handed to P2", p3.await(starts("TURN_DONE|P1|P2"), 2000) != null, "");
            p2.send("ROLL|P2");
            check("P2 can play right away", p3.await(starts("DICE|P2|"), 2000) != null, "");

            LineClient again = new LineClient(server.getPort());
            again.send("JOIN|P1|x");
            check("P1 can re-join with the same id", again.await(starts("WELCOME|P1|"), 2000) != null, "");
            check("... and receives the current board", again.await(starts("SNAPSHOT_END"), 2000) != null, "");
            again.close();

            p2.close();
            p3.close();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (server.sessionCount() > 0 && System.nanoTime() < deadline) Thread.sleep(20);
            check("all sessions cleaned up", server.sessionCount() == 0, "sessions=" + server.sessionCount());

            try (LineClient fresh = new LineClient(server.getPort())) {
                fresh.send("JOIN|P9|x");
                check("empty room starts a fresh game", fresh.await(starts("SNAPSHOT_END|0"), 2000) != null, "");
            }
        }
    }

    /** 5 clients each fire 2000 hover events at once through the lock-free relay path. */
    private static void testHoverFlood() throws Exception {
        section("7. Concurrent hover flood (5 senders x 2000, lock-free relay)");
        try (GameServer server = startServer(c -> c.turnTimeoutMs = 0)) {
            int n = 5;
            int perSender = 2000;
            List<LineClient> clients = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                LineClient c = new LineClient(server.getPort());
                c.send("JOIN|P" + i + "|x");
                c.await(starts("SNAPSHOT_END"), 2000);
                clients.add(c);
            }
            CountDownLatch go = new CountDownLatch(1);
            List<Thread> senders = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final LineClient c = clients.get(i);
                final String me = "P" + (i + 1);
                Thread t = new Thread(() -> {
                    try {
                        go.await();
                        StringBuilder batch = new StringBuilder();
                        for (int k = 0; k < perSender; k++) {
                            int cell = k % 100;
                            batch.append("HOVER|").append(me).append('|').append(cell / 10).append('|').append(cell % 10).append('\n');
                            if (batch.length() > 1500) {
                                c.sendRaw(batch.toString());
                                batch.setLength(0);
                            }
                        }
                        c.sendRaw(batch.toString());
                    } catch (Exception ignored) {
                    }
                });
                senders.add(t);
                t.start();
            }
            go.countDown();
            for (Thread t : senders) t.join();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            boolean allReceived = false;
            while (!allReceived && System.nanoTime() < deadline) {
                allReceived = true;
                for (LineClient c : clients) {
                    allReceived &= c.historyStarting("HOVER|").size() >= (n - 1) * perSender;
                }
                if (!allReceived) Thread.sleep(25);
            }
            check("every client received all " + (n - 1) * perSender + " hovers from the others", allReceived, "");

            boolean ordered = true;
            for (int r = 0; r < n; r++) {
                for (int s = 0; s < n; s++) {
                    if (r == s) continue;
                    String sender = "P" + (s + 1);
                    int expected = 0;
                    for (String h : clients.get(r).historyStarting("HOVER|" + sender + "|")) {
                        String[] f = h.split("\\|");
                        int cell = Integer.parseInt(f[2]) * 10 + Integer.parseInt(f[3]);
                        if (cell != expected % 100) ordered = false;
                        expected++;
                    }
                }
            }
            check("per-sender order preserved on every receiver", ordered, "");
            check("nobody was dropped as a slow consumer", server.sessionCount() == n, "sessions=" + server.sessionCount());
            for (LineClient c : clients) c.close();
        }
    }

    /** 60 threads connect, join random ids, poke the game and disconnect in random ways. */
    private static void testConnectionChurn() throws Exception {
        section("8. Connection churn (60 threads x 15 connect/play/disconnect cycles)");
        // The default 64-connection cap would (correctly) refuse some of the rapid reconnects while the
        // server is still cleaning up closed sockets, so lift it here to test join/leave races only.
        try (GameServer server = startServer(c -> {
            c.turnTimeoutMs = 25;
            c.maxConnections = 5000;
        })) {
            int threads = 60;
            int cycles = 15;
            ExecutorService ex = Executors.newFixedThreadPool(threads);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int seed = t;
                futures.add(ex.submit(() -> {
                    Random rnd = new Random(seed);
                    int problems = 0;
                    for (int i = 0; i < cycles; i++) {
                        try (LineClient c = new LineClient(server.getPort())) {
                            String id = "P" + (1 + rnd.nextInt(8));
                            c.send("JOIN|" + id + "|x");
                            if (rnd.nextBoolean()) {
                                c.send("ROLL|" + id);
                                c.send("CLAIM|" + id + "|" + rnd.nextInt(10) + "|" + rnd.nextInt(10));
                                c.send("END_TURN|" + id);
                            }
                            Thread.sleep(rnd.nextInt(15));
                        } catch (IOException e) {
                            problems++;
                        }
                    }
                    return problems;
                }));
            }
            int problems = 0;
            for (Future<Integer> f : futures) problems += f.get(60, TimeUnit.SECONDS);
            ex.shutdown();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (server.sessionCount() > 0 && System.nanoTime() < deadline) Thread.sleep(20);
            check("all " + threads * cycles + " connections were served", problems == 0, problems + " connect failures");
            check("every session was cleaned up", server.sessionCount() == 0, "sessions=" + server.sessionCount());
            check("room invariants hold after the chaos", server.checkInvariants() == null,
                    String.valueOf(server.checkInvariants()));
            System.out.println("      " + server.statsLine().replace("\n", "\n      "));
        }
    }

    /** With --require-token a JOIN must carry a genuine, unexpired lobby token that belongs to that very id. */
    private static void testLobbyTokenAuth() throws Exception {
        section("9. Lobby-token authentication on JOIN (integration with the Secure Network module)");
        TokenManager lobby = new TokenManager(SecurityConfig.sharedSecret(), SecurityConfig.TOKEN_LIFETIME);
        TokenManager expiredIssuer = new TokenManager(SecurityConfig.sharedSecret(), Duration.ofMillis(-1000));
        TokenManager foreignIssuer = new TokenManager("a-different-secret-16+", SecurityConfig.TOKEN_LIFETIME);

        try (GameServer server = startServer(c -> {
            c.requireLobbyTokens();
            c.turnTimeoutMs = 0;
        })) {
            int port = server.getPort();
            String p1Token = lobby.issue("P1");
            String tampered = p1Token.substring(0, p1Token.length() - 1) + (p1Token.endsWith("A") ? "B" : "A");

            check("genuine token for P1 -> WELCOME", firstAnswer(port, "JOIN|P1|" + p1Token).startsWith("WELCOME|P1"), "");
            // The token is checked before anything else, so each refusal below is purely about the token.
            check("P2 presenting P1's token -> AUTH_FAILED",
                    firstAnswer(port, "JOIN|P2|" + p1Token).startsWith("ERROR|AUTH_FAILED"), "");
            check("garbage token -> AUTH_FAILED", firstAnswer(port, "JOIN|P2|x").startsWith("ERROR|AUTH_FAILED"), "");
            check("missing token -> AUTH_FAILED", firstAnswer(port, "JOIN|P2").startsWith("ERROR|AUTH_FAILED"), "");
            check("tampered token -> AUTH_FAILED",
                    firstAnswer(port, "JOIN|P1|" + tampered).startsWith("ERROR|AUTH_FAILED"), "");
            check("expired token -> AUTH_FAILED",
                    firstAnswer(port, "JOIN|P2|" + expiredIssuer.issue("P2")).startsWith("ERROR|AUTH_FAILED"), "");
            check("token signed with another secret -> AUTH_FAILED",
                    firstAnswer(port, "JOIN|P2|" + foreignIssuer.issue("P2")).startsWith("ERROR|AUTH_FAILED"), "");

            try (LineClient bad = new LineClient(port)) {
                bad.send("JOIN|P2|forged");
                check("a failed login gets an explanation ...", bad.await(starts("ERROR|AUTH_FAILED"), 2000) != null, "");
                check("... and the connection is dropped", bad.await(starts("<EOF>"), 2000) != null, "");
            }

            try (LineClient legit = new LineClient(port)) {
                legit.send("JOIN|P2|" + lobby.issue("P2"));
                check("failed attempts did not burn the id: real P2 still gets in",
                        legit.await(starts("WELCOME|P2"), 2000) != null, "");
            }
            check("room invariants hold", server.checkInvariants() == null, String.valueOf(server.checkInvariants()));
        }
    }

    /** Opens a fresh connection, sends one line, returns the first WELCOME / ERROR / EOF line it gets. */
    private static String firstAnswer(int port, String line) throws Exception {
        try (LineClient c = new LineClient(port)) {
            c.send(line);
            String answer = c.await(l -> l.startsWith("WELCOME") || l.startsWith("ERROR") || l.equals("<EOF>"), 3000);
            return answer == null ? "<timeout>" : answer;
        }
    }

    // =====================================================================================
    //  Helpers
    // =====================================================================================

    private static GameServer startServer(Consumer<GameServer.Config> tweak) throws IOException {
        GameServer.Config cfg = new GameServer.Config();
        cfg.port = 0;
        cfg.verbose = false;
        tweak.accept(cfg);
        GameServer server = new GameServer(cfg);
        server.start();
        return server;
    }

    private static List<String> idsRepeated(int distinct, int each) {
        List<String> ids = new ArrayList<>();
        for (int k = 0; k < each; k++) {
            for (int i = 1; i <= distinct; i++) ids.add("P" + i);
        }
        return ids;
    }

    private static List<String> idsDistinct(int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= count; i++) ids.add("P" + i);
        return ids;
    }

    /** Opens one socket per id, releases them all at once and reports "ID <first answer line>" for each. */
    private static List<String> raceJoins(int port, List<String> ids) throws Exception {
        CountDownLatch ready = new CountDownLatch(ids.size());
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService ex = Executors.newFixedThreadPool(ids.size());
        List<LineClient> keepOpen = Collections.synchronizedList(new ArrayList<>());
        List<Future<String>> futures = new ArrayList<>();
        for (String id : ids) {
            futures.add(ex.submit(() -> {
                LineClient c = new LineClient(port);
                keepOpen.add(c);
                ready.countDown();
                go.await();
                c.send("JOIN|" + id + "|x");
                String answer = c.await(l -> l.startsWith("WELCOME") || l.startsWith("ERROR"), 3000);
                return id + " " + answer;
            }));
        }
        ready.await();
        go.countDown();
        List<String> results = new ArrayList<>();
        for (Future<String> f : futures) results.add(f.get(10, TimeUnit.SECONDS));
        ex.shutdown();
        keepOpen.forEach(LineClient::close);
        return results;
    }

    /** Each TURN_DONE must start where the previous one ended. */
    private static boolean chainIsConsistent(List<String> turns) {
        for (int i = 1; i < turns.size(); i++) {
            String prevNext = turns.get(i - 1).split("\\|")[2];
            String finished = turns.get(i).split("\\|")[1];
            if (!prevNext.equals(finished)) return false;
        }
        return true;
    }

    /** True if 'part' equals the tail of 'whole' (a client sees the global stream from its join onwards). */
    private static boolean suffixOf(List<String> whole, List<String> part) {
        if (part.size() > whole.size()) return suffixOf(part, whole);
        return whole.subList(whole.size() - part.size(), whole.size()).equals(part);
    }

    private static Predicate<String> starts(String prefix) {
        return l -> l.startsWith(prefix);
    }

    private static void section(String title) {
        System.out.println("\n== " + title);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name + (detail.isEmpty() ? "" : "  -> " + detail));
        }
    }

    /** Minimal line-based test client. Records everything it receives. */
    private static final class LineClient implements AutoCloseable {
        private final Socket socket;
        private final OutputStream out;
        private final BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        private final List<String> history = Collections.synchronizedList(new ArrayList<>());

        LineClient(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setTcpNoDelay(true);
            out = socket.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            Thread reader = new Thread(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        history.add(line);
                        inbox.add(line);
                    }
                } catch (IOException ignored) {
                }
                inbox.add("<EOF>");
            }, "test-client-reader");
            reader.setDaemon(true);
            reader.start();
        }

        synchronized void send(String line) throws IOException {
            sendRaw(line + "\n");
        }

        synchronized void sendRaw(String text) throws IOException {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        /** Consumes lines until one matches; returns it, or null on timeout. */
        String await(Predicate<String> match, long timeoutMs) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (true) {
                long left = deadline - System.nanoTime();
                if (left <= 0) return null;
                String line = inbox.poll(left, TimeUnit.NANOSECONDS);
                if (line == null) return null;
                if (match.test(line)) return line;
            }
        }

        List<String> historyStarting(String... prefixes) {
            List<String> result = new ArrayList<>();
            synchronized (history) {
                for (String l : history) {
                    for (String p : prefixes) {
                        if (l.startsWith(p)) {
                            result.add(l);
                            break;
                        }
                    }
                }
            }
            return result;
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
