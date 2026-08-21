package dev.hookrelay.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Is mantiginin cagirdigi tek yer.
 *
 * MANDATORY yayilimi bilincli: bu metot CAGIRANIN transaction'i icinde
 * calismak ZORUNDA. Transaction disinda cagrilirsa hata firlatir -
 * cunku transaction yoksa outbox'in tum anlami kaybolur.
 */
@Component
public class OutboxRecorder {

    private final OutboxRepository repository;
    private final ObjectMapper mapper;

    public OutboxRecorder(OutboxRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String aggregateType, String aggregateId,
                       String topic, String msgKey, Object payload) {
        try {
            String json = payload instanceof String s ? s : mapper.writeValueAsString(payload);
            repository.save(new OutboxEvent(aggregateType, aggregateId, topic, msgKey, json));
        } catch (Exception e) {
            throw new IllegalStateException("Outbox kaydi olusturulamadi", e);
        }
    }
}
