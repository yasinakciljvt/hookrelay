package dev.hookrelay.contracts;

import java.util.Set;
import java.util.UUID;

/**
 * hookrelay.endpoint-config.v1 (compacted) govdesi.
 *
 * Bu, dispatcher'in sicak yolda ihtiyac duydugu HER SEYI icerir. Amac tek:
 * dispatcher bir mesaji gonderirken admin-api'ye HTTP atmasin.
 * deleted=true gonderilir (tombstone yerine) ki tuketici silmeyi ayirt edebilsin.
 */
public record EndpointConfig(
        UUID endpointId,
        UUID applicationId,
        String url,
        String secret,
        Set<String> eventTypes,
        boolean enabled,
        boolean deleted,
        int rateLimitPerSecond,
        int maxAttempts,
        int timeoutMs,
        long version
) {
    /** "*" abonelik butun olay tiplerini kapsar. */
    public boolean subscribesTo(String eventType) {
        return eventTypes.contains("*") || eventTypes.contains(eventType);
    }

    public boolean deliverable() {
        return enabled && !deleted;
    }
}
