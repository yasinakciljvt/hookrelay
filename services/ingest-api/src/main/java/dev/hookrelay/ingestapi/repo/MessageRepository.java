package dev.hookrelay.ingestapi.repo;

import dev.hookrelay.ingestapi.domain.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    Optional<Message> findByApplicationIdAndIdempotencyKey(UUID applicationId, String key);

    List<Message> findTop50ByApplicationIdOrderByCreatedAtDesc(UUID applicationId);
}
