package dev.hookrelay.common.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Devre kesici ayarlari. Hepsinin makul varsayilani var - servis
 * application.yml'inde hic tanimlamasa da calisir.
 */
@ConfigurationProperties(prefix = "hookrelay.circuit-breaker")
public record CircuitBreakerSettings(
        Integer failureThreshold,
        Long openMillis,
        Integer halfOpenProbes,
        Long windowMillis
) {
    public CircuitBreakerSettings {
        if (failureThreshold == null || failureThreshold <= 0) failureThreshold = 5;
        if (openMillis == null || openMillis <= 0) openMillis = 30_000L;
        if (halfOpenProbes == null || halfOpenProbes <= 0) halfOpenProbes = 2;
        if (windowMillis == null || windowMillis <= 0) windowMillis = 60_000L;
    }
}
