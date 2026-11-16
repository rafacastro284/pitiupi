# Plugin Ping da oficina

**Projeto de referência:** `Ping-Pitiupi`  
**Objetivo:** demonstrar, seguindo a ordem da oficina, como definir um protocolo simples, implementar seu comportamento, construir uma interface e integrá-la ao Pitiupi.

> Esta versão acompanha o código atualizado compartilhado para a oficina. Ela não inclui `PingSender` nem o tratamento de broadcast: o exemplo trabalha somente com Ping direcionado a um participante.

---

## 1. Visão geral: o que o plugin faz?

O plugin permite enviar uma mensagem Ping para um participante específico. Quem recebe o Ping responde com um Pong. O remetente utiliza o identificador da solicitação para relacionar a resposta ao Ping enviado e calcula o tempo decorrido.

A implementação também oferece:

- Ping direcionado a um usuário;
- Ping contínuo, enviado periodicamente;
- registro de respostas, falhas e erros em uma interface Swing;
- ações no menu de contexto dos participantes.

### Fluxo geral

```text
Usuário A                         Usuário B
   |                                  |
   | -------- PingMessage ----------> |
   |                                  |
   | <-------- PongMessage ---------- |
   |                                  |
   | calcula o RTT                    |
   |                                  |
```

O valor calculado é o **RTT (Round-Trip Time)**: o tempo entre o envio do Ping e o recebimento do Pong correspondente.

---

## 2. Estrutura do plugin

A implementação está dividida em quatro áreas:

| Pacote lógico | Responsabilidade |
|---|---|
| `net` | Mensagens que serão trocadas pela rede |
| `control` | Lógica do Ping, dados pendentes e interface auxiliar de log |
| `GUI` | Interface gráfica e interação com o usuário |
| `plugin` | Integração com o ciclo de vida e os recursos do Pitiupi |

Os exemplos abaixo usam os pacotes `pitiupi.chat.net`, `pitiupi.chat.control`, `pitiupi.chat.GUI` e `pitiupi.chat.plugin`. Mantenha as declarações `package` e os diretórios coerentes no projeto Java.

---

# Parte I — Definição do protocolo

## 3. Definir o fluxo antes de escrever o código

Antes de criar classes, precisamos definir o comportamento que queremos implementar.

**O que precisa acontecer para que um usuário consiga testar a disponibilidade de outro?**

O fluxo definido é:

1. O remetente cria e envia um Ping.
2. O destinatário recebe o Ping.
3. O destinatário cria e envia um Pong como resposta.
4. O remetente recebe o Pong.
5. O remetente identifica a solicitação correspondente e calcula o RTT.

O Pitiupi já fornece os mecanismos de transporte. O plugin precisa definir as mensagens e decidir quando enviá-las e como reagir ao recebimento delas.

## 4. Mensagem `PingMessage`

### Por que criar essa classe?

Precisamos representar uma solicitação de Ping como uma mensagem que o núcleo do Pitiupi consiga transportar.

A classe estende `Message`, tipo-base utilizado pelo Pitiupi para suas mensagens.

### Quais informações ela carrega?

- `pingId`: identificador único da solicitação. Permite relacionar um Pong ao Ping que o originou.
- `sentAtMillis`: instante em que o Ping foi criado, em milissegundos.
- `senderName`: nome de quem enviou.
- `senderIp`: endereço IP do remetente, utilizado para direcionar a resposta.

O identificador é importante porque podem existir várias solicitações pendentes ao mesmo tempo. Não é seguro associar uma resposta apenas ao endereço IP ou ao horário.

### Código — `PingMessage.java`

```java
package pitiupi.chat.net;

import pitiupi.models.Messages.Message;

public class PingMessage extends Message {
    final String pingId;
    final long sentAtMillis;
    final String senderName;
    final String senderIp;

    public PingMessage(String pingId, long sentAtMillis, String senderName, String senderIp) {
        this.pingId = pingId;
        this.sentAtMillis = sentAtMillis;
        this.senderName = senderName;
        this.senderIp = senderIp;
    }

    public String getSenderIp() {
        return senderIp;
    }

    public long getSentAtMillis() {
        return sentAtMillis;
    }

    public String getSenderName() {
        return senderName;
    }

    public String getPingId() {
        return pingId;
    }
}
```

