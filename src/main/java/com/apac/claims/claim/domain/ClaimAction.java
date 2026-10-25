package com.apac.claims.claim.domain;

/**
 * Everything that can happen to a claim and may change its status. Each action carries the phrase used in error
 * messages ("cannot be picked up"), so that a refused transition reads as plain language.
 */
public enum ClaimAction {
    SUBMIT("submitted"),
    PICK_UP("picked up"),
    ASSIGN("assigned"),
    REQUEST_INFORMATION("asked for more information"),
    RECEIVE_INFORMATION("updated with the claimant's information"),
    ESCALATE("escalated"),
    APPROVE("approved"),
    REJECT("rejected"),
    SETTLE("settled");

    private final String passivePhrase;

    ClaimAction(String passivePhrase) {
        this.passivePhrase = passivePhrase;
    }

    /** The action as it follows "cannot be", for example "picked up". */
    public String passivePhrase() {
        return passivePhrase;
    }
}
