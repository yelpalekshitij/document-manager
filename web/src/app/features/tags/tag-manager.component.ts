import {
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  inject,
  output,
  signal,
  viewChild,
} from '@angular/core';

import { Tag } from '../../core/api/types';
import { DocumentsStore } from '../documents/documents.store';
import { TAG_COLORS, TagsStore, normalizeTagName } from './tags.store';

/**
 * Modal for tag CRUD: create (name + colour), inline rename, recolor, and
 * delete behind an in-app confirmation (no window.confirm — it's untestable
 * and blocked in some embeds). Escape closes the dialog, or cancels the
 * rename / confirmation in progress.
 */
@Component({
  selector: 'vd-tag-manager',
  standalone: true,
  template: `
    <div class="overlay" (click)="onBackdrop($event)">
      <section
        class="panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="tag-manager-title"
        (keydown)="onKeydown($event)"
      >
        <header>
          <h2 id="tag-manager-title">Manage tags</h2>
          <button type="button" class="close" aria-label="Close" (click)="close.emit()">×</button>
        </header>

        <form class="create" (submit)="onCreate($event)">
          <div class="field">
            <label for="new-tag-name">Name</label>
            <input
              #nameInput
              id="new-tag-name"
              type="text"
              autocomplete="off"
              [value]="newName()"
              (input)="newName.set($any($event.target).value)"
            />
          </div>
          <div class="field">
            <label for="new-tag-color">Colour</label>
            <select id="new-tag-color" (change)="newColor.set($any($event.target).value)">
              <option value="" [selected]="newColor() === ''">None</option>
              @for (c of colors; track c.value) {
                <option [value]="c.value" [selected]="newColor() === c.value">{{ c.label }}</option>
              }
            </select>
          </div>
          <button type="submit" class="primary" [disabled]="!canCreate()">Add tag</button>
        </form>

        @if (tags.error()) {
          <p class="error" role="alert">{{ tags.error() }}</p>
        }

        @if (tags.tags().length === 0) {
          <p class="muted empty">No tags yet. Create one above, then add it to documents from the list or viewer.</p>
        } @else {
          <ul class="rows">
            @for (t of tags.tags(); track t.id) {
              <li class="row">
                <div class="line">
                  <span class="dot" aria-hidden="true" [style.background]="t.color ?? null"></span>
                  @if (editingId() === t.id) {
                    <form class="rename-form" (submit)="onRename($event, t)">
                      <input
                        class="rename-input"
                        type="text"
                        autocomplete="off"
                        [attr.aria-label]="'New name for ' + t.name"
                        [value]="editName()"
                        (input)="editName.set($any($event.target).value)"
                        (keydown)="onRenameKeydown($event)"
                      />
                      <button type="submit" class="small" [disabled]="tags.busy()">Save</button>
                      <button type="button" class="small link" (click)="cancelRename()">Cancel</button>
                    </form>
                  } @else {
                    <span class="name">{{ t.name }}</span>
                    <span class="count">{{ t.documentCount }} {{ t.documentCount === 1 ? 'doc' : 'docs' }}</span>
                    <select class="recolor" [attr.aria-label]="'Colour for ' + t.name" (change)="onRecolor($event, t)">
                      <option value="" [selected]="!t.color">None</option>
                      @if (t.color && !isPreset(t.color)) {
                        <option [value]="t.color" selected>Custom ({{ t.color }})</option>
                      }
                      @for (c of colors; track c.value) {
                        <option [value]="c.value" [selected]="t.color === c.value">{{ c.label }}</option>
                      }
                    </select>
                    <button type="button" class="small rename" (click)="startRename(t)">Rename</button>
                    <button type="button" class="small delete" (click)="confirmingId.set(t.id)">Delete</button>
                  }
                </div>
                @if (confirmingId() === t.id) {
                  <div class="confirm" role="alertdialog" [attr.aria-label]="'Confirm deleting ' + t.name">
                    <p>{{ deleteMessage(t) }}</p>
                    <div class="confirm-actions">
                      <button type="button" class="small danger confirm" [disabled]="tags.busy()" (click)="onDelete(t)">
                        Delete
                      </button>
                      <button type="button" class="small link cancel" (click)="confirmingId.set(null)">Cancel</button>
                    </div>
                  </div>
                }
              </li>
            }
          </ul>
        }
      </section>
    </div>
  `,
  styles: [
    `
      .overlay {
        position: fixed; inset: 0; background: rgba(0, 0, 0, 0.55);
        display: flex; align-items: center; justify-content: center; z-index: 110;
      }
      .panel {
        background: var(--vd-bg-card); border: 1px solid var(--vd-border); border-radius: 12px;
        width: min(94vw, 560px); max-height: 86vh; overflow: auto; padding: 1rem 1.25rem;
        display: flex; flex-direction: column; gap: 0.85rem;
      }
      header { display: flex; justify-content: space-between; align-items: center; }
      h2 { margin: 0; font-size: 1.05rem; }
      .close { background: transparent; border: none; color: var(--vd-fg); font-size: 1.4rem; cursor: pointer; }
      .create { display: flex; flex-wrap: wrap; gap: 0.6rem; align-items: flex-end; }
      .field { display: flex; flex-direction: column; gap: 0.2rem; }
      .field:first-child { flex: 1; min-width: 12rem; }
      label { font-size: 0.75rem; color: var(--vd-fg-muted); }
      input, select {
        background: var(--vd-bg); border: 1px solid var(--vd-border); color: var(--vd-fg);
        padding: 0.35rem 0.55rem; border-radius: 6px; font: inherit;
      }
      input:focus-visible, select:focus-visible, button:focus-visible { outline: 2px solid var(--vd-accent); outline-offset: 1px; }
      .primary {
        background: var(--vd-accent); color: #0e1116; border: none; border-radius: 6px;
        padding: 0.45rem 0.9rem; font: inherit; cursor: pointer;
      }
      .primary:disabled { opacity: 0.5; cursor: not-allowed; }
      .rows { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; }
      .row { border-top: 1px solid var(--vd-border); padding: 0.45rem 0; }
      .line { display: flex; align-items: center; gap: 0.5rem; flex-wrap: wrap; }
      .dot { width: 0.6rem; height: 0.6rem; border-radius: 50%; background: var(--vd-fg-muted); flex: none; }
      .name { flex: 1; min-width: 6rem; overflow-wrap: anywhere; }
      .count { color: var(--vd-fg-muted); font-size: 0.75rem; }
      .rename-form { display: flex; flex: 1; gap: 0.4rem; }
      .rename-input { flex: 1; }
      .small {
        background: transparent; border: 1px solid var(--vd-border); color: var(--vd-fg);
        border-radius: 5px; padding: 0.2rem 0.55rem; font: inherit; font-size: 0.8rem; cursor: pointer;
      }
      .small.link { border-color: transparent; color: var(--vd-fg-muted); }
      .delete, .danger { color: var(--vd-danger); }
      .danger { border-color: var(--vd-danger); }
      .confirm { margin-top: 0.4rem; padding: 0.5rem 0.75rem; border: 1px solid var(--vd-danger); border-radius: 8px; }
      .confirm p { margin: 0 0 0.4rem; font-size: 0.85rem; }
      .confirm-actions { display: flex; gap: 0.4rem; }
      .muted { color: var(--vd-fg-muted); font-size: 0.85rem; }
      .error { color: var(--vd-danger); font-size: 0.85rem; margin: 0; }
    `,
  ],
})
export class TagManagerComponent {
  readonly close = output<void>();

