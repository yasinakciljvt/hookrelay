package dev.hookrelay.adminapi.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Bir musterinin webhook alacagi URL. */
@Entity
@Table(name = "endpoint")
public class Endpoint {

    @Id
    private UUID id;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(nullable = false, length = 2000)
    private String url;

    /** Bu endpoint'e giden isteklerin HMAC anahtari. Her endpoint'in kendine ait. */
    @Column(nullable = false, length = 128)
    private String secret;

    @Column(length = 255)
    private String description;

    /**
     * Abone olunan olay tipleri, virgulle ayrilmis. "*" = hepsi.
     *
     * Neden ayri tablo degil: burada iliskisel sorgu yapmiyoruz, bu alan
     * her zaman endpoint'le birlikte okunuyor ve compacted topic'e
     * butun halinde gidiyor. Ayri tablo sadece bir JOIN maliyeti eklerdi.
     */
    @Column(name = "event_types", nullable = false, length = 2000)
    private String eventTypes;

    @Column(nullable = false)
    private boolean enabled = true;

    /** 0 = sinirsiz. Yavas musteriyi bogmamak icin. */
    @Column(name = "rate_limit_per_second", nullable = false)
    private int rateLimitPerSecond;

    /** Kac denemeden sonra DLQ. Katman sayisi + 1'i gecemez. */
    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 6;

    @Column(name = "timeout_ms", nullable = false)
    private int timeoutMs = 10_000;

    @Column(nullable = false)
    private long version = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Endpoint() {}

    public Endpoint(UUID applicationId, String url, String secret, String description,
                    Set<String> eventTypes, int rateLimitPerSecond,
                    int maxAttempts, int timeoutMs) {
        this.id = UUID.randomUUID();
        this.applicationId = applicationId;
        this.url = url;
        this.secret = secret;
        this.description = description;
        this.eventTypes = String.join(",", eventTypes);
        this.enabled = true;
        this.rateLimitPerSecond = rateLimitPerSecond;
        this.maxAttempts = maxAttempts;
        this.timeoutMs = timeoutMs;
        this.version = 1;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void update(String url, String description, Set<String> eventTypes,
                       Boolean enabled, Integer rateLimitPerSecond,
                       Integer maxAttempts, Integer timeoutMs) {
        if (url != null) this.url = url;
        if (description != null) this.description = description;
        if (eventTypes != null && !eventTypes.isEmpty()) this.eventTypes = String.join(",", eventTypes);
        if (enabled != null) this.enabled = enabled;
        if (rateLimitPerSecond != null) this.rateLimitPerSecond = rateLimitPerSecond;
        if (maxAttempts != null) this.maxAttempts = maxAttempts;
        if (timeoutMs != null) this.timeoutMs = timeoutMs;
        this.version++;
        this.updatedAt = Instant.now();
    }

    public Set<String> eventTypeSet() {
        Set<String> out = new LinkedHashSet<>();
        for (String s : eventTypes.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public UUID getId()                { return id; }
    public UUID getApplicationId()     { return applicationId; }
    public String getUrl()             { return url; }
    public String getSecret()          { return secret; }
    public String getDescription()     { return description; }
    public String getEventTypes()      { return eventTypes; }
    public boolean isEnabled()         { return enabled; }
    public int getRateLimitPerSecond() { return rateLimitPerSecond; }
    public int getMaxAttempts()        { return maxAttempts; }
    public int getTimeoutMs()          { return timeoutMs; }
    public long getVersion()           { return version; }
    public Instant getCreatedAt()      { return createdAt; }
    public Instant getUpdatedAt()      { return updatedAt; }
}
