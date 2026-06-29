import { Injectable, computed, inject, signal } from '@angular/core';

import { PasskeyClient } from './passkey.client';
import { AuthResponse, PasskeySummary } from '../api/types';
import { AuthStore } from '../auth/auth.store';

@Injectable({ providedIn: 'root' })
export class PasskeyStore {
  private readonly client = inject(PasskeyClient);
  private readonly auth = inject(AuthStore);

  private readonly _passkeys = signal<PasskeySummary[] | null>(null);
  private readonly _loading = signal(false);
  private readonly _busy = signal(false);
  private readonly _error = signal<string | null>(null);

  readonly passkeys = this._passkeys.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly busy = this._busy.asReadonly();
  readonly error = this._error.asReadonly();
  readonly browserSupportsWebAuthn = this.client.browserSupportsWebAuthn;
  readonly count = computed(() => this._passkeys()?.length ?? 0);

  async refresh(): Promise<void> {
    this._loading.set(true);
    this._error.set(null);
    try {
      this._passkeys.set(await this.client.list());
    } catch (e) {
      this._error.set(messageOf(e));
    } finally {
      this._loading.set(false);
    }
  }

  async addPasskey(label?: string): Promise<PasskeySummary | null> {
    this._busy.set(true);
    this._error.set(null);
    try {
      const summary = await this.client.addPasskey(label);
      const current = this._passkeys() ?? [];
      this._passkeys.set([summary, ...current]);
      return summary;
    } catch (e) {
      this._error.set(messageOf(e));
      return null;
    } finally {
      this._busy.set(false);
    }
  }

  async signInWithPasskey(): Promise<AuthResponse | null> {
    this._busy.set(true);
    this._error.set(null);
    try {
      const session = await this.client.signIn();
      this.auth.adoptSession(session);
      return session;
    } catch (e) {
      this._error.set(messageOf(e));
      return null;
    } finally {
      this._busy.set(false);
    }
  }

  async deletePasskey(id: string): Promise<void> {
    this._busy.set(true);
    this._error.set(null);
    try {
      await this.client.delete(id);
      const current = this._passkeys() ?? [];
      this._passkeys.set(current.filter((p) => p.id !== id));
    } catch (e) {
      this._error.set(messageOf(e));
    } finally {
      this._busy.set(false);
    }
  }

  clearError(): void {
    this._error.set(null);
  }
}

function messageOf(e: unknown): string {
  if (e instanceof Error) {
    if (e.name === 'NotAllowedError') return 'Passkey prompt was cancelled or timed out.';
    if (e.name === 'InvalidStateError') return 'This authenticator is already registered.';
    return e.message;
  }
  return 'Passkey operation failed.';
}
