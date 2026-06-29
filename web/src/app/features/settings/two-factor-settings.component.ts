import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';

import { ApiError } from '../../core/api/types';
import { toApiError } from '../../core/api/http-error';
import { catchError } from 'rxjs';

type Phase = 'idle' | 'setup' | 'confirm' | 'show-codes' | 'regenerating';

@Component({
  selector: 'vd-two-factor-settings',
  standalone: true,
  imports: [FormsModule],
  template: `
    <section class="card">
      <h2>Two-factor authentication</h2>
      <p class="hint">Protect your vault with a time-based one-time password (TOTP).</p>

      @if (phase() === 'idle') {
        @if (!enabled()) {
          <button class="primary" [disabled]="busy()" (click)="startSetup()">
            {{ busy() ? 'Starting…' : 'Set up 2FA' }}
          </button>
        } @else {
          <p class="status">2FA is <strong>enabled</strong>.</p>
          <div class="actions">
            <button class="link" (click)="phase.set('regenerating')">Regenerate backup codes</button>
            <button class="danger-link" [disabled]="busy()" (click)="startDisable()">Disable 2FA</button>
          </div>
        }
      }

      @if (phase() === 'setup') {
        <p class="setup-hint">
          Scan this QR code with your authenticator app (Google Authenticator, Authy, 1Password…).
        </p>
        <div class="qr-wrapper">
          <canvas #qrCanvas id="2fa-qr"></canvas>
          <p class="secret-text">{{ otpauthUri() }}</p>
        </div>
        <label>
          Verification code (from your app)
          <input
            type="text"
            inputmode="numeric"
            maxlength="6"
            [(ngModel)]="confirmCode"
            placeholder="000000"
          />
        </label>
        @if (error(); as e) { <p class="error">{{ e }}</p> }
        <div class="row">
          <button class="secondary" (click)="cancelSetup()">Cancel</button>
          <button class="primary" [disabled]="busy() || confirmCode.length < 6" (click)="finishSetup()">
            {{ busy() ? 'Activating…' : 'Activate 2FA' }}
          </button>
        </div>
      }

      @if (phase() === 'show-codes') {
        <div class="codes-banner">
          <p><strong>Save your backup codes.</strong> Each code works once. Store them somewhere safe — this is the only time they're shown.</p>
          <ol>
            @for (code of backupCodes(); track code) { <li><code>{{ code }}</code></li> }
          </ol>
          <button class="primary" (click)="done()">I've saved them</button>
        </div>
      }

      @if (phase() === 'regenerating') {
        <label>
          Enter your current TOTP code to regenerate backup codes
          <input type="text" inputmode="numeric" maxlength="6" [(ngModel)]="confirmCode" placeholder="000000" />
        </label>
        @if (error(); as e) { <p class="error">{{ e }}</p> }
        <div class="row">
          <button class="secondary" (click)="phase.set('idle'); confirmCode = ''">Cancel</button>
          <button class="primary" [disabled]="busy() || confirmCode.length < 6" (click)="regenerate()">
            {{ busy() ? 'Generating…' : 'Generate new codes' }}
          </button>
        </div>
      }

      @if (phase() === 'confirm') {
        <label>
          Enter your current TOTP code to disable 2FA
          <input type="text" inputmode="numeric" maxlength="6" [(ngModel)]="confirmCode" placeholder="000000" />
        </label>
        @if (error(); as e) { <p class="error">{{ e }}</p> }
        <div class="row">
          <button class="secondary" (click)="phase.set('idle'); confirmCode = ''">Cancel</button>
          <button class="danger" [disabled]="busy() || confirmCode.length < 6" (click)="disable()">
            {{ busy() ? 'Disabling…' : 'Disable 2FA' }}
          </button>
        </div>
      }
    </section>
  `,
  styles: [`
    .card { background: var(--vd-bg-card); border: 1px solid var(--vd-border); border-radius: 12px; padding: 1.5rem; }
    h2 { margin: 0 0 0.25rem; font-size: 1.05rem; color: var(--vd-fg-muted); }
    .hint { margin: 0 0 1.25rem; color: var(--vd-fg-muted); font-size: 0.875rem; }
    .status { margin: 0 0 1rem; }
    .actions { display: flex; gap: 1rem; }
    .setup-hint { margin-bottom: 1rem; font-size: 0.9rem; }
    .qr-wrapper { margin-bottom: 1rem; }
    .secret-text { font-size: 0.7rem; color: var(--vd-fg-muted); word-break: break-all; margin: 0.5rem 0; }
    label { display: grid; gap: 0.35rem; font-size: 0.875rem; color: var(--vd-fg-muted); margin-bottom: 1rem; }
    input { padding: 0.65rem 0.75rem; background: var(--vd-bg); border: 1px solid var(--vd-border); border-radius: 6px; color: var(--vd-fg); font: inherit; letter-spacing: 0.25em; }
    input:focus { outline: none; border-color: var(--vd-accent); }
    .row { display: flex; gap: 1rem; }
    button { padding: 0.65rem 1.1rem; border-radius: 6px; cursor: pointer; font: inherit; font-weight: 600; border: 0; }
    .primary { background: var(--vd-accent); color: #0e1116; }
    .secondary { background: transparent; border: 1px solid var(--vd-border); color: var(--vd-fg); }
    .danger { background: var(--vd-danger); color: #fff; }
    .danger-link, .link { background: transparent; border: 0; color: var(--vd-fg-muted); cursor: pointer; font: inherit; padding: 0; text-decoration: underline; }
    .danger-link { color: var(--vd-danger); }
    button[disabled] { opacity: 0.6; cursor: progress; }
    .codes-banner { background: var(--vd-bg); border: 1px solid var(--vd-border); border-radius: 8px; padding: 1rem; margin-bottom: 1rem; }
    .codes-banner ol { padding-left: 1.5rem; }
    .codes-banner code { font-size: 1rem; letter-spacing: 0.12em; }
    .error { color: var(--vd-danger); font-size: 0.875rem; margin: 0 0 1rem; }
  `],
})
export class TwoFactorSettingsComponent {
  private readonly http = inject(HttpClient);

