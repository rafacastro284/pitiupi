package pitiupi.adedanha.plugin;

import java.util.List;
import javax.swing.SwingUtilities;
import pitiupi.adedanha.GUI.AdedanhaPanel;
import pitiupi.adedanha.control.AdedanhaController;
import pitiupi.adedanha.control.GameNetwork;
import pitiupi.adedanha.net.AnswersMessage;
import pitiupi.adedanha.net.RoundStartMessage;
import pitiupi.adedanha.net.StopMessage;
import pitiupi.adedanha.net.VotesMessage;
import pitiupi.api.plugin.PeerActionsRegistrar;
import pitiupi.api.plugin.PluginWorkspace;
import pitiupi.application.messaging.MessageDispatcher;
import pitiupi.application.messaging.SendTarget;
import pitiupi.application.messaging.TransportMode;
import pitiupi.application.peeractions.PeerAction;
import pitiupi.application.peeractions.PeerActionTarget;
import pitiupi.control.ConnectionManager;
import pitiupi.models.Messages.Message;
import pitiupi.models.Plugin;

/**
 * Liga a adedanha ao núcleo: cria a tela e o controller, diz quais mensagens são
 * deste plugin e entrega cada uma ao controller.
 */
public class AdedanhaPlugin extends Plugin {

    private PluginWorkspace workspace;
    private AdedanhaPanel panel;
    private AdedanhaController controller;

    @Override public String getName() { return "Adedanha"; }
    @Override public String getAuthor() { return "Rafael Castro Moreira"; }
    @Override public String getVersion() { return "1.0"; }
    @Override public String getIdentifier() { return "pitiupi.adedanha"; }

    @Override
    public void createPlugin(ConnectionManager connectionManager, PluginWorkspace workspace) {
        this.workspace = workspace;

        // Os dois meios de envio do jogo, com o MessageDispatcher do núcleo.
        GameNetwork network = new GameNetwork() {
            @Override
            public void sendFast(Message message) {
                MessageDispatcher.send(connectionManager, message,
                        SendTarget.broadcastAll(), TransportMode.MULTICAST_UDP);
            }

            @Override
            public void sendReliable(Message message) {
                // Na sala inteira, o núcleo abre (ou reaproveita) um TCP por pessoa online.
                MessageDispatcher.send(connectionManager, message,
                        SendTarget.broadcastAll(), TransportMode.TCP);
            }
        };

        panel = new AdedanhaPanel();
        controller = new AdedanhaController(network, panel, connectionManager::getUserName);
        panel.setController(controller);

        bindShellWorkspaceContent(panel);
        installPeerActions(workspace.peerActions());
    }

    @Override
    public void installPeerActions(PeerActionsRegistrar registrar) {
        registrar.register(PeerAction.of(
                "adedanha.open",
                "Jogar adedanha com todos",
                PeerActionTarget.ALL_PEERS,
                target -> openTab()
        ));
    }

    private void openTab() {
        workspace.openOrFocusTab(getIdentifier(), getDisplayName());
    }

    @Override
    public List<Class<? extends Message>> getMessages() {
        return List.of(RoundStartMessage.class, StopMessage.class, AnswersMessage.class, VotesMessage.class);
    }

    @Override
    public void receiveMessage(Message message) {
        if (controller == null) {
            return;
        }
        if (message instanceof RoundStartMessage m) {
            controller.receiveRoundStart(m);
            // Alguém começou uma rodada: abre a aba para a pessoa ver a letra.
            SwingUtilities.invokeLater(this::openTab);
        } else if (message instanceof StopMessage m) {
            controller.receiveStop(m);
        } else if (message instanceof AnswersMessage m) {
            controller.receiveAnswers(m);
        } else if (message instanceof VotesMessage m) {
            controller.receiveVotes(m);
        }
    }
}
