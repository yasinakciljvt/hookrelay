package dev.hookrelay.dispatcher.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hookrelay.contracts.DeliveryTask;
import dev.hookrelay.contracts.Topics;
import dev.hookrelay.dispatcher.delivery.DeliveryProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Ana teslimat tuketicisi.
 *
 * Offset islemden SONRA commit ediliyor, yani "en az bir kez" teslimat.
 * Webhook'ta "iki kez geldi" cozulebilir bir problem (alici
 * X-HookRelay-Id ile ayiklar), "hic gelmedi" cozulemez.
 *
 * Islem basarisiz olsa bile ack ediyoruz: basarisiz teslimat kaybolmuyor,
 * bir retry topic'ine tasiniyor. Ack etmeseydik ayni kayit partition'i
 * bloklardi (head-of-line blocking).
 */
@Component
public class DeliveryConsumer {

    private static final Logger log = LoggerFactory.getLogger(DeliveryConsumer.class);

    private final DeliveryProcessor processor;
    private final ObjectMapper mapper;

    public DeliveryConsumer(DeliveryProcessor processor, ObjectMapper mapper) {
        this.processor = processor;
        this.mapper = mapper;
    }

    @KafkaListener(
            topics = Topics.MESSAGES,
            groupId = "${hookrelay.consumer.group:dispatcher}",
            concurrency = "${hookrelay.consumer.concurrency:6}")
    public void onMessage(String payload, Acknowledgment ack) {
        try {
            DeliveryTask task = mapper.readValue(payload, DeliveryTask.class);
            processor.process(task);
        } catch (Exception e) {
            // Buraya dusmek beklenmeyen bir durum: govde ayristirilamadi
            // ya da altyapi coktu. Mesaji bloklamiyoruz, logluyoruz.
            log.error("Teslimat gorevi islenemedi, atlaniyor: {}", payload, e);
        } finally {
            ack.acknowledge();
        }
    }
}
