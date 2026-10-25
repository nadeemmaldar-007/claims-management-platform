package com.apac.claims.claim.api;

import com.apac.claims.claim.api.dto.ApproveRequest;
import com.apac.claims.claim.api.dto.AssignClaimRequest;
import com.apac.claims.claim.api.dto.ClaimDetailResponse;
import com.apac.claims.claim.api.dto.EscalateRequest;
import com.apac.claims.claim.api.dto.RejectRequest;
import com.apac.claims.claim.api.dto.SettleClaimRequest;
import com.apac.claims.claim.service.ClaimAssignmentService;
import com.apac.claims.claim.service.ClaimDecisionService;
import com.apac.claims.claim.service.ClaimSettlementService;
import com.apac.claims.identity.service.CurrentUserService;
import com.apac.claims.shared.security.Authz;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The actions that move a claim through its workflow: pick-up, assignment, escalation, the two decisions and
 * settlement. Each returns the updated claim.
 */
@RestController
@RequestMapping("/api/v1/claims/{claimNumber}")
@Tag(name = "Claims", description = "First notice of loss, workflow and decisions.")
public class ClaimActionController {

    private static final String CLAIM_NUMBER = "Claim number";
    private static final String CLAIM_EXAMPLE = "CLM-10003";

    private final ClaimAssignmentService assignmentService;
    private final ClaimDecisionService decisionService;
    private final ClaimSettlementService settlementService;
    private final CurrentUserService currentUserService;

    public ClaimActionController(ClaimAssignmentService assignmentService, ClaimDecisionService decisionService,
                                 ClaimSettlementService settlementService, CurrentUserService currentUserService) {
        this.assignmentService = assignmentService;
        this.decisionService = decisionService;
        this.settlementService = settlementService;
        this.currentUserService = currentUserService;
    }

    @Operation(summary = "Pick up a claim",
            description = "The caller takes a SUBMITTED claim from the queue and starts the review. If another officer "
                    + "took it first the answer is 409 CLAIM_ALREADY_ASSIGNED.")
    @ApiResponse(responseCode = "200", description = "The claim, now UNDER_REVIEW and assigned to the caller")
    @ApiResponse(responseCode = "401", description = "No valid token")
    @ApiResponse(responseCode = "403", description = "Only an OFFICER or a MANAGER may do this")
    @ApiResponse(responseCode = "404", description = "Unknown claim, or a claim outside the caller's market")
    @ApiResponse(responseCode = "409", description = "CLAIM_ALREADY_ASSIGNED: someone else took the claim; "
            + "CLAIM_INVALID_TRANSITION: the claim is in a status that cannot be picked up")
    @PreAuthorize(Authz.STAFF)
    @PostMapping("/pick-up")
    public ClaimDetailResponse pickUp(@Parameter(description = CLAIM_NUMBER, example = CLAIM_EXAMPLE)
                                      @PathVariable String claimNumber) {
        return assignmentService.pickUp(claimNumber, currentUserService.requireCurrentUser());
    }

    @Operation(summary = "Assign or reassign a claim",
            description = "A manager gives the claim to an active officer of the claim's market. From SUBMITTED this "
                    + "starts the review; from UNDER_REVIEW, PENDING_INFORMATION or ESCALATED it is a reassignment that "
                    + "keeps the status and is recorded as an internal timeline entry.")
    @ApiResponse(responseCode = "200", description = "The claim with its new officer")
    @ApiResponse(responseCode = "400", description = "officerUserId is missing")
    @ApiResponse(responseCode = "401", description = "No valid token")
    @ApiResponse(responseCode = "403", description = "Only a MANAGER may do this")
    @ApiResponse(responseCode = "404", description = "Unknown claim")
    @ApiResponse(responseCode = "409", description = "CLAIM_INVALID_TRANSITION: the claim can no longer be assigned")
    @ApiResponse(responseCode = "422", description = "officerUserId is not an active officer of the claim's market, or "
            + "is already the assigned officer")
    @PreAuthorize(Authz.MANAGER)
    @PostMapping("/assign")
    public ClaimDetailResponse assign(@Parameter(description = CLAIM_NUMBER, example = CLAIM_EXAMPLE)
                                      @PathVariable String claimNumber,
                                      @Valid @RequestBody AssignClaimRequest request) {
        return assignmentService.assign(claimNumber, request.officerUserId(), currentUserService.requireCurrentUser());
    }

    @Operation(summary = "Escalate a claim to a manager",
            description = "The assigned officer passes a claim that is UNDER_REVIEW to the managers, for example because "
                    + "the payout is above their approval limit. The remarks are shown to staff; the claimant is told "
                    + "only that the claim went to a manager.")
    @ApiResponse(responseCode = "200", description = "The claim, now ESCALATED")
    @ApiResponse(responseCode = "400", description = "remarks is missing or not 10 to 1000 characters")
    @ApiResponse(responseCode = "401", description = "No valid token")
    @ApiResponse(responseCode = "403", description = "Only the assigned OFFICER may do this")
    @ApiResponse(responseCode = "404", description = "Unknown claim, or a claim outside the caller's market")
    @ApiResponse(responseCode = "409", description = "CLAIM_INVALID_TRANSITION: only an UNDER_REVIEW claim is escalated")
    @PreAuthorize(Authz.OFFICER)
    @PostMapping("/escalate")
    public ClaimDetailResponse escalate(@Parameter(description = CLAIM_NUMBER, example = CLAIM_EXAMPLE)
                                        @PathVariable String claimNumber,
                                        @Valid @RequestBody EscalateRequest request) {
        return decisionService.escalate(claimNumber, request, currentUserService.requireCurrentUser());
    }

