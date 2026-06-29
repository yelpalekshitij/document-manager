import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { ApiError } from '../../core/api/types';
import { AuthStore } from '../../core/auth/auth.store';

type Phase = 'verifying' | 'failed' | 'done';

@Component({
  selector: 'vd-verify-email',
  standalone: true,
  imports: [RouterLink],
  template: `
    <main class="verify">
      <section class="card">
        @switch (phase()) {
          @case ('verifying') {
            <h1>Verifying your email…</h1>
            <p class="tagline">Hold on while we activate your vault.</p>
          }
          @case ('done') {
            <h1>Email verified</h1>
            <p class="tagline">Taking you to your vault…</p>
          }
          @case ('failed') {
            <h1>That link didn't work</h1>
            <p class="tagline">{{ message() }}</p>
            <p class="hint">
              Need a new link? <a routerLink="/login">Sign in</a> and we'll offer to resend.
            </p>
          }
        }
      </section>
    </main>
  `,
  styles: [
    `
      .verify { min-height: 100vh; display: grid; place-items: center; padding: 2rem; }
      .card {
        width: min(420px, 100%);
        background: var(--vd-bg-card);
        border: 1px solid var(--vd-border);
        border-radius: 12px;
        padding: 2rem;
      }
      h1 { margin: 0 0 0.5rem; font-size: 1.5rem; }
      .tagline { margin: 0; color: var(--vd-fg-muted); font-size: 0.95rem; }
      .hint { margin: 1rem 0 0; font-size: 0.85rem; color: var(--vd-fg-muted); }
    `,
  ],
})
export class VerifyEmailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly store = inject(AuthStore);

  readonly phase = signal<Phase>('verifying');
  readonly message = signal<string>('Token missing or invalid.');

  ngOnInit(): void {
    const token = this.route.snapshot.queryParamMap.get('token');
    if (!token) {
      this.message.set('No verification token was provided.');
      this.phase.set('failed');
      return;
    }
    this.store.verifyEmail({ token }).subscribe({
      next: () => {
        this.phase.set('done');
        setTimeout(() => this.router.navigate(['/']), 400);
      },
      error: (e: ApiError) => {
        this.message.set(e.message ?? 'Token unknown, expired, or already used.');
        this.phase.set('failed');
      },
    });
  }
}
