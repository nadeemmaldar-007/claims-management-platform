package com.apac.claims.claim.service;

import java.util.Locale;

import com.apac.claims.claim.domain.Claim;
import com.apac.claims.claim.domain.ClaimRepository;
import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.shared.exception.ResourceNotFoundException;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * The single place that decides which claims a user may see: a claimant their own, an officer those of their own
 * market, a manager all of them. Every read and every action loads its claim through {@link #requireVisible}, so a
 * claim the caller may not see looks exactly like a claim that does not exist (404), and its existence is never
 * revealed. Being allowed to see a claim is not the same as being allowed to act on it; the action services check
 * that separately and answer 403.
 */
@Component
public class ClaimScope {

    private final ClaimRepository claims;

    public ClaimScope(ClaimRepository claims) {
        this.claims = claims;
    }

    /**
     * @return the claim, with its claimant, market and officer loaded
     * @throws ResourceNotFoundException when no such claim exists or the user is not allowed to see it
     */
    public Claim requireVisible(String claimNumber, AppUser user) {
        String number = claimNumber == null ? "" : claimNumber.trim().toUpperCase(Locale.ROOT);
        return claims.findByClaimNumber(number)
                .filter(claim -> isVisible(claim, user))
                .orElseThrow(() -> ResourceNotFoundException.of("Claim", number));
    }

    public boolean isVisible(Claim claim, AppUser user) {
        return switch (user.getRole()) {
            case MANAGER -> true;
            case OFFICER -> user.getMarket() != null && claim.getMarket().getId().equals(user.getMarket().getId());
            case CLAIMANT -> claim.getClaimant().getId().equals(user.getId());
        };
    }

    /** The base filter of every list: only what the user may see. Callers add their own filters with {@code and}. */
    public Specification<Claim> visibleTo(AppUser user) {
        return switch (user.getRole()) {
            case MANAGER -> (root, query, cb) -> cb.conjunction();
            case OFFICER -> user.getMarket() == null
                    ? (root, query, cb) -> cb.disjunction()
                    : (root, query, cb) -> cb.equal(root.get("market").get("id"), user.getMarket().getId());
            case CLAIMANT -> (root, query, cb) -> cb.equal(root.get("claimant").get("id"), user.getId());
        };
    }
}
