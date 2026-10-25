# ADR 0004: Stateless JWT (HS256) with the role selected at login

- **Status:** Accepted
- **Date:** 2026-10-25

## Context

The API is consumed by a separate frontend and by tools such as Swagger UI, so it needs a token-based scheme. The login screen asks for a user ID, a password and a role chosen from a list. Swagger UI shows an enum query parameter as a real dropdown, which makes the demo easy. There is one deployable, so there is no need to share verification keys with other services.

## Decision

- `POST /api/v1/auth/login?role=CLAIMANT|OFFICER|MANAGER` with body `{ "userId", "password" }`. `role` is a **required enum query parameter**. The service authenticates the user and then checks that the account's role equals the chosen role. **Every failure** (unknown user, wrong password, inactive account, wrong role) returns the same `401` with the message "Invalid user ID, password or role".
- The response carries a **JWT signed with HS256**, using the Spring Security resource-server support (Nimbus): `NimbusJwtEncoder` with an `ImmutableSecret`, `NimbusJwtDecoder.withSecretKey(...).macAlgorithm(MacAlgorithm.HS256)` (the default would be RS256), and an explicit HS256 header on every encode. Claims: `iss`, `sub` (user id), `iat`, `exp`, `role`, and `market` only when it is not null.
- `role` becomes the authority `ROLE_<role>` through a `JwtAuthenticationConverter`.
- The secret must be at least 32 bytes; the application **fails at startup** with a clear message otherwise.
- Sessions are stateless and CSRF is disabled (token API). On every request the user is loaded from the database by `sub`; a missing or inactive user gets 401.
- Passwords are stored as BCrypt hashes.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Server-side sessions | Needs session storage and CSRF handling; harder to use from Swagger UI and API clients |
| Opaque tokens with a token store | An extra table and a lookup on every request, with no benefit in a single application |
| RS256 with a key pair | Better when other services verify tokens; here it adds key management for no gain. It can replace HS256 later without changing the API |
| An external identity provider (for example Keycloak) | Another system to run and no Docker on the development machine; out of scope |
| The `jjwt` library | Unnecessary: Nimbus is already part of the Spring Security resource-server dependency |

## Consequences

**Good**
- No server-side session state; any instance can verify a token.
- The role dropdown works naturally in Swagger UI.
- The same 401 for every login failure avoids revealing which accounts exist.
- Loading the user on every request means a deactivated account stops working immediately, even with a valid token.

**Bad**
- A token cannot be revoked before it expires (default 120 minutes); the per-request user check is the only mitigation.
- The shared secret must be protected and rotated carefully; rotating it invalidates all tokens.
- Choosing a role at login is a convenience, not an extra security factor: the role stored on the account is authoritative, and one account has exactly one role.
- One database read per request for the user lookup.
