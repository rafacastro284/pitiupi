package pitiupi.adedanha.net;

/** Respostas de um jogador; answers[i] responde a categoria i. Vai por TCP. */
public class AnswersMessage extends AdedanhaMessage {

    private static final long serialVersionUID = 1L;

    private String roundId;
    private String playerId;
    private String playerName;
    private String[] answers;

    public AnswersMessage() {
    }

    public AnswersMessage(String roundId, String playerId, String playerName, String[] answers) {
        this.roundId = roundId;
        this.playerId = playerId;
        this.playerName = playerName;
        this.answers = answers;
    }

    public String getRoundId() { return roundId; }
    public String getPlayerId() { return playerId; }
    public String getPlayerName() { return playerName; }
    public String[] getAnswers() { return answers; }
}
