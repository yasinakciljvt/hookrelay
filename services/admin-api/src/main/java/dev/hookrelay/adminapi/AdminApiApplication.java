package dev.hookrelay.adminapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * KONTROL DUZLEMI (control plane).
 *
 * Uygulama ve endpoint tanimlarinin TEK sahibi. Bu servis coktugunde
 * yeni endpoint eklenemez ama TESLIMAT DURMAZ - cunku dispatcher
 * konfigurasyonu Redis replikasindan okuyor, buraya HTTP atmiyor.
 * Kontrol duzlemi ile veri duzlemini ayirmanin butun anlami bu cumlede.
 */
@SpringBootApplication(scanBasePackages = "dev.hookrelay")
@ConfigurationPropertiesScan("dev.hookrelay")
@EnableScheduling
@org.springframework.data.jpa.repository.config.EnableJpaRepositories(
        basePackages = {"dev.hookrelay.adminapi.repo", "dev.hookrelay.outbox"})
@org.springframework.boot.autoconfigure.domain.EntityScan(
        basePackages = {"dev.hookrelay.adminapi.domain", "dev.hookrelay.outbox"})
public class AdminApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(AdminApiApplication.class, args);
    }
}
