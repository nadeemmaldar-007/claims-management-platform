package com.apac.claims.claim.api;

import java.net.URI;
import java.util.List;
import java.util.Locale;

import com.apac.claims.claim.api.dto.ClaimDetailResponse;
import com.apac.claims.claim.api.dto.CreateInformationRequest;
import com.apac.claims.claim.api.dto.InformationRequestResponse;
import com.apac.claims.claim.api.dto.RespondToInformationRequest;
import com.apac.claims.claim.service.InformationRequestService;
import com.apac.claims.identity.service.CurrentUserService;
import com.apac.claims.shared.security.Authz;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Questions from staff to the claimant, and the claimant's answers. */
@RestController
@RequestMapping("/api/v1/claims/{claimNumber}/information-requests")
@Tag(name = "Claims", description = "First notice of loss, workflow and decisions.")
public class InformationRequestController {

    private static final String CLAIM_NUMBER = "Claim number";
    private static final String CLAIM_EXAMPLE = "CLM-10003";
    private static final String REQUEST_ID = "Id of the information request";

    private final InformationRequestService service;
    private final CurrentUserService currentUserService;

    public InformationRequestController(InformationRequestService service, CurrentUserService currentUserService) {
        this.service = service;
        this.currentUserService = currentUserService;
    }

    @Operation(summary = "Ask the claimant for information",
            description = "The assigned officer or a manager puts a question to the claimant. The claim must be "
                    + "UNDER_REVIEW and becomes PENDING_INFORMATION. dueInDays (1 to 30) defaults to the configured "
                    + "number of days.")
    @ApiResponse(responseCode = "201", description = "The new request; the Location header points to the list")
    @ApiResponse(responseCode = "400", description = "question is missing or not 10 to 1000 characters, or dueInDays "
            + "is outside 1 to 30")
    @ApiResponse(responseCode = "401", description = "No valid token")
    @ApiResponse(responseCode = "403", description = "A claimant, or an officer who is not the assigned officer")
    @ApiResponse(responseCode = "404", description = "Unknown claim, or a claim outside the caller's market")
    @ApiResponse(responseCode = "409", description = "CLAIM_INVALID_TRANSITION: only an UNDER_REVIEW claim can be "
            + "put on hold for information")
    @PreAuthorize(Authz.STAFF)
    @PostMapping
    public ResponseEntity<InformationRequestResponse> create(
            @Parameter(description = CLAIM_NUMBER, example = CLAIM_EXAMPLE) @PathVariable String claimNumber,
            @Valid @RequestBody CreateInformationRequest request) {
        InformationRequestResponse created = service.create(claimNumber, request,
                currentUserService.requireCurrentUser());
        return ResponseEntity.created(URI.create("/api/v1/claims/" + claimNumber.trim().toUpperCase(Locale.ROOT)
                + "/information-requests")).body(created);
    }

    @Operation(summary = "List the information requests of a claim",
            description = "Oldest first. A claimant sees the requests of their own claims, with staff shown as "
                    + "\"Claims team\"; staff see those of the claims in their scope. overdue is true for an OPEN "
                    + "request whose due time has passed.")
    @ApiResponse(responseCode = "200", description = "The requests")
    @ApiResponse(responseCode = "401", description = "No valid token")
    @ApiResponse(responseCode = "404", description = "Unknown claim, or a claim the caller may not see")
    @PreAuthorize(Authz.AUTHENTICATED)
    @GetMapping
    public List<InformationRequestResponse> list(
            @Parameter(description = CLAIM_NUMBER, example = CLAIM_EXAMPLE) @PathVariable String claimNumber) {
        return service.list(claimNumber, currentUserService.requireCurrentUser());
    }

    @Operation(summary = "Answer an information request",
            description = "Only the claimant of the claim can answer, and only a request that is still OPEN. Once no "
                    + "request is left OPEN the claim goes back to UNDER_REVIEW. The answer is text only.")
    @ApiResponse(responseCode = "200", description = "The claim")
    @ApiResponse(responseCode = "400", description = "responseText is missing or not 10 to 2000 characters")
    @ApiResponse(responseCode = "401", description = "No valid token")
    @ApiResponse(responseCode = "403", description = "Only a CLAIMANT may do this")
    @ApiResponse(responseCode = "404", description = "Unknown claim, a claim of another claimant, or a request that "
            + "belongs to another claim")
    @ApiResponse(responseCode = "409", description = "INFORMATION_REQUEST_NOT_OPEN: already answered or cancelled")
    @PreAuthorize(Authz.CLAIMANT)
    @PostMapping("/{requestId}/respond")
    public ClaimDetailResponse respond(
            @Parameter(description = CLAIM_NUMBER, example = CLAIM_EXAMPLE) @PathVariable String claimNumber,
            @Parameter(description = REQUEST_ID, example = "5") @PathVariable long requestId,
            @Valid @RequestBody RespondToInformationRequest request) {
        return service.respond(claimNumber, requestId, request, currentUserService.requireCurrentUser());
    }

    @Operation(summary = "Cancel an information request",
            description = "The assigned officer or a manager withdraws a question that is still OPEN. When no request "
                    + "is left OPEN the claim goes back to UNDER_REVIEW.")
    @ApiResponse(responseCode = "200", description = "The claim")
    @ApiResponse(responseCode = "401", description = "No valid token")
    @ApiResponse(responseCode = "403", description = "A claimant, or an officer who is not the assigned officer")
    @ApiResponse(responseCode = "404", description = "Unknown claim, a claim outside the caller's market, or a request "
            + "that belongs to another claim")
    @ApiResponse(responseCode = "409", description = "INFORMATION_REQUEST_NOT_OPEN: already answered or cancelled")
    @PreAuthorize(Authz.STAFF)
    @PostMapping("/{requestId}/cancel")
    public ClaimDetailResponse cancel(
            @Parameter(description = CLAIM_NUMBER, example = CLAIM_EXAMPLE) @PathVariable String claimNumber,
            @Parameter(description = REQUEST_ID, example = "5") @PathVariable long requestId) {
        return service.cancel(claimNumber, requestId, currentUserService.requireCurrentUser());
    }
}
