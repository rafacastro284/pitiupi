package pitiupi.adedanha.game;

import java.util.Map;
import java.util.Set;

/**
 * Resultado de uma rodada.
 *
 * @param pointsByPlayer playerId → pontos por categoria (mesma ordem das categorias)
 * @param annulledKeys   chaves "playerId#indice" das respostas anuladas na votação
 */
public record RoundResult(Map<String, int[]> pointsByPlayer, Set<String> annulledKeys) {

    /** Soma dos pontos de um jogador na rodada. */
    public int totalOf(String playerId) {
        int[] points = pointsByPlayer.get(playerId);
        if (points == null) {
            return 0;
        }
        int sum = 0;
        for (int p : points) {
            sum += p;
        }
        return sum;
    }
}
