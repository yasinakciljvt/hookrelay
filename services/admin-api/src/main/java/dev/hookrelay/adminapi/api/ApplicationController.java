package dev.hookrelay.adminapi.api;

import dev.hookrelay.adminapi.service.ApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/applications")
public class ApplicationController {

    private static final String KEY_WARNING =
            "Bu anahtar bir daha gosterilmeyecek. Simdi kaydedin.";

    private final ApplicationService service;

    public ApplicationController(ApplicationService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Dtos.ApplicationCreatedResponse create(
            @Valid @RequestBody Dtos.CreateApplicationRequest request) {
        var created = service.create(request.name());
        return new Dtos.ApplicationCreatedResponse(
                Dtos.ApplicationResponse.from(created.application()),
                created.plaintextApiKey(), KEY_WARNING);
    }

    @GetMapping
    public List<Dtos.ApplicationResponse> list() {
        return service.list().stream().map(Dtos.ApplicationResponse::from).toList();
    }

    @GetMapping("/{id}")
    public Dtos.ApplicationResponse get(@PathVariable UUID id) {
        return Dtos.ApplicationResponse.from(service.get(id));
    }

    @PostMapping("/{id}/rotate-key")
    public Map<String, String> rotateKey(@PathVariable UUID id) {
        return Map.of("apiKey", service.rotateKey(id), "warning", KEY_WARNING);
    }

    @PostMapping("/{id}/enabled")
    public Dtos.ApplicationResponse setEnabled(@PathVariable UUID id,
                                               @RequestParam boolean value) {
        return Dtos.ApplicationResponse.from(service.setEnabled(id, value));
    }

    /** Replikalari yeniden kurmak icin. Bkz. ApplicationService.republishAll. */
    @PostMapping("/republish")
    public ResponseEntity<Map<String, Integer>> republish() {
        return ResponseEntity.ok(Map.of("published", service.republishAll()));
    }
}
