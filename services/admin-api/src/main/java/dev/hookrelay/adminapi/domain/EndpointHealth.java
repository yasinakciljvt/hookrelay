package dev.hookrelay.adminapi.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * PROJEKSIYON - bu tabloyu hicbir kullanici yazmaz.
 *
 * Tamami hookrelay.delivery-results.v1 topic'i dinlenerek turetilir.
 * Silinip topic bastan okunsa aynen yeniden olusur. CQRS'in okuma tarafi
 * tam olarak budur: yazma modeli (dispatcher'daki delivery_attempt)
 * ile okuma modeli (bu tablo) ayri sekilde, ayri servislerde, ayri
 * amaclar icin tutulur.
 *
 * Kazanci somut: arayuzde "bu endpoint saglikli mi" sorusu 1 satirlik
 * bir SELECT. Ayni soruyu delivery_attempt uzerinde sormak milyonlarca
 * satiri taramak demekti.
 */
@Entity
@Table(name = "endpoint_health")
public class EndpointHealth {

    @Id
    @Column(name = "endpoint_id")
    private UUID endpointId;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(nullable = false) private long succeeded;
    @Column(nullable = false) private long failed;

    /**
     * Devre kesici / hiz siniri yuzunden HIC ATILMAYAN istekler.
     *
     * failed'dan ayri tutuluyor cunku bunlar musterinin hatasi degil,
     * bizim kararimiz. Ayni kovaya koymak "basari orani" metrigini
     * anlamsiz yapardi: devresi acik bir endpoint, hicbir istek
     * almadigi halde saniyede yuzlerce "hata" biriktirirdi.
     */
    @Column(name = "short_circuited", nullable = false) private long shortCircuited;
    @Column(name = "consecutive_failures", nullable = false) private int consecutiveFailures;

    @Column(name = "last_status")        private Integer lastStatus;
    @Column(name = "last_error", length = 500) private String lastError;
    @Column(name = "last_latency_ms")    private Long lastLatencyMs;
    @Column(name = "last_delivery_at")   private Instant lastDeliveryAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected EndpointHealth() {}

    public EndpointHealth(UUID endpointId, UUID applicationId) {
        this.endpointId = endpointId;
        this.applicationId = applicationId;
        this.updatedAt = Instant.now();
    }

    public void recordSuccess(Integer status, long latencyMs, Instant at) {
        this.succeeded++;
        this.consecutiveFailures = 0;
        this.lastStatus = status;
        this.lastError = null;
        this.lastLatencyMs = latencyMs;
        this.lastDeliveryAt = at;
        this.updatedAt = Instant.now();
    }

    public void recordFailure(Integer status, long latencyMs, String error, Instant at) {
        this.failed++;
        this.consecutiveFailures++;
        this.lastStatus = status;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
        this.lastLatencyMs = latencyMs;
        this.lastDeliveryAt = at;
        this.updatedAt = Instant.now();
    }

    /** Istek atilmadi; sadece atlanan sayisini artir. */
    public void recordShortCircuit(Instant at) {
        this.shortCircuited++;
        this.lastDeliveryAt = at;
        this.updatedAt = Instant.now();
    }

    public double successRate() {
        long total = succeeded + failed;
        return total == 0 ? 1.0 : (double) succeeded / total;
    }

    public UUID getEndpointId()      { return endpointId; }
    public UUID getApplicationId()   { return applicationId; }
    public long getSucceeded()       { return succeeded; }
    public long getFailed()          { return failed; }
    public long getShortCircuited()  { return shortCircuited; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public Integer getLastStatus()   { return lastStatus; }
    public String getLastError()     { return lastError; }
    public Long getLastLatencyMs()   { return lastLatencyMs; }
    public Instant getLastDeliveryAt() { return lastDeliveryAt; }
    public Instant getUpdatedAt()    { return updatedAt; }
}
