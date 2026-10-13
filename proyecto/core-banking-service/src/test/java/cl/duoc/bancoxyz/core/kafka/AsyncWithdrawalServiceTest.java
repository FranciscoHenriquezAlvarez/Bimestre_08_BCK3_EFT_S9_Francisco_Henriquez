package cl.duoc.bancoxyz.core.kafka;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.duoc.bancoxyz.events.WithdrawalRequestedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AsyncWithdrawalServiceTest {
    @Test
    void ignoresAnEventAlreadyProcessed() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        AsyncWithdrawalService service = new AsyncWithdrawalService(jdbc, new ObjectMapper());
        UUID eventId = UUID.randomUUID();
        WithdrawalRequestedEvent event = new WithdrawalRequestedEvent(eventId, UUID.randomUUID(), 104,
                new BigDecimal("10000"), Instant.now());
        when(jdbc.update(eq("INSERT INTO processed_events (event_id, processed_at) VALUES (?, ?) ON CONFLICT DO NOTHING"),
                eq(eventId), any(Timestamp.class))).thenReturn(0);

        AsyncWithdrawalService.ProcessingResult result = service.process(event);

        assertTrue(result.duplicate());
        verify(jdbc).update(eq("INSERT INTO processed_events (event_id, processed_at) VALUES (?, ?) ON CONFLICT DO NOTHING"),
                eq(eventId), any(Timestamp.class));
    }
}
