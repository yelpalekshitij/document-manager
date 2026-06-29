import { Injectable, inject } from '@angular/core';
import { catchError, firstValueFrom } from 'rxjs';
import {
  browserSupportsWebAuthn,
  browserSupportsWebAuthnAutofill,
  startAuthentication,
  startRegistration,
} from '@simplewebauthn/browser';
import type {
  PublicKeyCredentialCreationOptionsJSON,
  PublicKeyCredentialRequestOptionsJSON,
} from '@simplewebauthn/browser';

import { PasskeyService } from '../api/generated';
import { toApiError } from '../api/http-error';
import { AuthResponse, PasskeySummary } from '../api/types';

/**
 * Orchestrates the two WebAuthn ceremonies. The generated `PasskeyService`
 * does the HTTP; `@simplewebauthn/browser` does the `navigator.credentials.*`
 * calls and base64url ↔ ArrayBuffer plumbing.
 */
@Injectable({ providedIn: 'root' })
export class PasskeyClient {
  private readonly api = inject(PasskeyService);

  /** Cheap synchronous capability probe. Hide the CTAs when false. */
  readonly browserSupportsWebAuthn = browserSupportsWebAuthn();

  /** Registration: user is already authed. */
  async addPasskey(label?: string): Promise<PasskeySummary> {
    const optionsResponse = await firstValueFrom(
      this.api.beginPasskeyRegistration().pipe(catchError(toApiError)),
    );

    const credential = await startRegistration({
      optionsJSON: optionsResponse.publicKey as PublicKeyCredentialCreationOptionsJSON,
    });

    return firstValueFrom(
      this.api
        .finishPasskeyRegistration({
          challengeId: optionsResponse.challengeId,
          label,
          credential,
        })
        .pipe(catchError(toApiError)),
    );
  }

  /** Sign-in: no auth required. */
  async signIn(): Promise<AuthResponse> {
    const optionsResponse = await firstValueFrom(
      this.api.beginPasskeySignin().pipe(catchError(toApiError)),
    );

    const assertion = await startAuthentication({
      optionsJSON: optionsResponse.publicKey as PublicKeyCredentialRequestOptionsJSON,
    });

    return firstValueFrom(
      this.api
        .finishPasskeySignin({
          challengeId: optionsResponse.challengeId,
          assertion,
        })
        .pipe(catchError(toApiError)),
    );
  }

  /**
   * Sign in via Conditional UI (autofill mediation). Kicks off a
   * non-blocking `navigator.credentials.get` that lies dormant until the
   * user focuses an input with `autocomplete="username webauthn"`, at
   * which point the browser surfaces available passkeys in the autofill
   * dropdown. The promise resolves when a credential is chosen.
   *
   * Pass an {@link AbortController} to cancel when the screen unmounts
   * (Conditional UI requests stay open until aborted or fulfilled).
   *
   * Returns null when autofill isn't supported by the browser — caller
   * should fall back to the explicit "Sign in with a passkey" button.
   */
  async signInAutofill(signal?: AbortSignal): Promise<AuthResponse | null> {
    const supported = await browserSupportsWebAuthnAutofill();
    if (!supported) return null;

    const optionsResponse = await firstValueFrom(
      this.api.beginPasskeySignin().pipe(catchError(toApiError)),
    );

    let assertion;
    try {
      assertion = await startAuthentication({
        optionsJSON: optionsResponse.publicKey as PublicKeyCredentialRequestOptionsJSON,
        useBrowserAutofill: true,
        // @simplewebauthn/browser v11 accepts an AbortSignal at the top level.
        // Cast for older typings.
        ...({ abortSignal: signal } as { abortSignal?: AbortSignal }),
      });
    } catch (err) {
      // AbortError is the expected path when navigation cancels the request —
      // swallow silently. Anything else bubbles up.
      if (err instanceof Error && err.name === 'AbortError') return null;
      throw err;
    }

    return firstValueFrom(
      this.api
        .finishPasskeySignin({
          challengeId: optionsResponse.challengeId,
          assertion,
        })
        .pipe(catchError(toApiError)),
    );
  }

  list(): Promise<PasskeySummary[]> {
    return firstValueFrom(
      this.api
        .listPasskeys()
        .pipe(catchError(toApiError)),
    ).then((r) => r.passkeys);
  }

  delete(id: string): Promise<void> {
    return firstValueFrom(
      this.api.deletePasskey(id).pipe(catchError(toApiError)),
    ).then(() => undefined);
  }
}
