package com.apac.claims.market.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One of the six markets. It fixes the currency of its claims and the largest amount an officer may approve
 * without escalating, expressed in that currency.
 */
@Entity
@Table(name = "markets")
public class Market {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "code", nullable = false, length = 2)
    private String code;

    @Column(name = "country_name", nullable = false, length = 60)
    private String countryName;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "escalation_threshold", nullable = false, precision = 19, scale = 2)
    private BigDecimal escalationThreshold;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected Market() {
    }

    public Market(String code, String countryName, String currencyCode, BigDecimal escalationThreshold,
                  boolean active) {
        this.code = code;
        this.countryName = countryName;
        this.currencyCode = currencyCode;
        this.escalationThreshold = escalationThreshold;
        this.active = active;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getCountryName() {
        return countryName;
    }

    public void setCountryName(String countryName) {
        this.countryName = countryName;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
    }

    public BigDecimal getEscalationThreshold() {
        return escalationThreshold;
    }

    public void setEscalationThreshold(BigDecimal escalationThreshold) {
        this.escalationThreshold = escalationThreshold;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
