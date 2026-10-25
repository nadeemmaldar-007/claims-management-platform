package com.apac.claims.claim.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.apac.claims.claim.api.dto.SubmitClaimRequest;
import com.apac.claims.claim.domain.Claim;
import com.apac.claims.claim.domain.ClaimRepository;
import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.claim.domain.ClaimType;
import com.apac.claims.claim.domain.ClaimWorkflow;
import com.apac.claims.claim.domain.IdempotencyKey;
import com.apac.claims.claim.domain.IdempotencyKeyRepository;
import com.apac.claims.claim.domain.TimelineEntryType;
import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.UserRepository;
import com.apac.claims.market.domain.Market;
import com.apac.claims.shared.event.DomainEventPublisher;
import com.apac.claims.shared.exception.BusinessRuleException;
import com.apac.claims.shared.exception.ConflictException;
import com.apac.claims.shared.exception.ErrorCode;
import com.apac.claims.shared.exception.UnauthenticatedException;
import com.apac.claims.shared.exception.ValidationFailedException;
import com.apac.claims.shared.exception.ValidationFailedException.FieldError;
import com.apac.claims.shared.money.MoneyRules;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * First notice of loss. One transaction does everything in a fixed order, so that a failed submission leaves nothing
 * behind and never uses up a claim number: replay check, request validation, policy checks, duplicate check, and only
 * then the claim number, the claim, its timeline entry, the event and the idempotency key.
 *
 * <p>Two identical requests with the same idempotency key that arrive at the same moment both pass the replay check;
 * the second then fails on the unique key when it is stored, its whole transaction rolls back (including the claim
 * number) and the caller gets a 409 and can retry to receive the replay. That is safe, because only one claim exists.
 */
