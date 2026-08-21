package dev.hookrelay.ingestapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hookrelay.common.error.ApiException;
import dev.hookrelay.common.redis.RedisIdempotencyStore;
import dev.hookrelay.ingestapi.domain.Message;
import dev.hookrelay.ingestapi.repo.MessageRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    private final MessageRepository messages;
    private final MessageWriter writer;
    private final RedisIdempotencyStore idempotency;
    private final ObjectMapper mapper;
    private final Counter accepted;
    private final Counter duplicates;

    public IngestService(MessageRepository messages, MessageWriter writer,
                         RedisIdempotencyStore idempotency,
                         ObjectMapper mapper, MeterRegistry meters) {
        this.messages = messages;
        this.writer = writer;
        this.idempotency = idempotency;
        this.mapper = mapper;
        this.accepted = Counter.builder("hookrelay.ingest.accepted").register(meters);
        this.duplicates = Counter.builder("hookrelay.ingest.duplicates").register(meters);
    }

    public record Accepted(UUID messageId, String eventType, List<UUID> deliveryIds,
                           boolean duplicate) {}

    /**
     * IDEMPOTENCY: IKI KATMANLI, BILINCLI OLARAK
     * 1. Redis (hizli): ayni anahtari saniyeler icinde tekrar goren istegi
     *    veritabanina hic gitmeden geri cevirir. Sicak yolun korumasi.
     * 2. Postgres UNIQUE kisiti (dogru): Redis restart olsa, anahtar TTL'i
     *    dolsa, Redis tamamen kaybolsa bile ikinci kayit ACILAMAZ.
     *
     * Neden ikisi birden: Redis bir CACHE'tir, dogruluk garantisi vermez -
     * bellekten tasabilir, restart'ta bosalabilir. Tek basina Redis'e
     * guvenen bir idempotency, "cogu zaman calisan" bir idempotency'dir.
     * Tek basina veritabani ise her istekte disk I/O demek.
     * Hiz Redis'ten, dogruluk veritabanindan.
     */
    public Accepted ingest(UUID applicationId, String eventType, Object payload,
                           String idempotencyKey) {

        String payloadJson = toJson(payload);

        boolean hasKey = idempotencyKey != null && !idempotencyKey.isBlank();

        if (hasKey) {
            Optional<String> cached = idempotency.reserveOrGet(
                    applicationId.toString(), idempotencyKey, "PENDING", IDEMPOTENCY_TTL);

            if (cached.isPresent() && !"PENDING".equals(cached.get())) {
                duplicates.increment();
                return readCached(cached.get());
            }
            // "PENDING" gorduysek: ayni anahtarli baska bir istek SU AN
            // isleniyor demektir. Veritabani kisiti nasil olsa yakalayacak,
            // devam ediyoruz.
        }

        try {
            var written = writer.store(applicationId, eventType, payloadJson, idempotencyKey);
            Accepted result = new Accepted(
                    written.message().getId(), eventType, written.deliveryIds(), false);

            if (hasKey) {
                idempotency.put(applicationId.toString(), idempotencyKey,
                        toJson(result), IDEMPOTENCY_TTL);
            }
            accepted.increment();
            return result;

        } catch (DataIntegrityViolationException e) {
            // Anahtar YOKSA bu bizim bekledigimiz catisma degildir -
            // baska bir kisit patlamistir. Yutmak, gercek bir semayi
            // "idempotency catismasi" diye maskeler. Oldugu gibi yukari
            // birakiyoruz ki GlobalExceptionHandler 500 donsun ve loga
            // gercek sebep dusun.
            if (!hasKey) throw e;

            // Yaris kaybedildi: ayni anahtarla baska bir istek once yazdi.
            // Bu bir HATA DEGIL, idempotency'nin calistiginin kanitidir.
            duplicates.increment();
            Message existing = messages
                    .findByApplicationIdAndIdempotencyKey(applicationId, idempotencyKey)
                    .orElseThrow(() -> ApiException.conflict(
                            "Idempotency catismasi cozulemedi: " + idempotencyKey));

            // accepted sayaci ARTMIYOR: bu yeni bir olay degil.
            // (Onceki surumde artiyordu ve "kabul edilen olay" metrigi
            //  mukerrerleri de sayiyordu - Redis'ten donen mukerrerler
            //  ise saymiyordu. Iki yol iki farkli sey olcuyordu.)
            return new Accepted(existing.getId(), existing.getEventType(), List.of(), true);
        }
    }

    @Transactional(readOnly = true)
    public List<Message> recent(UUID applicationId) {
        return messages.findTop50ByApplicationIdOrderByCreatedAtDesc(applicationId);
    }

    @Transactional(readOnly = true)
    public Message get(UUID applicationId, UUID messageId) {
        Message m = messages.findById(messageId)
                .orElseThrow(() -> ApiException.notFound("Mesaj", messageId));
        if (!m.getApplicationId().equals(applicationId)) {
            // Baska kiracinin mesaji. 403 degil 404: kaynagin VARLIGINI bile sizdirmayiz.
            throw ApiException.notFound("Mesaj", messageId);
        }
        return m;
    }

    private String toJson(Object o) {
        try {
            return o instanceof String s ? s : mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw ApiException.badRequest("Govde JSON'a cevrilemedi: " + e.getMessage());
        }
    }

    private Accepted readCached(String json) {
        try {
            Accepted a = mapper.readValue(json, Accepted.class);
            return new Accepted(a.messageId(), a.eventType(), a.deliveryIds(), true);
        } catch (Exception e) {
            // Saklanan cevap okunamiyor. Sessizce yeni bir mesaj
            // olusturmak YANLIS olurdu - idempotency sozunu bozardi.
            // 409 donup istemcinin tekrar denemesini istiyoruz.
            log.warn("Saklanan idempotency cevabi ayristirilamadi: {}", json, e);
            throw ApiException.conflict("Ayni Idempotency-Key ile islem devam ediyor");
        }
    }
}
