package pitiupi.adedanha.net;

import pitiupi.models.Messages.Message;

/**
 * Base das mensagens da adedanha: todas levam o IPv4 de quem enviou.
 *
 * <p>O núcleo entrega só o objeto, sem o endereço de origem. Com o IP dentro da
 * mensagem, quem recebe aprende o endereço do outro jogador e passa a mandar unicast
 * para ele, mesmo numa rede em que o multicast (e a lista de usuários) não chega.</p>
 */
public abstract class AdedanhaMessage extends Message {

    private static final long serialVersionUID = 1L;

    private String senderIp;

    protected AdedanhaMessage() {
    }

    /** Preenchido pelo plugin na hora do envio. */
    public String getSenderIp() { return senderIp; }
    public void setSenderIp(String senderIp) { this.senderIp = senderIp; }
}
