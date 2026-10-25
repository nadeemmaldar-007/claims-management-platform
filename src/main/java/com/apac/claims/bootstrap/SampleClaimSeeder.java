package com.apac.claims.bootstrap;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

import com.apac.claims.claim.api.dto.ApproveRequest;
import com.apac.claims.claim.api.dto.EscalateRequest;
import com.apac.claims.claim.api.dto.RecordAssessmentRequest;
import com.apac.claims.claim.api.dto.SubmitClaimRequest;
import com.apac.claims.claim.domain.AssessmentRecommendation;
import com.apac.claims.claim.domain.ClaimRepository;
import com.apac.claims.claim.domain.ClaimType;
import com.apac.claims.claim.domain.DamageType;
import com.apac.claims.claim.domain.PerilType;
import com.apac.claims.claim.domain.PropertyType;
import com.apac.claims.claim.service.ClaimAssessmentService;
import com.apac.claims.claim.service.ClaimAssignmentService;
import com.apac.claims.claim.service.ClaimDecisionService;
import com.apac.claims.claim.service.ClaimSubmissionService;
import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Seeds four sample claims at start-up when {@code app.seed.sample-claims} is true and the database holds no claim at
 * all, so a first look at the application shows claims in four different states. The claims are created through the
 * same services that the controllers call, acting as the seeded claimants and officers, and not by inserting rows: the
 * numbers, snapshots, timeline entries and outbox events are exactly those of real use, and the rules are applied.
 *
 * <p>The four claims: a motor claim in India that waits in the queue (SUBMITTED); a motor claim in Singapore that an
 * officer assessed and escalated (ESCALATED); a flood claim on a property in Australia that an officer picked up
 * (UNDER_REVIEW); a fire claim on a property in India that was assessed and approved (APPROVED). On a fresh database
 * they are {@code CLM-10001}, {@code CLM-10002}, {@code CLP-10001} and {@code CLP-10002}, so the next claims are the ones
 * numbered 10003.
 *
 * <p>It runs after {@link DataInitializer}, which creates the accounts and policies it needs. If anything goes wrong the
 * start-up is aborted with a message that says what failed, rather than leaving a half-seeded database behind; the
 * remedy is to reset the database. A second start finds claims and does nothing.
 */
