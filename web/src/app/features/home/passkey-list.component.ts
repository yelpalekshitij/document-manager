import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';

import { PasskeyStore } from '../../core/passkey/passkey.store';

@Component({
  selector: 'vd-passkey-list',
  standalone: true,
  imports: [DatePipe],
  template: `
    <section class="card">
      <header>
        <div>
          <h2>Passkeys</h2>
          <p class="hint">Sign in without a password from any of your devices.</p>
        </div>
        @if (store.browserSupportsWebAuthn) {
          <button class="primary" [disabled]="store.busy()" (click)="add()">
            {{ store.busy() && pending() === 'add' ? 'Waiting…' : 'Add a passkey' }}
          </button>
        }
      </header>

      @if (!store.browserSupportsWebAuthn) {
        <p class="muted">This browser doesn't support WebAuthn.</p>
      } @else if (store.loading()) {
        <p>Loading…</p>
      } @else if (store.passkeys() === null) {
        <p>—</p>
      } @else if (store.passkeys()!.length === 0) {
        <p class="muted">No passkeys yet. Add one to sign in faster next time.</p>
      } @else {
        <ul>
          @for (pk of store.passkeys(); track pk.id) {
            <li>
              <div>
                <strong>{{ pk.label }}</strong>
                <span class="meta">
                  added {{ pk.createdAt | date: 'mediumDate' }}
                  @if (pk.lastUsedAt) { · used {{ pk.lastUsedAt | date: 'shortDate' }} }
                </span>
              </div>
              <button class="link" [disabled]="store.busy()" (click)="remove(pk.id)">Remove</button>
            </li>
          }
        </ul>
      }

      @if (store.error(); as e) {
        <p class="error">{{ e }}</p>
      }
    </section>
  `,
  styles: [
    `
      .card {
        background: var(--vd-bg-card);
        border: 1px solid var(--vd-border);
        border-radius: 12px;
        padding: 1.5rem;
      }
      header {
        display: flex;
        justify-content: space-between;
        align-items: flex-start;
        gap: 1rem;
        margin-bottom: 1rem;
      }
      h2 { margin: 0; font-size: 1.05rem; color: var(--vd-fg-muted); }
      .hint { margin: 0.25rem 0 0; color: var(--vd-fg-muted); font-size: 0.85rem; }
      .muted { color: var(--vd-fg-muted); margin: 0; }
      ul { list-style: none; margin: 0; padding: 0; display: grid; gap: 0.75rem; }
      li {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: 0.75rem 0.9rem;
        background: var(--vd-bg);
        border: 1px solid var(--vd-border);
        border-radius: 8px;
      }
      .meta { display: block; color: var(--vd-fg-muted); font-size: 0.75rem; }
      .primary {
        background: var(--vd-accent);
        color: #0e1116;
        border: 0;
        padding: 0.5rem 0.9rem;
        border-radius: 6px;
        cursor: pointer;
        font: inherit;
        font-weight: 600;
      }
      .primary[disabled] { opacity: 0.6; cursor: progress; }
      .link {
        background: transparent;
        color: var(--vd-fg-muted);
        border: 0;
        cursor: pointer;
        font: inherit;
        font-size: 0.85rem;
      }
      .link:hover { color: var(--vd-danger); }
      .error { color: var(--vd-danger); margin: 1rem 0 0; font-size: 0.875rem; }
    `,
  ],
})
export class PasskeyListComponent implements OnInit {
  protected readonly store = inject(PasskeyStore);
  readonly pending = signal<'add' | 'remove' | null>(null);

  ngOnInit(): void {
    this.store.refresh();
  }

  async add(): Promise<void> {
    this.pending.set('add');
    try {
      const label = window.prompt('Name this passkey (optional)') ?? undefined;
      await this.store.addPasskey(label?.trim() || undefined);
    } finally {
      this.pending.set(null);
    }
  }

  async remove(id: string): Promise<void> {
    if (!window.confirm('Remove this passkey? You can re-add it later.')) return;
    this.pending.set('remove');
    try {
      await this.store.deletePasskey(id);
    } finally {
      this.pending.set(null);
    }
  }
}
