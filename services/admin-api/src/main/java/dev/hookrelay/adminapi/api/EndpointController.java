package dev.hookrelay.adminapi.api;

import dev.hookrelay.adminapi.domain.EndpointHealth;
import dev.hookrelay.adminapi.repo.EndpointHealthRepository;
import dev.hookrelay.adminapi.service.EndpointService;
import dev.hookrelay.common.redis.RedisCircuitBreaker;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class EndpointController {

    private final EndpointService service;
    private final EndpointHealthRepository health;
    private final RedisCircuitBreaker breaker;

    public EndpointController(EndpointService service, EndpointHealthRepository health,
                              RedisCircuitBreaker breaker) {
        this.service = service;
        this.health = health;
        this.breaker = breaker;
    }

    @PostMapping("/applications/{applicationId}/endpoints")
    @ResponseStatus(HttpStatus.CREATED)
    public Dtos.EndpointResponse create(@PathVariable UUID applicationId,
                                        @Valid @RequestBody Dtos.CreateEndpointRequest r) {
        return Dtos.EndpointResponse.from(service.create(applicationId, r.url(), r.description(),
                r.eventTypes(), r.rateLimitPerSecond(), r.maxAttempts(), r.timeoutMs(), r.secret()));
    }

    @GetMapping("/applications/{applicationId}/endpoints")
    public List<Dtos.EndpointResponse> listByApp(@PathVariable UUID applicationId) {
        return service.listByApplication(applicationId).stream()
                .map(Dtos.EndpointResponse::from).toList();
    }

    @GetMapping("/endpoints")
    public List<Dtos.EndpointResponse> listAll() {
        return service.listAll().stream().map(Dtos.EndpointResponse::from).toList();
    }

    @GetMapping("/endpoints/{id}")
    public Dtos.EndpointResponse get(@PathVariable UUID id) {
        return Dtos.EndpointResponse.from(service.get(id));
    }

    @PatchMapping("/endpoints/{id}")
    public Dtos.EndpointResponse update(@PathVariable UUID id,
                                        @RequestBody Dtos.UpdateEndpointRequest r) {
        return Dtos.EndpointResponse.from(service.update(id, r.url(), r.description(),
                r.eventTypes(), r.enabled(), r.rateLimitPerSecond(), r.maxAttempts(), r.timeoutMs()));
    }

    @DeleteMapping("/endpoints/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    /**
     * Saglik = projeksiyon tablosu + Redis'teki canli devre durumu.
     * Iki farkli kaynak: biri olaylardan turetilmis gecmis, digeri anlik durum.
     */
    @GetMapping("/endpoints/{id}/health")
    public Dtos.EndpointHealthResponse health(@PathVariable UUID id) {
        String state = breaker.state(id).name();
        return health.findById(id)
                .map(h -> Dtos.EndpointHealthResponse.from(h, state))
                .orElseGet(() -> Dtos.EndpointHealthResponse.empty(id, state));
    }

    @GetMapping("/applications/{applicationId}/health")
    public List<Dtos.EndpointHealthResponse> healthByApp(@PathVariable UUID applicationId) {
        List<EndpointHealth> rows = health.findByApplicationId(applicationId);
        return rows.stream()
                .map(h -> Dtos.EndpointHealthResponse.from(h,
                        breaker.state(h.getEndpointId()).name()))
                .toList();
    }

    @PostMapping("/endpoints/republish")
    public Map<String, Integer> republish() {
        return Map.of("published", service.republishAll());
    }
}
