package com.apac.claims.support;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The tests share one database and the API tests leave their outbox events PENDING, because the relay is switched off
 * in the test profile. A test that runs the relay would otherwise deliver the events of every earlier test as well,
 * and a claim submitted by a test is announced to every officer of the market that other tests created, which is
 * hundreds of notifications for nothing. Such a test parks the backlog first: the rows of earlier tests are marked
 * PROCESSED (they only matter to the test that created them, which has finished) and the events of the test itself
 * are then the only ones the relay finds.
 */
public final class OutboxBacklog {

    private OutboxBacklog() {
    }

    /** @return how many pending events were parked */
    public static int park(JdbcTemplate jdbc) {
        return jdbc.update("update outbox_events set status = 'PROCESSED', processed_at = ? where status = 'PENDING'",
                OffsetDateTime.now(ZoneOffset.UTC));
    }
}
