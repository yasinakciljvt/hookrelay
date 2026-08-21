package dev.hookrelay.dispatcher.delivery.checks;

import dev.hookrelay.contracts.DeliveryResult;
import dev.hookrelay.dispatcher.delivery.DeliveryContext;

import java.time.Duration;
import java.util.Optional;

/**
 * CHAIN OF RESPONSIBILITY.
 *
 * Istek gonderilmeden once gecmesi gereken kontroller. Her biri
 * bagimsiz, her biri tek bir sey biliyor, sirasi order() ile belli.
 *
 * Neden zincir, neden tek bir if blogu degil: bugun 2 kontrol var
 * (devre kesici, hiz siniri). Yarin "musteri IP'si kara listede mi",
 * "bu olay tipi gecici olarak duraklatildi mi", "bakim penceresi mi"
 * eklenecek. Her biri DeliveryProcessor'a bir if daha eklemek yerine
 * bir sinif olarak gelir ve processor'a hic dokunulmaz.
 *
 * Zincirin sirasi ONEMLI ve ucuzdan pahaliya dizilmis:
 *   10 devre kesici (1 Redis cagrisi, cogu zaman kapali → hemen gec)
 *   20 hiz siniri   (1 Redis cagrisi, jeton harciyor -- devre acikken
 *                    bosuna jeton harcamamak icin sonra)
 */
public interface PreflightCheck {

    /**
     * @param outcome         sonuca ne yazilacak
     * @param reason          insan okuyacak aciklama
     * @param minDelay        en erken ne kadar sonra tekrar denenebilir
     * @param consumesAttempt bu engelleme bir deneme hakki yakiyor mu
     */
    record Block(DeliveryResult.Outcome outcome, String reason,
                 Duration minDelay, boolean consumesAttempt) {}

    /** Bos Optional = gecti, sonraki kontrole devam. */
    Optional<Block> check(DeliveryContext context);

    int order();
}
