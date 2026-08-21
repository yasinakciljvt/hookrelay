package dev.hookrelay.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * hookrelay.messages.v1 ve butun retry topic'lerinin govdesi.
 *
 * Dikkat: bu kayit BIR ENDPOINT'e yapilacak TEK teslimati temsil eder,
 * mantiksal olayi degil. Bir olay 3 endpoint'e gidiyorsa 3 DeliveryTask uretilir.
 * Fan-out ingest'te yapilir - boylece Kafka key'i endpointId olabilir ve
 * "ayni endpoint'e sirali teslimat" garantisi partition'dan bedava gelir.
 */
public record DeliveryTask(
        UUID deliveryId,
        UUID messageId,
        UUID applicationId,
        UUID endpointId,
        String eventType,
        String payload,
        int attempt,
        Instant firstQueuedAt
) {
    public DeliveryTask withAttempt(int next) {
        return new DeliveryTask(deliveryId, messageId, applicationId, endpointId,
                eventType, payload, next, firstQueuedAt);
    }
}
