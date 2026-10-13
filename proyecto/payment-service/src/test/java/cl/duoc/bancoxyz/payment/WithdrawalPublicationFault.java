package cl.duoc.bancoxyz.payment;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

public class WithdrawalPublicationFault implements ProducerInterceptor<String, Object> {
    static final AtomicBoolean ENABLED = new AtomicBoolean();
    static final AtomicReference<Object> ORIGINAL_EVENT = new AtomicReference<>();

    @Override
    public ProducerRecord<String, Object> onSend(ProducerRecord<String, Object> record) {
        if (ENABLED.get()) {
            ORIGINAL_EVENT.set(record.value());
            // El broker real no tiene esta particion; el productor agotara su espera de metadata.
            return new ProducerRecord<>(record.topic(), 999, record.timestamp(), record.key(), record.value(), record.headers());
        }
        return record;
    }

    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
    }

    @Override
    public void close() {
    }

    @Override
    public void configure(Map<String, ?> configs) {
    }
}
