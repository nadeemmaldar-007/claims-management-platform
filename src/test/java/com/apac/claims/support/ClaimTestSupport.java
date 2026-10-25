package com.apac.claims.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.identity.domain.UserRepository;
import com.apac.claims.market.domain.MarketRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;

import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Drives the claims API the way a client does, so the claim tests can start from any status without repeating the
 * set-up. Every step goes through the real endpoints and checks its own status code, so a broken set-up fails where it
 * happens and not three assertions later.
 *
 * <p>All tests share one database. A test therefore works with claimants and officers it creates itself ({@link
 * #newHolder}, {@link #newAccount}) and with incident dates that no other claim of the same policy uses, and it
 * asserts only about the claim numbers it created. Seeded policies cover the year around the seeding date only, so
 * {@link #recentDate()} stays inside it; the policies of a new holder start six years back, so {@link #freshDate()} may
 * go back as far as the five-year limit allows.
 */
public class ClaimTestSupport {

    public static final String LOCATION = "MG Road, Bengaluru";
    public static final String DESCRIPTION = "Rear-ended at a traffic signal; bumper and boot lid damaged.";
    public static final String NEW_ACCOUNT_PASSWORD = "Claims@12345";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    /** A logged-in account: the token comes from the real login endpoint. */
    public record Account(String userId, String password, Role role, String marketCode, String token) {
    }

    /** A claimant together with a motor and a property policy that belong to them alone. */
    public record Holder(Account account, String motorPolicy, String propertyPolicy) {
    }

    private final MockMvc mvc;
    private final ObjectMapper objectMapper;
    private final UserRepository users;
    private final MarketRepository markets;
    private final PasswordEncoder passwordEncoder;
    private Account manager;

    public ClaimTestSupport(MockMvc mvc, ObjectMapper objectMapper, UserRepository users, MarketRepository markets,
                            PasswordEncoder passwordEncoder) {
        this.mvc = mvc;
        this.objectMapper = objectMapper;
        this.users = users;
        this.markets = markets;
        this.passwordEncoder = passwordEncoder;
    }

    // ---- dates -------------------------------------------------------------------------------------------------

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    /** A day inside the last 1400 that no earlier call of this JVM returned, for the policies of a new holder. */
    public static LocalDate freshDate() {
        return today().minusDays(3 + SEQUENCE.getAndIncrement() % 1400);
    }

    /** A day inside the last 330, which a seeded policy (valid one year back) still covers. */
    public static LocalDate recentDate() {
        return today().minusDays(3 + SEQUENCE.getAndIncrement() % 330);
    }

    // ---- accounts ----------------------------------------------------------------------------------------------

    public Account login(String userId, String password, Role role) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .param("role", role.name())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", userId, "password", password))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("login of %s as %s", userId, role).isEqualTo(200);
        String token = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
        return new Account(userId, password, role, null, token);
    }

    /** One of the seeded accounts, for example {@code seeded(Role.OFFICER, "SG")} is {@code officer.sg}. */
    public Account seeded(Role role, String marketCode) throws Exception {
        String suffix = role == Role.MANAGER ? "apac" : marketCode.toLowerCase();
        String password = switch (role) {
            case CLAIMANT -> "Claimant@123";
            case OFFICER -> "Officer@123";
            case MANAGER -> "Manager@123";
        };
        Account account = login(role.name().toLowerCase() + "." + suffix, password, role);
        return new Account(account.userId(), password, role, role == Role.MANAGER ? null : marketCode,
                account.token());
    }

    /** The seeded manager, logged in once per test. */
    public Account manager() throws Exception {
        if (manager == null) {
            manager = seeded(Role.MANAGER, null);
        }
        return manager;
    }

    /** Creates an account with a unique id and logs it in. An officer or a claimant needs a market. */
    public Account newAccount(Role role, String marketCode) throws Exception {
        AppUser user = createUser(role, marketCode, true);
        Account account = login(user.getUserId(), NEW_ACCOUNT_PASSWORD, role);
        return new Account(account.userId(), NEW_ACCOUNT_PASSWORD, role, marketCode, account.token());
    }

    /** Inserts an account directly and returns the entity, for set-ups the API deliberately cannot do. */
    public AppUser createUser(Role role, String marketCode, boolean active) {
        String userId = AbstractApiTest.uniqueUserId("t" + role.name().substring(0, 3).toLowerCase());
        AppUser user = new AppUser(userId, passwordEncoder.encode(NEW_ACCOUNT_PASSWORD), "Test " + role,
                userId + "@test.example", role, marketCode == null ? null : markets.findByCode(marketCode).orElseThrow());
        user.setActive(active);
        return users.saveAndFlush(user);
    }

    public Holder newHolder(String marketCode) throws Exception {
        boolean yen = "JP".equals(marketCode);
        boolean small = "SG".equals(marketCode) || "AU".equals(marketCode);
        return newHolder(marketCode, new BigDecimal(small ? "100000" : yen ? "3000000" : "1000000"),
                new BigDecimal(small ? "100" : yen ? "10000" : "1000"));
    }

    /** A claimant of the market with a motor and a property policy, both valid from six years back to two ahead. */
    public Holder newHolder(String marketCode, BigDecimal sumInsured, BigDecimal deductible) throws Exception {
        Account claimant = newAccount(Role.CLAIMANT, marketCode);
        String motor = "MOT-" + TestData.unique().toUpperCase();
        String property = "PRP-" + TestData.unique().toUpperCase();
        putPolicy(motor, "MOTOR", marketCode, claimant.userId(), "KA01AB1234", sumInsured, deductible, "ACTIVE");
        putPolicy(property, "PROPERTY", marketCode, claimant.userId(), "Flat 402, Lakeview, Powai", sumInsured,
                deductible, "ACTIVE");
        return new Holder(claimant, motor, property);
    }

    public void putPolicy(String policyNumber, String productType, String marketCode, String holderUserId,
                          String insuredItem, BigDecimal sumInsured, BigDecimal deductible, String status)
            throws Exception {
        MvcResult result = put(manager(), "/api/v1/policies/" + policyNumber, policyBody(productType, marketCode,
                holderUserId, insuredItem, sumInsured, deductible, today().minusYears(6), today().plusYears(2),
                status));
        assertThat(result.getResponse().getStatus()).as("create policy %s: %s", policyNumber,
                result.getResponse().getContentAsString()).isEqualTo(201);
    }

    public Map<String, Object> policyBody(String productType, String marketCode, String holderUserId,
                                          String insuredItem, BigDecimal sumInsured, BigDecimal deductible,
                                          LocalDate validFrom, LocalDate validTo, String status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productType", productType);
        body.put("marketCode", marketCode);
        body.put("holderUserId", holderUserId);
        body.put("insuredItem", insuredItem);
        body.put("sumInsured", sumInsured);
        body.put("deductible", deductible);
        body.put("validFrom", validFrom.toString());
        body.put("validTo", validTo.toString());
        body.put("status", status);
        return body;
    }

    // ---- request bodies ----------------------------------------------------------------------------------------

    public Map<String, Object> motorBody(String policyNumber, Object claimedAmount, LocalDate incidentDate) {
        Map<String, Object> motor = new LinkedHashMap<>();
        motor.put("vehicleRegistrationNumber", "MH12AB1234");
        motor.put("vehicleMake", "Hyundai");
        motor.put("vehicleModel", "i20");
        motor.put("vehicleYear", 2021);
        motor.put("damageType", "COLLISION");
        motor.put("policeReportNumber", "FIR-2026-5521");
        Map<String, Object> body = commonBody("MOTOR", policyNumber, claimedAmount, incidentDate);
        body.put("motor", motor);
        return body;
    }

    public Map<String, Object> propertyBody(String policyNumber, Object claimedAmount, LocalDate incidentDate) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("propertyAddress", "Flat 402, Lakeview Apartments, Powai, Mumbai 400076");
        property.put("propertyType", "RESIDENTIAL");
        property.put("perilType", "FLOOD");
        Map<String, Object> body = commonBody("PROPERTY", policyNumber, claimedAmount, incidentDate);
        body.put("property", property);
        return body;
    }

    private Map<String, Object> commonBody(String claimType, String policyNumber, Object claimedAmount,
                                           LocalDate incidentDate) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("claimType", claimType);
        body.put("policyNumber", policyNumber);
        body.put("claimedAmount", claimedAmount);
        body.put("incidentDate", incidentDate.toString());
        body.put("incidentLocation", LOCATION);
        body.put("description", DESCRIPTION);
        return body;
    }

    // ---- HTTP --------------------------------------------------------------------------------------------------

    public MvcResult get(Account actor, String path) throws Exception {
        return perform(MockMvcRequestBuilders.get(path), actor, null);
    }

    public MvcResult post(Account actor, String path, Object body) throws Exception {
        return perform(MockMvcRequestBuilders.post(path), actor, body);
    }

    public MvcResult put(Account actor, String path, Object body) throws Exception {
        return perform(MockMvcRequestBuilders.put(path), actor, body);
    }

    /** Submits with an optional idempotency key and returns the raw result, whatever the status. */
    public MvcResult submit(Account claimant, Map<String, Object> body, String idempotencyKey) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post("/api/v1/claims");
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return perform(request, claimant, body);
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, Account actor, Object body) throws Exception {
        if (actor != null) {
            request.header("Authorization", "Bearer " + actor.token());
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mvc.perform(request).andReturn();
    }

    public static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    public static <T> T read(MvcResult result, String jsonPath) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
    }

    /** The machine-readable {@code code} of an error response. */
    public static String code(MvcResult result) throws Exception {
        return read(result, "$.code");
    }

    public static void expect(MvcResult result, int expectedStatus) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as("status of %s %s: %s", result.getRequest().getMethod(), result.getRequest().getRequestURI(),
                        result.getResponse().getContentAsString())
                .isEqualTo(expectedStatus);
    }

    // ---- claims, taken to a status through the API --------------------------------------------------------------

    public String submitMotor(Holder holder, Object amount) throws Exception {
        return submitted(holder.account(), motorBody(holder.motorPolicy(), amount, freshDate()));
    }

    public String submitMotor(Holder holder) throws Exception {
        return submitMotor(holder, new BigDecimal("45000.00"));
    }

    public String submitProperty(Holder holder) throws Exception {
        return submitted(holder.account(), propertyBody(holder.propertyPolicy(), new BigDecimal("250000.00"),
                freshDate()));
    }

    private String submitted(Account claimant, Map<String, Object> body) throws Exception {
        MvcResult result = submit(claimant, body, null);
        expect(result, 201);
        return read(result, "$.claimNumber");
    }

    public String underReview(Holder holder, Account officer) throws Exception {
        String claimNumber = submitMotor(holder);
        expect(post(officer, "/api/v1/claims/" + claimNumber + "/pick-up", null), 200);
        return claimNumber;
    }

    /** An UNDER_REVIEW claim whose latest assessment confirms coverage: loss 44000 gives a payout of 43000. */
    public String assessed(Holder holder, Account officer) throws Exception {
        String claimNumber = underReview(holder, officer);
        assess(claimNumber, officer, new BigDecimal("44000.00"), true, "APPROVE");
        return claimNumber;
    }

    public MvcResult assess(String claimNumber, Account actor, Object loss, boolean coverage, String recommendation)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assessedLossAmount", loss);
        body.put("coverageConfirmed", coverage);
        body.put("recommendation", recommendation);
        body.put("notes", "Internal notes for staff only.");
        MvcResult result = post(actor, "/api/v1/claims/" + claimNumber + "/assessments", body);
        expect(result, 201);
        return result;
    }

    /** Asks the claimant a question and returns the id of the new request. */
    public long requestInformation(String claimNumber, Account staff) throws Exception {
        Map<String, Object> body = Map.of("question", "Please send the repair estimate from the workshop.");
        MvcResult result = post(staff, "/api/v1/claims/" + claimNumber + "/information-requests", body);
        expect(result, 201);
        Number id = read(result, "$.id");
        return id.longValue();
    }

    public String pendingInformation(Holder holder, Account officer) throws Exception {
        String claimNumber = underReview(holder, officer);
        requestInformation(claimNumber, officer);
        return claimNumber;
    }

    public void respond(String claimNumber, long requestId, Account claimant) throws Exception {
        MvcResult result = post(claimant, "/api/v1/claims/" + claimNumber + "/information-requests/" + requestId
                + "/respond", Map.of("responseText", "The repair estimate is 42000 INR from Sunrise Auto Works."));
        expect(result, 200);
    }

    public String escalated(Holder holder, Account officer) throws Exception {
        String claimNumber = assessed(holder, officer);
        expect(post(officer, "/api/v1/claims/" + claimNumber + "/escalate",
                Map.of("remarks", "Assessed payout is above my approval limit.")), 200);
        return claimNumber;
    }

    public String approved(Holder holder, Account officer) throws Exception {
        String claimNumber = assessed(holder, officer);
        expect(post(officer, "/api/v1/claims/" + claimNumber + "/approve", null), 200);
        return claimNumber;
    }

    public String rejected(Holder holder, Account officer) throws Exception {
        String claimNumber = underReview(holder, officer);
        expect(post(officer, "/api/v1/claims/" + claimNumber + "/reject", rejectionBody()), 200);
        return claimNumber;
    }

    public static Map<String, Object> rejectionBody() {
        return Map.of("reasonCode", "NOT_COVERED", "remarks", "Flood damage is not covered by this motor policy.");
    }

    public static String path(String claimNumber, String suffix) {
        return "/api/v1/claims/" + claimNumber + suffix;
    }

    public String statusOf(String claimNumber, Account viewer) throws Exception {
        MvcResult result = get(viewer, path(claimNumber, ""));
        expect(result, 200);
        return read(result, "$.status");
    }
}
