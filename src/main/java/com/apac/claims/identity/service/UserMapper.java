package com.apac.claims.identity.service;

import com.apac.claims.identity.api.dto.UserResponse;
import com.apac.claims.identity.api.dto.UserSummary;
import com.apac.claims.identity.domain.AppUser;

/**
 * Maps accounts to their response records. It is called inside the services' transactions, because the market is
 * loaded lazily and entities never leave the service layer.
 */
final class UserMapper {

    private UserMapper() {
    }

    static UserResponse toResponse(AppUser user) {
        return new UserResponse(user.getUserId(), user.getFullName(), user.getEmail(), user.getRole(),
                marketCode(user), user.isActive(), user.getCreatedAt());
    }

    static UserSummary toSummary(AppUser user) {
        return new UserSummary(user.getUserId(), user.getFullName(), user.getRole(), marketCode(user));
    }

    static String marketCode(AppUser user) {
        return user.getMarket() == null ? null : user.getMarket().getCode();
    }
}
