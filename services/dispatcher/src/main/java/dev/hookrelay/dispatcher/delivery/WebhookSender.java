package dev.hookrelay.dispatcher.delivery;

import dev.hookrelay.common.crypto.WebhookSigner;
import dev.hookrelay.contracts.Headers;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Tek isi: imzali HTTP POST atmak ve ne oldugunu duzgunce raporlamak.
 *
 * Karar VERMEZ (yeniden denenecek mi, DLQ'ya mi gidecek) - o
 * DeliveryProcessor'in isi. Bu ayrim, sinifi tek basina test
 * edilebilir kiliyor.
 */
@Component
public class WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);
    private static final int SNIPPET_MAX = 2000;

    private final HttpClient client;
    private final WebhookSigner signer;
    private final Timer timer;

    public WebhookSender(HttpClient client, WebhookSigner signer, MeterRegistry meters) {
        this.client = client;
        this.signer = signer;
        this.timer = Timer.builder("hookrelay.dispatch.http")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meters);
    }

    public SendResult send(DeliveryContext ctx) {
        var endpoint = ctx.endpoint();
        var task = ctx.task();
        Instant now = Instant.now();
        String signature = signer.sign(endpoint.secret(), task.payload(), now);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint.url()))
                .timeout(Duration.ofMillis(endpoint.timeoutMs()))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("User-Agent", "HookRelay/1.0")
                // Alicinin idempotency anahtari BUDUR. "En az bir kez"
                // teslimat yaptigimiz icin ayni id ile ikinci kez
                // gelebiliriz; alicinin bunu ayirt etmesi lazim.
                .header(Headers.OUT_ID, task.deliveryId().toString())
                .header(Headers.OUT_TIMESTAMP, String.valueOf(now.getEpochSecond()))
                .header(Headers.OUT_SIGNATURE, signature)
                .header(Headers.OUT_EVENT_TYPE, task.eventType())
                .header(Headers.OUT_ATTEMPT, String.valueOf(task.attempt()))
                .POST(HttpRequest.BodyPublishers.ofString(task.payload()))
                .build();

        long start = System.nanoTime();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long latency = elapsedMs(start);
            timer.record(Duration.ofMillis(latency));

            return new SendResult(
                    response.statusCode(),
                    latency,
                    snippet(response.body()),
                    null,
                    parseRetryAfter(response).orElse(null));

        } catch (HttpTimeoutException e) {
            long latency = elapsedMs(start);
            timer.record(Duration.ofMillis(latency));
            return new SendResult(null, latency, null,
                    "Zaman asimi (" + endpoint.timeoutMs() + " ms)", null);

        } catch (IOException e) {
            long latency = elapsedMs(start);
            timer.record(Duration.ofMillis(latency));
            // DNS coz-ulemedi, baglanti reddedildi, TLS el sikismasi basarisiz...
            return new SendResult(null, latency, null,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), null);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SendResult(null, elapsedMs(start), null, "Kesildi", null);

        } catch (Exception e) {
            log.error("Beklenmeyen gonderim hatasi: endpoint={}", endpoint.endpointId(), e);
            return new SendResult(null, elapsedMs(start), null,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), null);
        }
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private String snippet(String body) {
        if (body == null) return null;
        return body.length() <= SNIPPET_MAX ? body : body.substring(0, SNIPPET_MAX);
    }

    /**
     * Retry-After iki bicimde gelebilir (RFC 9110):
     *   Retry-After: 120                              → saniye
     *   Retry-After: Wed, 21 Oct 2026 07:28:00 GMT    → HTTP tarihi
     * Ikisini de destekliyoruz; cogu istemci ikincisini atlar ve
     * 429'lari yanlis zamanlar.
     */
    private Optional<Duration> parseRetryAfter(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After").flatMap(raw -> {
            String value = raw.trim();
            try {
                long seconds = Long.parseLong(value);
                return seconds >= 0 ? Optional.of(Duration.ofSeconds(seconds)) : Optional.empty();
            } catch (NumberFormatException ignored) {
                // sayi degilse tarih olabilir
            }
            try {
                Instant when = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(value));
                Duration d = Duration.between(Instant.now(), when);
                return d.isNegative() ? Optional.empty() : Optional.of(d);
            } catch (Exception e) {
                return Optional.empty();
            }
        });
    }
}