## 5. Mensagem `PongMessage`

### Por que criar outra classe?

O Pong representa a resposta à solicitação. Separar Ping e Pong em classes distintas deixa explícito que são mensagens com finalidades diferentes e permite que o plugin identifique cada tipo recebido.

O Pong precisa carregar o identificador do Ping original. Dessa forma, o remetente consegue encontrar a solicitação pendente correspondente.

### Quais informações ela carrega?

- `pingId`: identificador recebido no Ping original.
- `sentAtMillis`: horário de envio informado pelo Ping original.
- `receiverName`: nome de quem está respondendo.
- `receiverIp`: endereço IP de quem está respondendo.

O construtor recebe o próprio `PingMessage`. Isso evita que o código que cria o Pong precise copiar manualmente os dados da solicitação.

### Código — `PongMessage.java`

```java
package pitiupi.chat.net;

import pitiupi.models.Messages.Message;

public class PongMessage extends Message {
    final String pingId;
    final long sentAtMillis;
    final String receiverName;
    final String receiverIp;

    public PongMessage(PingMessage pingMessage, String receiverName, String receiverIp) {
        this.pingId = pingMessage.getPingId();
        this.sentAtMillis = pingMessage.getSentAtMillis();
        this.receiverName = receiverName;
        this.receiverIp = receiverIp;
    }

    public String getPingId() {
        return pingId;
    }

    public long getSentAtMillis() {
        return sentAtMillis;
    }

    public String getReceiverName() {
        return receiverName;
    }

    public String getReceiverIp() {
        return receiverIp;
    }
}
```

### O que precisa ser compatível entre os participantes?

Os participantes precisam concordar com o formato e o significado das mensagens. Por exemplo, ambos precisam interpretar `pingId` como o identificador da solicitação e entender quais informações uma mensagem Ping ou Pong contém.

---

# Parte II — Interface auxiliar e dados

## 6. `PingLogSink`

### Por que criar essa interface?

O controlador precisa comunicar resultados, como respostas recebidas, falhas e erros. Porém, não deveria precisar conhecer os detalhes de como esses resultados serão exibidos.

`PingLogSink` define o que o controlador precisa comunicar, sem determinar como a interface fará isso. Isso permite trocar a forma de apresentação dos resultados sem reescrever a lógica do Ping.

### Código — `PingLogSink.java`

```java
package pitiupi.chat.control;

public interface PingLogSink {
    public void appendSendSuccess(String receiverName, String receiverIp, long rtt);
    public void appendFailure(String receiverIp);
    public void appendError(String errorText);
}
```

## 7. `PingData`

### Por que precisamos guardar dados pendentes?

Quando enviamos um Ping direcionado, ainda não recebemos a resposta. Precisamos guardar informações para reconhecer a resposta futura e calcular o tempo decorrido.

O mapa de solicitações pendentes será indexado pelo `pingId`. O valor associado guarda o endereço do destinatário e o instante de envio.

### Código — `PingData.java`

```java
package pitiupi.chat.control;

public record PingData(String receiverIp, long sentTime) {
}
```

O uso de `record` é adequado porque `PingData` é apenas um pequeno agrupamento de dados, sem necessidade de comportamento adicional.

---

# Parte III — Controlador

## 8. Responsabilidade do `PingController`

O controlador coordena o funcionamento do Ping. Ele não constrói a interface e não implementa o transporte de rede. Em vez disso, utiliza os mecanismos fornecidos pelo Pitiupi para enviar mensagens e processa as mensagens recebidas pelo plugin.

Suas responsabilidades são:

- criar mensagens Ping;
- enviar Ping direcionado;
- iniciar e interromper o envio contínuo;
- responder a Ping recebidos;
- receber e correlacionar Pong;
- calcular RTT;
- identificar solicitações que não receberam resposta dentro do prazo;
- informar resultados por meio de `PingLogSink`.

