package dev.hookrelay.dispatcher.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hookrelay.common.kafka.KafkaHeaderCodec;
import dev.hookrelay.contracts.DeliveryResult;
import dev.hookrelay.contracts.DeliveryTask;
import dev.hookrelay.contracts.Headers;
import dev.hookrelay.contracts.Topics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Kafka'ya cikan her sey buradan gecer: yeniden deneme, DLQ, sonuc. */
@Component
public class DeliveryPublisher {

    private static final Logger log = LoggerFactory.getLogger(DeliveryPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final Counter rescheduled;
    private final Counter deadLettered;

    public DeliveryPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper,
                             MeterRegistry meters) {
        this.kafka = kafka;
        this.mapper = mapper;
        this.rescheduled = Counter.builder("hookrelay.dispatch.rescheduled").register(meters);
        this.deadLettered = Counter.builder("hookrelay.dispatch.dead_lettered").register(meters);
    }

    /**
     * Teslimati bir yeniden deneme katmanina birakir.
     *
     * KEY DEGISMIYOR: hala endpointId. Katman topic'i icinde de ayni
     * endpoint'in kayitlari ayni partition'a duser, sira korunur.
     *
     * NOT_BEFORE basligi kritik: zamanlayici, topic'in adina degil
     * BU BASLIGA bakar. Sayesinde Retry-After gibi topic gecikmesinden
     * farkli bir bekleme suresi de temsil edilebiliyor.
     */
    public void reschedule(DeliveryTask task, int tier, Instant notBefore, int nextAttempt) {
        String topic = Topics.retryTier(tier);
        DeliveryTask next = task.withAttempt(nextAttempt);

        ProducerRecord<String, String> record = new ProducerRecord<>(
                topic, task.endpointId().toString(), toJson(next));
        record.headers()
                .add(Headers.NOT_BEFORE, KafkaHeaderCodec.of(notBefore.toEpochMilli()))
                .add(Headers.ATTEMPT, KafkaHeaderCodec.of(nextAttempt));

        kafka.send(record);
        rescheduled.increment();
        log.debug("Yeniden zamanlandi: delivery={} katman=t{} not-before={}",
                task.deliveryId(), tier, notBefore);
    }

    /**
     * DLQ'ya birakir.
     *
     * DLQ'nun tuketicisi YOK ve olmamali. Buraya dusen mesaj bir INSAN
     * bakana kadar bekler. Otomatik tuketen bir DLQ, DLQ degil bir
     * dongudur - mesaj olur, geri gelir, yine olur.
     */
    public void deadLetter(DeliveryTask task, String reason) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                Topics.DLQ, task.endpointId().toString(), toJson(task));
        record.headers()
                .add(Headers.DEAD_REASON, KafkaHeaderCodec.of(reason))
                .add(Headers.ATTEMPT, KafkaHeaderCodec.of(task.attempt()));

        kafka.send(record);
        deadLettered.increment();
        log.warn("DLQ: delivery={} endpoint={} sebep={}",
                task.deliveryId(), task.endpointId(), reason);
    }

    /** Sonuc yayini. Kimin dinledigi dispatcher'i ilgilendirmez. */
    public void publishResult(DeliveryResult result) {
        kafka.send(Topics.DELIVERY_RESULTS, result.endpointId().toString(), toJson(result));
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("Kafka govdesi serilestirilemedi", e);
        }
    }
}
