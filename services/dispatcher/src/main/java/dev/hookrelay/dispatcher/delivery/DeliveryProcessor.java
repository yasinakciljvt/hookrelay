package dev.hookrelay.dispatcher.delivery;

import dev.hookrelay.common.redis.EndpointConfigCache;
import dev.hookrelay.common.redis.RedisCircuitBreaker;
import dev.hookrelay.contracts.DeliveryResult;
import dev.hookrelay.contracts.DeliveryTask;
import dev.hookrelay.contracts.EndpointConfig;
import dev.hookrelay.dispatcher.delivery.checks.PreflightCheck;
import dev.hookrelay.dispatcher.domain.Delivery;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * TESLIMATIN BEYNI.
 *
 * Bu sinif kendisi HICBIR IS YAPMAZ - HTTP atmaz, veritabanina yazmaz,
 * Kafka'ya basmaz. Sadece SIRAYI ve KARARI yonetir. Her adim ayri bir
 * bean'de ve tek basina test edilebilir.
 *
 * Akis:
 *   1. Bu teslimat zaten basarili mi?         → atla (mukerrer teslimat)
 *   2. Endpoint hala var mi, acik mi?          → yoksa dusur
 *   3. Yasam suresi doldu mu?                  → DLQ
 *   4. On kontroller (devre kesici, hiz siniri)→ engellendiyse ertele
 *   5. Bulkhead izni                           → yoksa ertele
 *   6. Gonder
 *   7. Sonuca gore: bitti / yeniden dene / DLQ
 */
@Component
public class DeliveryProcessor {

    private static final Logger log = LoggerFactory.getLogger(DeliveryProcessor.class);

    private final EndpointConfigCache configs;
    private final List<PreflightCheck> checks;
    private final EndpointBulkhead bulkhead;
    private final WebhookSender sender;
    private final DeliveryStore store;
    private final DeliveryPublisher publisher;
    private final RetryPolicy retryPolicy;
    private final RedisCircuitBreaker breaker;
    private final FailureClassifier classifier;

    private final Counter succeeded;
    private final Counter failed;
    private final Counter skipped;

    public DeliveryProcessor(EndpointConfigCache configs, List<PreflightCheck> checks,
                             EndpointBulkhead bulkhead, WebhookSender sender,
                             DeliveryStore store, DeliveryPublisher publisher,
                             RetryPolicy retryPolicy, RedisCircuitBreaker breaker,
                             FailureClassifier classifier, MeterRegistry meters) {
        this.configs = configs;
        // Zincirin sirasi order() ile belirlenir, Spring'in bean sirasiyla degil.
        // Bean sirasi paket adina, sinif adina, hatta derleyiciye gore degisir -
        // uzerine sistem kurulacak bir sey degil.
        this.checks = checks.stream()
                .sorted(Comparator.comparingInt(PreflightCheck::order))
                .toList();
        this.bulkhead = bulkhead;
        this.sender = sender;
        this.store = store;
        this.publisher = publisher;
        this.retryPolicy = retryPolicy;
        this.breaker = breaker;
        this.classifier = classifier;
        this.succeeded = Counter.builder("hookrelay.dispatch.succeeded").register(meters);
        this.failed = Counter.builder("hookrelay.dispatch.failed").register(meters);
        this.skipped = Counter.builder("hookrelay.dispatch.skipped_duplicate").register(meters);
    }

    public void process(DeliveryTask task) {

        // 1. MUKERRER TESLIMAT KORUMASI
        //
        // Kafka "en az bir kez" teslim eder. Offset commit edilmeden
        // once surec olurse ayni kayit tekrar gelir. Zaten basarili
        // olmus bir teslimati ikinci kez gondermek, musterinin
        // sistemine ikinci kez "odeme alindi" demek olabilir.
        Optional<Delivery.Status> current = store.currentStatus(task.deliveryId());
        if (current.filter(Delivery.Status.SUCCEEDED::equals).isPresent()) {
            skipped.increment();
            log.debug("Zaten teslim edilmis, atlaniyor: {}", task.deliveryId());
            return;
        }

        // 2. ENDPOINT HALA GECERLI MI
        Optional<EndpointConfig> found = configs.get(task.endpointId());
        if (found.isEmpty()) {
            drop(task, "Endpoint konfigurasyonu bulunamadi (silinmis olabilir)");
            return;
        }
        EndpointConfig endpoint = found.get();
        if (!endpoint.deliverable()) {
            drop(task, "Endpoint devre disi veya silinmis");
            return;
        }

        // 3. YASAM SURESI
        if (retryPolicy.expired(task.firstQueuedAt())) {
            toDeadLetter(task, "Yasam suresi doldu");
            return;
        }

        DeliveryContext ctx = new DeliveryContext(task, endpoint);

        // 4. ON KONTROLLER
        for (PreflightCheck check : checks) {
            Optional<PreflightCheck.Block> block = check.check(ctx);
            if (block.isPresent()) {
                handleBlock(ctx, block.get());
                return;
            }
        }

        // 5. BULKHEAD - izin yoksa BEKLEME, geri koy.
        if (!bulkhead.tryAcquire(endpoint.endpointId())) {
            handleBlock(ctx, new PreflightCheck.Block(
                    DeliveryResult.Outcome.RETRYING,
                    "Endpoint es zamanlilik kotasi dolu",
                    Duration.ofSeconds(5), false));
            return;
        }

        try {
            // 6. GONDER
            SendResult result = sender.send(ctx);
            handleResult(ctx, result);
        } finally {
            // finally SART: send() istisna atarsa izin sonsuza kadar
            // kilitli kalir ve o endpoint bir daha hic teslimat almaz.
            bulkhead.release(endpoint.endpointId());
        }
    }

