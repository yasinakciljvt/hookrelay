package dev.hookrelay.adminapi.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Bir musteri / kiraci. Olaylari bu kimlikle gonderir.
 * Sinif adi "Application" degil cunku Spring'in kendi Application
 * kavramiyla karisiyor ve import kazalarina yol aciyor.
 */
@Entity
@Table(name = "application")
public class WebhookApplication {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 120)
    private String name;

    /** Anahtarin kendisi degil, SHA-256 hash'i. Bkz. ApiKeys. */
    @Column(name = "api_key_hash", nullable = false, length = 128)
    private String apiKeyHash;

    /** Arayuzde gosterilecek "hr_3f9a..." onizlemesi. Tek basina ise yaramaz. */
    @Column(name = "api_key_preview", nullable = false, length = 32)
    private String apiKeyPreview;

    @Column(nullable = false)
    private boolean enabled = true;

    /** Her degisiklikte artar; compacted topic'te eski/yeni ayrimi icin. */
    @Column(nullable = false)
    private long version = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WebhookApplication() {}

    public WebhookApplication(String name, String apiKeyHash, String apiKeyPreview) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.apiKeyHash = apiKeyHash;
        this.apiKeyPreview = apiKeyPreview;
        this.enabled = true;
        this.version = 1;
        this.createdAt = Instant.now();
    }

    public void rotateKey(String newHash, String newPreview) {
        this.apiKeyHash = newHash;
        this.apiKeyPreview = newPreview;
        this.version++;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        this.version++;
    }

    public UUID getId()             { return id; }
    public String getName()         { return name; }
    public String getApiKeyHash()   { return apiKeyHash; }
    public String getApiKeyPreview(){ return apiKeyPreview; }
    public boolean isEnabled()      { return enabled; }
    public long getVersion()        { return version; }
    public Instant getCreatedAt()   { return createdAt; }
}
