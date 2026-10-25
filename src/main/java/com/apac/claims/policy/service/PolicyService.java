package com.apac.claims.policy.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.identity.domain.UserRepository;
import com.apac.claims.market.domain.Market;
import com.apac.claims.market.domain.MarketRepository;
import com.apac.claims.policy.api.dto.PolicyResponse;
import com.apac.claims.policy.api.dto.UpsertPolicyRequest;
import com.apac.claims.policy.domain.Policy;
import com.apac.claims.policy.domain.PolicyRepository;
import com.apac.claims.shared.exception.ForbiddenException;
import com.apac.claims.shared.exception.ResourceNotFoundException;
import com.apac.claims.shared.exception.ValidationFailedException;
import com.apac.claims.shared.exception.ValidationFailedException.FieldError;
import com.apac.claims.shared.money.MoneyRules;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Policy reference data: what a claimant sees of their own policies, what staff may read, and how a manager creates
 * or updates a policy (the stand-in for the feed from the policy administration system). It maps entities to
 * response records inside its transactions.
 */
@Service
public class PolicyService {

    private static final Logger log = LoggerFactory.getLogger(PolicyService.class);
    private static final Pattern POLICY_NUMBER = Pattern.compile("^[A-Za-z0-9-]{6,20}$");

    private final PolicyRepository policies;
    private final MarketRepository markets;
    private final UserRepository users;

    public PolicyService(PolicyRepository policies, MarketRepository markets, UserRepository users) {
        this.policies = policies;
        this.markets = markets;
        this.users = users;
    }

    /** The caller's own policies, ordered by policy number. */
    @Transactional(readOnly = true)
    public List<PolicyResponse> listMine(AppUser claimant) {
        return policies.findByHolder(claimant).stream()
                .sorted(Comparator.comparing(Policy::getPolicyNumber))
                .map(PolicyService::toResponse)
                .toList();
    }

    /**
     * A policy for staff: an officer only sees policies of their own market, a manager sees all. A policy outside the
     * officer's market looks exactly like a policy that does not exist (404), so its existence is not revealed.
     */
    @Transactional(readOnly = true)
    public PolicyResponse getForStaff(String policyNumber, AppUser staff) {
        String number = normalise(policyNumber);
        Policy policy = policies.findByPolicyNumber(number)
                .orElseThrow(() -> ResourceNotFoundException.of("Policy", number));
        if (staff.getRole() == Role.MANAGER) {
            return toResponse(policy);
        }
        if (staff.getRole() == Role.OFFICER) {
            boolean sameMarket = staff.getMarket() != null
                    && staff.getMarket().getCode().equals(policy.getMarket().getCode());
            if (!sameMarket) {
                throw ResourceNotFoundException.of("Policy", number);
            }
            return toResponse(policy);
        }
        throw new ForbiddenException("Only staff can read a policy by its number");
    }

    /** Creates the policy, or updates it when the number exists. The type, market and holder cannot be changed. */
    @Transactional
    public UpsertResult upsert(String policyNumber, UpsertPolicyRequest request) {
        String number = normalise(policyNumber);
        if (!POLICY_NUMBER.matcher(number).matches()) {
            throw ValidationFailedException.forField("policyNumber", "must be 6 to 20 letters, digits or dashes");
        }
        Market market = markets.findByCode(request.marketCode().trim().toUpperCase(Locale.ROOT))
                .filter(Market::isActive)
                .orElseThrow(() -> ValidationFailedException.forField("marketCode", "unknown or inactive market"));
        checkAmountsAndDates(request, market);
        AppUser holder = requireHolder(request.holderUserId(), market);

        Policy existing = policies.findByPolicyNumber(number).orElse(null);
        if (existing == null) {
            Policy created = policies.saveAndFlush(new Policy(number, market, request.productType(), holder,
                    request.insuredItem().trim(), MoneyRules.normalize(request.sumInsured(), market.getCurrencyCode()),
                    MoneyRules.normalize(request.deductible(), market.getCurrencyCode()), request.validFrom(),
                    request.validTo(), request.status()));
            log.info("Policy created: policyNumber={} market={}", number, market.getCode());
            return new UpsertResult(toResponse(created), true);
        }
        requireUnchanged(existing, request, market, holder);
        existing.setInsuredItem(request.insuredItem().trim());
        existing.setSumInsured(MoneyRules.normalize(request.sumInsured(), market.getCurrencyCode()));
        existing.setDeductible(MoneyRules.normalize(request.deductible(), market.getCurrencyCode()));
        existing.setValidFrom(request.validFrom());
        existing.setValidTo(request.validTo());
        existing.setStatus(request.status());
        log.info("Policy updated: policyNumber={} market={}", number, market.getCode());
        return new UpsertResult(toResponse(policies.saveAndFlush(existing)), false);
    }

