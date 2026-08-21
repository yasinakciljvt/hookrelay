package dev.hookrelay.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * TEK GIRIS KAPISI.
 *
 * Disaridan gelen her istek buradan gecer, arkadaki 5 servise dagitilir.
 * Kazanci: istemci (arayuz, musteri, k6) tek bir adres bilir; servisler
 * bolunse, tasinsa, portu degisse istemci etkilenmez.
 *
 * Neden Eureka yok: Docker Compose'da servis adlari zaten DNS ile
 * cozuluyor (http://dispatcher:8083). Servis kesfi icin ayri bir
 * bilesen calistirmak, cozdugu problemden buyuk bir problem eklemek olur.
 * Kubernetes'e gecilirse Service kaynagi ayni isi gorur - yine Eureka
 * gerekmez. Eureka'nin yeri, dinamik olceklenen ve DNS'i olmayan
 * ortamlardir.
 */
@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
