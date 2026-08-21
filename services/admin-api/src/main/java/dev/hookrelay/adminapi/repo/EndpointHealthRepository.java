package dev.hookrelay.adminapi.repo;

import dev.hookrelay.adminapi.domain.EndpointHealth;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EndpointHealthRepository extends JpaRepository<EndpointHealth, UUID> {
    List<EndpointHealth> findByApplicationId(UUID applicationId);
}
