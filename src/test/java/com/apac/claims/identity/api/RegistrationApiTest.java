package com.apac.claims.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;

import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.support.AbstractApiTest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class RegistrationApiTest extends AbstractApiTest {

    private static final String GOOD_PASSWORD = "Claimant@123";

    private Map<String, Object> registrationBody(String userId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("password", GOOD_PASSWORD);
        body.put("fullName", "Test Claimant");
        body.put("email", userId + "@test.example");
        body.put("marketCode", "IN");
        return body;
    }

    private ResultActions register(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    @Test
    void registrationCreatesAClaimantAndReturnsItWithoutAnyPasswordData() throws Exception {
        String userId = uniqueUserId("reg");

        register(registrationBody(userId))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.role").value("CLAIMANT"))
                .andExpect(jsonPath("$.marketCode").value("IN"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(content().string(not(containsString(GOOD_PASSWORD))));

        AppUser stored = users.findByUserId(userId).orElseThrow();
        assertThat(stored.getRole()).isEqualTo(Role.CLAIMANT);
        assertThat(stored.getPasswordHash()).startsWith("$2").isNotEqualTo(GOOD_PASSWORD);
    }

    @Test
    void aRoleInTheBodyIsIgnoredSoRegistrationCannotCreateStaff() throws Exception {
        String userId = uniqueUserId("esc");
        Map<String, Object> body = registrationBody(userId);
        body.put("role", "MANAGER");

        register(body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("CLAIMANT"));

        assertThat(users.findByUserId(userId).orElseThrow().getRole()).isEqualTo(Role.CLAIMANT);
        // And the new account cannot log in as a manager either.
        assertThat(attemptLogin(userId, GOOD_PASSWORD, Role.MANAGER).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void aRegisteredClaimantCanLogInAtOnce() throws Exception {
        String userId = uniqueUserId("log");
        register(registrationBody(userId)).andExpect(status().isCreated());

        String token = login(userId, GOOD_PASSWORD, Role.CLAIMANT);

        assertThat(token).isNotBlank();
    }

    @Test
    void theUserIdAndEmailAreStoredInLowerCaseAndTheMarketCodeInUpperCase() throws Exception {
        String lower = uniqueUserId("case");
        Map<String, Object> body = registrationBody(lower.toUpperCase());
        body.put("email", lower.toUpperCase() + "@Test.Example");
        body.put("marketCode", "sg");

        register(body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(lower))
                .andExpect(jsonPath("$.email").value(lower + "@test.example"))
                .andExpect(jsonPath("$.marketCode").value("SG"));
    }

    @Test
    void aDuplicateUserIdIsAConflictEvenWithDifferentCase() throws Exception {
        String userId = uniqueUserId("dup");
        register(registrationBody(userId)).andExpect(status().isCreated());

        Map<String, Object> again = registrationBody(userId.toUpperCase());
        again.put("email", "another-" + userId + "@test.example");
        register(again)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void aDuplicateEmailIsAConflictEvenWithDifferentCase() throws Exception {
        String userId = uniqueUserId("mail");
        register(registrationBody(userId)).andExpect(status().isCreated());

        Map<String, Object> other = registrationBody(uniqueUserId("mail"));
        other.put("email", userId.toUpperCase() + "@TEST.EXAMPLE");
        register(other)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"short1", "nodigitshere", "123456789012", "Abcdefg1Abcdefg1Abcdefg1Abcdefg1Abcdefg1"
            + "Abcdefg1Abcdefg1Abcdefg1Abcdefg1Abcdefg1Abcdefg1"})
    void aWeakPasswordIsABadRequestNamingThePasswordField(String password) throws Exception {
        Map<String, Object> body = registrationBody(uniqueUserId("weak"));
        body.put("password", password);

        register(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'password')]").exists());
    }

    @Test
    void aRejectedPasswordIsNeverEchoedBackInTheResponse() throws Exception {
        Map<String, Object> body = registrationBody(uniqueUserId("echo"));
        body.put("password", "weakpassword-without-digits");

        register(body)
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("weakpassword-without-digits"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-email", "missing-at.example.com", "two@@test.example", ""})
    void anInvalidEmailIsABadRequestNamingTheEmailField(String email) throws Exception {
        Map<String, Object> body = registrationBody(uniqueUserId("mail"));
        body.put("email", email);

        register(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'email')]").exists());
    }

    @Test
    void anUnknownMarketIsABadRequestNamingTheMarketCodeField() throws Exception {
        Map<String, Object> body = registrationBody(uniqueUserId("mkt"));
        body.put("marketCode", "ZZ");

        register(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("marketCode"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"INDIA", "I", "1N", ""})
    void aMarketCodeThatIsNotTwoLettersIsABadRequest(String marketCode) throws Exception {
        Map<String, Object> body = registrationBody(uniqueUserId("mkt"));
        body.put("marketCode", marketCode);

        register(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'marketCode')]").exists());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "bad user!", "way.too.long.user.id.that.exceeds.thirty", "semi;colon"})
    void aUserIdWithTheWrongShapeIsABadRequest(String userId) throws Exception {
        register(registrationBody(userId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'userId')]").exists());
    }

    @Test
    void aFullNameThatIsTooShortIsABadRequest() throws Exception {
        Map<String, Object> body = registrationBody(uniqueUserId("name"));
        body.put("fullName", "A");

        register(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'fullName')]").exists());
    }

    @Test
    void everyProblemIsReportedTogetherAndSortedByField() throws Exception {
        register(Map.of("userId", "a", "password", "x", "fullName", "B", "email", "nope", "marketCode", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(greaterThanOrEqualTo(5)))
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.errors[1].field").value("fullName"));
    }

    @Test
    void registrationIsOpenToAnonymousCallers() throws Exception {
        // No Authorization header at all: this would be 401 if the path were not public.
        register(registrationBody(uniqueUserId("anon"))).andExpect(status().isCreated());
    }

    @Test
    void aNewlyRegisteredAccountBelongsToTheMarketItChose() throws Exception {
        String userId = uniqueUserId("jp");
        Map<String, Object> body = registrationBody(userId);
        body.put("marketCode", "JP");
        register(body).andExpect(status().isCreated()).andExpect(jsonPath("$.marketCode").value("JP"));

        String token = login(userId, GOOD_PASSWORD, Role.CLAIMANT);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/users/me")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketCode").value("JP"));
    }
}
