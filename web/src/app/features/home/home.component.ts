import { Component, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router, RouterLink } from '@angular/router';

import { AuthStore } from '../../core/auth/auth.store';
import { PasskeyListComponent } from './passkey-list.component';
import { TwoFactorSettingsComponent } from '../settings/two-factor-settings.component';

interface HealthResponse {
  status: string;
  components: Record<string, string>;
}

@Component({
  selector: 'vd-home',
  standalone: true,
  imports: [PasskeyListComponent, TwoFactorSettingsComponent, RouterLink],
  template: `
    <main class="home">
      <header>
        <div>
          <h1>VaultDoc</h1>
          @if (store.user(); as u) {
            <p class="who">Signed in as <strong>{{ u.email }}</strong></p>
          }
        </div>
        <button type="button" class="link" (click)="signOut()">Sign out</button>
      </header>

      <section class="card">
        <h2>Backend health</h2>
        @if (loading()) {
          <p>Checking…</p>
        } @else if (error()) {
          <p class="error">Backend unreachable: {{ error() }}</p>
        } @else if (health(); as h) {
          <p>Overall: <strong>{{ h.status }}</strong></p>
          <ul>
            @for (entry of components(); track entry.key) {
              <li>{{ entry.key }}: {{ entry.value }}</li>
            }
          </ul>
        }
      </section>

      <section class="card cta">
        <div>
          <h2>Your documents</h2>
          <p>Upload, organize, search, and view files in one place.</p>
        </div>
        <a class="primary" routerLink="/documents">Open vault →</a>
      </section>

      <vd-passkey-list />
      <vd-two-factor-settings />

      <section class="card">
        <h2>What's next</h2>
        <p>
          OCR + LLM extraction, expiry alerts, and Ask-Your-Vault arrive in Phase 2.
        </p>
      </section>
    </main>
  `,
  styles: [
    `
      .home {
        max-width: 720px;
        margin: 4rem auto;
        padding: 0 1.5rem;
        display: grid;
        gap: 1.5rem;
      }
      header {
        display: flex;
        justify-content: space-between;
        align-items: flex-start;
        gap: 1rem;
      }
      h1 { margin: 0; }
      .who { margin: 0.25rem 0 0; color: var(--vd-fg-muted); font-size: 0.9rem; }
      .card {
        background: var(--vd-bg-card);
        border: 1px solid var(--vd-border);
        border-radius: 12px;
        padding: 1.5rem;
      }
      h2 { margin: 0 0 1rem; font-size: 1.05rem; color: var(--vd-fg-muted); }
      ul { margin: 0; padding-left: 1.25rem; }
      .error { color: var(--vd-danger); }
      .link {
        background: transparent;
        color: var(--vd-accent);
        border: 1px solid var(--vd-border);
        padding: 0.4rem 0.9rem;
        border-radius: 6px;
        cursor: pointer;
        font: inherit;
      }
      .link:hover { border-color: var(--vd-accent); }
      .cta {
        display: flex; align-items: center; justify-content: space-between;
        gap: 1.5rem;
      }
      .cta h2 { margin: 0 0 0.25rem; color: var(--vd-fg); font-size: 1.1rem; }
      .cta p { margin: 0; color: var(--vd-fg-muted); font-size: 0.9rem; }
      .cta .primary {
        background: var(--vd-accent); color: #0e1116;
        padding: 0.55rem 1.1rem; border-radius: 8px;
        text-decoration: none; font-weight: 500;
      }
    `,
  ],
})
export class HomeComponent {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  protected readonly store = inject(AuthStore);

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly health = signal<HealthResponse | null>(null);

  readonly components = () => {
    const h = this.health();
    return h ? Object.entries(h.components).map(([key, value]) => ({ key, value })) : [];
  };

  constructor() {
    this.http.get<HealthResponse>('/api/v1/health').subscribe({
      next: (h) => {
        this.health.set(h);
        this.loading.set(false);
      },
      error: (err: Error) => {
        this.error.set(err.message ?? 'unknown');
        this.loading.set(false);
      },
    });
  }

  signOut(): void {
    this.store.logout();
    this.router.navigate(['/login']);
  }
}
