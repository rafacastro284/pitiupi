package pitiupi.adedanha.control;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import pitiupi.adedanha.game.AdedanhaRules;
import pitiupi.adedanha.game.RoundResult;
import pitiupi.adedanha.net.AnswersMessage;
import pitiupi.adedanha.net.RoundStartMessage;
import pitiupi.adedanha.net.StopMessage;
import pitiupi.adedanha.net.VotesMessage;

/**
 * Estado da partida e regras de rede, sem Swing.
 *
 * <p>Fases: IDLE → PLAYING → COLLECTING → VOTING → RESULT → (nova rodada) PLAYING.</p>
 *
 * <ul>
 *   <li>PLAYING: campos abertos, esperando alguém dar STOP.</li>
 *   <li>COLLECTING: STOP recebido; mandamos as nossas respostas e esperamos as dos outros
 *       por {@code collectWindowMillis}.</li>
 *   <li>VOTING: cada um marca o que quer anular; termina quando todos votam ou no prazo.</li>
 *   <li>RESULT: placar calculado com {@link AdedanhaRules#score}.</li>
 * </ul>
 *
 * <p>Todos os métodos públicos são {@code synchronized}: mensagens da rede e cliques da
 * tela chegam em threads diferentes e mexem no mesmo estado.</p>
 */
public final class AdedanhaController {

    public enum Phase { IDLE, PLAYING, COLLECTING, VOTING, RESULT }

    /** Sem K, Q, W, X, Y, Z: quase não há palavras em português com elas. */
    static final String LETTERS = "ABCDEFGHIJLMNOPRSTUV";

    public static final String[] DEFAULT_CATEGORIES = {
            "Nome", "Animal", "Cor", "Fruta", "Objeto", "Cidade/Estado/País"
    };

    private final GameNetwork network;
    private final GameListener listener;
    private final Supplier<String> myName;
    private final String myId = UUID.randomUUID().toString();
    private final long collectWindowMillis;
    private final long voteTimeoutMillis;
    private final Random random = new Random();

