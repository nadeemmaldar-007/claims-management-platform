package com.apac.claims.integration.domain;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence port for the outbox. */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {

    /**
     * Events of the given status (PENDING for the relay) whose next attempt time has passed, oldest first.
     * The {@link Pageable} only limits the batch size; ordering is fixed by the query.
     */
    @Query("select e from OutboxEvent e where e.status = :status and e.nextAttemptAt <= :now "
            + "order by e.occurredAt asc")
    List<OutboxEvent> findDueEvents(@Param("status") OutboxStatus status, @Param("now") Instant now,
                                    Pageable pageable);
}
