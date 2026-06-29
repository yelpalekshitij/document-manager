import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { catchError } from 'rxjs';

import { ApiError } from '../../core/api/types';
import { toApiError } from '../../core/api/http-error';

@Component({
  selector: 'vd-reset-password',
  standalone: true,
  imports: [FormsModule, RouterLink],
  template: `
    <main class="auth">
      <section class="card">
        @if (!token()) {
          <h1>Invalid link</h1>
          <p class="tagline">No reset token was found. <a routerLink="/forgot-password">Request a new link.</a></p>
        } @else if (done()) {
          <h1>Password updated</h1>
          <p class="tagline">Your password has been changed. <a routerLink="/login">Sign in</a> with your new password.</p>
        } @else {
          <h1>Set a new password</h1>
          <form (submit)="onSubmit($event)">
            <label>
              New password
              <input
                type="password"
                name="password"
                [(ngModel)]="password"
                autocomplete="new-password"
                minlength="12"
                required
              />
              <small>At least 12 characters.</small>
            </label>
            @if (error(); as e) { <p class="error">{{ e }}</p> }
            <button type="submit" [disabled]="busy() || password.length < 12">
              {{ busy() ? 'Updating…' : 'Update password' }}
            </button>
          </form>
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
    small { color: var(--vd-fg-muted); font-size: 0.75rem; }
    input { padding: 0.65rem 0.75rem; background: var(--vd-bg); border: 1px solid var(--vd-border); border-radius: 6px; color: var(--vd-fg); font: inherit; }
    input:focus { outline: none; border-color: var(--vd-accent); }
    button { margin-top: 0.25rem; padding: 0.75rem 1rem; background: var(--vd-accent); color: #0e1116; border: 0; border-radius: 6px; font-weight: 600; cursor: pointer; }
    button[disabled] { opacity: 0.6; cursor: not-allowed; }
    .error { color: var(--vd-danger); font-size: 0.875rem; margin: 0; }
  `],
})
export class ResetPasswordComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly http = inject(HttpClient);

  password = '';
  readonly token = signal<string | null>(null);
  readonly busy = signal(false);
  readonly done = signal(false);
  readonly error = signal<string | null>(null);

  ngOnInit(): void {
    const t = this.route.snapshot.queryParamMap.get('token');
    this.token.set(t);
  }

  onSubmit(event: SubmitEvent): void {
    event.preventDefault();
    const t = this.token();
    if (!t) return;
    this.busy.set(true);
    this.error.set(null);
    this.http.post('/api/v1/auth/reset-password', { token: t, newPassword: this.password })
      .pipe(catchError(toApiError))
      .subscribe({
        next: () => { this.busy.set(false); this.done.set(true); },
        error: (e: ApiError) => { this.busy.set(false); this.error.set(e.message ?? 'Reset failed.'); },
      });
  }
}
