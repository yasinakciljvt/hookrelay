package dev.hookrelay.ingestapi.service;

import dev.hookrelay.common.redis.EndpointConfigCache;
import dev.hookrelay.contracts.DeliveryTask;
import dev.hookrelay.contracts.EndpointConfig;
import dev.hookrelay.contracts.Topics;
import dev.hookrelay.ingestapi.domain.Message;
import dev.hookrelay.ingestapi.repo.MessageRepository;
import dev.hookrelay.outbox.OutboxRecorder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Yazma islemi neden AYRI BIR BEAN'de?
 *
 * @Transactional Spring'de proxy ile calisir: cagri once proxy'ye gelir,
 * proxy transaction'i acar, sonra gercek nesneye gecer. AYNI SINIF ICINDEN
 * yapilan cagri (this.store(...)) proxy'ye ugramaz - dogrudan metoda gider
 * ve transaction HIC ACILMAZ. Anotasyon orada durur, hicbir sey yapmaz.
 *
 * Bu, Spring'in en sik dusulen tuzagi. Burada acikca kaciniyoruz:
 * transaction sinirini ayri bir bean'e tasiyoruz ki cagri gercekten
 * proxy uzerinden gecsin.
 *
 * (Bizim durumumuzda hata sessiz de kalmazdi: OutboxRecorder
 * Propagation.MANDATORY kullaniyor ve transaction yoksa patliyor.
 * Bilincli bir erken uyari mekanizmasi.)
 */
@Component
public class MessageWriter {

    private static final Logger log = LoggerFactory.getLogger(MessageWriter.class);

    private final MessageRepository messages;
    private final EndpointConfigCache endpoints;
    private final OutboxRecorder outbox;
    private final Counter fannedOut;

    public MessageWriter(MessageRepository messages, EndpointConfigCache endpoints,
                         OutboxRecorder outbox, MeterRegistry meters) {
        this.messages = messages;
        this.endpoints = endpoints;
        this.outbox = outbox;
        this.fannedOut = Counter.builder("hookrelay.ingest.deliveries.created").register(meters);
    }

    public record Written(Message message, List<UUID> deliveryIds) {}

    /**
     * TEK TRANSACTION: mesaj satiri + N adet outbox satiri.
     * Ya hepsi ya hicbiri. "Mesaj yazildi ama 3 teslimattan 2'si kuyruga
     * girdi" diye bir ara durum yok.
     */
    @Transactional
    public Written store(UUID applicationId, String eventType,
                         String payloadJson, String idempotencyKey) {

        Message message = new Message(applicationId, eventType, payloadJson, idempotencyKey);

        // FAN-OUT BURADA - dispatcher'da degil.
        //
        // Neden: Kafka kaydinin key'i endpointId olmali ki "ayni endpoint'e
        // sirali teslimat" garantisi partition'dan bedava gelsin. Fan-out'u
        // dispatcher'a birakirsak key olarak messageId kullanmak zorunda
        // kalirdik ve o garanti kaybolurdu.
        List<EndpointConfig> targets = endpoints.listByApplication(applicationId).stream()
                .filter(EndpointConfig::deliverable)
                .filter(c -> c.subscribesTo(eventType))
                .toList();

        List<UUID> deliveryIds = new ArrayList<>(targets.size());
        Instant now = Instant.now();

        for (EndpointConfig target : targets) {
            DeliveryTask task = new DeliveryTask(
                    UUID.randomUUID(), message.getId(), applicationId, target.endpointId(),
                    eventType, payloadJson, 1, now);

            outbox.record("delivery", task.deliveryId().toString(),
                    Topics.MESSAGES, target.endpointId().toString(), task);
            deliveryIds.add(task.deliveryId());
        }

        message.setDeliveryCount(deliveryIds.size());
        messages.save(message);
        fannedOut.increment(deliveryIds.size());

        if (targets.isEmpty()) {
            log.debug("Olay kabul edildi ama abone endpoint yok: app={} type={}",
                    applicationId, eventType);
        }
        return new Written(message, deliveryIds);
    }
}
