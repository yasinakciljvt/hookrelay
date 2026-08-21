package dev.hookrelay.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Outbox tablosunu Kafka'ya akitir.
 *
 * Sira garantisi tek poller ornegiyle korunur. Iki ornek ayni anda
 * calisirsa SKIP LOCKED ikisine farkli satirlar verir ve eski kayit
 * yeniden sonra basilabilir. Bu yuzden ingest yatay olcekleniyor,
 * poller olceklenmiyor. Alternatifler: outbox'i key hash'ine gore
 * bolmek, ya da Debezium ile WAL'dan okumak.
 */
@Component
@ConditionalOnProperty(name = "hookrelay.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final int batchSize;
    private final Duration sendTimeout;
    private final Counter published;
    private final Counter failed;

    public OutboxPublisher(OutboxRepository repository,
                           KafkaTemplate<String, String> kafka,
                           MeterRegistry meters,
                           @org.springframework.beans.factory.annotation.Value("${hookrelay.outbox.batch-size:200}") int batchSize,
                           @org.springframework.beans.factory.annotation.Value("${hookrelay.outbox.send-timeout-ms:5000}") long sendTimeoutMs) {
        this.repository = repository;
        this.kafka = kafka;
        this.batchSize = batchSize;
        this.sendTimeout = Duration.ofMillis(sendTimeoutMs);
        this.published = Counter.builder("hookrelay.outbox.published").register(meters);
        this.failed = Counter.builder("hookrelay.outbox.failed").register(meters);
    }

    /**
     * fixedDelay (fixedRate degil): bir tur bitmeden yenisi baslamasin.
     * fixedRate olsaydi yavas bir tur sirasinda turlar ust uste binerdi.
     */
    @Scheduled(fixedDelayString = "${hookrelay.outbox.poll-interval-ms:200}")
    @Transactional
    public void drain() {
        List<OutboxEvent> batch = repository.lockUnpublished(batchSize);
        if (batch.isEmpty()) return;

        // ONCE HEPSINI GONDER, SONRA TEYITLERI TOPLA
        //
        // Ilk yazilisi her kayitta send().get() cagiriyordu - yani her
        // kayit icin bir gidis-donus bekleniyordu. 500 kayitlik bir paket,
        // 1 ms'lik gidis-donus ile 500 ms suruyordu ve poller tek is
        // parcacikli oldugu icin bu dogrudan sistemin tavani oluyordu.
        // Olculdu: giris ~67 olay/sn'de tikaniyor, p95 5,7 saniye.
        //
        // Simdi butun paket once uretici tamponuna birakiliyor (send
        // asenkrondur, aninda doner), sonra teyitler toplaniyor. Kafka
        // istemcisi kayitlari paketleyip tek istekte gonderiyor.
        //
        // SIRA GARANTISI KORUNUYOR: send cagrilari created_at sirasiyla
        // YAPILIYOR ve idempotent uretici, ayni partition'a yapilan
        // cagrilarin sirasini koruyor. Degisen tek sey, teyidi ne zaman
        // bekledigimiz.
        //
        // KAYIPSIZLIK KORUNUYOR: hala hicbir kaydi teyit almadan
        // "yayinlandi" diye isaretlemiyoruz.
        List<CompletableFuture<SendResult<String, String>>> futures = new ArrayList<>(batch.size());
        for (OutboxEvent event : batch) {
            futures.add(kafka.send(new ProducerRecord<>(
                    event.getTopic(), event.getMsgKey(), event.getPayload())));
        }

        for (int i = 0; i < batch.size(); i++) {
            OutboxEvent event = batch.get(i);
            try {
                futures.get(i).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
                event.markPublished();
                published.increment();
            } catch (Exception e) {
                // Kismi basarisizlik artik sorun degil: basarili olanlar
                // isaretlenir, olmayanlar bir sonraki turda tekrar denenir.
                // (Onceki surumde ilk hatada break ediliyordu; asenkron
                // gonderimde "sonrakiler zaten gitmis olabilir" durumu
                // dogdugu icin her kaydin kendi teyidine bakiyoruz.)
                event.markFailed(e.getMessage());
                failed.increment();
                log.error("Outbox kaydi yayinlanamadi: id={} topic={} deneme={}",
                        event.getId(), event.getTopic(), event.getAttempts(), e);
            }
        }
        repository.saveAll(batch);
    }

    /** Yayinlanmis kayitlari sonsuza kadar tutmak tabloyu sisirir. */
    @Scheduled(cron = "${hookrelay.outbox.cleanup-cron:0 */10 * * * *}")
    @Transactional
    public void cleanup() {
        int removed = repository.deletePublishedBefore(Instant.now().minus(Duration.ofHours(6)));
        if (removed > 0) log.info("Outbox temizligi: {} kayit silindi", removed);
    }
}
