package dev.hookrelay.dispatcher.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Tek bir HTTP denemesinin kaydi. Silinmez, guncellenmez - sadece eklenir.
 *
 * Bu tablo bir DENETIM IZI. Musteri "webhook gelmedi" dediginde acilan
 * ilk yer burasi olacak: ne zaman, hangi URL'e, kac ms'de, hangi kodu
 * donduk, cevabinin ilk 2000 karakteri neydi.
 */
@Entity
@Table(name = "delivery_attempt",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_attempt_delivery_no",
                columnNames = {"delivery_id", "attempt"}))
public class DeliveryAttempt {

    @Id
    private UUID id;

    @Column(name = "delivery_id", nullable = false)  private UUID deliveryId;
    @Column(name = "endpoint_id", nullable = false)  private UUID endpointId;
    @Column(nullable = false)                        private int attempt;

    @Column(name = "http_status")                    private Integer httpStatus;
    @Column(name = "latency_ms", nullable = false)   private long latencyMs;
    @Column(length = 1000)                           private String error;

    /** Cevabin ilk 2000 karakteri. Hata ayiklamanin en cok ise yarayan alani. */
    @Column(name = "response_snippet", length = 2000) private String responseSnippet;

    @Column(name = "occurred_at", nullable = false)  private Instant occurredAt;

    protected DeliveryAttempt() {}

    public DeliveryAttempt(UUID deliveryId, UUID endpointId, int attempt, Integer httpStatus,
                           long latencyMs, String error, String responseSnippet) {
        this.id = UUID.randomUUID();
        this.deliveryId = deliveryId;
        this.endpointId = endpointId;
        this.attempt = attempt;
        this.httpStatus = httpStatus;
        this.latencyMs = latencyMs;
        this.error = cut(error, 1000);
        this.responseSnippet = cut(responseSnippet, 2000);
        this.occurredAt = Instant.now();
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.substring(0, Math.min(s.length(), max));
    }

    public UUID getId()              { return id; }
    public UUID getDeliveryId()      { return deliveryId; }
    public UUID getEndpointId()      { return endpointId; }
    public int getAttempt()          { return attempt; }
    public Integer getHttpStatus()   { return httpStatus; }
    public long getLatencyMs()       { return latencyMs; }
    public String getError()         { return error; }
    public String getResponseSnippet(){ return responseSnippet; }
    public Instant getOccurredAt()   { return occurredAt; }
}