    /** Envios em ordem e fora da thread de quem chamou (o TCP pode demorar segundos). */
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> daemon(r, "adedanha-envio"));
    /** Prazos: fim da coleta de respostas e fim da votação. */
    private final ScheduledExecutorService timers =
            Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "adedanha-prazo"));

    private Phase phase = Phase.IDLE;
    private RoundStartMessage round;
    private String[] draft = new String[0];
    private final Map<String, String[]> answers = new LinkedHashMap<>();
    private final Map<String, Set<String>> votes = new HashMap<>();
    private final Map<String, String> names = new HashMap<>();
    private final Map<String, Integer> totals = new LinkedHashMap<>();
    private ScheduledFuture<?> pendingTimer;

    public AdedanhaController(GameNetwork network, GameListener listener, Supplier<String> myName) {
        this(network, listener, myName, 3_000, 90_000);
    }

    public AdedanhaController(GameNetwork network, GameListener listener, Supplier<String> myName,
                              long collectWindowMillis, long voteTimeoutMillis) {
        this.network = network;
        this.listener = listener;
        this.myName = myName;
        this.collectWindowMillis = collectWindowMillis;
        this.voteTimeoutMillis = voteTimeoutMillis;
    }

    public String getMyId() {
        return myId;
    }

    public synchronized Phase getPhase() {
        return phase;
    }

    // ------------------------------------------------------------------ início da rodada

    /** Botão "Nova rodada". */
    public synchronized void startNewRound() {
        if (phase == Phase.PLAYING || phase == Phase.COLLECTING || phase == Phase.VOTING) {
            listener.onLog("Termine a rodada atual antes de começar outra.");
            return;
        }
        String letter = String.valueOf(LETTERS.charAt(random.nextInt(LETTERS.length())));
        RoundStartMessage msg = new RoundStartMessage(
                UUID.randomUUID().toString(), letter, DEFAULT_CATEGORIES.clone(),
                System.currentTimeMillis(), myId, myName.get());
        applyRoundStart(msg);
        send(() -> network.sendFast(msg));
    }

    public synchronized void receiveRoundStart(RoundStartMessage m) {
        if (round != null && round.getRoundId().equals(m.getRoundId())) {
            return; // repetida, ou a nossa própria voltando pelo multicast
        }
        boolean busy = phase == Phase.PLAYING || phase == Phase.COLLECTING;
        if (busy && !isEarlier(m, round)) {
            listener.onLog(m.getStarterName() + " também iniciou uma rodada; vale a que começou antes.");
            return;
        }
        if (phase == Phase.VOTING && isEarlier(m, round)) {
            return; // aviso atrasado de uma rodada mais velha
        }
        if (busy) {
            listener.onLog("Rodada de " + m.getStarterName() + " começou antes; trocando para ela.");
        }
        applyRoundStart(m);
    }

    /**
     * Desempate quando duas pessoas iniciam ao mesmo tempo: vence o menor instante, e no
     * empate o menor id. Todos comparam os mesmos dois valores, então todos escolhem igual.
     */
    static boolean isEarlier(RoundStartMessage a, RoundStartMessage b) {
        if (a.getStartedAtMillis() != b.getStartedAtMillis()) {
            return a.getStartedAtMillis() < b.getStartedAtMillis();
        }
        return a.getRoundId().compareTo(b.getRoundId()) < 0;
    }

    private void applyRoundStart(RoundStartMessage m) {
        cancelTimer();
        round = m;
        phase = Phase.PLAYING;
        answers.clear();
        votes.clear();
        draft = new String[m.getCategories().length];
        Arrays.fill(draft, "");
        names.put(m.getStarterId(), m.getStarterName());
        names.put(myId, myName.get());
        listener.onRoundStarted(m.getLetter(), m.getCategories(), m.getStarterName());
    }

    // ------------------------------------------------------------------ jogando

    /** A tela chama a cada tecla, para o controller sempre ter o texto atual. */
    public synchronized void setDraftAnswer(int categoryIndex, String text) {
        if (phase == Phase.PLAYING && categoryIndex >= 0 && categoryIndex < draft.length) {
            draft[categoryIndex] = text == null ? "" : text;
        }
    }

    /** Botão "STOP!". */
    public synchronized void stop() {
        if (phase != Phase.PLAYING) {
            return;
        }
        StopMessage msg = new StopMessage(round.getRoundId(), myId, myName.get());
        applyStop(myName.get());
        send(() -> network.sendFast(msg));
    }

    public synchronized void receiveStop(StopMessage m) {
        if (isCurrentRound(m.getRoundId()) && phase == Phase.PLAYING) {
            applyStop(m.getPlayerName());
        }
    }

    private void applyStop(String stopperName) {
        phase = Phase.COLLECTING;
        String[] mine = draft.clone();
        if (hasAnyAnswer(mine)) {
            answers.put(myId, mine);
        }
        names.put(myId, myName.get());
        listener.onRoundStopped(stopperName);

        AnswersMessage msg = new AnswersMessage(round.getRoundId(), myId, myName.get(), mine);
        send(() -> network.sendReliable(msg));

        String roundId = round.getRoundId();
        pendingTimer = timers.schedule(() -> startVoting(roundId), collectWindowMillis, TimeUnit.MILLISECONDS);
    }

    public synchronized void receiveAnswers(AnswersMessage m) {
        if (!isCurrentRound(m.getRoundId())) {
            return;
        }
        if (phase == Phase.PLAYING) {
            // O STOP vai por UDP e pode ter se perdido. Se já chegou resposta de alguém,
            // a rodada acabou: paramos também.
            applyStop(m.getPlayerName());
        }
        if (phase != Phase.COLLECTING) {
            listener.onLog("Respostas de " + m.getPlayerName() + " chegaram depois do prazo e ficaram de fora.");
            return;
        }
        if (hasAnyAnswer(m.getAnswers())) {
            answers.put(m.getPlayerId(), m.getAnswers());
        }
        names.put(m.getPlayerId(), m.getPlayerName());
    }

    /**
     * Quem não escreveu nada não entra como jogador: não pontua e ninguém espera o voto
     * dele. Sem isso, quem está com o plugin aberto mas não jogou travaria a votação.
     */
    static boolean hasAnyAnswer(String[] answers) {
        if (answers == null) {
            return false;
        }
        for (String a : answers) {
            if (a != null && !a.isBlank()) {
                return true;
            }
        }
        return false;
    }

    /** A tela usa para avisar que o voto de quem não respondeu não conta. */
    public synchronized boolean isPlayingThisRound() {
        return answers.containsKey(myId);
    }

    // ------------------------------------------------------------------ votação

    private synchronized void startVoting(String roundId) {
        if (!isCurrentRound(roundId) || phase != Phase.COLLECTING) {
            return;
        }
        phase = Phase.VOTING;
        listener.onVotingStarted(round.getLetter(), round.getCategories(), playerAnswersList());
        pendingTimer = timers.schedule(() -> closeVoting(roundId), voteTimeoutMillis, TimeUnit.MILLISECONDS);
        maybeFinish(); // votos de quem foi mais rápido podem ter chegado durante a coleta
    }

    /** Botão "Confirmar votos". keys = chaves de AdedanhaRules.answerKey que a pessoa quer anular. */
    public synchronized void submitVotes(Set<String> keys) {
        if (phase != Phase.VOTING || votes.containsKey(myId)) {
            return;
        }
        Set<String> mine = new HashSet<>(keys);
        votes.put(myId, mine);
        VotesMessage msg = new VotesMessage(round.getRoundId(), myId, myName.get(), mine.toArray(new String[0]));
        send(() -> network.sendReliable(msg));
        maybeFinish();
    }

    public synchronized void receiveVotes(VotesMessage m) {
        if (!isCurrentRound(m.getRoundId())) {
            return;
        }
        if (phase != Phase.COLLECTING && phase != Phase.VOTING) {
            return;
        }
        votes.put(m.getVoterId(), new HashSet<>(Arrays.asList(m.getAnnulledKeys())));
        names.put(m.getVoterId(), m.getVoterName());
        if (phase == Phase.VOTING) {
            maybeFinish();
        }
    }

    private void maybeFinish() {
        if (votes.keySet().containsAll(answers.keySet())) {
            finish();
        }
    }

    private synchronized void closeVoting(String roundId) {
        if (isCurrentRound(roundId) && phase == Phase.VOTING) {
            listener.onLog("Prazo da votação acabou; contando os votos que chegaram.");
            finish();
        }
    }

    private void finish() {
        cancelTimer();
        phase = Phase.RESULT;
        RoundResult result = AdedanhaRules.score(
                round.getLetter(), round.getCategories().length, answers, votes);
        for (String playerId : answers.keySet()) {
            totals.merge(playerId, result.totalOf(playerId), Integer::sum);
        }
        listener.onResult(round.getCategories(), playerAnswersList(), result,
                new LinkedHashMap<>(totals), new HashMap<>(names));
    }

    // ------------------------------------------------------------------ apoio

    private boolean isCurrentRound(String roundId) {
        return round != null && round.getRoundId().equals(roundId);
    }

    /** Ordem por nome, para a tabela ficar igual em todos os PCs. */
    private List<PlayerAnswers> playerAnswersList() {
        List<PlayerAnswers> list = new ArrayList<>();
        for (Map.Entry<String, String[]> e : answers.entrySet()) {
            list.add(new PlayerAnswers(e.getKey(), names.getOrDefault(e.getKey(), "?"), e.getValue()));
        }
        list.sort(Comparator.comparing(PlayerAnswers::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(PlayerAnswers::playerId));
        return list;
    }

    private void cancelTimer() {
        if (pendingTimer != null) {
            pendingTimer.cancel(false);
            pendingTimer = null;
        }
    }

    private void send(Runnable task) {
        sender.execute(() -> {
            try {
                task.run();
            } catch (RuntimeException ex) {
                listener.onLog("Falha ao enviar: " + ex.getMessage());
            }
        });
    }

    /** Para as threads. O plugin chama quando o programa fecha. */
    public void shutdown() {
        sender.shutdownNow();
        timers.shutdownNow();
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
