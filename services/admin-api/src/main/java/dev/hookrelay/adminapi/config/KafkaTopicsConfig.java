package dev.hookrelay.adminapi.config;

import dev.hookrelay.contracts.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;


/**
 * Topic'leri kod olusturur, elle degil.
 *
 * Neden onemli: partition sayisi ve cleanup.policy calisma zamaninda
 * degistirilmesi zor kararlar. Burada durunca hem versiyonlanir hem de
 * "docker compose up" diyen herkeste ayni sistem kurulur.
 *
 * KafkaAdmin bu bean'leri gorup topic'leri olusturur; VAR OLAN topic'i
 * degistirmez (partition sayisini kod dusurmez).
 */
@Configuration
public class KafkaTopicsConfig {

    /**
     * Ana teslimat topic'i. 12 partition.
     *
     * PARTITION SAYISI = PARALELLIK TAVANI. 12 partition varsa 13. tuketici
     * bos oturur. Yukari cikarmak kolay, asagi inmek imkansiz - bu yuzden
     * baslangicta ihtiyactan bir tik fazlasi secilir.
     */
    @Bean
    public NewTopic messagesTopic() {
        return TopicBuilder.name(Topics.MESSAGES)
                .partitions(12).replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(7L * 24 * 3600 * 1000))
                .build();
    }

    /**
     * Katmanli yeniden deneme topic'leri. Daha az trafik → daha az partition.
     *
     * TUZAK - DONUS TIPI KafkaAdmin.NewTopics OLMAK ZORUNDA
     * Ilk yazilisi soyleydi ve SESSIZCE calismadi:
     *
     *     @Bean
     *     public List<NewTopic> retryTopics() { ... }
     *
     * KafkaAdmin, uygulama baglaminda NewTopic ve KafkaAdmin.NewTopics
     * tipindeki bean'leri arar. List<NewTopic> bunlardan hicbiri degil -
     * Spring bean'i olusturur, KafkaAdmin gormezden gelir, hata cikmaz.
     *
     * Sonuc: bes retry topic'i hic olusmaz. Ilk yeniden deneme gerekene
     * kadar da fark edilmez; sonra "Topic ... not present in metadata"
     * hatasi gelir ve sebebi bu bean'de aranmaz.
     *
     * Birden fazla topic'i tek bean'de tanimlamanin dogru yolu budur.
     */
    @Bean
    public KafkaAdmin.NewTopics retryTopics() {
        NewTopic[] topics = Topics.RETRY_TIERS.stream()
                .map(name -> TopicBuilder.name(name).partitions(6).replicas(1)
                        .config(TopicConfig.RETENTION_MS_CONFIG,
                                String.valueOf(3L * 24 * 3600 * 1000))
                        .build())
                .toArray(NewTopic[]::new);
        return new KafkaAdmin.NewTopics(topics);
    }

    /** DLQ 30 gun tutulur: elle kurtarma icin insan zamani gerekir. */
    @Bean
    public NewTopic dlqTopic() {
        return TopicBuilder.name(Topics.DLQ)
                .partitions(3).replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(30L * 24 * 3600 * 1000))
                .build();
    }

    @Bean
    public NewTopic deliveryResultsTopic() {
        return TopicBuilder.name(Topics.DELIVERY_RESULTS)
                .partitions(12).replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(2L * 24 * 3600 * 1000))
                .build();
    }

    /**
     * COMPACTED topic'ler.
     *
     * cleanup.policy=compact → Kafka eski kayitlari SILMEZ, ayni key'in
     * eski surumlerini siler. Sonucta topic "her key icin son deger"
     * tutan bir tabloya donusur. Yeni bir dispatcher offset 0'dan okur
     * ve 3 saniyede butun endpoint tablosunu ogrenir.
     *
     * segment.ms + min.cleanable.dirty.ratio dusuk: demo sirasinda
     * sikistirmanin gorulebilmesi icin. Uretimde varsayilanlar birakilir.
     */
    @Bean
    public NewTopic endpointConfigTopic() {
        return TopicBuilder.name(Topics.ENDPOINT_CONFIG)
                .partitions(3).replicas(1)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT)
                .config(TopicConfig.SEGMENT_MS_CONFIG, "60000")
                .config(TopicConfig.MIN_CLEANABLE_DIRTY_RATIO_CONFIG, "0.1")
                .build();
    }

    @Bean
    public NewTopic appConfigTopic() {
        return TopicBuilder.name(Topics.APP_CONFIG)
                .partitions(3).replicas(1)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT)
                .config(TopicConfig.SEGMENT_MS_CONFIG, "60000")
                .config(TopicConfig.MIN_CLEANABLE_DIRTY_RATIO_CONFIG, "0.1")
                .build();
    }
}
