package dev.hookrelay.common.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hookrelay.common.redis.AppConfigCache;
import dev.hookrelay.contracts.ApplicationConfig;
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
 * app-config compacted topic'ini Redis replikasina akitir.
 * Tasarim gerekceleri icin bkz. {@link EndpointConfigReplicator}.
 */
@Component
@ConditionalOnProperty(name = "hookrelay.app-config.replicate", havingValue = "true")
public class AppConfigReplicator implements ConsumerSeekAware {

    private static final Logger log = LoggerFactory.getLogger(AppConfigReplicator.class);

    private final AppConfigCache cache;
    private final ObjectMapper mapper;

    public AppConfigReplicator(AppConfigCache cache, ObjectMapper mapper) {
        this.cache = cache;
        this.mapper = mapper;
    }

    @KafkaListener(
            topics = Topics.APP_CONFIG,
            groupId = "app-config-#{T(java.util.UUID).randomUUID().toString()}")
    public void onConfig(ConsumerRecord<String, String> record) {
        try {
            if (record.value() == null || record.value().isBlank()) return;
            ApplicationConfig cfg = mapper.readValue(record.value(), ApplicationConfig.class);
            cache.put(cfg);
            log.debug("Uygulama replikasi guncellendi: {} v{}", cfg.applicationId(), cfg.version());
        } catch (Exception e) {
            log.error("Uygulama konfigurasyon kaydi islenemedi: key={}", record.key(), e);
        }
    }

    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments,
                                     ConsumerSeekCallback callback) {
        if (assignments.isEmpty()) return;
        callback.seekToBeginning(assignments.keySet());
        log.info("Uygulama konfigurasyonu bastan okunuyor: {} partition", assignments.size());
    }
}
