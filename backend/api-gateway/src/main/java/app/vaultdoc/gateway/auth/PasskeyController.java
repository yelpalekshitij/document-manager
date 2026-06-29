package app.vaultdoc.gateway.auth;

import java.util.UUID;

import app.vaultdoc.core.auth.passkey.PasskeyService;
import app.vaultdoc.core.auth.passkey.PasskeyRegistrationStarted;
import app.vaultdoc.core.auth.passkey.PasskeySigninStarted;
import app.vaultdoc.core.auth.passkey.PasskeySummary;
import app.vaultdoc.core.auth.service.AuthenticatedSession;
import app.vaultdoc.core.auth.service.InvalidCredentialsException;
import app.vaultdoc.gateway.auth.dto.AuthResponse;
import app.vaultdoc.gateway.auth.dto.PasskeyListResponse;
import app.vaultdoc.gateway.auth.dto.PasskeyRegistrationOptionsResponse;
import app.vaultdoc.gateway.auth.dto.PasskeyRegistrationRequest;
import app.vaultdoc.gateway.auth.dto.PasskeySigninOptionsResponse;
import app.vaultdoc.gateway.auth.dto.PasskeySigninRequest;
import tools.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/passkey")
public class PasskeyController {

    private final PasskeyService passkeys;

    public PasskeyController(PasskeyService passkeys) {
        this.passkeys = passkeys;
    }

    @PostMapping("/register/begin")
    public ResponseEntity<PasskeyRegistrationOptionsResponse> beginRegistration(
            @AuthenticationPrincipal String userId) {
        if (userId == null) {
            throw new InvalidCredentialsException();
        }
        PasskeyRegistrationStarted started = passkeys.beginRegistration(UUID.fromString(userId));
        return ResponseEntity.ok(new PasskeyRegistrationOptionsResponse(
                started.challengeId(), started.optionsJson()));
    }

    @PostMapping("/register/finish")
    public ResponseEntity<PasskeySummary> finishRegistration(
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody PasskeyRegistrationRequest req) {
        if (userId == null) {
            throw new InvalidCredentialsException();
        }
        PasskeySummary summary = passkeys.finishRegistration(
                UUID.fromString(userId), req.challengeId(), req.label(), req.credential());
        return ResponseEntity.status(HttpStatus.CREATED).body(summary);
    }

    @PostMapping("/signin/begin")
    public ResponseEntity<PasskeySigninOptionsResponse> beginSignin() {
        PasskeySigninStarted started = passkeys.beginSignin();
        return ResponseEntity.ok(new PasskeySigninOptionsResponse(
                started.challengeId(), started.optionsJson()));
    }

    @PostMapping("/signin/finish")
    public ResponseEntity<AuthResponse> finishSignin(
            @Valid @RequestBody PasskeySigninRequest req,
            HttpServletRequest http) {
        AuthenticatedSession session = passkeys.finishSignin(
                req.challengeId(),
                req.assertion(),
                http.getHeader("User-Agent"),
                http.getRemoteAddr());
        return ResponseEntity.ok(AuthResponse.from(session));
    }

    @GetMapping
    public ResponseEntity<PasskeyListResponse> list(@AuthenticationPrincipal String userId) {
        if (userId == null) {
            throw new InvalidCredentialsException();
        }
        return ResponseEntity.ok(new PasskeyListResponse(passkeys.list(UUID.fromString(userId))));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal String userId,
            @PathVariable("id") UUID id) {
        if (userId == null) {
            throw new InvalidCredentialsException();
        }
        passkeys.delete(UUID.fromString(userId), id);
        return ResponseEntity.noContent().build();
    }
}
