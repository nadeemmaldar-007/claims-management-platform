package com.apac.claims.reporting.service;

/** An active officer inside the scope of a report; the market gives the currency of the officer's amounts. */
public record ReportOfficer(String userId, String fullName, String marketCode, String currencyCode) {
}