    // Sonuc isleme

    private void handleResult(DeliveryContext ctx, SendResult result) {
        DeliveryTask task = ctx.task();
        EndpointConfig endpoint = ctx.endpoint();

        if (result.success()) {
            breaker.recordSuccess(endpoint.endpointId());
            store.recordAttempt(task, result, Delivery.Status.SUCCEEDED, null);
            publisher.publishResult(outcome(ctx, result, DeliveryResult.Outcome.SUCCEEDED));
            succeeded.increment();
            log.debug("Teslim edildi: delivery={} status={} {}ms",
                    task.deliveryId(), result.httpStatus(), result.latencyMs());
            return;
        }

        breaker.recordFailure(endpoint.endpointId());
        failed.increment();

        // Kalici hata: yeniden denemek ayni cevabi almaktir.
        if (classifier.permanent(result)) {
            store.recordAttempt(task, result, Delivery.Status.EXHAUSTED, null);
            publisher.deadLetter(task, "Kalici hata: HTTP " + result.httpStatus());
            publisher.publishResult(outcome(ctx, result, DeliveryResult.Outcome.EXHAUSTED));
            log.info("Kalici hata, DLQ: delivery={} status={}",
                    task.deliveryId(), result.httpStatus());
            return;
        }

        Optional<RetryPolicy.Reschedule> next = retryPolicy.afterFailure(
                task.attempt(), endpoint.maxAttempts(), result.retryAfter());

        if (next.isEmpty()) {
            store.recordAttempt(task, result, Delivery.Status.EXHAUSTED, null);
            publisher.deadLetter(task, "Butun denemeler tukendi (" + task.attempt() + ")");
            publisher.publishResult(outcome(ctx, result, DeliveryResult.Outcome.EXHAUSTED));
            return;
        }

        RetryPolicy.Reschedule r = next.get();
        store.recordAttempt(task, result, Delivery.Status.PENDING, r.notBefore());
        publisher.reschedule(task, r.tier(), r.notBefore(), task.attempt() + 1);
        publisher.publishResult(outcome(ctx, result, DeliveryResult.Outcome.RETRYING));
        log.debug("Yeniden denenecek: delivery={} katman=t{} {} sonra",
                task.deliveryId(), r.tier(), r.delay());
    }

    private void handleBlock(DeliveryContext ctx, PreflightCheck.Block block) {
        DeliveryTask task = ctx.task();

        if (retryPolicy.expired(task.firstQueuedAt())) {
            toDeadLetter(task, "Yasam suresi doldu (engelliyken): " + block.reason());
            return;
        }

        RetryPolicy.Reschedule r = retryPolicy.withoutConsumingAttempt(block.minDelay());
        store.recordBlocked(task, block.reason(), r.notBefore());

        // DENEME SAYISI ARTMIYOR: ayni attempt ile geri konuyor.
        // Musterinin hatasi olmayan bir engelleme, onun deneme
        // hakkini yakmamali.
        int attempt = block.consumesAttempt() ? task.attempt() + 1 : task.attempt();
        publisher.reschedule(task, r.tier(), r.notBefore(), attempt);

        publisher.publishResult(outcome(task, block.outcome(), block.reason()));

        log.debug("Engellendi: delivery={} sebep={} → t{}",
                task.deliveryId(), block.reason(), r.tier());
    }

    /** Endpoint artik yok/kapali. Hic istek atilmadi. */
    private void drop(DeliveryTask task, String reason) {
        store.discard(task, reason);
        publisher.publishResult(
                outcome(task, DeliveryResult.Outcome.DISCARDED, reason));
        log.info("Teslimat dusuruldu: {} - {}", task.deliveryId(), reason);
    }

    /**
     * Yasam suresi doldu. DLQ'ya gider ama YENI BIR DENEME YOK -
     * o yuzden recordAttempt degil markExhausted.
     */
    private void toDeadLetter(DeliveryTask task, String reason) {
        store.markExhausted(task, reason);
        publisher.deadLetter(task, reason);
        publisher.publishResult(
                outcome(task, DeliveryResult.Outcome.EXHAUSTED, reason));
    }

    // DeliveryResult fabrikalari
    //
    // Ikisi de ayni kaydi kuruyor; ayrim, ortada gercek bir HTTP cevabi
    // olup olmamasi. Uc ayri yerde elle kurulmasi hem tekrardi hem de
    // alan sirasini karistirmaya davetiyeydi (hepsi UUID ve String).

    /** Gercek bir HTTP denemesinin sonucu. */
    private DeliveryResult outcome(DeliveryContext ctx, SendResult r, DeliveryResult.Outcome o) {
        DeliveryTask task = ctx.task();
        return new DeliveryResult(task.deliveryId(), task.endpointId(), task.applicationId(),
                task.eventType(), o, r.httpStatus(), r.latencyMs(), task.attempt(),
                r.error(), Instant.now());
    }

    /** Istek atilmadan verilen karar (engelleme, dusurme, sure asimi). */
    private DeliveryResult outcome(DeliveryTask task, DeliveryResult.Outcome o, String reason) {
        return new DeliveryResult(task.deliveryId(), task.endpointId(), task.applicationId(),
                task.eventType(), o, null, 0, task.attempt(), reason, Instant.now());
    }
}
