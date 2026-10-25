package com.apac.claims.support;

import com.apac.claims.claim.domain.ClaimAssessmentRepository;
import java.util.List;

import com.apac.claims.claim.domain.ClaimCounterRepository;
import com.apac.claims.claim.domain.ClaimRepository;
import com.apac.claims.claim.domain.ClaimTimelineRepository;
import com.apac.claims.claim.domain.ClaimType;
import com.apac.claims.claim.domain.InformationRequestRepository;
import com.apac.claims.integration.domain.OutboxEventRepository;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Base class of the claim API tests. It adds the claim repositories, for the few set-ups and checks that the API cannot
 * do (moving a due date into the past, counting timeline rows, reading the outbox), and a {@link ClaimTestSupport}
 * that takes a claim to any status through the real endpoints. No test is {@code @Transactional}: every request commits
 * for real, which is what the locking and outbox tests need.
 */
public abstract class AbstractClaimApiTest extends AbstractApiTest {

    @Autowired
    protected ClaimRepository claims;
    @Autowired
    protected ClaimTimelineRepository timeline;
    @Autowired
    protected ClaimAssessmentRepository assessments;
    @Autowired
    protected InformationRequestRepository informationRequests;
    @Autowired
    protected ClaimCounterRepository counters;
    @Autowired
    protected OutboxEventRepository outbox;
    @Autowired
    protected JdbcTemplate jdbc;

    protected ClaimTestSupport support;

    @BeforeEach
    void createSupport() {
        support = new ClaimTestSupport(mvc, objectMapper, users, markets, passwordEncoder);
    }

    /** Timeline rows of the claim, counted in the database so that no lazy association is involved. */
    protected int timelineCount(String claimNumber) {
        return jdbc.queryForObject("select count(*) from claim_timeline_entries where claim_id = "
                + "(select id from claims where claim_number = ?)", Integer.class, claimNumber);
    }

    /** Event types written to the outbox for the claim, in the order they happened. */
    protected List<String> eventTypesOf(String claimNumber) {
        return jdbc.queryForList("select event_type from outbox_events where aggregate_id = ? "
                + "order by occurred_at, event_type", String.class, claimNumber);
    }

    protected int claimRowsOfPolicy(String policyNumber) {
        return jdbc.queryForObject("select count(*) from claims where policy_number = ?", Integer.class, policyNumber);
    }

    protected long nextCounterValue(ClaimType type) {
        return counters.findById(type).orElseThrow().getNextValue();
    }
}
