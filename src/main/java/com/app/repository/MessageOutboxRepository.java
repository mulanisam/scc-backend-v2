package com.app.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.app.entity.MessageOutbox;
import com.app.entity.MessageOutbox.Channel;
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
     *
     * Weekly statements are deliberately excluded. They are media messages - the PDF is
     * uploaded and the send names the returned media id - so the ordinary text dispatcher
     * cannot send one; it would post the covering message with no statement attached.
     * WeeklyStatementJob owns that queue. Excluding them here rather than only refusing
     * them at send time also keeps them from filling the batch of 50 and starving the
     * daily messages behind them: 250 queued statements would otherwise take every slot
     * in every run, forever.
     */
    @Query("""
            SELECT m FROM MessageOutbox m
             WHERE m.messageType <> com.app.entity.MessageOutbox$MessageType.WEEKLY_STATEMENT
               AND (m.status = com.app.entity.MessageOutbox$Status.PENDING
                    OR (m.status = com.app.entity.MessageOutbox$Status.FAILED
                        AND m.attempts < :maxAttempts))
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

    /**
     * Accepted but not yet known to have arrived, and recent enough that the provider
     * still holds a log for it. Oldest first, so a backlog clears in order.
     */
    @Query("""
            SELECT m FROM MessageOutbox m
             WHERE m.status = :status
               AND m.providerMessageId IS NOT NULL
               AND m.sentAt >= :since
             ORDER BY m.sentAt ASC
            """)
    List<MessageOutbox> findAwaitingDelivery(@Param("status") Status status,
                                            @Param("since") LocalDateTime since);

    /** How many times this message has already been resent. */
    long countByIdempotencyKeyStartingWith(String prefix);

    /** Counts by channel and status across a range, for the messaging dashboard. */
    @Query("""
            SELECT m.channel, m.status, COUNT(m) FROM MessageOutbox m
             WHERE m.referenceDate BETWEEN :from AND :to
             GROUP BY m.channel, m.status
            """)
    List<Object[]> countByChannelAndStatus(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * The dashboard's message list, filtered the way the screen filters: by channel,
     * by status, by kind, or none of them. Written as one query with null-tolerant
     * predicates rather than an overload per combination.
     */
    @Query("""
            SELECT m FROM MessageOutbox m
             WHERE (:channel IS NULL OR m.channel = :channel)
               AND (:status IS NULL OR m.status = :status)
               AND (:messageType IS NULL OR m.messageType = :messageType)
               AND (:from IS NULL OR m.referenceDate >= :from)
               AND (:to IS NULL OR m.referenceDate <= :to)
             ORDER BY m.id DESC
            """)
    List<MessageOutbox> search(@Param("channel") Channel channel,
                              @Param("status") Status status,
                              @Param("messageType") MessageType messageType,
                              @Param("from") LocalDate from,
                              @Param("to") LocalDate to,
                              Limit limit);

    /** One kind of message in one state - the weekly statement run's own queue. */
    List<MessageOutbox> findByMessageTypeAndStatus(MessageType messageType, Status status, Limit limit);

    /**
     * Counts by status for each period one kind of message covers.
     *
     * The weekly statement screen's rollup. Grouped by reference_date because for a
     * statement that column is the week it covers, so one group is one week's run - which
     * is the unit somebody asks about ("did last week's statements go out?"), not the day
     * the send happened. A run is spread over whatever days it was retried on, and a
     * resend queued on Thursday belongs to Sunday's week, not to Thursday.
     */
    @Query("""
            SELECT m.referenceDate, m.status, COUNT(m) FROM MessageOutbox m
             WHERE m.messageType = :messageType
             GROUP BY m.referenceDate, m.status
             ORDER BY m.referenceDate DESC
            """)
    List<Object[]> countByPeriodAndStatus(@Param("messageType") MessageType messageType);

    /** Everything that failed and is worth a human look, newest first. */
    @Query("""
            SELECT m FROM MessageOutbox m
             WHERE m.status = com.app.entity.MessageOutbox$Status.FAILED
             ORDER BY m.id DESC
            """)
    List<MessageOutbox> findFailures(Limit limit);
}
