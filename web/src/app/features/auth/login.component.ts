import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import {
  ApiError,
  PROBLEM_UNVERIFIED_ACCOUNT,
  PROBLEM_VERIFICATION_RATE_LIMITED,
} from '../../core/api/types';
import { AuthStore } from '../../core/auth/auth.store';
import { PasskeyClient } from '../../core/passkey/passkey.client';
import { PasskeyStore } from '../../core/passkey/passkey.store';
import { TwoFactorDialogComponent } from './two-factor-dialog.component';

@Component({
  selector: 'vd-login',
  standalone: true,
  imports: [FormsModule, RouterLink, TwoFactorDialogComponent],
  template: `
    @if (twoFactorToken(); as token) {
      <vd-two-factor-dialog
        [twoFactorToken]="token"
        (cancel)="twoFactorToken.set(null)"
        (success)="onTwoFactorSuccess()"
      />
    }
    <main class="auth">
      <section class="card">
        <h1>VaultDoc</h1>
        <p class="tagline">Sign in to your vault.</p>
        <form (submit)="onSubmit($event)">
          <label>
            Email
            <input
              type="email"
              name="email"
              [(ngModel)]="email"
              autocomplete="email webauthn"
              required
            />
          </label>
          <label>
            Password
            <input
              type="password"
              name="password"
              [(ngModel)]="password"
              autocomplete="current-password"
              required
            />
          </label>
          @if (error(); as e) {
            <p class="error">{{ e }}</p>
          }
          @if (unverified()) {
            <div class="callout">
              Your email isn't verified yet. Check your inbox or
              <button type="button" class="inline-link" [disabled]="resendBusy()" (click)="resend()">
                {{ resendBusy() ? 'resending…' : 'send a new link' }}
              </button>.
              @if (resendNotice(); as n) {
                <p class="notice">{{ n }}</p>
              }
            </div>
          }
          <button type="submit" [disabled]="busy()">{{ busy() ? 'Signing in…' : 'Sign in' }}</button>
        </form>

        @if (passkeyStore.browserSupportsWebAuthn) {
          <div class="divider"><span>or</span></div>
          <button
            type="button"
            class="secondary"
            [disabled]="passkeyStore.busy()"
            (click)="signInWithPasskey()"
          >
            {{ passkeyStore.busy() ? 'Waiting for passkey…' : 'Sign in with a passkey' }}
          </button>
          @if (passkeyStore.error(); as e) {
            <p class="error">{{ e }}</p>
          }
        }

        <p class="hint">
          No account? <a routerLink="/register">Create one</a>.
          &nbsp;·&nbsp;
          <a routerLink="/forgot-password">Forgot password?</a>
        </p>
      </section>
    </main>
  `,
  styles: [
    `
      .auth { min-height: 100vh; display: grid; place-items: center; padding: 2rem; }
      .card {
        width: min(420px, 100%);
        background: var(--vd-bg-card);
        border: 1px solid var(--vd-border);
        border-radius: 12px;
        padding: 2rem;
      }
      h1 { margin: 0 0 0.25rem; font-size: 1.75rem; }
      .tagline { margin: 0 0 1.5rem; color: var(--vd-fg-muted); font-size: 0.95rem; }
      form { display: grid; gap: 1rem; }
      label { display: grid; gap: 0.35rem; font-size: 0.875rem; color: var(--vd-fg-muted); }
      input {
        padding: 0.65rem 0.75rem;
        background: var(--vd-bg);
        border: 1px solid var(--vd-border);
        border-radius: 6px;
        color: var(--vd-fg);
        font: inherit;
      }
      input:focus { outline: none; border-color: var(--vd-accent); }
      button {
        margin-top: 0.5rem;
        padding: 0.75rem 1rem;
        background: var(--vd-accent);
        color: #0e1116;
        border: 0;
        border-radius: 6px;
        font-weight: 600;
        cursor: pointer;
      }
      button[disabled] { opacity: 0.6; cursor: progress; }
      .callout {
        background: var(--vd-bg);
        border: 1px solid var(--vd-border);
        border-radius: 6px;
        padding: 0.75rem 0.9rem;
        font-size: 0.85rem;
        color: var(--vd-fg-muted);
      }
      .inline-link {
        background: transparent;
        color: var(--vd-accent);
        border: 0;
        padding: 0;
        font: inherit;
        cursor: pointer;
      }
      .inline-link[disabled] { opacity: 0.6; }
      .hint { margin: 1rem 0 0; font-size: 0.85rem; color: var(--vd-fg-muted); }
      .error { color: var(--vd-danger); margin: 0; font-size: 0.875rem; }
      .notice { color: var(--vd-success); margin: 0.5rem 0 0; font-size: 0.8rem; }
      .divider {
        display: flex;
        align-items: center;
        gap: 0.75rem;
        margin: 1.25rem 0 0.75rem;
        color: var(--vd-fg-muted);
        font-size: 0.75rem;
        text-transform: uppercase;
        letter-spacing: 0.08em;
      }
      .divider::before, .divider::after {
        content: '';
        flex: 1;
        border-top: 1px solid var(--vd-border);
      }
      .secondary {
        margin-top: 0;
        background: transparent;
        color: var(--vd-fg);
        border: 1px solid var(--vd-border);
      }
      .secondary:hover { border-color: var(--vd-accent); color: var(--vd-accent); }
    `,
  ],
})
export class LoginComponent implements OnInit, OnDestroy {
  private readonly store = inject(AuthStore);
  private readonly router = inject(Router);
  protected readonly passkeyStore = inject(PasskeyStore);
  private readonly passkeyClient = inject(PasskeyClient);
  private autofillAbort?: AbortController;

