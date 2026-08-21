package dev.hookrelay.ingestapi.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Musterinin gonderdigi MANTIKSAL olay.
 *
 * Dikkat: bu bir teslimat degil. Bir olay 3 endpoint'e gidiyorsa
 * 1 Message + 3 DeliveryTask olur. Ayrimi kaybetmek, sonradan
 * "kac olay geldi" ile "kac istek attik" sorularini birbirine karistirir.
 */
@Entity
@Table(name = "message",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_message_idempotency",
                columnNames = {"application_id", "idempotency_key"}))
public class Message {

    @Id
    private UUID id;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(name = "event_type", nullable = false, length = 120)
    private String eventType;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    /**
     * Musterinin verdigi Idempotency-Key. NULL olabilir (istege bagli).
     *
     * Postgres'te NULL'lar birbirine esit sayilmaz, yani UNIQUE kisiti
     * anahtar vermeyen istekleri engellemez. Tam istedigimiz davranis.
     */
    @Column(name = "idempotency_key", length = 200)
    private String idempotencyKey;

    /** Fan-out sonucu kac teslimat uretildigi. Gozlem icin. */
    @Column(name = "delivery_count", nullable = false)
    private int deliveryCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Message() {}

    public Message(UUID applicationId, String eventType, String payload, String idempotencyKey) {
        this.id = UUID.randomUUID();
        this.applicationId = applicationId;
        this.eventType = eventType;
        this.payload = payload;
        this.idempotencyKey = idempotencyKey;
        this.deliveryCount = 0;
        this.createdAt = Instant.now();
    }

    public void setDeliveryCount(int deliveryCount) { this.deliveryCount = deliveryCount; }

    public UUID getId()             { return id; }
    public UUID getApplicationId()  { return applicationId; }
    public String getEventType()    { return eventType; }
    public String getPayload()      { return payload; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public int getDeliveryCount()   { return deliveryCount; }
    public Instant getCreatedAt()   { return createdAt; }
}
