package pitiupi.adedanha.GUI;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableModel;
import pitiupi.adedanha.control.AdedanhaController;
import pitiupi.adedanha.control.GameListener;
import pitiupi.adedanha.control.PlayerAnswers;
import pitiupi.adedanha.game.AdedanhaRules;
import pitiupi.adedanha.game.RoundResult;

/**
 * Tela da adedanha: três cartões (jogar, votar, resultado) e um histórico embaixo.
 *
 * <p>Implementa {@link GameListener}: o controller avisa o que mudou e a tela só desenha.
 * Esses avisos podem vir da thread da rede, por isso cada método passa por
 * {@link SwingUtilities#invokeLater} antes de mexer em componente.</p>
 */
public class AdedanhaPanel extends JPanel implements GameListener {

    private static final String CARD_PLAY = "play";
    private static final String CARD_VOTE = "vote";
    private static final String CARD_RESULT = "result";

    private AdedanhaController controller;
    private Function<String, String> onAddPlayerIp;

    private final JTextField ipField = new JTextField(14);
    private final JLabel knownPlayersLabel = new JLabel("Por IP: ninguém ainda");

    private final CardLayout cards = new CardLayout();
    private final JPanel cardHost = new JPanel(cards);
    private final JLabel status = new JLabel();
    private final JTextArea log = new JTextArea(4, 40);

    // Cartão "jogar"
    private final JLabel letterLabel = new JLabel("–", SwingConstants.CENTER);
    private final JPanel fieldsPanel = new JPanel(new GridBagLayout());
    private final List<JTextField> fields = new ArrayList<>();
    private final JButton newRoundButton = new JButton("Nova rodada");
    private final JButton stopButton = new JButton("STOP!");

    // Cartão "votar"
    private final VoteTableModel voteModel = new VoteTableModel();
    private final JButton confirmVotesButton = new JButton("Confirmar votos");
    private final JLabel voteHint = new JLabel();

    // Cartão "resultado"
    private final DefaultTableModel resultModel = new DefaultTableModel() {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };

