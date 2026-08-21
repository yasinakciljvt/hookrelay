package dev.hookrelay.common.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hookrelay.contracts.ApplicationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** Uygulama (musteri) konfigurasyonunun yerel replikasi. Bkz. EndpointConfigCache. */
@Component
public class AppConfigCache {

    private static final Logger log = LoggerFactory.getLogger(AppConfigCache.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public AppConfigCache(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    private String key(UUID id)            { return "hr:app:" + id + ":cfg"; }
    private String byKeyHash(String hash)  { return "hr:apikey:" + hash; }

    public void put(ApplicationConfig cfg) {
        try {
            if (cfg.deleted()) {
                remove(cfg);
                return;
            }
            redis.opsForValue().set(key(cfg.applicationId()), mapper.writeValueAsString(cfg));
            // Ters indeks: API anahtarindan uygulamaya O(1). ingest'in sicak yolu bu.
            redis.opsForValue().set(byKeyHash(cfg.apiKeyHash()), cfg.applicationId().toString());
        } catch (Exception e) {
            log.error("Uygulama konfigurasyonu yazilamadi: {}", cfg.applicationId(), e);
        }
    }

    public void remove(ApplicationConfig cfg) {
        redis.delete(key(cfg.applicationId()));
        if (cfg.apiKeyHash() != null) redis.delete(byKeyHash(cfg.apiKeyHash()));
    }

    public Optional<ApplicationConfig> get(UUID id) {
        String json = redis.opsForValue().get(key(id));
        if (json == null) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(json, ApplicationConfig.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** API anahtarinin SHA-256 hash'inden uygulamayi bul. */
    public Optional<ApplicationConfig> findByApiKeyHash(String hash) {
        String id = redis.opsForValue().get(byKeyHash(hash));
        if (id == null) return Optional.empty();
        return get(UUID.fromString(id));
    }
}
