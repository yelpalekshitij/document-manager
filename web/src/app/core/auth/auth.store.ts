import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, tap } from 'rxjs';

import { AuthService } from '../api/generated';
import { toApiError } from '../api/http-error';
import {
  AuthResponse,
  LoginRequest,
  MeResponse,
  RegisterRequest,
  RegistrationResponse,
  VerifyEmailRequest,
} from '../api/types';

const ACCESS_TOKEN_KEY = 'vd.accessToken';
const REFRESH_TOKEN_KEY = 'vd.refreshToken';
const USER_KEY = 'vd.user';

/**
 * Tokens live in localStorage for Phase 1.1a/b so the dev loop is frictionless.
 * Phase 1.1c will move them to HTTP-only cookies set by the backend; the surface
 * exposed by this store does not change.
 */
@Injectable({ providedIn: 'root' })
export class AuthStore {
  private readonly api = inject(AuthService);

  private readonly _user = signal<MeResponse | null>(readJson<MeResponse>(USER_KEY));
  private readonly _accessToken = signal<string | null>(readString(ACCESS_TOKEN_KEY));
  private readonly _refreshToken = signal<string | null>(readString(REFRESH_TOKEN_KEY));

  readonly user = this._user.asReadonly();
  readonly accessToken = this._accessToken.asReadonly();
  readonly isAuthenticated = computed(() => this._accessToken() !== null);

  register(body: RegisterRequest): Observable<RegistrationResponse> {
    return this.api.register(body).pipe(catchError(toApiError));
  }

  login(body: LoginRequest): Observable<AuthResponse | { twoFactorToken: string }> {
    // The /auth/login endpoint can return either an AuthResponse (2FA off)
    // or a TwoFactorChallengeResponse { twoFactorToken } (2FA on).
    // We only store the session in the former case.
    return (this.api.login(body) as Observable<unknown>).pipe(
      tap((res) => {
        if (res && typeof res === 'object' && 'accessToken' in res) {
          this.store(res as AuthResponse);
        }
      }),
      catchError(toApiError),
    ) as Observable<AuthResponse | { twoFactorToken: string }>;
  }

  verifyEmail(body: VerifyEmailRequest): Observable<AuthResponse> {
    return this.api.verifyEmail(body).pipe(
      tap((res) => this.store(res)),
      catchError(toApiError),
    );
  }

  resendVerification(email: string): Observable<void> {
    return this.api.resendVerification({ email }).pipe(catchError(toApiError));
  }

  refresh(): Observable<AuthResponse> | null {
    const rt = this._refreshToken();
    if (!rt) return null;
    return this.api.refresh({ refreshToken: rt }).pipe(
      tap((res) => this.store(res)),
      catchError(toApiError),
    );
  }

  logout(): void {
    const rt = this._refreshToken();
    if (rt) {
      this.api.logout({ refreshToken: rt }).subscribe({ error: () => void 0 });
    }
    this.clear();
  }

  /**
   * Store a session obtained out-of-band (e.g. from the passkey sign-in
   * ceremony, which doesn't route through {@link login}).
   */
  adoptSession(res: AuthResponse): void {
    this.store(res);
  }

  private store(res: AuthResponse): void {
    this._accessToken.set(res.accessToken);
    this._refreshToken.set(res.refreshToken);
    this._user.set(res.user);
    writeString(ACCESS_TOKEN_KEY, res.accessToken);
    writeString(REFRESH_TOKEN_KEY, res.refreshToken);
    writeJson(USER_KEY, res.user);
  }

  private clear(): void {
    this._accessToken.set(null);
    this._refreshToken.set(null);
    this._user.set(null);
    localStorage.removeItem(ACCESS_TOKEN_KEY);
    localStorage.removeItem(REFRESH_TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
  }
}

function readString(key: string): string | null {
  return typeof window === 'undefined' ? null : localStorage.getItem(key);
}

function writeString(key: string, value: string): void {
  if (typeof window !== 'undefined') localStorage.setItem(key, value);
}

function readJson<T>(key: string): T | null {
  const raw = readString(key);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as T;
  } catch {
    return null;
  }
}

function writeJson<T>(key: string, value: T): void {
  if (typeof window !== 'undefined') localStorage.setItem(key, JSON.stringify(value));
}
