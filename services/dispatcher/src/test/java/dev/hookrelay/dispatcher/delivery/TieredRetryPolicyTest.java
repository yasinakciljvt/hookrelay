package dev.hookrelay.dispatcher.delivery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TieredRetryPolicyTest {

    private static final List<Duration> DELAYS = List.of(
            Duration.ofSeconds(10), Duration.ofMinutes(1), Duration.ofMinutes(5),
            Duration.ofMinutes(30), Duration.ofHours(2));

    private final RetryPolicy policy =
            new TieredRetryPolicy(new RetryProperties(DELAYS, Duration.ofHours(24)));

    @Test
    @DisplayName("1. deneme basarisiz olunca t1'e (10 sn) gider")
    void ilk_hata_t1() {
        Optional<RetryPolicy.Reschedule> r = policy.afterFailure(1, 6, null);

        assertThat(r).isPresent();
        assertThat(r.get().tier()).isEqualTo(1);
        assertThat(r.get().delay()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("Katmanlar sirayla ilerler: 1→t1, 2→t2, ... 5→t5")
    void katmanlar_sirayla() {
        for (int attempt = 1; attempt <= 5; attempt++) {
            var r = policy.afterFailure(attempt, 6, null);
            assertThat(r).as("deneme %d", attempt).isPresent();
            assertThat(r.get().tier()).isEqualTo(attempt);
            assertThat(r.get().delay()).isEqualTo(DELAYS.get(attempt - 1));
        }
    }

    @Test
    @DisplayName("Katmanlar bitince bos doner - cagiran DLQ'ya yollar")
    void katmanlar_bitince_bos() {
        assertThat(policy.afterFailure(6, 6, null)).isEmpty();
        assertThat(policy.afterFailure(9, 20, null))
                .as("maxAttempts buyuk olsa bile katman sayisi tavandir")
                .isEmpty();
    }

    @Test
    @DisplayName("maxAttempts katman sayisindan kucukse erken durur")
    void max_attempts_tavani() {
        assertThat(policy.afterFailure(2, 3, null)).isPresent();
        assertThat(policy.afterFailure(3, 3, null)).isEmpty();
    }

    @Test
    @DisplayName("Retry-After katman gecikmesinden uzunsa ona uyulur")
    void retry_after_uzunsa_kazanir() {
        var r = policy.afterFailure(1, 6, Duration.ofMinutes(3));

        assertThat(r).isPresent();
        assertThat(r.get().tier()).as("topic degismez, sadece not-before ileri alinir").isEqualTo(1);
        assertThat(r.get().delay()).isEqualTo(Duration.ofMinutes(3));
        assertThat(r.get().notBefore()).isAfter(Instant.now().plus(Duration.ofMinutes(2)));
    }

    @Test
    @DisplayName("Retry-After katman gecikmesinden kisaysa yok sayilir")
    void retry_after_kisaysa_yok_sayilir() {
        var r = policy.afterFailure(3, 6, Duration.ofSeconds(2));

        assertThat(r).isPresent();
        assertThat(r.get().delay())
                .as("kendi geri cekilmemizden erken donmeyiz")
                .isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("Deneme yakmadan erteleme, istenen sureyi karsilayan ilk katmani secer")
    void deneme_yakmadan_erteleme() {
        assertThat(policy.withoutConsumingAttempt(Duration.ofSeconds(3)).tier())
                .as("3 sn → 10 sn'lik t1 yeterli").isEqualTo(1);

        assertThat(policy.withoutConsumingAttempt(Duration.ofSeconds(45)).tier())
                .as("45 sn → t1 (10 sn) yetmez, t2 (60 sn)").isEqualTo(2);

        assertThat(policy.withoutConsumingAttempt(Duration.ofMinutes(20)).tier())
                .as("20 dk → t4 (30 dk)").isEqualTo(4);
    }

    @Test
    @DisplayName("En buyuk katmandan uzun bekleme istenirse son katmana konur ama erken islenmez")
    void cok_uzun_bekleme() {
        var r = policy.withoutConsumingAttempt(Duration.ofHours(5));

        assertThat(r.tier()).isEqualTo(5);
        assertThat(r.notBefore())
                .as("not-before basligi topic gecikmesinden bagimsiz - erken islenmez")
                .isAfter(Instant.now().plus(Duration.ofHours(4)));
    }

    @Test
    @DisplayName("Yasam suresi dolan teslimat expired doner")
    void yasam_suresi() {
        assertThat(policy.expired(Instant.now())).isFalse();
        assertThat(policy.expired(Instant.now().minus(Duration.ofHours(23)))).isFalse();
        assertThat(policy.expired(Instant.now().minus(Duration.ofHours(25)))).isTrue();
        assertThat(policy.expired(null)).isFalse();
    }

    @Test
    @DisplayName("Bos ayarla kurulursa makul varsayilanlar devreye girer")
    void varsayilanlar() {
        RetryPolicy p = new TieredRetryPolicy(new RetryProperties(null, null));
        assertThat(p.afterFailure(1, 6, null)).isPresent();
        assertThat(p.expired(Instant.now().minus(Duration.ofHours(25)))).isTrue();
    }
}
