package dev.hookrelay.ingestapi.api;

import dev.hookrelay.ingestapi.domain.Message;
import dev.hookrelay.ingestapi.security.ApiKeyFilter;
import dev.hookrelay.ingestapi.service.IngestService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
public class IngestController {

    private final IngestService service;

    public IngestController(IngestService service) {
        this.service = service;
    }

    public record EventRequest(
            @NotBlank @Size(max = 120) String eventType,
            @NotNull Object payload) {}

    public record EventResponse(
            UUID messageId, String eventType, List<UUID> deliveryIds,
            int deliveryCount, boolean duplicate, String status) {}

    public record MessageResponse(
            UUID id, String eventType, String payload, int deliveryCount, Instant createdAt) {

        static MessageResponse from(Message m) {
            return new MessageResponse(m.getId(), m.getEventType(), m.getPayload(),
                    m.getDeliveryCount(), m.getCreatedAt());
        }
    }

    /**
     * 202 Accepted - ve bunun bir tercih oldugunu bilerek.
     *
     * Bu uc, teslimatin BASARILI oldugunu soylemez. Sadece "olayi
     * dayanikli sekilde kaydettim, teslimattan ben sorumluyum" der.
     * Musteri sonucu delivery uclarindan veya kendi webhook'undan ogrenir.
     */
    @PostMapping("/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EventResponse ingest(
            @RequestAttribute(ApiKeyFilter.ATTR_APPLICATION_ID) UUID applicationId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody EventRequest request) {

        var accepted = service.ingest(applicationId, request.eventType(),
                request.payload(), idempotencyKey);

        return new EventResponse(accepted.messageId(), accepted.eventType(),
                accepted.deliveryIds(), accepted.deliveryIds().size(), accepted.duplicate(),
                accepted.duplicate() ? "zaten_kabul_edilmis" : "kuyruga_alindi");
    }

    @GetMapping("/messages")
    public List<MessageResponse> recent(
            @RequestAttribute(ApiKeyFilter.ATTR_APPLICATION_ID) UUID applicationId) {
        return service.recent(applicationId).stream().map(MessageResponse::from).toList();
    }

    @GetMapping("/messages/{id}")
    public ResponseEntity<MessageResponse> get(
            @RequestAttribute(ApiKeyFilter.ATTR_APPLICATION_ID) UUID applicationId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(MessageResponse.from(service.get(applicationId, id)));
    }
}
