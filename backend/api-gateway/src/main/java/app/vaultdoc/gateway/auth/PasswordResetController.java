package app.vaultdoc.gateway.auth;

import app.vaultdoc.core.auth.service.PasswordResetService;
import app.vaultdoc.gateway.auth.dto.ForgotPasswordRequest;
import app.vaultdoc.gateway.auth.dto.ResetPasswordRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class PasswordResetController {

    private final PasswordResetService passwordReset;

    public PasswordResetController(PasswordResetService passwordReset) {
        this.passwordReset = passwordReset;
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
        passwordReset.issueResetLink(req.email());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        passwordReset.resetPassword(req.token(), req.newPassword().toCharArray());
        return ResponseEntity.noContent().build();
    }
}
