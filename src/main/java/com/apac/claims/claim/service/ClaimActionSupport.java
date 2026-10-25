package com.apac.claims.claim.service;

import com.apac.claims.claim.domain.Claim;
import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.identity.domain.UserRepository;
import com.apac.claims.shared.exception.ForbiddenException;
import com.apac.claims.shared.exception.UnauthenticatedException;

import org.springframework.stereotype.Component;

/**
 * The steps every action on a claim starts with, so the action services state each rule once: reload the caller, load
 * the claim inside the caller's scope (404 when out of scope), and answer the question "may this person act on this
 * claim?" with a 403. Seeing a claim and acting on it are different things: an officer sees every claim of their
 * market but acts only on the claims assigned to them.
 */
@Component
public class ClaimActionSupport {

    private final UserRepository users;
    private final ClaimScope scope;

    public ClaimActionSupport(UserRepository users, ClaimScope scope) {
        this.users = users;
        this.scope = scope;
    }

    /** The caller arrives from another transaction; the services work with a live entity of their own. */
    public AppUser reload(AppUser caller) {
        return users.findById(caller.getId()).orElseThrow(() -> new UnauthenticatedException("Authentication required"));
    }

    /** @throws com.apac.claims.shared.exception.ResourceNotFoundException when the caller may not see the claim */
    public Claim load(String claimNumber, AppUser actor) {
        return scope.requireVisible(claimNumber, actor);
    }

    public void requireStaff(AppUser actor) {
        if (actor.getRole() == Role.CLAIMANT) {
            throw new ForbiddenException("Only claims staff can do this");
        }
    }

    public void requireManager(AppUser actor) {
        if (actor.getRole() != Role.MANAGER) {
            throw new ForbiddenException("Only a manager can do this");
        }
    }

    /**
     * Staff may act when they are a manager, or the officer the claim is assigned to.
     *
     * @param doing what the caller tries to do, finishing the sentence "Only the assigned officer or a manager can ..."
     */
    public void requireAssigneeOrManager(Claim claim, AppUser actor, String doing) {
        requireStaff(actor);
        if (actor.getRole() != Role.MANAGER && !isAssignedTo(claim, actor)) {
            throw new ForbiddenException("Only the assigned officer or a manager can " + doing + ".");
        }
    }

    public static boolean isAssignedTo(Claim claim, AppUser user) {
        return claim.getAssignedOfficer() != null && claim.getAssignedOfficer().getId().equals(user.getId());
    }
}
