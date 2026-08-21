package dev.hookrelay.adminapi.service;

import dev.hookrelay.adminapi.domain.WebhookApplication;
import dev.hookrelay.adminapi.repo.ApplicationRepository;
import dev.hookrelay.common.crypto.ApiKeys;
import dev.hookrelay.common.error.ApiException;
import dev.hookrelay.contracts.ApplicationConfig;
import dev.hookrelay.contracts.Topics;
import dev.hookrelay.outbox.OutboxRecorder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ApplicationService {

    private final ApplicationRepository repository;
    private final OutboxRecorder outbox;

    public ApplicationService(ApplicationRepository repository, OutboxRecorder outbox) {
        this.repository = repository;
        this.outbox = outbox;
    }

    /** Duz metin API anahtari SADECE burada, SADECE bir kez doner. */
    public record Created(WebhookApplication application, String plaintextApiKey) {}

    @Transactional
    public Created create(String name) {
        if (repository.existsByName(name)) {
            throw ApiException.conflict("Bu isimde bir uygulama zaten var: " + name);
        }
        String key = ApiKeys.generate();
        WebhookApplication app = new WebhookApplication(name, ApiKeys.hash(key), ApiKeys.preview(key));
        repository.save(app);
        publish(app);   // AYNI transaction - outbox'in tum anlami bu
        return new Created(app, key);
    }

    @Transactional
    public String rotateKey(UUID id) {
        WebhookApplication app = get(id);
        String key = ApiKeys.generate();
        app.rotateKey(ApiKeys.hash(key), ApiKeys.preview(key));
        publish(app);
        return key;
    }

    @Transactional
    public WebhookApplication setEnabled(UUID id, boolean enabled) {
        WebhookApplication app = get(id);
        app.setEnabled(enabled);
        publish(app);
        return app;
    }

    /**
     * Butun uygulamalari compacted topic'e yeniden basar.
     *
     * Ne zaman lazim: Kafka'yi sifirdan kurdunuz, ya da replika bozuldu.
     * Kaynak dogru (veritabani), turetilmis olan yeniden uretilebilir -
     * saglikli bir mimarinin isareti tam olarak budur.
     */
    @Transactional
    public int republishAll() {
        List<WebhookApplication> all = repository.findAll();
        all.forEach(this::publish);
        return all.size();
    }

    @Transactional(readOnly = true)
    public List<WebhookApplication> list() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public WebhookApplication get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Uygulama", id));
    }

    private void publish(WebhookApplication app) {
        outbox.record("application", app.getId().toString(),
                Topics.APP_CONFIG, app.getId().toString(),
                new ApplicationConfig(app.getId(), app.getName(), app.getApiKeyHash(),
                        app.isEnabled(), false, app.getVersion()));
    }
}