    @Operation(summary = "Approve a claim",
            description = "Needs a latest assessment that confirms coverage with a payout above zero. The approved "
                    + "amount defaults to that payout and may be lower, never higher. An officer may approve up to the "
                    + "market's escalation threshold only; a manager has no limit. An escalated claim can be approved "
                    + "by a manager only. The body may be left out.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = false,
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(
                            name = "Approve for less", value = """
                            {
                              "approvedAmount": 39000.00,
                              "remarks": "Approved as assessed."
                            }
                            """))))
    @ApiResponse(responseCode = "200", description = "The claim, now APPROVED")
    @ApiResponse(responseCode = "400", description = "Invalid amount or too many decimal places for the currency")
    @ApiResponse(responseCode = "401", description = "No valid token")
    @ApiResponse(responseCode = "403", description = "Not the assigned officer, or an officer on an escalated claim")
    @ApiResponse(responseCode = "404", description = "Unknown claim, or a claim outside the caller's market")
    @ApiResponse(responseCode = "409", description = "ASSESSMENT_REQUIRED, CLAIM_INVALID_TRANSITION or "
            + "CONCURRENT_MODIFICATION")
    @ApiResponse(responseCode = "422", description = "APPROVAL_LIMIT_EXCEEDED, or approvedAmount above the "
            + "recommended payout")
    @PreAuthorize(Authz.STAFF)
    @PostMapping("/approve")
    public ClaimDetailResponse approve(@Parameter(description = CLAIM_NUMBER, example = CLAIM_EXAMPLE)
                                       @PathVariable String claimNumber,
                                       @Valid @RequestBody(required = false) ApproveRequest request) {
        return decisionService.approve(claimNumber, request, currentUserService.requireCurrentUser());
    }

    @Operation(summary = "Reject a claim",
            description = "The reason code and the remarks are the explanation the claimant is shown. A request for "
                    + "information that is still open is cancelled with the claim. An escalated claim can be rejected "
                    + "by a manager only.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "Not covered", value = """
                    {
                      "reasonCode": "NOT_COVERED",
                      "remarks": "Flood damage is not covered by this motor policy."
                    }
                    """))))
    @ApiResponse(responseCode = "200", description = "The claim, now REJECTED")
    @ApiResponse(responseCode = "400", description = "reasonCode or remarks is missing or invalid")
    @ApiResponse(responseCode = "401", description = "No valid token")
    @ApiResponse(responseCode = "403", description = "Not the assigned officer, or an officer on an escalated claim")
    @ApiResponse(responseCode = "404", description = "Unknown claim, or a claim outside the caller's market")
    @ApiResponse(responseCode = "409", description = "CLAIM_INVALID_TRANSITION: the claim is in a status that cannot "
            + "be rejected")
    @PreAuthorize(Authz.STAFF)
    @PostMapping("/reject")
    public ClaimDetailResponse reject(@Parameter(description = CLAIM_NUMBER, example = CLAIM_EXAMPLE)
                                      @PathVariable String claimNumber, @Valid @RequestBody RejectRequest request) {
        return decisionService.reject(claimNumber, request, currentUserService.requireCurrentUser());
    }

    @Operation(summary = "Settle an approved claim",
            description = "The assigned officer or a manager pays an APPROVED claim through the payment gateway. The "
                    + "amount paid is the approved amount. The gateway is called before anything is written: if it is "
                    + "not available the answer is 502 and the claim stays APPROVED, with no timeline entry and no "
                    + "event. Asking again after a failure succeeds, and a claim is never paid twice.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "Bank transfer", value = """
                    {
                      "paymentMethod": "BANK_TRANSFER",
                      "remarks": "Paid to the account on file."
                    }
                    """))))
    @ApiResponse(responseCode = "200", description = "The claim, now SETTLED, with its settlement block")
    @ApiResponse(responseCode = "400", description = "paymentMethod is missing or not BANK_TRANSFER or CHEQUE")
    @ApiResponse(responseCode = "401", description = "No valid token")
    @ApiResponse(responseCode = "403", description = "A claimant, or an officer who is not the assigned officer")
    @ApiResponse(responseCode = "404", description = "Unknown claim, or a claim outside the caller's market")
    @ApiResponse(responseCode = "409", description = "CLAIM_INVALID_TRANSITION: only an APPROVED claim can be settled, "
            + "and a claim is settled once")
    @ApiResponse(responseCode = "502", description = "PAYMENT_GATEWAY_UNAVAILABLE: the claim stays APPROVED")
    @PreAuthorize(Authz.STAFF)
    @PostMapping("/settle")
    public ClaimDetailResponse settle(@Parameter(description = CLAIM_NUMBER, example = CLAIM_EXAMPLE)
                                      @PathVariable String claimNumber,
                                      @Valid @RequestBody SettleClaimRequest request) {
        return settlementService.settle(claimNumber, request, currentUserService.requireCurrentUser());
    }
}
