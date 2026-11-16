package pitiupi.adedanha.net;

/** Votos de anulação de um jogador. Cada item é "playerId#indiceCategoria". Vai por TCP. */
public class VotesMessage extends AdedanhaMessage {

    private static final long serialVersionUID = 1L;

    private String roundId;
    private String voterId;
    private String voterName;
    private String[] annulledKeys;

    public VotesMessage() {
    }

    public VotesMessage(String roundId, String voterId, String voterName, String[] annulledKeys) {
        this.roundId = roundId;
        this.voterId = voterId;
        this.voterName = voterName;
        this.annulledKeys = annulledKeys;
    }

    public String getRoundId() { return roundId; }
    public String getVoterId() { return voterId; }
    public String getVoterName() { return voterName; }
    public String[] getAnnulledKeys() { return annulledKeys; }
}
