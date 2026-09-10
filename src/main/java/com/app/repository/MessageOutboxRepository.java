package com.app.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.app.entity.MessageOutbox;
import com.app.entity.MessageOutbox.MessageType;
import com.app.entity.MessageOutbox.Status;

@Repository
public interface MessageOutboxRepository extends JpaRepository<MessageOutbox, Long> {

    /**
     * Used to skip work that is already queued or sent, before building a message.
     * The unique constraint on the column is the real guarantee; this avoids
     * relying on a caught constraint violation for the ordinary case.
     */
    Optional<MessageOutbox> findByIdempotencyKey(String idempotencyKey);

    boolean existsByIdempotencyKey(String idempotencyKey);

    /**
     * The dispatch queue: anything never attempted, plus anything that failed and
     * has attempts left. Oldest first, so a backlog drains in the order it built up.
     *
     * The attempt cap is a parameter rather than baked in, so the dispatcher owns
     * the retry policy and this query stays a plain read.
     */
    @Query("""
            SELECT m FROM MessageOutbox m
             WHERE m.status = com.app.entity.MessageOutbox$Status.PENDING
                OR (m.status = com.app.entity.MessageOutbox$Status.FAILED AND m.attempts < :maxAttempts)
             ORDER BY m.id ASC
            """)
    List<MessageOutbox> findDispatchable(@Param("maxAttempts") int maxAttempts, Limit limit);

    List<MessageOutbox> findByStatusOrderByIdDesc(Status status, Limit limit);

    List<MessageOutbox> findByCustomerIdOrderByIdDesc(Long customerId, Limit limit);

    List<MessageOutbox> findByMessageTypeAndReferenceDateOrderByIdAsc(MessageType messageType, LocalDate referenceDate);

    /** Counts by status for one day, for the dashboard and for a morning check. */
    @Query("""
            SELECT m.status, COUNT(m) FROM MessageOutbox m
             WHERE m.referenceDate = :referenceDate
             GROUP BY m.status
            """)
    List<Object[]> countByStatusForDate(@Param("referenceDate") LocalDate referenceDate);
}
