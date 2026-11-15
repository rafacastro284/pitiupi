package pitiupi.adedanha.game;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Regras da adedanha, sem rede e sem tela.
 *
 * <p>Não existe anfitrião: cada PC chama {@link #score} com as mesmas respostas e os
 * mesmos votos e chega ao mesmo placar. Por isso nada aqui pode depender de relógio,
 * de ordem de chegada ou de número aleatório.</p>
 */
public final class AdedanhaRules {

    public static final int POINTS_UNIQUE = 10;
    public static final int POINTS_REPEATED = 5;

    private AdedanhaRules() {
    }

    /** Chave de uma resposta: "playerId#indiceCategoria". É o que vai na VotesMessage. */
    public static String answerKey(String playerId, int categoryIndex) {
        return playerId + "#" + categoryIndex;
    }

    /** "  Árvore  Grande " vira "arvore grande": sem acento, minúsculo, espaços simples. */
    public static String normalize(String word) {
        if (word == null) {
            return "";
        }
        String noAccents = Normalizer.normalize(word, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return noAccents.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** A palavra não está vazia e começa com a letra sorteada (ignorando acento). */
    public static boolean startsWithLetter(String word, String letter) {
        String w = normalize(word);
        String l = normalize(letter);
        return !w.isEmpty() && !l.isEmpty() && w.startsWith(l);
    }

    /**
     * A resposta é anulada se mais da metade de quem votou, fora o próprio autor, marcou.
     * Votos de quem não jogou a rodada não contam.
     */
    public static boolean isAnnulled(String authorId, int categoryIndex,
                                     Set<String> playerIds, Map<String, Set<String>> votesByVoter) {
        String key = answerKey(authorId, categoryIndex);
        int voters = 0;
        int against = 0;
        for (Map.Entry<String, Set<String>> entry : votesByVoter.entrySet()) {
            String voterId = entry.getKey();
            if (voterId.equals(authorId) || !playerIds.contains(voterId)) {
                continue;
            }
            voters++;
            if (entry.getValue().contains(key)) {
                against++;
            }
        }
        return voters > 0 && against * 2 > voters;
    }

    /**
     * Calcula os pontos da rodada.
     *
     * @param letter         letra sorteada
     * @param categoryCount  quantas categorias a rodada tem
     * @param answersByPlayer playerId → respostas (answers[i] é a categoria i)
     * @param votesByVoter   voterId → chaves que ele quer anular
     */
    public static RoundResult score(String letter, int categoryCount,
                                    Map<String, String[]> answersByPlayer,
                                    Map<String, Set<String>> votesByVoter) {
        Set<String> playerIds = answersByPlayer.keySet();
        Map<String, int[]> points = new HashMap<>();
        Set<String> annulled = new HashSet<>();
        for (String playerId : playerIds) {
            points.put(playerId, new int[categoryCount]);
        }

        for (int c = 0; c < categoryCount; c++) {
            // 1) Quem tem resposta válida nesta categoria, e a palavra normalizada.
            Map<String, String> valid = new HashMap<>();
            for (String playerId : playerIds) {
                String word = answerAt(answersByPlayer.get(playerId), c);
                if (!startsWithLetter(word, letter)) {
                    continue;
                }
                if (isAnnulled(playerId, c, playerIds, votesByVoter)) {
                    annulled.add(answerKey(playerId, c));
                    continue;
                }
                valid.put(playerId, normalize(word));
            }
            // 2) Quantas vezes cada palavra válida apareceu.
            Map<String, Integer> count = new HashMap<>();
            for (String word : valid.values()) {
                count.merge(word, 1, Integer::sum);
            }
            // 3) Única vale 10, repetida vale 5. Vazia, letra errada ou anulada fica 0.
            for (Map.Entry<String, String> entry : valid.entrySet()) {
                int pts = count.get(entry.getValue()) == 1 ? POINTS_UNIQUE : POINTS_REPEATED;
                points.get(entry.getKey())[c] = pts;
            }
        }
        return new RoundResult(points, annulled);
    }

    private static String answerAt(String[] answers, int index) {
        if (answers == null || index >= answers.length) {
            return "";
        }
        return answers[index];
    }
}
