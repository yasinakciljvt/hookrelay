package dev.hookrelay.common.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hookrelay.contracts.EndpointConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Endpoint konfigurasyonunun yerel kopyasi.
 *
 * Bu sinifin varlik sebebi tek bir cumle: dispatcher bir mesaji gonderirken
 * admin-api'ye HTTP atmamali. Atarsa saniyede binlerce istek control plane'e
 * yagar, admin-api coktugunde teslimat da durur ve iki servis birbirine
 * yeniden yapisir.
 *
 * Veriyi kim dolduruyor: EndpointConfigReplicator, compacted Kafka
 * topic'ini dinleyerek. Yani bu bir "cache" degil, bir REPLIKA -
 * kacirilan guncelleme yok, TTL yok, invalidation problemi yok.
 */
@Component
public class EndpointConfigCache {

    private static final Logger log = LoggerFactory.getLogger(EndpointConfigCache.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public EndpointConfigCache(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    private String epKey(UUID endpointId)  { return "hr:ep:" + endpointId; }
    private String appKey(UUID appId)      { return "hr:app:" + appId + ":eps"; }

    public void put(EndpointConfig cfg) {
        try {
            if (cfg.deleted()) {
                remove(cfg.applicationId(), cfg.endpointId());
                return;
            }
            redis.opsForValue().set(epKey(cfg.endpointId()), mapper.writeValueAsString(cfg));
            redis.opsForSet().add(appKey(cfg.applicationId()), cfg.endpointId().toString());
        } catch (Exception e) {
            log.error("Endpoint konfigurasyonu yazilamadi: {}", cfg.endpointId(), e);
        }
    }

    public void remove(UUID applicationId, UUID endpointId) {
        redis.delete(epKey(endpointId));
        redis.opsForSet().remove(appKey(applicationId), endpointId.toString());
    }

    public Optional<EndpointConfig> get(UUID endpointId) {
        String json = redis.opsForValue().get(epKey(endpointId));
        if (json == null) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(json, EndpointConfig.class));
        } catch (Exception e) {
            log.error("Endpoint konfigurasyonu okunamadi: {}", endpointId, e);
            return Optional.empty();
        }
    }

    /**
     * Fan-out'un temeli: bir uygulamanin butun endpoint'leri.
     *
     * TEK MGET, N AYRI GET DEGIL
     * Ilk yazilisi kume uyelerini alip her biri icin ayri GET yapiyordu.
     * 6 endpoint = 7 Redis gidis-donusu, ve bu KABUL ISTEGININ SICAK
     * YOLUNDA. Her gidis-donus ~0,2 ms olsa bile 100 olay/sn'de saniyede
     * 700 gidis-donus demek.
     *
     * MGET hepsini tek komutta getirir: 2 gidis-donus (SMEMBERS + MGET),
     * endpoint sayisindan bagimsiz.
     *
     * Bu, N+1 probleminin Redis'teki hali. Veritabaninda herkesin bildigi
     * bu tuzak, onbellekte cogu zaman gozden kaciyor.
     */
    public List<EndpointConfig> listByApplication(UUID applicationId) {
        Set<String> ids = redis.opsForSet().members(appKey(applicationId));
        if (ids == null || ids.isEmpty()) return List.of();

        List<String> keys = ids.stream().map(id -> epKey(UUID.fromString(id))).toList();
        List<String> payloads = redis.opsForValue().multiGet(keys);
        if (payloads == null) return List.of();

        List<EndpointConfig> out = new ArrayList<>(payloads.size());
        for (String json : payloads) {
            if (json == null) continue;   // kume ile deger arasinda yaris - atla
            try {
                out.add(mapper.readValue(json, EndpointConfig.class));
            } catch (Exception e) {
                log.error("Endpoint konfigurasyonu ayristirilamadi", e);
            }
        }
        return out;
    }
}
