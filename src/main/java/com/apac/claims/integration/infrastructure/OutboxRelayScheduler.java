package com.apac.claims.integration.infrastructure;

import com.apac.claims.integration.service.OutboxRelay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Calls {@link OutboxRelay#processBatch()} on a fixed delay. It, and the scheduling support it switches on, exist only
 * when {@code app.outbox.relay.enabled} is true (the default); the tests set it to false and call the relay
 * themselves, so no background thread disturbs them. Every exception is caught, because an exception that escapes a
 * scheduled method is logged by Spring but a bug here must never be able to end the schedule.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "app.outbox.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxRelay relay;

    public OutboxRelayScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${app.outbox.relay.poll-interval-ms:1000}")
    public void poll() {
        try {
            relay.processBatch();
        } catch (Exception e) {
            log.error("The outbox relay failed; it will try again at the next poll", e);
        }
    }
}
