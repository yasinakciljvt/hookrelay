package dev.hookrelay.chaostarget;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * DEMONUN KURBANI - kotu davranan musteri sunucusu.
 *
 * Bu servis projenin en degerli parcalarindan biri, cunku onsuz
 * yeniden deneme / devre kesici / hiz siniri gibi ozellikleri
 * GOSTEREMEZSINIZ. Mimari dokumanda "devre kesici var" yazmak kolay;
 * ekranda acilip kapandigini izletmek baska bir sey.
 *
 * Davranislar:
 *   /sink/ok          her zaman 200
 *   /sink/flaky       yuzde N ihtimalle patlar
 *   /sink/slow        yavas cevap verir (zaman asimi testi)
 *   /sink/ratelimited 429 + Retry-After
 *   /sink/dead        her zaman 500
 *   /sink/gone        410 - kalici hata, DLQ'ya aninda gitmeli
 *   /sink/strict      imzayi DOGRULAR, gecersizse 401
 */
@SpringBootApplication(scanBasePackages = {"dev.hookrelay.chaostarget", "dev.hookrelay.common.crypto"})
public class ChaosTargetApplication {
    public static void main(String[] args) {
        SpringApplication.run(ChaosTargetApplication.class, args);
    }
}
