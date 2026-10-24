import { Component, computed, inject, input, output, signal } from '@angular/core';

import { TagRef } from '../../core/api/types';
import { DocumentsStore } from '../documents/documents.store';
import { TagsStore } from './tags.store';

/**
 * A document's tags as chips. In editable mode each chip gets a labelled
 * remove button and a select offers the tags not yet attached — native
 * controls, so keyboard and screen-reader support come for free.
 */
@Component({
  selector: 'vd-tag-chips',
  standalone: true,
  template: `
    <div class="chips" (click)="editable() && $event.stopPropagation()">
      @if (tags().length > 0) {
        <ul class="list" aria-label="Tags">
          @for (t of tags(); track t.id) {
            <li class="chip" [style.border-color]="t.color ?? null">
              <span class="dot" aria-hidden="true" [style.background]="t.color ?? null"></span>
              <span class="label">{{ t.name }}</span>
              @if (editable()) {
                <button
                  type="button"
                  class="remove"
                  [attr.aria-label]="'Remove tag ' + t.name"
                  [disabled]="pending()"
                  (click)="remove(t)"
                >×</button>
              }
            </li>
          }
        </ul>
      }
      @if (editable()) {
        <select
          class="add"
          aria-label="Add tag"
          [disabled]="pending() || available().length === 0"
          (change)="onAdd($event)"
        >
          <option value="">{{ available().length === 0 ? 'No more tags' : '+ Add tag' }}</option>
          @for (t of available(); track t.id) {
            <option [value]="t.id">{{ t.name }}</option>
          }
        </select>
        @if (error()) {
          <p class="error" role="alert">{{ error() }}</p>
        }
      }
    </div>
  `,
  styles: [
    `
      .chips { display: flex; flex-wrap: wrap; align-items: center; gap: 0.3rem; }
      .list { display: flex; flex-wrap: wrap; gap: 0.3rem; list-style: none; margin: 0; padding: 0; }
      .chip {
        display: inline-flex; align-items: center; gap: 0.3rem;
        border: 1px solid var(--vd-border); border-radius: 999px;
        padding: 0.05rem 0.5rem; font-size: 0.75rem; color: var(--vd-fg);
      }
      .dot { width: 0.5rem; height: 0.5rem; border-radius: 50%; background: var(--vd-fg-muted); }
      .remove {
        background: transparent; border: none; color: var(--vd-fg-muted);
        cursor: pointer; padding: 0 0.1rem; font: inherit; line-height: 1;
      }
      .remove:hover, .remove:focus-visible { color: var(--vd-danger); }
      .add {
        background: var(--vd-bg); color: var(--vd-fg-muted);
        border: 1px dashed var(--vd-border); border-radius: 999px;
        font: inherit; font-size: 0.75rem; padding: 0.05rem 0.4rem;
      }
      .error { flex-basis: 100%; margin: 0.2rem 0 0; color: var(--vd-danger); font-size: 0.75rem; }
    `,
  ],
})
export class TagChipsComponent {
  readonly documentId = input.required<string>();
  readonly tags = input<TagRef[]>([]);
  readonly editable = input(false);
  /** The document's tags after a successful add/remove. */
  readonly changed = output<TagRef[]>();

  private readonly docs = inject(DocumentsStore);
  private readonly tagsStore = inject(TagsStore);

  protected readonly pending = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly available = computed(() => {
    const attached = new Set(this.tags().map((t) => t.id));
    return this.tagsStore.tags().filter((t) => !attached.has(t.id));
  });

  async remove(tag: TagRef): Promise<void> {
    await this.apply(() => this.docs.removeTag(this.documentId(), tag.id));
  }

  async onAdd(event: Event): Promise<void> {
    const select = event.target as HTMLSelectElement;
    const tagId = select.value;
    select.value = '';
    if (!tagId) return;
    await this.apply(() => this.docs.addTag(this.documentId(), tagId));
  }

  private async apply(op: () => Promise<TagRef[] | null>): Promise<void> {
    this.pending.set(true);
    this.error.set(null);
    try {
      const result = await op();
      if (result) {
        this.changed.emit(result);
      } else {
        this.error.set(this.docs.tagError() ?? 'Could not update tags.');
      }
    } finally {
      this.pending.set(false);
    }
  }
}
