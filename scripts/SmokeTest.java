import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Smoke test for a running instance of the claims platform.
 *
 * <p>Run it with {@code java scripts/SmokeTest.java [baseUrl]} (default {@code http://localhost:8080}); it needs
 * JDK 21 or later and nothing else. It prints PASS or FAIL for every step with the real HTTP status and exits with
 * 1 if any step failed, so it can be used by hand and from a script.
 *
 * <p>To extend it in a later prompt, add a method such as {@code claimsWorkflow()} next to
 * {@link #securityAndIdentity()} and call it from {@link #main}. Use {@link #step}, {@link #stepContains} and
 * {@link #check} for assertions and {@link #login} to get a token; nothing else needs to change.
 */
public class SmokeTest {

    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"");

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static String baseUrl = "http://localhost:8080";
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        if (args.length > 0) {
            baseUrl = args[0].endsWith("/") ? args[0].substring(0, args[0].length() - 1) : args[0];
        }
        System.out.println("Smoke test against " + baseUrl);
        System.out.println();
        try {
            securityAndIdentity();
            claimsJourney();
        } catch (RuntimeException e) {
            fail("the script itself failed unexpectedly: " + e);
        }
        System.out.println();
        System.out.println("Smoke test finished: " + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---- steps of prompt 3: security and identity ---------------------------------------------------------

    private static void securityAndIdentity() {
        section("Health, documentation and redirect");
        stepContains("health is UP", 200, () -> get("/actuator/health", null), "\"status\":\"UP\"");
        step("Swagger UI is reachable", 200, () -> get("/swagger-ui/index.html", null));
        stepContains("OpenAPI document has the bearer scheme and the CLAIMANT role", 200,
                () -> get("/v3/api-docs", null), "bearerAuth", "CLAIMANT");
        HttpResponse<String> root = step("GET / redirects", 302, () -> get("/", null));
        check("GET / redirects to the Swagger UI", root != null
                && root.headers().firstValue("Location").orElse("").endsWith("/swagger-ui/index.html"),
                root == null ? "no response" : "Location: " + root.headers().firstValue("Location").orElse("none"));

        section("Login with each role");
        String claimant = loginAndCheck("claimant.in", "Claimant@123", "CLAIMANT");
        String officer = loginAndCheck("officer.in", "Officer@123", "OFFICER");
        String manager = loginAndCheck("manager.apac", "Manager@123", "MANAGER");

        section("Login failures are all 401");
        step("claimant credentials with the OFFICER role", 401, () -> postLogin("claimant.in", "Claimant@123", "OFFICER"));
        step("officer credentials with the MANAGER role", 401, () -> postLogin("officer.in", "Officer@123", "MANAGER"));
        step("manager credentials with the CLAIMANT role", 401, () -> postLogin("manager.apac", "Manager@123", "CLAIMANT"));
        stepContains("wrong password", 401, () -> postLogin("claimant.in", "Wrong@12345", "CLAIMANT"),
                "Invalid user ID, password or role");
        stepContains("unknown role value is a bad request", 400, () -> postLogin("claimant.in", "Claimant@123", "ADMIN"),
                "CLAIMANT, OFFICER, MANAGER");

        section("Tokens");
        HttpResponse<String> noToken = stepContains("/users/me without a token", 401, () -> get("/api/v1/users/me", null),
                "UNAUTHENTICATED");
        check("the 401 carries a correlation id header",
                noToken != null && noToken.headers().firstValue("X-Correlation-Id").isPresent(),
                "headers: " + (noToken == null ? "no response" : noToken.headers().map().keySet()));
        stepContains("/users/me as the claimant", 200, () -> get("/api/v1/users/me", claimant), "claimant.in");
        stepContains("/users/me as the officer", 200, () -> get("/api/v1/users/me", officer), "officer.in");
        stepContains("/users/me as the manager", 200, () -> get("/api/v1/users/me", manager), "manager.apac");

        section("Authorization and user management");
        String officerId = "smoke.off." + unique();
        step("a claimant cannot create users", 403, () -> post("/api/v1/users", newStaffJson(officerId, "OFFICER", "IN"),
                claimant));
        stepContains("a manager creates an officer", 201, () -> post("/api/v1/users",
                newStaffJson(officerId, "OFFICER", "IN"), manager), officerId, "\"role\":\"OFFICER\"");
        loginAndCheck(officerId, "Officer@123", "OFFICER");

        section("Registration");
        String claimantId = "smoke.clm." + unique();
        stepContains("a new claimant registers", 201, () -> post("/api/v1/auth/register",
                registrationJson(claimantId, "Claimant@123"), null), claimantId, "\"role\":\"CLAIMANT\"");
        loginAndCheck(claimantId, "Claimant@123", "CLAIMANT");
        stepContains("a weak password is rejected", 400, () -> post("/api/v1/auth/register",
                registrationJson("smoke.weak." + unique(), "weak"), null), "errors", "password");

        section("Markets");
        stepContains("markets are listed with their currencies", 200, () -> get("/api/v1/markets", claimant),
                "INR", "JPY");
    }

    // ---- steps of prompt 4: policies, claims, queue, assignment, assessment, decisions ---------------------

    private static final Pattern CLAIM_NUMBER = Pattern.compile("\"claimNumber\"\\s*:\\s*\"([A-Z]{3}-\\d+)\"");
    private static final Pattern INCIDENT_DATE = Pattern.compile("\"incidentDate\"\\s*:\\s*\"(\\d{4}-\\d{2}-\\d{2})\"");
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

    private static void claimsJourney() {
        section("Claims: log in as the seeded claimants, officers and the manager");
        String claimantIn = login("claimant.in", "Claimant@123", "CLAIMANT");
        String claimantSg = login("claimant.sg", "Claimant@123", "CLAIMANT");
        String officerIn = login("officer.in", "Officer@123", "OFFICER");
        String officerSg = login("officer.sg", "Officer@123", "OFFICER");
        String manager = login("manager.apac", "Manager@123", "MANAGER");
        if (claimantIn == null || claimantSg == null || officerIn == null || officerSg == null || manager == null) {
            fail("a seeded account could not log in, so the claims journey is skipped");
            return;
        }

        section("Policies");
        stepContains("claimant.in lists the own policies", 200, () -> get("/api/v1/policies/mine", claimantIn),
                "MOT-IN-0001");

        section("Submission, validation and idempotency");
        String key = "smoke-" + unique();
        AtomicReference<LocalDate> date = new AtomicReference<>();
        HttpResponse<String> submitted = step("claimant.in submits a MOTOR claim of 45000.00", 201,
                () -> submitWithFreshDate(claimantIn, day -> motorJson("MOT-IN-0001", "45000.00", day), key, date));
        String inClaim = claimNumber(submitted, "CLM-");
        LocalDate inDate = date.get();
        if (inClaim == null) {
            fail("the MOTOR claim was not created, so the rest of the journey is skipped");
            return;
        }
        HttpResponse<String> replay = step("the same Idempotency-Key replays the claim", 200,
                () -> postWith("/api/v1/claims", motorJson("MOT-IN-0001", "45000.00", inDate), claimantIn,
                        "Idempotency-Key", key));
        check("  the replay returns the same claim number " + inClaim,
                replay != null && inClaim.equals(claimNumber(replay, "CLM-")), "body: " + body(replay));
        check("  the replay carries Idempotent-Replay: true", replay != null
                && "true".equals(replay.headers().firstValue("Idempotent-Replay").orElse("")),
                "headers: " + (replay == null ? "no response" : replay.headers().map().keySet()));
        AtomicReference<LocalDate> sgDate = new AtomicReference<>();
        HttpResponse<String> sgSubmitted = step("claimant.sg submits a PROPERTY claim of 25000.00", 201,
                () -> submitWithFreshDate(claimantSg, day -> propertyJson("PRP-SG-0001", "25000.00", day), null,
                        sgDate));
        String sgClaim = claimNumber(sgSubmitted, "CLP-");
        stepContains("the lapsed policy MOT-IN-0002 is refused", 422,
                () -> post("/api/v1/claims", motorJson("MOT-IN-0002", "1000.00", daysAgo(400)), claimantIn),
                "POLICY_NOT_ACTIVE");
        stepContains("an amount above the sum insured is refused", 422,
                () -> post("/api/v1/claims", motorJson("MOT-IN-0001", "1500000.01", daysAgo(5)), claimantIn),
                "AMOUNT_EXCEEDS_SUM_INSURED");
        stepContains("a MOTOR claim with a property block is a bad request", 400,
                () -> post("/api/v1/claims", wrongBlockJson(daysAgo(5)), claimantIn), "VALIDATION_FAILED");

        section("Scope: nobody sees the claims of another claimant or market");
        if (sgClaim != null) {
            step("claimant.in reading the SG claim", 404, () -> get("/api/v1/claims/" + sgClaim, claimantIn));
        }
        step("officer.sg reading the IN claim", 404, () -> get("/api/v1/claims/" + inClaim, officerSg));

        section("Queue and pick-up");
        step("claimant.in cannot read the queue", 403, () -> get("/api/v1/claims/queue", claimantIn));
        check("the IN officer's queue contains " + inClaim, queueContains(officerIn, inClaim),
                "the claim was not found in the queue");
        step("officer.sg cannot pick up the IN claim", 404,
                () -> post("/api/v1/claims/" + inClaim + "/pick-up", "", officerSg));
        stepContains("officer.in picks up the claim", 200,
                () -> post("/api/v1/claims/" + inClaim + "/pick-up", "", officerIn), "UNDER_REVIEW", "officer.in");
        stepContains("the manager picking it up again is told it is taken", 409,
                () -> post("/api/v1/claims/" + inClaim + "/pick-up", "", manager), "CLAIM_ALREADY_ASSIGNED");

        section("Assessment and information request");
        // Seeded motor policy: deductible 1% of 500000 = 5000, so a loss of 44000 gives a payout of 39000.
        stepContains("officer.in assesses a loss of 44000.00", 201,
                () -> post("/api/v1/claims/" + inClaim + "/assessments", assessmentJson("44000.00", true), officerIn),
                "\"recommendedPayout\":39000.00", "\"deductibleApplied\":5000.00");
        HttpResponse<String> asked = stepContains("officer.in asks for the repair estimate", 201,
                () -> post("/api/v1/claims/" + inClaim + "/information-requests",
                        "{\"question\": \"Please send the repair estimate from the workshop.\", \"dueInDays\": 7}",
                        officerIn), "\"status\":\"OPEN\"");
        String requestId = asked == null ? null : firstGroup(ID, asked.body());
        if (requestId != null) {
            stepContains("claimant.in answers the request", 200, () -> post("/api/v1/claims/" + inClaim
                    + "/information-requests/" + requestId + "/respond",
                    "{\"responseText\": \"The repair estimate is 42000 INR from Sunrise Auto Works.\"}", claimantIn),
                    "\"status\":\"UNDER_REVIEW\"");
        } else {
            fail("the information request has no id, so it cannot be answered");
        }
        HttpResponse<String> timeline = stepContains("the claimant reads the timeline", 200,
                () -> get("/api/v1/claims/" + inClaim + "/timeline", claimantIn), "INFORMATION_PROVIDED");
        check("  the claimant's timeline has no INTERNAL entry",
                timeline != null && !timeline.body().contains("INTERNAL"), "body: " + body(timeline));
        check("  and no staff user id", timeline != null && !timeline.body().contains("officer.in"),
                "body: " + body(timeline));

        section("Approval limit, escalation and the manager's decision");
        AtomicReference<LocalDate> bigDate = new AtomicReference<>();
        HttpResponse<String> big = step("claimant.in submits a second MOTOR claim of 1200000.00", 201,
                () -> submitWithFreshDate(claimantIn, day -> motorJson("MOT-IN-0001", "1200000.00", day), null,
                        bigDate));
        String bigClaim = claimNumber(big, "CLM-");
        if (bigClaim != null) {
            step("officer.in picks it up", 200, () -> post("/api/v1/claims/" + bigClaim + "/pick-up", "", officerIn));
            stepContains("officer.in assesses a loss of 1200000.00", 201,
                    () -> post("/api/v1/claims/" + bigClaim + "/assessments", assessmentJson("1200000.00", true),
                            officerIn), "\"recommendedPayout\":1195000.00");
            stepContains("officer.in approving above the IN threshold is refused", 422,
                    () -> post("/api/v1/claims/" + bigClaim + "/approve", "", officerIn), "APPROVAL_LIMIT_EXCEEDED");
            stepContains("officer.in escalates the claim", 200, () -> post("/api/v1/claims/" + bigClaim + "/escalate",
                    "{\"remarks\": \"Assessed payout is above my approval limit.\"}", officerIn), "ESCALATED");
            stepContains("officer.in cannot approve the escalated claim", 403,
                    () -> post("/api/v1/claims/" + bigClaim + "/approve", "", officerIn), "escalated");
            stepContains("the manager approves the escalated claim", 200,
                    () -> post("/api/v1/claims/" + bigClaim + "/approve", "", manager), "\"status\":\"APPROVED\"",
                    "1195000.00");
        } else {
            fail("the second claim was not created, so the approval steps are skipped");
        }

        section("Policy maintenance by the manager");
        String policyNumber = "MOT-IN-" + unique().substring(0, 6).toUpperCase();
        stepContains("the manager creates policy " + policyNumber + " for claimant.in", 201,
                () -> put("/api/v1/policies/" + policyNumber, policyJson(), manager), policyNumber);
        step("a claimant cannot create a policy", 403, () -> put("/api/v1/policies/" + policyNumber, policyJson(),
                claimantIn));

        section("Reject, then resubmit the same policy and day");
        if (sgClaim != null && sgDate.get() != null) {
            step("officer.sg picks up the SG claim", 200,
                    () -> post("/api/v1/claims/" + sgClaim + "/pick-up", "", officerSg));
            stepContains("officer.sg rejects it with a reason code", 200, () -> post("/api/v1/claims/" + sgClaim
                    + "/reject", "{\"reasonCode\": \"NOT_COVERED\", \"remarks\": \"Flood damage is not covered "
                    + "by this policy.\"}", officerSg), "\"status\":\"REJECTED\"", "NOT_COVERED");
            stepContains("the same policy and day are accepted again after the rejection", 201,
                    () -> post("/api/v1/claims", propertyJson("PRP-SG-0001", "25000.00", sgDate.get()), claimantSg),
                    "CLP-");
        } else {
            fail("the SG claim was not created, so the rejection steps are skipped");
        }
    }

    /** Submits with a random day of the last 330 and tries another day (up to five times) on DUPLICATE_CLAIM. */
    private static HttpResponse<String> submitWithFreshDate(String token, Function<LocalDate, String> body,
                                                            String idempotencyKey, AtomicReference<LocalDate> used) {
        HttpResponse<String> response = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            LocalDate day = daysAgo(3 + ThreadLocalRandom.current().nextInt(327));
            used.set(day);
            response = idempotencyKey == null ? post("/api/v1/claims", body.apply(day), token)
                    : postWith("/api/v1/claims", body.apply(day), token, "Idempotency-Key", idempotencyKey);
            if (response.statusCode() != 409 || !response.body().contains("DUPLICATE_CLAIM")) {
                return response;
            }
        }
        return response;
    }

    /** The claim lists are paged, so the queue is read page by page until the claim shows up or the pages end. */
    private static boolean queueContains(String token, String claimNumber) {
        for (int page = 0; page < 20; page++) {
            HttpResponse<String> response = get("/api/v1/claims/queue?size=50&page=" + page, token);
            if (response.statusCode() != 200) {
                return false;
            }
            if (response.body().contains("\"" + claimNumber + "\"")) {
                return true;
            }
            if (response.body().contains("\"last\":true")) {
                return false;
            }
        }
        return false;
    }

    private static LocalDate daysAgo(int days) {
        return LocalDate.now(ZoneOffset.UTC).minusDays(days);
    }

    private static String claimNumber(HttpResponse<String> response, String prefix) {
        String number = response == null ? null : firstGroup(CLAIM_NUMBER, response.body());
        if (number == null || !number.startsWith(prefix)) {
            return null;
        }
        boolean wellFormed = Pattern.matches("^" + prefix + "\\d{5,}$", number);
        check("  the claim number " + number + " has the form " + prefix + "nnnnn", wellFormed, "number: " + number);
        return number;
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String body(HttpResponse<String> response) {
        return response == null ? "no response" : shorten(response.body());
    }

    private static String motorJson(String policyNumber, String amount, LocalDate day) {
        return """
                {
                  "claimType": "MOTOR",
                  "policyNumber": "%s",
                  "claimedAmount": %s,
                  "incidentDate": "%s",
                  "incidentLocation": "MG Road, Bengaluru",
                  "description": "Rear-ended at a traffic signal; bumper and boot lid damaged.",
                  "motor": {
                    "vehicleRegistrationNumber": "MH12AB1234",
                    "vehicleMake": "Hyundai",
                    "vehicleModel": "i20",
                    "vehicleYear": 2021,
                    "damageType": "COLLISION",
                    "policeReportNumber": "FIR-2026-5521"
                  }
                }
                """.formatted(policyNumber, amount, day);
    }

    private static String propertyJson(String policyNumber, String amount, LocalDate day) {
        return """
                {
                  "claimType": "PROPERTY",
                  "policyNumber": "%s",
                  "claimedAmount": %s,
                  "incidentDate": "%s",
                  "incidentLocation": "Orchard Road, Singapore",
                  "description": "Heavy rain flooded the ground floor; flooring and furniture damaged.",
                  "property": {
                    "propertyAddress": "12 Orchard View, Singapore 238801",
                    "propertyType": "RESIDENTIAL",
                    "perilType": "FLOOD"
                  }
                }
                """.formatted(policyNumber, amount, day);
    }

    /** A MOTOR claim that carries a property block instead of the motor block. */
    private static String wrongBlockJson(LocalDate day) {
        return """
                {
                  "claimType": "MOTOR",
                  "policyNumber": "MOT-IN-0001",
                  "claimedAmount": 1000.00,
                  "incidentDate": "%s",
                  "incidentLocation": "MG Road, Bengaluru",
                  "description": "Rear-ended at a traffic signal; bumper and boot lid damaged.",
                  "property": {
                    "propertyAddress": "Flat 402, Lakeview Apartments, Powai, Mumbai 400076",
                    "propertyType": "RESIDENTIAL",
                    "perilType": "FLOOD"
                  }
                }
                """.formatted(day);
    }

    private static String assessmentJson(String loss, boolean coverage) {
        return """
                {
                  "assessedLossAmount": %s,
                  "coverageConfirmed": %s,
                  "recommendation": "APPROVE",
                  "notes": "Photos match the repair estimate."
                }
                """.formatted(loss, coverage);
    }

    private static String policyJson() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return """
                {
                  "productType": "MOTOR",
                  "marketCode": "IN",
                  "holderUserId": "claimant.in",
                  "insuredItem": "KA01SMOKE99",
                  "sumInsured": 1000000.00,
                  "deductible": 1000.00,
                  "validFrom": "%s",
                  "validTo": "%s",
                  "status": "ACTIVE"
                }
                """.formatted(today.minusYears(1), today.plusYears(1));
    }

    // ---- harness: reuse these in later prompts ------------------------------------------------------------

    /** Prints a heading so the output reads in groups. */
    static void section(String title) {
        System.out.println("-- " + title);
    }

    /**
     * Runs one HTTP call and checks the status. Prints PASS or FAIL with the real status.
     *
     * @return the response, or null when the call itself failed (for example the connection was refused)
     */
    static HttpResponse<String> step(String name, int expectedStatus, Supplier<HttpResponse<String>> call) {
        HttpResponse<String> response;
        try {
            response = call.get();
        } catch (RuntimeException e) {
            fail(name + " (the request failed: " + e.getMessage() + ")");
            return null;
        }
        if (response.statusCode() == expectedStatus) {
            pass(name + " -> " + response.statusCode());
        } else {
            fail(name + " -> expected " + expectedStatus + " but got " + response.statusCode()
                    + "  body: " + shorten(response.body()));
        }
        return response;
    }

    /** Like {@link #step}, and also requires the body to contain every given text. */
    static HttpResponse<String> stepContains(String name, int expectedStatus, Supplier<HttpResponse<String>> call,
                                             String... expectedTexts) {
        HttpResponse<String> response = step(name, expectedStatus, call);
        if (response != null && response.statusCode() == expectedStatus) {
            for (String text : expectedTexts) {
                check("  body of '" + name + "' contains " + text, response.body().contains(text),
                        "body: " + shorten(response.body()));
            }
        }
        return response;
    }

    /** Records a check that is not an HTTP status, for example a header or a text in a body. */
    static void check(String name, boolean condition, String detailOnFailure) {
        if (condition) {
            pass(name);
        } else {
            fail(name + "  (" + detailOnFailure + ")");
        }
    }

    /** Logs in and returns the access token, or null when the login did not succeed. The step is reported. */
    static String login(String userId, String password, String role) {
        HttpResponse<String> response = step("login as " + userId + " (" + role + ")", 200,
                () -> postLogin(userId, password, role));
        if (response == null || response.statusCode() != 200) {
            return null;
        }
        Matcher matcher = ACCESS_TOKEN.matcher(response.body());
        if (!matcher.find()) {
            fail("login as " + userId + " returned no accessToken");
            return null;
        }
        return matcher.group(1);
    }

    /** Logs in, and also checks that the response names the role that was asked for. */
    private static String loginAndCheck(String userId, String password, String role) {
        HttpResponse<String> response = step("login as " + userId + " (" + role + ")", 200,
                () -> postLogin(userId, password, role));
        if (response == null || response.statusCode() != 200) {
            return null;
        }
        check("  login response names the role " + role, response.body().contains("\"role\":\"" + role + "\""),
                "body: " + shorten(response.body()));
        Matcher matcher = ACCESS_TOKEN.matcher(response.body());
        if (!matcher.find()) {
            fail("login as " + userId + " returned no accessToken");
            return null;
        }
        return matcher.group(1);
    }

    static HttpResponse<String> get(String path, String token) {
        return send(request(path, token).GET().build());
    }

    static HttpResponse<String> post(String path, String jsonBody, String token) {
        return send(request(path, token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build());
    }

    /** A POST that also sends one extra header, for example the Idempotency-Key. */
    static HttpResponse<String> postWith(String path, String jsonBody, String token, String header, String value) {
        return send(request(path, token).header("Content-Type", "application/json").header(header, value)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build());
    }

    static HttpResponse<String> put(String path, String jsonBody, String token) {
        return send(request(path, token).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(jsonBody)).build());
    }

    static HttpResponse<String> postLogin(String userId, String password, String role) {
        String body = """
                {"userId": "%s", "password": "%s"}
                """.formatted(userId, password);
        return post("/api/v1/auth/login?role=" + role, body, null);
    }

    private static HttpRequest.Builder request(String path, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(20));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private static HttpResponse<String> send(HttpRequest request) {
        try {
            return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e.getClass().getSimpleName() + " calling " + request.method() + " "
                    + request.uri() + (e.getMessage() == null ? "" : ": " + e.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private static String newStaffJson(String userId, String role, String marketCode) {
        return """
                {
                  "userId": "%s",
                  "password": "Officer@123",
                  "fullName": "Smoke Test Officer",
                  "email": "%s@smoke.example",
                  "role": "%s",
                  "marketCode": "%s"
                }
                """.formatted(userId, userId, role, marketCode);
    }

    private static String registrationJson(String userId, String password) {
        return """
                {
                  "userId": "%s",
                  "password": "%s",
                  "fullName": "Smoke Test Claimant",
                  "email": "%s@smoke.example",
                  "marketCode": "IN"
                }
                """.formatted(userId, password, userId);
    }

    /** Ten random characters, so repeated runs never collide on user ids or emails. */
    static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static void pass(String message) {
        passed++;
        System.out.println("PASS  " + message);
    }

    private static void fail(String message) {
        failed++;
        System.out.println("FAIL  " + message);
    }

    private static String shorten(String text) {
        String single = text == null ? "" : text.replaceAll("\\s+", " ");
        return single.length() > 160 ? single.substring(0, 160) + "..." : single;
    }
}
