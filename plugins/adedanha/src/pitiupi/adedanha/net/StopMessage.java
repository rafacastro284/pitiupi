package pitiupi.adedanha.net;

/** Alguém gritou STOP. Vai por UDP multicast e unicast; repetidos são ignorados. */
public class StopMessage extends AdedanhaMessage {

    private static final long serialVersionUID = 1L;

    private String roundId;
    private String playerId;
    private String playerName;

    public StopMessage() {
    }

    public StopMessage(String roundId, String playerId, String playerName) {
        this.roundId = roundId;
        this.playerId = playerId;
        this.playerName = playerName;
    }

    public String getRoundId() { return roundId; }
    public String getPlayerId() { return playerId; }
    public String getPlayerName() { return playerName; }
}
