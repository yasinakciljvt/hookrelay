package dev.hookrelay.adminapi.service;

import dev.hookrelay.adminapi.domain.Endpoint;
import dev.hookrelay.adminapi.repo.EndpointRepository;
import dev.hookrelay.common.error.ApiException;
import dev.hookrelay.contracts.EndpointConfig;
import dev.hookrelay.contracts.Topics;
import dev.hookrelay.outbox.OutboxRecorder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class EndpointService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final EndpointRepository repository;
    private final ApplicationService applications;
    private final OutboxRecorder outbox;
    private final int tierCount;

    public EndpointService(EndpointRepository repository, ApplicationService applications,
                           OutboxRecorder outbox) {
        this.repository = repository;
        this.applications = applications;
        this.outbox = outbox;
        this.tierCount = Topics.tierCount();
    }

    @Transactional
    public Endpoint create(UUID applicationId, String url, String description,
                           Set<String> eventTypes, Integer rateLimitPerSecond,
                           Integer maxAttempts, Integer timeoutMs, String secret) {
        applications.get(applicationId);   // uygulama yoksa 404
        validateUrl(url);

        int attempts = clampAttempts(maxAttempts == null ? tierCount + 1 : maxAttempts);
        Endpoint endpoint = new Endpoint(
                applicationId, url,
                secret == null || secret.isBlank() ? generateSecret() : secret,
                description,
                eventTypes == null || eventTypes.isEmpty() ? Set.of("*") : eventTypes,
                rateLimitPerSecond == null ? 0 : rateLimitPerSecond,
                attempts,
                timeoutMs == null ? 10_000 : timeoutMs);

        repository.save(endpoint);
        publish(endpoint, false);
        return endpoint;
    }

    @Transactional
    public Endpoint update(UUID id, String url, String description, Set<String> eventTypes,
                           Boolean enabled, Integer rateLimitPerSecond,
                           Integer maxAttempts, Integer timeoutMs) {
        Endpoint endpoint = get(id);
        if (url != null) validateUrl(url);
        endpoint.update(url, description, eventTypes, enabled, rateLimitPerSecond,
                maxAttempts == null ? null : clampAttempts(maxAttempts), timeoutMs);
        publish(endpoint, false);
        return endpoint;
    }

    /**
     * Silme = compacted topic'e deleted=true basmak.
     *
     * Neden tombstone (null govde) degil: tombstone'u alan tuketici
     * "silindi mi yoksa sema mi bozuk" ayrimini yapamaz ve hangi
     * uygulamaya ait oldugunu bilemez (key sadece endpointId). Acik
     * bir deleted bayragi tasimak hem okunakli hem hata ayiklanabilir.
     */
    @Transactional
    public void delete(UUID id) {
        Endpoint endpoint = get(id);
        publish(endpoint, true);
        repository.delete(endpoint);
    }

    @Transactional
    public int republishAll() {
        List<Endpoint> all = repository.findAll();
        all.forEach(e -> publish(e, false));
        return all.size();
    }

    @Transactional(readOnly = true)
    public List<Endpoint> listByApplication(UUID applicationId) {
        return repository.findByApplicationIdOrderByCreatedAtDesc(applicationId);
    }

    @Transactional(readOnly = true)
    public List<Endpoint> listAll() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public Endpoint get(UUID id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("Endpoint", id));
    }

    private void publish(Endpoint e, boolean deleted) {
        outbox.record("endpoint", e.getId().toString(),
                Topics.ENDPOINT_CONFIG, e.getId().toString(),
                new EndpointConfig(e.getId(), e.getApplicationId(), e.getUrl(), e.getSecret(),
                        e.eventTypeSet(), e.isEnabled(), deleted, e.getRateLimitPerSecond(),
                        e.getMaxAttempts(), e.getTimeoutMs(), e.getVersion()));
    }

    /**
     * maxAttempts katman sayisini asamaz: 5 katman varsa 6. deneme
     * yapilacak bir topic yok. Sessizce tavana kirpiyoruz - kullanici
     * 99 yazsa bile sistem tutarli kalir.
     */
    private int clampAttempts(int requested) {
        return Math.max(1, Math.min(requested, tierCount + 1));
    }

    private void validateUrl(String url) {
        if (url == null || url.isBlank()) throw ApiException.badRequest("url bos olamaz");
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw ApiException.badRequest("url http:// veya https:// ile baslamali");
        }
    }

    private String generateSecret() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return "whsec_" + HexFormat.of().formatHex(raw);
    }
}
