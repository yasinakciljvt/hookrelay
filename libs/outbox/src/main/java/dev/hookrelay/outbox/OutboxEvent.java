package dev.hookrelay.outbox;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Yayinlanmayi bekleyen Kafka kaydinin veritabanindaki hali.
 *
 * "repo.save(m); kafka.send(m);" ikilisi atomik degildir: arada surec
 * olurse ya mesaj kaybolur ya hayalet teslimat olur. Iki ayri sistem tek
 * transaction'a giremez (XA/2PC pratikte Kafka ile kullanilmaz).
 *
 * Cozum: Kafka'ya gidecek kaydi ayni transaction'da bu tabloya yaz. Ayri
 * bir surec tarayip basar ve yayinlandi diye isaretler.
 *
 * Bedeli: mesaj en az bir kez gider, dolayisiyla tuketici idempotent
 * olmak zorunda.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    @Id
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "topic", nullable = false, length = 128)
    private String topic;

    /** Kafka partition anahtari. null olabilir ama bizde hep dolu -- sira garantisi buna bagli. */
    @Column(name = "msg_key", length = 128)
    private String msgKey;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** null = henuz yayinlanmadi. Poller bu sutuna bakiyor. */
    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    protected OutboxEvent() {}

    public OutboxEvent(String aggregateType, String aggregateId, String topic,
                       String msgKey, String payload) {
        this.id = UUID.randomUUID();
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.topic = topic;
        this.msgKey = msgKey;
        this.payload = payload;
        this.createdAt = Instant.now();
        this.attempts = 0;
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
        this.lastError = null;
    }

    public void markFailed(String error) {
        this.attempts++;
        this.lastError = error == null ? null
                : error.substring(0, Math.min(error.length(), 1000));
    }

    public UUID getId()            { return id; }
    public String getTopic()       { return topic; }
    public String getMsgKey()      { return msgKey; }
    public String getPayload()     { return payload; }
    public Instant getCreatedAt()  { return createdAt; }
    public Instant getPublishedAt(){ return publishedAt; }
    public int getAttempts()       { return attempts; }
    public String getAggregateId() { return aggregateId; }
}
