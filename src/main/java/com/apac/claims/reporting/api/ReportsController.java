package com.apac.claims.reporting.api;

import java.time.LocalDate;

import com.apac.claims.identity.service.CurrentUserService;
import com.apac.claims.reporting.api.dto.AgingResponse;
import com.apac.claims.reporting.api.dto.ExposureResponse;
import com.apac.claims.reporting.api.dto.OverviewResponse;
import com.apac.claims.reporting.api.dto.PerformanceResponse;
import com.apac.claims.reporting.api.dto.WorkloadResponse;
import com.apac.claims.reporting.service.ReportingService;
import com.apac.claims.shared.security.Authz;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The five reports. They are computed live and read only. An officer sees the market of their own team; a manager sees
 * every market and may narrow a report to one with {@code marketCode}. A claimant gets a 403 on every report.
 */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reports", description = "Workload, performance, aging and exposure.")
public class ReportsController {

    private static final String MARKET_CODE = "A manager can narrow the report to one market, for example IN. An officer "
            + "always sees their own market only: another market gives an empty report.";

    private final ReportingService service;
    private final CurrentUserService currentUserService;

    public ReportsController(ReportingService service, CurrentUserService currentUserService) {
        this.service = service;
        this.currentUserService = currentUserService;
    }

    @Operation(summary = "Overview",
            description = "Claims by status (all seven statuses, zero where there is none) and by type, the outstanding "
                    + "exposure per market and in the base currency, the size of the queue, how many queued claims and "
                    + "how many open claims are late, and the open claims by age. The exchange rates are fixed and "
                    + "illustrative (rateSource STATIC_ILLUSTRATIVE).")
    @ApiResponse(responseCode = "200", description = "The overview")
    @ApiResponse(responseCode = "400", description = "Unknown market code")
    @ApiResponse(responseCode = "401", description = "No valid token")
    @ApiResponse(responseCode = "403", description = "Only an OFFICER or a MANAGER may do this")
    @PreAuthorize(Authz.STAFF)
    @GetMapping("/overview")
    public OverviewResponse overview(@Parameter(description = MARKET_CODE, example = "IN")
                                     @RequestParam(required = false) String marketCode) {
        return service.overview(currentUserService.requireCurrentUser(), marketCode);
    }

    @Operation(summary = "Liability exposure",
            description = "Per market and claim type: the open claimed amount, the approved amount waiting for payment, "
                    + "the settled total and the outstanding exposure (open plus approved), each in the local currency "
                    + "and in the base currency, and a total in the base currency only. The exchange rates are fixed "
                    + "and illustrative (rateSource STATIC_ILLUSTRATIVE). Managers only.")
    @ApiResponse(responseCode = "200", description = "The exposure rows and the total")
    @ApiResponse(responseCode = "400", description = "Unknown market code")
    @ApiResponse(responseCode = "401", description = "No valid token")
    @ApiResponse(responseCode = "403", description = "Only a MANAGER may do this")
    @PreAuthorize(Authz.MANAGER)
    @GetMapping("/exposure")
    public ExposureResponse exposure(@Parameter(description = MARKET_CODE, example = "IN")
                                     @RequestParam(required = false) String marketCode) {
        return service.exposure(currentUserService.requireCurrentUser(), marketCode);
    }

    @Operation(summary = "Aging of the open claims",
            description = "The open claims by whole days since submission in four bands (0-2, 3-7, 8-14, 15+ days): the "
                    + "number and the exposure in the base currency, and in the local currency when the report covers "
                    + "one market.")
    @ApiResponse(responseCode = "200", description = "The four bands")
    @ApiResponse(responseCode = "400", description = "Unknown market code")
    @ApiResponse(responseCode = "401", description = "No valid token")
    @ApiResponse(responseCode = "403", description = "Only an OFFICER or a MANAGER may do this")
    @PreAuthorize(Authz.STAFF)
    @GetMapping("/aging")
    public AgingResponse aging(@Parameter(description = MARKET_CODE, example = "IN")
                               @RequestParam(required = false) String marketCode) {
        return service.aging(currentUserService.requireCurrentUser(), marketCode);
    }

    @Operation(summary = "Workload",
            description = "Per active officer: the open claims by status, the approved claims waiting for settlement, the "
                    + "age of the oldest open claim and the claims that are late; per market: the claims in the queue and "
                    + "how many of them are late. An officer sees their own team.")
    @ApiResponse(responseCode = "200", description = "One row per officer and one per market")
    @ApiResponse(responseCode = "400", description = "Unknown market code")
    @ApiResponse(responseCode = "401", description = "No valid token")
    @ApiResponse(responseCode = "403", description = "Only an OFFICER or a MANAGER may do this")
    @PreAuthorize(Authz.STAFF)
    @GetMapping("/workload")
    public WorkloadResponse workload(@Parameter(description = MARKET_CODE, example = "IN")
                                     @RequestParam(required = false) String marketCode) {
        return service.workload(currentUserService.requireCurrentUser(), marketCode);
    }

    @Operation(summary = "Performance",
            description = "Per active officer for a period of whole days in UTC, both ends included: decisions, approval "
                    + "rate, settled claims, the average hours from submission to the first assignment and to the "
                    + "decision, and the total approved. The default period is the 30 days up to and including today. "
                    + "An officer sees their own team.")
    @ApiResponse(responseCode = "200", description = "One row per officer")
    @ApiResponse(responseCode = "400", description = "from is after to, a date is invalid, or the market code is unknown")
    @ApiResponse(responseCode = "401", description = "No valid token")
    @ApiResponse(responseCode = "403", description = "Only an OFFICER or a MANAGER may do this")
    @PreAuthorize(Authz.STAFF)
    @GetMapping("/performance")
    public PerformanceResponse performance(
            @Parameter(description = "First day of the period (UTC), yyyy-MM-dd; default 29 days before to",
                    example = "2026-09-26")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Last day of the period (UTC), yyyy-MM-dd; default today", example = "2026-10-25")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = MARKET_CODE, example = "IN") @RequestParam(required = false) String marketCode) {
        return service.performance(currentUserService.requireCurrentUser(), marketCode, from, to);
    }
}
