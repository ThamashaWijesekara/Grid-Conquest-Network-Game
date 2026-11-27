package game.server.demo;

import game.security.SecurityConfig;
import game.security.TokenManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * A scripted player that speaks the server protocol over a plain blocking socket.
 *
 * It exists for two reasons: (1) to demo and stress the multi-threaded server without opening
 * five GUI windows, and (2) as a small reference for whoever wires the real GUI to the server.
 *
 * Behaviour: wait for my turn -> ROLL -> burst-send one CLAIM per rolled number on random free
 * cells (the server must keep those in order) -> repeat until GAME_OVER.
 *
 * Run by hand (one terminal per bot):  java game.server.demo.BotClient 127.0.0.1 9900 P1 400
 * Against a server started with --require-token, add a 5th argument: a real lobby token, or the
 * word "auto" to have the bot sign its own token with the shared GRID_SECURITY_SECRET.
 */
public final class BotClient implements Runnable {
    private final String host;
    private final int port;
    private final String id;
    private final long thinkMs;
    private final String token;
    private final Consumer<String> log;
    private final Random random;

    private volatile Socket socket;
    private OutputStream out;

    private String[][] owners = new String[0][0];       // reader thread only
    private volatile String active = "";
    private boolean rollSent;                             // reader thread only
    private volatile boolean joined;
    private volatile boolean gameOver;
    private volatile String winner = "";
    private volatile String finalScoreboard = "";
    private volatile String lastError = "";
    private final AtomicInteger errors = new AtomicInteger();
    private final AtomicInteger claimsApplied = new AtomicInteger();
    private final CountDownLatch finished = new CountDownLatch(1);

    public BotClient(String host, int port, String id, long thinkMs, Consumer<String> log) {
        this(host, port, id, thinkMs, "ONLINE", log);
    }

    /** @param token sent in JOIN|id|token; needed when the server runs with --require-token */
    public BotClient(String host, int port, String id, long thinkMs, String token, Consumer<String> log) {
        this.host = host;
        this.port = port;
        this.id = id;
        this.thinkMs = thinkMs;
        this.token = token;
        this.log = log;
        this.random = new Random(id.hashCode() * 31L + System.nanoTime());
    }

    @Override
    public void run() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 3000);
            s.setTcpNoDelay(true);
            socket = s;
            out = s.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));

            send("JOIN|" + id + "|" + token);
            String line;
            while ((line = in.readLine()) != null) {
                handle(line);
                if (gameOver) break;
            }
        } catch (IOException e) {
            if (!gameOver) log.accept(id + ": connection ended (" + e.getMessage() + ")");
        } finally {
            finished.countDown();
        }
    }

    private void handle(String line) throws IOException {
        String[] p = line.split("\\|", -1);
        switch (p[0]) {
            case "WELCOME" -> {
                int n = Integer.parseInt(p[2]);
                owners = new String[n][n];
                log.accept(id + ": welcome, grid " + n + "x" + n + ", " + p[3] + " player(s) in room");
            }
            case "ROSTER" -> active = p[1];
            case "CELL" -> owners[Integer.parseInt(p[2])][Integer.parseInt(p[3])] = p[1];
            case "SNAPSHOT_END" -> joined = true;
            case "DICE" -> {
                log.accept(id + ": " + p[1] + " rolled " + p[2]);
                if (p[1].equals(id)) burstClaims(Integer.parseInt(p[2]));
            }
            case "CLAIM" -> {
                owners[Integer.parseInt(p[2])][Integer.parseInt(p[3])] = p[1];
                claimsApplied.incrementAndGet();
            }
            case "TURN_DONE" -> {
                active = p[2];
                rollSent = false;
                log.accept(id + ": turn " + p[1] + " -> " + p[2]);
            }
            case "RESET" -> {
                owners = new String[owners.length][owners.length];
                active = p[2];
                rollSent = false;
            }
            case "GAME_OVER" -> {
                winner = p[1];
                finalScoreboard = p[3];
                gameOver = true;
                log.accept(id + ": GAME OVER, winner " + p[1] + " (" + p[3] + ")");
            }
            case "ERROR" -> {
                errors.incrementAndGet();
                lastError = line;
                log.accept(id + ": server said " + line);
            }
            default -> { /* JOIN, LEAVE, HOVER, NOTICE, PONG: nothing to do */ }
        }
        if (joined && !gameOver && !rollSent && id.equals(active)) {
            rollSent = true;
            pause();
            send("ROLL|" + id);
        }
    }

    /** Sends all claims back-to-back WITHOUT waiting for answers: the server must keep them in order. */
    private void burstClaims(int count) throws IOException {
        List<int[]> free = new ArrayList<>();
        for (int r = 0; r < owners.length; r++) {
            for (int c = 0; c < owners.length; c++) {
                if (owners[r][c] == null) free.add(new int[]{r, c});
            }
        }
        Collections.shuffle(free, random);
        for (int i = 0; i < Math.min(count, free.size()); i++) {
            pause();
            send("CLAIM|" + id + "|" + free.get(i)[0] + "|" + free.get(i)[1]);
        }
    }

    private void pause() {
        if (thinkMs <= 0) return;
        try {
            Thread.sleep(thinkMs / 2 + random.nextInt((int) thinkMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void send(String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // ---- results ----

    public boolean awaitFinished(long timeoutMs) throws InterruptedException {
        return finished.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        try {
            Socket s = socket;
            if (s != null) s.close();
        } catch (IOException ignored) {
        }
    }

    public String id() {
        return id;
    }

    public boolean isGameOver() {
        return gameOver;
    }

    public String winner() {
        return winner;
    }

    public String scoreboard() {
        return finalScoreboard;
    }

    public int errors() {
        return errors.get();
    }

    public String lastError() {
        return lastError;
    }

    /** Board as this client saw it (call after the game ended). */
    public String boardFingerprint() {
        StringBuilder sb = new StringBuilder();
        for (String[] row : owners) {
            for (String o : row) sb.append(o == null ? "." : o).append(',');
            sb.append(';');
        }
        return sb.toString();
    }

    public int cellsOwned() {
        int n = 0;
        for (String[] row : owners) {
            for (String o : row) if (o != null) n++;
        }
        return n;
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9900;
        String id = args.length > 2 ? args[2].toUpperCase() : "BOT1";
        long think = args.length > 3 ? Long.parseLong(args[3]) : 300;
        String token = args.length > 4 ? args[4] : "ONLINE";
        if ("auto".equalsIgnoreCase(token)) {
            token = new TokenManager(SecurityConfig.sharedSecret(), SecurityConfig.TOKEN_LIFETIME).issue(id);
        }
        BotClient bot = new BotClient(host, port, id, think, token, System.out::println);
        bot.run();
    }
}
