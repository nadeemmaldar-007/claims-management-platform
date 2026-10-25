/**
 * Security: JWT issuing and decoding, the security filter chain and the JSON 401 and 403 handlers.
 * Takes plain values (user id, role name, market code) so that it never depends on a feature module.
 *
 * <p>May depend on: {@code shared}, {@code config}. Depends on no feature module.
 */
package com.apac.claims.security;
