package pitiupi.adedanha.plugin;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Pattern;
import javax.swing.SwingUtilities;
import pitiupi.adedanha.GUI.AdedanhaPanel;
import pitiupi.adedanha.control.AdedanhaController;
import pitiupi.adedanha.control.GameNetwork;
import pitiupi.adedanha.net.AdedanhaMessage;
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
import pitiupi.application.presence.UserPeerView;
import pitiupi.control.ConnectionManager;
import pitiupi.models.Messages.Message;
import pitiupi.models.Plugin;
import pitiupi.presentation.shell.LocalNetworkInfo;
import pitiupi.presentation.shell.PeerIpv4;

/**
 * Liga a adedanha ao núcleo: cria a tela e o controller, diz quais mensagens são
 * deste plugin e entrega cada uma ao controller.
 *
 * <p>Além da lista de usuários do núcleo (que depende de multicast), o plugin guarda
 * os IPs de jogadores digitados na tela ou aprendidos pelas mensagens recebidas. Para
 * esses IPs, tudo também vai por unicast. Assim o jogo funciona numa rede que não
 * entrega multicast a algum dos PCs.</p>
 */
public class AdedanhaPlugin extends Plugin {

    private static final Pattern IPV4 =
            Pattern.compile("^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)$");

    private ConnectionManager connectionManager;
    private PluginWorkspace workspace;
    private AdedanhaPanel panel;
    private AdedanhaController controller;

    /** IPs de jogadores conhecidos pelo plugin (digitados ou aprendidos). */
    private final Set<String> knownPeerIps = new CopyOnWriteArraySet<>();

    @Override public String getName() { return "Adedanha"; }
    @Override public String getAuthor() { return "Rafael Castro Moreira"; }
    @Override public String getVersion() { return "1.1"; }
    @Override public String getIdentifier() { return "pitiupi.adedanha"; }

    @Override
    public void createPlugin(ConnectionManager connectionManager, PluginWorkspace workspace) {
        this.connectionManager = connectionManager;
        this.workspace = workspace;

        GameNetwork network = new GameNetwork() {
            @Override
            public void sendFast(Message message) {
                stampSender(message);
                // Multicast para a sala, e uma cópia unicast para cada IP conhecido.
                // Quem receber as duas ignora a repetida (o controller compara o id).
                MessageDispatcher.send(connectionManager, message,
                        SendTarget.broadcastAll(), TransportMode.MULTICAST_UDP);
                for (InetAddress address : knownAddresses()) {
                    MessageDispatcher.send(connectionManager, message,
                            SendTarget.singlePeer(address), TransportMode.UNICAST_UDP);
                }
            }

            @Override
            public void sendReliable(Message message) {
                stampSender(message);
                // TCP para todos: quem está na lista do núcleo mais os IPs conhecidos.
                for (InetAddress address : reliableTargets()) {
                    MessageDispatcher.send(connectionManager, message,
                            SendTarget.singlePeer(address), TransportMode.TCP);
                }
            }
        };

        panel = new AdedanhaPanel();
        controller = new AdedanhaController(network, panel, connectionManager::getUserName);
        panel.setController(controller);
        panel.setOnAddPlayerIp(this::addPeerIpFromUi);

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

    // ------------------------------------------------------------------ IPs conhecidos

    /** Chamado pelo botão "Adicionar". Devolve a mensagem de erro, ou null se deu certo. */
    private String addPeerIpFromUi(String text) {
        String ip = PeerIpv4.normalize(text);
        if (!IPV4.matcher(ip).matches()) {
            return "IP inválido: \"" + text + "\". Use o formato 172.18.191.239.";
        }
        if (ip.equals(myIp())) {
            return "Esse é o seu próprio IP.";
        }
        rememberPeer(ip);
        return null;
    }

    /** Aprende o IP de quem mandou uma mensagem do jogo. */
    private void rememberPeer(String ip) {
        if (ip == null || ip.isBlank() || ip.equals(myIp()) || !IPV4.matcher(ip).matches()) {
            return;
        }
        if (knownPeerIps.add(ip)) {
            panel.showKnownPlayers(new ArrayList<>(knownPeerIps));
        }
    }

    private void stampSender(Message message) {
        if (message instanceof AdedanhaMessage m) {
            m.setSenderIp(myIp());
        }
    }

    private static String myIp() {
        return LocalNetworkInfo.bestLocalIpv4();
    }

    private List<InetAddress> knownAddresses() {
        List<InetAddress> list = new ArrayList<>();
        for (String ip : knownPeerIps) {
            try {
                list.add(InetAddress.getByName(ip));
            } catch (Exception ignored) {
                // IP validado ao entrar na lista; não deve acontecer.
            }
        }
        return list;
    }

    /** Lista do núcleo + IPs conhecidos, sem repetir e sem incluir este PC. */
    private List<InetAddress> reliableTargets() {
        Set<String> ips = new LinkedHashSet<>();
        for (UserPeerView view : connectionManager.getPresenceService().listPeerViews()) {
            if (view.isBroadcastTarget() || view.isSelf()) {
                continue;
            }
            view.getInetAddress().ifPresent(a -> ips.add(PeerIpv4.fromInetAddress(a)));
        }
        ips.addAll(knownPeerIps);
        ips.remove(myIp());
        List<InetAddress> list = new ArrayList<>();
        for (String ip : ips) {
            try {
                list.add(InetAddress.getByName(ip));
            } catch (Exception ignored) {
                // endereço inválido na lista do núcleo: pula
            }
        }
        return list;
    }

    // ------------------------------------------------------------------ recepção

    @Override
    public List<Class<? extends Message>> getMessages() {
        return List.of(RoundStartMessage.class, StopMessage.class, AnswersMessage.class, VotesMessage.class);
    }

    @Override
    public void receiveMessage(Message message) {
        if (controller == null) {
            return;
        }
        if (message instanceof AdedanhaMessage m) {
            rememberPeer(m.getSenderIp());
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
