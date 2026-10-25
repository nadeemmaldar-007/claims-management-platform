package com.apac.claims.claim.api;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

import com.apac.claims.claim.api.dto.ClaimDetailResponse;
import com.apac.claims.claim.api.dto.ClaimSummaryResponse;
import com.apac.claims.claim.api.dto.QueueItemResponse;
import com.apac.claims.claim.api.dto.SubmitClaimRequest;
import com.apac.claims.claim.api.dto.TimelineEntryResponse;
import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.claim.domain.ClaimType;
import com.apac.claims.claim.service.ClaimFilters;
import com.apac.claims.claim.service.ClaimQueryService;
import com.apac.claims.claim.service.ClaimSubmissionService;
import com.apac.claims.claim.service.SubmissionResult;
import com.apac.claims.identity.service.CurrentUserService;
import com.apac.claims.shared.api.PageResponse;
import com.apac.claims.shared.security.Authz;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Submitting and reading claims. The literal path {@code /queue} is declared next to {@code /{claimNumber}}; Spring
 * prefers the literal, so the queue is never mistaken for a claim number.
 */
@RestController
@RequestMapping("/api/v1/claims")
@Tag(name = "Claims", description = "First notice of loss, workflow and decisions.")
public class ClaimController {

    private static final String MOTOR_EXAMPLE = """
            {
              "claimType": "MOTOR",
              "policyNumber": "MOT-IN-0001",
              "claimedAmount": 45000.00,
              "incidentDate": "2026-10-15",
              "incidentLocation": "MG Road, Bengaluru",
              "description": "Rear-ended at a traffic signal; bumper and boot lid damaged.",
              "motor": {
                "vehicleRegistrationNumber": "MH12AB1234",
                "vehicleMake": "Hyundai",
                "vehicleModel": "i20",
                "vehicleYear": 2021,
                "damageType": "COLLISION",
                "policeReportNumber": "FIR-2026-5521"
              }
            }
            """;

    private static final String PROPERTY_EXAMPLE = """
            {
              "claimType": "PROPERTY",
              "policyNumber": "PRP-IN-0001",
              "claimedAmount": 250000.00,
              "incidentDate": "2026-10-15",
              "incidentLocation": "Powai, Mumbai",
              "description": "Heavy rain flooded the ground floor; flooring and furniture damaged.",
              "property": {
                "propertyAddress": "Flat 402, Lakeview Apartments, Powai, Mumbai 400076",
                "propertyType": "RESIDENTIAL",
                "perilType": "FLOOD"
              }
            }
            """;

    private final ClaimSubmissionService submissionService;
    private final ClaimQueryService queryService;
    private final CurrentUserService currentUserService;

    public ClaimController(ClaimSubmissionService submissionService, ClaimQueryService queryService,
                           CurrentUserService currentUserService) {
        this.submissionService = submissionService;
        this.queryService = queryService;
        this.currentUserService = currentUserService;
    }

