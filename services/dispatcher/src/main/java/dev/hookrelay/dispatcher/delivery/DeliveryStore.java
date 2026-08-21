package dev.hookrelay.dispatcher.delivery;

import dev.hookrelay.contracts.DeliveryTask;
import dev.hookrelay.dispatcher.domain.Delivery;
import dev.hookrelay.dispatcher.repo.DeliveryAttemptRepository;
import dev.hookrelay.dispatcher.repo.DeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Teslimat durumunun veritabanina yazilmasi.
 *
 * Ayri bean olmasinin sebebi MessageWriter ile ayni: @Transactional
 * proxy uzerinden calisir, ayni siniftan yapilan cagri proxy'ye ugramaz.
 *
 * DIKKAT - TRANSACTION SINIRLARI: HTTP istegi ile veritabani transaction'i
 * ASLA ic ice olmamali. O yuzden burada iki ayri kisa transaction var:
 * gonderimden once durum okumasi, gonderimden sonra sonuc yazimi.
 * Tek bir transaction icine alsaydik, yavas bir musteri 30 saniye
 * boyunca bir veritabani baglantisini tutardi ve havuz tukenirdi.
 */
@Component
public class DeliveryStore {

    private static final Logger log = LoggerFactory.getLogger(DeliveryStore.class);

    private final DeliveryRepository deliveries;
    private final DeliveryAttemptRepository attempts;

    public DeliveryStore(DeliveryRepository deliveries, DeliveryAttemptRepository attempts) {
        this.deliveries = deliveries;
        this.attempts = attempts;
    }

    /** Teslimatin guncel durumu. Bos = bu teslimat ilk kez goruluyor. */
    @Transactional(readOnly = true)
    public Optional<Delivery.Status> currentStatus(UUID deliveryId) {
        return deliveries.findStatusById(deliveryId);
    }

    /**
     * Teslimat satirini bulur, yoksa olusturur.
     *
     * private VE @Transactional YOK - ikisi de bilincli.
     *
     * Bu metot yalnizca bu sinifin icinden, hepsi zaten @Transactional
     * olan metotlardan cagriliyor. Uzerine @Transactional yazsaydik
     * anotasyon HICBIR SEY YAPMAZDI: Spring'de transaction proxy ile
     * calisir ve ayni siniftan yapilan cagri proxy'ye ugramaz.
     *
     * Calismayan bir anotasyon, olmayan bir anotasyondan kotudur:
     * okuyan kisi "burasi kendi transaction'ini aciyor" sanir ve
     * yanlis bir zihin modeli kurar. Bkz. MessageWriter - ayni tuzagin
     * gercekten zarar verdigi yer.
     */
    private Delivery ensure(DeliveryTask task) {
        return deliveries.findById(task.deliveryId()).orElseGet(() ->
                deliveries.save(new Delivery(task.deliveryId(), task.messageId(),
                        task.applicationId(), task.endpointId(), task.eventType(), task.payload())));
    }

    /**
     * Gercek bir HTTP denemesini ve teslimatin yeni durumunu tek
     * transaction'da yazar.
     *
     * Deneme kaydi ON CONFLICT DO NOTHING ile ekleniyor - mukerrer
     * teslimatta istisna DOGMUYOR. Sebebi DeliveryAttemptRepository'de
     * uzun uzun yazili; ozeti: JPA'da kisit ihlalini yakalayip devam
     * etmek transaction'i sessizce oldurur.
     */
    @Transactional
    public void recordAttempt(DeliveryTask task, SendResult result, Delivery.Status newStatus,
                              Instant nextAttemptAt) {

        int inserted = attempts.insertIgnoringDuplicate(
                UUID.randomUUID(), task.deliveryId(), task.endpointId(), task.attempt(),
                result.httpStatus(), result.latencyMs(),
                cut(result.error(), 1000), cut(result.responseSnippet(), 2000), Instant.now());

        if (inserted == 0) {
            log.debug("Deneme zaten kayitli (mukerrer teslimat): delivery={} attempt={}",
                    task.deliveryId(), task.attempt());
        }

        Delivery delivery = ensure(task);
        String error = result.error() != null ? result.error() : "HTTP " + result.httpStatus();

        switch (newStatus) {
            case SUCCEEDED -> delivery.succeeded(task.attempt(), result.httpStatus());
            case EXHAUSTED -> delivery.exhausted(task.attempt(), result.httpStatus(), error);
            case PENDING   -> delivery.retrying(task.attempt(), result.httpStatus(), error, nextAttemptAt);
            case DISCARDED -> delivery.discarded(error);
        }
        deliveries.save(delivery);
    }

    /**
     * Hic istek atilmadan ertelenen teslimat.
     *
     * delivery_attempt'e YAZMAZ - cunku o tablo "atilan HTTP istekleri"
     * demek. Atilmayan bir istegi oraya yazmak, denetim izini yalanci
     * yapar: musteri "bize 40 istek atmissiniz" der, oysa 5 tanesi
     * gercek, 35'i devre kesici kaydidir.
     */
    @Transactional
    public void recordBlocked(DeliveryTask task, String reason, Instant nextAttemptAt) {
        Delivery delivery = ensure(task);
        delivery.blocked(reason, nextAttemptAt);
        deliveries.save(delivery);
    }

    /** Butun denemeler tukendi ama ortada yeni bir HTTP denemesi yok. */
    @Transactional
    public void markExhausted(DeliveryTask task, String reason) {
        Delivery delivery = ensure(task);
        delivery.exhausted(task.attempt(), null, reason);
        deliveries.save(delivery);
    }

    @Transactional
    public void discard(DeliveryTask task, String reason) {
        Delivery delivery = ensure(task);
        delivery.discarded(reason);
        deliveries.save(delivery);
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.substring(0, Math.min(s.length(), max));
    }
}
