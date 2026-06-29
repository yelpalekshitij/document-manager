import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { ApiError, PROBLEM_VERIFICATION_RATE_LIMITED } from '../../core/api/types';
import { AuthStore } from '../../core/auth/auth.store';

type ViewState = 'form' | 'check-email';

@Component({
  selector: 'vd-register',
  standalone: true,
  imports: [FormsModule, RouterLink],
  template: `
    <main class="auth">
      <section class="card">
        @if (view() === 'form') {
          <h1>Create your vault</h1>
          <p class="tagline">Your documents, encrypted, intelligent, one search away.</p>
          <form (submit)="onSubmit($event)">
            <label>
              Email
              <input type="email" name="email" [(ngModel)]="email" autocomplete="email" required />
            </label>
            <label>
              Display name (optional)
              <input type="text" name="displayName" [(ngModel)]="displayName" autocomplete="name" />
            </label>
            <label>
              Password
              <input
                type="password"
                name="password"
                [(ngModel)]="password"
                autocomplete="new-password"
                minlength="12"
                required
              />
              <small>At least 12 characters. No composition rules — use a passphrase.</small>
            </label>
            @if (error(); as e) {
              <p class="error">{{ e }}</p>
            }
            <button type="submit" [disabled]="busy()">
              {{ busy() ? 'Creating…' : 'Create account' }}
            </button>
          </form>
          <p class="hint">Already have an account? <a routerLink="/login">Sign in</a>.</p>
        } @else {
          <h1>Check your email</h1>
          <p class="tagline">
            We sent a verification link to <strong>{{ submittedEmail() }}</strong>. Click it to
            activate your account.
          </p>
          @if (resendError(); as e) {
            <p class="error">{{ e }}</p>
          } @else if (resendNotice(); as n) {
            <p class="notice">{{ n }}</p>
          }
          <button type="button" class="link" [disabled]="resendBusy()" (click)="resend()">
            {{ resendBusy() ? 'Resending…' : 'Resend email' }}
          </button>
          <p class="hint">Wrong address? <a routerLink="/register" (click)="reset()">Start over</a>.</p>
        }
      </section>
    </main>
  `,
  styles: [
    `
      .auth { min-height: 100vh; display: grid; place-items: center; padding: 2rem; }
      .card {
        width: min(440px, 100%);
        background: var(--vd-bg-card);
        border: 1px solid var(--vd-border);
        border-radius: 12px;
        padding: 2rem;
      }
      h1 { margin: 0 0 0.25rem; font-size: 1.5rem; }
      .tagline { margin: 0 0 1.5rem; color: var(--vd-fg-muted); font-size: 0.9rem; }
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
      small { color: var(--vd-fg-muted); font-size: 0.75rem; }
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
      button.link {
        background: transparent;
        color: var(--vd-accent);
        border: 1px solid var(--vd-border);
        margin-top: 1rem;
      }
      button[disabled] { opacity: 0.6; cursor: progress; }
      .hint { margin: 1rem 0 0; font-size: 0.85rem; color: var(--vd-fg-muted); }
      .error { color: var(--vd-danger); margin: 0; font-size: 0.875rem; }
      .notice { color: var(--vd-success); margin: 0; font-size: 0.875rem; }
    `,
  ],
})
export class RegisterComponent {
  private readonly store = inject(AuthStore);

  email = '';
  password = '';
  displayName = '';

  readonly view = signal<ViewState>('form');
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly submittedEmail = signal('');
  readonly resendBusy = signal(false);
  readonly resendError = signal<string | null>(null);
  readonly resendNotice = signal<string | null>(null);

  onSubmit(event: SubmitEvent): void {
    event.preventDefault();
    this.busy.set(true);
    this.error.set(null);
    const submitEmail = this.email.trim();
    this.store
      .register({
        email: submitEmail,
        password: this.password,
        displayName: this.displayName.trim() || undefined,
      })
      .subscribe({
        next: () => {
          this.busy.set(false);
          this.submittedEmail.set(submitEmail);
          this.view.set('check-email');
        },
        error: (e: ApiError) => {
          this.busy.set(false);
          this.error.set(e.message ?? 'Registration failed.');
        },
      });
  }

  resend(): void {
    if (!this.submittedEmail()) return;
    this.resendBusy.set(true);
    this.resendError.set(null);
    this.resendNotice.set(null);
    this.store.resendVerification(this.submittedEmail()).subscribe({
      next: () => {
        this.resendBusy.set(false);
        this.resendNotice.set('Verification email sent.');
      },
      error: (e: ApiError) => {
        this.resendBusy.set(false);
        if (e.is(PROBLEM_VERIFICATION_RATE_LIMITED)) {
          this.resendError.set('Hold on — try again in a minute.');
        } else {
          this.resendError.set(e.message ?? 'Could not resend right now.');
        }
      },
    });
  }

  reset(): void {
    this.view.set('form');
    this.submittedEmail.set('');
    this.error.set(null);
    this.resendError.set(null);
    this.resendNotice.set(null);
  }
}
