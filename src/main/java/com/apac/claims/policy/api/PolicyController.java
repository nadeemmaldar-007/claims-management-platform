package com.apac.claims.policy.api;

import java.net.URI;
import java.util.List;

import com.apac.claims.identity.service.CurrentUserService;
import com.apac.claims.policy.api.dto.PolicyResponse;
import com.apac.claims.policy.api.dto.UpsertPolicyRequest;
import com.apac.claims.policy.service.PolicyService;
import com.apac.claims.policy.service.UpsertResult;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Policy reference data: a claimant's own policies, staff lookups, and maintenance by a manager. */
@RestController
@RequestMapping("/api/v1/policies")
@Tag(name = "Policies", description = "Policy reference data: own policies for claimants, maintenance for managers.")
public class PolicyController {

    private final PolicyService policyService;
    private final CurrentUserService currentUserService;

    public PolicyController(PolicyService policyService, CurrentUserService currentUserService) {
        this.policyService = policyService;
        this.currentUserService = currentUserService;
    }

    @Operation(summary = "My policies", description = "A claimant lists the policies they hold.")
    @ApiResponse(responseCode = "200", description = "The caller's policies, ordered by policy number")
    @ApiResponse(responseCode = "401", description = "No valid token")
    @ApiResponse(responseCode = "403", description = "Only a CLAIMANT may do this")
    @PreAuthorize(Authz.CLAIMANT)
    @GetMapping("/mine")
    public List<PolicyResponse> mine() {
        return policyService.listMine(currentUserService.requireCurrentUser());
    }

    @Operation(summary = "Get a policy",
            description = "An officer reads a policy of their own market; a manager reads any policy.")
    @ApiResponse(responseCode = "200", description = "The policy")
    @ApiResponse(responseCode = "401", description = "No valid token")
    @ApiResponse(responseCode = "403", description = "Only an OFFICER or a MANAGER may do this")
    @ApiResponse(responseCode = "404", description = "Unknown policy, or a policy of another market than the officer's")
    @PreAuthorize(Authz.STAFF)
    @GetMapping("/{policyNumber}")
    public PolicyResponse get(@Parameter(description = "Policy number, not case sensitive", example = "MOT-IN-0001")
                              @PathVariable String policyNumber) {
        return policyService.getForStaff(policyNumber, currentUserService.requireCurrentUser());
    }

    @Operation(summary = "Create or update a policy",
            description = "A manager creates the policy (201 with a Location header) or, when the number already "
                    + "exists, updates it (200). The product type, the market and the holder of an existing policy "
                    + "cannot be changed. The holder must be an active claimant of the policy's market.")
    @ApiResponse(responseCode = "200", description = "The policy was updated")
    @ApiResponse(responseCode = "201", description = "The policy was created")
    @ApiResponse(responseCode = "400", description = "Validation failed, for example an unknown holder, a holder of "
            + "another market, a deductible above the sum insured, wrong decimal places, or a change of an "
            + "immutable field")
    @ApiResponse(responseCode = "401", description = "No valid token")
    @ApiResponse(responseCode = "403", description = "Only a MANAGER may do this")
    @PreAuthorize(Authz.MANAGER)
    @PutMapping("/{policyNumber}")
    public ResponseEntity<PolicyResponse> upsert(
            @Parameter(description = "Policy number: 6 to 20 letters, digits or dashes; stored upper case",
                    example = "MOT-IN-0003") @PathVariable String policyNumber,
            @Valid @RequestBody UpsertPolicyRequest request) {
        UpsertResult result = policyService.upsert(policyNumber, request);
        if (result.created()) {
            return ResponseEntity.created(URI.create("/api/v1/policies/" + result.policy().policyNumber()))
                    .body(result.policy());
        }
        return ResponseEntity.ok(result.policy());
    }
}
