package dev.hookrelay.retryscheduler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * GECIKMELI YENIDEN DENEME ZAMANLAYICISI.
 *
 * Projedeki en ilginc servis. Kafka'nin desteklemedigi bir seyi -
 * "bu mesaji 30 dakika sonra isle" - Kafka'nin kendi ilkelleriyle kurar.
 *
 * Ayri bir servis olmasinin sebebi: tuketim semantigi dispatcher'dan
 * tamamen farkli. Dispatcher hizli tuketir ve HTTP atar; bu servis
 * cogu zaman duraklatilmis (paused) partition'larla oturur ve saate bakar.
 * Ikisini ayni surece koymak, birinin ayarlarinin digerini bozmasi demekti.
 */
@SpringBootApplication(scanBasePackages = "dev.hookrelay")
@ConfigurationPropertiesScan("dev.hookrelay")
public class RetrySchedulerApplication {
    public static void main(String[] args) {
        SpringApplication.run(RetrySchedulerApplication.class, args);
    }
}
