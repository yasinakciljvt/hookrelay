package dev.hookrelay.adminapi.repo;

import dev.hookrelay.adminapi.domain.Endpoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EndpointRepository extends JpaRepository<Endpoint, UUID> {
    List<Endpoint> findByApplicationIdOrderByCreatedAtDesc(UUID applicationId);
}
