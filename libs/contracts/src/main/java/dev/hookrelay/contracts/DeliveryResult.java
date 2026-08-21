package dev.hookrelay.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * hookrelay.delivery-results.v1 govdesi.
 * Bunu dinleyen her tuketici bagimsizdir: admin-api saglik projeksiyonu kurar,
 * Prometheus metrik uretir, ileride bir bildirim servisi eklenebilir -
 * dispatcher'da tek satir degismeden.
 */
public record DeliveryResult(
        UUID deliveryId,
        UUID endpointId,
        UUID applicationId,
        String eventType,
        Outcome outcome,
        Integer httpStatus,
        long latencyMs,
        int attempt,
        String error,
        Instant occurredAt
) {
    public enum Outcome {
        /** 2xx alindi. Is bitti. */
        SUCCEEDED,
        /** Istek atildi, basarisiz oldu, yeniden denenecek. */
        RETRYING,
        /** Istek atildi, butun denemeler tukendi, DLQ'ya dustu. */
        EXHAUSTED,
        /** Devre kesici veya hiz siniri yuzunden HIC ISTEK ATILMADI. */
        SHORT_CIRCUITED,
        /** Endpoint silinmis/kapali. Teslimat dusuruldu, istek atilmadi. */
        DISCARDED;

        /**
         * Gercekten bir HTTP istegi atildi mi?
         *
         * Bu ayrim, saglik projeksiyonunun dogru olmasi icin sart.
         * Ilk surumde "SUCCEEDED degilse hatadir" varsayilmisti ve
         * silinmis bir endpoint'in 25 bin dusurulmus teslimati,
         * o endpoint'in "25 bin kez hata verdigi" gibi gorunuyordu.
         * Oysa ona tek bir istek bile atilmamisti.
         *
         * Bir metrigin yanlis olmasi, olmamasindan kotudur: olmayan
         * metrige bakmazsiniz, yanlis olana guvenirsiniz.
         */
        public boolean isRealAttempt() {
            return this == SUCCEEDED || this == RETRYING || this == EXHAUSTED;
        }
    }
}
