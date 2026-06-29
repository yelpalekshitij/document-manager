package app.vaultdoc.gateway.auth;

import java.util.List;
import java.util.UUID;

import app.vaultdoc.core.auth.service.AuthenticatedSession;
import app.vaultdoc.core.auth.service.InvalidCredentialsException;
import app.vaultdoc.core.auth.twofa.TwoFactorService;
import app.vaultdoc.gateway.auth.dto.AuthResponse;
import app.vaultdoc.gateway.auth.dto.BackupCodesResponse;
import app.vaultdoc.gateway.auth.dto.BackupCodesStatusResponse;
import app.vaultdoc.gateway.auth.dto.TwoFactorBackupRequest;
import app.vaultdoc.gateway.auth.dto.TwoFactorCodeRequest;
import app.vaultdoc.gateway.auth.dto.TwoFactorSetupResponse;
import app.vaultdoc.gateway.auth.dto.TwoFactorVerifyRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/2fa")
public class TwoFactorController {

    private final TwoFactorService twoFa;

    public TwoFactorController(TwoFactorService twoFa) {
        this.twoFa = twoFa;
    }

    @PostMapping("/setup/begin")
    public ResponseEntity<TwoFactorSetupResponse> setupBegin(
            @AuthenticationPrincipal String userId) {
        requireAuth(userId);
        var started = twoFa.beginSetup(UUID.fromString(userId));
        return ResponseEntity.ok(TwoFactorSetupResponse.from(started));
    }

    @PostMapping("/setup/finish")
    public ResponseEntity<BackupCodesResponse> setupFinish(
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody TwoFactorCodeRequest req) {
        requireAuth(userId);
        List<String> codes = twoFa.finishSetup(UUID.fromString(userId), req.code());
        return ResponseEntity.ok(new BackupCodesResponse(codes));
    }

    @PostMapping("/verify")
    public ResponseEntity<AuthResponse> verify(
            @Valid @RequestBody TwoFactorVerifyRequest req,
            HttpServletRequest http) {
        AuthenticatedSession session = twoFa.verifyTotp(
                req.twoFactorToken(), req.code(),
                http.getHeader("User-Agent"), http.getRemoteAddr());
        return ResponseEntity.ok(AuthResponse.from(session));
    }

    @PostMapping("/backup")
    public ResponseEntity<AuthResponse> backup(
            @Valid @RequestBody TwoFactorBackupRequest req,
            HttpServletRequest http) {
        AuthenticatedSession session = twoFa.verifyBackupCode(
                req.twoFactorToken(), req.backupCode(),
                http.getHeader("User-Agent"), http.getRemoteAddr());
        return ResponseEntity.ok(AuthResponse.from(session));
    }

    @GetMapping("/backup-codes")
    public ResponseEntity<BackupCodesStatusResponse> backupCodesStatus(
            @AuthenticationPrincipal String userId) {
        requireAuth(userId);
        return ResponseEntity.ok(BackupCodesStatusResponse.from(
                twoFa.getBackupCodeStatus(UUID.fromString(userId))));
    }

    @PostMapping("/backup-codes/regenerate")
    public ResponseEntity<BackupCodesResponse> regenerateBackupCodes(
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody TwoFactorCodeRequest req) {
        requireAuth(userId);
        List<String> codes = twoFa.regenerateBackupCodes(UUID.fromString(userId), req.code());
        return ResponseEntity.ok(new BackupCodesResponse(codes));
    }

    @DeleteMapping
    public ResponseEntity<Void> disable(
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody TwoFactorCodeRequest req) {
        requireAuth(userId);
        twoFa.disable(UUID.fromString(userId), req.code());
        return ResponseEntity.noContent().build();
    }

    private static void requireAuth(String userId) {
        if (userId == null) throw new InvalidCredentialsException();
    }
}
