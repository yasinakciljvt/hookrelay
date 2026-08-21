package dev.hookrelay.dispatcher.repo;

import dev.hookrelay.dispatcher.domain.DeliveryAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, UUID> {

    List<DeliveryAttempt> findByDeliveryIdOrderByAttemptAsc(UUID deliveryId);

    List<DeliveryAttempt> findTop100ByEndpointIdOrderByOccurredAtDesc(UUID endpointId);

    /**
     * MUKERRER DENEMEYI ISTISNA ATMADAN YUTAR.
     *
     *  BURADA CIDDI BIR HATA VARDI - VE COK YAYGIN BIR HATADIR
     *
     * Ilk yazilisi soyleydi:
     *
     *     try {
     *         attempts.save(new DeliveryAttempt(...));
     *     } catch (DataIntegrityViolationException e) {
     *         log.debug("zaten kayitli, sorun degil");
     *     }
     *     deliveries.save(delivery);          // ← ARTIK CALISMAZ
     *
     * Kulaga makul geliyor: kisit patliyor, yutuyoruz, devam ediyoruz.
     * Ama JPA'da BOYLE CALISMAZ.
     *
     * Hibernate bir kisit ihlali gordugunde persistence context'i
     * TUTARSIZ kabul eder ve transaction'i "yalnizca geri sarilabilir"
     * (rollback-only) olarak isaretler. Istisnayi yakalayip devam
     * etmeniz bir sey degistirmez: sonraki yazmalar calisir gibi
     * gorunur, sonra commit aninda UnexpectedRollbackException gelir
     * ve TUM transaction geri sarilir.
     *
     * Sonuc: mukerrer teslimat geldiginde - ki bu Kafka'da NORMAL bir
     * durumdur - teslimatin durum guncellemesi de kaybolur. Hata
     * mesaji da suclunun yerini gostermez.
     *
     * COZUM: istisnayi hic dogurmamak. Postgres'in ON CONFLICT'i
     * catismayi veritabani icinde, atomik olarak, sessizce yutar.
     * Hibernate'in haberi bile olmaz.
     *
     * @return eklendiyse 1, catisma nedeniyle atlandiysa 0
     */
    @Modifying
    @Query(value = """
            INSERT INTO delivery_attempt
                (id, delivery_id, endpoint_id, attempt, http_status,
                 latency_ms, error, response_snippet, occurred_at)
            VALUES (:id, :deliveryId, :endpointId, :attempt, :httpStatus,
                    :latencyMs, :error, :snippet, :occurredAt)
            ON CONFLICT (delivery_id, attempt) DO NOTHING
            """, nativeQuery = true)
    int insertIgnoringDuplicate(@Param("id") UUID id,
                                @Param("deliveryId") UUID deliveryId,
                                @Param("endpointId") UUID endpointId,
                                @Param("attempt") int attempt,
                                @Param("httpStatus") Integer httpStatus,
                                @Param("latencyMs") long latencyMs,
                                @Param("error") String error,
                                @Param("snippet") String snippet,
                                @Param("occurredAt") Instant occurredAt);
}
