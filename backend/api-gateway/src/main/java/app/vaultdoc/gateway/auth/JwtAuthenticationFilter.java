package app.vaultdoc.gateway.auth;

import java.io.IOException;
import java.util.List;

import app.vaultdoc.core.auth.token.AccessTokenClaims;
import app.vaultdoc.core.auth.token.JwtIssuer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads the access token from the {@code Authorization: Bearer …} header
 * (sufficient for Phase 1.1a — cookie-based auth lands when the web client
 * needs CSRF-safe state-changing requests beyond /auth/*).
 *
 * <p>Sets the authenticated principal to the userId so downstream controllers
 * can read {@code Authentication.getName()} for owner-scoping.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final JwtIssuer jwt;

    public JwtAuthenticationFilter(JwtIssuer jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER)) {
            String token = header.substring(BEARER.length()).trim();
            jwt.verify(token).ifPresent(claims -> setAuthentication(claims, request));
        }
        chain.doFilter(request, response);
    }

    private void setAuthentication(AccessTokenClaims claims, HttpServletRequest request) {
        var auth = new UsernamePasswordAuthenticationToken(
                claims.userId().toString(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
