package app.vaultdoc.gateway.config;

import java.net.URI;

import app.vaultdoc.core.auth.passkey.InvalidPasskeyCredentialException;
import app.vaultdoc.core.auth.passkey.PasskeyChallengeExpiredException;
import app.vaultdoc.core.auth.passkey.PasskeyNotFoundException;
import app.vaultdoc.core.auth.service.AccountLockedException;
import app.vaultdoc.core.auth.service.DuplicateEmailException;
import app.vaultdoc.core.auth.service.InvalidCredentialsException;
import app.vaultdoc.core.auth.service.InvalidResetTokenException;
import app.vaultdoc.core.auth.service.InvalidVerificationTokenException;
import app.vaultdoc.core.auth.service.ResetRateLimitedException;
import app.vaultdoc.core.auth.service.UnverifiedAccountException;
import app.vaultdoc.core.auth.service.VerificationRateLimitedException;
import app.vaultdoc.core.auth.token.InvalidRefreshTokenException;
import app.vaultdoc.core.auth.twofa.InvalidFirstFactorTokenException;
import app.vaultdoc.core.auth.twofa.InvalidTotpCodeException;
import app.vaultdoc.core.auth.twofa.TwoFactorAlreadyEnabledException;
import app.vaultdoc.core.auth.twofa.TwoFactorNotEnabledException;
import app.vaultdoc.catalog.service.DocumentNotFoundException;
import app.vaultdoc.catalog.service.FolderNotFoundException;
import app.vaultdoc.catalog.service.InvalidTagException;
import app.vaultdoc.catalog.service.TagLimitExceededException;
import app.vaultdoc.catalog.service.TagNameConflictException;
import app.vaultdoc.catalog.service.TagNotFoundException;
import app.vaultdoc.ingest.service.DocumentAlreadyFinalizedException;
import app.vaultdoc.ingest.service.MimeNotAllowedException;
import app.vaultdoc.ingest.service.SizeExceededException;
import app.vaultdoc.ingest.service.SizeMismatchException;
import app.vaultdoc.ingest.service.UploadObjectMissingException;
import app.vaultdoc.ingest.service.VirusDetectedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ProblemDetailsHandler {

    private static final String PROBLEM_BASE = "https://vaultdoc.app/problems/";

    @ExceptionHandler(DuplicateEmailException.class)
    ResponseEntity<ProblemDetail> handleDuplicate(DuplicateEmailException e, HttpServletRequest req) {
        return problem(HttpStatus.CONFLICT, type("email-already-in-use"),
                "Email already in use", e.getMessage(), req);
    }

    @ExceptionHandler({InvalidCredentialsException.class, InvalidRefreshTokenException.class})
    ResponseEntity<ProblemDetail> handleUnauthorized(RuntimeException e, HttpServletRequest req) {
        return problem(HttpStatus.UNAUTHORIZED, type("invalid-credentials"),
                "Unauthorized", e.getMessage(), req);
    }

    @ExceptionHandler(UnverifiedAccountException.class)
    ResponseEntity<ProblemDetail> handleUnverified(UnverifiedAccountException e, HttpServletRequest req) {
        return problem(HttpStatus.FORBIDDEN, type("unverified-account"),
                "Email not verified",
                "Sign-in is blocked until the account email is verified.", req);
    }

    @ExceptionHandler(AccountLockedException.class)
    ResponseEntity<ProblemDetail> handleLocked(AccountLockedException e, HttpServletRequest req) {
        return problem(HttpStatus.FORBIDDEN, type("account-locked"),
                "Account locked", e.getMessage(), req);
    }

    @ExceptionHandler(InvalidVerificationTokenException.class)
    ResponseEntity<ProblemDetail> handleInvalidToken(InvalidVerificationTokenException e, HttpServletRequest req) {
        return problem(HttpStatus.BAD_REQUEST, type("invalid-verification-token"),
                "Invalid verification token", e.getMessage(), req);
    }

    @ExceptionHandler(VerificationRateLimitedException.class)
    ResponseEntity<ProblemDetail> handleRateLimit(VerificationRateLimitedException e, HttpServletRequest req) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        body.setTitle("Too many requests");
        body.setType(type("verification-rate-limited"));
        body.setInstance(URI.create(req.getRequestURI()));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfter().getSeconds()))
                .body(body);
    }

    @ExceptionHandler(PasskeyChallengeExpiredException.class)
    ResponseEntity<ProblemDetail> handlePasskeyChallenge(PasskeyChallengeExpiredException e, HttpServletRequest req) {
        return problem(HttpStatus.BAD_REQUEST, type("passkey-challenge-expired"),
                "Passkey challenge expired", e.getMessage(), req);
    }

    @ExceptionHandler(InvalidPasskeyCredentialException.class)
    ResponseEntity<ProblemDetail> handlePasskeyInvalid(InvalidPasskeyCredentialException e, HttpServletRequest req) {
        return problem(HttpStatus.BAD_REQUEST, type("invalid-passkey-credential"),
                "Invalid passkey credential", e.getMessage(), req);
    }

    @ExceptionHandler(PasskeyNotFoundException.class)
    ResponseEntity<ProblemDetail> handlePasskeyNotFound(PasskeyNotFoundException e, HttpServletRequest req) {
        return problem(HttpStatus.NOT_FOUND, type("passkey-not-found"),
                "Passkey not found", e.getMessage(), req);
    }

    @ExceptionHandler(InvalidResetTokenException.class)
    ResponseEntity<ProblemDetail> handleInvalidReset(InvalidResetTokenException e, HttpServletRequest req) {
        return problem(HttpStatus.BAD_REQUEST, type("invalid-reset-token"),
                "Invalid reset token", e.getMessage(), req);
    }

    @ExceptionHandler(ResetRateLimitedException.class)
    ResponseEntity<ProblemDetail> handleResetRateLimit(ResetRateLimitedException e, HttpServletRequest req) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        body.setTitle("Too many requests");
        body.setType(type("reset-rate-limited"));
        body.setInstance(URI.create(req.getRequestURI()));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfter().getSeconds()))
                .body(body);
    }

    @ExceptionHandler(InvalidTotpCodeException.class)
    ResponseEntity<ProblemDetail> handleInvalidTotp(InvalidTotpCodeException e, HttpServletRequest req) {
        return problem(HttpStatus.BAD_REQUEST, type("invalid-totp-code"),
                "Invalid TOTP code", e.getMessage(), req);
    }

    @ExceptionHandler(InvalidFirstFactorTokenException.class)
    ResponseEntity<ProblemDetail> handleInvalidFF(InvalidFirstFactorTokenException e, HttpServletRequest req) {
        return problem(HttpStatus.BAD_REQUEST, type("invalid-first-factor-token"),
                "Invalid or expired 2FA token", e.getMessage(), req);
    }

    @ExceptionHandler(TwoFactorAlreadyEnabledException.class)
    ResponseEntity<ProblemDetail> handle2faAlreadyEnabled(TwoFactorAlreadyEnabledException e, HttpServletRequest req) {
        return problem(HttpStatus.CONFLICT, type("two-factor-already-enabled"),
                "2FA already enabled", e.getMessage(), req);
    }

    @ExceptionHandler(TwoFactorNotEnabledException.class)
    ResponseEntity<ProblemDetail> handle2faNotEnabled(TwoFactorNotEnabledException e, HttpServletRequest req) {
        return problem(HttpStatus.CONFLICT, type("two-factor-not-enabled"),
                "2FA not enabled", e.getMessage(), req);
    }

    // ── Documents / folders / ingest ───────────────────────────────────────

    @ExceptionHandler(DocumentNotFoundException.class)
    ResponseEntity<ProblemDetail> handleDocNotFound(DocumentNotFoundException e, HttpServletRequest req) {
        return problem(HttpStatus.NOT_FOUND, type("document-not-found"),
                "Document not found", e.getMessage(), req);
    }

    @ExceptionHandler(FolderNotFoundException.class)
    ResponseEntity<ProblemDetail> handleFolderNotFound(FolderNotFoundException e, HttpServletRequest req) {
        return problem(HttpStatus.NOT_FOUND, type("folder-not-found"),
                "Folder not found", e.getMessage(), req);
    }

    @ExceptionHandler(MimeNotAllowedException.class)
    ResponseEntity<ProblemDetail> handleMimeNotAllowed(MimeNotAllowedException e, HttpServletRequest req) {
        return problem(HttpStatus.BAD_REQUEST, type("mime-not-allowed"),
                "Unsupported file type", e.getMessage(), req);
    }

    @ExceptionHandler(SizeExceededException.class)
    ResponseEntity<ProblemDetail> handleSizeExceeded(SizeExceededException e, HttpServletRequest req) {
        return problem(HttpStatus.BAD_REQUEST, type("size-exceeded"),
                "File too large", e.getMessage(), req);
    }

    @ExceptionHandler(SizeMismatchException.class)
    ResponseEntity<ProblemDetail> handleSizeMismatch(SizeMismatchException e, HttpServletRequest req) {
        return problem(HttpStatus.CONFLICT, type("size-mismatch"),
                "Uploaded size does not match", e.getMessage(), req);
    }

    @ExceptionHandler(UploadObjectMissingException.class)
    ResponseEntity<ProblemDetail> handleUploadMissing(UploadObjectMissingException e, HttpServletRequest req) {
        return problem(HttpStatus.CONFLICT, type("upload-object-missing"),
                "Upload not received", e.getMessage(), req);
    }

    @ExceptionHandler(DocumentAlreadyFinalizedException.class)
    ResponseEntity<ProblemDetail> handleAlreadyFinalized(DocumentAlreadyFinalizedException e, HttpServletRequest req) {
        return problem(HttpStatus.CONFLICT, type("document-already-finalized"),
                "Document already finalized", e.getMessage(), req);
    }

    @ExceptionHandler(VirusDetectedException.class)
    ResponseEntity<ProblemDetail> handleVirus(VirusDetectedException e, HttpServletRequest req) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, type("virus-detected"),
                "Virus detected", e.getMessage(), req);
    }

    // ── Tags ───────────────────────────────────────────────────────────────

    @ExceptionHandler(TagNotFoundException.class)
    ResponseEntity<ProblemDetail> handleTagNotFound(TagNotFoundException e, HttpServletRequest req) {
        return problem(HttpStatus.NOT_FOUND, type("tag-not-found"),
                "Tag not found", e.getMessage(), req);
    }

    @ExceptionHandler(TagNameConflictException.class)
    ResponseEntity<ProblemDetail> handleTagConflict(TagNameConflictException e, HttpServletRequest req) {
        return problem(HttpStatus.CONFLICT, type("tag-name-conflict"),
                "Tag name already in use", e.getMessage(), req);
    }

    @ExceptionHandler(TagLimitExceededException.class)
    ResponseEntity<ProblemDetail> handleTagLimit(TagLimitExceededException e, HttpServletRequest req) {
        return problem(HttpStatus.CONFLICT, type("tag-limit-reached"),
                "Tag limit reached", e.getMessage(), req);
    }

    @ExceptionHandler(InvalidTagException.class)
    ResponseEntity<ProblemDetail> handleInvalidTag(InvalidTagException e, HttpServletRequest req) {
        return problem(HttpStatus.BAD_REQUEST, type("invalid-tag"),
                "Invalid tag", e.getMessage(), req);
    }

    // ── Request binding ────────────────────────────────────────────────────

    /** A path/query value that doesn't convert (e.g. a non-UUID id) — 400, not a 500. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest req) {
        return problem(HttpStatus.BAD_REQUEST, type("validation"),
                "Validation failed", e.getName() + ": invalid value", req);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException e, HttpServletRequest req) {
        return problem(HttpStatus.BAD_REQUEST, type("malformed-request"),
                "Malformed request body", "The request body is not valid JSON for this endpoint.", req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException e, HttpServletRequest req) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        return problem(HttpStatus.BAD_REQUEST, type("validation"),
                "Validation failed", detail, req);
    }

    private static URI type(String slug) {
        return URI.create(PROBLEM_BASE + slug);
    }

    private static ResponseEntity<ProblemDetail> problem(
            HttpStatus status, URI type, String title, String detail, HttpServletRequest req) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(title);
        body.setType(type);
        body.setInstance(URI.create(req.getRequestURI()));
        return ResponseEntity.status(status).body(body);
    }
}
