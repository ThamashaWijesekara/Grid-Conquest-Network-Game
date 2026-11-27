package game.server;

import game.security.SecurityConfig;
import game.security.TokenManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiPredicate;
import java.util.function.IntSupplier;

/**
 * Multi-threaded game server for the Dice-Roll Grid Capture Game (Member 3 - Multi-Threading).
 *
 * <pre>
 *   clients ──TCP──►  [ nio-selector ]  accepts, reads, frames lines, writes
 *                           │  one line at a time, in order per client
 *                           ▼
 *                     [ worker-1..N ]   thread pool: validate + apply game rules
 *                           │
 *                           ▼
 *                       GameRoom        one lock = one consistent board for everyone
 *                           ▲
 *                     [ turn-timer ]    scheduled turn timeouts
 * </pre>
 *
 * Threads:
 * <ul>
 *   <li><b>nio-selector</b> (1) - the ONLY thread that touches sockets. Non-blocking NIO
 *       (Selector, ServerSocketChannel, SocketChannel, ByteBuffer) lets one thread serve many clients.</li>
 *   <li><b>worker-N</b> (pool) - run game logic. Lines of one client are processed in order, lines of
 *       different clients in parallel.</li>
 *   <li><b>turn-timer</b> (1) - passes the turn when a player stays idle too long.</li>
 *   <li><b>console</b> (optional) - admin commands typed on the server console.</li>
 * </ul>
 */
public final class GameServer implements AutoCloseable {

    /** All tunables in one place. Public fields keep the demo/test code short. */
    public static final class Config {
        public int port = Protocol.DEFAULT_PORT;
        public int workerThreads = Math.max(4, Runtime.getRuntime().availableProcessors());
        public int maxPlayers = Protocol.DEFAULT_MAX_PLAYERS;
        public int gridSize = Protocol.DEFAULT_GRID_SIZE;
        public int maxConnections = 64;
        /** A turn is passed on when the active player does nothing for this long (0 = never). */
        public long turnTimeoutMs = 60_000;
        /** A connection that has not sent JOIN within this time is dropped. */
        public long joinTimeoutMs = 30_000;
        /** Drop connections silent for this long (0 = off, since waiting for your turn is silent). */
        public long idleTimeoutMs = 0;
        public boolean verbose = true;
        public IntSupplier dice = () -> ThreadLocalRandom.current().nextInt(1, 7);
        /** (playerId, token) -> may this client join? Default accepts everybody (classroom/test mode). */
        public BiPredicate<String, String> authenticator = (id, token) -> true;
        /** Informational only (shown in the startup log); set by {@link #requireLobbyTokens()}. */
        public boolean tokensRequired;

        /**
         * Makes JOIN require the signed token handed out by the secure lobby
         * ({@link game.security.SecureLobbyServer}). Uses the same shared secret as the lobby
         * and the UDP layer, so no code of the Secure Network module is changed or duplicated.
         */
        public void requireLobbyTokens() {
            TokenManager tokens = new TokenManager(SecurityConfig.sharedSecret(), SecurityConfig.TOKEN_LIFETIME);
            tokensRequired = true;
            authenticator = (id, token) -> {
                TokenManager.TokenDetails details = tokens.verify(token);
                return details != null && details.playerId().equals(id);
            };
        }
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final Config config;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService scheduler;
    private final GameRoom room;

    private final Set<ClientSession> sessions = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<ClientSession> writeRequests = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<CloseRequest> closeRequests = new ConcurrentLinkedQueue<>();
    private final LongAdder totalConnections = new LongAdder();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final ByteBuffer readBuf = ByteBuffer.allocate(4096);   // selector thread only

    private Selector selector;
    private ServerSocketChannel serverChannel;
    private Thread ioThread;
    private volatile boolean running;

    private record CloseRequest(ClientSession session, String reason) {
    }

