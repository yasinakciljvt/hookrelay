package dev.hookrelay.common.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hookrelay.common.redis.EndpointConfigCache;
import dev.hookrelay.contracts.EndpointConfig;
import dev.hookrelay.contracts.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Compacted konfigurasyon topic'ini Redis replikasina akitir.
 *
 * Iki alisilmadik tarafi var, ikisi de kasitli:
 *
 * groupId'de rastgele UUID -- her ornek kendi grubunda. Bu bir is kuyrugu
 * degil durum tablosu; her dispatcher orneginin butun endpoint'leri
 * bilmesi gerekiyor. Ortak grup olsaydi partition'lar paylastirilir ve
 * her ornek endpoint'lerin ucte birini gorurdu.
 *
 * seekToBeginning -- her acilista bastan okunur. Topic compacted oldugu
 * icin bu, gecmisi degil guncel tabloyu okumak demek. Offset saklamaya
 * gerek yok: durumun kaynagi topic'in kendisi.
 *
 * @TopicPartition ile elle atama da mumkundu ama partition numaralarini
 * anotasyona yazmayi gerektiriyor; sayi degisince sessizce eksik okunur.
 */
@Component
@ConditionalOnProperty(name = "hookrelay.endpoint-config.replicate", havingValue = "true")
public class EndpointConfigReplicator implements ConsumerSeekAware {

    private static final Logger log = LoggerFactory.getLogger(EndpointConfigReplicator.class);

    private final EndpointConfigCache cache;
    private final ObjectMapper mapper;

    public EndpointConfigReplicator(EndpointConfigCache cache, ObjectMapper mapper) {
        this.cache = cache;
        this.mapper = mapper;
    }

    @KafkaListener(
            topics = Topics.ENDPOINT_CONFIG,
            groupId = "endpoint-config-#{T(java.util.UUID).randomUUID().toString()}")
    public void onConfig(ConsumerRecord<String, String> record) {
        try {
            // Mezar tasi (tombstone): compacted topic'te null govde silme demek.
            // Biz silmeyi deleted=true ile yapiyoruz ama baska bir arac
            // tombstone basarsa da cokmeyelim.
            if (record.value() == null || record.value().isBlank()) {
                log.debug("Mezar tasi alindi: {}", record.key());
                return;
            }
            EndpointConfig cfg = mapper.readValue(record.value(), EndpointConfig.class);
            cache.put(cfg);
            log.debug("Endpoint replikasi guncellendi: {} v{}", cfg.endpointId(), cfg.version());
        } catch (Exception e) {
            // Tek bir bozuk kayit butun replikasyonu durdurmamali.
            log.error("Konfigurasyon kaydi islenemedi: key={}", record.key(), e);
        }
    }

    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments,
                                     ConsumerSeekCallback callback) {
        if (assignments.isEmpty()) return;
        callback.seekToBeginning(assignments.keySet());
        log.info("Endpoint konfigurasyonu bastan okunuyor: {} partition", assignments.size());
    }
}
