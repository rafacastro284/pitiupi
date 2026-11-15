package pitiupi.adedanha.net;

import pitiupi.models.Messages.Message;

/** Alguém gritou STOP. Vai por UDP multicast; repetidos são ignorados. */
public class StopMessage extends Message {

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