    @Operation(summary = "Submit a claim (first notice of loss)",
            description = "A claimant reports an incident under one of their own policies. Give the motor block for a "
                    + "MOTOR claim or the property block for a PROPERTY claim, never both. The claim takes the "
                    + "currency of your market. With an Idempotency-Key header (8 to 64 characters) a repeated "
                    + "request returns the original claim with status 200 and the header Idempotent-Replay: true "
                    + "instead of creating a second claim.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    mediaType = "application/json",
                    examples = {
                            @ExampleObject(name = "Motor claim", summary = "A full MOTOR claim", value = MOTOR_EXAMPLE),
                            @ExampleObject(name = "Property claim", summary = "A full PROPERTY claim",
                                    value = PROPERTY_EXAMPLE)})))
    @ApiResponse(responseCode = "201", description = "Claim created; the Location header points to it")
    @ApiResponse(responseCode = "200", description = "Replay: the idempotency key was used before; the original "
            + "claim is returned with the header Idempotent-Replay: true")
    @ApiResponse(responseCode = "400", description = "Validation failed, for example a wrong or missing motor or "
            + "property block, a bad date or amount, or an idempotency key of the wrong length")
    @ApiResponse(responseCode = "401", description = "No valid token")
    @ApiResponse(responseCode = "403", description = "Only a CLAIMANT may submit a claim")
    @ApiResponse(responseCode = "409", description = "DUPLICATE_CLAIM: a claim for the same policy, incident date and "
            + "type exists and is not rejected")
    @ApiResponse(responseCode = "422", description = "Policy rule broken: POLICY_NOT_FOUND, POLICY_NOT_ACTIVE, "
            + "POLICY_TYPE_MISMATCH, POLICY_MARKET_MISMATCH or AMOUNT_EXCEEDS_SUM_INSURED")
    @PreAuthorize(Authz.CLAIMANT)
    @PostMapping
    public ResponseEntity<ClaimDetailResponse> submit(
            @Parameter(description = "Optional key that makes the submission safe to repeat; 8 to 64 characters",
                    example = "7d9f3c52-claim-0001")
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody SubmitClaimRequest request) {
        SubmissionResult result = submissionService.submit(currentUserService.requireCurrentUser(), request,
                idempotencyKey);
        if (result.replay()) {
            return ResponseEntity.ok().header("Idempotent-Replay", "true").body(result.claim());
        }
        return ResponseEntity.created(URI.create("/api/v1/claims/" + result.claim().claimNumber()))
                .body(result.claim());
    }

    @Operation(summary = "List claims",
            description = "Lists the claims the caller may see: a claimant their own, an officer those of their market, "
                    + "a manager all. Filter by status, type, market, assignee or submission date (both dates "
                    + "inclusive). Size defaults to 10 and is capped at 50. Sort by createdAt, claimedAmount, "
                    + "incidentDate or claimNumber, for example sort=claimedAmount,desc; the default is createdAt,desc.")
    @ApiResponse(responseCode = "200", description = "A page of claims")
    @ApiResponse(responseCode = "400", description = "Invalid filter, date range, page, size or sort")
    @ApiResponse(responseCode = "401", description = "No valid token")
    @PreAuthorize(Authz.AUTHENTICATED)
    @GetMapping
    public PageResponse<ClaimSummaryResponse> list(
            @Parameter(description = "Only claims in this status") @RequestParam(required = false) ClaimStatus status,
            @Parameter(description = "Only claims of this type") @RequestParam(required = false) ClaimType claimType,
            @Parameter(description = "Only claims of this market, for example IN")
            @RequestParam(required = false) String marketCode,
            @Parameter(description = "me, or the user id of an officer") @RequestParam(required = false)
            String assignedTo,
            @Parameter(description = "true for claims nobody has taken, false for claims that have an officer")
            @RequestParam(required = false) Boolean unassigned,
            @Parameter(description = "Submitted on or after this day (UTC), yyyy-MM-dd", example = "2026-10-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate submittedFrom,
            @Parameter(description = "Submitted on or before this day (UTC), yyyy-MM-dd", example = "2026-10-31")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate submittedTo,
            @Parameter(description = "Page number, starting at 0") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size, default 10, maximum 50") @RequestParam(required = false) Integer size,
            @Parameter(description = "createdAt, claimedAmount, incidentDate or claimNumber, optionally with ,asc or ,desc")
            @RequestParam(required = false) String sort) {
        ClaimFilters filters = new ClaimFilters(status, claimType, marketCode, assignedTo, unassigned, submittedFrom,
                submittedTo);
        return queryService.list(currentUserService.requireCurrentUser(), filters,
                ClaimQueryService.pageRequest(page, size, sort));
    }

    @Operation(summary = "The officer queue",
            description = "Submitted claims that nobody has picked up yet, oldest first, with their age in hours and "
                    + "whether they breach the first-review target. An officer sees the queue of their own market, "
                    + "a manager the queue of all markets.")
    @ApiResponse(responseCode = "200", description = "A page of waiting claims")
    @ApiResponse(responseCode = "401", description = "No valid token")
    @ApiResponse(responseCode = "403", description = "Only an OFFICER or a MANAGER may do this")
    @PreAuthorize(Authz.STAFF)
    @GetMapping("/queue")
    public PageResponse<QueueItemResponse> queue(
            @Parameter(description = "Page number, starting at 0") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size, default 10, maximum 50") @RequestParam(required = false) Integer size) {
        return queryService.queue(currentUserService.requireCurrentUser(),
                ClaimQueryService.queuePageRequest(page, size));
    }

    @Operation(summary = "Get a claim",
            description = "One claim in full. A claim outside the caller's scope is reported as not found. Staff also "
                    + "receive the latest assessment; a claimant never does.")
    @ApiResponse(responseCode = "200", description = "The claim")
    @ApiResponse(responseCode = "401", description = "No valid token")
    @ApiResponse(responseCode = "404", description = "Unknown claim, or a claim the caller may not see")
    @PreAuthorize(Authz.AUTHENTICATED)
    @GetMapping("/{claimNumber}")
    public ClaimDetailResponse get(@Parameter(description = "Claim number", example = "CLM-10003")
                                   @PathVariable String claimNumber) {
        return queryService.get(claimNumber, currentUserService.requireCurrentUser());
    }

    @Operation(summary = "Claim timeline",
            description = "The history of the claim, oldest first. A claimant only receives the PUBLIC entries, and "
                    + "staff actions show the actor as \"Claims team\"; staff receive every entry.")
    @ApiResponse(responseCode = "200", description = "The timeline entries")
    @ApiResponse(responseCode = "401", description = "No valid token")
    @ApiResponse(responseCode = "404", description = "Unknown claim, or a claim the caller may not see")
    @PreAuthorize(Authz.AUTHENTICATED)
    @GetMapping("/{claimNumber}/timeline")
    public List<TimelineEntryResponse> timeline(@Parameter(description = "Claim number", example = "CLM-10003")
                                                @PathVariable String claimNumber) {
        return queryService.timeline(claimNumber, currentUserService.requireCurrentUser());
    }
}