## 9. Ferramentas utilizadas pelo controlador

| Ferramenta | Finalidade |
|---|---|
| `ConnectionManager` | Acessar informações da conexão local, como o nome do usuário |
| `MessageDispatcher` | Solicitar ao núcleo o envio de uma mensagem |
| `SendTarget` | Definir o destinatário da mensagem |
| `TransportMode` | Selecionar o modo de transporte |
| `LocalNetworkInfo` | Obter o endereço IPv4 local |
| `PeerIpv4` | Normalizar o endereço IPv4 de um participante |
| `InetAddress` | Representar e resolver endereços IP |
| `UUID` | Gerar identificadores únicos para os Pings |
| `ConcurrentHashMap` | Armazenar solicitações pendentes com acesso concorrente |
| `javax.swing.Timer` | Executar ações periódicas, como Ping contínuo e verificação de timeout |

A principal ideia é que `MessageDispatcher` e os tipos relacionados abstraem o envio. O plugin informa qual mensagem deseja enviar, para quem e utilizando qual modo de transporte.

## 10. Criar e configurar o controlador

O construtor recebe o `ConnectionManager`, cria o mapa de solicitações pendentes e configura o temporizador responsável por verificar Pings sem resposta.

O `PingLogSink` é configurado posteriormente, quando o painel é criado. Isso mantém o controlador independente da implementação concreta da interface.

### Código — início de `PingController.java`

```java
package pitiupi.chat.control;

import pitiupi.application.messaging.MessageDispatcher;
import pitiupi.application.messaging.SendTarget;
import pitiupi.application.messaging.TransportMode;
import pitiupi.chat.net.PingMessage;
import pitiupi.chat.net.PongMessage;
import pitiupi.control.ConnectionManager;
import pitiupi.presentation.shell.LocalNetworkInfo;
import pitiupi.presentation.shell.PeerIpv4;

import javax.swing.*;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PingController {
    final ConnectionManager connectionManager;
    PingLogSink pingLogSink;

    final Map<String, PingData> pendingPings;

    Timer continuousTimer;
    Timer collectorTimer;

    static final int CONTINUOUS_INTERVAL_MS = 1000;
    static final int COLLECTOR_INTERVAL_MS = 5000;

    public PingController(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;

        pendingPings = new ConcurrentHashMap<>();

        configureCollector();
        collectorTimer.start();
    }

    public void setPingLogSink(PingLogSink pingLogSink) {
        this.pingLogSink = pingLogSink;
    }
```

## 11. Verificar solicitações sem resposta

Um Ping direcionado pode não receber resposta. Por isso, o controlador precisa de um mecanismo para remover solicitações antigas e informar a falha.

O `collectorTimer` executa periodicamente. A cada verificação, percorre os Pings pendentes e compara o horário atual com o horário de envio.

Se o tempo ultrapassar `COLLECTOR_INTERVAL_MS`, a solicitação é removida e a interface recebe uma notificação de falha.

### Código — `configureCollector`

```java
    private void configureCollector() {
        collectorTimer = new Timer(COLLECTOR_INTERVAL_MS, e -> {
            long currentTime = System.currentTimeMillis();

            for (String key : pendingPings.keySet()) {
                PingData pingData = pendingPings.get(key);
                if (currentTime - pingData.sentTime() > COLLECTOR_INTERVAL_MS) {
                    pingLogSink.appendFailure(pingData.receiverIp());
                    pendingPings.remove(key);
                }
            }
        });
    }
```

## 12. Criar uma mensagem Ping

A criação do Ping foi concentrada em `generatePingMessage`, evitando repetir a obtenção de informações locais nos métodos de envio.

O método:

1. Gera um identificador com `UUID.randomUUID()`.
2. Obtém o horário atual com `System.currentTimeMillis()`.
3. Obtém o nome do usuário pelo `ConnectionManager`.
4. Obtém o IPv4 local por `LocalNetworkInfo`.
5. Cria a mensagem com essas informações.

### Código — `generatePingMessage`

