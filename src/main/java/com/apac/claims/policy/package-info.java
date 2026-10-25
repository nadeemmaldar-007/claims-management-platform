/**
 * Policy: reference data held as a local snapshot that stands in for the policy administration system,
 * and the adapter that implements the claim module's {@code PolicyLookup} port.
 *
 * <p>May depend on: {@code shared}, {@code config}, {@code identity}, {@code market}, {@code claim}
 * (it implements a port owned by {@code claim}).
 */
package com.apac.claims.policy;
