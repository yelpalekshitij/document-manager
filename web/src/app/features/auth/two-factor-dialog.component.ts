import { Component, EventEmitter, Input, Output, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { catchError, firstValueFrom } from 'rxjs';

import { TwoFactorService } from '../../core/api/generated';
import { toApiError } from '../../core/api/http-error';
import { ApiError } from '../../core/api/types';
import { AuthStore } from '../../core/auth/auth.store';

@Component({
  selector: 'vd-two-factor-dialog',
  standalone: true,
  imports: [FormsModule],
  template: `
    <div class="overlay" (click)="cancel.emit()">
      <div class="dialog" (click)="$event.stopPropagation()">
        <h2>Two-factor authentication required</h2>

        @if (!useBackup()) {
          <p>Enter the 6-digit code from your authenticator app.</p>
          <label>
            TOTP code
            <input
              type="text"
              inputmode="numeric"
              maxlength="6"
              [(ngModel)]="code"
              placeholder="000000"
              autofocus
            />
          </label>
        } @else {
          <p>Enter one of your backup codes.</p>
          <label>
            Backup code
            <input type="text" [(ngModel)]="code" placeholder="XXX-XXX-XXX" />
          </label>
        }

        @if (error(); as e) { <p class="error">{{ e }}</p> }

        <div class="actions">
          <button type="button" class="link" (click)="useBackup.set(!useBackup())">
            {{ useBackup() ? '← Use TOTP code' : 'Use a backup code instead' }}
          </button>
          <div class="row">
            <button type="button" class="secondary" (click)="cancel.emit()">Cancel</button>
            <button type="button" class="primary" [disabled]="busy() || code.length < 3" (click)="submit()">
              {{ busy() ? 'Verifying…' : 'Verify' }}
            </button>
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.65); display: grid; place-items: center; z-index: 1000; }
    .dialog { background: var(--vd-bg-card); border: 1px solid var(--vd-border); border-radius: 12px; padding: 2rem; width: min(420px, calc(100vw - 2rem)); }
    h2 { margin: 0 0 0.5rem; font-size: 1.2rem; }
    p { margin: 0 0 1rem; color: var(--vd-fg-muted); font-size: 0.9rem; }
    label { display: grid; gap: 0.35rem; font-size: 0.875rem; color: var(--vd-fg-muted); margin-bottom: 1rem; }
    input { padding: 0.65rem 0.75rem; background: var(--vd-bg); border: 1px solid var(--vd-border); border-radius: 6px; color: var(--vd-fg); font: inherit; letter-spacing: 0.2em; }
    input:focus { outline: none; border-color: var(--vd-accent); }
    .actions { display: grid; gap: 0.75rem; }
    .row { display: flex; justify-content: flex-end; gap: 0.75rem; }
    button { padding: 0.65rem 1.1rem; border-radius: 6px; cursor: pointer; font: inherit; font-weight: 600; border: 0; }
    .primary { background: var(--vd-accent); color: #0e1116; }
    .secondary { background: transparent; border: 1px solid var(--vd-border); color: var(--vd-fg); }
    .link { background: transparent; border: 0; color: var(--vd-fg-muted); cursor: pointer; font: inherit; font-size: 0.85rem; padding: 0; text-decoration: underline; }
    button[disabled] { opacity: 0.6; cursor: progress; }
    .error { color: var(--vd-danger); font-size: 0.875rem; margin: 0 0 0.75rem; }
  `],
})
export class TwoFactorDialogComponent {
  @Input({ required: true }) twoFactorToken!: string;
  @Output() readonly cancel = new EventEmitter<void>();
  @Output() readonly success = new EventEmitter<void>();

  private readonly api = inject(TwoFactorService);
  private readonly authStore = inject(AuthStore);

  code = '';
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly useBackup = signal(false);

  async submit(): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      const session = this.useBackup()
        ? await firstValueFrom(
            this.api.verify2faBackup({
              twoFactorToken: this.twoFactorToken,
              backupCode: this.code,
            }).pipe(catchError(toApiError)))
        : await firstValueFrom(
            this.api.verify2fa({
              twoFactorToken: this.twoFactorToken,
              code: this.code,
            }).pipe(catchError(toApiError)));
      this.authStore.adoptSession(session);
      this.success.emit();
    } catch (e) {
      this.error.set(e instanceof ApiError ? (e.message ?? 'Verification failed.') : 'Verification failed.');
    } finally {
      this.busy.set(false);
    }
  }
}