```java
    private PingMessage generatePingMessage() {
        String pingId = UUID.randomUUID().toString();
        long millis = System.currentTimeMillis();

        String userName = connectionManager.getUserName();
        String userIp = LocalNetworkInfo.bestLocalIpv4();

        return new PingMessage(pingId, millis, userName, userIp);
    }
```

## 13. Enviar Ping para um participante

O envio direcionado recebe o endereço IP do destinatário.

O endereço é normalizado com `PeerIpv4.normalize` e convertido em `InetAddress`. Depois, o controlador solicita ao `MessageDispatcher` o envio da mensagem para um único participante, utilizando `UNICAST_UDP`.

Após solicitar o envio, guarda os dados da solicitação em `pendingPings`, usando o identificador do Ping como chave. Quando o Pong correspondente chegar, essa entrada será usada para calcular o RTT.

### Código — `sendPing`

```java
    public void sendPing(String receiverIp) {
        try {
            PingMessage pingMessage = generatePingMessage();

            String normalizedReceiverIp = PeerIpv4.normalize(receiverIp);
            InetAddress receiverAddress = InetAddress.getByName(normalizedReceiverIp);

            MessageDispatcher.send(
                connectionManager,
                pingMessage,
                SendTarget.singlePeer(receiverAddress),
                TransportMode.UNICAST_UDP
            );

            PingData pingData = new PingData(receiverIp, pingMessage.getSentAtMillis());
            pendingPings.put(pingMessage.getPingId(), pingData);
        }
        catch (UnknownHostException ex) {
            pingLogSink.appendError("Falha em conseguir o InetAddress do destinatário");
        }
    }
```

## 14. Enviar Ping continuamente

O envio contínuo utiliza um `Timer` para chamar `sendPing` periodicamente.

Antes de iniciar, o controlador verifica se já existe um temporizador ativo. Isso evita iniciar outro ciclo de envio contínuo enquanto o anterior ainda está ativo.

`stopContinuousPing` interrompe o temporizador e limpa sua referência.

### Código — `sendContinuousPing` e `stopContinuousPing`

```java
    public void sendContinuousPing(String receiverIp) {
        if (continuousTimer != null) {
            pingLogSink.appendError("Você já está enviando pings contínuos para outro usuário");
            return;
        }

        continuousTimer = new Timer(CONTINUOUS_INTERVAL_MS, e -> {
            sendPing(receiverIp);
        });

        continuousTimer.start();
    }

    public void stopContinuousPing() {
        if (continuousTimer != null) {
            continuousTimer.stop();
            continuousTimer = null;
        }
    }
```

## 15. Responder a um Ping recebido

Quando outro participante envia um Ping, precisamos responder com um Pong.

O método utiliza os dados do Ping original para preservar seu identificador e horário de envio. Em seguida, obtém o nome e o IP locais para identificar quem está respondendo.

Por fim, envia o Pong ao endereço informado na mensagem original.

### Código — `sendPong`

```java
    public void sendPong(PingMessage pingMessage) {
        try {
            String userName = connectionManager.getUserName();
            String userIp = LocalNetworkInfo.bestLocalIpv4();

            PongMessage pongMessage = new PongMessage(pingMessage, userName, userIp);

            InetAddress senderAddress = InetAddress.getByName(pingMessage.getSenderIp());

            MessageDispatcher.send(
                connectionManager,
                pongMessage,
                SendTarget.singlePeer(senderAddress),
                TransportMode.MULTICAST_UDP
            );
        }
        catch (UnknownHostException ex) {
            Logger.getLogger(PingController.class.getName()).log(Level.WARNING, null, ex);
        }
    }
```

## 16. Receber um Pong e calcular o RTT

Quando um Pong chega, o controlador procura a solicitação em `pendingPings` pelo `pingId` e remove a entrada correspondente.

O tempo decorrido é calculado subtraindo o horário de envio do horário atual. O resultado é encaminhado ao `PingLogSink`, que decide como apresentá-lo.

### Código — `receivePong`

