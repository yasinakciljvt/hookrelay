package dev.hookrelay.adminapi.api;

import dev.hookrelay.adminapi.domain.Endpoint;
import dev.hookrelay.adminapi.domain.EndpointHealth;
import dev.hookrelay.adminapi.domain.WebhookApplication;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Istek/cevap kayitlari tek dosyada - hepsi kucuk ve birlikte okunuyor. */
public final class Dtos {

    private Dtos() {}

    public record CreateApplicationRequest(
            @NotBlank @Size(max = 120) String name) {}

    public record ApplicationResponse(
            UUID id, String name, String apiKeyPreview, boolean enabled,
            long version, Instant createdAt) {

        public static ApplicationResponse from(WebhookApplication a) {
            return new ApplicationResponse(a.getId(), a.getName(), a.getApiKeyPreview(),
                    a.isEnabled(), a.getVersion(), a.getCreatedAt());
        }
    }

    /** apiKey alani SADECE olusturma ve yenileme cevabinda dolu gelir. */
    public record ApplicationCreatedResponse(
            ApplicationResponse application, String apiKey, String warning) {}

    public record CreateEndpointRequest(
            @NotBlank String url,
            String description,
            Set<String> eventTypes,
            Integer rateLimitPerSecond,
            Integer maxAttempts,
            Integer timeoutMs,
            String secret) {}

    public record UpdateEndpointRequest(
            String url,
            String description,
            Set<String> eventTypes,
            Boolean enabled,
            Integer rateLimitPerSecond,
            Integer maxAttempts,
            Integer timeoutMs) {}

    public record EndpointResponse(
            UUID id, UUID applicationId, String url, String description,
            Set<String> eventTypes, boolean enabled, int rateLimitPerSecond,
            int maxAttempts, int timeoutMs, String secret, long version,
            Instant createdAt, Instant updatedAt) {

        public static EndpointResponse from(Endpoint e) {
            return new EndpointResponse(e.getId(), e.getApplicationId(), e.getUrl(),
                    e.getDescription(), e.eventTypeSet(), e.isEnabled(),
                    e.getRateLimitPerSecond(), e.getMaxAttempts(), e.getTimeoutMs(),
                    e.getSecret(), e.getVersion(), e.getCreatedAt(), e.getUpdatedAt());
        }
    }

    public record EndpointHealthResponse(
            UUID endpointId, long succeeded, long failed, long shortCircuited,
            double successRate, int consecutiveFailures, Integer lastStatus, String lastError,
            Long lastLatencyMs, Instant lastDeliveryAt, String circuitState) {

        public static EndpointHealthResponse from(EndpointHealth h, String circuitState) {
            return new EndpointHealthResponse(h.getEndpointId(), h.getSucceeded(), h.getFailed(),
                    h.getShortCircuited(), h.successRate(), h.getConsecutiveFailures(),
                    h.getLastStatus(), h.getLastError(), h.getLastLatencyMs(),
                    h.getLastDeliveryAt(), circuitState);
        }

        public static EndpointHealthResponse empty(UUID endpointId, String circuitState) {
            return new EndpointHealthResponse(endpointId, 0, 0, 0, 1.0, 0,
                    null, null, null, null, circuitState);
        }
    }
}
