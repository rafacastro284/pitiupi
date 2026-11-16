package pitiupi.adedanha.control;

import java.util.List;
import java.util.Map;
import pitiupi.adedanha.game.RoundResult;

/**
 * O que a tela recebe do controller. Os métodos podem ser chamados fora da thread do
 * Swing: quem implementar usa SwingUtilities.invokeLater antes de mexer em componente.
 */
public interface GameListener {

    /** Rodada nova: mostrar a letra e liberar os campos. */
    void onRoundStarted(String letter, String[] categories, String starterName);

    /** Alguém deu STOP: travar os campos. As respostas já estão sendo enviadas. */
    void onRoundStopped(String stopperName);

    /** Todas as respostas chegaram (ou acabou o prazo): mostrar a tabela de votação. */
    void onVotingStarted(String letter, String[] categories, List<PlayerAnswers> answers);

    /**
     * Votação encerrada.
     *
     * @param totals placar acumulado da sessão: playerId → pontos
     * @param names  playerId → nome
     */
    void onResult(String[] categories, List<PlayerAnswers> answers, RoundResult result,
                  Map<String, Integer> totals, Map<String, String> names);

    /** Linha de texto para o histórico (quem entrou, mensagem ignorada etc.). */
    void onLog(String text);
}