  email = '';
  password = '';
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly unverified = signal(false);
  readonly resendBusy = signal(false);
  readonly resendNotice = signal<string | null>(null);
  readonly twoFactorToken = signal<string | null>(null);

  ngOnInit(): void {
    // Kick off Conditional UI: a non-blocking navigator.credentials.get that
    // surfaces available passkeys in the email field's autofill dropdown.
    // Silently no-ops when the browser doesn't support autofill mediation.
    if (!this.passkeyStore.browserSupportsWebAuthn) return;
    this.autofillAbort = new AbortController();
    void this.passkeyClient
      .signInAutofill(this.autofillAbort.signal)
      .then((session) => {
        if (session) {
          this.store.adoptSession(session);
          this.router.navigate(['/']);
        }
      })
      .catch(() => {
        // Conditional UI errors are non-fatal — the user can still type a
        // password or click "Sign in with a passkey".
      });
  }

  ngOnDestroy(): void {
    this.autofillAbort?.abort();
  }

  onSubmit(event: SubmitEvent): void {
    event.preventDefault();
    this.busy.set(true);
    this.error.set(null);
    this.unverified.set(false);
    this.resendNotice.set(null);
    this.store.login({ email: this.email.trim(), password: this.password }).subscribe({
      next: (res) => {
        this.busy.set(false);
        if (res && 'twoFactorToken' in res) {
          // 2FA is enabled — show the TOTP dialog.
          this.twoFactorToken.set(res.twoFactorToken);
        } else {
          this.router.navigate(['/']);
        }
      },
      error: (e: ApiError) => {
        this.busy.set(false);
        if (e.is(PROBLEM_UNVERIFIED_ACCOUNT)) {
          this.unverified.set(true);
        } else {
          this.error.set(e.message ?? 'Sign-in failed.');
        }
      },
    });
  }

  onTwoFactorSuccess(): void {
    this.twoFactorToken.set(null);
    this.router.navigate(['/']);
  }

  async signInWithPasskey(): Promise<void> {
    const session = await this.passkeyStore.signInWithPasskey();
    if (session) {
      this.router.navigate(['/']);
    }
  }

  resend(): void {
    if (!this.email.trim()) {
      this.error.set('Enter your email first, then resend.');
      return;
    }
    this.resendBusy.set(true);
    this.resendNotice.set(null);
    this.store.resendVerification(this.email.trim()).subscribe({
      next: () => {
        this.resendBusy.set(false);
        this.resendNotice.set('Verification email sent.');
      },
      error: (e: ApiError) => {
        this.resendBusy.set(false);
        if (e.is(PROBLEM_VERIFICATION_RATE_LIMITED)) {
          this.resendNotice.set('Try again in a minute.');
        } else {
          this.resendNotice.set(e.message ?? 'Could not resend right now.');
        }
      },
    });
  }
}
