package dev.hookrelay.adminapi.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hookrelay.adminapi.domain.EndpointHealth;
import dev.hookrelay.adminapi.repo.EndpointHealthRepository;
import dev.hookrelay.contracts.DeliveryResult;
import dev.hookrelay.contracts.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * delivery-results topic'inden endpoint_health projeksiyonunu kurar.
 *
 * Bu sinif, dispatcher'da TEK SATIR degistirmeden eklendi. Olay tabanli
 * mimarinin somut kazanci bu: yeni bir okuyucu eklemek, yazani
 * ilgilendirmiyor. Yarin bir bildirim servisi ayni topic'i dinleyip
 * "endpoint'iniz 10 kez ust uste hata verdi" maili atabilir -
 * yine dispatcher'a dokunmadan.
 */
@Component
public class DeliveryResultProjector {

    private static final Logger log = LoggerFactory.getLogger(DeliveryResultProjector.class);

    private final EndpointHealthRepository repository;
    private final ObjectMapper mapper;

    public DeliveryResultProjector(EndpointHealthRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @KafkaListener(topics = Topics.DELIVERY_RESULTS, groupId = "admin-health-projection")
    @Transactional
    public void onResult(String payload) {
        try {
            DeliveryResult r = mapper.readValue(payload, DeliveryResult.class);

            EndpointHealth h = repository.findById(r.endpointId())
                    .orElseGet(() -> new EndpointHealth(r.endpointId(), r.applicationId()));

            // GERCEK DENEME AYRIMI
            //
            // Ilk surum "SUCCEEDED degilse hatadir" diyordu. Sonuc: silinmis
            // bir endpoint'in 25 bin dusurulmus teslimati, o endpoint'in
            // "25 bin kez hata verdigi" gibi gorunuyordu - oysa ona tek bir
            // istek bile atilmamisti. Basari orani da anlamsizlasiyordu.
            if (!r.outcome().isRealAttempt()) {
                if (r.outcome() == DeliveryResult.Outcome.SHORT_CIRCUITED) {
                    h.recordShortCircuit(r.occurredAt());
                    repository.save(h);
                }
                // DISCARDED: endpoint zaten yok, saglik kaydini kirletmeye gerek yok.
                return;
            }

            if (r.outcome() == DeliveryResult.Outcome.SUCCEEDED) {
                h.recordSuccess(r.httpStatus(), r.latencyMs(), r.occurredAt());
            } else {
                h.recordFailure(r.httpStatus(), r.latencyMs(), r.error(), r.occurredAt());
            }
            repository.save(h);
        } catch (Exception e) {
            // Projeksiyon kirilirsa TESLIMAT DURMAZ. Bu tuketici bagimsiz.
            log.error("Teslimat sonucu islenemedi: {}", payload, e);
        }
    }
}