@Service
public class ClaimSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(ClaimSubmissionService.class);
    private static final int MIN_KEY_LENGTH = 8;
    private static final int MAX_KEY_LENGTH = 64;
    private static final int FIRST_VEHICLE_YEAR = 1980;
    private static final int MAX_AGE_YEARS = 5;

    private final ClaimRepository claims;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final UserRepository users;
    private final PolicyLookup policyLookup;
    private final ClaimNumberGenerator numberGenerator;
    private final TimelineService timeline;
    private final DomainEventPublisher eventPublisher;
    private final ClaimEvents events;
    private final ClaimMapper mapper;
    private final Clock clock;

    public ClaimSubmissionService(ClaimRepository claims, IdempotencyKeyRepository idempotencyKeys,
                                  UserRepository users, PolicyLookup policyLookup,
                                  ClaimNumberGenerator numberGenerator, TimelineService timeline,
                                  DomainEventPublisher eventPublisher, ClaimEvents events, ClaimMapper mapper,
                                  Clock clock) {
        this.claims = claims;
        this.idempotencyKeys = idempotencyKeys;
        this.users = users;
        this.policyLookup = policyLookup;
        this.numberGenerator = numberGenerator;
        this.timeline = timeline;
        this.eventPublisher = eventPublisher;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public SubmissionResult submit(AppUser caller, SubmitClaimRequest request, String idempotencyKey) {
        // The caller came from another transaction; reload it so that rows refer to a live entity.
        AppUser claimant = users.findById(caller.getId())
                .orElseThrow(() -> new UnauthenticatedException("Authentication required"));

        // (a) a key that was used before returns the original claim
        String key = checkKey(idempotencyKey);
        if (key != null) {
            Optional<IdempotencyKey> used = idempotencyKeys.findByUserAndIdempotencyKey(claimant, key);
            if (used.isPresent()) {
                Claim original = claims.findByClaimNumber(used.get().getClaimNumber()).orElseThrow(() ->
                        new IllegalStateException("Idempotency key refers to a claim that does not exist"));
                return new SubmissionResult(mapper.toDetail(original, claimant), true);
            }
        }

        // (b) and (c) the request and the claimant's market
        Market market = requireMarket(claimant);
        validateRequest(request, market);
        String policyNumber = request.policyNumber().trim().toUpperCase(Locale.ROOT);

        // (d) policy checks, in the documented order
        PolicyView policy = checkPolicy(claimant, market, request, policyNumber);

        // (e) duplicate claim
        if (claims.existsByPolicyNumberAndIncidentDateAndClaimTypeAndStatusNot(policyNumber,
                request.incidentDate(), request.claimType(), ClaimStatus.REJECTED)) {
            throw new ConflictException(ErrorCode.DUPLICATE_CLAIM, "A claim for policy " + policyNumber
                    + " with this incident date and type already exists");
        }

        // (f) to (j): only now is a claim number taken, so every failure above leaves the counter untouched
        String claimNumber = numberGenerator.next(request.claimType());
        Claim claim = buildClaim(claimNumber, claimant, market, request, policyNumber, policy);
        claims.saveAndFlush(claim);
        timeline.record(claim, TimelineEntryType.SUBMITTED, null, ClaimWorkflow.initialStatus(),
                claimant.getUserId(), "Claim submitted.");
        eventPublisher.publish(events.submitted(claim));
        if (key != null) {
            idempotencyKeys.saveAndFlush(new IdempotencyKey(claimant, key, claimNumber));
        }
        log.info("Claim submitted claimNumber={} type={} market={}", claimNumber, claim.getClaimType(),
                market.getCode());
        return new SubmissionResult(mapper.toDetail(claim, claimant), false);
    }

    private static String checkKey(String key) {
        if (key == null) {
            return null;
        }
        if (key.length() < MIN_KEY_LENGTH || key.length() > MAX_KEY_LENGTH) {
            throw ValidationFailedException.forField("Idempotency-Key", "must be 8 to 64 characters");
        }
        return key;
    }

    private static Market requireMarket(AppUser claimant) {
        if (claimant.getMarket() == null) {
            throw new BusinessRuleException(ErrorCode.POLICY_MARKET_MISMATCH,
                    "Your account has no market, so a claim cannot be submitted");
        }
        return claimant.getMarket();
    }

    /** Everything that depends on the clock, the block shape or the currency; all problems are reported together. */
    private void validateRequest(SubmitClaimRequest request, Market market) {
        List<FieldError> errors = new ArrayList<>();
        checkBlocks(request, errors);
        LocalDate today = LocalDate.now(clock);
        if (request.incidentDate().isBefore(today.minusYears(MAX_AGE_YEARS))) {
            errors.add(new FieldError("incidentDate", "must not be older than " + MAX_AGE_YEARS + " years"));
        }
        // "Tomorrow in UTC" and not "today": a claimant in a market ahead of UTC reports an incident from today.
        if (request.incidentDate().isAfter(today.plusDays(1))) {
            errors.add(new FieldError("incidentDate", "must not be later than tomorrow (UTC)"));
        }
        try {
            MoneyRules.requireValidScale(request.claimedAmount(), market.getCurrencyCode(), "claimedAmount");
        } catch (ValidationFailedException e) {
            errors.addAll(e.getErrors());
        }
        SubmitClaimRequest.MotorDetails motor = request.motor();
        if (request.claimType() == ClaimType.MOTOR && motor != null) {
            int latestYear = today.getYear() + 1;
            if (motor.vehicleYear() < FIRST_VEHICLE_YEAR || motor.vehicleYear() > latestYear) {
                errors.add(new FieldError("motor.vehicleYear",
                        "must be between " + FIRST_VEHICLE_YEAR + " and " + latestYear));
            }
        }
        if (!errors.isEmpty()) {
            errors.sort(Comparator.comparing(FieldError::field).thenComparing(FieldError::message));
            throw new ValidationFailedException("Request validation failed", errors);
        }
    }

    /** A MOTOR claim needs the motor block and no property block, and the other way round. */
    private static void checkBlocks(SubmitClaimRequest request, List<FieldError> errors) {
        boolean motorClaim = request.claimType() == ClaimType.MOTOR;
        String wanted = motorClaim ? "motor" : "property";
        String unwanted = motorClaim ? "property" : "motor";
        if ((motorClaim ? request.motor() : request.property()) == null) {
            errors.add(new FieldError(wanted, "is required for a " + request.claimType() + " claim"));
        }
        if ((motorClaim ? request.property() : request.motor()) != null) {
            errors.add(new FieldError(unwanted, "must not be given for a " + request.claimType() + " claim"));
        }
    }

    private PolicyView checkPolicy(AppUser claimant, Market market, SubmitClaimRequest request, String policyNumber) {
        // A policy that belongs to someone else is reported exactly like one that does not exist.
        PolicyView policy = policyLookup.findByPolicyNumber(policyNumber)
                .filter(p -> p.holderUserId().equals(claimant.getUserId()))
                .orElseThrow(() -> new BusinessRuleException(ErrorCode.POLICY_NOT_FOUND,
                        "Policy " + policyNumber + " was not found among your policies"));
        if (!policy.coversIncidentOn(request.incidentDate())) {
            throw new BusinessRuleException(ErrorCode.POLICY_NOT_ACTIVE,
                    "Policy " + policyNumber + " is not active on the incident date");
        }
        if (policy.productType() != request.claimType()) {
            throw new BusinessRuleException(ErrorCode.POLICY_TYPE_MISMATCH,
                    "Policy " + policyNumber + " covers " + policy.productType() + " claims, not "
                            + request.claimType());
        }
        if (!policy.marketCode().equals(market.getCode())) {
            throw new BusinessRuleException(ErrorCode.POLICY_MARKET_MISMATCH,
                    "Policy " + policyNumber + " belongs to another market than yours");
        }
        if (request.claimedAmount().compareTo(policy.sumInsured()) > 0) {
            throw new BusinessRuleException(ErrorCode.AMOUNT_EXCEEDS_SUM_INSURED,
                    "The claimed amount exceeds the sum insured of the policy");
        }
        return policy;
    }

    private Claim buildClaim(String claimNumber, AppUser claimant, Market market, SubmitClaimRequest request,
                             String policyNumber, PolicyView policy) {
        String currency = market.getCurrencyCode();
        Claim claim = new Claim(claimNumber, request.claimType(), ClaimWorkflow.initialStatus(), policyNumber,
                scaled(policy.sumInsured(), currency), scaled(policy.deductible(), currency), claimant, market,
                currency, scaled(request.claimedAmount(), currency), request.incidentDate(),
                request.incidentLocation().trim(), request.description().trim());
        SubmitClaimRequest.MotorDetails motor = request.motor();
        if (request.claimType() == ClaimType.MOTOR) {
            claim.setVehicleRegistrationNumber(motor.vehicleRegistrationNumber().trim());
            claim.setVehicleMake(motor.vehicleMake().trim());
            claim.setVehicleModel(motor.vehicleModel().trim());
            claim.setVehicleYear(motor.vehicleYear());
            claim.setDamageType(motor.damageType());
            claim.setPoliceReportNumber(motor.policeReportNumber() == null || motor.policeReportNumber().isBlank()
                    ? null : motor.policeReportNumber().trim());
        } else {
            SubmitClaimRequest.PropertyDetails property = request.property();
            claim.setPropertyAddress(property.propertyAddress().trim());
            claim.setPropertyType(property.propertyType());
            claim.setPerilType(property.perilType());
        }
        return claim;
    }

    private static BigDecimal scaled(BigDecimal amount, String currencyCode) {
        return MoneyRules.normalize(amount, currencyCode);
    }
}
