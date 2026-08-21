package dev.hookrelay.dispatcher.delivery.checks;

import dev.hookrelay.common.redis.RedisRateLimiter;
import dev.hookrelay.contracts.DeliveryResult;
import dev.hookrelay.dispatcher.delivery.DeliveryContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Endpoint'in kaldirabilecegi hizdan fazlasini gondermeyelim.
 *
 * Bu, bizim degil MUSTERININ korunmasi. Kucuk bir Rails sunucusu
 * saniyede 10 istek kaldiriyorsa, elimizde 5.000 mesaj birikti diye
 * hepsini bir anda atmak onlari devirir - sonra da 5.000 mesajin
 * hepsini yeniden denemek zorunda kaliriz. Yavas gondermek herkes
 * icin daha hizli bitirir.
 *
 * Deneme hakki yakmaz: hiz siniri musterinin hatasi degil.
 */
@Component
public class RateLimitCheck implements PreflightCheck {

    private final RedisRateLimiter limiter;
    private final Counter throttled;

    public RateLimitCheck(RedisRateLimiter limiter, MeterRegistry meters) {
        this.limiter = limiter;
        this.throttled = Counter.builder("hookrelay.dispatch.rate_limited").register(meters);
    }

    @Override
    public Optional<Block> check(DeliveryContext ctx) {
        int perSecond = ctx.endpoint().rateLimitPerSecond();
        if (perSecond <= 0) return Optional.empty();

        var decision = limiter.tryAcquire(ctx.endpoint().endpointId(), perSecond);
        if (decision.allowed()) return Optional.empty();

        throttled.increment();
        return Optional.of(new Block(
                DeliveryResult.Outcome.RETRYING,
                "Hiz siniri: " + perSecond + "/sn, " + decision.waitMillis() + " ms sonra",
                Duration.ofMillis(Math.max(decision.waitMillis(), 1000)),
                false));
    }

    @Override
    public int order() { return 20; }
}
