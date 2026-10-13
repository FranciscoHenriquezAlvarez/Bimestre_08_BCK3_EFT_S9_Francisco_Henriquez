package cl.duoc.bancoxyz.atm.kafka;

import java.util.UUID;

public class KafkaPublicationException extends RuntimeException {
    private final UUID transactionId;

    public KafkaPublicationException(UUID transactionId, Throwable cause) {
        super("No fue posible publicar la solicitud de retiro en Kafka", cause);
        this.transactionId = transactionId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }
}
