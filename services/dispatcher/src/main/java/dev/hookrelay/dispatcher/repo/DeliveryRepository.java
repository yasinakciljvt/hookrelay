package dev.hookrelay.dispatcher.repo;

import dev.hookrelay.dispatcher.domain.Delivery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

    Page<Delivery> findByEndpointIdOrderByCreatedAtDesc(UUID endpointId, Pageable pageable);

    Page<Delivery> findByApplicationIdOrderByCreatedAtDesc(UUID applicationId, Pageable pageable);

    Page<Delivery> findByStatusOrderByUpdatedAtDesc(Delivery.Status status, Pageable pageable);

    /**
     * Belirli bir endpoint'in belirli durumdaki teslimatlari.
     *
     * Neden ayri metot: toplu yeniden gonderim once 500 kayit cekip
     * sonra Java'da endpoint'e gore filtreliyordu. Sonuc: "en fazla
     * 500 gonder" dediginizde, o 500'un icinde o endpoint'ten sadece
     * 12 tane varsa 12 tane gonderiliyordu - sessizce eksik is.
     * Filtreleme veritabaninda yapilmali ki limit ne dediyse o olsun.
     */
    List<Delivery> findByEndpointIdAndStatusOrderByUpdatedAtDesc(
            UUID endpointId, Delivery.Status status, Pageable pageable);

    List<Delivery> findByMessageId(UUID messageId);

    long countByStatus(Delivery.Status status);

    long countByEndpointIdAndStatus(UUID endpointId, Delivery.Status status);

    @Query("SELECT d.status, COUNT(d) FROM Delivery d GROUP BY d.status")
    List<Object[]> countGroupedByStatus();

    /**
     * Sadece durum sutunu. Bos Optional = teslimat henuz yok.
     *
     * Onceki surum "isAlreadySucceeded" adinda bir boolean donduruyordu.
     * Durumun kendisini dondurmek ayni maliyette ama cagirana daha cok
     * sey soyluyor - ve loglara "zaten DISCARDED" gibi anlamli bir
     * mesaj yazilabiliyor.
     */
    @Query("SELECT d.status FROM Delivery d WHERE d.id = :id")
    Optional<Delivery.Status> findStatusById(@Param("id") UUID id);
}
