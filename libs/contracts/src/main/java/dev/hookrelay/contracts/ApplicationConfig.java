package dev.hookrelay.contracts;

import java.util.UUID;

/**
 * hookrelay.app-config.v1 (compacted) govdesi.
 *
 * apiKeyHash gonderilir, anahtarin kendisi ASLA gonderilmez.
 * Kafka topic'i uzun omurlu bir kayit; icine duz metin sir yazilmaz.
 */
public record ApplicationConfig(
        UUID applicationId,
        String name,
        String apiKeyHash,
        boolean enabled,
        boolean deleted,
        long version
) {}
