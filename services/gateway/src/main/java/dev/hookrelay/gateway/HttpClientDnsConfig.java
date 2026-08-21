package dev.hookrelay.gateway;

import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Reactor Netty'yi JVM'in DNS cozumleyicisine yonlendirir.
 *
 * Yasanan hata: bir arka servis yeniden olusturulunca gateway ona giden
 * her istegi "Connection refused: dispatcher/172.23.0.10" ile reddediyordu
 * -- oysa konteynerin yeni adresi .11 idi ve gateway'in icinden wget
 * calisiyordu.
 *
 * Sebep: Reactor Netty kendi DNS onbellegini kullanir ve Docker'in gomulu
 * DNS'i konteyner adlari icin TTL 600 doner. Yani on dakika eski IP.
 * JVM cozumleyicisi networkaddress.cache.ttl'e uyar; Dockerfile'da 10 sn.
 *
 * Ayni problem Kubernetes'te de var: pod yeniden planlaninca IP degisir.
 */
@Configuration
public class HttpClientDnsConfig {

    @Bean
    public HttpClientCustomizer jvmDnsResolverCustomizer() {
        return httpClient -> httpClient.resolver(DefaultAddressResolverGroup.INSTANCE);
    }
}