```java
    public void receivePong(PongMessage pongMessage) {
        long currentMillis = System.currentTimeMillis();

        PingData pingData = pendingPings.remove(pongMessage.getPingId());

        long rtt = currentMillis - pingData.sentTime();

        pingLogSink.appendSendSuccess(
            pongMessage.getReceiverName(),
            pongMessage.getReceiverIp(),
            rtt
        );
    }
}
```

---

# Parte IV — Interface gráfica

## 17. Responsabilidade do `PingPanel`

A interface permite que o usuário informe um IP, envie um Ping, inicie ou interrompa o envio contínuo e acompanhe os resultados.

O painel implementa `PingLogSink`: recebe os resultados do controlador e os apresenta no log.

Assim, o controlador não precisa conhecer `JTextArea`, botões ou qualquer outro componente Swing. Ele apenas chama os métodos definidos pela interface `PingLogSink`.

## 18. Ferramentas utilizadas na GUI

| Ferramenta | Finalidade |
|---|---|
| `JPanel` | Painel principal |
| `JTextField` | Campo para informar o IP |
| `JButton` | Botão de envio |
| `JToggleButton` | Alternar envio contínuo |
| `JTextArea` | Exibir o log como texto simples |
| `JScrollPane` | Permitir rolagem do log |
| `BorderLayout` e `FlowLayout` | Organizar os componentes |
| `SwingUtilities.invokeLater` | Atualizar a interface na Event Dispatch Thread |

## 19. Criar e organizar os componentes

O construtor recebe o controlador e cria os componentes da interface.

A interface utiliza um `BorderLayout`: os controles ficam na região superior e o log ocupa a região central. `JTextArea` é suficiente porque o log apresenta texto simples, sem formatação por cores.

### Código — início de `PingPanel.java`

```java
package pitiupi.chat.GUI;

import pitiupi.chat.control.PingController;
import pitiupi.chat.control.PingLogSink;

import javax.swing.*;
import java.awt.*;

public class PingPanel extends JPanel implements PingLogSink {

    private final PingController controller;

    private final JTextField txtIp;
    private final JButton btnSend;
    private final JToggleButton btnContinuous;
    private final JTextArea txtLog;

    public PingPanel(PingController controller) {
        this.controller = controller;

        txtIp = new JTextField(15);
        btnSend = new JButton("Enviar Ping");
        btnContinuous = new JToggleButton("Ping contínuo");
        txtLog = new JTextArea();

        configLog();
        configButtons();

        setLayout(new BorderLayout());

        JPanel top = new JPanel();
        top.add(new JLabel("IP:"));
        top.add(txtIp);
        top.add(btnSend);
        top.add(btnContinuous);

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(txtLog), BorderLayout.CENTER);
    }
```

## 20. Configurar o log

O log não deve ser editável pelo usuário. Como ele contém apenas texto, podemos utilizar `JTextArea` e seu método `append`, sem precisar de `StyledDocument` ou atributos de estilo.

O `SwingUtilities.invokeLater` agenda a alteração na thread responsável pela interface gráfica. Isso é importante porque os métodos do `PingLogSink` podem ser chamados durante o processamento de mensagens da rede.

### Código — `configLog` e `appendText`

```java
    private void configLog() {
        txtLog.setEditable(false);
    }

    private void appendText(String text) {
        SwingUtilities.invokeLater(() ->
            txtLog.append(text + "\n")
        );
    }
```

## 21. Configurar os botões

Cada botão delega a operação ao controlador.

- O botão de envio chama `sendPing`.
- O botão alternável inicia ou interrompe `sendContinuousPing`.

A interface não implementa o protocolo: ela apenas transforma ações do usuário em chamadas ao controlador.

### Código — `configButtons`

```java
    private void configButtons() {
        btnSend.addActionListener(e ->
            controller.sendPing(txtIp.getText())
        );

        btnContinuous.addActionListener(e -> {
            if (btnContinuous.isSelected()) {
                controller.sendContinuousPing(txtIp.getText());
                btnContinuous.setText("Parar Ping contínuo");
            } else {
                controller.stopContinuousPing();
                btnContinuous.setText("Ping contínuo");
            }
        });
    }
```

## 22. Apresentar resultados e erros

