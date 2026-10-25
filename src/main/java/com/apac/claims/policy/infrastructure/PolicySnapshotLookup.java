package com.apac.claims.policy.infrastructure;

import java.util.Optional;

import com.apac.claims.claim.domain.ClaimType;
import com.apac.claims.claim.service.PolicyLookup;
import com.apac.claims.claim.service.PolicyView;
import com.apac.claims.policy.domain.Policy;
import com.apac.claims.policy.domain.PolicyRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adapter that implements the claim module's {@link PolicyLookup} from the local {@code policies} table, which stands
 * in for the policy administration system. It is the only class that knows both the policy entity and the claim view.
 */
@Component
public class PolicySnapshotLookup implements PolicyLookup {

    private final PolicyRepository policies;

    public PolicySnapshotLookup(PolicyRepository policies) {
        this.policies = policies;
    }

    /** Read-only transaction: the market and holder are loaded lazily while the view is built. */
    @Override
    @Transactional(readOnly = true)
    public Optional<PolicyView> findByPolicyNumber(String policyNumber) {
        return policies.findByPolicyNumber(policyNumber).map(PolicySnapshotLookup::toView);
    }

    private static PolicyView toView(Policy policy) {
        return new PolicyView(policy.getPolicyNumber(), policy.getMarket().getCode(),
                ClaimType.valueOf(policy.getProductType().name()), policy.getHolder().getUserId(),
                policy.getSumInsured(), policy.getDeductible(), policy.getValidFrom(), policy.getValidTo(),
                PolicyView.Status.valueOf(policy.getStatus().name()));
    }
}
