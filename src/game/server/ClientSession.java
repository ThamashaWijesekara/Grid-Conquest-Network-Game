package game.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Server-side state of ONE connected client.
 *
 * Two thread-safety ideas live here:
 *
 * 1. INBOUND  - "serial executor per client". The selector thread frames lines and drops them
 *    into this session's inbox; at most one worker drains the inbox at any moment. So the
 *    lines of ONE client are always handled in the order they were sent (ROLL before CLAIM),
 *    while lines of DIFFERENT clients are handled in parallel by the worker pool.
 *
 * 2. OUTBOUND - "single writer". Any thread may call {@link #send(String)}; it only appends to
 *    a lock-free queue. Only the selector thread ever writes to the channel, so a slow client
 *    can never block the thread that is broadcasting to everybody else.
 */
final class ClientSession {
    /** Lines waiting for a worker. Exceeding this means the client is flooding the server. */
    private static final int MAX_PENDING_LINES = 4096;
    /** Lines one worker handles before yielding its thread so other clients get a turn. */
    private static final int DRAIN_BATCH = 16;
    /** Bytes we are willing to queue for a client that is not reading. */
    private static final int MAX_OUTBOUND_BYTES = 256 * 1024;

    private static final AtomicLong SEQUENCE = new AtomicLong();

    final long number = SEQUENCE.incrementAndGet();
    final SocketChannel channel;
    final String remote;
    final long connectedAtNanos = System.nanoTime();
    volatile SelectionKey key;

    private final GameServer server;
    private final Executor pool;

    private volatile String playerId;
    private volatile long lastActivityNanos = System.nanoTime();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean closeRequested = new AtomicBoolean();

    // ---- inbound framing (selector thread only) ----
    private final byte[] lineBuf = new byte[Protocol.MAX_LINE_BYTES];
    private int lineLen;

    // ---- inbound serial execution ----
    private final Queue<Runnable> inbox = new ArrayDeque<>();   // guarded by synchronized (inbox)
    private boolean draining;                                   // guarded by synchronized (inbox)

    // ---- outbound ----
    private final ConcurrentLinkedQueue<ByteBuffer> outbox = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingBytes = new AtomicInteger();
    private final AtomicBoolean writeScheduled = new AtomicBoolean();

    ClientSession(SocketChannel channel, GameServer server, Executor pool) {
        this.channel = channel;
        this.server = server;
        this.pool = pool;
        String addr;
        try {
            addr = String.valueOf(channel.getRemoteAddress());
        } catch (IOException e) {
            addr = "unknown";
        }
        this.remote = addr;
    }

    // ------------------------------------------------------------------ identity / lifecycle

    String getPlayerId() {
        return playerId;
    }

    void setPlayerId(String id) {
        this.playerId = id;
    }

    boolean isClosed() {
        return closed.get();
    }

    /** @return true only for the caller that actually flipped the flag. */
    boolean markClosed() {
        return closed.compareAndSet(false, true);
    }

    void touch() {
        lastActivityNanos = System.nanoTime();
    }

    long idleNanos() {
        return System.nanoTime() - lastActivityNanos;
    }

    long ageNanos() {
        return System.nanoTime() - connectedAtNanos;
    }

    /** Asks the selector thread to close this session. Safe to call from any thread, any number of times. */
    void close(String reason) {
        if (closeRequested.compareAndSet(false, true)) {
            server.requestClose(this, reason);
        }
    }

    // ------------------------------------------------------------------ inbound

    /**
     * Splits raw bytes into lines. Selector thread only.
     *
     * @return false if the peer sent an over-long line or flooded us (caller must drop the client)
     */
    boolean frame(ByteBuffer in, Predicate<String> lineSink) {
        while (in.hasRemaining()) {
            byte b = in.get();
            if (b == '\n') {
                int len = lineLen;
                if (len > 0 && lineBuf[len - 1] == '\r') len--;
                lineLen = 0;
                if (len > 0 && !lineSink.test(new String(lineBuf, 0, len, StandardCharsets.UTF_8))) {
                    return false;
                }
            } else {
                if (lineLen == lineBuf.length) return false;
                lineBuf[lineLen++] = b;
            }
        }
        return true;
    }

    /** Queues one line for the worker pool, preserving per-client order. */
    boolean submitLine(String line, BiConsumer<ClientSession, String> handler) {
        return submit(() -> handler.accept(this, line), true);
    }

    /** Queues a task that must run AFTER every line already queued (used for disconnect clean-up). */
    void submitLast(Runnable task) {
        if (!submit(task, false)) {
            task.run();   // pool already shut down: run inline
        }
    }

    private boolean submit(Runnable task, boolean enforceLimit) {
        synchronized (inbox) {
            if (enforceLimit && inbox.size() >= MAX_PENDING_LINES) return false;
            inbox.add(task);
            if (draining) return true;      // the worker that is draining will pick this up
            draining = true;
        }
        try {
            pool.execute(this::drain);
            return true;
        } catch (RejectedExecutionException e) {
            synchronized (inbox) {
                inbox.clear();
                draining = false;
            }
            return false;
        }
    }

    private void drain() {
        for (int i = 0; i < DRAIN_BATCH; i++) {
            Runnable task;
            synchronized (inbox) {
                task = inbox.poll();
                if (task == null) {
                    draining = false;
                    return;
                }
            }
            try {
                task.run();
            } catch (Throwable t) {
                server.log("handler failure for " + this + ": " + t);
            }
        }
        // Batch used up: give the thread back so other clients are not starved, then continue.
        synchronized (inbox) {
            if (inbox.isEmpty()) {
                draining = false;
                return;
            }
        }
        try {
            pool.execute(this::drain);
        } catch (RejectedExecutionException e) {
            synchronized (inbox) {
                inbox.clear();
                draining = false;
            }
        }
    }

    // ------------------------------------------------------------------ outbound

    /** Queues one line for this client. Never blocks, never throws. Safe from any thread. */
    boolean send(String line) {
        if (closed.get()) return false;
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        if (pendingBytes.addAndGet(bytes.length) > MAX_OUTBOUND_BYTES) {
            close("slow consumer (outbound queue overflow)");
            return false;
        }
        outbox.add(ByteBuffer.wrap(bytes));
        if (writeScheduled.compareAndSet(false, true)) {
            server.scheduleWrite(this);
        }
        return true;
    }

    void clearWriteScheduled() {
        writeScheduled.set(false);
    }

    /**
     * Writes as much queued data as the socket accepts. Selector thread only.
     *
     * @return true if everything was written, false if the socket buffer is full
     */
    boolean flush() throws IOException {
        ByteBuffer buf;
        while ((buf = outbox.peek()) != null) {
            int written = channel.write(buf);
            if (written > 0) pendingBytes.addAndGet(-written);
            if (buf.hasRemaining()) return false;
            outbox.poll();
        }
        return true;
    }

    @Override
    public String toString() {
        String id = playerId;
        return "session#" + number + "[" + (id == null ? "unjoined" : id) + "@" + remote + "]";
    }
}
