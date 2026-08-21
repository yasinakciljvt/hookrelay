package dev.hookrelay.common.redis;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Endpoint bazli, ORNEKLER ARASI PAYLASILAN devre kesici.
 *
 * Neden gerekli: bir musterinin sunucusu tamamen coktuyse ona 50.000 istek
 * daha atmak hem bizim is parcaciklarimizi hem onlarin ayaga kalkma sansini
 * yakar. Devre acilinca teslimatlar HIC DENENMEDEN bir sonraki katmana
 * ertelenir - mesaj kaybolmaz, sadece bosa istek atilmaz.
 */
@Component
public class RedisCircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    public record Verdict(boolean allowed, State state, long failures) {}

    private final StringRedisTemplate redis;
    private final RedisScript<List> script;
    private final CircuitBreakerSettings settings;

    public RedisCircuitBreaker(StringRedisTemplate redis,
                               @Qualifier("circuitBreakerScript") RedisScript<List> script,
                               CircuitBreakerSettings settings) {
        this.redis = redis;
        this.script = script;
        this.settings = settings;
    }

    public Verdict allow(UUID endpointId)    { return run(endpointId, "allow"); }
    public Verdict recordSuccess(UUID id)    { return run(id, "success"); }
    public Verdict recordFailure(UUID id)    { return run(id, "failure"); }

    public State state(UUID endpointId) {
        String s = (String) redis.opsForHash().get(key(endpointId), "state");
        return s == null ? State.CLOSED : State.valueOf(s);
    }

    @SuppressWarnings("unchecked")
    private Verdict run(UUID endpointId, String op) {
        List<Object> r = redis.execute(script,
                List.of(key(endpointId)),
                op,
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(settings.failureThreshold()),
                String.valueOf(settings.openMillis()),
                String.valueOf(settings.halfOpenProbes()),
                String.valueOf(settings.windowMillis()));

        if (r == null || r.size() < 3) return new Verdict(true, State.CLOSED, 0);
        return new Verdict(
                ((Number) r.get(0)).intValue() == 1,
                State.valueOf(String.valueOf(r.get(1))),
                ((Number) r.get(2)).longValue());
    }

    private String key(UUID endpointId) { return "hr:cb:" + endpointId; }
}
