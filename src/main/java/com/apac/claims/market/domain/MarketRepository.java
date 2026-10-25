package com.apac.claims.market.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence port for markets. */
public interface MarketRepository extends JpaRepository<Market, Long> {

    Optional<Market> findByCode(String code);

    boolean existsByCode(String code);

    /** All markets in the order they were seeded, which is the order the API lists them in. */
    List<Market> findAllByOrderByIdAsc();
}
