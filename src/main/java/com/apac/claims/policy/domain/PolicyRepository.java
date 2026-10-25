package com.apac.claims.policy.domain;

import java.util.List;
import java.util.Optional;

import com.apac.claims.identity.domain.AppUser;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence port for policy snapshots. */
public interface PolicyRepository extends JpaRepository<Policy, Long> {

    Optional<Policy> findByPolicyNumber(String policyNumber);

    List<Policy> findByHolder(AppUser holder);
}
