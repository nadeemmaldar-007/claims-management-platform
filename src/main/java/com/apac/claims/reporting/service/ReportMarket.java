package com.apac.claims.reporting.service;

/** A market inside the scope of a report: its code and the currency of its amounts. */
public record ReportMarket(String code, String currencyCode) {
}
