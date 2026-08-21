package dev.hookrelay.dispatcher.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hookrelay.common.error.ApiException;
import dev.hookrelay.contracts.DeliveryTask;
import dev.hookrelay.contracts.Topics;
import dev.hookrelay.dispatcher.domain.Delivery;
import dev.hookrelay.dispatcher.repo.DeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * YENIDEN GONDERIM (replay).
 *
 * DLQ'nun otomatik tuketicisi yok - olmamali da. Ama insanin
 * mudahale edebilecegi bir kapi OLMAK ZORUNDA, yoksa DLQ bir cop
 * kutusuna donusur.
 *
 * Tipik senaryo: musterinin sunucusu 3 saat cokmus, 8.000 teslimat
 * tukenip DLQ'ya dusmus. Sunucu geri geldi. Bir dugmeye basip hepsini
 * yeniden kuyruga koyabilmek gerekiyor.
 *
 * Yeniden gonderimde attempt 1'e ve firstQueuedAt su ana sifirlanir:
 * teslimat, taze bir teslimat gibi yeniden dogar. Aksi halde eski
 * yasam suresi kontrolune takilip aninda DLQ'ya geri duserdi.
 */
@RestController
@RequestMapping("/api/deliveries")
public class ReplayController {

    private static final Logger log = LoggerFactory.getLogger(ReplayController.class);

    private final DeliveryRepository deliveries;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;

    public ReplayController(DeliveryRepository deliveries, KafkaTemplate<String, String> kafka,
                            ObjectMapper mapper) {
        this.deliveries = deliveries;
        this.kafka = kafka;
        this.mapper = mapper;
    }

    @PostMapping("/{id}/replay")
    @Transactional
    public Map<String, Object> replay(@PathVariable UUID id) {
        Delivery d = deliveries.findById(id)
                .orElseThrow(() -> ApiException.notFound("Teslimat", id));
        requeue(d);
        return Map.of("deliveryId", id, "status", "yeniden_kuyruga_alindi");
    }

    /** Bir endpoint'in butun tukenmis teslimatlarini yeniden kuyruga koyar. */
    @PostMapping("/replay-exhausted")
    @Transactional
    public Map<String, Object> replayExhausted(
            @RequestParam(required = false) UUID endpointId,
            @RequestParam(defaultValue = "500") int limit) {

        PageRequest page = PageRequest.of(0, Math.min(limit, 5000));

        // Filtreleme VERITABANINDA. Once sayfalayip sonra Java'da
        // filtrelemek, limit'i yalan soyleyen bir sayiya cevirir.
        List<Delivery> targets = endpointId == null
                ? deliveries.findByStatusOrderByUpdatedAtDesc(Delivery.Status.EXHAUSTED, page)
                            .getContent()
                : deliveries.findByEndpointIdAndStatusOrderByUpdatedAtDesc(
                            endpointId, Delivery.Status.EXHAUSTED, page);

        targets.forEach(this::requeue);
        log.info("Toplu yeniden gonderim: {} teslimat", targets.size());
        return Map.of("requeued", targets.size());
    }

    private void requeue(Delivery d) {
        if (d.getPayload() == null) {
            throw ApiException.badRequest(
                    "Bu teslimatin govdesi saklanmamis, yeniden gonderilemez: " + d.getId());
        }
        DeliveryTask task = new DeliveryTask(
                d.getId(), d.getMessageId(), d.getApplicationId(), d.getEndpointId(),
                d.getEventType(), d.getPayload(), 1, Instant.now());

        d.resetForReplay();
        deliveries.save(d);

        try {
            kafka.send(Topics.MESSAGES, d.getEndpointId().toString(),
                    mapper.writeValueAsString(task));
        } catch (Exception e) {
            throw new IllegalStateException("Yeniden gonderim yayinlanamadi", e);
        }
    }
}
