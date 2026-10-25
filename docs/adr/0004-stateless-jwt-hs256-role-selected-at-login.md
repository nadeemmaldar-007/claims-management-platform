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
- The `role` claim is not re-read from the database, so a role change for an active user applies at the next login (see the implementation notes).

## Implementation notes (prompt 3)

The decision was implemented as written. These details became concrete or changed:

- **The per-request account check is in the security chain, not only in a service.** The "Good" consequence above promised that a deactivated account stops working at once. Implementing it only in `CurrentUserService` would have covered just the endpoints that load the user (for example `/users/me`) and left an old token valid on `/markets`. Instead `security` declares a small port, `ActiveAccountChecker`, which `identity` implements with one existence query, and the JWT converter calls it after the signature check. `security` still imports nothing from `identity`. A mutation check (disabling the call) made three tests fail: the token of a deactivated user was accepted with a 200 on `/markets`, and for a deleted user and for an unknown user the 401 came from the service layer without the `WWW-Authenticate` header instead of from the filter chain.
- **Token validation.** The decoder checks the signature (HS256 only, so a token with HS512 or `none` in its header is rejected even with the right key), the issuer and the timestamps. Spring's default 60-second tolerance for clock drift was kept; time is read from the `Clock` bean, so tests control it. The issuer is plain text (`claims-management-platform`), not a URL, so code reads the `iss` claim as a string.
- **Login.** Every credential failure gives the same exception and message and is logged at WARN with the user id (cleaned of any characters outside the allowed set) and the requested role, never the password. A failure of the user lookup itself, such as the database being down, is deliberately a 500 and not a 401, so an outage is not shown as "invalid credentials".
- **Role changes are not live.** The `role` claim is trusted until the token expires, so a role change for an active user applies at the next login. Only deletion and deactivation take effect immediately. This is added to the "Bad" consequences.
- **Not built:** login throttling and account lockout, refresh tokens, a token revocation list and a `jti` claim.
