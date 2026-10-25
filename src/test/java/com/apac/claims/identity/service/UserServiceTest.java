package com.apac.claims.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.apac.claims.identity.api.dto.CreateUserRequest;
import com.apac.claims.identity.api.dto.UserResponse;
import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.identity.domain.UserRepository;
import com.apac.claims.market.domain.Market;
import com.apac.claims.market.domain.MarketRepository;
import com.apac.claims.shared.api.PageResponse;
import com.apac.claims.shared.exception.DuplicateResourceException;
import com.apac.claims.shared.exception.ValidationFailedException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository users;
    @Mock
    private MarketRepository markets;
    @Mock
    private PasswordEncoder passwordEncoder;

    private UserService service;
    private Market india;

    @BeforeEach
    void setUp() {
        service = new UserService(users, markets, passwordEncoder);
        india = new Market("IN", "India", "INR", new BigDecimal("500000.00"), true);
    }

    private void saveReturnsTheEntity() {
        when(users.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static void assertFieldError(Throwable thrown, String field) {
        assertThat(thrown).isInstanceOfSatisfying(ValidationFailedException.class,
                e -> assertThat(e.getErrors()).extracting(ValidationFailedException.FieldError::field)
                        .containsExactly(field));
    }

    // ---- creating accounts --------------------------------------------------------------------------------

    @Test
    void anAccountIsNormalisedHashedAndSaved() {
        when(markets.findByCode("IN")).thenReturn(Optional.of(india));
        when(passwordEncoder.encode("Claimant@123")).thenReturn("{bcrypt}hashed");
        saveReturnsTheEntity();

        UserResponse response = service.createAccount("  New.Claimant ", "Claimant@123", "  New Claimant ",
                "  New.Claimant@Test.Example ", Role.CLAIMANT, "in");

        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo("new.claimant");
        assertThat(saved.getValue().getEmail()).isEqualTo("new.claimant@test.example");
        assertThat(saved.getValue().getFullName()).isEqualTo("New Claimant");
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("{bcrypt}hashed").isNotEqualTo("Claimant@123");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.CLAIMANT);
        assertThat(saved.getValue().getMarket()).isSameAs(india);
        assertThat(saved.getValue().isActive()).isTrue();
        assertThat(response.userId()).isEqualTo("new.claimant");
        assertThat(response.marketCode()).isEqualTo("IN");
    }

    @Test
    void theResponseNeverContainsThePasswordOrItsHash() {
        when(markets.findByCode("IN")).thenReturn(Optional.of(india));
        when(passwordEncoder.encode("Claimant@123")).thenReturn("hash-value-xyz");
        saveReturnsTheEntity();

        UserResponse response = service.createAccount("new.claimant", "Claimant@123", "New Claimant",
                "n@test.example", Role.CLAIMANT, "IN");

        assertThat(response.toString()).doesNotContain("Claimant@123").doesNotContain("hash-value-xyz");
    }

    @Test
    void aDuplicateUserIdIsRejectedAndNothingIsSaved() {
        when(markets.findByCode("IN")).thenReturn(Optional.of(india));
        when(users.existsByUserId("taken.id")).thenReturn(true);

        assertThatThrownBy(() -> service.createAccount("Taken.ID", "Claimant@123", "Name", "e@test.example",
                Role.CLAIMANT, "IN")).isInstanceOf(DuplicateResourceException.class).hasMessageContaining("User ID");

        verify(users, never()).save(any());
    }

    @Test
    void aDuplicateEmailIsRejectedCaseInsensitively() {
        when(markets.findByCode("IN")).thenReturn(Optional.of(india));
        when(users.existsByEmail("taken@test.example")).thenReturn(true);

        assertThatThrownBy(() -> service.createAccount("fresh.id", "Claimant@123", "Name", "Taken@Test.Example",
                Role.CLAIMANT, "IN")).isInstanceOf(DuplicateResourceException.class).hasMessageContaining("Email");

        verify(users, never()).save(any());
    }

    // ---- market rules -------------------------------------------------------------------------------------

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void anOfficerOrClaimantWithoutAMarketIsRejected(String marketCode) {
        assertThatThrownBy(() -> service.createAccount("officer.new", "Officer@123", "Name", "e@test.example",
                Role.OFFICER, marketCode)).satisfies(e -> assertFieldError(e, "marketCode"));
        assertThatThrownBy(() -> service.createAccount("claimant.new", "Claimant@123", "Name", "e@test.example",
                Role.CLAIMANT, marketCode)).satisfies(e -> assertFieldError(e, "marketCode"));
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void aManagerWithAMarketIsRejected() {
        assertThatThrownBy(() -> service.createAccount("manager.new", "Manager@123", "Name", "e@test.example",
                Role.MANAGER, "IN")).satisfies(e -> assertFieldError(e, "marketCode"));
    }

    @Test
    void aManagerWithoutAMarketIsCreatedWithNoMarket() {
        when(passwordEncoder.encode("Manager@123")).thenReturn("hash");
        saveReturnsTheEntity();

        UserResponse response = service.createAccount("manager.new", "Manager@123", "Name", "e@test.example",
                Role.MANAGER, null);

        assertThat(response.marketCode()).isNull();
        assertThat(response.role()).isEqualTo(Role.MANAGER);
        verifyNoInteractions(markets);
    }

    @Test
    void anUnknownMarketIsRejectedNamingTheField() {
        when(markets.findByCode("ZZ")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createAccount("officer.new", "Officer@123", "Name", "e@test.example",
                Role.OFFICER, "zz")).satisfies(e -> assertFieldError(e, "marketCode"));
    }

    @Test
    void anInactiveMarketIsRejectedLikeAnUnknownOne() {
        india.setActive(false);
        when(markets.findByCode("IN")).thenReturn(Optional.of(india));

        assertThatThrownBy(() -> service.createAccount("officer.new", "Officer@123", "Name", "e@test.example",
                Role.OFFICER, "IN")).satisfies(e -> assertFieldError(e, "marketCode"));
    }

    @Test
    void theMarketCodeIsNormalisedToUpperCaseBeforeTheLookup() {
        when(markets.findByCode("IN")).thenReturn(Optional.of(india));
        when(passwordEncoder.encode(any())).thenReturn("hash");
        saveReturnsTheEntity();

        service.createAccount("officer.new", "Officer@123", "Name", "e@test.example", Role.OFFICER, " in ");

        verify(markets).findByCode("IN");
    }

    // ---- staff creation -----------------------------------------------------------------------------------

    @Test
    void createStaffRejectsAClaimantNamingTheRoleField() {
        CreateUserRequest request = new CreateUserRequest("claimant.new", "Claimant@123", "Name", "e@test.example",
                Role.CLAIMANT, "IN");

        assertThatThrownBy(() -> service.createStaff(request)).satisfies(e -> assertFieldError(e, "role"));

        verifyNoInteractions(users, markets, passwordEncoder);
    }

    @Test
    void createStaffCreatesAnOfficerOfTheGivenMarket() {
        when(markets.findByCode("IN")).thenReturn(Optional.of(india));
        when(passwordEncoder.encode("Officer@123")).thenReturn("hash");
        saveReturnsTheEntity();

        UserResponse response = service.createStaff(new CreateUserRequest("officer.new", "Officer@123", "Officer New",
                "officer.new@test.example", Role.OFFICER, "IN"));

        assertThat(response.role()).isEqualTo(Role.OFFICER);
        assertThat(response.marketCode()).isEqualTo("IN");
    }

    // ---- reading ------------------------------------------------------------------------------------------

    @Test
    void getMeMapsTheGivenAccountWithoutTouchingTheRepository() {
        AppUser user = new AppUser("claimant.in", "hash", "Claimant India", "claimant.in@test.example", Role.CLAIMANT,
                india);
        user.setCreatedAt(Instant.parse("2026-03-01T10:00:00Z"));

        UserResponse response = service.getMe(user);

        assertThat(response).isEqualTo(new UserResponse("claimant.in", "Claimant India", "claimant.in@test.example",
                Role.CLAIMANT, "IN", true, Instant.parse("2026-03-01T10:00:00Z")));
        verifyNoInteractions(users);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listKeepsThePageAndSizeAndAddsTheIdAsATieBreaker() {
        AppUser user = new AppUser("officer.in", "hash", "Officer India", "o@test.example", Role.OFFICER, india);
        when(users.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(user), PageRequest.of(2, 5), 11));
        Pageable requested = PageRequest.of(2, 5, Sort.by(Sort.Direction.DESC, "createdAt"));

        PageResponse<UserResponse> page = service.list(Role.OFFICER, "in", requested);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(users).findAll(any(Specification.class), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(captor.getValue().getPageSize()).isEqualTo(5);
        assertThat(captor.getValue().getSort().stream().map(Sort.Order::getProperty)).containsExactly("createdAt", "id");
        assertThat(page.content()).extracting(UserResponse::userId).containsExactly("officer.in");
        assertThat(page.totalElements()).isEqualTo(11);
        assertThat(page.page()).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listWorksWithoutAnyFilter() {
        when(users.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());

        PageResponse<UserResponse> page = service.list(null, null, PageRequest.of(0, 10));

        assertThat(page.content()).isEmpty();
    }

    // ---- page request for the list ------------------------------------------------------------------------

    @Test
    void thePageRequestDefaultsToTenSortedByUserId() {
        Pageable pageable = UserService.pageRequest(null, null, null);

        assertThat(pageable.getPageSize()).isEqualTo(10);
        assertThat(pageable.getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "userId"));
    }

    @Test
    void thePageRequestCapsTheSizeAtFifty() {
        assertThat(UserService.pageRequest(0, 500, null).getPageSize()).isEqualTo(50);
    }

    @Test
    void thePageRequestAcceptsOnlyUserIdAndCreatedAtForSorting() {
        assertThat(UserService.pageRequest(0, 10, "createdAt,desc").getSort())
                .isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
        assertThatThrownBy(() -> UserService.pageRequest(0, 10, "email")).satisfies(e -> assertFieldError(e, "sort"));
    }
}
