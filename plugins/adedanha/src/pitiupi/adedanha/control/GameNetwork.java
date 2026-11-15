package pitiupi.adedanha.control;

import pitiupi.models.Messages.Message;

/**
 * Como o controller manda mensagens. O plugin implementa com o MessageDispatcher do
 * núcleo; um teste pode implementar sem rede nenhuma.
 */
public interface GameNetwork {

    /** Aviso rápido para todos. No plugin: UDP multicast (pode se perder). */
    void sendFast(Message message);

    /** Dado que não pode se perder. No plugin: TCP para cada pessoa online. */
    void sendReliable(Message message);
}
