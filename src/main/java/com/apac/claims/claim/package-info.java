/**
 * Claim: the core of the platform. First notice of loss, workflow, queue and pick-up, assignment, assessment,
 * information requests, decisions, settlement, timeline and claim numbers.
 * External systems are reached only through ports owned here and through the shared event contracts.
 *
 * <p>May depend on: {@code shared}, {@code config}, {@code identity}, {@code market}.
 * Must not depend on {@code policy}, {@code notification}, {@code reporting} or {@code integration}.
 */
package com.apac.claims.claim;
