package com.apac.claims.claim.service;

import java.util.Optional;

/**
 * Port through which the claim module asks about policies. The claim module owns the interface; the policy module
 * implements it (today with a local snapshot standing in for the policy administration system), so a real policy
 * system can replace the adapter without touching any claim code.
 */
public interface PolicyLookup {

    /** @return the policy with this number, or empty when there is none */
    Optional<PolicyView> findByPolicyNumber(String policyNumber);
}