@Component
@Order(2)
@ConditionalOnProperty(name = "app.seed.sample-claims", havingValue = "true")
public class SampleClaimSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SampleClaimSeeder.class);

    private final ClaimRepository claims;
    private final UserRepository users;
    private final ClaimSubmissionService submission;
    private final ClaimAssignmentService assignment;
    private final ClaimAssessmentService assessment;
    private final ClaimDecisionService decisions;
    private final Clock clock;

    public SampleClaimSeeder(ClaimRepository claims, UserRepository users, ClaimSubmissionService submission,
                             ClaimAssignmentService assignment, ClaimAssessmentService assessment,
                             ClaimDecisionService decisions, Clock clock) {
        this.claims = claims;
        this.users = users;
        this.submission = submission;
        this.assignment = assignment;
        this.assessment = assessment;
        this.decisions = decisions;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (claims.count() > 0) {
            log.info("Sample claims skipped: the database already holds claims");
            return;
        }
        try {
            seed();
        } catch (RuntimeException e) {
            throw new IllegalStateException("The sample claims could not be created (" + e.getMessage()
                    + "). Check that the demo accounts and policies exist (app.seed.demo-data) or reset the database.",
                    e);
        }
    }

    private void seed() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        AppUser claimantIn = user("claimant.in");
        AppUser claimantSg = user("claimant.sg");
        AppUser claimantAu = user("claimant.au");
        AppUser officerIn = user("officer.in");
        AppUser officerSg = user("officer.sg");
        AppUser officerAu = user("officer.au");

        // (1) Waits in the queue of India.
        String motorIn = submission.submit(claimantIn, motor("MOT-IN-0001", "45000.00", today.minusDays(6),
                "MG Road, Bengaluru", "Rear-ended at a traffic signal; the bumper and the boot lid are damaged.",
                new SubmitClaimRequest.MotorDetails("MH12AB1234", "Hyundai", "i20", 2021, DamageType.COLLISION,
                        "FIR-2026-5521")), null).claim().claimNumber();

        // (2) Assessed and escalated: the payout is above the approval limit of an officer in Singapore.
        String motorSg = submission.submit(claimantSg, motor("MOT-SG-0001", "18000.00", today.minusDays(18),
                "Pan-Island Expressway, exit to Bukit Timah", "Hit the car in front when the traffic stopped "
                        + "suddenly; the front bumper, bonnet and radiator need replacing.",
                new SubmitClaimRequest.MotorDetails("SJK1234A", "Toyota", "Corolla Altis", 2020, DamageType.COLLISION,
                        "TP/2026/48213")), null).claim().claimNumber();
        assignment.pickUp(motorSg, officerSg);
        assessment.record(motorSg, new RecordAssessmentRequest(new BigDecimal("17500.00"), true,
                AssessmentRecommendation.ESCALATE, "Repair estimate checked against the photos; above my limit."),
                officerSg);
        decisions.escalate(motorSg, new EscalateRequest("The payout is above my approval limit."), officerSg);

        // (3) Picked up and under review.
        String floodAu = submission.submit(claimantAu, property("PRP-AU-0001", "6500.00", today.minusDays(12),
                "Parramatta, New South Wales", "Storm water entered the ground floor after the street drain "
                        + "overflowed; the carpets and the kitchen units are damaged.",
                new SubmitClaimRequest.PropertyDetails("27 Banksia Street, Parramatta NSW 2150",
                        PropertyType.RESIDENTIAL, PerilType.FLOOD)), null).claim().claimNumber();
        assignment.pickUp(floodAu, officerAu);

        // (4) Assessed and approved within the limit of an officer in India.
        String fireIn = submission.submit(claimantIn, property("PRP-IN-0001", "250000.00", today.minusDays(25),
                "Powai, Mumbai", "An electrical short circuit started a fire in the kitchen; the cabinets, the "
                        + "appliances and the paint of two walls are damaged.",
                new SubmitClaimRequest.PropertyDetails("Flat 402, Lakeview Apartments, Powai, Mumbai 400076",
                        PropertyType.RESIDENTIAL, PerilType.FIRE)), null).claim().claimNumber();
        assignment.pickUp(fireIn, officerIn);
        assessment.record(fireIn, new RecordAssessmentRequest(new BigDecimal("245000.00"), true,
                AssessmentRecommendation.APPROVE, "Fire brigade report received; damage matches the estimate."),
                officerIn);
        decisions.approve(fireIn, new ApproveRequest(null, "Approved as assessed."), officerIn);

        log.info("Sample claims created: {} (submitted), {} (escalated), {} (under review), {} (approved)", motorIn,
                motorSg, floodAu, fireIn);
    }

    private AppUser user(String userId) {
        return users.findByUserId(userId).orElseThrow(() -> new IllegalStateException("account " + userId
                + " does not exist"));
    }

    private static SubmitClaimRequest motor(String policyNumber, String amount, LocalDate incidentDate,
                                            String location, String description,
                                            SubmitClaimRequest.MotorDetails details) {
        return new SubmitClaimRequest(ClaimType.MOTOR, policyNumber, new BigDecimal(amount), incidentDate, location,
                description, details, null);
    }

    private static SubmitClaimRequest property(String policyNumber, String amount, LocalDate incidentDate,
                                               String location, String description,
                                               SubmitClaimRequest.PropertyDetails details) {
        return new SubmitClaimRequest(ClaimType.PROPERTY, policyNumber, new BigDecimal(amount), incidentDate, location,
                description, null, details);
    }
}
