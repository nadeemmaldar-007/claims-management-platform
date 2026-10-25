package com.apac.claims.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import com.apac.claims.claim.domain.Claim;
import com.apac.claims.claim.domain.ClaimCounterRepository;
import com.apac.claims.claim.domain.ClaimRepository;
import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.claim.domain.ClaimType;
import com.apac.claims.claim.domain.DecisionType;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The sample claims on an empty database of their own (the application starts, the demo accounts and policies are
 * seeded, and then the four claims are created through the services). The other tests keep the seeder off, so this
 * class is the only place where it runs.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.seed.sample-claims=true",
        "spring.datasource.url=jdbc:h2:mem:sample_seed;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
class SampleClaimSeederTest {

    @Autowired
    private ClaimRepository claims;
    @Autowired
    private ClaimCounterRepository counters;
    @Autowired
    private SampleClaimSeeder seeder;
    @Autowired
    private JdbcTemplate jdbc;

    private Claim claim(String claimNumber) {
        return claims.findByClaimNumber(claimNumber).orElseThrow();
    }

    @Test
    void fourClaimsExistInFourDifferentStates() {
        assertThat(claims.count()).isEqualTo(4);
        assertThat(claims.findAll()).extracting(Claim::getStatus).containsExactlyInAnyOrder(ClaimStatus.SUBMITTED,
                ClaimStatus.ESCALATED, ClaimStatus.UNDER_REVIEW, ClaimStatus.APPROVED);
    }

    @Test
    void theClaimNumbersFollowTheRulesAndTheNextOnesAreTen003() {
        assertThat(claims.findAll()).extracting(Claim::getClaimNumber)
                .allSatisfy(number -> assertThat(number).matches("^(CLM|CLP)-\\d{5,}$"))
                .containsExactlyInAnyOrder("CLM-10001", "CLM-10002", "CLP-10001", "CLP-10002");
        assertThat(counters.findById(ClaimType.MOTOR).orElseThrow().getNextValue()).isEqualTo(10003);
        assertThat(counters.findById(ClaimType.PROPERTY).orElseThrow().getNextValue()).isEqualTo(10003);
    }

