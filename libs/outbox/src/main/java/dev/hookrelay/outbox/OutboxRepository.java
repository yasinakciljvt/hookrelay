package dev.hookrelay.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Yayinlanmamis kayitlari SIRAYLA kilitleyerek al.
     *
     * FOR UPDATE SKIP LOCKED: baska bir poller ornegi ayni satirlari
     * kilitlemisse bekleme, atla ve sonrakini al. Bu olmadan iki ornek
     * ayni satirda birbirini bekler ve tek ornek hizinda calisirsiniz.
     *
     * ORDER BY created_at, id: ayni endpoint'e giden olaylarin sirasi
     * korunsun diye. (Tek poller ornegi icin yeterli. Cok ornekte sira
     * ornekler ARASINDA bozulabilir - bkz. OutboxPublisher javadoc.)
     */
    @Query(value = """
            SELECT * FROM outbox_event
            WHERE published_at IS NULL
            ORDER BY created_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockUnpublished(@Param("limit") int limit);

    long countByPublishedAtIsNull();

    @Query("DELETE FROM OutboxEvent o WHERE o.publishedAt IS NOT NULL AND o.publishedAt < :before")
    @org.springframework.data.jpa.repository.Modifying
    int deletePublishedBefore(@Param("before") Instant before);
}