Como `PingPanel` implementa `PingLogSink`, o controlador pode informar resultados sem depender diretamente de `JTextArea`.

Cada método monta uma mensagem e delega a atualização visual para `appendText`.

### Código — implementação de `PingLogSink`

```java
    @Override
    public void appendSendSuccess(String receiverName, String receiverIp, long rtt) {
        appendText("Pong recebido de " + receiverName +
                " (" + receiverIp + ") - RTT: " + rtt + " ms");
    }

    @Override
    public void appendFailure(String receiverIp) {
        appendText("Ping para " + receiverIp + " expirou!");
    }

    @Override
    public void appendError(String errorText) {
        appendText("Erro: " + errorText);
    }
```

## 23. Métodos auxiliares para iniciar ações

O plugin também precisa iniciar um Ping a partir de uma ação no menu de contexto de um participante.

Para reaproveitar o comportamento já associado aos botões, o painel preenche o campo de IP e usa `doClick()`. Esse método simula um clique no botão e dispara seu `ActionListener`.

No caso do botão alternável, `doClick()` também altera seu estado selecionado. Por isso, verificamos primeiro se ele já está selecionado: chamar `doClick()` novamente faria o botão alternar para desligado.

### Código — métodos auxiliares

```java
    public void sendPingTo(String receiverIp) {
        txtIp.setText(receiverIp);
        btnSend.doClick();
    }

    public void sendContinuousPingTo(String receiverIp) {
        txtIp.setText(receiverIp);

        if (!btnContinuous.isSelected()) {
            btnContinuous.doClick();
        }
    }
}
```

---

# Parte V — Integração com o Pitiupi

## 24. Responsabilidade de `PingPlugin`

Até aqui, temos mensagens, controlador e interface. Falta informar ao Pitiupi que o plugin existe, quais mensagens ele utiliza e como deve reagir quando uma delas chega.

A classe `PingPlugin` estende `Plugin`, classe-base da plataforma para plugins.

Ela é responsável por:

- fornecer os metadados do plugin;
- criar e conectar controlador e interface;
- registrar as mensagens aceitas;
- encaminhar mensagens recebidas;
- registrar ações no menu dos participantes;
- abrir ou focar a aba do plugin.

## 25. `createPlugin`

### Por que esse método é importante?

O Pitiupi chama `createPlugin` para inicializar o plugin com os recursos que a plataforma disponibiliza.

O método recebe:

- `ConnectionManager`: acesso às informações e recursos da conexão;
- `PluginWorkspace`: integração com a área de trabalho da aplicação.

A implementação cria o controlador e o painel, conecta o `PingLogSink`, registra o conteúdo visual com `bindShellWorkspaceContent` e instala as ações dos participantes.

A ordem é importante: primeiro criamos os objetos, depois conectamos suas dependências e, por fim, registramos o conteúdo e as ações na plataforma.

### Código — início de `PingPlugin.java`

```java
package pitiupi.chat.plugin;

import pitiupi.api.plugin.PeerActionsRegistrar;
import pitiupi.api.plugin.PluginWorkspace;
import pitiupi.application.peeractions.PeerAction;
import pitiupi.application.peeractions.PeerActionTarget;
import pitiupi.application.peeractions.PeerTarget;
import pitiupi.application.presence.UserPeerView;
import pitiupi.chat.GUI.PingPanel;
import pitiupi.chat.control.PingController;
import pitiupi.chat.net.PingMessage;
import pitiupi.chat.net.PongMessage;
import pitiupi.control.ConnectionManager;
import pitiupi.models.Messages.Message;
import pitiupi.models.Plugin;

import java.util.List;

public class PingPlugin extends Plugin {
    PingController pingController;
    PingPanel pingPanel;
    PluginWorkspace pluginWorkspace;

    @Override
    public String getName() {
        return "Ping";
    }

    @Override
    public String getAuthor() {
        return "Gustavo H";
    }

    @Override
    public String getVersion() {
        return "1.0";
    }

    @Override
    public String getIdentifier() {
        return "Pitiupi.Exemplo.Plugin.Ping.GustavoH";
    }

    @Override
    public void createPlugin(ConnectionManager connectionManager, PluginWorkspace pluginWorkspace) {
        pingController = new PingController(connectionManager);
        pingPanel = new PingPanel(pingController);

        this.pluginWorkspace = pluginWorkspace;

        pingController.setPingLogSink(pingPanel);

        bindShellWorkspaceContent(pingPanel);

        installPeerActions(pluginWorkspace.peerActions());
    }
```

