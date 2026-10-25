package com.apac.claims.config;

import java.util.List;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI document and Swagger UI settings: title, how to log in, the tags per area, and one global bearer
 * token requirement so that protected operations show the lock. Public operations remove it with an empty
 * {@code @SecurityRequirements}. A controller's {@code @Tag} must use exactly the description given here for its
 * tag, otherwise the document lists the tag twice.
 */
@Configuration
public class OpenApiConfig {

    static final String BEARER_AUTH = "bearerAuth";

    private static final String DESCRIPTION = """
            Backend of the claims platform for motor and property claims in six markets (IN, SG, AU, JP, MY, TH).

            **How to log in**
            1. Open **Authentication, POST /api/v1/auth/login**.
            2. Choose your role in the **role** dropdown (CLAIMANT, OFFICER or MANAGER).
            3. Enter `userId` and `password` in the body and press **Execute**.
            4. Copy the `accessToken` from the response, click **Authorize** at the top and paste the token only.

            **Demo accounts (user ids)**: manager `manager.apac`; officers `officer.in`, `officer.sg`, `officer.au`, \
            `officer.jp`, `officer.my`, `officer.th`; claimants `claimant.in`, `claimant.sg`, `claimant.au`, \
            `claimant.jp`, `claimant.my`, `claimant.th`. The password of each account is documented in the \
            README; the login example shows the one for `claimant.in`.

            **Errors** are returned as `application/problem+json` with a `code`, a `timestamp` and the \
            `correlationId` of the request, which is also sent in the `X-Correlation-Id` response header.
            """;

    @Bean
    public OpenAPI claimsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Claims Management Platform API")
                        .version("1.0.0")
                        .description(DESCRIPTION))
                .tags(List.of(
                        new Tag().name("Authentication").description("Log in and register. Public."),
                        new Tag().name("Users").description("Own profile and user management."),
                        new Tag().name("Markets").description("The six markets, their currencies and thresholds."),
                        new Tag().name("Policies").description(
                                "Policy reference data: own policies for claimants, maintenance for managers."),
                        new Tag().name("Claims").description("First notice of loss, workflow and decisions."),
                        new Tag().name("Notifications").description("In-app notifications."),
                        new Tag().name("Reports").description("Workload, performance, aging and exposure.")))
                .components(new Components().addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Paste the accessToken returned by the login operation. Do not add the word "
                                + "Bearer.")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }
}
