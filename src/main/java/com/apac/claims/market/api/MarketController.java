package com.apac.claims.market.api;

import java.util.List;

import com.apac.claims.market.api.dto.MarketResponse;
import com.apac.claims.market.service.MarketService;
import com.apac.claims.shared.security.Authz;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The markets with their currencies and escalation thresholds, readable by every logged-in user. */
@RestController
@RequestMapping("/api/v1/markets")
@Tag(name = "Markets", description = "The six markets, their currencies and escalation thresholds.")
public class MarketController {

    private final MarketService marketService;

    public MarketController(MarketService marketService) {
        this.marketService = marketService;
    }

    @Operation(summary = "List markets", description = "Returns the six markets with currency and threshold.")
    @ApiResponse(responseCode = "200", description = "The markets")
    @ApiResponse(responseCode = "401", description = "No valid token")
    @PreAuthorize(Authz.AUTHENTICATED)
    @GetMapping
    public List<MarketResponse> list() {
        return marketService.list();
    }

    @Operation(summary = "Get a market", description = "Returns one market. The code is not case sensitive.")
    @ApiResponse(responseCode = "200", description = "The market")
    @ApiResponse(responseCode = "401", description = "No valid token")
    @ApiResponse(responseCode = "404", description = "No market has this code")
    @PreAuthorize(Authz.AUTHENTICATED)
    @GetMapping("/{code}")
    public MarketResponse get(@Parameter(description = "Market code, for example IN or jp", example = "IN")
                              @PathVariable String code) {
        return marketService.getByCode(code);
    }
}
