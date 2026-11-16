package pitiupi.adedanha.control;

/** As respostas de um jogador numa rodada, para a tela mostrar. */
public record PlayerAnswers(String playerId, String name, String[] answers) {
}