  protected readonly tags = inject(TagsStore);
  private readonly docs = inject(DocumentsStore);
  private readonly injector = inject(Injector);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly nameInput = viewChild<ElementRef<HTMLInputElement>>('nameInput');

  protected readonly colors = TAG_COLORS;
  protected readonly newName = signal('');
  protected readonly newColor = signal('');
  protected readonly editingId = signal<string | null>(null);
  protected readonly editName = signal('');
  protected readonly confirmingId = signal<string | null>(null);
  protected readonly canCreate = computed(() => normalizeTagName(this.newName()).length > 0 && !this.tags.busy());

  constructor() {
    this.tags.clearError();
    afterNextRender(() => this.nameInput()?.nativeElement.focus());
  }

  protected deleteMessage(tag: Tag): string {
    const docs = tag.documentCount === 1 ? 'document' : 'documents';
    return `Delete “${tag.name}”? It will be removed from ${tag.documentCount} ${docs}. The documents themselves stay.`;
  }

  protected isPreset(color: string): boolean {
    return TAG_COLORS.some((c) => c.value === color);
  }

  async onCreate(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.canCreate()) return;
    const created = await this.tags.create(this.newName(), this.newColor() || null);
    if (created) {
      this.newName.set('');
      this.newColor.set('');
    }
  }

  startRename(tag: Tag): void {
    this.confirmingId.set(null);
    this.editingId.set(tag.id);
    this.editName.set(tag.name);
    afterNextRender(
      () => this.host.nativeElement.querySelector<HTMLInputElement>('input.rename-input')?.focus(),
      { injector: this.injector },
    );
  }

  cancelRename(): void {
    this.editingId.set(null);
    this.editName.set('');
  }

  async onRename(event: Event, tag: Tag): Promise<void> {
    event.preventDefault();
    if (normalizeTagName(this.editName()) === tag.name) {
      this.cancelRename();
      return;
    }
    const updated = await this.docs.updateTag(tag.id, { name: this.editName() });
    if (updated) this.cancelRename();
  }

  onRenameKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      // Cancel the rename only; don't let the dialog close.
      event.stopPropagation();
      this.cancelRename();
    }
  }

  async onRecolor(event: Event, tag: Tag): Promise<void> {
    const color = (event.target as HTMLSelectElement).value;
    await this.docs.updateTag(tag.id, { color });
  }

  async onDelete(tag: Tag): Promise<void> {
    const ok = await this.docs.deleteTag(tag.id);
    if (ok) this.confirmingId.set(null);
  }

  onKeydown(event: KeyboardEvent): void {
    if (event.key !== 'Escape') return;
    if (this.confirmingId() !== null) {
      this.confirmingId.set(null);
      return;
    }
    this.close.emit();
  }

  onBackdrop(event: MouseEvent): void {
    if (event.target === event.currentTarget) this.close.emit();
  }
}
