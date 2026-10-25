# ADR 0010: Static illustrative exchange rates for base-currency exposure

- **Status:** Accepted
- **Date:** 2026-10-25

## Context

Each market works in its own currency (INR, SGD, AUD, JPY, MYR, THB). Managers must see liability exposure across all markets, which is only meaningful in one currency. A real exchange-rate feed needs credentials, network access and a policy for which rate applies to which date, none of which is in scope.

## Decision

- Rates are configuration, bound to a `@ConfigurationProperties` record: `app.reporting.base-currency` (`USD`), `app.reporting.rate-source` (`STATIC_ILLUSTRATIVE`) and `app.reporting.rates` (INR 0.012, SGD 0.74, AUD 0.65, JPY 0.0067, MYR 0.21, THB 0.029).
- Reports give every amount in the **local currency** of the market and, where totals across markets are needed, in the **base currency** only. Totals across markets are never given in local currencies.
- The exposure response states `baseCurrency` and `rateSource: "STATIC_ILLUSTRATIVE"`.
- The rates are labelled as illustrative in the API, in the documents and in the README. They are for demonstration and must not be used for financial reporting.
- Claims, assessments and payments always use the claim's own currency; conversion happens only in the reporting module.

## Alternatives considered

| Alternative | Why not |
|---|---|
| A live external rate service | Network dependency and credentials; tests become flaky or need a stub; outside the scope |
| A database table of rates with history | More schema and an admin interface, and an agreed rule for which date's rate to use |
| No conversion | Managers would see six separate totals and could not compare markets |

## Consequences

**Good**
- Deterministic and testable numbers; no external dependency.
- The label makes clear that the totals are not accounting figures.

**Bad**
- The base-currency totals are not real values and do not move with the market.
- Changing a rate means changing configuration and restarting the application.
- The rates apply to all dates; there is no historical rate.

## Implementation note (prompt 5)

`reporting.service.CurrencyConverter` multiplies an amount by the rate of its currency and rounds once, half up, to the two decimal places of the base currency; amounts of several markets are converted row by row and then added, never added in their local currencies. The overview, exposure and aging responses carry `baseCurrency` and `rateSource`. The application refuses to start (`RateConfigurationCheck`) if the currency of a market has no rate, so a market added without one is found at start-up. The sums are computed in memory in Java on purpose (see the class documentation of `ReportingService`); a read model or SQL aggregation is the step for a larger volume.
