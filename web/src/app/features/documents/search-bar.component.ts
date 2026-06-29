import { Component, inject, signal } from '@angular/core';

import { DocumentsStore } from './documents.store';

@Component({
  selector: 'vd-search-bar',
  standalone: true,
  template: `
    <div class="bar">
      <input
        type="search"
        placeholder="Search filename, type, tag…"
        [value]="value()"
        (input)="onInput($event)"
        (keyup.escape)="clear()"
      />
      @if (value().length > 0) {
        <button type="button" class="clear" (click)="clear()" aria-label="Clear">×</button>
      }
    </div>
  `,
  styles: [
    `
      .bar {
        display: flex; align-items: center; gap: 0.4rem;
        background: var(--vd-bg-card);
        border: 1px solid var(--vd-border);
        border-radius: 8px;
        padding: 0.4rem 0.7rem;
      }
      input {
        flex: 1;
        background: transparent;
        border: none;
        color: var(--vd-fg);
        font: inherit;
        outline: none;
      }
      .clear {
        background: transparent; border: none; color: var(--vd-fg-muted);
        font-size: 1.1rem; cursor: pointer; line-height: 1; padding: 0 0.25rem;
      }
    `,
  ],
})
export class SearchBarComponent {
  protected readonly value = signal('');

  private readonly store = inject(DocumentsStore);
  private debounceHandle: ReturnType<typeof setTimeout> | null = null;

  onInput(event: Event): void {
    const v = (event.target as HTMLInputElement).value;
    this.value.set(v);
    if (this.debounceHandle) clearTimeout(this.debounceHandle);
    this.debounceHandle = setTimeout(() => void this.store.runSearch(v), 200);
  }

  clear(): void {
    this.value.set('');
    if (this.debounceHandle) clearTimeout(this.debounceHandle);
    void this.store.runSearch('');
  }
}