    /** Every problem of the request itself is reported together, not one at a time. */
    private static void checkAmountsAndDates(UpsertPolicyRequest request, Market market) {
        List<FieldError> errors = new ArrayList<>();
        collectScaleError(request.sumInsured(), market, "sumInsured", errors);
        collectScaleError(request.deductible(), market, "deductible", errors);
        if (request.deductible().compareTo(request.sumInsured()) > 0) {
            errors.add(new FieldError("deductible", "must not be above the sum insured"));
        }
        if (!request.validTo().isAfter(request.validFrom())) {
            errors.add(new FieldError("validTo", "must be after validFrom"));
        }
        if (!errors.isEmpty()) {
            throw new ValidationFailedException("Request validation failed", errors);
        }
    }

    private static void collectScaleError(BigDecimal amount, Market market, String field,
                                          List<FieldError> errors) {
        try {
            MoneyRules.requireValidScale(amount, market.getCurrencyCode(), field);
        } catch (ValidationFailedException e) {
            errors.addAll(e.getErrors());
        }
    }

    private AppUser requireHolder(String holderUserId, Market market) {
        AppUser holder = users.findByUserId(holderUserId.trim().toLowerCase(Locale.ROOT))
                .filter(AppUser::isActive)
                .filter(user -> user.getRole() == Role.CLAIMANT)
                .orElseThrow(() -> ValidationFailedException.forField("holderUserId",
                        "must be an existing, active claimant"));
        if (holder.getMarket() == null || !holder.getMarket().getCode().equals(market.getCode())) {
            throw ValidationFailedException.forField("holderUserId",
                    "must belong to the market of the policy (" + market.getCode() + ")");
        }
        return holder;
    }

    private static void requireUnchanged(Policy existing, UpsertPolicyRequest request, Market market, AppUser holder) {
        List<FieldError> errors = new ArrayList<>();
        if (existing.getProductType() != request.productType()) {
            errors.add(new FieldError("productType", "cannot be changed on an existing policy"));
        }
        if (!existing.getMarket().getCode().equals(market.getCode())) {
            errors.add(new FieldError("marketCode", "cannot be changed on an existing policy"));
        }
        if (!existing.getHolder().getUserId().equals(holder.getUserId())) {
            errors.add(new FieldError("holderUserId", "cannot be changed on an existing policy"));
        }
        if (!errors.isEmpty()) {
            throw new ValidationFailedException("Request validation failed", errors);
        }
    }

    private static String normalise(String policyNumber) {
        return policyNumber == null ? "" : policyNumber.trim().toUpperCase(Locale.ROOT);
    }

    private static PolicyResponse toResponse(Policy policy) {
        return new PolicyResponse(policy.getPolicyNumber(), policy.getProductType(), policy.getMarket().getCode(),
                policy.getHolder().getUserId(), policy.getInsuredItem(), policy.getSumInsured(),
                policy.getDeductible(), policy.getMarket().getCurrencyCode(), policy.getValidFrom(),
                policy.getValidTo(), policy.getStatus(), policy.getCreatedAt(), policy.getUpdatedAt());
    }
}
