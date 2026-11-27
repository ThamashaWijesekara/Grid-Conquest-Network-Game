package game.ui;

import game.model.Player;
import game.security.SecureLobbyClient;
import game.security.SecurityConfig;
import game.udp.UdpBroadcastManager;
import game.udp.UdpEventListener;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.*;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class InteractiveGridFrame extends JFrame implements UdpEventListener {
    private static final int GRID_SIZE = 10;
    private static final int TOTAL_CELLS = GRID_SIZE * GRID_SIZE;
    private static final int UDP_PORT = 9876;

    // Modern Arcade Theme Palette
    private static final Color BG_DARK = new Color(8, 12, 21);
    private static final Color PANEL_DARK = new Color(15, 23, 42);
    private static final Color CARD_DARK = new Color(24, 34, 56);
    private static final Color CELL_EMPTY = new Color(30, 41, 59);
    private static final Color CELL_BORDER = new Color(51, 65, 85);
    private static final Color ACCENT_GOLD = new Color(250, 204, 21);
    private static final Color ACCENT_GREEN = new Color(34, 197, 94);
    private static final Color ACCENT_CYAN = new Color(56, 189, 248);

    private final Player currentPlayer;
    private final Map<String, Player> playersMap = new LinkedHashMap<>();
    private final List<String> turnOrder = new ArrayList<>();
    private String activePlayerId;

    private final String[][] cellOwners = new String[GRID_SIZE][GRID_SIZE];
    private final GridTileButton[][] gridTiles = new GridTileButton[GRID_SIZE][GRID_SIZE];
    private int claimedCount = 0;

    private int lastClaimedRow = -1;
    private int lastClaimedCol = -1;

    private final JLabel turnBannerLabel = new JLabel("TURN: P1", SwingConstants.CENTER);
    private final JLabel diceIconLabel = new JLabel("🎲", SwingConstants.CENTER);
    private final JLabel diceNumberLabel = new JLabel("-", SwingConstants.CENTER);
    private final JLabel lastRollLabel = new JLabel("Last Roll: -", SwingConstants.CENTER);
    private final JLabel remainingLabel = new JLabel("Claims Left: 0", SwingConstants.CENTER);
    private final JLabel hoverInfoLabel = new JLabel("Hover over any tile", SwingConstants.CENTER);
    private final JLabel lastMoveLabel = new JLabel("Latest Capture: None", SwingConstants.CENTER);
    private final JLabel playersListLabel = new JLabel("", SwingConstants.LEFT);
    private final JLabel statusLabel = new JLabel("⚡ Arena ready! Roll the dice when it's your turn.", SwingConstants.LEFT);
    private final JProgressBar boardFillBar = new JProgressBar(0, TOTAL_CELLS);

    private final JButton rollButton = new JButton("🎲 ROLL DICE");
    private final JButton soundToggleButton = new JButton("🔊 SFX: ON");
    private final JButton resetBoardButton = new JButton("↺ RESET BOARD");

    private boolean soundEnabled = true;
    private int remainingClaims = 0;
    private boolean isRollingAnimation = false;

    private int lastHoverRow = -1;
    private int lastHoverCol = -1;
    private int lastRemoteHoverRow = -1;
    private int lastRemoteHoverCol = -1;

    private final UdpBroadcastManager udpManager;

    public InteractiveGridFrame(String playerId, Color playerColor) {
        this(playerId, playerColor, null);
    }

    public InteractiveGridFrame(String playerId, Color playerColor, String authToken) {
        String cleanId = (playerId == null || playerId.isBlank()) ? "P1" : playerId.trim().toUpperCase();
        Color cleanColor = (playerColor == null) ? new Color(239, 68, 68) : playerColor;

        this.currentPlayer = new Player(cleanId, cleanColor);
        ensurePlayerRegistered(this.currentPlayer.id, this.currentPlayer.color);

        this.activePlayerId = "P1";
        ensurePlayerRegistered("P1", new Color(239, 68, 68));
        ensurePlayerRegistered("P2", new Color(59, 130, 246));

        this.udpManager = authToken == null
                ? new UdpBroadcastManager(UDP_PORT, this)
                : new UdpBroadcastManager(UDP_PORT, this, cleanId, authToken);

        setTitle("GRID CONQUEST // " + this.currentPlayer.id + " — Multiplayer Grid Arena");
        setSize(1080, 740);
        setMinimumSize(new Dimension(920, 650));
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG_DARK);
        setLayout(new BorderLayout(14, 14));

        add(buildTopHeader(), BorderLayout.NORTH);
        add(buildGridArena(), BorderLayout.CENTER);
        add(buildSideHud(), BorderLayout.EAST);
        add(buildBottomStatusBar(), BorderLayout.SOUTH);

        udpManager.start();
        udpManager.sendJoin(this.currentPlayer.id);
        refreshUIState();
    }

    private synchronized void ensurePlayerRegistered(String id, Color color) {
        if (!playersMap.containsKey(id)) {
            playersMap.put(id, new Player(id, color));
            turnOrder.add(id);
            Collections.sort(turnOrder);
        }
        if (activePlayerId == null && !turnOrder.isEmpty()) {
            activePlayerId = turnOrder.get(0);
        }
    }

    private synchronized String getActivePlayerId() {
        if (activePlayerId != null && turnOrder.contains(activePlayerId)) {
            return activePlayerId;
        }
        return turnOrder.isEmpty() ? currentPlayer.id : turnOrder.get(0);
    }

    private synchronized String computeNextPlayerId(String currentId) {
        if (turnOrder.isEmpty()) return currentPlayer.id;
        int idx = turnOrder.indexOf(currentId);
        if (idx < 0) return turnOrder.get(0);
        return turnOrder.get((idx + 1) % turnOrder.size());
    }

    private synchronized boolean isMyTurn() {
        if (turnOrder.size() <= 1) return true;
        return currentPlayer.id.equals(getActivePlayerId());
    }

    private synchronized void setTurnTo(String nextPlayerId) {
        if (nextPlayerId != null && !nextPlayerId.isBlank()) {
            ensurePlayerRegistered(nextPlayerId, colorForPlayer(nextPlayerId));
            activePlayerId = nextPlayerId;
        }
        remainingClaims = 0;
    }

    private Color colorForPlayer(String playerId) {
        Player p = playersMap.get(playerId);
        if (p != null) return p.color;
        int hash = Math.abs(playerId.hashCode());
        return Color.getHSBColor((hash % 360) / 360f, 0.78f, 0.92f);
    }

    private JPanel buildTopHeader() {
        JPanel header = new JPanel(new BorderLayout(14, 0));
        header.setBackground(PANEL_DARK);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(36, 48, 72)),
                new EmptyBorder(12, 20, 12, 20)
        ));

        JPanel leftTitle = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        leftTitle.setOpaque(false);

        JLabel badge = new JLabel("UDP LIVE");
        badge.setOpaque(true);
        badge.setBackground(new Color(30, 58, 138));
        badge.setForeground(ACCENT_CYAN);
        badge.setFont(new Font("SansSerif", Font.BOLD, 11));
        badge.setBorder(new EmptyBorder(3, 8, 3, 8));

        JLabel title = new JLabel("GRID CONQUEST ARENA");
        title.setFont(new Font("SansSerif", Font.BOLD, 21));
        title.setForeground(Color.WHITE);

        leftTitle.add(title);
        leftTitle.add(badge);

        JPanel rightControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        rightControls.setOpaque(false);

        styleUtilityButton(soundToggleButton, CARD_DARK, Color.WHITE);
        soundToggleButton.addActionListener(e -> {
            soundEnabled = !soundEnabled;
            soundToggleButton.setText(soundEnabled ? "🔊 SFX: ON" : "🔇 SFX: OFF");
        });

        turnBannerLabel.setOpaque(true);
        turnBannerLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
        turnBannerLabel.setBorder(new EmptyBorder(7, 16, 7, 16));
        turnBannerLabel.setBackground(ACCENT_GREEN);
        turnBannerLabel.setForeground(BG_DARK);

        rightControls.add(soundToggleButton);
        rightControls.add(turnBannerLabel);

        header.add(leftTitle, BorderLayout.WEST);
        header.add(rightControls, BorderLayout.EAST);
        return header;
    }

    private JPanel buildGridArena() {
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(BG_DARK);
        outer.setBorder(new EmptyBorder(0, 18, 0, 6));

        JPanel board = new JPanel(new GridLayout(GRID_SIZE, GRID_SIZE, 6, 6));
        board.setBackground(PANEL_DARK);
        board.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(45, 60, 88), 2, true),
                new EmptyBorder(12, 12, 12, 12)
        ));

        for (int r = 0; r < GRID_SIZE; r++) {
            for (int c = 0; c < GRID_SIZE; c++) {
                final int row = r;
                final int col = c;

                GridTileButton tile = new GridTileButton();
                tile.setFont(new Font("SansSerif", Font.BOLD, 12));
                tile.setForeground(Color.WHITE);
                tile.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                tile.setTileFill(CELL_EMPTY);
                tile.setHighlightColor(CELL_BORDER, 1);

                tile.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseEntered(MouseEvent e) {
                        applyLocalHover(row, col);
                        udpManager.sendHover(currentPlayer.id, row, col);
                    }
                });

                tile.addActionListener(e -> handleCellClick(row, col));

                gridTiles[r][c] = tile;
                board.add(tile);
            }
        }

        outer.add(board, BorderLayout.CENTER);
        return outer;
    }

    private JPanel buildSideHud() {
        JPanel hud = new JPanel();
        hud.setLayout(new BoxLayout(hud, BoxLayout.Y_AXIS));
        hud.setPreferredSize(new Dimension(310, 0));
        hud.setBackground(PANEL_DARK);
        hud.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(36, 48, 72), 1, true),
                new EmptyBorder(16, 16, 16, 16)
        ));

        JLabel meBadge = new JLabel("PLAYER: " + currentPlayer.id, SwingConstants.CENTER);
        meBadge.setAlignmentX(Component.CENTER_ALIGNMENT);
        meBadge.setOpaque(true);
        meBadge.setBackground(currentPlayer.color);
        meBadge.setForeground(Color.WHITE);
        meBadge.setFont(new Font("SansSerif", Font.BOLD, 14));
        meBadge.setBorder(new EmptyBorder(6, 16, 6, 16));
        meBadge.setMaximumSize(new Dimension(270, 34));

        JPanel diceCard = new JPanel();
        diceCard.setLayout(new BoxLayout(diceCard, BoxLayout.Y_AXIS));
        diceCard.setAlignmentX(Component.CENTER_ALIGNMENT);
        diceCard.setBackground(CARD_DARK);
        diceCard.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(65, 83, 115), 1, true),
                new EmptyBorder(12, 12, 12, 12)
        ));
        diceCard.setMaximumSize(new Dimension(275, 150));

        diceIconLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        diceIconLabel.setFont(new Font("SansSerif", Font.PLAIN, 22));

        diceNumberLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        diceNumberLabel.setFont(new Font("SansSerif", Font.BOLD, 38));
        diceNumberLabel.setForeground(ACCENT_GOLD);

        lastRollLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        lastRollLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        lastRollLabel.setForeground(new Color(203, 213, 225));

        diceCard.add(diceIconLabel);
        diceCard.add(diceNumberLabel);
        diceCard.add(Box.createVerticalStrut(4));
        diceCard.add(lastRollLabel);

        remainingLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        remainingLabel.setFont(new Font("SansSerif", Font.BOLD, 15));
        remainingLabel.setForeground(ACCENT_CYAN);

        rollButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        rollButton.setFont(new Font("SansSerif", Font.BOLD, 15));
        rollButton.setBackground(ACCENT_GREEN);
        rollButton.setForeground(BG_DARK);
        rollButton.setFocusPainted(false);
        rollButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        rollButton.setMaximumSize(new Dimension(275, 46));
        rollButton.addActionListener(e -> triggerDiceRoll());

        hoverInfoLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        hoverInfoLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        hoverInfoLabel.setForeground(new Color(148, 163, 184));

        lastMoveLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        lastMoveLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        lastMoveLabel.setForeground(ACCENT_GOLD);

        JPanel rosterCard = new JPanel(new BorderLayout());
        rosterCard.setAlignmentX(Component.CENTER_ALIGNMENT);
        rosterCard.setBackground(CARD_DARK);
        rosterCard.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(65, 83, 115), 1, true),
                new EmptyBorder(10, 12, 10, 12)
        ));
        rosterCard.setMaximumSize(new Dimension(275, 175));

        JLabel rosterTitle = new JLabel("LEADERBOARD & TURNS");
        rosterTitle.setFont(new Font("SansSerif", Font.BOLD, 11));
        rosterTitle.setForeground(new Color(148, 163, 184));
        rosterTitle.setBorder(new EmptyBorder(0, 0, 6, 0));

        playersListLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
        playersListLabel.setForeground(Color.WHITE);

        rosterCard.add(rosterTitle, BorderLayout.NORTH);
        rosterCard.add(playersListLabel, BorderLayout.CENTER);

        styleUtilityButton(resetBoardButton, new Color(51, 65, 85), new Color(226, 232, 240));
        resetBoardButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        resetBoardButton.setMaximumSize(new Dimension(275, 34));
        resetBoardButton.addActionListener(e -> resetLocalBoard());

        hud.add(meBadge);
        hud.add(Box.createVerticalStrut(10));
        hud.add(diceCard);
        hud.add(Box.createVerticalStrut(8));
        hud.add(remainingLabel);
        hud.add(Box.createVerticalStrut(10));
        hud.add(rollButton);
        hud.add(Box.createVerticalStrut(8));
        hud.add(hoverInfoLabel);
        hud.add(Box.createVerticalStrut(4));
        hud.add(lastMoveLabel);
        hud.add(Box.createVerticalStrut(10));
        hud.add(rosterCard);
        hud.add(Box.createVerticalStrut(10));
        hud.add(resetBoardButton);

        return hud;
    }

    private void styleUtilityButton(JButton btn, Color bg, Color fg) {
        btn.setFont(new Font("SansSerif", Font.BOLD, 12));
        btn.setBackground(bg);
        btn.setForeground(fg);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(71, 85, 105), 1, true),
                new EmptyBorder(5, 10, 5, 10)
        ));
    }

    private JPanel buildBottomStatusBar() {
        JPanel bottom = new JPanel(new BorderLayout(14, 0));
        bottom.setBackground(PANEL_DARK);
        bottom.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(36, 48, 72)),
                new EmptyBorder(10, 20, 12, 20)
        ));

        statusLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
        statusLabel.setForeground(new Color(226, 232, 240));

        boardFillBar.setPreferredSize(new Dimension(250, 20));
        boardFillBar.setStringPainted(true);
        boardFillBar.setBackground(CARD_DARK);
        boardFillBar.setForeground(ACCENT_CYAN);
        boardFillBar.setBorder(new LineBorder(new Color(65, 83, 115), 1, true));
        boardFillBar.setValue(0);
        boardFillBar.setString("Territory Captured: 0 / " + TOTAL_CELLS);

        bottom.add(statusLabel, BorderLayout.CENTER);
        bottom.add(boardFillBar, BorderLayout.EAST);
        return bottom;
    }

    private void triggerDiceRoll() {
        if (isRollingAnimation) return;

        if (!isMyTurn()) {
            playTone(220, 90);
            statusLabel.setText("⏳ Not your turn! Active turn is " + getActivePlayerId() + ".");
            return;
        }

        if (remainingClaims > 0) {
            playTone(260, 90);
            statusLabel.setText("🎯 Capture your remaining " + remainingClaims + " tile(s) first!");
            return;
        }

        if (claimedCount >= TOTAL_CELLS) {
            announceWinner();
            return;
        }

        isRollingAnimation = true;
        rollButton.setEnabled(false);
        playTone(520, 70);

        final int finalRoll = ThreadLocalRandom.current().nextInt(1, 7);
        final int[] ticks = {0};

        javax.swing.Timer animTimer = new javax.swing.Timer(65, evt -> {
            ticks[0]++;
            int preview = ThreadLocalRandom.current().nextInt(1, 7);
            diceNumberLabel.setText(String.valueOf(preview));
            playTone(400 + preview * 45, 25);

            if (ticks[0] >= 8) {
                ((javax.swing.Timer) evt.getSource()).stop();
                isRollingAnimation = false;

                remainingClaims = finalRoll;
                diceNumberLabel.setText(String.valueOf(finalRoll));
                lastRollLabel.setText("Last Roll: " + currentPlayer.id + " rolled " + finalRoll);
                statusLabel.setText("🎲 " + currentPlayer.id + " rolled " + finalRoll + "! Select " + finalRoll + " free tile(s).");
                playTone(760, 120);

                udpManager.sendDiceRoll(currentPlayer.id, finalRoll);
                refreshUIState();
            }
        });
        animTimer.start();
    }

    private void handleCellClick(int row, int col) {
        if (isRollingAnimation) return;

        if (!isMyTurn()) {
            playTone(220, 90);
            statusLabel.setText("⏳ Wait for your turn! Currently " + getActivePlayerId() + "'s turn.");
            return;
        }

        if (remainingClaims <= 0) {
            playTone(240, 90);
            statusLabel.setText("🎲 Roll the dice first to unlock tile claims!");
            return;
        }

        String owner = cellOwners[row][col];
        if (owner != null) {
            playTone(190, 110);
            statusLabel.setText("🔒 Tile (" + row + "," + col + ") is already claimed by " + owner + "!");
            return;
        }

        applyCellClaim(currentPlayer.id, row, col);
        udpManager.sendCellClaim(currentPlayer.id, row, col);
        playTone(640, 85);

        remainingClaims--;

        if (claimedCount >= TOTAL_CELLS) {
            announceWinner();
            return;
        }

        if (remainingClaims == 0) {
            String nextPlayer = computeNextPlayerId(currentPlayer.id);
            setTurnTo(nextPlayer);
            udpManager.sendTurnDone(currentPlayer.id, nextPlayer);
            statusLabel.setText("✅ Turn complete! Passing turn to " + nextPlayer + ".");
        } else {
            statusLabel.setText("🔥 Claimed (" + row + "," + col + ")! " + remainingClaims + " more tile(s) to claim.");
        }

        refreshUIState();
    }

    private synchronized void applyCellClaim(String playerId, int row, int col) {
        if (cellOwners[row][col] != null) return;

        ensurePlayerRegistered(playerId, colorForPlayer(playerId));
        cellOwners[row][col] = playerId;
        claimedCount++;

        // Clear previous latest marker if present
        if (lastClaimedRow != -1 && lastClaimedCol != -1) {
            String prevOwner = cellOwners[lastClaimedRow][lastClaimedCol];
            if (prevOwner != null) {
                gridTiles[lastClaimedRow][lastClaimedCol].setText(prevOwner);
                gridTiles[lastClaimedRow][lastClaimedCol].setHighlightColor(Color.WHITE, 2);
            }
        }

        lastClaimedRow = row;
        lastClaimedCol = col;
        lastMoveLabel.setText("Latest Capture: " + playerId + " @ (" + row + ", " + col + ")");

        Player p = playersMap.get(playerId);
        if (p != null) {
            p.score++;
        }

        Color c = colorForPlayer(playerId);
        GridTileButton tile = gridTiles[row][col];
        tile.setTileFill(c);
        tile.setText("★ " + playerId);
        tile.setHighlightColor(ACCENT_GOLD, 2);

        boardFillBar.setValue(claimedCount);
        boardFillBar.setString("Territory Captured: " + claimedCount + " / " + TOTAL_CELLS);
        refreshRosterBoard();
    }

    private synchronized void resetLocalBoard() {
        claimedCount = 0;
        remainingClaims = 0;
        lastClaimedRow = -1;
        lastClaimedCol = -1;
        activePlayerId = turnOrder.isEmpty() ? currentPlayer.id : turnOrder.get(0);
        diceNumberLabel.setText("-");
        lastRollLabel.setText("Last Roll: -");
        lastMoveLabel.setText("Latest Capture: None");

        for (Player p : playersMap.values()) {
            p.score = 0;
        }
        for (int r = 0; r < GRID_SIZE; r++) {
            for (int c = 0; c < GRID_SIZE; c++) {
                cellOwners[r][c] = null;
                gridTiles[r][c].setText("");
                gridTiles[r][c].setTileFill(CELL_EMPTY);
                gridTiles[r][c].setHighlightColor(CELL_BORDER, 1);
            }
        }
        boardFillBar.setValue(0);
        boardFillBar.setString("Territory Captured: 0 / " + TOTAL_CELLS);
        statusLabel.setText("↺ Board reset! Active turn: " + getActivePlayerId());
        refreshUIState();
    }

    private void refreshRosterBoard() {
        String activeId = getActivePlayerId();
        StringBuilder sb = new StringBuilder("<html>");
        for (String id : turnOrder) {
            Player p = playersMap.get(id);
            int sc = p != null ? p.score : 0;
            String hex = String.format("#%02x%02x%02x",
                    colorForPlayer(id).getRed(),
                    colorForPlayer(id).getGreen(),
                    colorForPlayer(id).getBlue());
            String turnTag = id.equals(activeId) ? " <span style='color:#FACC15;'>◀ TURN</span>" : "";
            String youTag = id.equals(currentPlayer.id) ? " <span style='color:#38BDF8;'>(You)</span>" : "";

            sb.append("<div style='margin-bottom:4px;'>")
                    .append("<span style='color:").append(hex).append(";'>■</span> ")
                    .append("<b>").append(id).append("</b>").append(youTag)
                    .append(" — <b>").append(sc).append("</b> pts")
                    .append(turnTag)
                    .append("</div>");
        }
        sb.append("</html>");
        playersListLabel.setText(sb.toString());
    }

    private void refreshUIState() {
        String activeId = getActivePlayerId();
        boolean myTurn = isMyTurn();

        turnBannerLabel.setText(myTurn ? "⭐ YOUR TURN (" + currentPlayer.id + ")" : "⏳ TURN: " + activeId);
        turnBannerLabel.setBackground(myTurn ? ACCENT_GREEN : ACCENT_GOLD);
        turnBannerLabel.setForeground(BG_DARK);

        remainingLabel.setText("Claims Left: " + remainingClaims);
        rollButton.setEnabled(!isRollingAnimation && myTurn && remainingClaims == 0 && claimedCount < TOTAL_CELLS);

        refreshRosterBoard();
    }

    private void announceWinner() {
        Player winner = null;
        for (Player p : playersMap.values()) {
            if (winner == null || p.score > winner.score) {
                winner = p;
            }
        }
        String msg = winner != null
                ? "🏆 Match Complete! Champion: " + winner.id + " (" + winner.score + " tiles)"
                : "🏆 Match Complete!";
        statusLabel.setText(msg);
        rollButton.setEnabled(false);
        playTone(880, 220);
        JOptionPane.showMessageDialog(this, msg, "Victory!", JOptionPane.INFORMATION_MESSAGE);
    }

    private void applyLocalHover(int row, int col) {
        SwingUtilities.invokeLater(() -> {
            if (lastHoverRow != -1 && lastHoverCol != -1) {
                resetTileHighlight(lastHoverRow, lastHoverCol);
            }
            if (cellOwners[row][col] == null) {
                gridTiles[row][col].setHighlightColor(ACCENT_GOLD, 2);
                hoverInfoLabel.setText("Hovering (" + row + ", " + col + ") • Free");
            } else {
                hoverInfoLabel.setText("Tile (" + row + ", " + col + ") • Owned by " + cellOwners[row][col]);
            }
            lastHoverRow = row;
            lastHoverCol = col;
        });
    }

    private void resetTileHighlight(int row, int col) {
        if (row == lastClaimedRow && col == lastClaimedCol && cellOwners[row][col] != null) {
            gridTiles[row][col].setHighlightColor(ACCENT_GOLD, 2);
        } else if (cellOwners[row][col] != null) {
            gridTiles[row][col].setHighlightColor(Color.WHITE, 2);
        } else {
            gridTiles[row][col].setHighlightColor(CELL_BORDER, 1);
        }
    }

    private void playTone(int hz, int durationMs) {
        if (!soundEnabled) return;
        Thread sfx = new Thread(() -> {
            try {
                float sampleRate = 8000f;
                int count = (int) (sampleRate * durationMs / 1000.0);
                byte[] buf = new byte[count];
                for (int i = 0; i < count; i++) {
                    double angle = i / (sampleRate / hz) * 2.0 * Math.PI;
                    buf[i] = (byte) (Math.sin(angle) * 45.0);
                }
                AudioFormat af = new AudioFormat(sampleRate, 8, 1, true, false);
                try (SourceDataLine sdl = AudioSystem.getSourceDataLine(af)) {
                    sdl.open(af);
                    sdl.start();
                    sdl.write(buf, 0, count);
                    sdl.drain();
                }
            } catch (Exception ignored) {
            }
        }, "SFX-Thread");
        sfx.setDaemon(true);
        sfx.start();
    }

    @Override
    public void onRemotePlayerJoin(String playerId) {
        SwingUtilities.invokeLater(() -> {
            ensurePlayerRegistered(playerId, colorForPlayer(playerId));
            refreshUIState();
        });
    }

    @Override
    public void onRemoteHover(String playerId, int row, int col) {
        if (playerId.equals(currentPlayer.id)) return;
        SwingUtilities.invokeLater(() -> {
            if (lastRemoteHoverRow != -1 && lastRemoteHoverCol != -1) {
                resetTileHighlight(lastRemoteHoverRow, lastRemoteHoverCol);
            }
            if (cellOwners[row][col] == null) {
                gridTiles[row][col].setHighlightColor(colorForPlayer(playerId), 2);
            }
            lastRemoteHoverRow = row;
            lastRemoteHoverCol = col;
            hoverInfoLabel.setText(playerId + " at (" + row + ", " + col + ")");
        });
    }

    @Override
    public void onRemoteDiceRoll(String playerId, int diceValue) {
        if (playerId.equals(currentPlayer.id)) return;
        SwingUtilities.invokeLater(() -> {
            diceNumberLabel.setText(String.valueOf(diceValue));
            lastRollLabel.setText("Last Roll: " + playerId + " rolled " + diceValue);
            statusLabel.setText("🎲 " + playerId + " rolled " + diceValue + "!");
        });
    }

    @Override
    public void onRemoteCellClaim(String playerId, int row, int col) {
        if (playerId.equals(currentPlayer.id)) return;
        SwingUtilities.invokeLater(() -> {
            applyCellClaim(playerId, row, col);
            statusLabel.setText("⚡ " + playerId + " captured (" + row + ", " + col + ")");
        });
    }

    @Override
    public void onRemoteTurnDone(String finishedPlayerId, String nextPlayerId) {
        if (finishedPlayerId.equals(currentPlayer.id)) return;
        SwingUtilities.invokeLater(() -> {
            setTurnTo(nextPlayerId);
            refreshUIState();
            statusLabel.setText("🔄 Turn passed to " + getActivePlayerId() + "!");
        });
    }

    private static class GridTileButton extends JButton {
        private Color tileFill = CELL_EMPTY;
        private Color borderCol = CELL_BORDER;
        private int borderThickness = 1;

        public GridTileButton() {
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setOpaque(false);
        }

        public void setTileFill(Color fill) {
            this.tileFill = fill;
            repaint();
        }

        public void setHighlightColor(Color color, int thickness) {
            this.borderCol = color;
            this.borderThickness = thickness;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            int pad = 1;

            g2.setColor(tileFill);
            g2.fill(new RoundRectangle2D.Float(pad, pad, w - pad * 2, h - pad * 2, 10, 10));

            g2.setColor(borderCol);
            g2.setStroke(new BasicStroke(borderThickness));
            g2.draw(new RoundRectangle2D.Float(
                    pad + borderThickness / 2f,
                    pad + borderThickness / 2f,
                    w - (pad + borderThickness),
                    h - (pad + borderThickness),
                    10, 10
            ));

            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static Color parseColorArg(String arg) {
        if (arg == null) return new Color(239, 68, 68);
        return switch (arg.trim().toUpperCase()) {
            case "BLUE" -> new Color(59, 130, 246);
            case "GREEN" -> new Color(34, 197, 94);
            case "PURPLE" -> new Color(168, 85, 247);
            case "ORANGE" -> new Color(249, 115, 22);
            default -> new Color(239, 68, 68);
        };
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            String playerId;
            Color color;

            if (args.length >= 1 && !args[0].isBlank()) {
                playerId = args[0].trim().toUpperCase();
                color = args.length >= 2 ? parseColorArg(args[1]) : new Color(239, 68, 68);
            } else {
                String[] idChoices = {"P1", "P2", "P3", "P4"};
                String chosenId = (String) JOptionPane.showInputDialog(
                        null,
                        "Select or enter Player ID:",
                        "Join Grid Conquest Arena",
                        JOptionPane.PLAIN_MESSAGE,
                        null,
                        idChoices,
                        "P1"
                );
                if (chosenId == null) return;
                playerId = chosenId.trim().isEmpty() ? "P1" : chosenId.trim().toUpperCase();

                String[] colorChoices = {"RED", "BLUE", "GREEN", "PURPLE", "ORANGE"};
                String chosenColor = (String) JOptionPane.showInputDialog(
                        null,
                        "Select Player Color:",
                        "Player Color",
                        JOptionPane.PLAIN_MESSAGE,
                        null,
                        colorChoices,
                        "RED"
                );
                color = parseColorArg(chosenColor);
            }

            JPasswordField passwordField = new JPasswordField();
            int result = JOptionPane.showConfirmDialog(
                    null, passwordField, "Password for " + playerId,
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
            );
            if (result != JOptionPane.OK_OPTION) return;

            char[] password = passwordField.getPassword();
            try {
                SecureLobbyClient lobby = new SecureLobbyClient("localhost", SecurityConfig.LOBBY_PORT);
                String response = lobby.login(playerId, password);
                if (response == null || !response.startsWith("AUTH_OK|")) {
                    JOptionPane.showMessageDialog(null, "Login failed: " + response,
                            "Secure Lobby", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                String token = response.substring("AUTH_OK|".length());
                new InteractiveGridFrame(playerId, color, token).setVisible(true);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null,
                        "Cannot reach the secure lobby: " + e.getMessage(),
                        "Secure Lobby", JOptionPane.ERROR_MESSAGE);
            } finally {
                java.util.Arrays.fill(password, '\0');
            }
        });
    }
}
