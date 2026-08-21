package dev.hookrelay.common.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Endpoint bazli token bucket. Betigin tamami rate_limit.lua icinde. */
@Component
public class RedisRateLimiter {

    private final StringRedisTemplate redis;
    private final RedisScript<List> script;

    public RedisRateLimiter(StringRedisTemplate redis,
                            @org.springframework.beans.factory.annotation.Qualifier("rateLimitScript")
                            RedisScript<List> script) {
        this.redis = redis;
        this.script = script;
    }

    public record Decision(boolean allowed, long waitMillis) {}

    /**
     * @param perSecond saniyede kac teslimat. 0 = sinirsiz.
     * @return izin ve reddedildiyse ne kadar sonra tekrar denenebilecegi
     */
    @SuppressWarnings("unchecked")
    public Decision tryAcquire(UUID endpointId, int perSecond) {
        if (perSecond <= 0) return new Decision(true, 0);

        // Kapasite = 1 saniyelik hiz, en az 1. Kisa patlamalara izin verir
        // ama ortalama hizi perSecond'da tutar.
        int capacity = Math.max(1, perSecond);

        List<Long> r = redis.execute(script,
                List.of("hr:rl:" + endpointId),
                String.valueOf(capacity),
                String.valueOf(perSecond),
                String.valueOf(System.currentTimeMillis()),
                "1");

        if (r == null || r.size() < 2) return new Decision(true, 0);
        return new Decision(r.get(0) == 1L, r.get(1));
    }
}
