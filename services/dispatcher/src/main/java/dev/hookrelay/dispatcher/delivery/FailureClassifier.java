package dev.hookrelay.dispatcher.delivery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * "Bu hatayi yeniden denemeye deger mi?"
 *
 * Siniflandirma bir POLITIKADIR, veri degildir. O yuzden {@link SendResult}
 * kaydinin icinde degil, ayri bir bean'de: musteri bazli veya ortam bazli
 * degisebilmesi gerekiyor ve test edilebilir olmasi lazim.
 *
 * Kural:
 *   2xx                → basari
 *   ag hatasi (kod yok) → gecici. Sunucu yarin ayakta olabilir.
 *   408, 429            → gecici. Ikisi de "simdi olmaz" demek, "asla" degil.
 *   410 Gone            → HER ZAMAN kalici. "Bu kaynak kalici olarak yok"
 *                         demenin baska bir yolu yok; israr etmek kabalik.
 *   diger 4xx           → varsayilan kalici, ayarla acilabilir
 *   5xx                 → gecici
 *
 * Neden diger 4xx varsayilan olarak kalici: "istegin kendisi yanlis" demek.
 * Ayni istegi bes kez daha gondermek ayni cevabi bes kez daha almaktir --
 * bosa kaynak, bosa gecikme, bosa DLQ gecikmesi.
 *
 * Neden yine de acilabilir: bazi musteriler token yenilerken gecici 403
 * doner. Onlar icin 4xx'i yeniden denemek dogru olabilir. Karari biz degil,
 * sistemi calistiran versin.
 */
@Component
public class FailureClassifier {

    private final boolean retryOn4xx;

    public FailureClassifier(
            @Value("${hookrelay.delivery.retry-on-4xx:false}") boolean retryOn4xx) {
        this.retryOn4xx = retryOn4xx;
    }

    public boolean permanent(SendResult result) {
        Integer status = result.httpStatus();

        if (status == null) return false;              // ag hatasi → gecici
        if (status >= 200 && status < 300) return false;
        if (status == 410) return true;                // ayardan bagimsiz
        if (status == 408 || status == 429) return false;
        if (status >= 400 && status < 500) return !retryOn4xx;

        return false;                                  // 5xx ve digerleri gecici
    }

    public boolean retryOn4xx() { return retryOn4xx; }
}