## 26. Registrar as mensagens: `getMessages`

O Pitiupi precisa saber quais tipos de mensagem pertencem ao plugin.

`getMessages` retorna as classes `PingMessage` e `PongMessage`. Com isso, o núcleo pode reconhecer os tipos de mensagem utilizados pelo plugin e encaminhá-los conforme seu mecanismo de mensagens.

### Código — `getMessages`

```java
    @Override
    public List<Class<? extends Message>> getMessages() {
        return List.of(PingMessage.class, PongMessage.class);
    }
```

## 27. Receber e encaminhar mensagens: `receiveMessage`

O núcleo entrega ao plugin uma mensagem recebida.

O método verifica o tipo concreto da mensagem:

- Se for `PingMessage`, chama `sendPong` para responder.
- Se for `PongMessage`, chama `receivePong` para processar a resposta e calcular o RTT.

Essa separação mantém o tratamento de cada mensagem no controlador, enquanto o plugin atua como ponto de integração com o núcleo.

### Código — `receiveMessage`

```java
    @Override
    public void receiveMessage(Message message) {
        if (message instanceof PingMessage ping) {
            pingController.sendPong(ping);
        }
        else if (message instanceof PongMessage pong) {
            pingController.receivePong(pong);
        }
    }
```

## 28. Registrar ações no menu: `installPeerActions`

### Por que utilizar Peer Actions?

O Pitiupi permite registrar ações associadas aos participantes da lista lateral. Assim, o usuário pode iniciar uma funcionalidade diretamente a partir do menu de outro participante, sem precisar copiar seu endereço IP manualmente.

O método recebe um `PeerActionsRegistrar`, utilizado para registrar cada ação.

A implementação registra duas ações:

1. **Enviar ping para esta pessoa:** envia um Ping único ao participante selecionado.
2. **Enviar pings contínuos para esta pessoa:** inicia o envio periódico ao participante selecionado.

### Elementos utilizados

- `PeerAction.of`: constrói a ação.
- `PeerActionTarget.SINGLE_PEER`: indica que a ação exige um participante específico.
- `PeerTarget.Single`: representa o alvo individual recebido pelo callback.
- `UserPeerView`: fornece informações sobre o participante selecionado, incluindo seu endereço IP.
- `openPingPanel`: abre ou foca a aba do plugin.

### Código — `installPeerActions` e `openPingPanel`

```java
    @Override
    public void installPeerActions(PeerActionsRegistrar registrar) {
        registrar.register(PeerAction.of(
            "singlePing.peer",
            "Enviar ping para esta pessoa",
            PeerActionTarget.SINGLE_PEER,
            target -> {
                if (target instanceof PeerTarget.Single) {
                    PeerTarget.Single single = (PeerTarget.Single) target;
                    UserPeerView peer = single.peer();

                    openPingPanel();

                    peer.getInetAddress().ifPresent(address -> {
                        pingPanel.sendPingTo(address.getHostAddress());
                    });
                }
            }
        ));

        registrar.register(PeerAction.of(
            "continuousPing.peer",
            "Enviar pings contínuos para esta pessoa",
            PeerActionTarget.SINGLE_PEER,
            target -> {
                if (target instanceof PeerTarget.Single) {
                    PeerTarget.Single single = (PeerTarget.Single) target;
                    UserPeerView peer = single.peer();

                    openPingPanel();

                    peer.getInetAddress().ifPresent(address -> {
                        pingPanel.sendContinuousPingTo(address.getHostAddress());
                    });
                }
            }
        ));
    }

    private void openPingPanel() {
        pluginWorkspace.openOrFocusTab(
            getIdentifier(),
            getDisplayName()
        );
    }
}
```

