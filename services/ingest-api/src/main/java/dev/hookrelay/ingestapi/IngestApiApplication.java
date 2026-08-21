package dev.hookrelay.ingestapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * VERI DUZLEMININ GIRISI.
 *
 * Tek isi var ve hizli yapmasi gerekiyor: olayi al, dayanikli sekilde yaz,
 * 202 don. Teslimatla ilgilenmez, HTTP atmaz, musteriye bakmaz.
 *
 * Neden 202 (Accepted) ve 200 degil: teslimat HENUZ OLMADI. 200 donmek
 * "isiniz bitti" demek olurdu ve yalan olurdu. 202 tam olarak
 * "aldim, sirada, sonucu ayri yerden takip et" anlamina gelir.
 */
@SpringBootApplication(scanBasePackages = "dev.hookrelay")
@ConfigurationPropertiesScan("dev.hookrelay")
@EnableScheduling
@org.springframework.data.jpa.repository.config.EnableJpaRepositories(
        basePackages = {"dev.hookrelay.ingestapi.repo", "dev.hookrelay.outbox"})
@org.springframework.boot.autoconfigure.domain.EntityScan(
        basePackages = {"dev.hookrelay.ingestapi.domain", "dev.hookrelay.outbox"})
public class IngestApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(IngestApiApplication.class, args);
    }
}
