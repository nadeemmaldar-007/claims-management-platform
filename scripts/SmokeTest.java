import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
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
