package dev.hookrelay.adminapi.repo;

import dev.hookrelay.adminapi.domain.WebhookApplication;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ApplicationRepository extends JpaRepository<WebhookApplication, UUID> {
    Optional<WebhookApplication> findByName(String name);
    boolean existsByName(String name);
}
