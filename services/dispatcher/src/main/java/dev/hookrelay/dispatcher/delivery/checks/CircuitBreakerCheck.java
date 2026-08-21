package dev.hookrelay.dispatcher.delivery.checks;

import dev.hookrelay.common.redis.RedisCircuitBreaker;
import dev.hookrelay.contracts.DeliveryResult;
import dev.hookrelay.dispatcher.delivery.DeliveryContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Musteri coktuyse ona 50.000 istek daha atmayalim.
 *
 * ONEMLI: engelleme bir DENEME HAKKI YAKMAZ (consumesAttempt = false).
 * Sebep adil olmak: musteri 5 dakika bakimda diye teslimatin butun
 * haklarini tuketmesi, bakim bitince mesajlarin coktan DLQ'ya dusmus
 * olmasi demekti. Suc bizde degil, onlarda da degil - sadece zamanlama.
 *
 * Sonsuz donguyu ne engelliyor: RetryPolicy.expired() - teslimatin
 * toplam yasam suresi (varsayilan 24 saat). O dolunca DLQ.
 */
@Component
public class CircuitBreakerCheck implements PreflightCheck {

    private final RedisCircuitBreaker breaker;
    private final Counter blocked;

    public CircuitBreakerCheck(RedisCircuitBreaker breaker, MeterRegistry meters) {
        this.breaker = breaker;
        this.blocked = Counter.builder("hookrelay.dispatch.short_circuited").register(meters);
    }

    @Override
    public Optional<Block> check(DeliveryContext ctx) {
        var verdict = breaker.allow(ctx.endpoint().endpointId());
        if (verdict.allowed()) return Optional.empty();

        blocked.increment();
        return Optional.of(new Block(
                DeliveryResult.Outcome.SHORT_CIRCUITED,
                "Devre kesici acik (durum=" + verdict.state() + ", hata=" + verdict.failures() + ")",
                Duration.ofSeconds(30),
                false));
    }

    @Override
    public int order() { return 10; }
}
