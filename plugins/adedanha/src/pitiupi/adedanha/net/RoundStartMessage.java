package pitiupi.adedanha.net;

/** Anuncia uma rodada nova: letra sorteada e categorias. Vai por UDP multicast e, para os IPs conhecidos, UDP unicast. */
public class RoundStartMessage extends AdedanhaMessage {

    private static final long serialVersionUID = 1L;

    private String roundId;
    private String letter;
    private String[] categories;
    private long startedAtMillis;
    private String starterId;
    private String starterName;

    public RoundStartMessage() {
    }

    public RoundStartMessage(String roundId, String letter, String[] categories,
                             long startedAtMillis, String starterId, String starterName) {
        this.roundId = roundId;
        this.letter = letter;
        this.categories = categories;
        this.startedAtMillis = startedAtMillis;
        this.starterId = starterId;
        this.starterName = starterName;
    }

    public String getRoundId() { return roundId; }
    public String getLetter() { return letter; }
    public String[] getCategories() { return categories; }
    public long getStartedAtMillis() { return startedAtMillis; }
    public String getStarterId() { return starterId; }
    public String getStarterName() { return starterName; }
}
