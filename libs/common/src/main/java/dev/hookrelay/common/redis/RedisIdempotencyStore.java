package dev.hookrelay.common.redis;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Idempotency-Key deposu.
 *
 * Kullanim: "bu anahtari ilk kez mi goruyorum?" Cevap hayirsa daha once
 * dondurulmus cevabi aynen tekrar dondur - istemcinin gozunde islem
 * bir kez olmus gibi gorunur.
 */
@Component
public class RedisIdempotencyStore {

    private final StringRedisTemplate redis;
    private final RedisScript<List> script;

    public RedisIdempotencyStore(StringRedisTemplate redis,
                                 @Qualifier("idempotencyScript") RedisScript<List> script) {
        this.redis = redis;
        this.script = script;
    }

    /** Bos Optional = anahtar ilk kez goruldu ve rezerve edildi. */
    @SuppressWarnings("unchecked")
    public Optional<String> reserveOrGet(String scope, String key, String value, Duration ttl) {
        List<Object> r = redis.execute(script,
                List.of("hr:idem:" + scope + ":" + key),
                value,
                String.valueOf(ttl.toMillis()));

        if (r == null || r.size() < 2) return Optional.empty();
        boolean isNew = ((Number) r.get(0)).intValue() == 1;
        return isNew ? Optional.empty() : Optional.of(String.valueOf(r.get(1)));
    }

    /** Rezervasyondan sonra gercek cevabi yerine koymak icin. */
    public void put(String scope, String key, String value, Duration ttl) {
        redis.opsForValue().set("hr:idem:" + scope + ":" + key, value, ttl);
    }
}
