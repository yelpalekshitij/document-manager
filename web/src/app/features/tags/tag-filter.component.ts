import { Component, inject, output } from '@angular/core';

import { DocumentsStore } from '../documents/documents.store';
import { TagsStore } from './tags.store';

/**
 * Sidebar tag filter. Each tag is a toggle button (`aria-pressed`); selected
 * tags combine with AND semantics and with the folder + search query.
 */
@Component({
  selector: 'vd-tag-filter',
  standalone: true,
  template: `
    <section class="tag-filter" aria-labelledby="tag-filter-heading">
      <div class="head">
        <h2 id="tag-filter-heading">Tags</h2>
        <div class="actions">
          @if (docs.selectedTagIds().length > 0) {
            <button type="button" class="link clear" aria-label="Clear tag filter" (click)="docs.clearTagFilter()">
              Clear
            </button>
          }
          <button type="button" class="link manage" (click)="manage.emit()">Manage</button>
        </div>
      </div>

      @if (tags.loading() && !tags.loaded()) {
        <p class="muted">Loading tags…</p>
      } @else if (tags.error() && !tags.loaded()) {
        <p class="error" role="alert">
          {{ tags.error() }}
          <button type="button" class="link retry" (click)="tags.load()">Retry</button>
        </p>
      } @else if (tags.tags().length === 0) {
        <p class="muted empty">No tags yet. Use “Manage” to create one, then add it to documents.</p>
      } @else {
        <ul class="list">
          @for (t of tags.tags(); track t.id) {
            <li>
              <button
                type="button"
                class="toggle"
                [class.selected]="isSelected(t.id)"
                [attr.aria-pressed]="isSelected(t.id)"
                (click)="docs.toggleTag(t.id)"
              >
                <span class="dot" aria-hidden="true" [style.background]="t.color ?? null"></span>
                <span class="name">{{ t.name }}</span>
                <span class="count" [attr.aria-label]="t.documentCount + ' documents'">{{ t.documentCount }}</span>
              </button>
            </li>
          }
        </ul>
        @if (docs.selectedTagIds().length > 1) {
          <p class="hint">Showing documents with all selected tags.</p>
        }
      }
    </section>
  `,
  styles: [
    `
      .tag-filter { margin-top: 1rem; padding: 0 0.25rem; }
      .head { display: flex; align-items: center; justify-content: space-between; padding: 0 0.5rem; }
      h2 {
        margin: 0; font-size: 0.75rem; text-transform: uppercase;
        letter-spacing: 0.04em; color: var(--vd-fg-muted);
      }
      .actions { display: flex; gap: 0.25rem; }
      .link {
        background: transparent; border: none; color: var(--vd-fg-muted);
        font: inherit; font-size: 0.78rem; cursor: pointer; padding: 0.2rem 0.3rem;
      }
      .link:hover, .link:focus-visible { color: var(--vd-accent); }
      .list { list-style: none; margin: 0.35rem 0 0; padding: 0; display: flex; flex-direction: column; gap: 0.1rem; }
      .toggle {
        all: unset; box-sizing: border-box; width: 100%;
        display: flex; align-items: center; gap: 0.5rem;
        padding: 0.35rem 0.75rem; border-radius: 6px; cursor: pointer; font-size: 0.88rem;
      }
      .toggle:hover { background: rgba(255, 255, 255, 0.03); }
      .toggle:focus-visible { outline: 2px solid var(--vd-accent); outline-offset: -2px; }
      .toggle.selected { background: rgba(88, 166, 255, 0.12); color: var(--vd-accent); }
      .dot { width: 0.55rem; height: 0.55rem; border-radius: 50%; background: var(--vd-fg-muted); flex: none; }
      .name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
      .count { font-size: 0.72rem; color: var(--vd-fg-muted); }
      .muted, .hint { color: var(--vd-fg-muted); font-size: 0.8rem; padding: 0 0.5rem; }
      .error { color: var(--vd-danger); font-size: 0.8rem; padding: 0 0.5rem; }
    `,
  ],
})
export class TagFilterComponent {
  /** The user asked to open the tag manager. */
  readonly manage = output<void>();

  protected readonly tags = inject(TagsStore);
  protected readonly docs = inject(DocumentsStore);

  constructor() {
    if (!this.tags.loaded() && !this.tags.loading()) {
      void this.tags.load();
    }
  }

  protected isSelected(id: string): boolean {
    return this.docs.selectedTagIds().includes(id);
  }
}
