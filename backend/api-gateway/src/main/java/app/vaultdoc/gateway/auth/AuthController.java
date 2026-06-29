package app.vaultdoc.gateway.auth;

import java.util.UUID;

import app.vaultdoc.core.auth.domain.User;
import app.vaultdoc.core.auth.service.AuthService;
import app.vaultdoc.core.auth.service.AuthenticatedSession;
import app.vaultdoc.core.auth.service.InvalidCredentialsException;
import app.vaultdoc.core.auth.service.LoginCommand;
import app.vaultdoc.core.auth.service.RegisterCommand;
import app.vaultdoc.core.auth.service.RegistrationResult;
import app.vaultdoc.core.auth.twofa.TwoFactorRequiredException;
import app.vaultdoc.gateway.auth.dto.AuthResponse;
import app.vaultdoc.gateway.auth.dto.LoginRequest;
import app.vaultdoc.gateway.auth.dto.TwoFactorChallengeResponse;
import app.vaultdoc.gateway.auth.dto.MeResponse;
import app.vaultdoc.gateway.auth.dto.RefreshRequest;
import app.vaultdoc.gateway.auth.dto.RegisterRequest;
import app.vaultdoc.gateway.auth.dto.RegistrationResponse;
import app.vaultdoc.gateway.auth.dto.ResendVerificationRequest;
import app.vaultdoc.gateway.auth.dto.VerifyEmailRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    public ResponseEntity<RegistrationResponse> register(@Valid @RequestBody RegisterRequest req) {
        RegistrationResult result = auth.register(new RegisterCommand(
                req.email(), req.password().toCharArray(), req.displayName()));
        return ResponseEntity.status(HttpStatus.CREATED).body(RegistrationResponse.from(result));
    }

    @PostMapping("/login")
    public ResponseEntity<Object> login(
            @Valid @RequestBody LoginRequest req,
            HttpServletRequest http) {
        try {
            AuthenticatedSession session = auth.login(new LoginCommand(
                    req.email(),
                    req.password().toCharArray(),
                    http.getHeader("User-Agent"),
                    http.getRemoteAddr()));
            return ResponseEntity.ok(AuthResponse.from(session));
        } catch (TwoFactorRequiredException tfa) {
            return ResponseEntity.ok(new TwoFactorChallengeResponse(tfa.getFirstFactorToken()));
        }
    }

    @PostMapping("/verify-email")
    public ResponseEntity<AuthResponse> verifyEmail(
            @Valid @RequestBody VerifyEmailRequest req,
            HttpServletRequest http) {
        AuthenticatedSession session = auth.verifyEmail(
                req.token(), http.getHeader("User-Agent"), http.getRemoteAddr());
        return ResponseEntity.ok(AuthResponse.from(session));
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(@Valid @RequestBody ResendVerificationRequest req) {
        auth.resendVerification(req.email());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            @Valid @RequestBody RefreshRequest req,
            HttpServletRequest http) {
        AuthenticatedSession session = auth.refresh(
                req.refreshToken(), http.getHeader("User-Agent"), http.getRemoteAddr());
        return ResponseEntity.ok(AuthResponse.from(session));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) RefreshRequest req) {
        if (req != null && req.refreshToken() != null) {
            auth.logout(req.refreshToken());
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(@AuthenticationPrincipal String userId) {
        if (userId == null) {
            throw new InvalidCredentialsException();
        }
        User user = auth.findById(UUID.fromString(userId))
                .orElseThrow(InvalidCredentialsException::new);
        return ResponseEntity.ok(MeResponse.from(user));
    }
}
