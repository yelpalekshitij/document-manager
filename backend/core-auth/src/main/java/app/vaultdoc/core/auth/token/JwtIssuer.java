package app.vaultdoc.core.auth.token;

import java.util.Optional;
import java.util.UUID;

public interface JwtIssuer {

    String issueAccessToken(UUID userId, String email);

    int accessTokenTtlSeconds();

    /**
     * Decode and validate a token. Returns empty if the token is missing,
     * malformed, expired, or signed with the wrong key.
     */
    Optional<AccessTokenClaims> verify(String token);
}
