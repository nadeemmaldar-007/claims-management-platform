package com.apac.claims.claim.api;

import java.net.URI;
import java.util.List;
import java.util.Locale;

import com.apac.claims.claim.api.dto.AssessmentResponse;
import com.apac.claims.claim.api.dto.RecordAssessmentRequest;
import com.apac.claims.claim.service.ClaimAssessmentService;
import com.apac.claims.identity.service.CurrentUserService;
import com.apac.claims.shared.security.Authz;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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

/** Internal assessments of a claim. Claimants never reach these endpoints. */
@RestController
@RequestMapping("/api/v1/claims/{claimNumber}/assessments")
@Tag(name = "Claims", description = "First notice of loss, workflow and decisions.")
public class ClaimAssessmentController {

    private final ClaimAssessmentService assessmentService;
    private final CurrentUserService currentUserService;

    public ClaimAssessmentController(ClaimAssessmentService assessmentService, CurrentUserService currentUserService) {
        this.assessmentService = assessmentService;
        this.currentUserService = currentUserService;
    }

    @Operation(summary = "Record an assessment",
            description = "The assigned officer or a manager records the facts of the assessment while the claim is "
                    + "UNDER_REVIEW or ESCALATED. The system computes the deductible (the claim's own) and the "
                    + "recommended payout: with coverage confirmed, the lower of the assessed loss and the sum insured "
                    + "minus the deductible, never below zero; without coverage, zero. Assessments are internal.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "Coverage confirmed", value = """
                    {
                      "assessedLossAmount": 44000.00,
                      "coverageConfirmed": true,
                      "recommendation": "APPROVE",
                      "notes": "Photos match the repair estimate."
                    }
                    """))))
    @ApiResponse(responseCode = "201", description = "The assessment; the Location header points to the list")
    @ApiResponse(responseCode = "400", description = "Invalid loss amount (or too many decimal places), missing field")
    @ApiResponse(responseCode = "401", description = "No valid token")
    @ApiResponse(responseCode = "403", description = "A claimant, or an officer who is not the assigned officer")
    @ApiResponse(responseCode = "404", description = "Unknown claim, or a claim outside the caller's market")
    @ApiResponse(responseCode = "409", description = "CLAIM_INVALID_TRANSITION: only an UNDER_REVIEW or ESCALATED "
            + "claim can be assessed")
    @PreAuthorize(Authz.STAFF)
    @PostMapping
    public ResponseEntity<AssessmentResponse> record(@Parameter(description = "Claim number", example = "CLM-10003")
                                                     @PathVariable String claimNumber,
                                                     @Valid @RequestBody RecordAssessmentRequest request) {
        AssessmentResponse assessment = assessmentService.record(claimNumber, request,
                currentUserService.requireCurrentUser());
        return ResponseEntity.created(URI.create("/api/v1/claims/" + claimNumber.trim().toUpperCase(Locale.ROOT)
                + "/assessments")).body(assessment);
    }

    @Operation(summary = "List the assessments of a claim",
            description = "Newest first; the first one is the latest and the one that counts.")
    @ApiResponse(responseCode = "200", description = "The assessments")
    @ApiResponse(responseCode = "401", description = "No valid token")
    @ApiResponse(responseCode = "403", description = "A claimant may not read assessments")
    @ApiResponse(responseCode = "404", description = "Unknown claim, or a claim outside the caller's market")
    @PreAuthorize(Authz.STAFF)
    @GetMapping
    public List<AssessmentResponse> list(@Parameter(description = "Claim number", example = "CLM-10003")
                                         @PathVariable String claimNumber) {
        return assessmentService.list(claimNumber, currentUserService.requireCurrentUser());
    }
}
