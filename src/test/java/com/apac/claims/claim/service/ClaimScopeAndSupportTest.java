package com.apac.claims.claim.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import com.apac.claims.claim.domain.Claim;
import com.apac.claims.claim.domain.ClaimRepository;
import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.claim.domain.ClaimType;
import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.identity.domain.UserRepository;
import com.apac.claims.market.domain.Market;
import com.apac.claims.shared.exception.ForbiddenException;
import com.apac.claims.shared.exception.ResourceNotFoundException;
import com.apac.claims.shared.exception.UnauthenticatedException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The two classes that decide who sees and who may act on a claim, without a database: the visibility matrix of
 * {@link ClaimScope} and the "assigned officer or manager" rule of {@link ClaimActionSupport}.
 */
class ClaimScopeAndSupportTest {

    private final ClaimRepository claims = mock(ClaimRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ClaimScope scope = new ClaimScope(claims);
    private final ClaimActionSupport support = new ClaimActionSupport(users, scope);

    private Market india;
    private Market singapore;
    private AppUser owner;
    private AppUser otherClaimant;
    private AppUser officerIn;
    private AppUser otherOfficerIn;
    private AppUser officerSg;
    private AppUser manager;
    private Claim claim;

    @BeforeEach
    void setUp() {
        india = market("IN", 1L);
        singapore = market("SG", 2L);
        owner = user("claimant.in", Role.CLAIMANT, india, 1L);
        otherClaimant = user("claimant.in2", Role.CLAIMANT, india, 2L);
        officerIn = user("officer.in", Role.OFFICER, india, 3L);
        otherOfficerIn = user("officer.in2", Role.OFFICER, india, 4L);
        officerSg = user("officer.sg", Role.OFFICER, singapore, 5L);
        manager = user("manager.apac", Role.MANAGER, null, 6L);
        claim = new Claim("CLM-10001", ClaimType.MOTOR, ClaimStatus.UNDER_REVIEW, "MOT-IN-0001",
                new BigDecimal("1500000.00"), new BigDecimal("5000.00"), owner, india, "INR",
                new BigDecimal("45000.00"), LocalDate.of(2026, 10, 1), "MG Road", "Rear-ended at a traffic signal");
        claim.setAssignedOfficer(officerIn);
        when(claims.findByClaimNumber("CLM-10001")).thenReturn(Optional.of(claim));
    }

    private static Market market(String code, long id) {
        Market market = new Market(code, code, "INR", new BigDecimal("1000.00"), true);
        ReflectionTestUtils.setField(market, "id", id);
        return market;
    }

    private static AppUser user(String userId, Role role, Market market, long id) {
        AppUser user = new AppUser(userId, "hash", userId, userId + "@test.example", role, market);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    // ---- scope ----------------------------------------------------------------------------------------------------

    @Test
    void aClaimantSeesOnlyTheirOwnClaimAnOfficerOnlyTheirMarketAndAManagerEverything() {
        assertThat(scope.isVisible(claim, owner)).isTrue();
        assertThat(scope.isVisible(claim, otherClaimant)).isFalse();
        assertThat(scope.isVisible(claim, officerIn)).isTrue();
        assertThat(scope.isVisible(claim, otherOfficerIn)).isTrue();
        assertThat(scope.isVisible(claim, officerSg)).isFalse();
        assertThat(scope.isVisible(claim, manager)).isTrue();
    }

    @Test
    void anOfficerWithoutAMarketSeesNothing() {
        AppUser orphan = user("officer.none", Role.OFFICER, null, 7L);

        assertThat(scope.isVisible(claim, orphan)).isFalse();
    }

    @Test
    void aClaimOutsideTheScopeLooksLikeAClaimThatDoesNotExist() {
        assertThatThrownBy(() -> scope.requireVisible("CLM-10001", officerSg))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> scope.requireVisible("CLM-99999", manager))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theClaimNumberIsTrimmedAndNotCaseSensitive() {
        assertThat(scope.requireVisible("  clm-10001 ", owner)).isSameAs(claim);
    }

    @Test
    void aMissingClaimNumberIsNotFoundAndNotAnError() {
        assertThatThrownBy(() -> scope.requireVisible(null, manager)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- who may act ------------------------------------------------------------------------------------------------

    @Test
    void theAssignedOfficerAndAnyManagerMayActButAnotherOfficerMayNot() {
        assertThatCode(() -> support.requireAssigneeOrManager(claim, officerIn, "assess this claim"))
                .doesNotThrowAnyException();
        assertThatCode(() -> support.requireAssigneeOrManager(claim, manager, "assess this claim"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> support.requireAssigneeOrManager(claim, otherOfficerIn, "assess this claim"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Only the assigned officer or a manager can assess this claim.");
    }

    @Test
    void anOfficerCannotActOnAClaimNobodyHasTakenYet() {
        claim.setAssignedOfficer(null);

        assertThatThrownBy(() -> support.requireAssigneeOrManager(claim, officerIn, "reject this claim"))
                .isInstanceOf(ForbiddenException.class);
        assertThatCode(() -> support.requireAssigneeOrManager(claim, manager, "reject this claim"))
                .doesNotThrowAnyException();
    }

    @Test
    void aClaimantIsNeverStaff() {
        assertThatThrownBy(() -> support.requireStaff(owner)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> support.requireAssigneeOrManager(claim, owner, "assess this claim"))
                .isInstanceOf(ForbiddenException.class);
        assertThatCode(() -> support.requireStaff(officerIn)).doesNotThrowAnyException();
        assertThatCode(() -> support.requireStaff(manager)).doesNotThrowAnyException();
    }

    @Test
    void onlyAManagerPassesTheManagerCheck() {
        assertThatCode(() -> support.requireManager(manager)).doesNotThrowAnyException();
        assertThatThrownBy(() -> support.requireManager(officerIn)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> support.requireManager(owner)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void isAssignedToComparesTheOfficerById() {
        assertThat(ClaimActionSupport.isAssignedTo(claim, officerIn)).isTrue();
        assertThat(ClaimActionSupport.isAssignedTo(claim, otherOfficerIn)).isFalse();
        claim.setAssignedOfficer(null);
        assertThat(ClaimActionSupport.isAssignedTo(claim, officerIn)).isFalse();
    }

    @Test
    void aCallerWhoNoLongerExistsIsUnauthenticated() {
        when(users.findById(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> support.reload(officerIn)).isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void theCallerIsReloadedAsALiveEntity() {
        AppUser fresh = user("officer.in", Role.OFFICER, india, 3L);
        when(users.findById(3L)).thenReturn(Optional.of(fresh));

        assertThat(support.reload(officerIn)).isSameAs(fresh);
    }
}
