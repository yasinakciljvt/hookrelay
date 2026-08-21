package dev.hookrelay.dispatcher.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;

@Configuration
public class HttpClientConfig {

    /**
     * Giden webhook'lar icin tek, paylasilan HttpClient.
     *
     * SANAL IS PARCACIKLARI (Java 21)
     * executor olarak newVirtualThreadPerTaskExecutor verildi. Sebep:
     * bu servisin yaptigi is neredeyse tamamen "istek at, cevabi bekle".
     * Klasik platform is parcacigi bu beklemede 1 MB yigin tutar ve
     * isletim sistemi is parcacigidir - birkac bin tanesini acamazsiniz.
     * Sanal is parcacigi beklerken serbest birakilir, on binlercesi
     * acilabilir.
     *
     * Yavas bir musteri artik "bir is parcacigini isgal eden" degil,
     * "bir sanal is parcacigini bekleten" bir sey. Bu, bulkhead'in
     * isini kolaylastirir ama YERINI ALMAZ: sanal is parcacigi bedava
     * olsa da uzaktaki sunucunun baglanti kotasi bedava degil.
     *
     * followRedirects NEVER: webhook'ta yonlendirme takip etmek
     * guvenlik acigidir. Musteri URL'i bir yere yonlendirirse imzali
     * istegimiz bilmedigimiz bir sunucuya gider (SSRF).
     */
    @Bean
    public HttpClient webhookHttpClient(
            @Value("${hookrelay.http.connect-timeout-ms:5000}") long connectTimeoutMs) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }
}