---

# Parte VI — Compilação e testes

## 29. Compilar o plugin

O projeto utiliza Ant. A compilação deve ser feita conforme a estrutura e os alvos definidos no projeto-base.

No terminal, a partir do diretório do projeto:

```bash
ant jar
```

Depois, instale ou copie o JAR para o diretório de plugins do Pitiupi, conforme o procedimento utilizado no ambiente da oficina.

## 30. Testar entre dois participantes

Para verificar o funcionamento, execute o Pitiupi em duas máquinas conectadas à mesma rede.

1. Inicie o Pitiupi nas duas máquinas.
2. Aguarde que os participantes apareçam na lista.
3. No menu de contexto de um participante, selecione **Enviar ping para esta pessoa**.
4. Verifique se o outro participante recebe a mensagem e responde com Pong.
5. Confira se o remetente apresenta o RTT no log.
6. Teste **Enviar pings contínuos para esta pessoa** e depois interrompa o envio pelo botão no painel.

Se forem usadas duas instâncias no mesmo computador, configure portas diferentes para evitar conflito.

---

# Observações técnicas sobre a implementação

Estas observações não precisam ser o foco da oficina, mas são pontos úteis para revisar depois da atividade.

1. **Ordem de inicialização do `PingLogSink`:** o `collectorTimer` é iniciado no construtor do controlador, mas o `pingLogSink` só é configurado depois que o painel é criado. Em condições normais, o primeiro disparo ocorre após cinco segundos, mas é mais seguro evitar que o temporizador dependa de uma referência ainda não configurada. Uma opção é iniciar o coletor depois de `setPingLogSink`.

2. **Pong inesperado ou duplicado:** `pendingPings.remove(pongMessage.getPingId())` pode retornar `null` se o Pong não corresponder a uma solicitação pendente, se chegar atrasado ou se for duplicado. Antes de acessar `pingData.sentTime()`, convém verificar se `pingData` não é `null`.

3. **Medição de tempo:** `System.currentTimeMillis()` funciona para o exemplo, mas `System.nanoTime()` é mais apropriado para medir intervalos porque não depende do relógio civil do sistema. Para manter a implementação didática, o exemplo usa milissegundos.

4. **Timeout:** o coletor verifica as solicitações a cada cinco segundos e também usa cinco segundos como limite. Isso significa que a notificação de falha pode ocorrer um pouco depois de cinco segundos, dependendo do momento em que o temporizador fizer a próxima verificação.

5. **Transporte do Pong:** o método `sendPong` utiliza `SendTarget.singlePeer(senderAddress)` junto com `TransportMode.MULTICAST_UDP`. Vale confirmar se essa combinação é intencional na API do Pitiupi; o destino é individual, enquanto o modo informado é multicast.

6. **Validação do endereço:** `PeerIpv4.normalize` e `InetAddress.getByName` tratam a normalização e resolução do endereço. Para uma versão mais robusta, também é interessante tratar entradas inválidas e outros erros de envio, não somente `UnknownHostException`.

7. **Estado do botão de Ping contínuo:** `sendContinuousPingTo` evita clicar novamente no botão quando ele já está selecionado. Ainda assim, se o controlador recusar iniciar o temporizador por já existir outro envio contínuo, a interface pode indicar um estado diferente do estado real do controlador. Para a oficina, mantenha o fluxo simples; depois, pode-se fazer o controlador informar se iniciou a operação.

---

## Encerramento

O exemplo percorre o ciclo principal de desenvolvimento de um plugin para o Pitiupi:

1. Definir o protocolo e as mensagens.
2. Implementar o comportamento no controlador.
3. Criar uma interface que apresenta os resultados.
4. Integrar o plugin ao ciclo de vida do Pitiupi.
5. Registrar as mensagens e as ações disponíveis para os participantes.
6. Compilar e testar a comunicação entre máquinas.

O ponto central é perceber que o plugin não precisa reimplementar a infraestrutura de comunicação da plataforma. Ele define o comportamento específico da funcionalidade e utiliza os mecanismos que o Pitiupi já disponibiliza.
