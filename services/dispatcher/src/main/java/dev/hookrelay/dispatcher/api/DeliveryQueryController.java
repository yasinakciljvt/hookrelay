package dev.hookrelay.dispatcher.api;

import dev.hookrelay.common.error.ApiException;
import dev.hookrelay.dispatcher.domain.Delivery;
import dev.hookrelay.dispatcher.domain.DeliveryAttempt;
import dev.hookrelay.dispatcher.repo.DeliveryAttemptRepository;
import dev.hookrelay.dispatcher.repo.DeliveryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Teslimat gunlugu.
 *
 * "Webhook gelmedi" diyen musteriye bakilacak ilk yer. Her denemenin
 * ne zaman yapildigi, hangi kodun dondugu, cevabin ilk 2000 karakteri.
 * Bu uclar olmadan sistem bir kara kutudur.
 */
@RestController
@RequestMapping("/api/deliveries")
public class DeliveryQueryController {

    private final DeliveryRepository deliveries;
    private final DeliveryAttemptRepository attempts;

    public DeliveryQueryController(DeliveryRepository deliveries,
                                   DeliveryAttemptRepository attempts) {
        this.deliveries = deliveries;
        this.attempts = attempts;
    }

    public record DeliveryView(
            UUID id, UUID messageId, UUID endpointId, String eventType, String status,
            int attempts, Integer lastStatus, String lastError,
            Instant nextAttemptAt, Instant createdAt, Instant updatedAt) {

        static DeliveryView from(Delivery d) {
            return new DeliveryView(d.getId(), d.getMessageId(), d.getEndpointId(),
                    d.getEventType(), d.getStatus().name(), d.getAttempts(),
                    d.getLastStatus(), d.getLastError(), d.getNextAttemptAt(),
                    d.getCreatedAt(), d.getUpdatedAt());
        }
    }

    public record AttemptView(
            int attempt, Integer httpStatus, long latencyMs, String error,
            String responseSnippet, Instant occurredAt) {

        static AttemptView from(DeliveryAttempt a) {
            return new AttemptView(a.getAttempt(), a.getHttpStatus(), a.getLatencyMs(),
                    a.getError(), a.getResponseSnippet(), a.getOccurredAt());
        }
    }

    public record DeliveryDetail(DeliveryView delivery, String payload, List<AttemptView> attempts) {}

    @GetMapping
    public Page<DeliveryView> list(
            @RequestParam(required = false) UUID endpointId,
            @RequestParam(required = false) UUID applicationId,
            @RequestParam(required = false) Delivery.Status status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 200));

        if (endpointId != null) {
            return deliveries.findByEndpointIdOrderByCreatedAtDesc(endpointId, pageable)
                    .map(DeliveryView::from);
        }
        if (applicationId != null) {
            return deliveries.findByApplicationIdOrderByCreatedAtDesc(applicationId, pageable)
                    .map(DeliveryView::from);
        }
        if (status != null) {
            return deliveries.findByStatusOrderByUpdatedAtDesc(status, pageable)
                    .map(DeliveryView::from);
        }
        return deliveries.findAll(pageable).map(DeliveryView::from);
    }

    @GetMapping("/{id}")
    public DeliveryDetail get(@PathVariable UUID id) {
        Delivery d = deliveries.findById(id)
                .orElseThrow(() -> ApiException.notFound("Teslimat", id));
        List<AttemptView> list = attempts.findByDeliveryIdOrderByAttemptAsc(id)
                .stream().map(AttemptView::from).toList();
        return new DeliveryDetail(DeliveryView.from(d), d.getPayload(), list);
    }

    @GetMapping("/stats")
    public Map<String, Long> stats() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Delivery.Status s : Delivery.Status.values()) out.put(s.name(), 0L);
        for (Object[] row : deliveries.countGroupedByStatus()) {
            out.put(((Delivery.Status) row[0]).name(), (Long) row[1]);
        }
        return out;
    }
}
