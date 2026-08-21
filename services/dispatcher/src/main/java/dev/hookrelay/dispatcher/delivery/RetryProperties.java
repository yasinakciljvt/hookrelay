package dev.hookrelay.dispatcher.delivery;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Yeniden deneme ayarlari.
 *
 * delays listesi katman topic'leriyle BIREBIR eslesir:
 *   delays[0] → hookrelay.retry.t1.v1
 *   delays[1] → hookrelay.retry.t2.v1  ...
 *
 * Uretimde: 10s, 1m, 5m, 30m, 2h  (toplam ~2.5 saatlik kurtarma penceresi)
 * Demo'da:  5s, 15s, 30s, 60s, 120s (docker-compose'da override ediliyor,
 *           yoksa demoyu izlemek 2.5 saat surer)
 */
@ConfigurationProperties(prefix = "hookrelay.retry")
public record RetryProperties(
        List<Duration> delays,
        Duration maxLifetime
) {
    public RetryProperties {
        if (delays == null || delays.isEmpty()) {
            delays = List.of(
                    Duration.ofSeconds(10), Duration.ofMinutes(1), Duration.ofMinutes(5),
                    Duration.ofMinutes(30), Duration.ofHours(2));
        }
        if (maxLifetime == null || maxLifetime.isZero()) {
            maxLifetime = Duration.ofHours(24);
        }
    }
}
