package dev.hookrelay.dispatcher.delivery;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Katmanli (tiered) yeniden deneme.
 *
 * KAFKA'DA GECIKMELI MESAJ PROBLEMI
 * RabbitMQ'da "bu mesaji 30 dakika sonra teslim et" diyebilirsiniz.
 * Kafka'da BOYLE BIR SEY YOK. Kafka bir kuyruk degil, bir GUNLUK:
 * kayitlar yazildiklari sirada durur, tuketici bastan sona okur.
 * Tek bir kaydi "sonraya birakmak" diye bir islem yoktur.
 *
 * Naif cozum: tuketici kaydi okur, Thread.sleep(30 dakika) yapar.
 * Felaket: max.poll.interval.ms (varsayilan 5 dk) asilir, broker
 * tuketiciyi olmus sayar, rebalance tetiklenir, mesaj baskasina gider,
 * o da uyur... Butun grup kilitlenir.
 *
 * COZUM: HER GECIKME ICIN AYRI TOPIC
 * t1=10sn, t2=1dk, t3=5dk, t4=30dk, t5=2sa.
 *
 * Bir topic'teki BUTUN kayitlar AYNI gecikmeye sahip oldugu icin,
 * partition icinde "islenme zamani" sirasi = "yazilma" sirasi.
 * Yani tuketicinin sadece BASTAKI kayda bakmasi yeterli:
 * o hazir degilse arkasindakiler de hazir degildir.
 *
 * Bu tek gozlem butun cozumu mumkun kiliyor. Karisik gecikmeler ayni
 * topic'te olsaydi, arkadaki hazir bir kaydi gormek icin ondekini
 * atlamak gerekirdi - Kafka'da mumkun degil (offset geriye/ileriye
 * atlanir ama "atla ve sonra don" diye bir sey yok).
 */
@Component
public class TieredRetryPolicy implements RetryPolicy {

    private final List<Duration> delays;
    private final Duration maxLifetime;

    public TieredRetryPolicy(RetryProperties properties) {
        this.delays = properties.delays();
        this.maxLifetime = properties.maxLifetime();
    }

    @Override
    public Optional<Reschedule> afterFailure(int attempt, int maxAttempts, Duration serverRetryAfter) {
        // attempt = simdi biten deneme. Sonraki katman indeksi = attempt (0 tabanli).
        if (attempt >= maxAttempts || attempt > delays.size()) {
            return Optional.empty();
        }
        int tier = attempt;                       // 1. deneme bitti → t1'e koy
        Duration delay = delays.get(tier - 1);

        // Sunucu "Retry-After: 120" dediyse ona SAYGI GOSTER.
        //
        // Bunu yapmak sadece kibarlik degil: 429 donen bir sunucuya
        // erken donmek, ikinci bir 429 ve bir deneme daha harcamak demek.
        // Katman topic'i degismiyor, sadece not-before ileri aliniyor -
        // tasarim bunu bedavaya destekliyor cunku zamanlayici topic adina
        // degil, kaydin uzerindeki not-before basligina bakiyor.
        if (serverRetryAfter != null && serverRetryAfter.compareTo(delay) > 0) {
            delay = serverRetryAfter;
        }
        return Optional.of(new Reschedule(tier, Instant.now().plus(delay), delay));
    }

    @Override
    public Reschedule withoutConsumingAttempt(Duration minDelay) {
        Duration wanted = minDelay == null ? delays.get(0) : minDelay;
        for (int i = 0; i < delays.size(); i++) {
            if (delays.get(i).compareTo(wanted) >= 0) {
                return new Reschedule(i + 1, Instant.now().plus(delays.get(i)), delays.get(i));
            }
        }
        // Istenen gecikme en buyuk katmandan da uzun: en buyugune koy,
        // not-before'u istenen ana ayarla. Kayit birkac tur donebilir
        // ama asla erken islenmez.
        int last = delays.size();
        return new Reschedule(last, Instant.now().plus(wanted), wanted);
    }

    @Override
    public boolean expired(Instant firstQueuedAt) {
        if (firstQueuedAt == null) return false;
        return Instant.now().isAfter(firstQueuedAt.plus(maxLifetime));
    }
}
