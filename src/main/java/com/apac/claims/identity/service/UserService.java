package com.apac.claims.identity.service;

import java.util.Locale;
import java.util.Set;

import com.apac.claims.identity.api.dto.CreateUserRequest;
import com.apac.claims.identity.api.dto.UserResponse;
import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.identity.domain.UserRepository;
import com.apac.claims.market.domain.Market;
import com.apac.claims.market.domain.MarketRepository;
import com.apac.claims.shared.api.PageRequests;
import com.apac.claims.shared.api.PageResponse;
import com.apac.claims.shared.exception.DuplicateResourceException;
import com.apac.claims.shared.exception.ValidationFailedException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account rules: how accounts are created (by self-registration or by a manager), who may have a market, and the
 * paged user list. It is the only place that creates accounts, so the duplicate, market and password rules cannot
 * differ between registration and staff creation.
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /** Fields the user list may be sorted by. */
    static final Set<String> SORTABLE_FIELDS = Set.of("userId", "createdAt");

    private final UserRepository users;
    private final MarketRepository markets;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository users, MarketRepository markets, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.markets = markets;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Creates an account of any role. Callers decide which role is allowed: registration passes CLAIMANT and the
     * staff endpoint passes OFFICER or MANAGER. The rules about the market belong to the role and are applied here.
     */
    @Transactional
    public UserResponse createAccount(String userId, String rawPassword, String fullName, String email, Role role,
                                      String marketCode) {
        String normalisedUserId = userId.trim().toLowerCase(Locale.ROOT);
        String normalisedEmail = email.trim().toLowerCase(Locale.ROOT);
        Market market = resolveMarket(role, marketCode);
        if (users.existsByUserId(normalisedUserId)) {
            throw new DuplicateResourceException("User ID is already in use");
        }
        if (users.existsByEmail(normalisedEmail)) {
            throw new DuplicateResourceException("Email is already registered");
        }
        AppUser saved = users.save(new AppUser(normalisedUserId, passwordEncoder.encode(rawPassword),
                fullName.trim(), normalisedEmail, role, market));
        log.info("Account created: userId={} role={}", saved.getUserId(), saved.getRole());
        return UserMapper.toResponse(saved);
    }

    /** Creates an officer or a manager. A claimant cannot be created here; claimants register themselves. */
    @Transactional
    public UserResponse createStaff(CreateUserRequest request) {
        if (request.role() == Role.CLAIMANT) {
            throw ValidationFailedException.forField("role", "must be OFFICER or MANAGER");
        }
        return createAccount(request.userId(), request.password(), request.fullName(), request.email(),
                request.role(), request.marketCode());
    }

    @Transactional(readOnly = true)
    public UserResponse getMe(AppUser currentUser) {
        return UserMapper.toResponse(currentUser);
    }

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> list(Role role, String marketCode, Pageable pageable) {
        Specification<AppUser> filter = (root, query, cb) -> cb.conjunction();
        if (role != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("role"), role));
        }
        if (marketCode != null && !marketCode.isBlank()) {
            String code = marketCode.trim().toUpperCase(Locale.ROOT);
            filter = filter.and((root, query, cb) -> cb.equal(root.get("market").get("code"), code));
        }
        // The id is added as a tie-breaker, because createdAt alone does not give a stable order across pages.
        Pageable stable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                pageable.getSort().and(Sort.by(Sort.Direction.ASC, "id")));
        return PageResponse.of(users.findAll(filter, stable), UserMapper::toResponse);
    }

    /** The page request for the user list: size capped at 50, sort limited to userId and createdAt. */
    public static Pageable pageRequest(Integer page, Integer size, String sort) {
        return PageRequests.of(page, size, sort, SORTABLE_FIELDS, Sort.by(Sort.Direction.ASC, "userId"));
    }

    private Market resolveMarket(Role role, String marketCode) {
        boolean hasMarket = marketCode != null && !marketCode.isBlank();
        if (role == Role.MANAGER) {
            if (hasMarket) {
                throw ValidationFailedException.forField("marketCode", "must not be set for a MANAGER");
            }
            return null;
        }
        if (!hasMarket) {
            throw ValidationFailedException.forField("marketCode", "is required for a " + role);
        }
        return markets.findByCode(marketCode.trim().toUpperCase(Locale.ROOT))
                .filter(Market::isActive)
                .orElseThrow(() -> ValidationFailedException.forField("marketCode", "unknown or inactive market"));
    }
}