  readonly phase = signal<Phase>('idle');
  readonly enabled = signal(false);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly otpauthUri = signal('');
  readonly backupCodes = signal<string[]>([]);
  confirmCode = '';

  constructor() {
    this.loadStatus();
  }

  private loadStatus(): void {
    this.http.get<{ remaining: number; total: number }>('/api/v1/auth/2fa/backup-codes')
      .pipe(catchError(err => { this.enabled.set(false); throw err; }))
      .subscribe({ next: () => this.enabled.set(true), error: () => {} });
  }

  startSetup(): void {
    this.busy.set(true);
    this.error.set(null);
    this.http.post<{ otpauthUri: string }>('/api/v1/auth/2fa/setup/begin', {})
      .pipe(catchError(toApiError))
      .subscribe({
        next: (res) => {
          this.busy.set(false);
          this.otpauthUri.set(res.otpauthUri);
          this.phase.set('setup');
        },
        error: (e: ApiError) => { this.busy.set(false); this.error.set(e.message); },
      });
  }

  cancelSetup(): void {
    this.phase.set('idle');
    this.otpauthUri.set('');
    this.confirmCode = '';
    this.error.set(null);
  }

  finishSetup(): void {
    this.busy.set(true);
    this.error.set(null);
    this.http.post<{ codes: string[] }>('/api/v1/auth/2fa/setup/finish', { code: this.confirmCode })
      .pipe(catchError(toApiError))
      .subscribe({
        next: (res) => {
          this.busy.set(false);
          this.enabled.set(true);
          this.backupCodes.set(res.codes);
          this.confirmCode = '';
          this.phase.set('show-codes');
        },
        error: (e: ApiError) => { this.busy.set(false); this.error.set(e.message); },
      });
  }

  done(): void {
    this.phase.set('idle');
    this.backupCodes.set([]);
  }

  startDisable(): void {
    this.confirmCode = '';
    this.error.set(null);
    this.phase.set('confirm');
  }

  disable(): void {
    this.busy.set(true);
    this.error.set(null);
    this.http.delete('/api/v1/auth/2fa', { body: { code: this.confirmCode } })
      .pipe(catchError(toApiError))
      .subscribe({
        next: () => {
          this.busy.set(false);
          this.enabled.set(false);
          this.confirmCode = '';
          this.phase.set('idle');
        },
        error: (e: ApiError) => { this.busy.set(false); this.error.set(e.message); },
      });
  }

  regenerate(): void {
    this.busy.set(true);
    this.error.set(null);
    this.http.post<{ codes: string[] }>('/api/v1/auth/2fa/backup-codes/regenerate', { code: this.confirmCode })
      .pipe(catchError(toApiError))
      .subscribe({
        next: (res) => {
          this.busy.set(false);
          this.backupCodes.set(res.codes);
          this.confirmCode = '';
          this.phase.set('show-codes');
        },
        error: (e: ApiError) => { this.busy.set(false); this.error.set(e.message); },
      });
  }
}
