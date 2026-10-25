package com.apac.claims.identity.domain;

import java.util.List;
import java.util.Optional;

import com.apac.claims.market.domain.Market;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Persistence port for accounts. {@link JpaSpecificationExecutor} lets the paged user list filter by role and
 * market with {@code Specification} objects instead of optional-parameter JPQL.
 */
public interface UserRepository extends JpaRepository<AppUser, Long>, JpaSpecificationExecutor<AppUser> {

    Optional<AppUser> findByUserId(String userId);

    boolean existsByUserId(String userId);

    /** Used on every authenticated request, so it asks the database a yes-or-no question and loads nothing. */
    boolean existsByUserIdAndActiveTrue(String userId);

    boolean existsByEmail(String email);

    List<AppUser> findByRoleAndMarketAndActiveTrue(Role role, Market market);

    /**
     * Active accounts of a role in the market with the given code. Notifications know the market only as the code in
     * an event payload and may not depend on the market module, so they ask by code.
     */
    List<AppUser> findByRoleAndMarketCodeAndActiveTrue(Role role, String marketCode);

    List<AppUser> findByRoleAndActiveTrue(Role role);
}
