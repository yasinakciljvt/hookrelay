package dev.hookrelay.retryscheduler.runner;

import dev.hookrelay.common.kafka.KafkaHeaderCodec;
import dev.hookrelay.contracts.Headers;
import dev.hookrelay.contracts.Topics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Kafka'da geciktirmeli mesaj: her gecikme icin ayri topic + pause/seek.
 *
 * Kafka bir kuyruk degil gunluktur; "bunu 30 dakika sonra ver" diye bir
 * islem yok. Thread.sleep ise max.poll.interval.ms'i asar, broker
 * tuketiciyi olmus sayar ve grup sonsuz rebalance'a girer.
 *
 * Bunun yerine: vakti gelmemis kaydi gorunce partition'i duraklat,
 * offset'i o kayda geri al, poll() cagirmaya devam et (heartbeat gitsin).
 * Vakit gelince resume.
 *
 * Bir katman topic'indeki butun kayitlarin gecikmesi ayni oldugu icin
 * not-before sirasi = offset sirasi; bastaki kaydi kontrol etmek yeter.
 * Katmanli topic tasariminin asil sebebi bu.
 *
 * Ayrintili gerekce: docs/ icindeki rehber, Bolum 9.
 */
@Component
public class RetrySchedulerRunner implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(RetrySchedulerRunner.class);

    private final KafkaTemplate<String, String> producer;
    private final MeterRegistry meters;
    private final String bootstrapServers;
    private final String groupId;
    private final long pollMillis;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<TopicPartition, Long> resumeAt = new ConcurrentHashMap<>();
    private KafkaConsumer<String, String> consumer;
    private Thread thread;

    private Counter forwarded;
    private Counter earlyPaused;

    public RetrySchedulerRunner(KafkaTemplate<String, String> producer,
                                MeterRegistry meters,
                                @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
                                @Value("${hookrelay.scheduler.group:retry-scheduler}") String groupId,
                                @Value("${hookrelay.scheduler.poll-ms:500}") long pollMillis) {
        this.producer = producer;
        this.meters = meters;
        this.bootstrapServers = bootstrapServers;
        this.groupId = groupId;
        this.pollMillis = pollMillis;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!running.compareAndSet(false, true)) return;

        this.forwarded = Counter.builder("hookrelay.scheduler.forwarded").register(meters);
        this.earlyPaused = Counter.builder("hookrelay.scheduler.paused").register(meters);
        Gauge.builder("hookrelay.scheduler.paused_partitions", resumeAt, Map::size)
                .register(meters);

        this.consumer = new KafkaConsumer<>(consumerProperties());
        this.thread = new Thread(this, "retry-scheduler");
        this.thread.setDaemon(false);
        this.thread.start();
        log.info("Yeniden deneme zamanlayicisi basladi. Katmanlar: {}", Topics.RETRY_TIERS);
    }

    @PreDestroy
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        // wakeup(): poll() icinde bloke olan tuketiciyi WakeupException ile
        // uyandirir. Tek guvenli disaridan durdurma yontemi budur;
        // KafkaConsumer thread-safe DEGILDIR, baska metodunu baska
        // is parcacigindan cagiramazsiniz.
        if (consumer != null) consumer.wakeup();
        try {
            if (thread != null) thread.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void run() {
        try {
            consumer.subscribe(Topics.RETRY_TIERS, new ClearPausedStateOnRebalance());

            while (running.get()) {
                resumeDuePartitions();

                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(pollMillis));
                if (records.isEmpty()) {
                    idleBackoff();
                    continue;
                }

                Map<TopicPartition, OffsetAndMetadata> commits = new HashMap<>();
                long now = System.currentTimeMillis();

                for (TopicPartition partition : records.partitions()) {
                    long lastProcessed = -1;

                    for (ConsumerRecord<String, String> record : records.records(partition)) {
                        long notBefore = KafkaHeaderCodec.longValue(
                                record.headers(), Headers.NOT_BEFORE, record.timestamp());

                        if (now < notBefore) {
                            // BASTAKI KAYIT HENUZ HAZIR DEGIL.
                            // Partition'i duraklat, offset'i bu kayda geri al.
                            consumer.pause(List.of(partition));
                            consumer.seek(partition, record.offset());
                            resumeAt.put(partition, notBefore);
                            earlyPaused.increment();

                            log.debug("Duraklatildi: {} → {} ms sonra",
                                    partition, notBefore - now);
                            break;   // bu partition'da devam etme
                        }

                        forward(record);
                        lastProcessed = record.offset();
                    }

                    if (lastProcessed >= 0) {
                        // Sadece GERCEKTEN islenenlere kadar commit.
                        // Duraklatilan kaydin offset'i commit EDILMEZ.
                        commits.put(partition, new OffsetAndMetadata(lastProcessed + 1));
                    }
                }

                if (!commits.isEmpty()) {
                    consumer.commitSync(commits);
                }
            }
        } catch (WakeupException e) {
            if (running.get()) log.error("Beklenmeyen wakeup", e);
        } catch (Exception e) {
            log.error("Zamanlayici dongusu coktu", e);
        } finally {
            try {
                consumer.close(Duration.ofSeconds(5));
            } catch (Exception ignored) { }
            log.info("Yeniden deneme zamanlayicisi durdu");
        }
    }

    /** Vakti gelen partition'lari yeniden aktif et. */
    private void resumeDuePartitions() {
        if (resumeAt.isEmpty()) return;
        long now = System.currentTimeMillis();

        List<TopicPartition> due = resumeAt.entrySet().stream()
                .filter(e -> now >= e.getValue())
                .map(Map.Entry::getKey)
                .toList();

        if (due.isEmpty()) return;
        // Sadece BIZE HALA ATANMIS olanlari devam ettir; rebalance
        // sirasinda elimizden alinmis bir partition'i resume etmek hata verir.
        Set<TopicPartition> assigned = consumer.assignment();
        List<TopicPartition> resumable = due.stream().filter(assigned::contains).toList();

        if (!resumable.isEmpty()) {
            consumer.resume(resumable);
            log.debug("Devam ettirildi: {}", resumable);
        }
        due.forEach(resumeAt::remove);
    }

    private void forward(ConsumerRecord<String, String> record) {
        // Key DEGISMIYOR (endpointId): ana topic'te de ayni partition'a
        // duser, ayni endpoint icin sira korunur.
        producer.send(Topics.MESSAGES, record.key(), record.value());
        forwarded.increment();
    }

    /**
     * Butun partition'lar duraklatilmissa poll() aninda bos doner ve
     * dongu CPU yakar. Kucuk bir nefes.
     */
    private void idleBackoff() {
        if (resumeAt.isEmpty()) return;
        try {
            Thread.sleep(Math.min(pollMillis, 250));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Properties consumerProperties() {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringDeserializer");
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringDeserializer");
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);   // offset'i biz yonetiyoruz
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 200);
        return p;
    }

    /**
     * Rebalance'ta duraklatma durumu SIFIRLANMALI.
     *
     * Elimizden alinan bir partition icin "su saatte devam ettir" notu
     * tutmaya devam edersek, o partition baska bir ornekteyken resume
     * etmeye calisiriz ve IllegalStateException aliriz. Yeni atanan
     * partition'lar zaten duraklatilmamis gelir.
     */
    private class ClearPausedStateOnRebalance implements ConsumerRebalanceListener {
        @Override
        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            partitions.forEach(resumeAt::remove);
            log.debug("Partition'lar geri alindi: {}", partitions);
        }

        @Override
        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            partitions.forEach(resumeAt::remove);
            log.info("Partition'lar atandi: {}", partitions);
        }
    }

    /** Arayuzde gostermek icin: hangi partition ne zaman devam edecek. */
    public Map<String, Long> pausedPartitions() {
        Map<String, Long> out = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        resumeAt.forEach((tp, at) -> out.put(tp.toString(), Math.max(0, at - now)));
        return out;
    }
}