    @Test
    void theMotorClaimOfIndiaWaitsInTheQueue() {
        Claim claim = claim("CLM-10001");

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.SUBMITTED);
        assertThat(claim.getClaimType()).isEqualTo(ClaimType.MOTOR);
        assertThat(claim.getClaimant().getUserId()).isEqualTo("claimant.in");
        assertThat(claim.getPolicyNumber()).isEqualTo("MOT-IN-0001");
        assertThat(claim.getCurrencyCode()).isEqualTo("INR");
        assertThat(claim.getClaimedAmount()).isEqualByComparingTo("45000.00");
        assertThat(claim.getAssignedOfficer()).isNull();
        assertThat(claim.getVehicleMake()).isEqualTo("Hyundai");
        assertThat(claim.getVehicleRegistrationNumber()).isEqualTo("MH12AB1234");
        assertThat(claim.getPoliceReportNumber()).isNotBlank();
    }

    @Test
    void theMotorClaimOfSingaporeWasAssessedAndEscalated() {
        Claim claim = claim("CLM-10002");

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.ESCALATED);
        assertThat(claim.getClaimant().getUserId()).isEqualTo("claimant.sg");
        assertThat(claim.getAssignedOfficer().getUserId()).isEqualTo("officer.sg");
        assertThat(claim.getCurrencyCode()).isEqualTo("SGD");
        assertThat(claim.getClaimedAmount()).isEqualByComparingTo("18000.00");
        List<Map<String, Object>> assessments = jdbc.queryForList("select assessed_loss_amount, coverage_confirmed, "
                + "recommendation, recommended_payout from claim_assessments where claim_id = ?", claim.getId());
        assertThat(assessments).hasSize(1);
        assertThat((BigDecimal) assessments.get(0).get("assessed_loss_amount")).isEqualByComparingTo("17500.00");
        assertThat(assessments.get(0).get("coverage_confirmed")).isEqualTo(true);
        assertThat(assessments.get(0).get("recommendation")).isEqualTo("ESCALATE");
        assertThat(timelineOf(claim)).containsExactly("SUBMITTED", "ASSIGNED", "ASSESSMENT_RECORDED", "ESCALATED");
    }

    @Test
    void theFloodClaimOfAustraliaWasPickedUpAndIsUnderReview() {
        Claim claim = claim("CLP-10001");

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.UNDER_REVIEW);
        assertThat(claim.getClaimType()).isEqualTo(ClaimType.PROPERTY);
        assertThat(claim.getClaimant().getUserId()).isEqualTo("claimant.au");
        assertThat(claim.getAssignedOfficer().getUserId()).isEqualTo("officer.au");
        assertThat(claim.getCurrencyCode()).isEqualTo("AUD");
        assertThat(claim.getClaimedAmount()).isEqualByComparingTo("6500.00");
        assertThat(claim.getPerilType().name()).isEqualTo("FLOOD");
        assertThat(claim.getPropertyAddress()).contains("Parramatta");
        assertThat(timelineOf(claim)).containsExactly("SUBMITTED", "ASSIGNED");
    }

    @Test
    void theFireClaimOfIndiaWasAssessedAndApproved() {
        Claim claim = claim("CLP-10002");

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.APPROVED);
        assertThat(claim.getClaimant().getUserId()).isEqualTo("claimant.in");
        assertThat(claim.getAssignedOfficer().getUserId()).isEqualTo("officer.in");
        assertThat(claim.getPolicyNumber()).isEqualTo("PRP-IN-0001");
        assertThat(claim.getClaimedAmount()).isEqualByComparingTo("250000.00");
        assertThat(claim.getPerilType().name()).isEqualTo("FIRE");
        assertThat(claim.getDecisionType()).isEqualTo(DecisionType.APPROVED);
        assertThat(claim.getDecidedBy().getUserId()).isEqualTo("officer.in");
        // Loss 245000.00 less the deductible of 5000.00 (1% of the threshold of 500000.00).
        assertThat(claim.getApprovedAmount()).isEqualByComparingTo("240000.00");
        assertThat(timelineOf(claim)).containsExactly("SUBMITTED", "ASSIGNED", "ASSESSMENT_RECORDED", "APPROVED");
    }

    @Test
    void theIncidentsLieInTheLastSixtyDays() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        assertThat(claims.findAll()).extracting(Claim::getIncidentDate).allSatisfy(date -> {
            assertThat(date).isBeforeOrEqualTo(today);
            assertThat(date).isAfterOrEqualTo(today.minusDays(60));
        });
    }

    @Test
    void theClaimsCameThroughTheServicesSoTheyHaveTheirEventsAndDescriptions() {
        assertThat(claims.findAll()).allSatisfy(claim -> {
            assertThat(claim.getDescription().length()).isGreaterThanOrEqualTo(30);
            assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ? and "
                    + "event_type = 'ClaimSubmitted'", Integer.class, claim.getClaimNumber())).isEqualTo(1);
        });
        assertThat(jdbc.queryForList("select event_type from outbox_events where aggregate_id = 'CLM-10002' "
                + "order by occurred_at", String.class)).containsExactly("ClaimSubmitted", "ClaimAssigned",
                "ClaimEscalated");
        assertThat(jdbc.queryForList("select event_type from outbox_events where aggregate_id = 'CLP-10002' "
                + "order by occurred_at", String.class)).containsExactly("ClaimSubmitted", "ClaimAssigned",
                "ClaimApproved");
    }

    @Test
    void aSecondRunCreatesNothingAndUsesNoClaimNumber() {
        long claimsBefore = claims.count();
        long events = jdbc.queryForObject("select count(*) from outbox_events", Long.class);

        seeder.run(null);
        seeder.run(null);

        assertThat(claims.count()).isEqualTo(claimsBefore).isEqualTo(4);
        assertThat(counters.findById(ClaimType.MOTOR).orElseThrow().getNextValue()).isEqualTo(10003);
        assertThat(jdbc.queryForObject("select count(*) from outbox_events", Long.class)).isEqualTo(events);
    }

    private List<String> timelineOf(Claim claim) {
        return jdbc.queryForList("select entry_type from claim_timeline_entries where claim_id = ? "
                + "order by occurred_at, id", String.class, claim.getId());
    }
}
