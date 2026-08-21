package dev.hookrelay.chaostarget;

import dev.hookrelay.common.crypto.WebhookSigner;
import dev.hookrelay.contracts.Headers;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/sink")
public class ChaosController {

    private static final Logger log = LoggerFactory.getLogger(ChaosController.class);
    private static final int MEMORY = 500;

    private final WebhookSigner signer;

    /** Son N istegin kaydi. Demoda "gercekten geldi mi" sorusunun cevabi. */
    private final Deque<Received> received = new ConcurrentLinkedDeque<>();
    private final Map<String, AtomicLong> counters = new java.util.concurrent.ConcurrentHashMap<>();

    public ChaosController(WebhookSigner signer) {
        this.signer = signer;
    }

    public record Received(String behavior, String deliveryId, String eventType,
                           int attempt, boolean signatureValid, int respondedWith,
                           Instant at) {}

    @PostMapping("/ok")
    public ResponseEntity<String> ok(@RequestBody(required = false) String body,
                                     HttpServletRequest request) {
        record("ok", request, 200, null);
        return ResponseEntity.ok("{\"ok\":true}");
    }

    /**
     * Yuzde rate ihtimalle 500 doner.
     *
     * Demonun kalbi: %50 ile 1000 mesaj gonderin, yarisi ilk denemede
     * gecer, yarisi t1'e duser, onun yarisi t2'ye... Gozunuzle
     * ustel azalmayi izlersiniz.
     */
    @PostMapping("/flaky")
    public ResponseEntity<String> flaky(@RequestParam(defaultValue = "50") int rate,
                                        @RequestBody(required = false) String body,
                                        HttpServletRequest request) {
        boolean fail = ThreadLocalRandom.current().nextInt(100) < rate;
        int status = fail ? 500 : 200;
        record("flaky", request, status, null);
        return ResponseEntity.status(status)
                .body(fail ? "{\"error\":\"rastgele hata\"}" : "{\"ok\":true}");
    }

    /** Zaman asimi testi. endpoint.timeoutMs'ten buyuk verin. */
    @PostMapping("/slow")
    public ResponseEntity<String> slow(@RequestParam(defaultValue = "30000") long ms,
                                       @RequestBody(required = false) String body,
                                       HttpServletRequest request) throws InterruptedException {
        Thread.sleep(Math.min(ms, 120_000));
        record("slow", request, 200, null);
        return ResponseEntity.ok("{\"ok\":true,\"slow\":true}");
    }

    /** 429 + Retry-After. Dispatcher'in bu basliga saygi gosterdigini kanitlar. */
    @PostMapping("/ratelimited")
    public ResponseEntity<String> rateLimited(@RequestParam(defaultValue = "30") int retryAfter,
                                              @RequestBody(required = false) String body,
                                              HttpServletRequest request) {
        record("ratelimited", request, 429, null);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(retryAfter))
                .body("{\"error\":\"cok hizli\"}");
    }

    /** Tamamen olmus sunucu. Devre kesiciyi acan senaryo. */
    @PostMapping("/dead")
    public ResponseEntity<String> dead(@RequestBody(required = false) String body,
                                       HttpServletRequest request) {
        record("dead", request, 500, null);
        return ResponseEntity.status(500).body("{\"error\":\"sunucu olu\"}");
    }

    /** 410 Gone - KALICI hata. Yeniden denenmeden DLQ'ya gitmeli. */
    @PostMapping("/gone")
    public ResponseEntity<String> gone(@RequestBody(required = false) String body,
                                       HttpServletRequest request) {
        record("gone", request, 410, null);
        return ResponseEntity.status(HttpStatus.GONE).body("{\"error\":\"bu endpoint kaldirildi\"}");
    }

    /**
     * IMZA DOGRULAYAN uc.
     *
     * Bu, projenin guvenlik iddiasinin CALISAN kaniti. Dokuman
     * "HMAC ile imzaliyoruz" der; burasi imzayi gercekten dogrular ve
     * yanlissa 401 doner. secret query parametresinden gelir cunku
     * bu bir demo sunucusu, gercek bir musteri degil.
     */
    @PostMapping("/strict")
    public ResponseEntity<String> strict(@RequestParam String secret,
                                         @RequestBody(required = false) String body,
                                         HttpServletRequest request) {
        String header = request.getHeader(Headers.OUT_SIGNATURE);
        boolean valid = signer.verify(secret, body == null ? "" : body, header, 300);
        record("strict", request, valid ? 200 : 401, valid);

        if (!valid) {
            log.warn("Imza dogrulanamadi: {}", header);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("{\"error\":\"imza gecersiz\"}");
        }
        return ResponseEntity.ok("{\"ok\":true,\"signature\":\"dogrulandi\"}");
    }

    @GetMapping("/received")
    public List<Received> received(@RequestParam(defaultValue = "50") int limit) {
        return received.stream().limit(Math.min(limit, MEMORY)).toList();
    }

    @GetMapping("/counters")
    public Map<String, Long> counters() {
        Map<String, Long> out = new LinkedHashMap<>();
        counters.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }

    @DeleteMapping("/received")
    public Map<String, String> clear() {
        received.clear();
        counters.clear();
        return Map.of("status", "temizlendi");
    }

    private void record(String behavior, HttpServletRequest request, int status, Boolean sigValid) {
        String deliveryId = request.getHeader(Headers.OUT_ID);
        String eventType = request.getHeader(Headers.OUT_EVENT_TYPE);
        int attempt = parseInt(request.getHeader(Headers.OUT_ATTEMPT));

        received.addFirst(new Received(behavior, deliveryId, eventType, attempt,
                sigValid != null && sigValid, status, Instant.now()));
        while (received.size() > MEMORY) received.pollLast();

        counters.computeIfAbsent(behavior + ":" + status, k -> new AtomicLong()).incrementAndGet();
        counters.computeIfAbsent("toplam", k -> new AtomicLong()).incrementAndGet();
    }

    private int parseInt(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