    public GameServer(Config config) {
        this.config = config;
        this.workers = new ThreadPoolExecutor(
                config.workerThreads, config.workerThreads, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), new NamedThreadFactory("worker"));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("turn-timer"));
        this.room = new GameRoom(config.gridSize, config.maxPlayers, config.turnTimeoutMs,
                scheduler, config.dice, config.authenticator, this::log);
    }

    // =====================================================================================
    //  Lifecycle
    // =====================================================================================

    public void start() throws IOException {
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        serverChannel.bind(new InetSocketAddress(config.port));      // all interfaces: LAN players can connect
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        running = true;
        ioThread = new Thread(this::ioLoop, "nio-selector");
        ioThread.setDaemon(true);
        ioThread.start();
    }

    /** The port actually bound (useful when the config asked for port 0). */
    public int getPort() {
        try {
            return ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
        } catch (IOException e) {
            return config.port;
        }
    }

    public void awaitStop() throws InterruptedException {
        stopped.await();
    }

    @Override
    public void close() {
        stop();
    }

    public void stop() {
        if (!running) return;
        running = false;
        selector.wakeup();
        try {
            ioThread.join(3000);                       // the I/O thread says goodbye to clients and closes them
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        workers.shutdown();
        try {
            if (!workers.awaitTermination(2, TimeUnit.SECONDS)) workers.shutdownNow();
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
        room.shutdown();
        scheduler.shutdownNow();
        log("server stopped");
        stopped.countDown();
    }

    // =====================================================================================
    //  The I/O reactor (nio-selector thread)
    // =====================================================================================

    private void ioLoop() {
        log("reactor started, listening on port " + getPort() + " with " + config.workerThreads + " workers, "
                + (config.tokensRequired ? "lobby token REQUIRED to join" : "open join (no token check)"));
        long lastSweep = System.nanoTime();
        while (running) {
            try {
                selector.select(500);
                processCloseRequests();
                processWriteRequests();

                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    try {
                        if (!key.isValid()) continue;
                        if (key.isAcceptable()) {
                            acceptAll();
                        } else {
                            ClientSession s = (ClientSession) key.attachment();
                            if (key.isReadable()) onReadable(s);
                            if (key.isValid() && key.isWritable()) onWritable(s);
                        }
                    } catch (CancelledKeyException ignored) {
                        // the session was closed while we were looking at its key
                    }
                }

                if (System.nanoTime() - lastSweep > TimeUnit.SECONDS.toNanos(1)) {
                    sweepTimeouts();
                    lastSweep = System.nanoTime();
                }
            } catch (ClosedSelectorException e) {
                break;
            } catch (IOException e) {
                if (running) log("reactor error: " + e);
            }
        }
        shutdownSessions();
    }

    private void acceptAll() throws IOException {
        SocketChannel ch;
        while ((ch = serverChannel.accept()) != null) {
            if (sessions.size() >= config.maxConnections) {
                ch.close();
                log("connection refused: server full");
                continue;
            }
            ch.configureBlocking(false);
            ch.setOption(StandardSocketOptions.TCP_NODELAY, true);
            ch.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
            ClientSession s = new ClientSession(ch, this, workers);
            s.key = ch.register(selector, SelectionKey.OP_READ, s);
            sessions.add(s);
            totalConnections.increment();
            log("accepted " + s);
        }
    }

    private void onReadable(ClientSession s) {
        try {
            int n = s.channel.read(readBuf);
            if (n < 0) {
                closeNow(s, "client disconnected");
                return;
            }
            if (n == 0) return;
            s.touch();
            readBuf.flip();
            boolean ok = s.frame(readBuf, line -> dispatch(s, line));
            readBuf.clear();
            if (!ok) {
                s.send(Protocol.join("ERROR", "PROTOCOL", "Line too long or too many requests"));
                closeNow(s, "protocol violation");
            }
        } catch (IOException e) {
            closeNow(s, "i/o error: " + e.getMessage());
        }
    }

    /** Hands one complete line to the worker pool (via the client's own ordered queue). */
    private boolean dispatch(ClientSession s, String line) {
        return s.submitLine(line, room::onLine);
    }

    private void onWritable(ClientSession s) {
        try {
            if (s.flush()) {
                s.key.interestOps(s.key.interestOps() & ~SelectionKey.OP_WRITE);
            }
        } catch (IOException e) {
            closeNow(s, "i/o error: " + e.getMessage());
        }
    }

    private void processWriteRequests() {
        ClientSession s;
        while ((s = writeRequests.poll()) != null) {
            if (s.isClosed()) continue;
            s.clearWriteScheduled();                 // before flushing: a send racing with us re-schedules itself
            try {
                if (!s.flush()) {                    // socket buffer full: wait for OP_WRITE
                    s.key.interestOps(s.key.interestOps() | SelectionKey.OP_WRITE);
                }
            } catch (IOException | CancelledKeyException e) {
                closeNow(s, "i/o error: " + e.getMessage());
            }
        }
    }

    private void processCloseRequests() {
        CloseRequest r;
        while ((r = closeRequests.poll()) != null) {
            closeNow(r.session(), r.reason());
        }
    }

    private void sweepTimeouts() {
        for (ClientSession s : sessions) {
            if (s.getPlayerId() == null && s.ageNanos() > TimeUnit.MILLISECONDS.toNanos(config.joinTimeoutMs)) {
                s.send(Protocol.join("ERROR", "JOIN_TIMEOUT", "Send JOIN within " + config.joinTimeoutMs / 1000 + "s"));
                closeNow(s, "join timeout");
            } else if (config.idleTimeoutMs > 0 && s.idleNanos() > TimeUnit.MILLISECONDS.toNanos(config.idleTimeoutMs)) {
                s.send(Protocol.join("ERROR", "IDLE_TIMEOUT", "No traffic for " + config.idleTimeoutMs / 1000 + "s"));
                closeNow(s, "idle timeout");
            }
        }
    }

    /** Closes a session. Selector thread only. Idempotent. */
    private void closeNow(ClientSession s, String reason) {
        if (!s.markClosed()) return;
        try {
            s.flush();                               // best effort: deliver the last ERROR/NOTICE
        } catch (IOException ignored) {
        }
        if (s.key != null) s.key.cancel();
        try {
            s.channel.close();
        } catch (IOException ignored) {
        }
        sessions.remove(s);
        log("closed " + s + " - " + reason);
        // Runs after every request this client already sent, so its last move is not lost.
        s.submitLast(() -> room.onDisconnect(s, reason));
    }

    private void shutdownSessions() {
        for (ClientSession s : sessions) {
            s.send(Protocol.join("NOTICE", "Server shutting down"));
            closeNow(s, "server shutdown");
        }
        try {
            serverChannel.close();
            selector.close();
        } catch (IOException ignored) {
        }
    }

    // ---- called from any thread ----

    void scheduleWrite(ClientSession s) {
        writeRequests.add(s);
        selector.wakeup();
    }

    void requestClose(ClientSession s, String reason) {
        closeRequests.add(new CloseRequest(s, reason));
        selector.wakeup();
    }

    void log(String msg) {
        if (config.verbose) {
            System.out.println(LocalTime.now().format(TIME) + " [" + Thread.currentThread().getName() + "] " + msg);
        }
    }

    // =====================================================================================
    //  Diagnostics
    // =====================================================================================

    public int sessionCount() {
        return sessions.size();
    }

    public long totalConnections() {
        return totalConnections.sum();
    }

    public int workerThreadsUsed() {
        return room.workerThreadsUsed();
    }

    public long turnTimeouts() {
        return room.turnTimeoutCount();
    }

    /** @return null when the room state is consistent, otherwise a description of the problem. */
    public String checkInvariants() {
        return room.checkInvariants();
    }

    public String statsLine() {
        return "sessions=" + sessions.size() + " (total accepted " + totalConnections.sum() + ")\n"
                + "workers: poolSize=" + workers.getPoolSize() + " active=" + workers.getActiveCount()
                + " queued=" + workers.getQueue().size() + " completed=" + workers.getCompletedTaskCount() + "\n"
                + room.statsLine();
    }

    // =====================================================================================
    //  Console + main
    // =====================================================================================

    private void consoleLoop() {
        System.out.println("Console commands: status | players | kick <id> | quit");
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = in.readLine()) != null) {
                String[] p = line.trim().split("\\s+");
                switch (p[0].toLowerCase()) {
                    case "status" -> System.out.println(statsLine());
                    case "players" -> System.out.println("players: " + room.playerIds());
                    case "kick" -> {
                        ClientSession s = p.length > 1 ? room.sessionOf(p[1]) : null;
                        if (s == null) System.out.println("no such player");
                        else s.close("kicked by admin");
                    }
                    case "quit", "exit" -> {
                        stop();
                        return;
                    }
                    case "" -> { }
                    default -> System.out.println("unknown command: " + p[0]);
                }
            }
        } catch (IOException ignored) {
        }
    }

    public static void main(String[] args) throws Exception {
        Config cfg = new Config();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> cfg.port = Integer.parseInt(args[++i]);
                case "--workers" -> cfg.workerThreads = Integer.parseInt(args[++i]);
                case "--max-players" -> cfg.maxPlayers = Integer.parseInt(args[++i]);
                case "--turn-seconds" -> cfg.turnTimeoutMs = Long.parseLong(args[++i]) * 1000;
                case "--idle-seconds" -> cfg.idleTimeoutMs = Long.parseLong(args[++i]) * 1000;
                case "--require-token" -> cfg.requireLobbyTokens();
                case "--quiet" -> cfg.verbose = false;
                default -> {
                    System.err.println("Usage: GameServer [--port N] [--workers N] [--max-players N]"
                            + " [--turn-seconds N] [--idle-seconds N] [--require-token] [--quiet]");
                    return;
                }
            }
        }
        GameServer server = new GameServer(cfg);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "shutdown-hook"));
        Thread console = new Thread(server::consoleLoop, "console");
        console.setDaemon(true);
        console.start();
        server.awaitStop();
    }

    // =====================================================================================

    /** Gives threads readable names ("worker-3") so logs and demos show WHO did the work. */
    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger();

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
