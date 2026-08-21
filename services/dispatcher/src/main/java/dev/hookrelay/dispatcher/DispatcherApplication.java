package dev.hookrelay.dispatcher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * TESLIMATCI - sistemin kalbi.
 *
 * Kafka'dan okur, endpoint konfigurasyonunu Redis replikasindan alir,
 * istegi imzalar, HTTP atar, sonucu yazar ve gerekiyorsa bir sonraki
 * yeniden deneme katmanina birakir.
 *
 * Yatay olceklenen tek servis budur: 3 ornek calistirinca Kafka
 * partition'lari aralarinda paylastirir. Tavan, topic'in partition
 * sayisidir (12) - 13. ornek bos oturur.
 */
@SpringBootApplication(scanBasePackages = "dev.hookrelay")
@ConfigurationPropertiesScan("dev.hookrelay")
@org.springframework.data.jpa.repository.config.EnableJpaRepositories(
        basePackages = "dev.hookrelay.dispatcher.repo")
@org.springframework.boot.autoconfigure.domain.EntityScan(
        basePackages = "dev.hookrelay.dispatcher.domain")
public class DispatcherApplication {
    public static void main(String[] args) {
        SpringApplication.run(DispatcherApplication.class, args);
    }
}
