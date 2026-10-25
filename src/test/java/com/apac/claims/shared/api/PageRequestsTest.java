package com.apac.claims.shared.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import com.apac.claims.shared.exception.ValidationFailedException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class PageRequestsTest {

    private static final Set<String> ALLOWED = Set.of("userId", "createdAt");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.ASC, "userId");

    private static Pageable of(Integer page, Integer size, String sort) {
        return PageRequests.of(page, size, sort, ALLOWED, DEFAULT_SORT);
    }

    @Test
    void defaultsToTheFirstPageOfTenWithTheDefaultSort() {
        Pageable pageable = of(null, null, null);

        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(10);
        assertThat(pageable.getSort()).isEqualTo(DEFAULT_SORT);
    }

    @Test
    void aSizeAboveFiftyIsCappedNotRejected() {
        assertThat(of(0, 500, null).getPageSize()).isEqualTo(50);
        assertThat(of(0, 51, null).getPageSize()).isEqualTo(50);
        assertThat(of(0, 50, null).getPageSize()).isEqualTo(50);
    }

    @Test
    void aSmallSizeIsKept() {
        assertThat(of(3, 1, null).getPageSize()).isEqualTo(1);
        assertThat(of(3, 1, null).getPageNumber()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -50})
    void aSizeBelowOneIsRejectedNamingTheParameter(int size) {
        assertThatThrownBy(() -> of(0, size, null))
                .isInstanceOfSatisfying(ValidationFailedException.class,
                        e -> assertThat(e.getErrors().get(0).field()).isEqualTo("size"));
    }

    @Test
    void aNegativePageIsRejectedNamingTheParameter() {
        assertThatThrownBy(() -> of(-1, 10, null))
                .isInstanceOfSatisfying(ValidationFailedException.class,
                        e -> assertThat(e.getErrors().get(0).field()).isEqualTo("page"));
    }

    @Test
    void aSortFieldFromTheWhitelistIsAcceptedAscendingByDefault() {
        assertThat(of(0, 10, "createdAt").getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "createdAt"));
    }

    @Test
    void theDirectionIsReadCaseInsensitively() {
        assertThat(of(0, 10, "createdAt,desc").getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
        assertThat(of(0, 10, "userId, DESC").getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "userId"));
        assertThat(of(0, 10, "userId,ASC").getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "userId"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "id", "email", "createdAt;drop table app_users", "UserId"})
    void aSortFieldOutsideTheWhitelistIsRejectedAndTheWhitelistIsListed(String sort) {
        assertThatThrownBy(() -> of(0, 10, sort))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> {
                    assertThat(e.getErrors().get(0).field()).isEqualTo("sort");
                    assertThat(e.getErrors().get(0).message()).contains("createdAt, userId");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"userId,sideways", "userId,asc,extra", "userId,"})
    void aBadDirectionOrShapeIsRejected(String sort) {
        assertThatThrownBy(() -> of(0, 10, sort)).isInstanceOf(ValidationFailedException.class);
    }

    @Test
    void aBlankSortMeansTheDefault() {
        assertThat(of(0, 10, "   ").getSort()).isEqualTo(DEFAULT_SORT);
        assertThat(of(0, 10, "").getSort()).isEqualTo(DEFAULT_SORT);
    }
}
