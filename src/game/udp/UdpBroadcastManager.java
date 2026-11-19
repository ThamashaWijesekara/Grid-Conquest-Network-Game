package game.udp;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class UdpBroadcastManager {
    private final int port;
    private final UdpEventListener listener;
    private DatagramSocket socket;
    private volatile boolean running = true;

    public UdpBroadcastManager(int port, UdpEventListener listener) {
        this.port = port;
        this.listener = listener;
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

        Thread sender = new Thread(() -> {
            byte[] data = message.getBytes(StandardCharsets.UTF_8);
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
                String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                handleMessage(msg);
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

        try {
            switch (type) {
                case "JOIN" -> listener.onRemotePlayerJoin(playerId);
                case "HOVER" -> {
                    if (parts.length == 4) {
                        listener.onRemoteHover(playerId, Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                    }
                }
                case "DICE" -> {
                    if (parts.length == 3) {
                        listener.onRemoteDiceRoll(playerId, Integer.parseInt(parts[2]));
                    }
                }
                case "CLAIM" -> {
                    if (parts.length == 4) {
                        listener.onRemoteCellClaim(playerId, Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                    }
                }
                case "TURN_DONE" -> listener.onRemoteTurnDone(playerId, parts[2]);
                default -> {
                }
            }
        } catch (NumberFormatException ignored) {
        }
    }

    public void close() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
}