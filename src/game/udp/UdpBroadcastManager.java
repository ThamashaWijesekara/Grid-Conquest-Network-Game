package game.udp;

import game.security.PacketSecurity;
import game.security.SecurityConfig;
import game.security.TokenManager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class UdpBroadcastManager {
    private final int port;
    private final UdpEventListener listener;
    private final PacketSecurity packetSecurity;
    private final TokenManager tokenManager;
    private final java.util.Map<String, String> playerTokens = new java.util.concurrent.ConcurrentHashMap<>();
    private DatagramSocket socket;
    private volatile boolean running = true;

    public UdpBroadcastManager(int port, UdpEventListener listener) {
        this.port = port;
        this.listener = listener;
        this.tokenManager = new TokenManager(SecurityConfig.sharedSecret(), SecurityConfig.TOKEN_LIFETIME);
        this.packetSecurity = new PacketSecurity(tokenManager);
    }

    public UdpBroadcastManager(int port, UdpEventListener listener, String playerId, String token) {
        this(port, listener);
        TokenManager.TokenDetails details = tokenManager.verify(token);
        if (details == null || !details.playerId().equals(playerId)) {
            throw new IllegalArgumentException("The lobby token is invalid for this player");
        }
        playerTokens.put(playerId, token);
    }

    public void start() {
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new InetSocketAddress(port));

            Thread receiverThread = new Thread(this::listenLoop, "UDP-Receiver-Thread");
            receiverThread.setDaemon(true);
            receiverThread.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void sendJoin(String playerId) {
        sendPacket("JOIN|" + playerId + "|ONLINE");
    }

    public void sendHover(String playerId, int row, int col) {
        sendPacket("HOVER|" + playerId + "|" + row + "|" + col);
    }

    public void sendDiceRoll(String playerId, int diceValue) {
        sendPacket("DICE|" + playerId + "|" + diceValue);
    }

    public void sendCellClaim(String playerId, int row, int col) {
        sendPacket("CLAIM|" + playerId + "|" + row + "|" + col);
    }

    public void sendTurnDone(String finishedPlayerId, String nextPlayerId) {
        sendPacket("TURN_DONE|" + finishedPlayerId + "|" + nextPlayerId);
    }

    private void sendPacket(String message) {
        if (socket == null || socket.isClosed()) return;

        String[] fields = message.split("\\|", 3);
        if (fields.length < 2) return;
        String token = playerTokens.computeIfAbsent(fields[1], tokenManager::issue);
        String securedMessage = packetSecurity.protect(message, token);

        Thread sender = new Thread(() -> {
            byte[] data = securedMessage.getBytes(StandardCharsets.UTF_8);
            try {
                DatagramPacket localPacket = new DatagramPacket(
                        data, data.length, InetAddress.getLoopbackAddress(), port
                );
                socket.send(localPacket);
            } catch (Exception ignored) {
            }

            try {
                InetAddress broadcastAddr = InetAddress.getByName("255.255.255.255");
                DatagramPacket broadcastPacket = new DatagramPacket(
                        data, data.length, broadcastAddr, port
                );
                socket.send(broadcastPacket);
            } catch (Exception ignored) {
            }
        }, "UDP-Sender-Thread");
        sender.setDaemon(true);
        sender.start();
    }

    private void listenLoop() {
        byte[] buffer = new byte[1024];
        while (running && socket != null && !socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String secured = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                PacketSecurity.VerifiedPacket verified = packetSecurity.verify(secured);
                if (verified != null) {
                    handleMessage(verified.payload());
                } else {
                    System.err.println("Rejected unauthenticated or modified UDP packet");
                }
            } catch (Exception e) {
                if (running) e.printStackTrace();
            }
        }
    }

    private void handleMessage(String msg) {
        String[] parts = msg.split("\\|");
        if (parts.length < 3) return;

        String type = parts[0];
        String playerId = parts[1];
        if (!validPlayerId(playerId)) return;

        try {
            switch (type) {
                case "JOIN" -> {
                    if (parts.length == 3 && "ONLINE".equals(parts[2])) {
                        listener.onRemotePlayerJoin(playerId);
                    }
                }
                case "HOVER" -> {
                    if (parts.length == 4) {
                        int row = Integer.parseInt(parts[2]);
                        int col = Integer.parseInt(parts[3]);
                        if (validCell(row, col)) listener.onRemoteHover(playerId, row, col);
                    }
                }
                case "DICE" -> {
                    if (parts.length == 3) {
                        int value = Integer.parseInt(parts[2]);
                        if (value >= 1 && value <= 6) listener.onRemoteDiceRoll(playerId, value);
                    }
                }
                case "CLAIM" -> {
                    if (parts.length == 4) {
                        int row = Integer.parseInt(parts[2]);
                        int col = Integer.parseInt(parts[3]);
                        if (validCell(row, col)) listener.onRemoteCellClaim(playerId, row, col);
                    }
                }
                case "TURN_DONE" -> {
                    if (parts.length == 3 && validPlayerId(parts[2])) {
                        listener.onRemoteTurnDone(playerId, parts[2]);
                    }
                }
                default -> {
                }
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private static boolean validPlayerId(String playerId) {
        return playerId != null && playerId.matches("[A-Z0-9_]{2,16}");
    }

    private static boolean validCell(int row, int col) {
        return row >= 0 && row < 10 && col >= 0 && col < 10;
    }

    public void close() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
}
