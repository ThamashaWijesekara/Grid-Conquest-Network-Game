package game.udp;

public interface UdpEventListener {
    void onRemotePlayerJoin(String playerId);
    void onRemoteHover(String playerId, int row, int col);
    void onRemoteDiceRoll(String playerId, int diceValue);
    void onRemoteCellClaim(String playerId, int row, int col);
    void onRemoteTurnDone(String finishedPlayerId, String nextPlayerId);
}