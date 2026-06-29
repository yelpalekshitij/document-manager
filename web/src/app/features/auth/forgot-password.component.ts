import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { catchError } from 'rxjs';

import { ApiError, PROBLEM_VERIFICATION_RATE_LIMITED } from '../../core/api/types';
import { toApiError } from '../../core/api/http-error';

@Component({
  selector: 'vd-forgot-password',
  standalone: true,
  imports: [FormsModule, RouterLink],
  template: `
    <main class="auth">
      <section class="card">
        @if (!sent()) {
          <h1>Forgot your password?</h1>
          <p class="tagline">Enter your email and we'll send a reset link.</p>
          <form (submit)="onSubmit($event)">
            <label>
              Email
              <input type="email" name="email" [(ngModel)]="email" autocomplete="email" required />
            </label>
            @if (error(); as e) { <p class="error">{{ e }}</p> }
            <button type="submit" [disabled]="busy()">
              {{ busy() ? 'Sending…' : 'Send reset link' }}
            </button>
          </form>
          <p class="hint"><a routerLink="/login">← Back to sign in</a></p>
        } @else {
          <h1>Check your email</h1>
          <p class="tagline">
            If <strong>{{ submittedEmail() }}</strong> has a VaultDoc account,
            a reset link is on its way. Check your spam folder if it doesn't arrive.
          </p>
          <p class="hint"><a routerLink="/login">← Back to sign in</a></p>
        }
      </section>
    </main>
  `,
  styles: [`
    .auth { min-height: 100vh; display: grid; place-items: center; padding: 2rem; }
    .card { width: min(420px, 100%); background: var(--vd-bg-card); border: 1px solid var(--vd-border); border-radius: 12px; padding: 2rem; }
    h1 { margin: 0 0 0.25rem; font-size: 1.5rem; }
    .tagline { margin: 0 0 1.5rem; color: var(--vd-fg-muted); font-size: 0.9rem; }
    form { display: grid; gap: 1rem; }
    label { display: grid; gap: 0.35rem; font-size: 0.875rem; color: var(--vd-fg-muted); }
    input { padding: 0.65rem 0.75rem; background: var(--vd-bg); border: 1px solid var(--vd-border); border-radius: 6px; color: var(--vd-fg); font: inherit; }
    input:focus { outline: none; border-color: var(--vd-accent); }
    button { margin-top: 0.25rem; padding: 0.75rem 1rem; background: var(--vd-accent); color: #0e1116; border: 0; border-radius: 6px; font-weight: 600; cursor: pointer; }
    button[disabled] { opacity: 0.6; cursor: progress; }
    .hint { margin: 1rem 0 0; font-size: 0.85rem; color: var(--vd-fg-muted); }
    .error { color: var(--vd-danger); font-size: 0.875rem; margin: 0; }
  `],
})
export class ForgotPasswordComponent {
  private readonly http = inject(HttpClient);

  email = '';
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly sent = signal(false);
  readonly submittedEmail = signal('');

  onSubmit(event: SubmitEvent): void {
    event.preventDefault();
    this.busy.set(true);
    this.error.set(null);
    const emailTrimmed = this.email.trim();
    this.http.post('/api/v1/auth/forgot-password', { email: emailTrimmed })
      .pipe(catchError(toApiError))
      .subscribe({
        next: () => {
          this.busy.set(false);
          this.submittedEmail.set(emailTrimmed);
          this.sent.set(true);
        },
        error: (e: ApiError) => {
          this.busy.set(false);
          if (e.status === 429) {
            this.error.set('Too many requests — try again in a moment.');
          } else {
            this.error.set(e.message ?? 'Request failed.');
          }
        },
      });
  }
}