    public AdedanhaPanel() {
        super(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        status.setFont(status.getFont().deriveFont(Font.BOLD));
        JPanel top = new JPanel(new BorderLayout(4, 4));
        top.add(buildPlayersBar(), BorderLayout.NORTH);
        top.add(status, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        cardHost.add(buildPlayCard(), CARD_PLAY);
        cardHost.add(buildVoteCard(), CARD_VOTE);
        cardHost.add(buildResultCard(), CARD_RESULT);
        add(cardHost, BorderLayout.CENTER);

        log.setEditable(false);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        JScrollPane logScroll = new JScrollPane(log);
        logScroll.setBorder(BorderFactory.createTitledBorder("Histórico"));
        add(logScroll, BorderLayout.SOUTH);

        showIdle();
    }

    /** O plugin chama depois de criar o controller (um precisa do outro). */
    public void setController(AdedanhaController controller) {
        this.controller = controller;
    }

    /** O plugin diz o que fazer com um IP digitado (devolve uma mensagem de erro, ou null se deu certo). */
    public void setOnAddPlayerIp(Function<String, String> onAddPlayerIp) {
        this.onAddPlayerIp = onAddPlayerIp;
    }

    /** Atualiza a linha "Por IP: ..." com os jogadores que o plugin conhece. */
    public void showKnownPlayers(Collection<String> ips) {
        SwingUtilities.invokeLater(() -> knownPlayersLabel.setText(ips.isEmpty()
                ? "Por IP: ninguém ainda"
                : "Por IP: " + String.join(", ", ips)));
    }

    // ------------------------------------------------------------------ montagem

    /**
     * Barra para quando o multicast não chega (lista de usuários vazia): digitar o IP de
     * um jogador. Basta um dos dois digitar; o outro aprende o IP pela primeira mensagem.
     */
    private JPanel buildPlayersBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        bar.add(new JLabel("IP do jogador:"));
        bar.add(ipField);
        JButton add = new JButton("Adicionar");
        Runnable addAction = () -> {
            if (onAddPlayerIp == null) {
                return;
            }
            String error = onAddPlayerIp.apply(ipField.getText().trim());
            if (error == null) {
                appendLog("Jogador adicionado por IP: " + ipField.getText().trim() + ".");
                ipField.setText("");
            } else {
                appendLog(error);
            }
        };
        add.addActionListener(e -> addAction.run());
        ipField.addActionListener(e -> addAction.run());
        bar.add(add);
        bar.add(knownPlayersLabel);
        return bar;
    }

    private JPanel buildPlayCard() {
        JPanel card = new JPanel(new BorderLayout(8, 8));

        letterLabel.setFont(letterLabel.getFont().deriveFont(Font.BOLD, 64f));
        card.add(letterLabel, BorderLayout.NORTH);
        card.add(new JScrollPane(fieldsPanel), BorderLayout.CENTER);

        stopButton.setFont(stopButton.getFont().deriveFont(Font.BOLD, 16f));
        newRoundButton.addActionListener(e -> controller.startNewRound());
        stopButton.addActionListener(e -> controller.stop());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 4));
        buttons.add(newRoundButton);
        buttons.add(stopButton);
        card.add(buttons, BorderLayout.SOUTH);
        return card;
    }

    private JPanel buildVoteCard() {
        JPanel card = new JPanel(new BorderLayout(8, 8));
        card.add(voteHint, BorderLayout.NORTH);

        JTable table = new JTable(voteModel);
        table.setRowHeight(24);
        table.getColumnModel().getColumn(4).setMaxWidth(80);
        card.add(new JScrollPane(table), BorderLayout.CENTER);

        confirmVotesButton.addActionListener(e -> {
            if (table.isEditing()) {
                table.getCellEditor().stopCellEditing();
            }
            confirmVotesButton.setEnabled(false);
            voteModel.setLocked(true);
            status.setText("Votos enviados. Esperando os outros votarem…");
            controller.submitVotes(voteModel.markedKeys());
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER));
        buttons.add(confirmVotesButton);
        card.add(buttons, BorderLayout.SOUTH);
        return card;
    }

    private JPanel buildResultCard() {
        JPanel card = new JPanel(new BorderLayout(8, 8));
        JTable table = new JTable(resultModel);
        table.setRowHeight(24);
        card.add(new JScrollPane(table), BorderLayout.CENTER);

        JButton again = new JButton("Nova rodada");
        again.addActionListener(e -> controller.startNewRound());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER));
        buttons.add(again);
        card.add(buttons, BorderLayout.SOUTH);
        return card;
    }

    private void showIdle() {
        status.setText("Clique em Nova rodada. Quem estiver com o Pitiupi aberto recebe a mesma letra.");
        letterLabel.setText("–");
        rebuildFields(AdedanhaController.DEFAULT_CATEGORIES);
        setFieldsEnabled(false);
        newRoundButton.setEnabled(true);
        stopButton.setEnabled(false);
        cards.show(cardHost, CARD_PLAY);
    }

    private void rebuildFields(String[] categories) {
        fieldsPanel.removeAll();
        fields.clear();
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        for (int i = 0; i < categories.length; i++) {
            gc.gridy = i;
            gc.gridx = 0;
            gc.weightx = 0;
            gc.fill = GridBagConstraints.NONE;
            gc.anchor = GridBagConstraints.EAST;
            fieldsPanel.add(new JLabel(categories[i] + ":"), gc);

            JTextField field = new JTextField(24);
            final int index = i;
            field.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { push(); }
                @Override public void removeUpdate(DocumentEvent e) { push(); }
                @Override public void changedUpdate(DocumentEvent e) { push(); }
                private void push() {
                    if (controller != null) {
                        controller.setDraftAnswer(index, field.getText());
                    }
                }
            });
            gc.gridx = 1;
            gc.weightx = 1;
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.anchor = GridBagConstraints.WEST;
            fieldsPanel.add(field, gc);
            fields.add(field);
        }
        // Empurra os campos para cima.
        gc.gridy = categories.length;
        gc.weighty = 1;
        fieldsPanel.add(new JPanel(), gc);
        fieldsPanel.revalidate();
        fieldsPanel.repaint();
    }

    private void setFieldsEnabled(boolean enabled) {
        for (JTextField f : fields) {
            f.setEditable(enabled);
        }
    }

    // ------------------------------------------------------------------ GameListener

    @Override
    public void onRoundStarted(String letter, String[] categories, String starterName) {
        SwingUtilities.invokeLater(() -> {
            letterLabel.setText(letter);
            rebuildFields(categories);
            setFieldsEnabled(true);
            newRoundButton.setEnabled(false);
            stopButton.setEnabled(true);
            status.setText("Letra " + letter + " — rodada iniciada por " + starterName + ". Quem terminar, clica STOP!");
            cards.show(cardHost, CARD_PLAY);
            if (!fields.isEmpty()) {
                fields.get(0).requestFocusInWindow();
            }
            appendLog("Rodada nova (" + starterName + "): letra " + letter + ".");
        });
    }

    @Override
    public void onRoundStopped(String stopperName) {
        SwingUtilities.invokeLater(() -> {
            setFieldsEnabled(false);
            stopButton.setEnabled(false);
            status.setText("STOP de " + stopperName + "! Enviando respostas e esperando as dos outros…");
            appendLog("STOP de " + stopperName + ".");
        });
    }

    @Override
    public void onVotingStarted(String letter, String[] categories, List<PlayerAnswers> answers) {
        boolean playing = controller.isPlayingThisRound();
        SwingUtilities.invokeLater(() -> {
            voteModel.load(letter, categories, answers, controller.getMyId());
            confirmVotesButton.setEnabled(true);
            voteHint.setText(playing
                    ? "Marque Anular no que não vale (palavra inventada, categoria errada). Vale a maioria."
                    : "Você não respondeu nada nesta rodada: pode olhar, mas o seu voto não conta.");
            status.setText("Votação — letra " + letter + ". " + answers.size() + " jogador(es).");
            cards.show(cardHost, CARD_VOTE);
            appendLog("Votação aberta com " + answers.size() + " jogador(es).");
        });
    }

    @Override
    public void onResult(String[] categories, List<PlayerAnswers> answers, RoundResult result,
                         Map<String, Integer> totals, Map<String, String> names) {
        SwingUtilities.invokeLater(() -> {
            List<String> columns = new ArrayList<>();
            columns.add("Jogador");
            for (String c : categories) {
                columns.add(c);
            }
            columns.add("Rodada");
            columns.add("Placar");
            resultModel.setColumnIdentifiers(columns.toArray());
            resultModel.setRowCount(0);

            for (PlayerAnswers pa : answers) {
                int[] points = result.pointsByPlayer().get(pa.playerId());
                List<Object> row = new ArrayList<>();
                row.add(pa.name());
                for (int c = 0; c < categories.length; c++) {
                    String word = c < pa.answers().length ? pa.answers()[c] : "";
                    String mark = result.annulledKeys().contains(AdedanhaRules.answerKey(pa.playerId(), c))
                            ? " (anulada)" : "";
                    row.add((word == null || word.isBlank() ? "—" : word.trim()) + mark + "  " + points[c]);
                }
                row.add(result.totalOf(pa.playerId()));
                row.add(totals.getOrDefault(pa.playerId(), 0));
                resultModel.addRow(row.toArray());
            }

            StringBuilder board = new StringBuilder("Placar: ");
            totals.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .forEach(e -> board.append(names.getOrDefault(e.getKey(), "?"))
                            .append(' ').append(e.getValue()).append("  "));
            status.setText("Fim da rodada. " + board.toString().trim());
            appendLog(board.toString().trim());
            newRoundButton.setEnabled(true);
            cards.show(cardHost, CARD_RESULT);
        });
    }

    @Override
    public void onLog(String text) {
        SwingUtilities.invokeLater(() -> appendLog(text));
    }

    private void appendLog(String text) {
        log.append(text + "\n");
        log.setCaretPosition(log.getDocument().getLength());
    }

    // ------------------------------------------------------------------ tabela de votação

    /** Uma linha por resposta: jogador, categoria, palavra, situação, caixinha "Anular". */
    private static final class VoteTableModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Jogador", "Categoria", "Resposta", "Situação", "Anular"};

        private final List<Object[]> rows = new ArrayList<>();
        private final List<String> keys = new ArrayList<>();
        private final List<Boolean> votable = new ArrayList<>();
        private boolean locked;

        void load(String letter, String[] categories, List<PlayerAnswers> answers, String myId) {
            rows.clear();
            keys.clear();
            votable.clear();
            locked = false;
            for (PlayerAnswers pa : answers) {
                boolean mine = pa.playerId().equals(myId);
                for (int c = 0; c < categories.length; c++) {
                    String word = c < pa.answers().length ? pa.answers()[c] : "";
                    if (word == null || word.isBlank()) {
                        continue; // vazia já vale 0, não há o que votar
                    }
                    boolean valid = AdedanhaRules.startsWithLetter(word, letter);
                    String situation;
                    if (!valid) {
                        situation = "não começa com " + letter + " (0)";
                    } else {
                        situation = mine ? "sua" : "";
                    }
                    rows.add(new Object[]{pa.name(), categories[c], word == null ? "" : word.trim(), situation, false});
                    keys.add(AdedanhaRules.answerKey(pa.playerId(), c));
                    votable.add(valid && !mine);
                }
            }
            fireTableDataChanged();
        }

        void setLocked(boolean locked) {
            this.locked = locked;
        }

        Set<String> markedKeys() {
            Set<String> marked = new HashSet<>();
            for (int i = 0; i < rows.size(); i++) {
                if (Boolean.TRUE.equals(rows.get(i)[4])) {
                    marked.add(keys.get(i));
                }
            }
            return marked;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int column) { return COLUMNS[column]; }
        @Override public Object getValueAt(int row, int column) { return rows.get(row)[column]; }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 4 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 4 && !locked && votable.get(row);
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            rows.get(row)[column] = value;
            fireTableCellUpdated(row, column);
        }
    }
}
