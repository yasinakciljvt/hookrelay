package dev.hookrelay.dispatcher.delivery;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * STRATEGY deseni.
 *
 * Yeniden deneme "ne zaman ve nereye" sorusunun cevabi. Bugun tek bir
 * uygulamasi var (katmanli topic'ler) ama arayuz olarak durmasinin
 * gercek bir sebebi var: musteri bazli politika. Bir musteri "beni
 * 3 kez dene, sonra birak" derken bir digeri "24 saat boyunca dene"
 * diyebilir. O gun geldiginde DeliveryProcessor'a dokunulmaz,
 * yeni bir RetryPolicy yazilir.
 *
 * (Arayuzu bugun kullanmayacak olsaydik yazmazdik. Kullanilmayan
 * soyutlama, desen degil suslemedir.)
 */
public interface RetryPolicy {

    /**
     * @param tier      1 tabanli katman numarasi
     * @param notBefore bu andan once islenmemeli
     */
    record Reschedule(int tier, Instant notBefore, Duration delay) {}

    /** Basarisiz denemeden sonra. Bos donerse: artik deneme yok, DLQ. */
    Optional<Reschedule> afterFailure(int attempt, int maxAttempts, Duration serverRetryAfter);

    /**
     * Deneme SAYILMADAN erteleme. Devre kesici acikken veya hiz siniri
     * doldugunda kullanilir: musterinin sucu degil, bizim kararimiz.
     */
    Reschedule withoutConsumingAttempt(Duration minDelay);

    /** Teslimat, dogum tarihinden bu yana yasam suresini asti mi? */
    boolean expired(Instant firstQueuedAt);
}
